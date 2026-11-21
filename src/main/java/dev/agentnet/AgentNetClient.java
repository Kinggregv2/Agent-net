package dev.agentnet;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import dev.agentnet.bus.AgentBus;
import dev.agentnet.map.Servers;
import dev.agentnet.map.Spots;
import dev.agentnet.map.Farms;
import dev.agentnet.map.Pois;
import dev.agentnet.role.Role;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

public final class AgentNetClient implements ClientModInitializer {
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Pattern COUNT = Pattern.compile("\\d{1,3}");
    private static final int MAX_BATCH = 100;
    /** Spacing between joins when no duration is given. */
    private static final long DEFAULT_SPACING_MS = 250;
    /** "90", "90s", "5m", "1h", "2d", or combined like "1d12h". */
    private static final Pattern DURATION = Pattern.compile("(?:\\d+[dhms]?)+", Pattern.CASE_INSENSITIVE);
    private static final Pattern DURATION_PART = Pattern.compile("(\\d+)([dhms]?)", Pattern.CASE_INSENSITIVE);
    private static final long MAX_DURATION_MS = 30L * 24 * 60 * 60 * 1000;

    static final Map<String, Agent> AGENTS = new ConcurrentHashMap<>();
    /** How many agents this client runs (for the LAN player cap). */
    public static int agentCount() { return AGENTS.size(); }

    /** The main speedrunner (not a helper). */
    public static boolean isMainRunner(String name) {
        return false; // no speedrunner any more (the fighting crew, 2026-11-21)
    }

    /**
     * The role's name as shown in lists and screens: the main speedrunner and its helpers are told apart (the user,
     * 2026-11-20: "make the speedrunner and speedrun helper 2 different roles at least visually").
     */
    public static String roleName(Agent a) {
        if (a == null || a.role() == null) return "no role";
        return a.role().displayName;
    }

    /** The role color (0xRRGGBB) of one of our agents by name, or -1: names are drawn in it. */
    public static int roleColor(String name) {
        Agent a = name == null ? null : AGENTS.get(name.toLowerCase());
        if (a == null || a.role() == null) return -1;
        return a.role().color & 0xFFFFFF;
    }
    /** An agent moves to another role by itself (a falloff speedrunner home becomes a brewer): next tick, as "/agent role". */
    public static void changeRole(String name, String roleId) {
        net.minecraft.client.Minecraft.getInstance().execute(() -> MasterAgent.run("agent role " + name + " " + roleId));
    }

    /** One of our agents (with a role or not). */
    public static boolean isAgent(String name) { return name != null && AGENTS.containsKey(name.toLowerCase()); }
    private static boolean xaeroWorldMap;

    private record Scheduled(Agent agent, long at) {}
    /** Main thread only. */
    private static final PriorityQueue<Scheduled> PENDING_JOIN = new PriorityQueue<>(Comparator.comparingLong(Scheduled::at));
    private static final PriorityQueue<Scheduled> PENDING_LEAVE = new PriorityQueue<>(Comparator.comparingLong(Scheduled::at));

    private static boolean freshWorldDone;

