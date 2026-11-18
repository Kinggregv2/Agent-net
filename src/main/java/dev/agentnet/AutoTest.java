package dev.agentnet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Deque;

import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.GameType;

/**
 * Development test runner. Does nothing unless agentnet/autotest.txt exists. When it
 * does, runs it once after joining a world, then renames it to autotest.done so it never
 * runs again by accident. Everything it does is logged with the "[AutoTest]" prefix.
 *
 * <pre>
 * wait 5                    pause (seconds)
 * online Name 90            wait until that agent is in the game (at most 90 s), then 3 s more
 * savepos / restorepos      remember / restore your position and game mode
 * /agent ...                AgentNet command
 * /anything else            server command (you need the permission for it)
 * log some text             write a marker to the log
 * agents                    log every agent's state
 * quit                      close the game
 * </pre>
 */
final class AutoTest {
    private static Deque<String> script;
    private static int waitTicks;
    /** "online": the agent the script waits for, and until when. */
    private static String waitOnline;
    private static long waitOnlineUntil;
    private static double[] savedPos;
    private static GameType savedMode;
    private static int joinDelay = 100; // let the world settle after joining
    private static int inboxCheck;

    private AutoTest() {}

    static void tick(Minecraft mc) {
        if (script == null) {
            if (mc.player == null || mc.getConnection() == null) return;
            if (--joinDelay > 0) return;
            load();
            if (script == null) script = new ArrayDeque<>(); // nothing to run; never check again
            // The civ was running when the game closed: bring the same crew back and carry on.
            if (script.isEmpty()) script.addAll(Civ.resumeScript());
            return;
        }
        // A running game takes more: agentnet/inbox.txt (same format) is picked up whenever nothing else is running.
        if (script.isEmpty() && mc.player != null && ++inboxCheck % 5 == 0) {
            Path inbox = AgentFiles.dir().resolve("inbox.txt");
            if (Files.exists(inbox)) {
                try {
                    script.addAll(Files.readAllLines(inbox, StandardCharsets.UTF_8));
                    Files.delete(inbox);
                    AgentNet.LOG.info("[AutoTest] inbox: {} lines", script.size());
                } catch (IOException e) { AgentNet.LOG.warn("[AutoTest] could not read {}", inbox, e); }
            }
        }
        if (script.isEmpty() || mc.player == null || mc.getConnection() == null) return;
        if (waitTicks > 0) { waitTicks--; return; }
        if (waitOnline != null) {
            Agent ag = AgentNetClient.AGENTS.get(waitOnline.toLowerCase());
            if (ag != null && ag.isOnline()) { AgentNet.LOG.info("[AutoTest] {} is in the game", waitOnline); waitOnline = null; waitTicks = 60; return; }
            if (System.currentTimeMillis() < waitOnlineUntil) return;
            AgentNet.LOG.warn("[AutoTest] {} did not come online in time: going on anyway", waitOnline);
            waitOnline = null;
        }
        String line = script.poll().strip();
        if (line.isEmpty() || line.startsWith("#")) return;
        AgentNet.LOG.info("[AutoTest] > {}", line);
        try {
            run(mc, line);
        } catch (Exception e) {
            AgentNet.LOG.warn("[AutoTest] failed: {}", line, e);
        }
    }

    private static volatile String locateFor, locateDim, locateUnder;
    private static boolean listening;
    private static final java.util.regex.Pattern AT = java.util.regex.Pattern.compile("\\[(-?\\d+), ?[~\\-\\d]+, ?(-?\\d+)\\]");

    /** "/locate" answers in chat: when one comes in for a pending "locate" line, move that agent there. */
    private static void listenChat() {
        if (listening) return;
        listening = true;
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register((msg, overlay) -> {
            String who = locateFor;
            if (who == null) return;
            String text = msg.getString();
            java.util.regex.Matcher m = AT.matcher(text);
            if (!text.contains("nearest") || !m.find()) {
                if (text.toLowerCase().contains("could not find")) { AgentNet.LOG.warn("[AutoTest] locate failed: {}", text); locateFor = null; }
                return;
            }
            locateFor = null;
            AgentNet.LOG.info("[AutoTest] located: {}", text);
            command(Minecraft.getInstance(), "execute in " + locateDim + " run spreadplayers " + m.group(1) + " " + m.group(2) + " 0 16 " + (locateUnder != null ? "under " + locateUnder + " " : "") + "false " + who);
        });
    }