    /**
     * Test launcher only: with -Dagentnet.newWorld=<name>, a brand-new survival world (normal difficulty, commands on,
     * random seed) is made and opened as soon as the title screen shows, the way vanilla's own debug world button does.
     * The folder gets a free name, so an old world is never overwritten.
     */
    private static void freshWorldAtStart(net.minecraft.client.Minecraft mc) {
        if (freshWorldDone) return;
        String name = System.getProperty("agentnet.newWorld");
        if (name == null || name.isBlank()) { freshWorldDone = true; return; }
        if (!(mc.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen)) return;
        freshWorldDone = true;
        try {
            var settings = new net.minecraft.world.level.LevelSettings(name, net.minecraft.world.level.GameType.SURVIVAL,
                    new net.minecraft.world.level.LevelSettings.DifficultySettings(net.minecraft.world.Difficulty.NORMAL, false, false),
                    true, net.minecraft.world.level.WorldDataConfiguration.DEFAULT);
            String folder = net.minecraft.util.FileUtil.findAvailableName(mc.getLevelSource().getBaseDir(), name, "");
            AgentNet.LOG.info("[AgentNet] making a fresh world '{}' (folder {})", name, folder);
            mc.createWorldOpenFlows().createFreshLevel(folder, settings, net.minecraft.world.level.levelgen.WorldOptions.defaultWithRandomSeed(),
                    net.minecraft.world.level.levelgen.presets.WorldPresets::createNormalWorldDimensions, mc.gui.screen());
        } catch (Exception e) { AgentNet.LOG.warn("couldn't make a fresh world", e); }
    }

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            freshWorldAtStart(mc);
            long now = System.currentTimeMillis();
            while (!PENDING_JOIN.isEmpty() && PENDING_JOIN.peek().at() <= now) {
                Agent a = PENDING_JOIN.poll().agent();
                if (!a.isClosed()) a.start();
            }
            while (!PENDING_LEAVE.isEmpty() && PENDING_LEAVE.peek().at() <= now) {
                Agent a = PENDING_LEAVE.poll().agent();
                if (!a.isClosed()) {
                    a.leave();
                    notify("§7[" + a.name + "] left (scheduled)");
                }
                AGENTS.remove(a.name.toLowerCase(), a);
            }
            for (Agent a : AGENTS.values()) {
                a.tick();
                if (a.isClosed()) AGENTS.remove(a.name.toLowerCase(), a);
            }
            AgentBus.flush();
            if (xaeroWorldMap) XaeroMapFeed.tick(mc);
            while (masterKey != null && masterKey.consumeClick()) openMaster = true;
            while (mapKey != null && mapKey.consumeClick()) openMap = true;
            if (openMap && mc.player != null && (mc.gui.screen() == null || mc.gui.screen() instanceof net.minecraft.client.gui.screens.ChatScreen)) {
                openMap = false;
                mc.gui.setScreen(new SupplyMapScreen());
            }
            if (openView && mc.player != null && (mc.gui.screen() == null || mc.gui.screen() instanceof net.minecraft.client.gui.screens.ChatScreen)) {
                openView = false;
                mc.gui.setScreen(new ViewScreen(null));
            }
            if (openMaster && mc.player != null && mc.gui.screen() == null || openMaster && mc.gui.screen() instanceof net.minecraft.client.gui.screens.ChatScreen) {
                openMaster = false;
                mc.gui.setScreen(new MasterScreen());
            }
            if (++warTicks % 40 == 0) checkWarTransition();
            if (warTicks % 40 == 0 && mc.player != null && mc.level != null && Servers.current() != null) {
                // Chunks around you count as explored too (explorers go where nobody has been).
                int pcx = mc.player.blockPosition().getX() >> 4, pcz = mc.player.blockPosition().getZ() >> 4;
                String dim = mc.level.dimension().identifier().toString();
                int r = Math.min(12, mc.options.getEffectiveRenderDistance());
                for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                    if (mc.level.getChunkSource().hasChunk(pcx + dx, pcz + dz)) dev.agentnet.map.Explored.add(Servers.current(), dim, pcx + dx, pcz + dz);
                }
            }
            if (warTicks % 1200 == 0) dev.agentnet.map.Explored.save();
            Civ.tick();
            Civ.tickPending();
            AfkChecker.tick();
            AutoTest.tick(mc);
        });
        AgentBus.agents = AGENTS::values;
        AgentBus.deliver = (to, m) -> { Agent a = AGENTS.get(to.toLowerCase()); if (a != null) a.deliver(m); };
        AgentBus.log = AgentFiles::botChat;
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> { AGENTS.values().forEach(Agent::leave); dev.agentnet.map.Explored.save(); });
        var keys = net.minecraft.client.KeyMapping.Category.register(net.minecraft.resources.Identifier.fromNamespaceAndPath("agentnet", "agentnet"));
        masterKey = net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(new net.minecraft.client.KeyMapping(
                "key.agentnet.master", 75 /* K */, keys));
        mapKey = net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(new net.minecraft.client.KeyMapping(
                "key.agentnet.map", 74 /* J */, keys));
        xaeroWorldMap = FabricLoader.getInstance().isModLoaded("xaeroworldmap");
        if (xaeroWorldMap) XaeroMapFeed.init();
        if (FabricLoader.getInstance().isModLoaded("xaerominimap")) XaeroBridge.init();
        else AgentNet.LOG.info("Xaero's Minimap not installed: agents won't be shown on a map");
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> {
            dispatcher.register(buildCommand());
            // /load [name|all]: bring saved agents back (same as /agent load)
            dispatcher.register(literal("load").executes(c -> load(c, "all"))
                    .then(argument("name", StringArgumentType.word()).suggests(SAVED)
                        .executes(c -> load(c, StringArgumentType.getString(c, "name")))));
        });
        AgentNet.LOG.info("AgentNet ready, files in {}", AgentFiles.dir());
    }

    // ================================================================= command tree

    private static final SuggestionProvider<FabricClientCommandSource> AGENT_TARGETS = (c, b) ->
            SharedSuggestionProvider.suggest(Stream.concat(Stream.of("all"), AGENTS.values().stream().map(a -> a.name)), b);

    private static final SuggestionProvider<FabricClientCommandSource> PLAYERS = (c, b) -> {
        var conn = Minecraft.getInstance().getConnection();
        Stream<String> online = conn == null ? Stream.empty()
                : conn.getOnlinePlayers().stream().map(PlayerInfo::getProfile).map(p -> p.name());
        return SharedSuggestionProvider.suggest(Stream.concat(online, AgentFiles.HITLIST.names().stream()).distinct(), b);
    };

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildCommand() {
        return literal("agent")
            // /agent join <count|name> [duration] [address]
            .then(literal("join")
                .then(argument("count|name", StringArgumentType.word())
                    .executes(c -> join(c, ""))
                    .then(argument("duration address", StringArgumentType.greedyString())
                        .executes(c -> join(c, StringArgumentType.getString(c, "duration address"))))))
            // /agent leave <all|count|name> [duration]
            .then(literal("leave")
                .then(argument("all|count|name", StringArgumentType.word()).suggests(AGENT_TARGETS)
                    .executes(c -> leave(c, ""))
                    .then(argument("duration", StringArgumentType.word())
                        .executes(c -> leave(c, StringArgumentType.getString(c, "duration"))))))
            // /agent spawn <count> <role>: new agents that take a role as soon as they're in
            .then(literal("spawn").then(argument("count", IntegerArgumentType.integer(1, MAX_BATCH))
                .then(argument("role", StringArgumentType.word())
                    .suggests((c, b) -> SharedSuggestionProvider.suggest(Stream.of(Role.values()).map(Role::id), b))
                    .executes(c -> {
                        Role role = Role.parse(StringArgumentType.getString(c, "role"));
                        if (role == null) return error(c, "Unknown role. See /agent roles");
                        String at = Servers.joinAddress();
                        if (at == null) return error(c, "You're not in a world");
                        List<String> names = spawn(IntegerArgumentType.getInteger(c, "count"), role, at);
                        return feedback(c, "Spawning " + names.size() + " " + role.displayName + "(s): " + String.join(", ", names));
                    }))))
            .then(action("trade", argument("item", StringArgumentType.word())
                    .executes(c -> setTask(c, "trade " + StringArgumentType.getString(c, "item")))
                    .then(argument("count", IntegerArgumentType.integer(1, 2304))
                        .executes(c -> setTask(c, "trade " + StringArgumentType.getString(c, "item") + " " + IntegerArgumentType.getInteger(c, "count"))))))
            .then(action("make", argument("item", StringArgumentType.word())
                    .executes(c -> setTask(c, "make " + StringArgumentType.getString(c, "item")))
                    .then(argument("count", IntegerArgumentType.integer(1, 2304))
                        .executes(c -> setTask(c, "make " + StringArgumentType.getString(c, "item") + " " + IntegerArgumentType.getInteger(c, "count"))))))
            .then(action("brew", argument("ingredient", StringArgumentType.word())
                    .executes(c -> setTask(c, "brew " + StringArgumentType.getString(c, "ingredient")))))
            .then(literal("master").executes(c -> { openMaster = true; return 1; }))
            // /agent chat all|important: every agent message in chat, or only attacks, finished builds, crashes...
            .then(literal("chat")
                .then(literal("all").executes(c -> { chatAll = true; return feedback(c, "Agent messages in chat: all"); }))
                .then(literal("important").executes(c -> { chatAll = false; return feedback(c, "Agent messages in chat: only important ones (all of them are still in the log)"); })))
            // /agent map: the supply chain (who waits on whom, who is AFK); also the J key
            .then(literal("map").executes(c -> { openMap = true; return 1; }))
            // ---- /agent civ on|off|status, cap <n>, build <schematic>: the autopilot (Civ)
            .then(literal("civ")
                .then(literal("on").executes(c -> { Civ.on(); return feedback(c, "Civ autopilot on: the master agent now runs the crew (see the master chat, K)"); }))
                .then(literal("off").executes(c -> { Civ.off(); return feedback(c, "Civ autopilot off"); }))
                .then(literal("status").executes(c -> {
                    StringBuilder sb = new StringBuilder(Civ.summary());
                    if (!Civ.decisions.isEmpty()) {
                        sb.append("\nLast decisions:");
                        Civ.decisions.stream().skip(Math.max(0, Civ.decisions.size() - 5)).forEach(d -> sb.append("\n  ").append(d));
                    }
                    return feedback(c, sb.toString());
                }))
                .then(literal("think")
                    .then(literal("on").executes(c -> { Civ.think = true; MasterAgent.plan(Civ.summary(), 3, Civ.cap); return feedback(c, "Civ thinking on: the local model also plans every 10 minutes (asking it now)"); }))
                    .then(literal("off").executes(c -> { Civ.think = false; return feedback(c, "Civ thinking off (rules only)"); })))
                .then(literal("cap").then(argument("agents", IntegerArgumentType.integer(1, 100)).executes(c -> {
                    Civ.cap = IntegerArgumentType.getInteger(c, "agents");
                    return feedback(c, "Civ population cap: " + Civ.cap);
                })))
                .then(literal("build").then(argument("schematic", StringArgumentType.word()).executes(c -> {
                    Civ.queueBuild(StringArgumentType.getString(c, "schematic"));
                    return feedback(c, "Queued for the autopilot's builders");
                }))))
            // ---- /agent view [role chunks]: view distance per role (no arguments: the screen)
            .then(literal("view").executes(c -> { openView = true; return 1; })
                .then(argument("role", StringArgumentType.word())
                    .suggests((c, b) -> SharedSuggestionProvider.suggest(java.util.stream.Stream.concat(java.util.Arrays.stream(Role.values()).map(Role::id), java.util.stream.Stream.of("none")).toList(), b))
                    .then(argument("chunks", IntegerArgumentType.integer(RoleView.MIN, RoleView.MAX)).executes(c -> {
                        String r = StringArgumentType.getString(c, "role");
                        Role role = r.equalsIgnoreCase("none") ? null : Role.parse(r);
                        if (role == null && !r.equalsIgnoreCase("none")) return error(c, "Unknown role " + r);
                        RoleView.set(role, IntegerArgumentType.getInteger(c, "chunks"));
                        return feedback(c, (role == null ? "Agents without a role" : role.displayName + "s") + " now see " + RoleView.get(role) + " chunks around them");
                    }))))
            // /agent pearl set <agent>   (look at the trapdoor/lever/button of its stasis chamber)
            .then(literal("pearl")
                .then(literal("set").then(argument("agent", StringArgumentType.word()).suggests(AGENT_TARGETS)
                    .then(argument("x", IntegerArgumentType.integer()).then(argument("y", IntegerArgumentType.integer()).then(argument("z", IntegerArgumentType.integer())
                        .executes(c -> {
                            String server = Servers.current(), name = StringArgumentType.getString(c, "agent");
                            var mc = c.getSource().getClient();
                            if (server == null || mc.level == null) return error(c, "You're not on a server");
                            int x = IntegerArgumentType.getInteger(c, "x"), y = IntegerArgumentType.getInteger(c, "y"), z = IntegerArgumentType.getInteger(c, "z");
                            dev.agentnet.map.Pearls.set(new dev.agentnet.map.Pearls.Pearl(server, name, mc.level.dimension().identifier().toString(), x, y, z));
                            return feedback(c, name + "'s pearl: pulled by clicking the block at " + x + " " + y + " " + z);
                        }))))
                    .executes(c -> {
                        String server = Servers.current(), name = StringArgumentType.getString(c, "agent");
                        var mc = c.getSource().getClient();
                        if (server == null || mc.level == null) return error(c, "You're not on a server");
                        if (!(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit)
                                || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
                            return error(c, "Look at the trapdoor, lever or button that pulls " + name + "'s pearl");
                        }
                        var p = hit.getBlockPos();
                        dev.agentnet.map.Pearls.set(new dev.agentnet.map.Pearls.Pearl(server, name,
                                mc.level.dimension().identifier().toString(), p.getX(), p.getY(), p.getZ()));
                        return feedback(c, name + "'s pearl: pulled by clicking " + mc.level.getBlockState(p).getBlock().getName().getString()
                                + " at " + p.toShortString());
                    })))
                .then(literal("remove").then(argument("agent", StringArgumentType.word()).executes(c -> {
                    String server = Servers.current(), name = StringArgumentType.getString(c, "agent");
                    return server != null && dev.agentnet.map.Pearls.remove(server, name) ? feedback(c, "Removed " + name + "'s pearl")
                            : error(c, name + " has no pearl set");
                })))
                .then(literal("list").executes(c -> {
                    String server = Servers.current();
                    if (server == null) return error(c, "You're not on a server");
                    var all = dev.agentnet.map.Pearls.list(server);
                    if (all.isEmpty()) return feedback(c, "No pearls set. Look at a stasis trapdoor and /agent pearl set <agent>");
                    all.forEach(p -> c.getSource().sendFeedback(Component.literal(" " + p.agent() + ": " + p.x() + " " + p.y() + " " + p.z())));
                    return all.size();
                })))
            // /agent war prepare|on|off|status
            .then(literal("war")
                .executes(c -> feedback(c, "War status: " + dev.agentnet.role.War.status().name().toLowerCase()))
                .then(literal("status").executes(c -> warStatus(c)))
                .then(literal("prepare").executes(c -> feedback(c, startWar(false))))
                .then(literal("on").executes(c -> feedback(c, startWar(true))))
                .then(literal("all")
                    .executes(c -> { dev.agentnet.role.War.allOut(true); return feedback(c, "§cWar on everyone: the warriors attack every player not on the whitelist (/agent whitelist add <player> to spare one; /agent war all off to stop)"); })
                    .then(literal("off").executes(c -> { dev.agentnet.role.War.allOut(false); return feedback(c, "War on everyone is over: only the hitlist again"); })))
                .then(literal("off").executes(c -> feedback(c, endWar()))))
            .then(literal("load").executes(c -> load(c, "all"))
                .then(argument("name", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(Stream.concat(Saves.names().stream(), Servers.current() == null ? Stream.<String>empty() : Roster.list(Servers.current()).stream().map(Roster.Entry::name)), b))
                    .executes(c -> {
                        String n = StringArgumentType.getString(c, "name");
                        return Saves.exists(n) ? loadSave(c, n) : load(c, n); // a crew save, else a saved agent by name
                    })))
            .then(literal("save").then(argument("name", StringArgumentType.word())
                .executes(c -> saveCrew(c, StringArgumentType.getString(c, "name")))))
            .then(literal("saves").executes(c -> {
                List<String> n = Saves.names();
                return n.isEmpty() ? feedback(c, "No crew saves yet (/agent save <name>)") : feedback(c, n.size() + " crew save(s): " + String.join(", ", n));
            }))
            .then(literal("saved").executes(c -> {
                String server = Servers.current();
                if (server == null) return error(c, "You're not on a server");
                var saved = Roster.list(server);
                if (saved.isEmpty()) return feedback(c, "No saved agents for this server");
                feedback(c, saved.size() + " saved agent(s):");
                saved.forEach(e -> c.getSource().sendFeedback(Component.literal(" " + e.name() + " [" + e.importance() + "] "
                        + (e.role() == null ? "no role" : e.role().id()) + (AGENTS.containsKey(e.name().toLowerCase()) ? " (online)" : ""))));
                return saved.size();
            }))
            .then(literal("forget").then(argument("name", StringArgumentType.word()).suggests(SAVED)
                .executes(c -> {
                    String server = Servers.current(), n = StringArgumentType.getString(c, "name");
                    if (server == null) return error(c, "You're not on a server");
                    return Roster.forget(server, n) ? feedback(c, "Forgot " + n) : error(c, "No saved agent named " + n);
                })))
            .then(literal("list").executes(c -> {
                if (AGENTS.isEmpty()) return feedback(c, "No agents");
                List<Agent> sorted = new ArrayList<>(AGENTS.values());
                sorted.sort(Comparator.comparingInt((Agent a) -> -a.importance).thenComparing(a -> a.name));
                feedback(c, AGENTS.size() + " agent(s), [importance]:");
                sorted.forEach(a -> c.getSource().sendFeedback(Component.literal(" " + a.describe())));
                return AGENTS.size();
            }))
            // /agent importance <agent|all> [1-10]
            .then(literal("importance")
                .then(argument("agent", StringArgumentType.word()).suggests(AGENT_TARGETS)
                    .executes(c -> {
                        List<Agent> t = targets(c);
                        t.forEach(a -> c.getSource().sendFeedback(Component.literal(a.name + ": " + a.importance)));
                        return t.size();
                    })
                    .then(argument("value", IntegerArgumentType.integer(1, 10))
                        .executes(c -> {
                            int v = IntegerArgumentType.getInteger(c, "value");
                            List<Agent> t = targets(c);
                            t.forEach(a -> { a.importance = v; Roster.remember(a); });
                            return t.isEmpty() ? 0 : feedback(c, "Importance " + v + " set for " + names(t));
                        }))))
            // ---- actions: stored on the agent; nothing executes them until agents get brains
            .then(action("kill", argument("player", StringArgumentType.word()).suggests(PLAYERS)
                    .executes(c -> setTask(c, "kill " + StringArgumentType.getString(c, "player")))))
            .then(action("follow", argument("player", StringArgumentType.word()).suggests(PLAYERS)
                    .executes(c -> setTask(c, "follow " + StringArgumentType.getString(c, "player")))))
            .then(action("mine", argument("block", StringArgumentType.word())
                    .executes(c -> setTask(c, "mine " + StringArgumentType.getString(c, "block")))
                    .then(argument("amount", IntegerArgumentType.integer(1))
                        .executes(c -> setTask(c, "mine " + IntegerArgumentType.getInteger(c, "amount")
                                + " " + StringArgumentType.getString(c, "block"))))))
            .then(literal("loot").then(argument("agent", StringArgumentType.word()).suggests(AGENT_TARGETS)
                    .executes(c -> setTask(c, "loot"))))
            .then(literal("golem").then(argument("agent", StringArgumentType.word()).suggests(AGENT_TARGETS)
                    .executes(c -> setTask(c, "golem"))))
            // /agent step <agent>: the speedrunner's portal one step at a time (each use does the next step)
            .then(literal("step").then(argument("agent", StringArgumentType.word()).suggests(AGENT_TARGETS)
                    .executes(c -> setTask(c, "step"))))
            // /agent speedrun <agent>: the speedrunner carries on by itself (no waiting between steps)
            .then(literal("speedrun").then(argument("agent", StringArgumentType.word()).suggests(AGENT_TARGETS)
                    .executes(c -> setTask(c, "speedrun"))
                    .then(literal("main").executes(c -> setTask(c, "speedrun main")))
                    // /agent speedrun <agent> stage <name>: jump to a stage (tests)
                    .then(literal("stage").then(argument("stage", StringArgumentType.word()).executes(c -> setTask(c, "speedrun stage " + StringArgumentType.getString(c, "stage")))))))
            // The lava-pool scout (an explorer): finds a pool for the speedrunners, marks it, goes into it and is retired.
            .then(literal("scout").then(argument("agent", StringArgumentType.word()).suggests(AGENT_TARGETS)
                    .executes(c -> setTask(c, "scout"))))
            .then(literal("hunt").then(argument("agent", StringArgumentType.word()).suggests(AGENT_TARGETS)
                    .executes(c -> setTask(c, "hunt"))))
            .then(literal("explore").then(argument("agent", StringArgumentType.word()).suggests(AGENT_TARGETS)
                    .executes(c -> setTask(c, "explore"))
                    .then(argument("radius", IntegerArgumentType.integer(16))
                        .executes(c -> setTask(c, "explore radius " + IntegerArgumentType.getInteger(c, "radius"))))))
            .then(action("goto", argument("x", DoubleArgumentType.doubleArg())
                    .then(argument("y", DoubleArgumentType.doubleArg())
                        .then(argument("z", DoubleArgumentType.doubleArg())
                            .executes(c -> setTask(c, String.format("goto %.0f %.0f %.0f",
                                    DoubleArgumentType.getDouble(c, "x"), DoubleArgumentType.getDouble(c, "y"),
                                    DoubleArgumentType.getDouble(c, "z"))))))))
            .then(action("build", argument("file", StringArgumentType.greedyString())
                    .executes(c -> {
                        // The schematic's origin is where you stand.
                        var me = net.minecraft.client.Minecraft.getInstance().player;
                        if (me == null) return error(c, "Join a world first");
                        var at = me.blockPosition();
                        return setTask(c, "build " + StringArgumentType.getString(c, "file") + " " + at.getX() + " " + at.getY() + " " + at.getZ());
                    })))
            .then(literal("stop").then(argument("agent", StringArgumentType.word()).suggests(AGENT_TARGETS)
                    .executes(c -> setTask(c, "idle"))))
            // /agent come <agent|all>: walk to where you stand
            .then(literal("come").then(argument("agent", StringArgumentType.word()).suggests(AGENT_TARGETS)
                    .executes(c -> {
                        var p = c.getSource().getPlayer();
                        if (p == null) return error(c, "You're not in a world");
                        List<Agent> t = targets(c);
                        for (Agent a : t) { a.task = "come"; a.navigate(p.getBlockX(), p.getBlockY(), p.getBlockZ(), 2); }
                        return t.isEmpty() ? 0 : feedback(c, names(t) + " coming to you");
                    })))
            // /agent where <agent|all>: travel status
            .then(literal("where").then(argument("agent", StringArgumentType.word()).suggests(AGENT_TARGETS)
                    .executes(c -> {
                        for (Agent a : targets(c)) {
                            c.getSource().sendFeedback(Component.literal(String.format("%s at %.0f %.0f %.0f, travel: %s",
                                    a.name, a.x(), a.y(), a.z(), a.navStatus())));
                        }
                        return 1;
                    })))
            // ---- roles: /agent role <agent|all> <role|none>, /agent roles
            .then(literal("role").then(argument("agent", StringArgumentType.word()).suggests(AGENT_TARGETS)
                .executes(c -> {
                    targets(c).forEach(a -> c.getSource().sendFeedback(Component.literal(
                            a.name + ": " + roleName(a))));
                    return 1;
                })
                .then(argument("role", StringArgumentType.word())
                    .suggests((c, b) -> SharedSuggestionProvider.suggest(Stream.concat(
                            Stream.of(Role.values()).map(Role::id), Stream.of("none")), b))
                    .executes(AgentNetClient::assignRole))))
            .then(literal("roles").executes(c -> {
                for (Role r : Role.values()) {
                    long n = AGENTS.values().stream().filter(a -> a.role() == r).count();
                    c.getSource().sendFeedback(Component.literal(String.format("§l%s§r (%d) [%s]: %s",
                            r.displayName, n, r.tier.name().toLowerCase(), r.description)));
                }
                return 1;
            }))
            // ---- /agent chest add|remove [x y z], list: storage chests distributors fill and fetch from
            .then(literal("chest")
                .then(literal("add").executes(c -> chestCmd(c, true, null))
                    .then(argument("x", IntegerArgumentType.integer()).then(argument("y", IntegerArgumentType.integer()).then(argument("z", IntegerArgumentType.integer())
                        .executes(c -> chestCmd(c, true, new int[]{IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "y"), IntegerArgumentType.getInteger(c, "z")}))))))
                .then(literal("remove").executes(c -> chestCmd(c, false, null))
                    .then(argument("x", IntegerArgumentType.integer()).then(argument("y", IntegerArgumentType.integer()).then(argument("z", IntegerArgumentType.integer())
                        .executes(c -> chestCmd(c, false, new int[]{IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "y"), IntegerArgumentType.getInteger(c, "z")}))))))
                .then(literal("list").executes(c -> {
                    var mc = net.minecraft.client.Minecraft.getInstance();
                    if (mc.level == null || Servers.current() == null) return error(c, "You're not in a world");
                    var list = dev.agentnet.role.ChestMemory.list(Servers.current(), mc.level.dimension().identifier().toString());
                    if (list.isEmpty()) return feedback(c, "No storage chests yet: look at a chest and run /agent chest add");
                    StringBuilder sb = new StringBuilder("Storage chests:");
                    for (var ch : list) sb.append("\n  ").append(ch.x()).append(" ").append(ch.y()).append(" ").append(ch.z()).append(ch.note().isEmpty() ? "" : " (" + ch.note() + ")");
                    return feedback(c, sb.toString());
                })))
            // ---- /agent schematic pos1|pos2 [x y z], save <name>: copy part of the world to
            //      agentnet/schematics/<name>.litematic, for builders (Litematica opens it too)
            .then(literal("schematic")
                .then(literal("pos1").executes(c -> schemCorner(c, 1, null))
                    .then(argument("x", IntegerArgumentType.integer()).then(argument("y", IntegerArgumentType.integer()).then(argument("z", IntegerArgumentType.integer())
                        .executes(c -> schemCorner(c, 1, new int[]{IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "y"), IntegerArgumentType.getInteger(c, "z")}))))))
                .then(literal("pos2").executes(c -> schemCorner(c, 2, null))
                    .then(argument("x", IntegerArgumentType.integer()).then(argument("y", IntegerArgumentType.integer()).then(argument("z", IntegerArgumentType.integer())
                        .executes(c -> schemCorner(c, 2, new int[]{IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "y"), IntegerArgumentType.getInteger(c, "z")}))))))
                .then(literal("save").then(argument("name", StringArgumentType.word()).executes(c -> {
                    if (schem1 == null || schem2 == null) return error(c, "Mark both corners first: /agent schematic pos1, /agent schematic pos2");
                    var level = net.minecraft.client.Minecraft.getInstance().level;
                    if (level == null) return error(c, "You're not in a world");
                    String name = StringArgumentType.getString(c, "name");
                    var file = AgentFiles.dir().resolve("schematics").resolve(name + ".litematic");
                    try {
                        int n = dev.agentnet.role.Litematic.save(file, name, Takeover.yourName(), level,
                                new net.minecraft.core.BlockPos(schem1[0], schem1[1], schem1[2]), new net.minecraft.core.BlockPos(schem2[0], schem2[1], schem2[2]));
                        return feedback(c, "Saved " + name + ".litematic (" + n + " blocks). Build it with /agent build <agent|all> " + name);
                    } catch (Exception e) {
                        return error(c, "Couldn't save: " + e.getMessage());
                    }
                }))))
            // ---- farm areas: /agent farm pos1, pos2, add <name> <crops|trees>, remove, list
            .then(literal("farm")
                .then(literal("pos1").executes(c -> setCorner(c, 1)))
                .then(literal("pos2").executes(c -> setCorner(c, 2)))
                .then(literal("add").then(argument("name", StringArgumentType.word())
                    .then(argument("type", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(Stream.of(Farms.Type.values()).map(Farms.Type::id), b))
                        .executes(AgentNetClient::addFarm))))
                .then(literal("remove").then(argument("name", StringArgumentType.word())
                    .suggests((c, b) -> SharedSuggestionProvider.suggest(Servers.current() == null ? List.<String>of()
                            : Farms.list(Servers.current()).stream().map(Farms.Farm::name).toList(), b))
                    .executes(c -> {
                        String name = StringArgumentType.getString(c, "name");
                        String server = Servers.current();
                        return feedback(c, server != null && Farms.remove(server, name)
                                ? "Farm '" + name + "' removed" : "No farm named '" + name + "'");
                    })))
                .then(literal("list").executes(c -> {
                    String server = Servers.current();
                    if (server == null) return error(c, "You're not on a server");
                    var farms = Farms.list(server);
                    if (farms.isEmpty()) return feedback(c, "No farms yet: /agent farm pos1, /agent farm pos2, /agent farm add <name> crops|trees");
                    for (Farms.Farm f : farms) {
                        String by = Farms.claimedBy(f.name());
                        c.getSource().sendFeedback(Component.literal(String.format("%s (%s): %d %d %d to %d %d %d, %s",
                                f.name(), f.type().id(), f.x1(), f.y1(), f.z1(), f.x2(), f.y2(), f.z2(),
                                by == null ? "free" : "worked by " + by)));
                    }
                    return farms.size();
                })))
            // ---- work spots: /agent spot add fishing <name>  (your position + where you look)
            .then(literal("spot")
                .then(literal("add").then(argument("type", StringArgumentType.word())
                    .suggests((c, b) -> SharedSuggestionProvider.suggest(Stream.of(Spots.Type.values()).map(Spots.Type::id), b))
                    .then(argument("name", StringArgumentType.word()).executes(AgentNetClient::addSpot))))
                .then(literal("remove").then(argument("name", StringArgumentType.word())
                    .suggests((c, b) -> SharedSuggestionProvider.suggest(Servers.current() == null ? List.<String>of()
                            : Spots.list(Servers.current()).stream().map(Spots.Spot::name).toList(), b))
                    .executes(c -> {
                        String name = StringArgumentType.getString(c, "name");
                        String server = Servers.current();
                        return feedback(c, server != null && Spots.remove(server, name)
                                ? "Spot '" + name + "' removed" : "No spot named '" + name + "'");
                    })))
                .then(literal("list").executes(c -> {
                    String server = Servers.current();
                    if (server == null) return error(c, "You're not on a server");
                    var spots = Spots.list(server);
                    if (spots.isEmpty()) return feedback(c, "No spots yet: stand there, look at the water/farm, /agent spot add fishing <name>");
                    for (Spots.Spot s : spots) {
                        String by = Spots.claimedBy(s.name());
                        c.getSource().sendFeedback(Component.literal(String.format("%s (%s): %.0f %.0f %.0f, %s",
                                s.name(), s.type().id(), s.x(), s.y(), s.z(), by == null ? "free" : "used by " + by)));
                    }
                    return spots.size();
                })))
            // ---- points of interest: /agent poi add <name> [x y z], remove, list
            .then(literal("poi")
                .then(literal("add").then(argument("name", StringArgumentType.word())
                    .executes(c -> {
                        var p = c.getSource().getPlayer();
                        return addPoi(c, p.getBlockX(), p.getBlockY(), p.getBlockZ());
                    })
                    .then(argument("x", IntegerArgumentType.integer()).then(argument("y", IntegerArgumentType.integer())
                        .then(argument("z", IntegerArgumentType.integer()).executes(c -> addPoi(c,
                            IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "y"),
                            IntegerArgumentType.getInteger(c, "z"))))))))
                .then(literal("remove").then(argument("name", StringArgumentType.word())
                    .suggests((c, b) -> SharedSuggestionProvider.suggest(
                            Servers.current() == null ? List.<String>of()
                                    : Pois.listServer(Servers.current()).stream().map(Pois.Poi::name).toList(), b))
                    .executes(c -> {
                        String name = StringArgumentType.getString(c, "name");
                        String server = Servers.current();
                        return feedback(c, server != null && Pois.remove(server, name)
                                ? "POI '" + name + "' removed" : "No POI named '" + name + "'");
                    })))
                .then(literal("list").executes(c -> {
                    String server = Servers.current();
                    if (server == null) return error(c, "You're not on a server");
                    var pois = Pois.listServer(server);
                    if (pois.isEmpty()) return feedback(c, "No POIs yet: /agent poi add <name> [x y z]");
                    for (Pois.Poi p : pois) {
                        String seen = p.lastVisited() == 0 ? "never visited"
                                : "visited " + formatDuration(System.currentTimeMillis() - p.lastVisited()) + " ago";
                        c.getSource().sendFeedback(Component.literal(String.format("%s: %d %d %d (%s), %s",
                                p.name(), p.x(), p.y(), p.z(), p.dimension(), seen)));
                    }
                    return pois.size();
                })))
            // ---- whitelist / hitlist
            .then(literal("whitelist")
                .then(literal("add").then(argument("player", StringArgumentType.word()).suggests(PLAYERS)
                    .executes(c -> {
                        String p = StringArgumentType.getString(c, "player");
                        if (!AgentFiles.WHITELIST.add(p, "added " + AgentFiles.now()))
                            return feedback(c, p + " is already whitelisted");
                        boolean wasEnemy = AgentFiles.HITLIST.remove(p);
                        return feedback(c, p + " whitelisted" + (wasEnemy ? " and removed from the hitlist" : ""));
                    })))
                .then(literal("remove").then(argument("player", StringArgumentType.word())
                    .suggests((c, b) -> SharedSuggestionProvider.suggest(AgentFiles.WHITELIST.names(), b))
                    .executes(c -> {
                        String p = StringArgumentType.getString(c, "player");
                        return feedback(c, AgentFiles.WHITELIST.remove(p) ? p + " removed from the whitelist"
                                : p + " isn't on the whitelist");
                    })))
                .then(literal("list").executes(c -> listNames(c, "Whitelist", AgentFiles.WHITELIST.names()))))
            .then(literal("hitlist")
                // By hand (a test, or someone you want the crew to watch for): /agent hitlist add <player>.
                .then(literal("add").then(argument("player", StringArgumentType.word())
                    .executes(c -> {
                        String p = StringArgumentType.getString(c, "player");
                        return feedback(c, AgentFiles.HITLIST.add(p, "added by hand (" + java.time.LocalDateTime.now().withNano(0) + ")") ? p + " is on the hitlist now" : p + " is on the hitlist already");
                    })))
                .then(literal("remove").then(argument("player", StringArgumentType.word())
                    .suggests((c, b) -> SharedSuggestionProvider.suggest(AgentFiles.HITLIST.names(), b))
                    .executes(c -> {
                        String p = StringArgumentType.getString(c, "player");
                        return feedback(c, AgentFiles.HITLIST.remove(p) ? p + " removed from the hitlist"
                                : p + " isn't on the hitlist");
                    })))
                .then(literal("list").executes(c -> listNames(c, "Hitlist", AgentFiles.HITLIST.names()))))
            // ---- take over an agent: /agent control <name>, /agent release, /agent takeover on|off
            .then(literal("control").then(argument("name", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(AGENTS.values().stream().map(a -> a.name), b))
                .executes(c -> {
                    String name = StringArgumentType.getString(c, "name");
                    Agent a = AGENTS.get(name.toLowerCase());
                    if (a == null) return error(c, "No agent named " + name);
                    return Takeover.start(a) ? feedback(c, "Taking control of " + a.name + "...") : 0;
                })))
            .then(literal("release").executes(c -> Takeover.release() ? 1 : 0))
            .then(literal("takeover")
                .executes(c -> feedback(c, "Automatic takeover under half health is " + (Takeover.auto ? "on" : "off")))
                .then(literal("on").executes(c -> { Takeover.auto = true; return feedback(c, "Automatic takeover on"); }))
                .then(literal("off").executes(c -> { Takeover.auto = false; return feedback(c, "Automatic takeover off"); })))
            // ---- /agent train on|off: warriors fight you too (PvP practice)
            .then(literal("train")
                .executes(c -> feedback(c, "Train mode is " + (dev.agentnet.role.Training.partner != null ? "on: warriors attack " + dev.agentnet.role.Training.partner : "off")))
                .then(literal("on").executes(c -> {
                    dev.agentnet.role.Training.partner = Takeover.yourName();
                    Takeover.auto = false; // a training hit shouldn't pull you into the agent
                    return feedback(c, "§cTrain mode on: warriors attack you (" + Takeover.yourName() + ") too. "
                            + "Automatic takeover is off meanwhile. /agent train off to stop");
                }))
                .then(literal("off").executes(c -> {
                    dev.agentnet.role.Training.partner = null;
                    return feedback(c, "Train mode off (turn automatic takeover back on with /agent takeover on if you want it)");
                })))
            // ---- /agent reconnect on|off: rejoin by themselves after kicks / server restarts
            .then(literal("aerial")
                .executes(c -> feedback(c, "Elytra mace on mobs: " + (dev.agentnet.role.CombatSettings.aerialOnMobs ? "on" : "off (players only)")))
                .then(literal("on").executes(c -> { dev.agentnet.role.CombatSettings.aerialOnMobs = true; return feedback(c, "Elytra mace on mobs too"); }))
                .then(literal("off").executes(c -> { dev.agentnet.role.CombatSettings.aerialOnMobs = false; return feedback(c, "Elytra mace on players only"); })))
            .then(literal("afk")
                .executes(c -> feedback(c, "AFK checker is " + (AfkChecker.enabled ? "on (" + AfkChecker.AFK_MS / 60_000 + " min)" : "off")))
                .then(literal("on").executes(c -> { AfkChecker.enabled = true; return feedback(c, "AFK checker on"); }))
                .then(literal("off").executes(c -> { AfkChecker.enabled = false; return feedback(c, "AFK checker off"); }))
                .then(argument("minutes", IntegerArgumentType.integer(1, 120))
                    .executes(c -> { AfkChecker.AFK_MS = IntegerArgumentType.getInteger(c, "minutes") * 60_000L; AfkChecker.enabled = true;
                        return feedback(c, "AFK checker: idle " + IntegerArgumentType.getInteger(c, "minutes") + " min = not working"); })))
            .then(literal("reconnect")
                .executes(c -> feedback(c, "Auto-reconnect is " + (autoReconnect ? "on" : "off")))
                .then(literal("on").executes(c -> { autoReconnect = true; return feedback(c, "Auto-reconnect on"); }))
                .then(literal("off").executes(c -> { autoReconnect = false; return feedback(c, "Auto-reconnect off"); })))
            // ---- /agent global <command> [args]: the same command for every agent
            //      ("global mine iron_ore 10" = "mine all iron_ore 10", "global stop", "global role miner")
            .then(literal("global").then(argument("command", StringArgumentType.greedyString())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("mine", "goto", "come", "follow", "explore",
                        "stop", "role", "make", "kill", "importance", "where"), b))
                .executes(c -> {
                    String[] p = StringArgumentType.getString(c, "command").trim().split("\\s+", 2);
                    if (AGENTS.isEmpty()) return error(c, "No agents");
                    String cmd = "agent " + p[0] + " all" + (p.length > 1 ? " " + p[1] : "");
                    try {
                        return net.fabricmc.fabric.api.client.command.v2.ClientCommands.getActiveDispatcher().execute(cmd, c.getSource());
                    } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                        return error(c, "/agent global: " + e.getMessage());
                    }
                })))
            // ---- private bot chat: /agent chat <message>, written to botchat.txt only
            .then(literal("chat").then(argument("message", StringArgumentType.greedyString())
                .executes(c -> {
                    String me = Takeover.yourName();
                    AgentFiles.botChat(me, "all", StringArgumentType.getString(c, "message"));
                    return feedback(c, "§7(bot chat) " + me + ": " + StringArgumentType.getString(c, "message"));
                })));
    }

    /** /agent <verb> <agent|all> ...rest */
    private static LiteralArgumentBuilder<FabricClientCommandSource> action(
            String verb, RequiredArgumentBuilder<FabricClientCommandSource, ?> rest) {
        return literal(verb).then(argument("agent", StringArgumentType.word()).suggests(AGENT_TARGETS).then(rest));
    }

    // ================================================================= join

    private static int join(CommandContext<FabricClientCommandSource> c, String rest) {
        String arg = StringArgumentType.getString(c, "count|name");
        FabricClientCommandSource src = c.getSource();
        boolean isCount = COUNT.matcher(arg).matches();
        if (!isCount && !VALID_NAME.matcher(arg).matches()) {
            return error(c, "Give a number of agents, or a name of 3-16 letters, digits or _");
        }

        // Optional "[duration] [address]": a leading token like 60, 60s, 5m, 1h30m, 2d is a duration.
        long durationMs = -1;
        String address = null;
        String[] parts = rest.trim().split("\\s+", 2);
        if (!parts[0].isEmpty() && DURATION.matcher(parts[0]).matches()) {
            durationMs = parseDuration(parts[0]);
            if (durationMs > MAX_DURATION_MS) return error(c, "Duration can be at most 30d");
            if (parts.length > 1) address = parts[1].trim();
        } else if (!parts[0].isEmpty()) {
            address = rest.trim();
        }
        if (address == null) {
            address = Servers.joinAddress();
            if (address == null) return error(c, "You're not in a world; add an address at the end");
        }
        String self = src.getClient().getUser().getName().toLowerCase();

        if (isCount) {
            int count = Integer.parseInt(arg);
            if (count < 1 || count > MAX_BATCH) return error(c, "Count must be 1-" + MAX_BATCH);
            Set<String> taken = new HashSet<>(AGENTS.keySet());
            taken.add(self);
            taken.add(Takeover.yourName().toLowerCase());
            List<String> names = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (int i = 0; i < count; i++) {
                String name = NameGenerator.next(taken);
                taken.add(name.toLowerCase());
                names.add(name);
                // With a duration each agent gets its own random moment in the window, so joins
                // arrive irregularly but average out to count/duration. Without: every 250 ms.
                long at = durationMs > 0 ? now + ThreadLocalRandom.current().nextLong(durationMs)
                        : now + i * DEFAULT_SPACING_MS;
                queueJoin(new Agent(name, address), at);
            }
            String when = durationMs > 0 ? " over " + formatDuration(durationMs) : "";
            return feedback(c, "Joining " + count + " agent(s) to " + address + when + ": " + String.join(", ", names));
        }

        if (arg.equalsIgnoreCase(self) || arg.equalsIgnoreCase(Takeover.yourName())) {
            return error(c, "That's your own name; the server would kick you");
        }
        if (AGENTS.containsKey(arg.toLowerCase())) return error(c, arg + " is already an agent");
        long delay = durationMs > 0 ? ThreadLocalRandom.current().nextLong(durationMs) : 0;
        // A joining agent always starts with a clean head (no role, no task): what it did on this or
        // another server earlier comes back only through /agent load <save> (or /load).
        queueJoin(new Agent(arg, address), System.currentTimeMillis() + delay);
        return feedback(c, "Joining " + arg + " to " + address + (delay > 0 ? " in " + formatDuration(delay) : "") + "...");
    }

    private static final SuggestionProvider<FabricClientCommandSource> SAVED = (c, b) -> {
        String server = Servers.current();
        Stream<String> names = server == null ? Stream.empty() : Roster.list(server).stream().map(Roster.Entry::name);
        return SharedSuggestionProvider.suggest(Stream.concat(Stream.of("all"), names), b);
    };

    // ================================================================= war

    /** Peacetime roles to give back after the war. */
    private static final Map<String, Role> PREWAR = new ConcurrentHashMap<>();
    private static final double DISTRIBUTOR_SHARE = 0.2; // one distributor for every ~4 warriors

    /**
     * War transition: every agent becomes a warrior or a distributor. The least important
     * agents become distributors (at least one); existing distributors stay distributors.
     */
    static String startWar(boolean straightToWar) {
        if (dev.agentnet.role.War.status() == dev.agentnet.role.War.Status.PEACE) {
            dev.agentnet.role.War.set(dev.agentnet.role.War.Status.TRANSITION);
            List<Agent> all = new ArrayList<>(AGENTS.values());
            int needed = Math.max(1, (int) Math.ceil(all.size() * DISTRIBUTOR_SHARE));
            long have = all.stream().filter(a -> a.role() == Role.DISTRIBUTOR).count();
            List<Agent> others = new ArrayList<>(all.stream().filter(a -> a.role() != Role.DISTRIBUTOR && a.role() != Role.WARRIOR).toList());
            others.sort(Comparator.comparingInt(a -> a.importance));
            int warriors = 0, distributors = 0;
            for (Agent a : others) {
                PREWAR.put(a.name.toLowerCase(), a.role() == null ? Role.WARRIOR : a.role());
                if (a.role() == null) PREWAR.remove(a.name.toLowerCase());
                if (have < needed) { a.setRole(Role.DISTRIBUTOR); have++; distributors++; }
                else { a.setRole(Role.WARRIOR); warriors++; }
            }
            for (Agent a : all) if (a.role() == Role.DISTRIBUTOR || a.role() == Role.WARRIOR) PREWAR.putIfAbsent(a.name.toLowerCase(), a.role());
            String msg = "War transition: " + warriors + " new warrior(s), " + distributors + " new distributor(s); kits are being handed out";
            if (!straightToWar) return msg;
        }
        dev.agentnet.role.War.set(dev.agentnet.role.War.Status.WAR);
        return "War status: WAR";
    }

    static String endWar() {
        dev.agentnet.role.War.allOut(false);
        if (!dev.agentnet.role.War.active()) return "There's no war";
        dev.agentnet.role.War.set(dev.agentnet.role.War.Status.PEACE);
        int restored = 0;
        for (Agent a : AGENTS.values()) {
            Role before = PREWAR.remove(a.name.toLowerCase());
            if (before != null && before != a.role()) { a.setRole(before); restored++; }
        }
        PREWAR.clear();
        return "Peace. " + restored + " agent(s) went back to their old jobs";
    }

    private static int warStatus(CommandContext<FabricClientCommandSource> c) {
        feedback(c, "War status: " + dev.agentnet.role.War.status().name().toLowerCase());
        for (Agent a : AGENTS.values()) {
            if (a.role() != Role.WARRIOR) continue;
            c.getSource().sendFeedback(Component.literal(" " + a.name + ": " + (dev.agentnet.role.War.hasKit(a) ? "§akit complete" : "§ekit incomplete")
                    + (a.warriorStatus().isEmpty() ? "" : " §7" + a.warriorStatus())));
        }
        return 1;
    }

    /** Transition is over once every warrior has its kit. Main thread, every 2 s. */
    private static void checkWarTransition() {
        if (dev.agentnet.role.War.status() != dev.agentnet.role.War.Status.TRANSITION) return;
        List<Agent> warriors = AGENTS.values().stream().filter(a -> a.isOnline() && a.role() == Role.WARRIOR).toList();
        if (warriors.isEmpty() || !warriors.stream().allMatch(dev.agentnet.role.War::hasKit)) return;
        dev.agentnet.role.War.set(dev.agentnet.role.War.Status.WAR);
        notify("§c[AgentNet] Every warrior has its kit: war status is now WAR");
    }

    private static int warTicks;

    /** Spacing for joins the master makes: some servers refuse logins that come too fast. */
    private static final long SPAWN_SPACING_MS = 1500;

    /** New agents with random names that take this role once online. */
    static List<String> spawn(int count, Role role, String address) {
        Set<String> taken = new HashSet<>(AGENTS.keySet());
        Minecraft mc = Minecraft.getInstance();
        taken.add(mc.getUser().getName().toLowerCase());
        taken.add(Takeover.yourName().toLowerCase());
        String server = Servers.key(address);
        Roster.list(server).forEach(e -> taken.add(e.name().toLowerCase())); // don't reuse saved names
        List<String> names = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String name = NameGenerator.next(taken);
            taken.add(name.toLowerCase());
            rejoin(name, address, role, 5, "idle", (long) (pendingJoins() + i) * SPAWN_SPACING_MS);
            names.add(name);
        }
        return names;
    }

    private static int pendingJoins() { return PENDING_JOIN.size(); }

    static boolean openMaster, openView, openMap;
    private static net.minecraft.client.KeyMapping masterKey, mapKey;

    /** /agent save <name>: every agent's role, task and importance, to agentnet/saves/<name>.txt. */
    static int saveCrew(CommandContext<FabricClientCommandSource> c, String name) {
        if (!Saves.validName(name)) return error(c, "Use letters, digits, - and _ for the save name");
        List<Agent> crew = new ArrayList<>(AGENTS.values());
        if (crew.isEmpty()) return error(c, "No agents to save");
        crew.sort(Comparator.comparing(a -> a.name));
        try { Saves.save(name, crew); }
        catch (java.io.IOException e) { return error(c, "Couldn't write the save: " + e.getMessage()); }
        return feedback(c, "Saved " + crew.size() + " agent(s) as '" + name + "': " + String.join(", ",
                crew.stream().map(a -> a.name + " (" + (a.role() == null ? "no role" : a.role().id()) + (a.task.equals("idle") ? "" : ", " + a.task) + ")").toList()));
    }

    /** /agent load <save>: give online agents their saved role and task; the rest join with them, here. */
    static int loadSave(CommandContext<FabricClientCommandSource> c, String name) {
        String at = Servers.joinAddress();
        if (at == null) return error(c, "You're not in a world");
        List<Saves.Entry> entries;
        try { entries = Saves.read(name); }
        catch (java.io.IOException e) { return error(c, "Couldn't read the save: " + e.getMessage()); }
        String self = c.getSource().getClient().getUser().getName();
        List<String> joined = new ArrayList<>(), updated = new ArrayList<>();
        for (Saves.Entry e : entries) {
            if (e.name().equalsIgnoreCase(self) || e.name().equalsIgnoreCase(Takeover.yourName())) continue;
            Agent a = AGENTS.get(e.name().toLowerCase());
            if (a != null) {
                a.importance = e.importance();
                a.setRole(e.role());
                a.setTask(e.task());
                Roster.remember(a);
                updated.add(e.name());
                continue;
            }
            Agent fresh = rejoin(e.name(), at, e.role(), e.importance(), e.task(), joined.size() * DEFAULT_SPACING_MS);
            fresh.replayTask = true; // its task goes to the role once it's in
            joined.add(e.name());
        }
        if (joined.isEmpty() && updated.isEmpty()) return error(c, "Save '" + name + "' has no agents");
        return feedback(c, "Loaded '" + name + "'" + (joined.isEmpty() ? "" : ": joining " + String.join(", ", joined))
                + (updated.isEmpty() ? "" : (joined.isEmpty() ? ": " : "; ") + "updated " + String.join(", ", updated)));
    }

    /** The server the crew is on; agents joining a different one start with wiped memory. */
    private static String crewServer;

    /** An agent is in: if the whole crew moved to another server, wipe what they remembered of the old one. */
    static void crewOnline(Agent a) {
        String key = Servers.key(a.address);
        if (crewServer != null && !crewServer.equals(key)) {
            String old = crewServer;
            boolean oldStillOn = AGENTS.values().stream().anyMatch(o -> o != a && o.isOnline() && Servers.key(o.address).equals(old));
            if (oldStillOn) return; // agents on both servers: keep it until the old one is empty
            dev.agentnet.role.Memory.wipe();
            notify("§e[AgentNet] the crew moved from " + old + " to " + key + ": agent memory wiped, everyone starts fresh");
        }
        crewServer = key;
    }

    /** Bring saved agents for this server back with their roles and importance. */
    static int load(CommandContext<FabricClientCommandSource> c, String which) {
        String at = Servers.joinAddress();
        if (at == null) return error(c, "You're not in a world");
        String self = c.getSource().getClient().getUser().getName();
        List<String> joined = new ArrayList<>();
        for (Roster.Entry e : Roster.list(Servers.current())) {
            if (!which.equalsIgnoreCase("all") && !e.name().equalsIgnoreCase(which)) continue;
            if (AGENTS.containsKey(e.name().toLowerCase()) || e.name().equalsIgnoreCase(self)
                    || e.name().equalsIgnoreCase(Takeover.yourName())) continue;
            rejoin(e.name(), at, e.role(), e.importance(), "idle", joined.size() * DEFAULT_SPACING_MS);
            joined.add(e.name());
        }
        if (joined.isEmpty()) return error(c, which.equalsIgnoreCase("all") ? "No saved agents to load (all online?)" : "No offline saved agent named " + which);
        return feedback(c, "Loading " + joined.size() + " agent(s): " + String.join(", ", joined));
    }

    private static void queueJoin(Agent agent, long at) {
        AGENTS.put(agent.name.toLowerCase(), agent);
        Roster.remember(agent);
        PENDING_JOIN.add(new Scheduled(agent, at));
    }

    /** Gone for good: it leaves and is never brought back (a lava scout whose job is done). */
    /** Agents retired for good (lower case): never brought back, by Civ or by a reconnect. */
    public static final java.util.Set<String> RETIRED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void retire(dev.agentnet.role.AgentHandle h) {
        if (h instanceof Agent a) { RETIRED.add(a.name.toLowerCase()); notify("§7[" + a.name + "] retired: not coming back"); removeAgent(a); }
    }

    /** Drop an agent without the "left" bookkeeping (used when you take it over). */
    static void removeAgent(Agent a) {
        a.leave();
        AGENTS.remove(a.name.toLowerCase(), a);
    }

    /** Bring an agent back as a bot with its old settings, after a delay. */
    static Agent rejoin(String name, String address, Role role, int importance, String task, long delayMs) {
        Agent a = new Agent(name, address);
        a.importance = importance;
        a.setRole(role);
        a.task = task == null ? "idle" : task;
        queueJoin(a, System.currentTimeMillis() + delayMs);
        return a;
    }

    /**
     * Reconnect an agent so the server sends it the world again (it was dropped to save
     * memory), then continue the trip it wanted to make.
     */
    // ---------------------------------------------------------------- auto-reconnect

    /** name -> [attempts, time of the last attempt]. */
    private static final java.util.Map<String, long[]> RECONNECTS = new java.util.HashMap<>();
    private static final long[] RECONNECT_DELAYS = {5_000, 15_000, 30_000, 60_000, 120_000, 300_000};
    private static final int MAX_RECONNECTS = 12;
    /** Kick messages that mean "don't come back" (or that you took this name over). */
    private static final java.util.regex.Pattern NO_RETRY = java.util.regex.Pattern.compile(
            "(?i)banned|white-?list|not allowed|logged in from another location|duplicate|agent left|outdated|incompatible|online-mode|authentication");

    /** Turn auto-reconnect on/off (/agent reconnect on|off). */
    static boolean autoReconnect = true;

    /**
     * An agent got disconnected without being told to leave (server restart, timeout, kick).
     * Bring it back with its role, importance and task, backing off: 5 s, 15 s, 30 s, 1 min,
     * 2 min, then every 5 min, up to 12 tries. Bans, whitelist kicks and duplicate logins
     * (you took the name) are never retried. Main thread.
     */
    static void autoReconnect(Agent a, String why) {
        if (!autoReconnect || a.leaveScheduled || "left".equals(a.status()) || RETIRED.contains(a.name.toLowerCase())) return;
        if (NO_RETRY.matcher(why).find()) { notify("§7[" + a.name + "] won't reconnect (" + why + ")"); return; }
        if (AGENTS.get(a.name.toLowerCase()) != a) return; // replaced already (takeover, manual rejoin)
        long now = System.currentTimeMillis();
        long[] r = RECONNECTS.computeIfAbsent(a.name.toLowerCase(), k -> new long[2]);
        if (now - r[1] > 10 * 60_000) r[0] = 0; // it stayed online a while: start the backoff over
        if (r[0] >= MAX_RECONNECTS) { notify("§c[" + a.name + "] gave up reconnecting after " + MAX_RECONNECTS + " tries"); return; }
        long delay = RECONNECT_DELAYS[(int) Math.min(r[0], RECONNECT_DELAYS.length - 1)];
        r[0]++;
        r[1] = now + delay;
        AGENTS.remove(a.name.toLowerCase(), a);
        Agent fresh = rejoin(a.name, a.address, a.role(), a.importance, a.task, delay);
        fresh.replayTask = true;
        notify("§7[" + a.name + "] reconnecting in " + (delay / 1000) + " s (try " + r[0] + ")");
    }

    /** Leave clutch: drop the connection just before a hard landing and come right back. */
    static void leaveClutch(Agent a) {
        if (AGENTS.get(a.name.toLowerCase()) != a) return;
        removeAgent(a);
        Agent fresh = rejoin(a.name, a.address, a.role(), a.importance, a.task, 1000);
        fresh.replayTask = true;
    }

    /** Out of the game for a while (mobs it cannot fight), then back as it was: same role, same job, same module. */
    static void leaveFor(Agent a, long ms) {
        if (AGENTS.get(a.name.toLowerCase()) != a) return;
        Agent.AWAY.put(a.name.toLowerCase(), new Object[]{a.role(), System.currentTimeMillis() + ms + 20_000});
        Agent fresh = rejoin(a.name, a.address, a.role(), a.importance, a.task, ms);
        fresh.adoptModule(a); // before the old one leaves: its onStop would reset the job
        a.leave();
    }

    static void reconnect(Agent a, dev.agentnet.pathing.PathFinder.Node goal, int radius) {
        Agent fresh = rejoin(a.name, a.address, a.role(), a.importance, a.task, 1500);
        fresh.adoptModule(a); // before the old one leaves: its onStop would reset the job
        a.leave();
        fresh.navigateWhenOnline(goal, radius);
    }

    // ================================================================= leave

    /**
     * /agent leave <name>              one agent, now
     * /agent leave all [duration]      everyone; with a duration, one by one at random times
     * /agent leave <count> [duration]  the <count> least important agents
     * With a duration, the least important agents get the earliest leave times.
     */
    private static int leave(CommandContext<FabricClientCommandSource> c, String durationArg) {
        String arg = StringArgumentType.getString(c, "all|count|name");
        long durationMs = 0;
        if (!durationArg.isEmpty()) {
            if (!DURATION.matcher(durationArg).matches()) return error(c, "Bad duration '" + durationArg + "', use e.g. 30s, 10m, 2h, 1d");
            durationMs = parseDuration(durationArg);
            if (durationMs > MAX_DURATION_MS) return error(c, "Duration can be at most 30d");
        }

        boolean all = arg.equalsIgnoreCase("all");
        if (!all && !COUNT.matcher(arg).matches()) {
            Agent a = AGENTS.get(arg.toLowerCase());
            if (a == null) return error(c, "No agent named " + arg);
            scheduleLeaves(List.of(a), durationMs);
            return feedback(c, a.name + (durationMs > 0 ? " will leave within " + formatDuration(durationMs) : " left"));
        }

        // Candidates, least important first; random order among equals.
        List<Agent> candidates = new ArrayList<>(AGENTS.values().stream().filter(a -> !a.leaveScheduled && !a.isClosed()).toList());
        Collections.shuffle(candidates);
        candidates.sort(Comparator.comparingInt(a -> a.importance));
        int count = all ? candidates.size() : Integer.parseInt(arg);
        if (candidates.isEmpty()) return error(c, "No agents to remove");
        if (count < 1) return error(c, "Count must be at least 1");
        List<Agent> chosen = candidates.subList(0, Math.min(count, candidates.size()));

        scheduleLeaves(chosen, durationMs);
        String when = durationMs > 0 ? " one by one over " + formatDuration(durationMs) : "";
        return feedback(c, "Removing " + chosen.size() + " agent(s)" + when + ": " + names(chosen));
    }

    private static void scheduleLeaves(List<Agent> agents, long durationMs) {
        if (durationMs <= 0) {
            for (Agent a : agents) {
                a.leave();
                AGENTS.remove(a.name.toLowerCase(), a);
            }
            return;
        }
        // Random moments in the window, earliest going to the first (least important) agent.
        long now = System.currentTimeMillis();
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < agents.size(); i++) times.add(now + ThreadLocalRandom.current().nextLong(durationMs));
        Collections.sort(times);
        for (int i = 0; i < agents.size(); i++) {
            Agent a = agents.get(i);
            a.leaveScheduled = true;
            PENDING_LEAVE.add(new Scheduled(a, times.get(i)));
        }
    }

    // ================================================================= roles + POIs

    private static int assignRole(CommandContext<FabricClientCommandSource> c) {
        String arg = StringArgumentType.getString(c, "role");
        Role role = arg.equalsIgnoreCase("none") ? null : Role.parse(arg);
        if (role == null && !arg.equalsIgnoreCase("none")) return error(c, "Unknown role '" + arg + "'. See /agent roles");
        List<Agent> t = targets(c);
        t.forEach(a -> a.setRole(role));
        return t.isEmpty() ? 0 : feedback(c, names(t) + " → " + (role == null ? "no role" : role.displayName));
    }

    /** Farm corners you've marked, like WorldEdit's pos1/pos2. */
    private static int[] corner1, corner2;

    /** /agent chest add|remove: the chest at x y z, or the one you're looking at. */
    private static int chestCmd(CommandContext<FabricClientCommandSource> c, boolean add, int[] at) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null || Servers.current() == null) return error(c, "You're not in a world");
        if (at == null) {
            if (!(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit) || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK)
                return error(c, "Look at a chest (or give x y z)");
            at = new int[]{hit.getBlockPos().getX(), hit.getBlockPos().getY(), hit.getBlockPos().getZ()};
        }
        String dim = mc.level.dimension().identifier().toString();
        if (!add) {
            dev.agentnet.role.ChestMemory.forget(Servers.current(), dim, at[0], at[1], at[2]);
            return feedback(c, "Forgot the chest at " + at[0] + " " + at[1] + " " + at[2]);
        }
        var block = mc.level.getBlockState(new net.minecraft.core.BlockPos(at[0], at[1], at[2])).getBlock();
        if (!(block instanceof net.minecraft.world.level.block.ChestBlock || block instanceof net.minecraft.world.level.block.BarrelBlock))
            return error(c, "That's not a chest or barrel");
        dev.agentnet.role.ChestMemory.remember(Servers.current(), dim, at[0], at[1], at[2], "storage");
        return feedback(c, "Storage chest added at " + at[0] + " " + at[1] + " " + at[2] + ". Distributors store their stock there and fetch from it");
    }

    private static int[] schem1, schem2;

    private static int schemCorner(CommandContext<FabricClientCommandSource> c, int which, int[] given) {
        int[] pos = given;
        if (pos == null) {
            var p = c.getSource().getPlayer();
            if (p == null) return error(c, "You're not in a world");
            pos = new int[]{p.getBlockX(), (int) Math.floor(p.getY() + 0.2), p.getBlockZ()};
        }
        if (which == 1) schem1 = pos; else schem2 = pos;
        return feedback(c, "Schematic corner " + which + " set to " + pos[0] + " " + pos[1] + " " + pos[2]
                + (schem1 != null && schem2 != null ? ". Now: /agent schematic save <name>" : ""));
    }

    private static int setCorner(CommandContext<FabricClientCommandSource> c, int which) {
        var p = c.getSource().getPlayer();
        if (p == null) return error(c, "You're not in a world");
        // The block your feet are in, also on farmland/paths (15/16 high) and soul sand.
        int[] pos = {p.getBlockX(), (int) Math.floor(p.getY() + 0.2), p.getBlockZ()};
        if (which == 1) corner1 = pos; else corner2 = pos;
        return feedback(c, "Farm corner " + which + " set to " + pos[0] + " " + pos[1] + " " + pos[2]
                + (corner1 != null && corner2 != null ? ". Now: /agent farm add <name> crops|trees" : ""));
    }

    private static int addFarm(CommandContext<FabricClientCommandSource> c) {
        Farms.Type type = Farms.Type.parse(StringArgumentType.getString(c, "type"));
        if (type == null) return error(c, "Farm type must be crops or trees");
        if (corner1 == null || corner2 == null) return error(c, "Mark both corners first: /agent farm pos1, /agent farm pos2");
        String server = Servers.current();
        var level = c.getSource().getClient().level;
        if (server == null || level == null) return error(c, "You're not on a server");
        String name = StringArgumentType.getString(c, "name");
        Farms.Farm farm = new Farms.Farm(type, server, level.dimension().identifier().toString(),
                corner1[0], corner1[1], corner1[2], corner2[0], corner2[1], corner2[2], name);
        if (farm.blocks() > 200_000) return error(c, "That farm is too big (max 200,000 blocks); make smaller farms");
        if (!Farms.add(farm)) return error(c, "A farm named '" + name + "' already exists");
        corner1 = corner2 = null;
        return feedback(c, String.format("%s farm '%s' added (%d x %d). Give an agent /agent role <name> farmer",
                type.id(), name, farm.x2() - farm.x1() + 1, farm.z2() - farm.z1() + 1));
    }

    private static int addSpot(CommandContext<FabricClientCommandSource> c) {
        Spots.Type type = Spots.Type.parse(StringArgumentType.getString(c, "type"));
        if (type == null) return error(c, "Spot type must be fishing (farms use /agent farm)");
        String server = Servers.current();
        var player = c.getSource().getPlayer();
        var level = c.getSource().getClient().level;
        if (server == null || player == null || level == null) return error(c, "You're not on a server");
        String name = StringArgumentType.getString(c, "name");
        // Stand in the middle of the block, facing the way you look now.
        Spots.Spot spot = new Spots.Spot(type, server, level.dimension().identifier().toString(),
                player.getBlockX() + 0.5, player.getY(), player.getBlockZ() + 0.5,
                player.getYRot(), player.getXRot(), name);
        if (!Spots.add(spot)) return error(c, "A spot named '" + name + "' already exists");
        return feedback(c, String.format("%s spot '%s' added at %d %d %d, facing yaw %.0f pitch %.0f",
                type.id(), name, player.getBlockX(), player.getBlockY(), player.getBlockZ(), player.getYRot(), player.getXRot()));
    }

    private static int addPoi(CommandContext<FabricClientCommandSource> c, int x, int y, int z) {
        String server = Servers.current();
        var level = c.getSource().getClient().level;
        if (server == null || level == null) return error(c, "You're not on a server");
        String name = StringArgumentType.getString(c, "name");
        String dim = level.dimension().identifier().toString();
        if (!Pois.add(server, dim, x, y, z, name)) return error(c, "A POI named '" + name + "' already exists");
        return feedback(c, "POI '" + name + "' added at " + x + " " + y + " " + z);
    }

    // ================================================================= actions

    /** Records the task on each target. Nothing carries it out yet: that's the brain's job. */
    private static int setTask(CommandContext<FabricClientCommandSource> c, String task) {
        List<Agent> t = targets(c);
        if (t.isEmpty()) return 0;
        // "all" means the workers: the crew's distributors, storage keepers, builders and guards stay on their
        // jobs (name one of them to move it). Taking the only distributor leaves nobody to hand things out.
        List<Agent> kept = new ArrayList<>();
        if (StringArgumentType.getString(c, "agent").equalsIgnoreCase("all") && !task.equals("idle") && !task.matches("(goto|follow|come)( .*)?")) {
            for (Agent a : t) if (a.role() == Role.DISTRIBUTOR || a.role() == Role.KEEPER || a.role() == Role.WARRIOR
                    || a.role() == Role.BUILDER && a.task.startsWith("build")) kept.add(a);
            if (!kept.isEmpty() && kept.size() < t.size()) { t = new ArrayList<>(t); t.removeAll(kept); }
            else kept.clear();
        }
        if (!kept.isEmpty()) c.getSource().sendFeedback(Component.literal("§7kept on their jobs: " + names(kept)));
        t.forEach(a -> a.setTask(task));
        return feedback(c, (task.equals("idle") ? "Stopped " : "Task '" + task + "' set for ") + names(t)
                + (task.equals("idle") || task.matches("(goto|make|brew|mine|explore|build|kill|trade|speedrun|loot|hunt|grind|fish|farm|guard|keep|pickup|rescue|follow|come)( .*)?") ? "" : " §7(no brain yet, they won't act on it)"));
    }

    /** Resolves the "agent" argument: a name, or "all". Reports an error if nothing matches. */
    private static List<Agent> targets(CommandContext<FabricClientCommandSource> c) {
        String who = StringArgumentType.getString(c, "agent");
        if (who.equalsIgnoreCase("all")) {
            List<Agent> list = new ArrayList<>(AGENTS.values());
            if (list.isEmpty()) error(c, "No agents");
            return list;
        }
        Agent a = AGENTS.get(who.toLowerCase());
        if (a == null) { error(c, "No agent named " + who); return List.of(); }
        return List.of(a);
    }

    // ================================================================= helpers

    /** "90" or "90s" = 90 seconds, "5m", "2h", "3d", "1d12h". */
    static long parseDuration(String s) {
        long ms = 0;
        Matcher m = DURATION_PART.matcher(s);
        while (m.find()) {
            long n = Math.min(Long.parseLong(m.group(1)), 1_000_000L); // avoid overflow on silly input
            ms += switch (m.group(2).toLowerCase()) {
                case "d" -> n * 86_400_000L;
                case "h" -> n * 3_600_000L;
                case "m" -> n * 60_000L;
                default -> n * 1_000L;
            };
        }
        return ms;
    }

    static String formatDuration(long ms) {
        long s = Math.max(1, Math.round(ms / 1000.0));
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m" + (s % 60 > 0 ? " " + s % 60 + "s" : "");
        if (s < 86400) return (s / 3600) + "h" + (s % 3600 / 60 > 0 ? " " + s % 3600 / 60 + "m" : "");
        return (s / 86400) + "d" + (s % 86400 / 3600 > 0 ? " " + s % 86400 / 3600 + "h" : "");
    }

    private static String names(List<Agent> agents) {
        return String.join(", ", agents.stream().map(a -> a.name).toList());
    }

    private static int listNames(CommandContext<FabricClientCommandSource> c, String title, List<String> names) {
        return feedback(c, title + " (" + names.size() + "): " + (names.isEmpty() ? "empty" : String.join(", ", names)));
    }

    private static int feedback(CommandContext<FabricClientCommandSource> c, String msg) {
        c.getSource().sendFeedback(Component.literal(msg));
        return 1;
    }

    private static int error(CommandContext<FabricClientCommandSource> c, String msg) {
        c.getSource().sendError(Component.literal(msg));
        return 0;
    }

    /** Show a message in your chat from any thread. */
    /** Every agent message in chat ("/agent chat all"), or only the important ones (the default). The log gets all. */
    static boolean chatAll = false;

    /** Worth seeing in chat: attacks and the hitlist, a finished base or build, crashes and deaths. */
    static boolean important(String msg) {
        String m = msg.replaceAll("§.", "").toLowerCase();
        return msg.contains("§4") || m.contains("hitlist") || m.contains("attacked by") || m.contains("base is finished")
                || m.contains("finished its part") || m.contains("module crashed") || m.contains(" died") || m.contains("was killed")
                || m.contains("storage is missing") || m.contains("] afk:") || m.contains("autopilot on") || m.contains("autopilot off");
    }

    public static void notify(String msg) {
        AgentNet.LOG.info(msg.replaceAll("§.", ""));
        MasterAgent.event(msg);
        if (!chatAll && !important(msg)) return; // the rest is only in the log ("/agent chat all" shows it here again)
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> { if (mc.player != null) mc.player.sendSystemMessage(Component.literal(msg)); });
    }
}