    /**
     * A server command from a test script. On your own world it runs on the built-in server as you, with full
     * permission, so test scripts work in a world without cheats (the world's own settings stay as they are);
     * elsewhere it goes to the server like typing it.
     */
    static void command(Minecraft mc, String cmd) {
        var local = mc.getSingleplayerServer();
        if (local != null && mc.player != null) {
            java.util.UUID me = mc.player.getUUID();
            local.execute(() -> {
                var p = local.getPlayerList().getPlayer(me);
                var src = (p != null ? p.createCommandSourceStack() : local.createCommandSourceStack())
                        .withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER);
                local.getCommands().performPrefixedCommand(src, cmd);
            });
            return;
        }
        mc.getConnection().sendCommand(cmd);
    }

    private static void load() {
        Path file = AgentFiles.dir().resolve("autotest.txt");
        if (!Files.exists(file)) return;
        try {
            script = new ArrayDeque<>(Files.readAllLines(file, StandardCharsets.UTF_8));
            Files.move(file, file.resolveSibling("autotest.done"), StandardCopyOption.REPLACE_EXISTING);
            AgentNet.LOG.info("[AutoTest] running {} lines", script.size());
        } catch (IOException e) {
            AgentNet.LOG.warn("[AutoTest] could not read {}", file, e);
        }
    }

    private static void run(Minecraft mc, String line) throws Exception {
        String[] p = line.split("\\s+", 2);
        switch (p[0]) {
            case "wait" -> waitTicks = (int) (Double.parseDouble(p[1]) * 20);
            case "online" -> { String[] o = line.strip().split("\s+"); waitOnline = o[1]; waitOnlineUntil = System.currentTimeMillis() + (long) ((o.length > 2 ? Double.parseDouble(o[2]) : 90) * 1000); }
            case "log" -> AgentNet.LOG.info("[AutoTest] ---- {}", p.length > 1 ? p[1] : "");
            case "savepos" -> {
                savedPos = new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()};
                savedMode = mc.gameMode.getPlayerMode();
                AgentNet.LOG.info("[AutoTest] saved {} {} {} {}", savedPos[0], savedPos[1], savedPos[2], savedMode);
            }
            case "restorepos" -> {
                if (savedPos == null) return;
                command(mc, String.format(java.util.Locale.ROOT, "tp @s %.3f %.3f %.3f", savedPos[0], savedPos[1], savedPos[2]));
                command(mc, "gamemode " + savedMode.getName());
            }
            case "agents" -> {
                for (Agent a : AgentNetClient.AGENTS.values()) {
                    AgentNet.LOG.info("[AutoTest] agent {} at {} {} {} | {} | travel: {} | inv {}/36 | chunks {} | {}",
                            a.name, Math.round(a.x()), Math.round(a.y()), Math.round(a.z()), a.describe(), a.navStatus(), a.usedSlots(), a.chunkCount(), a.moduleStatus());
                }
            }
            case "perf" -> {
                Runtime rt = Runtime.getRuntime();
                AgentNet.LOG.info("[AutoTest] perf: {} fps, memory {} / {} MB", mc.getFps(),
                        (rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20);
            }
            case "mapfeed" -> AgentNet.LOG.info("[AutoTest] map feed: {} chunk(s) waiting, {} drawn",
                    dev.agentnet.map.MapFeed.queued(), dev.agentnet.map.MapFeed.served());
            case "master" -> { MasterAgent.input(p.length > 1 ? p[1] : ""); MasterAgent.LOG.forEach(l -> AgentNet.LOG.info("[AutoTest] master log: {}", l.replaceAll("§.", ""))); MasterAgent.LOG.clear(); }
            case "screen" -> mc.gui.setScreen(switch (p.length > 1 ? p[1] : "") {
                case "master" -> new MasterScreen();
                case "view" -> new ViewScreen(null);
                default -> null;
            });
            case "screenshot" -> { net.minecraft.client.Screenshot.grab(mc, false); AgentNet.LOG.info("[AutoTest] screenshot {}", p.length > 1 ? p[1] : ""); }
            case "key" -> { // press a key binding by (part of) its name, e.g. "key open_map"
                String want = p.length > 1 ? p[1].toLowerCase() : "";
                for (net.minecraft.client.KeyMapping k : mc.options.keyMappings) {
                    if (k.getName().toLowerCase().contains(want)) { net.minecraft.client.KeyMapping.click(k.getDefaultKey()); AgentNet.LOG.info("[AutoTest] pressed {}", k.getName()); break; }
                    if (k.getName().contains("xaero")) AgentNet.LOG.info("[AutoTest] key binding: {}", k.getName());
                }
            }
            case "blockinfo" -> { // blockinfo <agent> <x> <y> <z>: the true blocks round a spot (your own world) beside what the agent sees
                String[] q = p[1].trim().split("\s+");
                Agent ag = AgentNetClient.AGENTS.get(q[0].toLowerCase());
                int bx = Integer.parseInt(q[1]), by = Integer.parseInt(q[2]), bz = Integer.parseInt(q[3]);
                var srv = mc.getSingleplayerServer();
                var view = ag == null ? null : ag.openWorld();
                StringBuilder sb = new StringBuilder();
                for (int dy = 2; dy >= -2; dy--) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    var pos = new net.minecraft.core.BlockPos(bx + dx, by + dy, bz + dz);
                    String truth = srv == null ? "?" : srv.overworld().getBlockState(pos).toString().replace("Block{minecraft:", "").replace("}", "");
                    var vs = view == null ? null : view.get(pos.getX(), pos.getY(), pos.getZ());
                    String seen = vs == null ? "?" : vs.toString().replace("Block{minecraft:", "").replace("}", "");
                    sb.append(String.format("%n  %d %d %d: server %s | agent %s%s", pos.getX(), pos.getY(), pos.getZ(), truth, seen, truth.equals(seen) ? "" : "   <-- DIFFERENT"));
                }
                AgentNet.LOG.info("[AutoTest] blocks round {} {} {}:{}", bx, by, bz, sb);
            }
            case "lan" -> AgentNet.LOG.info("[AutoTest] agents join at {}", dev.agentnet.map.Servers.joinAddress());
            case "mapstats" -> AgentNet.LOG.info("[AutoTest] map feed: {}", XaeroMapFeed.stats());
            case "seen" -> {
                for (Agent a : AgentNetClient.AGENTS.values()) {
                    AgentNet.LOG.info("[AutoTest] {} sees: {}", a.name, a.entitiesNear(64).stream()
                            .map(s -> s.name() + "#" + s.id() + String.format("@%.0f", s.distance()) + (s.blocking() ? "[BLOCKING]" : s.usingItem() ? "[using]" : "") + (s.swelling() ? "[hiss]" : "")).toList());
                }
            }
            case "tpagents" -> { for (Agent a : AgentNetClient.AGENTS.values()) command(mc, "tp " + a.name + " " + p[1]); }
            case "forgetagents" -> { for (Agent a : AgentNetClient.AGENTS.values()) Roster.forget(dev.agentnet.map.Servers.current(), a.name); }
            case "launch" -> { String[] q = p[1].split("\s+"); Agent ag = AgentNetClient.AGENTS.get(q[0].toLowerCase()); if (ag != null) { net.minecraft.world.phys.Vec3 v = new net.minecraft.world.phys.Vec3(Double.parseDouble(q[1]), Double.parseDouble(q[2]), Double.parseDouble(q[3])); mc.execute(() -> ag.launch(v)); } else AgentNet.LOG.warn("[AutoTest] no agent {}", q[0]); }
            case "locate" -> { // locate <structure> <agent> [dimension]: put the agent on the surface above the nearest one
                String[] q = p[1].split("\\s+");
                // locate <structure> <agent> [dimension] [under <y>]: "under" puts it in the caves below y (underground structures).
                String dim = q.length > 2 && !q[2].equals("under") ? q[2] : "minecraft:overworld";
                locateUnder = null;
                for (int i = 2; i + 1 < q.length; i++) if (q[i].equals("under")) locateUnder = q[i + 1];
                listenChat();
                locateFor = q[1];
                locateDim = dim;
                command(mc, "execute in " + dim + " run locate structure minecraft:" + q[0]);
            }
            case "inv" -> { // inv <agent>: what it carries
                Agent ag = AgentNetClient.AGENTS.get(p[1].trim().toLowerCase());
                if (ag == null) { AgentNet.LOG.warn("[AutoTest] no agent {}", p[1]); return; }
                java.util.Map<String, Integer> m = new java.util.TreeMap<>();
                for (var s : ag.inventory()) if (!s.isEmpty()) m.merge(dev.agentnet.role.Gear.id(s).replace("minecraft:", ""), s.getCount(), Integer::sum);
                AgentNet.LOG.info("[AutoTest] {} carries {}", ag.name, m);
            }
            case "blocks" -> { // blocks x y z: the 3x3 columns around it, y-2..y+2, from your own client's world
                String[] q = p[1].trim().split("\\s+");
                int bx = Integer.parseInt(q[0]), by = Integer.parseInt(q[1]), bz = Integer.parseInt(q[2]);
                for (int dy = 2; dy >= -2; dy--) {
                    StringBuilder sb = new StringBuilder("y=" + (by + dy) + ":");
                    for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                        var st = mc.level.getBlockState(new net.minecraft.core.BlockPos(bx + dx, by + dy, bz + dz));
                        sb.append(" [").append(dx).append(",").append(dz).append("]").append(st.toString().replace("Block{minecraft:", "").replace("}", ""));
                    }
                    AgentNet.LOG.info("[AutoTest] {}", sb);
                }
            }
            case "quit" -> mc.stop();
            default -> {
                if (!line.startsWith("/")) { AgentNet.LOG.warn("[AutoTest] unknown line: {}", line); return; }
                String cmd = line.substring(1);
                if (cmd.startsWith("agent ") || cmd.equals("agent")) {
                    var source = (FabricClientCommandSource) mc.getConnection().getSuggestionsProvider();
                    ClientCommands.getActiveDispatcher().execute(cmd, source);
                } else {
                    command(mc, cmd);
                }
            }
        }
    }
}
