package dev.agentnet;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.agentnet.bus.Message;
import dev.agentnet.role.AgentHandle;
import dev.agentnet.role.Role;
import dev.agentnet.role.RoleModule;
import net.minecraft.world.item.ItemStack;
import dev.agentnet.map.Servers;
import dev.agentnet.pathing.ChunkStore;
import dev.agentnet.pathing.Navigator;
import dev.agentnet.pathing.PathFinder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientRegistryLayer;
import net.minecraft.client.multiplayer.KnownPacksManager;
import net.minecraft.client.multiplayer.RegistryDataCollector;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.configuration.*;
import net.minecraft.network.protocol.cookie.ClientboundCookieRequestPacket;
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.login.*;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.phys.Vec3;

/**
 * One headless player: its own network connection to the server, with no window,
 * no world rendering and no extra game client. It does only what's needed to stay
 * online: answers keep-alives, confirms the positions the server sets, respawns.
 */
public final class Agent implements AgentHandle {
    /** What the agent tells the server about its "client": the view distance comes from its role (RoleView). */
    private ClientInformation info() {
        return new ClientInformation("en_us", RoleView.get(role), ChatVisiblity.FULL, true, 0x7F, HumanoidArm.RIGHT, false, true, ParticleStatus.MINIMAL);
    }

    /** Tell the server again (role or its view distance changed): it sends more or fewer chunks from now on. */
    int chunkCount() { return chunks.size(); }

    /** Milliseconds since it got into the world (0 if not online). */
    long onlineFor() { return isOnline() ? System.currentTimeMillis() - onlineSince : 0; }

    void sendClientInfo() {
        if (inGame) send(new ServerboundClientInformationPacket(info()));
    }

    final String name;
    final String address;
    private final Minecraft mc = Minecraft.getInstance();
    private volatile Connection connection;
    private volatile boolean closed;
    private volatile String status = "waiting to join";

    private RegistryDataCollector registryCollector = new RegistryDataCollector();
    private KnownPacksManager knownPacks;
    private volatile PositionMoveRotation pos = new PositionMoveRotation(Vec3.ZERO, Vec3.ZERO, 0f, 0f);
    private volatile boolean inGame;
    private boolean sentLoaded;

    /** 1 (expendable) .. 10 (keep at all costs). Least important agents leave first. */
    volatile int importance = 5;
    /** What the agent has been told to do. Nothing acts on it until agents get brains. */
    volatile String task = "idle";
    /** Set when rejoining after a kick: hand the task to the module again once it starts. */
    volatile boolean replayTask;
    /** Was in the world at least once (a failed first login is not retried). */
    volatile boolean everInGame;
    /** Set once a timed leave is scheduled, so it isn't picked twice. */
    volatile boolean leaveScheduled;

    // Damage tracking for the hitlist (game-phase netty thread only).
    private int myEntityId = Integer.MIN_VALUE;
    private final Map<Integer, UUID> playerEntities = new HashMap<>();
    private float lastHealth = Float.NaN;
    private float maxHealth = 20f;
    private String lastAttacker;
    /** Players (lower-case) who hit any agent, and when: enemies of the whole crew for {@link #ATTACKER_MS}. */
    static final Map<String, Long> ATTACKERS = new ConcurrentHashMap<>();
    static final long ATTACKER_MS = 20_000;
    private long lastAttackAt;
    private final Map<String, Float> damageThisLife = new HashMap<>();
    /** Player UUID -> name, from the tab list. Shared: every agent sees the same players. */
    private static final Map<UUID, String> PLAYER_NAMES = new ConcurrentHashMap<>();

    private volatile ResourceKey<Level> dimensionKey;
    private volatile String dimension;
    private volatile int dimMinY, dimHeight;

    // ---- pathfinding: raw chunks kept cheaply, decoded only during a search, all dropped after a trip
    /** Drop the raw chunks after this long without a trip. */
    private static final long IDLE_WORLD_MS = 60_000;
    /** After (re)joining, wait this long for chunks before giving up on a pending trip. */
    private static final long WAIT_FOR_WORLD_MS = 10_000;
    private final ChunkStore chunks = new ChunkStore();
    private final NavBody navBody = new NavBody();
    private final Navigator navigator = new Navigator(navBody);
    private volatile RegistryAccess.Frozen gameRegistries;
    private long onlineSince;
    /** A trip waiting for chunks to arrive (right after joining). Main thread only. */
    private PathFinder.Node pendingGoal;
    private int pendingRadius;
    private long pendingSince;
    /** Set while health is under half, so the low-health alert fires once per drop. */
    private boolean lowHealth;
    private long lowHealthAt;

    Agent(String name, String address) {
        this.name = name;
        this.address = address;
    }

    String status() { return status; }
    boolean isClosed() { return closed; }

    // ---------------------------------------------------------------- role + AgentHandle

    private volatile Role role;
    private RoleModule module;          // main thread only
    private boolean moduleStarted;      // main thread only

    /** A reconnect: take over the old connection's role module, state and all, so the job goes on where it was. */
    void adoptModule(Agent old) {
        if (old.module == null || old.role != role) { replayTask = true; return; }
        stopModule(); // the fresh one made by setRole, never started
        module = old.module;
        moduleStarted = false; // onStart runs again on the new connection (keepWorld etc.)
        old.module = null; // the old connection must not stop it on its way out
    }
    /** Main inventory, Inventory indexing: 0-8 hotbar, 9-35 main. Guarded by itself. */
    private final ItemStack[] inventory = new ItemStack[36];
    { java.util.Arrays.fill(inventory, ItemStack.EMPTY); }

    @Override public String name() { return name; }
    @Override public Role role() { return role; }
    @Override public int importance() { return importance; }
    @Override public String task() { return task; }
    @Override public String server() { return Servers.key(address); }
    @Override public String dimension() { return dimension; }
    ResourceKey<Level> dimensionKey() { return dimensionKey; }
    /** The UUID an offline-mode server gives this name. */
    UUID uuid() { return UUIDUtil.createOfflinePlayerUUID(name); }


    @Override public double x() { return pos.position().x; }
    @Override public double y() { return pos.position().y; }
    @Override public double z() { return pos.position().z; }
    @Override public boolean isOnline() { return inGame && !closed; }

    @Override
    public List<ItemStack> inventory() {
        synchronized (inventory) { return List.of(inventory.clone()); }
    }

    @Override
    public int usedSlots() {
        synchronized (inventory) {
            int n = 0;
            for (ItemStack s : inventory) if (!s.isEmpty()) n++;
            return n;
        }
    }

    // ---- hands, looking, bobber (bobber fields written on the netty thread)
    private volatile int selectedSlot;
    private int useSequence;
    private volatile int bobberId = -1;
    private volatile boolean bobberBiting;
    /** Synched-data id of FishingHook.DATA_BITING, looked up once by reflection. */
    private static final int BITING_DATA_ID = bitingDataId();

    private static int bitingDataId() {
        try {
            var f = net.minecraft.world.entity.projectile.FishingHook.class.getDeclaredField("DATA_BITING");
            f.setAccessible(true);
            return ((net.minecraft.network.syncher.EntityDataAccessor<?>) f.get(null)).id();
        } catch (ReflectiveOperationException | RuntimeException e) {
            AgentNet.LOG.warn("Can't find FishingHook.DATA_BITING; fishers won't notice bites", e);
            return -1;
        }
    }

    @Override public float yaw() { return pos.yRot(); }
    @Override public float pitch() { return pos.xRot(); }
    @Override public int selectedSlot() { return selectedSlot; }
    @Override public int bobberId() { return bobberId; }
    @Override public boolean bobberBiting() { return bobberBiting; }

    @Override
    public void selectHotbar(int slot) {
        if (slot < 0 || slot > 8 || slot == selectedSlot) return;
        selectedSlot = slot;
        sendInOrder(new ServerboundSetCarriedItemPacket(slot));
    }

    @Override
    public void look(float yaw, float pitch) {
        if (Float.isFinite(yaw) && Float.isFinite(pitch)) pitch = offEnderman(pos.position().add(0, 1.62, 0), yaw, pitch);
        pos = pos.withRotation(yaw, pitch);
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) { badMove("look " + yaw + " " + pitch); return; }
        // Mid-air this must say onGround=false: "true" makes the server land us right here (fall damage).
        send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, airVel == null, false));
    }

    @Override
    public void useItem() {
        send(new ServerboundUseItemPacket(net.minecraft.world.InteractionHand.MAIN_HAND, ++useSequence, pos.yRot(), pos.xRot()));
    }

    /** Use the held item while crouching (a bucket then fills the space above leaves/slabs instead of waterlogging them). */
    /** Held sneak (ancient cities: sculk sensors don't hear sneaking steps); walking slows to sneak speed. */
    private volatile boolean sneaking;

    @Override public void setSneaking(boolean on) {
        if (sneaking == on) return;
        sneaking = on;
        send(new ServerboundPlayerInputPacket(new net.minecraft.world.entity.player.Input(false, false, false, false, false, on, false)));
    }

    @Override public boolean sneaking() { return sneaking; }

    /** A normal jump (0.42 up); the air physics take it from there. No-op in the air. */
    @Override public void jump() {
        if (airVel == null && !riding()) launch(new Vec3(0, 0.42, 0));
    }

    /** A jump with a push along the ground: over a one-block gap. No-op in the air. */
    @Override public void hop(double vx, double vz) {
        if (airVel == null && !riding()) launch(new Vec3(vx, 0.42, vz));
    }

    @Override public void useItemSneaking() {
        send(new ServerboundPlayerInputPacket(new net.minecraft.world.entity.player.Input(false, false, false, false, false, true, false)));
        useItem();
        unsneakIn = 2;
    }

    @Override
    public void serverCommand(String command) {
        if (isOnline()) send(new ServerboundChatCommandPacket(command.startsWith("/") ? command.substring(1) : command));
    }

    @Override
    public void log(String message) {
        AgentNetClient.notify("§7[" + name + "] " + message);
    }

    /** What the role module says it's doing. */
    String warriorStatus() { return moduleStatus(); }

    /** Its role says there is nothing to do right now. */
    public boolean moduleIdle() { try { return module != null && module.idle(); } catch (RuntimeException e) { return false; } }

    @Override public String moduleStatus() {
        try { return module == null ? "" : module.status(); } catch (RuntimeException e) { return ""; }
    }

    /** Assign a role (null = none). Takes effect immediately. Main thread only. */
    void setRole(Role newRole) {
        if (newRole == role) return;
        stopModule();
        role = newRole;
        module = newRole == null ? null : newRole.createModule();
        // The old job doesn't carry over (a "mine ..." task saved with a rescuer turned it back into a miner on the next start).
        if (newRole != Role.MINER && task != null && task.startsWith("mine")) task = newRole == Role.SPEEDRUNNER || newRole == Role.FALLOFF ? "speedrun" : "idle";
        Roster.remember(this);
        sendClientInfo();
    }

    /** Record a task and hand it to the role module. Main thread only. */
    void setTask(String newTask) {
        task = newTask;
        // Movement tasks are handled by the agent itself, whatever its role.
        String[] p = newTask.split(" ");
        if (p[0].equals("goto") && p.length == 4) {
            try {
                navigate((int) Math.floor(Double.parseDouble(p[1])), (int) Math.floor(Double.parseDouble(p[2])),
                        (int) Math.floor(Double.parseDouble(p[3])), 1);
            } catch (NumberFormatException ignored) {}
        } else if (newTask.equals("idle")) {
            stopNavigation();
        } else if (p[0].equals("mine") && role != Role.MINER) {
            // Only miners mine: "/agent mine" makes the agent one.
            log("now a miner");
            setRole(Role.MINER);
        } else if (p[0].equals("explore") && role != Role.EXPLORER) {
            log("now an explorer");
            setRole(Role.EXPLORER);
        } else if (p[0].equals("trade") && role != Role.TRADER) {
            log("now a trader");
            setRole(Role.TRADER);
        } else if (p[0].equals("speedrun") && role != Role.SPEEDRUNNER && role != Role.FALLOFF) { // a falloff is a speedrunner already
            log("now a speedrunner");
            setRole(Role.SPEEDRUNNER);
        } else if (p[0].equals("grind") && role != Role.TRIAL_GRINDER) {
            log("now a trial grinder");
            setRole(Role.TRIAL_GRINDER);
        } else if ((p[0].equals("loot") || p[0].equals("golem")) && role != Role.LOOTER && role != Role.TRIAL_GRINDER) {
            log("now a looter");
            setRole(Role.LOOTER);
        } else if (p[0].equals("hunt") && role != Role.HUNTER) {
            log("now a hunter-gatherer");
            setRole(Role.HUNTER);
        } else if (p[0].equals("build") && role != Role.BUILDER) {
            log("now a builder");
            setRole(Role.BUILDER);
        }
        if (module != null) {
            try { module.onTask(this, newTask); }
            catch (RuntimeException e) { AgentNet.LOG.warn("[{}] {} onTask failed", name, role, e); }
        }
    }

    /** A bus message for this agent. Main thread only. */
    void deliver(Message m) {
        if (m.type() == Message.Type.HAND_OVER) { startHandOver(m); return; } // any role: give what it can spare
        if (m.type() == Message.Type.STEP_ASIDE && !isDigging()) { // any role: standing where a builder puts a block
            try {
                double bx = Integer.parseInt(m.get("x")) + 0.5, bz = Integer.parseInt(m.get("z")) + 0.5;
                Vec3 p = pos.position();
                double dx = p.x - bx, dz = p.z - bz, len = Math.max(0.3, Math.sqrt(dx * dx + dz * dz));
                navigate((int) Math.floor(p.x + dx / len * 3), (int) Math.floor(p.y + 0.2), (int) Math.floor(p.z + dz / len * 3), 1);
            } catch (RuntimeException ignored) {}
            return;
        }
        if (module == null) return;
        try { module.onMessage(this, m); }
        catch (RuntimeException e) { AgentNet.LOG.warn("[{}] {} onMessage failed", name, role, e); }
    }

    // ---- handing an item to a distributor that fetches it for someone else

    private String handFor, handItem;
    private int handLeft, handTicks;

    private void startHandOver(Message m) {
        handFor = m.from();
        handItem = m.get("item");
        int want = 1;
        try { want = Integer.parseInt(m.get("count")); } catch (RuntimeException ignored) {}
        handLeft = Math.min(want, dev.agentnet.role.Spare.spare(this, handItem));
        handTicks = 0;
        if (handLeft <= 0) { finishHandOver("can't spare any " + handItem); return; }
        log("handing " + handLeft + "x " + handItem.replace("minecraft:", "") + " to " + handFor);
    }

    /** One drop every 3 ticks toward the distributor. True while busy (the role waits). */
    private boolean tickHandOver() {
        if (handFor == null) return false;
        // Running from something (a creeper above all): the hand-over waits, its 15 s too. SolWillow2016 stopped to
        // hand a distributor its cobblestone with a creeper 5 blocks off and was blown up (run 1118c).
        if (fleeing || creeperFlee) { handTicks = 0; return false; }
        if (++handTicks > 20 * 15) { finishHandOver(null); return true; }
        if (handTicks % 3 != 0) return true;
        Agent to = AgentNetClient.AGENTS.get(handFor.toLowerCase(java.util.Locale.ROOT));
        if (to != null) lookAt(to.x(), to.y() + 0.5, to.z());
        if (handLeft <= 0) { finishHandOver(null); return true; }
        List<ItemStack> inv = inventory();
        for (int i = 0; i < inv.size() && i < 36; i++) {
            ItemStack s = inv.get(i);
            if (s.isEmpty() || !(handItem.equals("logs") ? dev.agentnet.role.Gear.matches(s, handItem) : dev.agentnet.role.Gear.id(s).equals(handItem))) continue;
            if (s.getCount() <= handLeft) { handLeft -= s.getCount(); dropStack(i); }
            else { handLeft--; dropOne(i); }
            return true;
        }
        finishHandOver(null);
        return true;
    }

    private void finishHandOver(String why) {
        dev.agentnet.bus.AgentBus.send(Message.of(name, handFor, Message.Type.DROP_DONE, why == null ? "here you go" : why, java.util.Map.of()));
        handFor = null;
    }

    // ---- non-warriors run from danger: hostile mobs and hitlist players

    private static final double DANGER = 8, SAFE = 16;
    private long threatSeenAt;
    private static final double DANGER_FAR = 18;
    private static double dangerRange(String mob) {
        return switch (mob) {
            case "skeleton", "stray", "bogged", "pillager", "witch", "blaze", "ghast", "evoker", "illusioner", "breeze", "shulker" -> 16;
            case "creeper", "phantom", "vex", "ravager", "warden" -> 12;
            default -> DANGER;
        };
    }
    private static final java.util.Set<String> HARMLESS = java.util.Set.of("enderman", "zombified_piglin", "piglin");
    private boolean fleeing;
    private int threatTicks, fleeReplan;
    private long fleeSince;
    private final java.util.Map<Integer, Long> ignoredThreats = new java.util.HashMap<>();

    /** True while running away (the role's work waits). Warriors fight instead. */
    // ---- night: agents that don't fight dig in when a mob comes for them on the surface. Three blocks down, a block over
    //      their heads, out again in the morning (the player's trick). Running and fighting with a pickaxe got four of them
    //      killed every five minutes of night.

    private volatile long dayTicks = -1, dayAt;
    private volatile float dayRate = 1f;

    /** The overworld's time of day (0..23999), from the server's clock; -1 if it has not said yet. */
    private long timeOfDay() {
        if (dayTicks < 0) return -1;
        long t = dayTicks + (long) ((System.currentTimeMillis() - dayAt) / 50.0 * dayRate);
        return Math.floorMod(t, 24000L);
    }

    private boolean night() { long t = timeOfDay(); return t >= 12800 && t < 23200; }

    private enum Burrow { NONE, DIG, ROOF, WAIT, OUT }
    private Burrow burrow = Burrow.NONE;
    private int burrowX, burrowY, burrowZ, burrowTicks, burrowCheck, burrowDigs;
    private long burrowAgainAt;

    private static final java.util.Set<String> BURROW_BLOCKS = java.util.Set.of("minecraft:dirt", "minecraft:cobblestone", "minecraft:cobbled_deepslate",
            "minecraft:stone", "minecraft:netherrack", "minecraft:granite", "minecraft:diorite", "minecraft:andesite", "minecraft:tuff", "minecraft:coarse_dirt");

    private boolean tickBurrow() {
        if (burrow == Burrow.NONE) {
            if (++burrowCheck % 10 != 0 || System.currentTimeMillis() < burrowAgainAt || !night()) return false;
            // A phantom about: under a roof, whoever it is. It flies and swoops faster than anything runs, and a fighter swinging
            // at it as it passes loses (itsCyber ran, turned, and died at 1 health, run 1118d). A roof stops it.
            boolean phantom = false;
            for (dev.agentnet.role.Seen s : entitiesNear(24)) if (s.name().equals("phantom")) { phantom = true; break; }
            // Fighters fight, when they have something to fight with and the health for it. Fresh from a death with empty
            // hands, the speedrunner "fought" a spider with its fists and died again within a minute of coming back.
            boolean fighter = role != null && role.needs.contains(Role.Capability.COMBAT);
            if (fighter && !phantom) {
                var inv = inventory();
                boolean armed = dev.agentnet.role.Gear.bestTool(inv, "sword") >= 0 || dev.agentnet.role.Gear.bestTool(inv, "axe") >= 0;
                float hp = health();
                if (role == Role.WARRIOR || armed && (Float.isNaN(hp) || hp >= 10)) return false;
            } else if (!phantom && module != null && module.fighting()) return false;
            if (!String.valueOf(dimension()).endsWith("overworld") || airVel != null || underwaterTicks > 0 || myVehicle >= 0) return false;
            var w = openWorld();
            if (w == null) return false;
            // A mob it can see, coming for it, not yet on top of it (then it fights: digging takes a few seconds).
            dev.agentnet.role.Seen near = null;
            Vec3 me = pos.position();
            for (dev.agentnet.role.Seen s : entitiesNear(16)) {
                if (s.player() || !s.hostile() || HARMLESS.contains(s.name()) && !s.angry()) continue;
                if (!dev.agentnet.role.Sight.clear(w, me.x, me.y + 1.62, me.z, s.pos().x, s.pos().y + 1.0, s.pos().z)) continue;
                near = s; break;
            }
            // Fresh from a death at night with nothing: in the ground at once, before anything comes. Waiting for a mob to
            // show first, the speedrunner died five times in three minutes at its spawn (skeletons, zombies, spiders).
            boolean fresh = System.currentTimeMillis() - diedAt < 120_000;
            if (!phantom && (near == null ? !fresh : near.distance() < 4)) return false;
            int x = (int) Math.floor(me.x), y = (int) Math.floor(me.y + 0.01), z = (int) Math.floor(me.z);
            if (!burrowable(w, x, y, z)) { burrowAgainAt = System.currentTimeMillis() + 15_000; return false; }
            burrow = Burrow.DIG; burrowX = x; burrowY = y; burrowZ = z; burrowTicks = 0; burrowDigs = 0;
            failedGoal = null; // the trip it was on waits for the morning
            pendingGoal = null; // and no trip waiting to start either: one did, its "boxed in" dug it back out of its hole and a zombie killed it
            if (navigator.busy()) navigator.stop();
            escapeGoal = null; escapeStep = null; escapeMoves.clear();
            log(phantom ? "§ephantoms about: digging in under a roof until morning" : near == null ? "§ejust back from a death, at night, with nothing: digging in until morning" : "§enight, and a " + near.name() + " is coming: digging in until morning");
            return true;
        }
        burrowTicks++;
        var w = openWorld();
        if (w == null) return true;
        Vec3 me = pos.position();
        int feet = (int) Math.floor(me.y + 0.01);
        switch (burrow) {
            case DIG -> {
                // Something on top of it already: the fight goes first (tickThreats), the burrow is dropped.
                for (dev.agentnet.role.Seen s : entitiesNear(3.2)) if (!s.player() && s.hostile() && !HARMLESS.contains(s.name()) && !s.name().equals("phantom")) { endBurrow(30_000); return false; } // a phantom passing over is why it digs: keep digging
                if (burrowTicks > 20 * 25) { log("§ecouldn't dig in here: back to work"); endBurrow(10_000); return false; }
                if (airVel != null) { burrowTicks--; return true; } // knocked back: not counted against the dig
                if (isDigging()) return true;
                // Knocked off its column (a phantom's swoop, run 1118d: the rescuer was pushed about for 25 s, gave up, and died):
                // dig where it stands now if it hasn't started down yet, else step back over the hole.
                int cx = (int) Math.floor(me.x), cz = (int) Math.floor(me.z);
                if (cx != burrowX || cz != burrowZ) {
                    if (feet >= burrowY && burrowable(w, cx, feet, cz)) { burrowX = cx; burrowZ = cz; burrowY = feet; burrowDigs = 0; }
                    else { stepToward(burrowX + 0.5, burrowZ + 0.5); return true; }
                }
                if (feet <= burrowY - 3) { burrow = Burrow.ROOF; burrowTicks = 0; return true; }
                BlockState under = w.get(burrowX, feet - 1, burrowZ);
                if (under == null) return true;
                if (dev.agentnet.pathing.Rules.canWalkThrough(under)) { gravityCheck = 9; tickGravity(); return true; } // falling into the hole it dug
                if (!under.getFluidState().isEmpty() || under.getBlock() == net.minecraft.world.level.block.Blocks.BEDROCK) { endBurrow(60_000); return false; }
                // The same block dug again and again and still there: the server won't let it (spawn protection, a claim).
                if (++burrowDigs > 4 + 3 * (burrowY - feet)) { log("§ethe ground here can't be dug: no hole for the night"); endBurrow(60_000); return false; }
                dev.agentnet.role.Gear.holdBestFor(this, under);
                lookAt(burrowX + 0.5, feet - 0.5, burrowZ + 0.5);
                dig(burrowX, feet - 1, burrowZ);
                return true;
            }
            case ROOF -> {
                BlockState roof = w.get(burrowX, burrowY - 1, burrowZ);
                if (roof != null && !dev.agentnet.pathing.Rules.canWalkThrough(roof)) { burrow = Burrow.WAIT; burrowTicks = 0; log("dug in for the night"); return true; }
                if (burrowTicks > 20 * 10) { burrow = Burrow.WAIT; burrowTicks = 0; return true; } // no roof: the hole it is in is still better than the open
                if (burrowTicks % 5 != 0) return true;
                var inv = inventory();
                int slot = -1;
                for (int i = 0; i < inv.size(); i++) if (BURROW_BLOCKS.contains(dev.agentnet.role.Gear.id(inv.get(i)))) { slot = i; break; }
                if (slot < 0) return true;
                int hb = slot < 9 ? slot : 7;
                if (slot >= 9) moveToHotbar(slot, hb);
                selectHotbar(hb);
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    int wx = burrowX + d.getStepX(), wz = burrowZ + d.getStepZ();
                    BlockState wall = w.get(wx, burrowY - 1, wz);
                    if (wall == null || dev.agentnet.pathing.Rules.canWalkThrough(wall)) continue;
                    Direction face = d.getOpposite();
                    lookAt(wx + 0.5 + face.getStepX() * 0.5, burrowY - 0.5, wz + 0.5 + face.getStepZ() * 0.5);
                    placeAgainst(wx, burrowY - 1, wz, face);
                    break;
                }
                return true;
            }
            case WAIT -> {
                if (tickFood()) return true;
                long t = timeOfDay();
                boolean morning = t >= 0 ? !night() : burrowTicks > 20 * 120;
                if (!morning && burrowTicks < 20 * 60 * 10) { if (burrowTicks % 1200 == 1) dev.agentnet.AgentNet.LOG.info("[{}] dug in, waiting for morning (time {})", name, t); return true; }
                burrow = Burrow.OUT; burrowTicks = 0;
                log("morning: coming out");
                return true;
            }
            case OUT -> {
                if (burrowTicks > 20 * 40) { endBurrow(0); return false; }
                if (isDigging() || escapeGoal != null) { if (escapeGoal != null) tickEscape(); return true; }
                BlockState roof = w.get(burrowX, burrowY - 1, burrowZ);
                if (roof != null && !dev.agentnet.pathing.Rules.canWalkThrough(roof) && feet <= burrowY - 2) {
                    dev.agentnet.role.Gear.holdBestFor(this, roof);
                    lookAt(burrowX + 0.5, burrowY - 0.5, burrowZ + 0.5);
                    dig(burrowX, burrowY - 1, burrowZ);
                    return true;
                }
                if (feet >= burrowY) { endBurrow(0); return false; }
                // Up the shaft on its own blocks (the escape pillars while the goal is over its head).
                escapeFrom = me; escapeAt = System.currentTimeMillis(); escapeStuck = 0; escapeRounds = 0;
                escapeGoal = new PathFinder.Node(burrowX, burrowY + 1, burrowZ);
                escapeRadius = 1;
                escapeDigs = 0;
                return true;
            }
            default -> { return false; }
        }
    }

    private void endBurrow(long pauseMs) {
        burrow = Burrow.NONE;
        burrowAgainAt = System.currentTimeMillis() + pauseMs;
    }

    /** Three blocks straight down it can dig (no water, lava or cave), firm ground under them, and solid walls at the top to hold a roof. */
    private boolean burrowable(dev.agentnet.pathing.BlockSource w, int x, int y, int z) {
        if (spawnProtected(x, z) || inBase(x, y, z)) return false;
        BlockState under = w.get(x, y - 4, z);
        if (under == null || dev.agentnet.pathing.Rules.canWalkThrough(under) || !under.getFluidState().isEmpty()) return false;
        for (int dy = 1; dy <= 3; dy++) {
            BlockState s = w.get(x, y - dy, z);
            if (s == null || dev.agentnet.pathing.Rules.canWalkThrough(s) || !s.getFluidState().isEmpty()
                    || s.getBlock() == net.minecraft.world.level.block.Blocks.BEDROCK || dev.agentnet.pathing.Rules.dangerous(s)) return false;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockState n = w.get(x + d.getStepX(), y - dy, z + d.getStepZ());
                if (n == null || !n.getFluidState().isEmpty() || dev.agentnet.pathing.Rules.canWalkThrough(n)) return false; // a cave or water beside it
            }
        }
        return true;
    }

    private boolean defending;
    private boolean creeperFlee;
    private long creeperSaidAt;
    private boolean shieldUp;
    private long defendSwingAt;

    private boolean tickThreats() {
        // Warriors fight as they always did: never backing off, not even from a creeper (the user: the warrior combat
        // as before). The creeper back-off below is for the other fighters (speedrunners, a looter clearing a chamber).
        if (role == Role.WARRIOR) { fleeing = false; creeperFlee = false; return false; }
        if (role == Role.WARRIOR || module != null && module.fighting()) {
            // Fighters (and a looter clearing a trial chamber) don't run, except from a creeper close by: two speedrun
            // helpers fighting zombies in a cave were blown up together by one (run 1118).
            if (++threatTicks % 5 == 0) {
                dev.agentnet.role.Seen boom = null;
                var w = openWorld();
                Vec3 me = pos.position();
                for (dev.agentnet.role.Seen s : entitiesNear(6)) {
                    if (!s.name().equals("creeper")) continue;
                    if (w != null && s.distance() > 4 && !dev.agentnet.role.Sight.clear(w, me.x, me.y + 1.62, me.z, s.pos().x, s.pos().y + 1.0, s.pos().z)) continue;
                    boom = s; break;
                }
                creeperFlee = boom != null;
                if (boom != null) {
                    if (navigator.busy() && ++fleeReplan % 3 != 0) return true;
                    if (System.currentTimeMillis() - creeperSaidAt > 10_000) { creeperSaidAt = System.currentTimeMillis(); log(String.format("§ea creeper %.0f blocks off: backing away from it, then back to the fight", boom.distance())); }
                    runFrom(boom.pos());
                    return true;
                }
            }
            if (creeperFlee) return true;
            fleeing = false; return false;
        }
        if (++threatTicks % 10 != 0) return fleeing;
        dev.agentnet.role.Seen threat = null;
        for (dev.agentnet.role.Seen s : entitiesNear(fleeing ? SAFE : DANGER)) {
            // A friend (whitelist, you) who just hit one of us is stepped away from, never fought.
            boolean bad = s.player() ? isEnemy(s.name()) || recentlyHit(s.name()) && !AgentNetClient.AGENTS.containsKey(s.name().toLowerCase())
                    : s.hostile() && (!HARMLESS.contains(s.name()) || s.angry());
            // Only one it can see: underground there is nearly always a mob within 16 blocks behind the rock, and agents
            // "fled" from it forever, calling for help every 10 s and doing nothing else.
            if (bad && !s.player()) {
                var w = openWorld();
                Vec3 me = pos.position();
                if (w != null && !dev.agentnet.role.Sight.clear(w, me.x, me.y + 1.62, me.z, s.pos().x, s.pos().y + 1.0, s.pos().z)) bad = false;
                Long ign = ignoredThreats.get(s.id());
                if (ign != null && System.currentTimeMillis() < ign) bad = false;
            }
            if (bad) { threat = s; break; } // nearest first
        }
        if (threat != null) threatSeenAt = System.currentTimeMillis();
        if (threat == null) {
            defending = false;
            if (shieldUp) { shieldUp = false; releaseUse(); }
            // Out of sight is not safe yet: a skeleton steps back round the corner. "Safe again" two seconds after running,
            // a builder stood still crafting doors and was shot; the speedrunner died the same way. 6 s with nothing seen.
            if (fleeing && System.currentTimeMillis() - threatSeenAt < 6000) return true;
            if (fleeing) { fleeing = false; if (navigator.busy()) navigator.stop(); log("safe again, back to work"); }
            return false;
        }
        // A skeleton (stray, pillager) drawing on it and a shield in its off hand: the shield up toward it, whether it runs
        // or stands. The speedrunner carried one and still died running and eating in the open, shot down from 8 HP.
        String tn = threat.name();
        boolean archer = !threat.player() && (tn.equals("skeleton") || tn.equals("stray") || tn.equals("bogged") || tn.equals("pillager"));
        if (archer && threat.distance() > 3.2 && dev.agentnet.role.Gear.id(offhand()).equals("minecraft:shield")) {
            if (!shieldUp) { shieldUp = true; useOffhand(); log("§eshield up against the " + tn + " (" + Math.round(threat.distance()) + " blocks)"); }
            faceWhileMoving(threat.pos().x, threat.pos().y + 1.5, threat.pos().z);
        } else if (shieldUp) { shieldUp = false; releaseUse(); }
        // Caught up with it anyway (spiders and zombies are as fast or faster; a skeleton keeps shooting while it runs):
        // running on only gets it killed from behind. It turns and fights with the best thing it carries. Never a creeper
        // (that one is run from, always) and never a player.
        // A creeper close by beats any fight: away from it first (MrsTaco31 turned to fight a skeleton with a creeper
        // right beside it and was blown up, run 1118b).
        if (!threat.name().equals("creeper")) {
            var cw = openWorld();
            Vec3 me = pos.position();
            for (dev.agentnet.role.Seen s : entitiesNear(5)) {
                if (!s.name().equals("creeper")) continue;
                if (cw != null && s.distance() > 4 && !dev.agentnet.role.Sight.clear(cw, me.x, me.y + 1.62, me.z, s.pos().x, s.pos().y + 1.0, s.pos().z)) continue;
                threat = s; break;
            }
        }
        if (!threat.player() && !threat.name().equals("creeper") && !threat.name().equals("enderman") && threat.distance() <= 3.2) {
            if (navigator.busy()) navigator.stop();
            long now = System.currentTimeMillis();
            if (!defending) { defending = true; log("§ecan't outrun the " + threat.name() + ": fighting it"); }
            fleeing = true;
            fleeSince = now; // fighting is not running: the "gave up running" minute starts again
            var inv = inventory();
            int w = dev.agentnet.role.Gear.bestTool(inv, "sword");
            if (w < 0) w = dev.agentnet.role.Gear.bestTool(inv, "axe");
            if (w < 0) w = dev.agentnet.role.Gear.bestTool(inv, "pickaxe");
            if (w >= 0) { if (w >= 9) { moveToHotbar(w, 0); w = 0; } if (selectedSlot() != w) selectHotbar(w); }
            lookAt(threat.pos().x, threat.pos().y + 0.9, threat.pos().z);
            if (now - defendSwingAt >= 950) { attack(threat.id()); defendSwingAt = now; } // a full-strength swing, about once a second
            callForHelp(threat);
            return true;
        }
        defending = false;
        if (fleeing && !threat.player() && System.currentTimeMillis() - fleeSince > 60_000) {
            // A minute of running and it's still there: back to work, and this one is ignored for a minute.
            ignoredThreats.put(threat.id(), System.currentTimeMillis() + 60_000);
            fleeing = false;
            if (navigator.busy()) navigator.stop();
            log("gave up running from the " + threat.name() + ": back to work");
            return false;
        }
        if (!fleeing) {
            fleeing = true;
            fleeSince = System.currentTimeMillis();
            fleeReplan = 0;
            if (navigator.busy()) navigator.stop();
            log("§erunning from " + (threat.player() ? threat.name() : "a " + threat.name()) + String.format(" (%.0f blocks)", threat.distance()));
        }
        if (!navigator.busy() || ++fleeReplan % 6 == 0) runFrom(threat.pos());
        if (!threat.player() || isEnemy(threat.name())) callForHelp(threat); // no warriors sent after a friend
        return true;
    }

    // ---- everyone eats: hungry, or hurt and not full (that's what heals). No food: ask a distributor.

    private int eatTicks, foodTicks;
    private long foodAskedAt;

    /** True while eating (the role's work waits). */
    private boolean tickFood() {
        if (eatTicks > 0) { eatTicks--; return true; }
        if (++foodTicks % 20 != 0 || !isOnline()) return false;
        if (module != null && module.fighting()) return false; // the fight comes first
        int food = food();
        float hp = health(), max = Math.max(1f, maxHealth());
        boolean hungry = food <= 14, hurt = !Float.isNaN(hp) && hp < max * 0.75f && food < 20;
        if (!hungry && !hurt) return false;
        var inv = inventory();
        int best = -1;
        for (int i = 0; i < inv.size(); i++) {
            var s = inv.get(i);
            if (!dev.agentnet.role.Gear.isFood(s) || dev.agentnet.role.Gear.id(s).contains("golden_apple")) continue; // gapples are for fights
            if (best < 0 || dev.agentnet.role.Gear.nutrition(s) > dev.agentnet.role.Gear.nutrition(inv.get(best))) best = i;
        }
        if (best < 0) {
            long now = System.currentTimeMillis();
            if (now - foodAskedAt > 60_000) {
                foodAskedAt = now;
                log((hurt ? "hurt" : "hungry") + " and out of food: asking for some");
                dev.agentnet.role.Gear.requestFood(this);
            }
            return false;
        }
        int slot = best;
        if (slot >= 9) { moveToHotbar(slot, 8); slot = 8; }
        selectHotbar(slot);
        useItem(); // the server finishes eating by itself (32 ticks) unless we let go
        eatTicks = 36;
        log("eating (" + Math.round(hp) + " HP, food " + food + ")");
        return true;
    }

    private int hubTicks;
    private long hubFailedAt;
    private boolean hubTried;

    /**
     * Nothing to do (no role, or the role says it's idle) and the crew has a base: wait at its hub,
     * so idle agents are all in one known place instead of wherever their last job ended.
     */
    private void tickHub() {
        if (++hubTicks % 200 != 0 || navigator.busy() || airVel != null) return;
        if (module != null && !module.idle()) return;
        if (!"idle".equals(task) && module == null) return; // a goto/follow order without a role
        Vec3 me = pos.position();
        // No finished base around here yet: there's no hub, skip this step entirely.
        var base = dev.agentnet.role.Bases.builtNear(dev.agentnet.map.Servers.key(address), dimension(), me.x, me.z);
        if (base == null) return;
        BlockPos hub = base.hub();
        if (hub.distToCenterSqr(me.x, me.y, me.z) <= 6 * 6) return;
        if (hubFailedAt > 0 && System.currentTimeMillis() - hubFailedAt < 5 * 60_000) return; // couldn't get there a moment ago
        if (navStatus().startsWith("failed") && hubTried) { hubFailedAt = System.currentTimeMillis(); hubTried = false; log("can't reach the base's hub: staying put"); return; }
        hubTried = true;
        log("nothing to do: going to the base's hub");
        // Each its own spot around the hub (not one pile: a delivery thrown to one would land with the others).
        double ang = (name.toLowerCase().hashCode() & 0xffff) / 65536.0 * Math.PI * 2;
        navigate(hub.getX() + (int) Math.round(Math.cos(ang) * 3), hub.getY(), hub.getZ() + (int) Math.round(Math.sin(ang) * 3), 1);
    }

    private long helpSentAt;

    /** Tell every warrior who is after us (again every 10 s while it lasts). */
    private void callForHelp(dev.agentnet.role.Seen threat) {
        long now = System.currentTimeMillis();
        if (now - helpSentAt < 10_000) return;
        helpSentAt = now;
        Vec3 me = pos.position();
        Map<String, String> d = Map.of("id", String.valueOf(threat.id()), "name", threat.name(),
                "x", String.valueOf((int) Math.floor(me.x)), "y", String.valueOf((int) Math.floor(me.y)), "z", String.valueOf((int) Math.floor(me.z)));
        int n = 0;
        for (dev.agentnet.role.AgentHandle w : dev.agentnet.bus.AgentBus.agents.get()) {
            if (w == this || w.role() != Role.WARRIOR) continue;
            dev.agentnet.bus.AgentBus.send(dev.agentnet.bus.Message.of(name, w.name(), dev.agentnet.bus.Message.Type.HELP,
                    "help, a " + threat.name() + " is after me", d));
            n++;
        }
        if (n > 0) log("calling " + n + " warrior(s) for help");
    }

    /** Head ~14 blocks away from a point: straight away if there's ground there, else the nearest direction that has some. */
    private void runFrom(Vec3 from) {
        Vec3 me = pos.position();
        double away = Math.atan2(me.z - from.z, me.x - from.x);
        var w = openWorld();
        for (int k = 0; k < 9; k++) {
            double ang = away + (k == 0 ? 0 : (k % 2 == 1 ? 1 : -1) * ((k + 1) / 2) * Math.PI / 6);
            int gx = (int) Math.floor(me.x + Math.cos(ang) * 14), gz = (int) Math.floor(me.z + Math.sin(ang) * 14);
            int gy = (int) Math.floor(me.y);
            if (w != null) {
                gy = Integer.MIN_VALUE;
                for (int dy = 0; dy <= 6 && gy == Integer.MIN_VALUE; dy++) {
                    for (int s : dy == 0 ? new int[]{0} : new int[]{dy, -dy}) {
                        int y = (int) Math.floor(me.y) + s;
                        BlockState ground = w.get(gx, y - 1, gz), feet = w.get(gx, y, gz), head = w.get(gx, y + 1, gz);
                        if (ground != null && feet != null && head != null && !airLike(ground) && ground.getFluidState().isEmpty()
                                && airLike(feet) && airLike(head)) { gy = y; break; }
                    }
                }
                if (gy == Integer.MIN_VALUE) continue;
            }
            navigate(gx, gy, gz, 3);
            return;
        }
    }

    private final dev.agentnet.role.Clutch clutch = new dev.agentnet.role.Clutch();

    @Override public void leaveClutch() { mc.execute(() -> AgentNetClient.leaveClutch(this)); }

    // ---- the crew's base: outsiders who walk in, break a block or open a chest there go on the hitlist

    private int baseGuardTicks;

    /** Inside the crew's base (building, entrance strip, a little around it), built or still going up. */
    private boolean inCrewBase(int x, int y, int z) {
        var b = dev.agentnet.role.Bases.crew(dev.agentnet.map.Servers.key(address), dimension());
        if (b == null) return false;
        var o = b.origin();
        // 17 x 13 building plus the 4-deep entrance strip (BasePlanner.SX, SZ, FRONT)
        return x >= o.getX() - 1 && x <= o.getX() + 17 && z >= o.getZ() - 1 && z <= o.getZ() + 13 + 4 && y >= o.getY() - 3 && y <= o.getY() + 12;
    }

    private void intruder(String who, String why) {
        if (who == null || who.equals("?") || isFriendly(who)) return; // you, agents and whitelisted players never
        if (AgentFiles.HITLIST.add(who, why + " (" + java.time.LocalDateTime.now().withNano(0) + ")"))
            AgentNetClient.notify("§4[" + name + "] " + who + " " + why + ": added to the hitlist");
    }

    private void guardBase() {
        for (dev.agentnet.role.Seen s : entitiesNear(32)) {
            if (!s.player() || s.pos() == null) continue;
            if (inCrewBase((int) Math.floor(s.pos().x), (int) Math.floor(s.pos().y), (int) Math.floor(s.pos().z))) intruder(s.name(), "walked into the base");
        }
    }

    private void tickModule() {
        if (shieldHold > 0 && --shieldHold % 10 == 0) { if (shieldHold == 0) releaseUse(); else useOffhand(); }
        if (++baseGuardTicks % 20 == 0) guardBase();
        if (role != Role.WARRIOR && clutch.tick(this)) return; // falling: water bucket or leave clutch (warriors have their own)
        if (tickHandOver()) return;
        if (tickBurrow()) return;  // dug in for the night
        if (tickThreats()) return; // running from a mob or a hitlist player
        if (tickFood()) return;    // eating
        tickHub();
        if (module == null) return;
        try {
            if (!moduleStarted) {
                module.onStart(this);
                moduleStarted = true;
                // Came back after a reconnect: pick the job up where it was (mine X, explore...).
                if (replayTask) { replayTask = false; if (!task.equals("idle")) module.onTask(this, task); }
            }
            module.tick(this);
        } catch (RuntimeException e) {
            AgentNet.LOG.warn("[{}] {} module failed, removing role", name, role, e);
            log("§c" + role.id() + " module crashed: " + e);
            module = null;
            role = null;
            moduleStarted = false;
        }
    }

    // ---------------------------------------------------------------- blocks

    private volatile boolean keepWorld;
    private BlockPos digPos;     // main thread only
    private int digTicksLeft;

    @Override
    public dev.agentnet.pathing.BlockSource openWorld() {
        if (gameRegistries == null || chunks.isEmpty() || dimHeight <= 0) return null;
        return new dev.agentnet.pathing.BlockView(chunks, gameRegistries, dimMinY);
    }

    @Override public void keepWorld(boolean keep) { keepWorld = keep; }

    // ---- spawn protection: non-op players can't place or break blocks near world spawn
    private volatile BlockPos worldSpawn;
    private volatile String spawnDimension = "minecraft:overworld";
    private volatile int opLevel;
    /** Vanilla's default spawn-protection radius; the server doesn't tell clients its value. */
    public static final int SPAWN_PROTECTION = 16;

    @Override
    public boolean spawnProtected(int x, int z) {
        BlockPos s = worldSpawn;
        if (s == null || opLevel > 0 || !dimension.equals(spawnDimension)) return false;
        // Your own world (singleplayer opened to LAN): vanilla's integrated server has no spawn protection at all (only the
        // dedicated server does). Taking 16 blocks round spawn as protected there, agents near spawn were "boxed in" with
        // no way out and couldn't dig in for the night: the speedrunner died a dozen times in a row at its respawn.
        if (mc.getSingleplayerServer() != null) return false;
        return Math.max(Math.abs(x - s.getX()), Math.abs(z - s.getZ())) <= SPAWN_PROTECTION;
    }

    @Override
    public BlockPos worldSpawn() { return worldSpawn; }

    // ---- entities around the agent (mobs, players) for warriors: positions decoded like the
    //      vanilla client does (deltas against a per-entity base position)

    private static final class Tracked {
        final int id;
        final net.minecraft.world.entity.EntityType<?> type;
        final UUID uuid;
        final net.minecraft.network.protocol.game.VecDeltaCodec codec = new net.minecraft.network.protocol.game.VecDeltaCodec();
        volatile Vec3 pos;
        /** LivingEntity flags (1 = using an item, 2 = with the offhand), creeper fuse, enderman anger, riding. */
        volatile int livingFlags, swell, vehicle = -1;
        volatile float health = Float.NaN;
        volatile boolean angry;
        volatile ItemStack mainHand = ItemStack.EMPTY, offHand = ItemStack.EMPTY;
        volatile int armorPieces;
        /** A banner on its head: a raid captain (drops an ominous bottle). */
        volatile boolean bannerHead;
        Tracked(int id, net.minecraft.world.entity.EntityType<?> type, UUID uuid, Vec3 pos) {
            this.id = id; this.type = type; this.uuid = uuid; this.pos = pos;
            codec.setBase(pos);
        }
    }

    private final Map<Integer, Tracked> tracked = new java.util.concurrent.ConcurrentHashMap<>();

    private static final int LIVING_FLAGS_ID = dataId(net.minecraft.world.entity.LivingEntity.class, "DATA_LIVING_ENTITY_FLAGS");
    private static final int HEALTH_ID = dataId(net.minecraft.world.entity.LivingEntity.class, "DATA_HEALTH_ID");
    private volatile byte selfFlags;
    /** The server says it is using an item right now (a raised shield, eating). */
    @Override public boolean usingItem() { return (selfFlags & 1) != 0; }
    @Override public float healthOf(int entityId) { Tracked t = tracked.get(entityId); return t == null ? Float.NaN : t.health; }
    private static final int SWELL_ID = dataId(net.minecraft.world.entity.monster.Creeper.class, "DATA_SWELL_DIR");
    private static final int CREEPY_ID = dataId(net.minecraft.world.entity.monster.Enderman.class, "DATA_CREEPY");
    /** A blaze's flags: bit 1 = charged (it shows as burning while it gets a volley of fireballs ready). */
    private static final int BLAZE_FLAGS_ID = dataId(net.minecraft.world.entity.monster.Blaze.class, "DATA_FLAGS_ID");

    /** The synced-data id behind a static EntityDataAccessor field, or -1. */
    private static int dataId(Class<?> c, String field) {
        try {
            var f = c.getDeclaredField(field);
            f.setAccessible(true);
            return ((net.minecraft.network.syncher.EntityDataAccessor<?>) f.get(null)).id();
        } catch (ReflectiveOperationException | RuntimeException e) {
            AgentNet.LOG.warn("No entity data field {}.{}", c.getSimpleName(), field);
            return -1;
        }
    }

    // ---- using items: shield (offhand), bow (hold, then release)

    @Override
    public void useOffhand() {
        sendInOrder(new ServerboundUseItemPacket(net.minecraft.world.InteractionHand.OFF_HAND, ++useSequence, pos.yRot(), pos.xRot()));
    }

    @Override
    public void releaseUse() {
        sendInOrder(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM, BlockPos.ZERO, Direction.DOWN, 0));
    }

    /**
     * Sent now, unless a swing is still waiting behind a queued move: then behind that too, so the server gets them in
     * the order they were made. (Its shield went back up a tick after an axe swing that was still waiting; the server
     * got "shield up" first, and an attacker using an item does not knock a shield out: vanilla's getActiveItem.)
     */
    private void sendInOrder(Packet<?> p) {
        synchronized (this) {
            ServerboundMovePlayerPacket last = moveQueue.peekLast();
            if (last != null && !afterMove.isEmpty()) { afterMove.computeIfAbsent(last, k -> new java.util.ArrayList<>()).add(p); return; }
        }
        send(p);
    }

    // ---- flying: a wind charge (or explosion) pushes the agent; agents have no physics of
    //      their own, so the launch is simulated here like a player's: gravity 0.08/tick,
    //      drag 0.98 vertical and 0.91 horizontal, a little air steering, land on blocks.
    //      onGround=false while flying lets the server count the fall (mace smash damage).

    private Vec3 airVel;
    private Vec3 airSteer = Vec3.ZERO;
    private int airTicks;
    /** Highest point of this flight (fall damage counts from here). */
    private double airPeakY;
    /** A block the warrior just poured water into for a clutch: count it as water before the server confirms. */
    private BlockPos clutchWater;
    /** In water after a fall: a few ticks with onGround=false so the server resets the fall (it does that in its entity tick). */
    private int waterHold;
    private int fallHold;
    /** Where a clutch boat was just placed (null = none). */
    private Vec3 clutchBoatAt;
    @Override public void expectBoat(double x, double y, double z) { clutchBoatAt = new Vec3(x, y, z); }
    @Override public void clearClutch() { clutchBoatAt = null; clutchWater = null; }
    @Override public void holdFall(int ticks) { fallHold = ticks; }

    void launch(Vec3 push) {
        if (airVel == null) airPeakY = pos.position().y;
        airVel = (airVel == null ? Vec3.ZERO : airVel).add(push);
        airTicks = 0;
        if (navigator.busy()) navigator.stop();
        AgentNet.LOG.info("[{}] launched {}", name, push);
    }

    /** The server set our motion (knockback from a hit, a lunge): fly it out. A trip in progress just waits. */
    private void knock(Vec3 v) {
        // On the ground a near-zero push is nothing. In the air it is the server stopping our fall (a mace smash
        // does that before Wind Burst throws us back up): take it, or the throw only slows the fall.
        if (v.lengthSqr() < 0.01 && airVel == null) return;
        if (airVel == null) airPeakY = pos.position().y;
        airVel = v;
        airTicks = 0;
    }

    private volatile int lastHitEntity = -1;
    private volatile long lastHitAt;

    /** The server says we damaged this entity within the last ms milliseconds. */
    @Override public boolean hitRecently(int entityId, long ms) { return lastHitEntity == entityId && System.currentTimeMillis() - lastHitAt <= ms; }

    @Override public boolean airborne() { return airVel != null; }
    @Override public double verticalSpeed() { return airVel == null ? 0 : airVel.y; }
    @Override public double horizontalSpeed() { return airVel == null ? 0 : Math.sqrt(airVel.x * airVel.x + airVel.z * airVel.z); }
    @Override public void steer(double dx, double dz) { airSteer = new Vec3(dx, 0, dz); }
    @Override public double airPeakY() { return airVel == null ? pos.position().y : airPeakY; }
    @Override public void expectWater(int x, int y, int z) { clutchWater = new BlockPos(x, y, z); }

    private boolean isWater(BlockState s, int x, int y, int z) {
        if (clutchWater != null && clutchWater.getX() == x && clutchWater.getY() == y && clutchWater.getZ() == z) return true;
        return s != null && s.getFluidState().is(net.minecraft.tags.FluidTags.WATER);
    }

    /** One tick of flight. True while flying (navigation and gravity wait). */
    private boolean tickAir() {
        if (rocketTicks > 0) rocketTicks--; // burning whether it glides or not
        if (airVel == null) return false;
        Vec3 p = pos.position();
        if (waterHold > 0) {
            // Floating in the clutch water: onGround=false lets the server see us in water and drop the fall.
            sendPosition(new ServerboundMovePlayerPacket.PosRot(p.x, p.y, p.z, pos.yRot(), pos.xRot(), false, false));
            if (--waterHold == 0) { airVel = null; airSteer = Vec3.ZERO; clutchWater = null; AgentNet.LOG.info("[{}] landed in water, no fall damage", name); }
            return true;
        }
        var w = openWorld();
        Vec3 v = airVel;
        double len = Math.sqrt(airSteer.x * airSteer.x + airSteer.z * airSteer.z);
        if (len > 1e-3 && !gliding) v = v.add(airSteer.x / len * 0.02, 0, airSteer.z / len * 0.02);
        double nx = p.x, ny = p.y, nz = p.z;
        boolean landed = false, splash = false;
        int waterY = Integer.MIN_VALUE;
        // Already in water (it dropped into a shallow pool): vanilla's water motion, not a fresh splash every tick. Each
        // tick of falling counted as a splash and a 3-tick hold, so it sank 0.08 a second and sat for seconds in a
        // half-block of water in a cave.
        boolean wetStart = false;
        if (!gliding && w != null) {
            int wx = (int) Math.floor(p.x), wy = (int) Math.floor(p.y), wz = (int) Math.floor(p.z);
            wetStart = isWater(w.get(wx, wy, wz), wx, wy, wz);
        }
        if (w == null || !hasWorldHere()) {
            // No world data (dropped while it stood idle): it can't tell floor from air, so flying a push out would
            // take it straight through the ground (the server lets that through). Stay put instead.
            AgentNet.LOG.info("[{}] pushed with no world loaded: staying where it stands", name);
            airVel = null; airSteer = Vec3.ZERO; gliding = false; rocketTicks = 0;
            return false;
        } else {
            // Vanilla's climbable rule: in a vine, ladder, scaffolding (the block at its feet), it sinks at most 0.15 a tick
            // and moves at most 0.15 sideways. Dropped through vines at full speed, the server put it back again and again
            // (a mangrove canopy held an agent for minutes).
            BlockState feetBlock = w.get((int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z));
            if (!gliding && feetBlock != null && feetBlock.is(net.minecraft.tags.BlockTags.CLIMBABLE))
                v = new Vec3(Math.max(-0.15, Math.min(0.15, v.x)), Math.max(-0.15, v.y), Math.max(-0.15, Math.min(0.15, v.z)));
            // Like the server: move the 0.6 x 1.8 box one axis at a time, Y first, stopping at blocks
            // (a mismatch over ~0.25 blocks gets "moved wrongly" and a rubber-band back).
            double ty = p.y + v.y;
            if (v.y < 0 && w.get((int) Math.floor(p.x), (int) Math.floor(p.y) - 1, (int) Math.floor(p.z)) == null && System.currentTimeMillis() - noDataSaidAt > 2_000) {
                noDataSaidAt = System.currentTimeMillis();
                AgentNet.LOG.warn("[{}] falling with no block data under it at {} (chunk {} {} stored: {}, {} chunks kept)", name, p, (int) Math.floor(p.x) >> 4, (int) Math.floor(p.z) >> 4, chunks.has((int) Math.floor(p.x) >> 4, (int) Math.floor(p.z) >> 4), chunks.size());
            }
            if (v.y < 0) {
                // Falling: the highest block top (its real shape: slabs, paths) between here and there.
                double floor = Double.NEGATIVE_INFINITY;
                for (int by = (int) Math.floor(p.y); by >= (int) Math.floor(ty) - 1 && floor == Double.NEGATIVE_INFINITY; by--) {
                    for (int bx = (int) Math.floor(p.x - 0.3); bx <= (int) Math.floor(p.x + 0.3); bx++)
                        for (int bz = (int) Math.floor(p.z - 0.3); bz <= (int) Math.floor(p.z + 0.3); bz++) {
                            BlockState s = w.get(bx, by, bz);
                            if (!wetStart && isWater(s, bx, by, bz) && by + 1 > ty && by <= p.y) { splash = true; waterY = Math.max(waterY, by); }
                            double top = by + topOf(s);
                            if (top > by && top <= p.y + 1e-6 && top >= ty) floor = Math.max(floor, top);
                        }
                }
                // A clutch boat we just put down (the server has it even if its spawn packet is still on the way): 0.5625 tall.
                if (clutchBoatAt != null && Math.abs(clutchBoatAt.x - p.x) < 1.0 && Math.abs(clutchBoatAt.z - p.z) < 1.0) {
                    double top = clutchBoatAt.y + 0.5625;
                    if (top <= p.y + 1e-6 && top >= ty) floor = Math.max(floor, top);
                }
                if (floor != Double.NEGATIVE_INFINITY) { ty = floor; landed = true; }
            } else if (v.y > 0 && !boxFree(w, p.x, ty, p.z)) { ty = p.y; v = new Vec3(v.x, 0, v.z); }
            ny = ty;
            if (splash) { ny = Math.max(ny, waterY + 0.1); landed = false; }
            if (boxFree(w, p.x + v.x, ny, p.z)) nx = p.x + v.x; else v = new Vec3(0, v.y, v.z);
            if (boxFree(w, nx, ny, p.z + v.z)) nz = p.z + v.z; else v = new Vec3(v.x, v.y, 0);
            // Slid off the edge of nothing: still standing on something after the horizontal move?
            if (!landed && v.y <= 0 && !boxFree(w, nx, ny - 0.05, nz)) {
                landed = true;
                // Onto it, not 0.02 above it (sinking through a vine it stopped just short, and the server said "standing on air").
                double top = Double.NEGATIVE_INFINITY;
                int by = (int) Math.floor(ny - 0.05);
                for (int bx = (int) Math.floor(nx - 0.3); bx <= (int) Math.floor(nx + 0.3); bx++)
                    for (int bz = (int) Math.floor(nz - 0.3); bz <= (int) Math.floor(nz + 0.3); bz++) {
                        BlockState s = w.get(bx, by, bz);
                        double t = by + topOf(s);
                        if (t > by && t <= ny + 1e-6) top = Math.max(top, t);
                    }
                if (top > Double.NEGATIVE_INFINITY) ny = top;
            }
        }
        // Boat clutch: the boat is placed but not here yet: hang just above the ground a moment (at most a few ticks).
        if (landed && fallHold > 0 && myVehicle < 0) {
            fallHold--;
            sendPosition(new ServerboundMovePlayerPacket.PosRot(p.x, p.y, p.z, pos.yRot(), pos.xRot(), false, false));
            return true;
        }
        if (++airTicks > (gliding ? 2400 : 200)) landed = true; // safety
        if (!Double.isFinite(nx) || !Double.isFinite(ny) || !Double.isFinite(nz)) { badMove("flight " + nx + " " + ny + " " + nz); airVel = null; airSteer = Vec3.ZERO; return true; }
        airPeakY = Math.max(airPeakY, ny);
        pos = new PositionMoveRotation(new Vec3(nx, ny, nz), Vec3.ZERO, pos.yRot(), pos.xRot());
        sendPosition(new ServerboundMovePlayerPacket.PosRot(nx, ny, nz, pos.yRot(), pos.xRot(), landed && !splash, false));
        if (splash) { gliding = false; rocketTicks = 0; waterHold = 3; airVel = Vec3.ZERO; AgentNet.LOG.info("[{}] splash at {} {} {}", name, nx, ny, nz); return true; }
        // The landing tick already sent this tick's position: nothing else may move this tick.
        if (landed || splash) { gliding = false; rocketTicks = 0; }
        if (landed) { airVel = null; airSteer = Vec3.ZERO; clutchWater = null; return true; }
        // Vanilla gravity: 0.08 a tick (0.01 with Slow Falling while going down), then 0.98 drag.
        double g = v.y <= 0 && hasEffect("slow_falling") ? 0.01 : 0.08;
        // In water (LivingEntity.travelInWater + getFluidFallingAdjustedMovement): times 0.8, then gravity/16 down.
        if (wetStart) { airVel = new Vec3(v.x * 0.8, v.y * 0.8 - g / 16, v.z * 0.8); return true; }
        airVel = gliding ? glide(v) : new Vec3(v.x * 0.91, (v.y - g) * 0.98, v.z * 0.91);
        return true;
    }

    /** Our own active effects (id like "minecraft:strength" -> when it runs out). */
    private final Map<String, Long> effects = new ConcurrentHashMap<>();

    @Override public boolean captain(int entityId) { Tracked t = tracked.get(entityId); return t != null && t.bannerHead; }

    @Override public boolean hasEffect(String id) {
        Long until = effects.get(id.contains(":") ? id : "minecraft:" + id);
        return until != null && until > System.currentTimeMillis();
    }

    // ---- elytra: gliding like a player (vanilla's fall-flying physics), rockets push along the look direction

    private boolean gliding;
    private int rocketTicks;

    /** One tick of elytra flight (LivingEntity.travelFallFlying, gravity 0.08), with a rocket's push while it burns. */
    private Vec3 glide(Vec3 v) {
        double yaw = Math.toRadians(pos.yRot()), pitch = Math.toRadians(pos.xRot());
        double lx = -Math.sin(yaw) * Math.cos(pitch), ly = -Math.sin(pitch), lz = Math.cos(yaw) * Math.cos(pitch);
        if (rocketTicks > 0) {
            v = v.add(lx * 0.1 + (lx * 1.5 - v.x) * 0.5, ly * 0.1 + (ly * 1.5 - v.y) * 0.5, lz * 0.1 + (lz * 1.5 - v.z) * 0.5);
        }
        double h = Math.sqrt(lx * lx + lz * lz), sp = Math.sqrt(v.x * v.x + v.z * v.z);
        double c = Math.cos(pitch);
        c = c * c * Math.min(1.0, Math.sqrt(lx * lx + ly * ly + lz * lz) / 0.4);
        v = v.add(0, 0.08 * (-1 + c * 0.75), 0);
        if (v.y < 0 && h > 0) { double m = v.y * -0.1 * c; v = v.add(lx * m / h, m, lz * m / h); }
        if (pitch < 0 && h > 0) { double m = sp * -Math.sin(pitch) * 0.04; v = v.add(-lx * m / h, m * 3.2, -lz * m / h); }
        if (h > 0) v = v.add((lx / h * sp - v.x) * 0.1, 0, (lz / h * sp - v.z) * 0.1);
        return new Vec3(v.x * 0.99, v.y * 0.98, v.z * 0.99);
    }

    /** Open the elytra (in the air, wearing one): the server starts fall-flying, our physics glide. */
    @Override public boolean startGliding() {
        if (airVel == null || gliding) return gliding;
        var chest = armor().get(1);
        if (chest.getItem() != net.minecraft.world.item.Items.ELYTRA) return false;
        send(playerCommand(net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        gliding = true;
        return true;
    }

    /** A player command packet for our own entity (we have no Entity object to build it from). */
    private net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket playerCommand(net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action action) {
        var buf = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeVarInt(myEntityId);
        buf.writeEnum(action);
        buf.writeVarInt(0);
        return net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.STREAM_CODEC.decode(buf);
    }

    /** The elytra came off (a chestplate swapped in): plain falling from here. */
    /** The rocket it fired keeps burning while the elytra is off (it pushes again the moment it glides again). */
    @Override public void stopGliding() { gliding = false; }
    @Override public int rocketLeft() { return rocketTicks; }
    @Override public boolean gliding() { return gliding; }
    /** A rocket was just used while gliding: push for its burn time (flight duration 1: about 20 ticks). */
    @Override public void rocketBoost() {
        if (!gliding) return;
        // A rocket lives 10 x (1 + flight duration) ticks, plus up to 11 more at random: count on the least.
        int flight = 1;
        try {
            var held = inventory().get(selectedSlot);
            var fw = held.get(net.minecraft.core.component.DataComponents.FIREWORKS);
            if (fw != null) flight = Math.max(1, fw.flightDuration());
        } catch (RuntimeException ignored) {}
        rocketTicks = 10 * (1 + flight);
    }

    /** Height of a block's collision top inside its cell (0 = nothing to stand on, 1 = full block). */
    private static double topOf(BlockState s) {
        if (s == null || s.isAir() || !s.getFluidState().isEmpty() && s.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) return 0;
        try {
            var shape = s.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            return shape.isEmpty() ? 0 : Math.min(1.5, shape.max(net.minecraft.core.Direction.Axis.Y));
        } catch (RuntimeException e) { return 1; }
    }

    /** The player box (0.6 wide, 1.8 tall) at feet (x,y,z) touches no block. */
    private boolean boxFree(dev.agentnet.pathing.BlockSource w, double x, double y, double z) {
        for (int bx = (int) Math.floor(x - 0.3); bx <= (int) Math.floor(x + 0.3 - 1e-7); bx++)
            for (int bz = (int) Math.floor(z - 0.3); bz <= (int) Math.floor(z + 0.3 - 1e-7); bz++)
                for (int by = (int) Math.floor(y - 0.5); by <= (int) Math.floor(y + 1.8 - 1e-7); by++) {
                    BlockState s = w.get(bx, by, bz);
                    double top = topOf(s);
                    if (top <= 0) continue;
                    // The block's solid part is [by, by+top) (fences 1.5 tall); does it overlap our [y, y+1.8)?
                    if (by + top > y + 1e-6 && by < y + 1.8 - 1e-6) return false;
                }
        return true;
    }

    // ---- boats: a boat clutch places one under us and gets in before landing

    /** Entity we're riding (-1 = none). */
    private volatile int myVehicle = -1;
    private volatile int recentBoat = -1;
    private volatile long recentBoatAt;
    private int unsneakIn;

    @Override public boolean riding() { return myVehicle >= 0; }
    @Override public int recentBoat() { return System.currentTimeMillis() - recentBoatAt < 2000 ? recentBoat : -1; }

    /** Get out of the vehicle: sneak for a tick. */
    @Override public void dismount() {
        send(new ServerboundPlayerInputPacket(new net.minecraft.world.entity.player.Input(false, false, false, false, false, true, false)));
        unsneakIn = 2;
    }

    /** Lunge and other spear jabs: the STAB action (26.x spears). */
    @Override public void stab() {
        send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STAB, BlockPos.ZERO, Direction.DOWN, 0));
    }

    private void mounted(int vehicle) {
        myVehicle = vehicle;
        airVel = null; airSteer = Vec3.ZERO; waterHold = 0; clutchWater = null; fallHold = 0;
        if (navigator.busy()) navigator.stop();
        AgentNet.LOG.info("[{}] got in a vehicle #{}", name, vehicle);
    }

    private void trackEntity(ClientboundAddEntityPacket p) {
        var type = p.getType();
        String typeName = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
        if ((typeName.endsWith("_boat") || typeName.endsWith("_raft")) && new Vec3(p.getX(), p.getY(), p.getZ()).distanceTo(pos.position()) < 6) { recentBoat = p.getId(); recentBoatAt = System.currentTimeMillis(); if (clutchBoatAt != null) AgentNet.LOG.info("[{}] boat #{} appeared, we are {} above it", name, p.getId(), String.format("%.2f", pos.position().y - p.getY())); }
        boolean player = type == EntityTypes.PLAYER;
        boolean monster = type.getCategory() == net.minecraft.world.entity.MobCategory.MONSTER;
        boolean trader = type == EntityTypes.VILLAGER || type == EntityTypes.WANDERING_TRADER;
        boolean animal = type.getCategory() == net.minecraft.world.entity.MobCategory.CREATURE; // hunters
        boolean other = type == EntityTypes.IRON_GOLEM || type == EntityTypes.ITEM_FRAME || type == EntityTypes.GLOW_ITEM_FRAME // looters
                || type == EntityTypes.ITEM          // followed as it falls: itemsNear gives where it lies now
                || type == EntityTypes.EYE_OF_ENDER // the speedrunner watches where its thrown eye flies
                || type == EntityTypes.END_CRYSTAL || type == EntityTypes.AREA_EFFECT_CLOUD // the dragon fight: crystals, its breath
                || typeName.endsWith("_boat") || typeName.endsWith("_raft"); // endermen are trapped in them
        if (!player && !monster && !trader && !animal && !other) return; // only what some role cares about
        tracked.put(p.getId(), new Tracked(p.getId(), type, p.getUUID(), new Vec3(p.getX(), p.getY(), p.getZ())));
    }

    @Override
    public List<dev.agentnet.role.Seen> entitiesNear(double radius) {
        Vec3 me = pos.position();
        List<dev.agentnet.role.Seen> out = new java.util.ArrayList<>();
        for (Tracked t : tracked.values()) {
            if (t.id == myEntityId || t.pos == null || t.type == EntityTypes.ITEM) continue; // items: itemsNear
            double d = t.pos.distanceTo(me);
            if (d > radius) continue;
            boolean player = t.type == EntityTypes.PLAYER;
            String who = player ? PLAYER_NAMES.get(t.uuid) : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(t.type).getPath();
            out.add(new dev.agentnet.role.Seen(t.id, who == null ? "?" : who, player,
                    t.type.getCategory() == net.minecraft.world.entity.MobCategory.MONSTER, t.pos, d,
                    (t.livingFlags & 1) != 0, (t.livingFlags & 2) != 0, t.mainHand, t.offHand, t.armorPieces,
                    t.swell > 0, t.angry, t.vehicle));
        }
        out.sort(java.util.Comparator.comparingDouble(dev.agentnet.role.Seen::distance));
        return out;
    }

    private volatile List<net.minecraft.world.item.trading.MerchantOffer> merchantOffers = List.of();

    @Override public List<net.minecraft.world.item.trading.MerchantOffer> tradeOffers() { return containerOpen() ? merchantOffers : List.of(); }

    @Override
    public void selectTrade(int index) { send(new net.minecraft.network.protocol.game.ServerboundSelectTradePacket(index)); }

    @Override
    public void interact(int entityId) {
        send(new net.minecraft.network.protocol.game.ServerboundInteractPacket(entityId, net.minecraft.world.InteractionHand.MAIN_HAND, new Vec3(0, 1, 0), false));
    }

    private long wallHitSaidAt;
    private int totemPops;
    private long noDataSaidAt;
    /** A small step on the spot (at most a fifth of a block this tick) toward a point at its own height: to stand square on a block. */
    @Override public void stepToward(double x, double z) {
        if (airVel != null || navigator.busy() || escapeGoal != null) return;
        Vec3 p = pos.position();
        double dx = x - p.x, dz = z - p.z, len = Math.hypot(dx, dz);
        if (len < 0.02) return;
        double step = Math.min(0.2, len);
        navBody.moveTo(p.x + dx / len * step, p.y, p.z + dz / len * step, Float.NaN);
    }

    private volatile boolean raining;
    @Override public boolean raining() { return raining; }

    /** One block up where it stands, a block from its inventory under its feet. False if it can not (no blocks, no room, in the air). */
    @Override public boolean pillarUp() {
        if (pillarPlace != null || escapeGoal != null || airVel != null || navigator.busy()) return false;
        var w = openWorld();
        if (w == null) return false;
        Vec3 p = pos.position();
        return pillar(w, (int) Math.floor(p.x), (int) Math.floor(p.y + 0.01), (int) Math.floor(p.z));
    }
    private static final Map<String, Long> WORLD_ASKED = new ConcurrentHashMap<>();

    @Override
    public void attack(int entityId) {
        // Never through a wall: a real player's swing would land on the block. Whatever role asks for the hit.
        Tracked t = tracked.get(entityId);
        if (t != null && t.pos != null) {
            Vec3 me = pos.position();
            if (!dev.agentnet.role.Sight.canHit(openWorld(), me.x, me.y, me.z, t.pos, t.type == EntityTypes.PLAYER)) {
                long now = System.currentTimeMillis();
                if (now - wallHitSaidAt > 5_000) { wallHitSaidAt = now; AgentNet.LOG.info("[{}] no hit: a block is between it and its target", name); }
                return;
            }
        }
        // In order with its moves: positions wait in a queue (one a tick, up to 4 behind), and an attack sent at once
        // reached the server while it still had the agent ticks back. In an elytra dive (2 blocks a tick) that was 6-8
        // blocks up, out of reach: the mace smash was dropped every time and the fall killed the agent.
        synchronized (this) {
            ServerboundMovePlayerPacket last = moveQueue.peekLast();
            if (last != null) { afterMove.computeIfAbsent(last, k -> new java.util.ArrayList<>()).add(new ServerboundAttackPacket(entityId)); afterMove.get(last).add(net.minecraft.network.protocol.game.ServerboundPunchPacket.INSTANCE); return; }
        }
        send(new ServerboundAttackPacket(entityId));
        swingArm();
    }

    /** A swing with another hotbar item, and straight back to the one in hand: all four packets in order behind the queued moves. */
    @Override public void attackWith(int entityId, int hotbarSlot) {
        int back = selectedSlot;
        if (hotbarSlot == back || hotbarSlot < 0 || hotbarSlot > 8) { attack(entityId); return; }
        java.util.List<Packet<?>> ps = java.util.List.of(new ServerboundSetCarriedItemPacket(hotbarSlot), new ServerboundAttackPacket(entityId),
                net.minecraft.network.protocol.game.ServerboundPunchPacket.INSTANCE, new ServerboundSetCarriedItemPacket(back));
        synchronized (this) {
            ServerboundMovePlayerPacket last = moveQueue.peekLast();
            if (last != null) { afterMove.computeIfAbsent(last, k -> new java.util.ArrayList<>()).addAll(ps); return; }
        }
        for (Packet<?> p : ps) send(p);
    }

    /** Tests only: holds its shield up for this many ticks (raised again every half second: a disabled shield comes back up). */
    public void holdShield(int ticks) { shieldHold = ticks; }
    private int shieldHold;

    /** Packets that go out right after this queued move (an attack made at that position). */
    private final java.util.Map<ServerboundMovePlayerPacket, java.util.List<Packet<?>>> afterMove = new java.util.IdentityHashMap<>();
    private void sendAfter(ServerboundMovePlayerPacket m) { java.util.List<Packet<?>> l = afterMove.remove(m); if (l != null) for (Packet<?> p : l) send(p); }

    /** 26.3's arm swing: shows the swing to everyone and resets the attack charge, like a real click. */
    @Override public void swingArm() {
        send(net.minecraft.network.protocol.game.ServerboundPunchPacket.INSTANCE);
    }

    /** Development only (-Dagentnet.sparring=Name): one of our own agents the others fight, for combat tests on a server without fake players. */
    private static final String SPARRING = System.getProperty("agentnet.sparring");

    @Override public boolean isEnemy(String player) {
        if (SPARRING != null && player != null && player.equalsIgnoreCase(SPARRING)) return !name.equalsIgnoreCase(SPARRING);
        if (player == null ||AgentNetClient.AGENTS.containsKey(player.toLowerCase())) return false;
        if (isFriendly(player)) return false; // whitelisted players and you are never fought, whatever they do
        if (recentlyHit(player)) return true; // just hit one of us: fight back
        return AgentFiles.HITLIST.contains(player) && !AgentFiles.WHITELIST.contains(player);
    }

    @Override public float health() { return lastHealth; }
    @Override public float maxHealth() { return maxHealth; }

    private static java.lang.reflect.Field moveEntityField;

    private static int moveEntityId(ClientboundMoveEntityPacket p) {
        try {
            if (moveEntityField == null) {
                moveEntityField = ClientboundMoveEntityPacket.class.getDeclaredField("entityId");
                moveEntityField.setAccessible(true);
            }
            return moveEntityField.getInt(p);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Integer.MIN_VALUE;
        }
    }

    private static java.lang.reflect.Field eventEntityField;

    private static int eventEntity(ClientboundEntityEventPacket p) {
        try {
            if (eventEntityField == null) {
                eventEntityField = ClientboundEntityEventPacket.class.getDeclaredField("entityId");
                eventEntityField.setAccessible(true);
            }
            return eventEntityField.getInt(p);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Integer.MIN_VALUE;
        }
    }
    @Override public boolean isDigging() { return digPos != null; }

    // ---- block predictions, as a real client keeps them
    //
    // A dig shows the block gone at once; the server answers with an ack of the action's sequence. If it refused the
    // dig (too little progress, out of reach) it sends no block update, only the ack, and the client is to put back what
    // it had. Without that, the agent believed in air where the block still stood: it walked on it, hovered over it,
    // and the server put it back again and again.
    private record Predicted(BlockPos pos, BlockState was, int sequence) {}
    private final java.util.List<Predicted> predicted = new java.util.concurrent.CopyOnWriteArrayList<>();
    private BlockState digState;
    /** The ground flag of the last move that went out: the server's idea of whether it stands (it digs 5x slower when not). */
    private volatile boolean sentGround = true;
    private boolean digAir;
    private int digWait;

    private void predictAir(BlockPos p, BlockState was, int sequence) {
        if (was != null) predicted.add(new Predicted(p.immutable(), was, sequence));
        while (predicted.size() > 64) predicted.remove(0);
        chunks.blockChanged(p.getX(), p.getY(), p.getZ(), Blocks.AIR.defaultBlockState());
    }

    /** The server said what is at this block: no prediction of ours stands for it any more. */
    private void serverBlock(BlockPos p) { if (!predicted.isEmpty()) predicted.removeIf(e -> e.pos.equals(p)); }

    /** The server acked our actions up to this sequence: predictions it didn't confirm with an update go back. */
    private void ackedUpTo(int sequence) {
        for (Predicted e : predicted) {
            if (e.sequence > sequence) continue;
            predicted.remove(e);
            chunks.blockChanged(e.pos.getX(), e.pos.getY(), e.pos.getZ(), e.was);
            AgentNet.LOG.info("[{}] the server kept the {} at {}: back in its picture of the blocks", name, e.was.getBlock().getDescriptionId().replace("block.minecraft.", ""), e.pos.toShortString());
        }
    }

    private boolean groundUnder() {
        var w = openWorld();
        if (w == null) return true;
        Vec3 p = pos.position();
        BlockState s = w.get((int) Math.floor(p.x), (int) Math.floor(p.y - 0.05), (int) Math.floor(p.z));
        return s == null || !airLike(s);
    }

    private boolean headInWater() {
        var w = openWorld();
        if (w == null) return false;
        Vec3 p = pos.position();
        BlockState s = w.get((int) Math.floor(p.x), (int) Math.floor(p.y + 1.62), (int) Math.floor(p.z));
        return s != null && s.getFluidState().is(net.minecraft.tags.FluidTags.WATER);
    }

    @Override
    public int dig(int x, int y, int z) {
        var world = openWorld();
        BlockState state = world == null ? null : world.get(x, y, z);
        if (state == null || state.isAir()) return -1;
        float hardness = state.getDestroySpeed(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (hardness < 0) return -1; // bedrock & co.
        BlockPos p = new BlockPos(x, y, z);
        Direction face = faceToward(p);
        send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, p, face, ++useSequence));
        if (hardness == 0) { predictAir(p, state, useSequence); return 0; }
        // Same formula as the server: progress per tick = speed / hardness / (30 if harvestable else 100).
        ItemStack held = inventory().get(selectedSlot);
        float speed = Math.max(1f, held.getDestroySpeed(state));
        // The server's penalties: five times slower off the ground, five times slower with its head under water. (Dug
        // in mid-air on a staircase hop, the block was "done" too early: the server kept it, and the agent walked on air.)
        digAir = airVel != null || !groundUnder() || !sentGround;
        if (digAir) speed /= 5f;
        if (headInWater()) speed /= 5f;
        boolean harvest = !state.requiresCorrectToolForDrops() || held.isCorrectToolForDrops(state);
        float perTick = speed / hardness / (harvest ? 30f : 100f);
        if (perTick >= 1f) { predictAir(p, state, useSequence); return 0; }
        digState = state;
        digPos = p;
        digTicksLeft = (int) Math.ceil(1f / perTick) + 1; // one tick of margin so the server never says "too fast"
        return digTicksLeft;
    }

    private void tickDigging() {
        if (digPos == null) return;
        if (--digTicksLeft > 0) return;
        // The server counts the dig with the ground flag the agent last sent (off the ground: five times slower). Timed as on
        // the ground but last said "in the air": it waits until the flag it sent says ground again (at most 5 s), or the
        // server keeps the block ("the server kept the stone", 70 times in 45 minutes, and agents walked into blocks
        // they thought were dug and were put back again and again).
        if (!digAir && !sentGround && digWait++ < 100) { digTicksLeft = 1; return; }
        digWait = 0;
        send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, digPos, faceToward(digPos), ++useSequence));
        predictAir(digPos, digState, useSequence);
        digPos = null;
    }

    /** Place the held block against a face while sneaking, so clicking a chest/door/table places instead of opening it. */
    @Override
    public void placeAgainst(int x, int y, int z, Direction face) {
        // Never into the crew's staircase: a pillar or a dropped block there blocks every miner's way down.
        if (dev.agentnet.role.MineShaft.inShaft(dev.agentnet.map.Servers.key(address), String.valueOf(dimension()), x + face.getStepX(), z + face.getStepZ())) {
            AgentNet.LOG.info("[{}] not placing a block in the crew's staircase at {} {} {}", name, x + face.getStepX(), y + face.getStepY(), z + face.getStepZ());
            return;
        }
        send(new ServerboundPlayerInputPacket(new net.minecraft.world.entity.player.Input(false, false, false, false, false, true, false)));
        useItemOn(x, y, z, face);
        send(new ServerboundPlayerInputPacket(net.minecraft.world.entity.player.Input.EMPTY));
    }

    @Override
    public void useItemOn(int x, int y, int z, Direction face) {
        BlockPos p = new BlockPos(x, y, z);
        Vec3 hit = Vec3.atCenterOf(p).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        send(new ServerboundUseItemOnPacket(net.minecraft.world.InteractionHand.MAIN_HAND,
                new BlockHitResult(hit, face, p, false), ++useSequence));
    }

    // ---- hunger, items on the ground (netty thread writes)
    private volatile int food = 20;
    /** Item entities the server told us about: id -> (estimated) position. */
    private final Map<Integer, Vec3> groundItems = new ConcurrentHashMap<>();

    @Override public int food() { return food; }

    @Override
    public List<Vec3> itemsNear(double radius) {
        Vec3 me = pos.position();
        List<Vec3> out = new ArrayList<>();
        // Where each item is now (it falls: a blaze rod drops from where the blaze died, in mid-air), not where it
        // appeared: the bots walked for spots three blocks up, found no way there, and the rods stayed on the floor.
        for (var e : groundItems.entrySet()) {
            Tracked t = tracked.get(e.getKey());
            Vec3 v = t != null && t.pos != null ? t.pos : e.getValue();
            if (v.distanceToSqr(me) <= radius * radius) out.add(v);
        }
        out.sort(java.util.Comparator.comparingDouble(v -> v.distanceToSqr(me)));
        return out;
    }

    @Override
    public void lookAt(double x, double y, double z) {
        Vec3 eye = pos.position().add(0, 1.62, 0);
        double dx = x - eye.x, dy = y - eye.y, dz = z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, h));
        look(yaw, offEnderman(eye, yaw, pitch));
    }

    /**
     * Never straight into an enderman's eyes: that is what makes it attack (the game's own test: the view within a
     * small angle of its eyes, closer = wider). A builder at the base kept looking past one while placing blocks and was
     * killed three times in two minutes. The pitch is tipped away a few degrees at a time until it is clear.
     */
    private float offEnderman(Vec3 eye, float yaw, float pitch) {
        java.util.List<dev.agentnet.role.Seen> near;
        try { near = entitiesNear(64); } catch (RuntimeException e) { return pitch; }
        for (int round = 0; round < 6; round++) {
            boolean staring = false;
            double vy = -Math.sin(Math.toRadians(pitch)), vh = Math.cos(Math.toRadians(pitch));
            double vx = -Math.sin(Math.toRadians(yaw)) * vh, vz = Math.cos(Math.toRadians(yaw)) * vh;
            for (dev.agentnet.role.Seen s : near) {
                if (!s.name().equals("enderman") || s.angry()) continue;
                double ex = s.pos().x - eye.x, ey = s.pos().y + 2.55 - eye.y, ez = s.pos().z - eye.z;
                double d = Math.sqrt(ex * ex + ey * ey + ez * ez);
                if (d < 0.5) continue;
                double dot = (vx * ex + vy * ey + vz * ez) / d;
                if (dot > 1 - 0.06 / d) { staring = true; pitch += ey > 0 ? 8f : -8f; break; } // twice the game's angle, for safety
            }
            if (!staring) break;
        }
        return Math.max(-90f, Math.min(90f, pitch));
    }

    // ---- recipes the server told this agent about (netty thread writes)
    private final Map<net.minecraft.world.item.crafting.display.RecipeDisplayId, net.minecraft.world.item.crafting.display.RecipeDisplayEntry> recipeBook = new ConcurrentHashMap<>();
    private volatile net.minecraft.world.item.crafting.SelectableRecipe.SingleInputSet<net.minecraft.world.item.crafting.StonecutterRecipe> stonecutter;

    @Override public java.util.Collection<net.minecraft.world.item.crafting.display.RecipeDisplayEntry> recipes() { return recipeBook.values(); }
    @Override public net.minecraft.world.item.crafting.SelectableRecipe.SingleInputSet<net.minecraft.world.item.crafting.StonecutterRecipe> stonecutterRecipes() { return stonecutter; }

    @Override
    public ItemStack resolve(net.minecraft.world.item.crafting.display.SlotDisplay d) {
        if (gameRegistries == null) return ItemStack.EMPTY;
        var ctx = net.minecraft.util.context.ContextMap.builder()
                .set(net.minecraft.world.item.crafting.display.SlotDisplayContext.REGISTRIES, gameRegistries)
                .buildAndValidate(net.minecraft.world.item.crafting.display.SlotDisplayContext.CONTEXT);
        try { return d.resolveForFirstStack(ctx); } catch (RuntimeException e) { return ItemStack.EMPTY; }
    }

    @Override
    public void placeRecipe(net.minecraft.world.item.crafting.display.RecipeDisplayId id, boolean max) {
        int c = containerOpen() ? openContainerId : 0;
        send(new net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket(c, id, max));
    }

    @Override
    public void clickMenuButton(int button) {
        if (containerOpen()) send(new net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket(openContainerId, button));
    }

    @Override
    public void quickMoveInventoryMenu(int slot) {
        click(0, inventoryStateId, slot, 0, net.minecraft.world.inventory.ContainerInput.QUICK_MOVE);
    }

    /** Left-click (button 0) or right-click (1) a slot of the player's own inventory menu: 0 result, 1-4 the 2x2 grid, 9-35 main, 36-44 hotbar. */
    @Override public void clickInventoryMenu(int menuSlot, int button) {
        click(0, inventoryStateId, menuSlot, button, net.minecraft.world.inventory.ContainerInput.PICKUP);
    }

    /** Inventory index (0-8 hotbar, 9-35 main) to its slot in the inventory menu. */
    @Override public int inventoryMenuSlot(int index) { return inventoryToMenuSlot(index); }

    /** 26.3 kicks ("Invalid move player packet") for a second position within one client tick. */
    private boolean movedThisTick;

    /** At most one position per client tick; a second is dropped (the next tick's carries the newer spot anyway). */
    private long tpSaidAt;
    private final java.util.List<Long> corrections = new java.util.concurrent.CopyOnWriteArrayList<>();

    private synchronized void sendPosition(ServerboundMovePlayerPacket p) {
        if (movedThisTick) return;
        movedThisTick = true;
        // Queued, not dropped: when the client lags it runs several ticks in a burst, and sending them all at once got
        // agents kicked, while dropping some merged "step up, then across" into one diagonal that clipped the block
        // edge, and the server put the agent back (hundreds of times an hour, agents re-planning until they gave up).
        // One per server tick, in order; only a long backlog loses its oldest.
        // A look on top of a look still waiting: the newer one takes its place (nothing rides on a plain look). A fighter
        // looking at its enemy every tick filled the queue to its limit and kept it there: the server ran 4 ticks behind,
        // and a wind-charge mace smash was taken for a plain hit (its fall, on the server, had only just begun).
        ServerboundMovePlayerPacket tail = moveQueue.peekLast();
        if (tail != null && !p.hasPosition() && !tail.hasPosition() && !afterMove.containsKey(tail)) moveQueue.pollLast();
        moveQueue.add(p);
        // Too long: the oldest that nothing waits behind goes (a swing that waits behind a move needs that move sent first).
        while (moveQueue.size() > 4) {
            ServerboundMovePlayerPacket drop = null;
            for (ServerboundMovePlayerPacket m : moveQueue) if (!afterMove.containsKey(m)) { drop = m; break; }
            if (drop != null) moveQueue.remove(drop); else sendAfter(moveQueue.poll());
        }
        flushMoves();
    }

    private final java.util.ArrayDeque<ServerboundMovePlayerPacket> moveQueue = new java.util.ArrayDeque<>();
    private long lastMoveNs;

    /**
     * A position went out since the last ClientTickEnd. 26.3 (vanilla, e.g. your own LAN world) kicks for a second
     * position before the next tick end, whatever the time between them: the answer to a correction (sent from the
     * network thread) and the tick's own move made two, and agents were kicked right after joining.
     */
    private boolean posSinceTickEnd;

    private synchronized void tickEnded() { posSinceTickEnd = false; sentTicks++; }

    private synchronized void flushMoves() {
        // Only from the game thread, the one that sends ClientTickEnd: sent from the network thread (the answer to a
        // correction), a position could get onto the wire ahead of a tick end sent just before it, and the server kicks.
        if (moveQueue.isEmpty() || posSinceTickEnd || !mc.isSameThread()) return;
        long now = System.nanoTime();
        // 20 ms, not 48: ticks come 50 ms apart give or take, and every tick that fell under 48 left one more move waiting
        // for good (one goes out a tick at most, so the queue never emptied again). The tick-end rule above is what the
        // server checks; this only spreads out a burst of ticks after a freeze.
        if (now - lastMoveNs < 20_000_000L) return;
        lastMoveNs = now;
        ServerboundMovePlayerPacket next = moveQueue.poll();
        if (next.hasPosition()) posSinceTickEnd = true;
        sentGround = next.isOnGround();
        send(next);
        sendAfter(next);
    }

    /** A move the server would kick us for (NaN etc.): drop it and say where it came from. */
    private void badMove(String what) {
        AgentNet.LOG.warn("[{}] not sending an invalid {} (would get kicked)", name, what, new IllegalStateException("bad movement"));
    }

    @Override
    public void swapOffhand() {
        send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN, 0));
    }

    @Override
    public void changeRole(Role r) {
        mc.execute(() -> { if (!closed) setRole(r); });
    }
    @Override
    public void assignTask(String t) {
        mc.execute(() -> { if (!closed) setTask(t); });
    }


    @Override
    public void dropOne(int index) {
        if (index < 0 || index >= 36) return;
        click(0, inventoryStateId, inventoryToMenuSlot(index), 0, net.minecraft.world.inventory.ContainerInput.THROW);
    }

    /** The face of a block that points at the agent. */
    private Direction faceToward(BlockPos p) {
        Vec3 eye = pos.position().add(0, 1.62, 0);
        Vec3 d = eye.subtract(Vec3.atCenterOf(p));
        return Direction.getApproximateNearest(d.x, d.y, d.z);
    }

    // ---------------------------------------------------------------- navigation

    @Override
    public void navigate(int x, int y, int z, int radius) {
        if (!isOnline()) return;
        // Long trips by elytra (any role): flying, or about to.
        if (flight.active()) { flightGoal = new PathFinder.Node(x, y, z); flightRadius = radius; flight.retarget(x, y, z); return; }
        if (myVehicle < 0 && escapeGoal == null && Math.hypot(x + 0.5 - pos.position().x, z + 0.5 - pos.position().z) > dev.agentnet.role.ElytraTravel.MIN_TRIP
                && dev.agentnet.role.ElytraTravel.ready(this)) {
            flightGoal = new PathFinder.Node(x, y, z); flightRadius = radius;
            pendingGoal = null; navigator.stop();
            flight.start(this, x, y, z);
            return;
        }
        if (escapeGoal != null) { escapeGoal = new PathFinder.Node(x, y, z); escapeRadius = radius; return; } // digging out first; then this trip
        // Same goal that just had no path: say so again instead of reconnecting for nothing.
        if (failedGoal != null && failedGoal.x() == x && failedGoal.y() == y && failedGoal.z() == z
                && System.currentTimeMillis() - failedAt < FAILED_GOAL_MS && !hasWorldHere()) {
            pendingGoal = null;
            return;
        }
        if (hasWorldHere()) {
            pendingGoal = null;
            navigator.navigate(x, y, z, radius);
        } else if (System.currentTimeMillis() - onlineSince < WAIT_FOR_WORLD_MS) {
            // Just joined: chunks are still arriving. Start as soon as they're here.
            pendingGoal = new PathFinder.Node(x, y, z);
            pendingRadius = radius;
            pendingSince = System.currentTimeMillis();
        } else {
            // The world was dropped to save memory: reconnect so the server sends it again.
            log("§7reconnecting to see the world again before travelling");
            AgentNetClient.reconnect(this, new PathFinder.Node(x, y, z), radius);
        }
    }

    private static final long FAILED_GOAL_MS = 30_000;
    // ---- stuck watch: detection, and a ladder of ways out

    private Vec3 stuckAnchor;
    private long stuckSince, stuckActedAt;
    private int stuckLevel, stuckTicks;
    private String stuckTask;

    /** It means to go somewhere: a trip, a dig-out, or a trip that failed in the last minute. */
    private boolean wantsToMove() {
        return navigator.busy() || escapeGoal != null || failedGoal != null && System.currentTimeMillis() - failedAt < 60_000;
    }

    /**
     * Wants to move but hasn't got 1.5 blocks anywhere in 20 s: stuck. One step more each further 20 s (a minute each was
     * far too slow: the user had to pull agents out by hand), back to the start as soon as it moves again: (1) stop, to the
     * middle of its block, plan afresh; (2) dig toward where it was going; (3) dig and pillar straight up to the surface,
     * or with open sky over it (a ditch, a pit) two blocks up where it stands; (4) leave and come back (its idea of where it is may no
     * longer match the server's); (5) drop what it was after and start its job over (another target).
     */
    private void tickStuckWatch() {
        if (++stuckTicks % 20 != 0 || airVel != null || isDigging() || myVehicle >= 0) return;
        // Dug in for the night: still on purpose. (The stuck watch dug one back out toward its old job, into a skeleton.)
        if (burrow != Burrow.NONE) { stuckAnchor = null; stuckLevel = 0; return; }
        Vec3 p = pos.position();
        long now = System.currentTimeMillis();
        if (stuckAnchor == null || stuckAnchor.distanceToSqr(p) > 1.5 * 1.5 || !wantsToMove()) {
            if (stuckLevel > 0 && stuckAnchor != null && stuckAnchor.distanceToSqr(p) > 1.5 * 1.5) log("§aunstuck: moving again");
            stuckAnchor = p; stuckSince = now; stuckActedAt = now; stuckLevel = 0;
            return;
        }
        if (now - stuckActedAt < 20_000) return;
        stuckActedAt = now;
        stuckLevel++;
        PathFinder.Node g = failedGoal != null ? failedGoal : navigator.goal();
        String where = String.format("%.0f %.0f %.0f", p.x, p.y, p.z);
        AgentNet.LOG.info("[{}] stuck for {} s at {} (goal {}), step {}; around it:\n{}", name, (now - stuckSince) / 1000, where,
                g == null ? "none" : g.x() + " " + g.y() + " " + g.z(), stuckLevel, surroundings());
        switch (stuckLevel) {
            case 1 -> {
                log("§estuck for 20 s at " + where + ": to the middle of its block, planning afresh");
                gravityCheck = 9; // and down onto whatever is under it, if its idea of its height is off
                if (navigator.busy()) navigator.stop();
                escapeGoal = null; escapeStep = null; escapeMoves.clear();
                var w = openWorld();
                int bx = (int) Math.floor(p.x), by = (int) Math.floor(p.y + 0.01), bz = (int) Math.floor(p.z);
                if (w != null && dev.agentnet.pathing.Rules.canWalkThrough(w.get(bx, by, bz)) && dev.agentnet.pathing.Rules.canWalkThrough(w.get(bx, by + 1, bz)))
                    navBody.moveTo(bx + 0.5, p.y, bz + 0.5, Float.NaN);
                if (g != null) navigator.navigate(g.x(), g.y(), g.z(), Math.max(1, navigator.radius()));
            }
            case 2 -> {
                log("§estill stuck: digging toward where it was going");
                escapeRounds = 0; escapeStuck = 0; escapeAt = 0; escapeGoal = null;
                if (g != null) startEscape(g, 1);
            }
            case 3 -> {
                log("§estill stuck: straight up to the surface");
                surfaceTriedAt = 0; escapeGoal = null; escapeStuck = 0;
                if (!toSurface(g, now) && !pillarOut(g)) log("§eit's at the surface already and can't pillar here");
            }
            case 4 -> {
                log("§estill stuck: leaving and coming straight back");
                mc.execute(() -> AgentNetClient.leaveClutch(this));
            }
            default -> {
                log("§estill stuck: dropping what it was after and starting its job over");
                failedGoal = null;
                if (navigator.busy()) navigator.stop();
                String t = task;
                if (role != null) { stopModule(); module = role.createModule(); moduleStarted = false; }
                if (t != null && !t.equals("idle")) mc.execute(() -> { if (!closed) setTask(t); });
                stuckLevel = 0;
            }
        }
    }

    private final java.util.List<Long> putBacks = new java.util.concurrent.CopyOnWriteArrayList<>();
    // Per name, not per connection: a rejoin is a new Agent, and the "once a minute" limit started over every time, so
    // a distributor left and came back every 4 s for ten minutes (119 rejoins) put back at the same spot each time.
    private static final java.util.Map<String, Long> PUT_BACK_REJOIN = new java.util.concurrent.ConcurrentHashMap<>();
    private PathFinder.Node failedGoal;
    private long failedAt;
    private String failedWhy;

    // A trip the job itself called off is not one the stuck watch should chase: it sent a speedrunner standing at its
    // furnace off toward the miner's old goal, and the iron was left in the furnace.
    @Override public void stopNavigation() { pendingGoal = null; navigator.stop(); failedGoal = null; if (flight.active()) { flightGoal = null; flight.stop(this); } }
    @Override public boolean isNavigating() { return pendingGoal != null || navigator.busy() || flight.active(); }

    /** Long trips by elytra, for every role. */
    private final dev.agentnet.role.ElytraTravel flight = new dev.agentnet.role.ElytraTravel();
    private PathFinder.Node flightGoal;
    private int flightRadius;
    @Override public boolean flying() { return flight.active(); }
    @Override public String navStatus() {
        if (pendingGoal != null) return "waiting for chunks";
        return navigator.status().name().toLowerCase()
                + (navigator.lastProblem().isEmpty() ? "" : " (" + navigator.lastProblem() + ")");
    }

    /** Resume a trip after a reconnect. */
    void navigateWhenOnline(PathFinder.Node goal, int radius) {
        pendingGoal = goal;
        pendingRadius = radius;
        pendingSince = System.currentTimeMillis();
    }

    private boolean hasWorldHere() {
        Vec3 p = pos.position();
        return chunks.has((int) Math.floor(p.x) >> 4, (int) Math.floor(p.z) >> 4);
    }

    // ---------------------------------------------------------------- gravity
    // Agents have no physics. Standing on nothing (its saved spot was removed while it was
    // offline, someone broke the block under it) gets a player kicked for "flying" after 4 s,
    // so an agent with air under its whole hitbox drops until it lands, 0.8 blocks a tick.

    private int gravityCheck;
    private int hoverTicks;

    /** Nothing under any corner of its hitbox, by what it knows of the world (water counts as support). */
    private boolean hovering() {
        var w = openWorld();
        if (w == null) return false;
        Vec3 p = pos.position();
        int fy = (int) Math.floor(p.y + 0.01);
        for (double ox : new double[]{-0.29, 0.29}) for (double oz : new double[]{-0.29, 0.29}) {
            int bx = (int) Math.floor(p.x + ox), bz = (int) Math.floor(p.z + oz);
            BlockState under = w.get(bx, fy - 1, bz), at = w.get(bx, fy, bz);
            if (under == null || at == null) return false;
            if (!airLike(under) || !airLike(at) && p.y - fy > 0.01) return false;
        }
        return true;
    }
    private double fallTo = Double.NaN;

    /** Spots where the ground gave way under its path lately: x, y, z, when. Avoided by the next plans for 2 minutes. */
    private final java.util.List<long[]> holes = new java.util.concurrent.CopyOnWriteArrayList<>();

    private void tickGravity() {
        if (!Double.isNaN(fallTo)) {
            // Nothing holds it up: a real fall (the air physics, as vanilla: from standing still, faster each tick, until
            // it hits something, slowly through vines and ladders). It used to slide straight down 0.8 a tick to a floor
            // worked out beforehand, which the server refused in a vine.
            fallTo = Double.NaN;
            if (airVel != null) return;
            // A trip started in the tick between (its job planning again at once) used to cancel the fall: notTurtle hung over
            // a hole for ten minutes, "dropping" every second and never falling. The fall comes first; the trip is planned again.
            if (navigator.busy()) navigator.stop();
            airVel = Vec3.ZERO; airSteer = Vec3.ZERO; airTicks = 0; airPeakY = pos.position().y;
            return;
        }
        if (navigator.busy() && airVel == null) {
            // Walking a path over a block that has gone since it was planned (a miner dug it out): it would walk on
            // air until the server kicks it for flying. In the air for over a second while walking: stop, fall, plan again.
            if (++gravityCheck % 5 != 0) return;
            if (!hovering()) { hoverTicks = 0; return; }
            if ((hoverTicks += 5) < 25) return;
            hoverTicks = 0;
            AgentNet.LOG.info("[{}] the ground on its path is gone at {}: dropping and planning again", name, pos.position());
            // The next plan goes round this spot: planning the same way over it again bounced it up and down over the
            // hole until the server kicked it for flying.
            { Vec3 hp = pos.position(); holes.add(new long[]{(long) Math.floor(hp.x), (long) Math.floor(hp.y + 0.01), (long) Math.floor(hp.z), System.currentTimeMillis()}); }
            navigator.stop();
        } else if (navigator.busy() || ++gravityCheck % 10 != 0) return; // while digging too: a miner that digs out its own footing (or stands on leaves that decay) must fall, or the server kicks it for floating
        var w = openWorld();
        if (w == null) return;
        Vec3 p = pos.position();
        int fy = (int) Math.floor(p.y + 0.01);
        // Anything under any corner of the 0.6-wide hitbox holds it up, as high as its top. Higher than the highest of
        // those tops, it hovers: down to it. (It hung 0.7 above a block of tuff bricks in a trial chamber, the server
        // saying "standing on air" every 10 s.)
        double support = Double.NEGATIVE_INFINITY;
        for (double ox : new double[]{-0.29, 0.29}) for (double oz : new double[]{-0.29, 0.29}) {
            int bx = (int) Math.floor(p.x + ox), bz = (int) Math.floor(p.z + oz);
            BlockState under = w.get(bx, fy - 1, bz), at = w.get(bx, fy, bz);
            if (under == null || at == null) return; // unknown: leave it
            if (!airLike(at)) support = Math.max(support, fy + topOf(at));
            if (!airLike(under)) support = Math.max(support, fy - 1 + topOf(under));
        }
        if (support > Double.NEGATIVE_INFINITY) {
            // Only a clear gap: small ones (0.2, 0.5) came up over and over on stairs and slabs, where the corners don't tell.
            if (p.y <= support + 0.6) return;
            fallTo = support;
            long nowH = System.currentTimeMillis();
            if (nowH - hoverSaidAt > 30_000) { hoverSaidAt = nowH; AgentNet.LOG.info("[{}] hovering {} above what holds it up at {}: down onto it", name, String.format("%.2f", p.y - support), p); }
            return;
        }
        int x = (int) Math.floor(p.x), z = (int) Math.floor(p.z);
        for (int y = fy - 1; y > dimMinY; y--) {
            BlockState s = w.get(x, y - 1, z);
            if (s == null) return;
            if (!s.getFluidState().isEmpty()) { fallTo = y; break; }          // water/lava: stop at its surface
            if (!airLike(s)) {
                double top = 1.0;
                try { top = s.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO).max(Direction.Axis.Y); }
                catch (RuntimeException ignored) {}
                fallTo = y - 1 + top;
                break;
            }
        }
        if (!Double.isNaN(fallTo)) AgentNet.LOG.info("[{}] standing on nothing at {}; dropping to y={}", name, p, fallTo);
    }

    /** Its feet are in water (swimming across). */
    private boolean feetInWater() {
        var w = openWorld();
        if (w == null) return false;
        Vec3 p = pos.position();
        BlockState s = w.get((int) Math.floor(p.x), (int) Math.floor(p.y + 0.01), (int) Math.floor(p.z));
        return s != null && s.getFluidState().is(net.minecraft.tags.FluidTags.WATER);
    }

    /** No collision box and no fluid. */
    private long hoverSaidAt;

    /** Feet height at x/z for a path node at whole-number height y: lowered onto a partial block under it (slab, farmland, carpet). */
    private double groundTop(double x, double y, double z) {
        var w = openWorld();
        if (w == null) return y;
        int fy = (int) y;
        double best = Double.NEGATIVE_INFINITY;
        for (double ox : new double[]{-0.29, 0.29}) for (double oz : new double[]{-0.29, 0.29}) {
            int bx = (int) Math.floor(x + ox), bz = (int) Math.floor(z + oz);
            BlockState at = w.get(bx, fy, bz), under = w.get(bx, fy - 1, bz);
            if (at == null || under == null) return y;
            if (!airLike(at)) return y; // something in its own cell (a slab it stands on top of the path's way): leave it
            if (!airLike(under)) best = Math.max(best, fy - 1 + topOf(under));
        }
        if (best == Double.NEGATIVE_INFINITY || best > y || best < y - 0.95) return y;
        return best;
    }

    private static boolean airLike(BlockState s) {
        if (s.isAir()) return true;
        if (!s.getFluidState().isEmpty()) return false;
        try { return s.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty(); }
        catch (RuntimeException e) { return false; }
    }

    // ---- digging out of a pit: no way at all from where it stands

    private PathFinder.Node escapeGoal, escapeStep;
    private Vec3 escapeFrom;
    private int escapeStuck;
    private final java.util.List<Vec3> escapeMoves = new java.util.ArrayList<>();
    private int escapeRadius, escapeDigs, escapeRounds;
    private long escapeAt, escapeLoggedAt;
    private Vec3 lastFailAt;
    private long lastFailMs;
    private long escapeSaidAt;
    private BlockPos pillarPlace;
    private int escapeHover;

    private long surroundSaidAt;

    /** The blocks around it, for the log: 5 x 5 at each height from 2 under its feet to 2 over its head. */
    private String surroundings() {
        var w = openWorld();
        if (w == null) return "(no world)";
        Vec3 p = pos.position();
        int fx = (int) Math.floor(p.x), fy = (int) Math.floor(p.y + 0.01), fz = (int) Math.floor(p.z);
        StringBuilder sb = new StringBuilder();
        for (int dy = 3; dy >= -2; dy--) {
            sb.append("  y").append(fy + dy).append(dy == 0 ? " (feet)" : dy == 1 ? " (head)" : "").append(": ");
            for (int dz = -2; dz <= 2; dz++) {
                for (int dx = -2; dx <= 2; dx++) {
                    BlockState s = w.get(fx + dx, fy + dy, fz + dz);
                    char c = s == null ? '?' : s.getFluidState().is(net.minecraft.tags.FluidTags.LAVA) ? 'L' : !s.getFluidState().isEmpty() ? '~'
                            : dev.agentnet.pathing.Rules.canWalkThrough(s) ? '.' : '#';
                    sb.append(dx == 0 && dz == 0 && (dy == 0 || dy == 1) ? Character.toLowerCase(c == '.' ? 'a' : 'x') : c);
                }
                sb.append(dz < 2 ? " " : "");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private long surfaceTriedAt;

    /**
     * Last resort before giving up: no way out toward the goal (a cave pocket it keeps digging around in), so straight up
     * to the surface over it, pillaring and digging through the ceiling. Up there walking works again. False if it
     * tried that in the last 2 minutes, or it is at the surface already.
     */
    /**
     * Open sky over it but still no way on (a ditch or pit two or more deep, steep sides): two blocks straight up on its
     * own blocks, where it stands. From up there it walks on. Only when where it is going is not lower than here.
     */
    private boolean pillarOut(PathFinder.Node goal) {
        Vec3 p = pos.position();
        int fx = (int) Math.floor(p.x), fy = (int) Math.floor(p.y + 0.01), fz = (int) Math.floor(p.z);
        if (goal != null && goal.y() < fy) return false;
        escapeStuck = 0; escapeRounds = 0;
        escapeFrom = p;
        escapeAt = System.currentTimeMillis();
        escapeGoal = new PathFinder.Node(fx, fy + 3, fz); // up until its feet are two higher (the escape pillars while the goal is over its head)
        escapeRadius = 1;
        escapeDigs = 0;
        log("§estill stuck: pillaring two blocks up where it stands");
        return true;
    }

    @Override public boolean goUp() { surfaceTriedAt = 0; return toSurface(null, System.currentTimeMillis()); }
    @Override public boolean escaping() { return escapeGoal != null; }

    private boolean toSurface(PathFinder.Node goal, long now) {
        if (now - surfaceTriedAt < 120_000 || !dimension().endsWith("overworld")) return false;
        var w = openWorld();
        if (w == null) return false;
        Vec3 p = pos.position();
        int fx = (int) Math.floor(p.x), fy = (int) Math.floor(p.y + 0.01), fz = (int) Math.floor(p.z);
        int top = fy;
        // The highest real ceiling over it. Leaves, logs and vines are a tree, not a roof: under a canopy it dug straight up
        // through the tree and ended up standing on top of the leaves, unable to get down.
        for (int y = fy + 1; y < dimMinY + dimHeight; y++) {
            BlockState s = w.get(fx, y, fz);
            if (s == null) break;
            if (s.isAir() || s.is(net.minecraft.tags.BlockTags.LEAVES) || s.is(net.minecraft.tags.BlockTags.LOGS) || s.is(net.minecraft.tags.BlockTags.CLIMBABLE)) continue;
            top = y;
        }
        if (top <= fy + 2) return false; // open sky over it: nothing to dig up through
        surfaceTriedAt = now;
        escapeStuck = 0;
        escapeRounds = 0;
        escapeFrom = p;
        escapeAt = now;
        escapeGoal = new PathFinder.Node(fx, top + 1, fz);
        escapeRadius = 1;
        escapeDigs = -200; // a long way up: more digging allowed than for one step
        log("§eno way out from here: going straight up to the surface (y=" + (top + 1) + ")");
        return true;
    }

    private void startEscape(PathFinder.Node goal, int radius) {
        if (burrow != Burrow.NONE && burrow != Burrow.OUT) return; // digging in for the night: it is meant to be in a hole
        if (goal != null && goal.y() < pos.position().y - 1 && !navBody.hasPickaxe()) return; // no pickaxe: never digging deeper down
        if (underwaterTicks > 0) return; // under water: up for air first (tickUnderwater), no digging sideways
        if (escapeGoal != null) return; // already digging out: let it finish
        if (!hasWorldHere() || onlineFor() < 5_000) return; // no world loaded yet: that's why there was no way, not a pit
        long now = System.currentTimeMillis();
        if (now - escapeAt > 60_000) escapeRounds = 0;
        Vec3 here = pos.position();
        // Climbing out of a deep pit (each step higher than the last): keep going, however many steps it takes.
        if (escapeFrom != null && here.y > escapeFrom.y + 0.5) escapeRounds = 0;
        if (escapeRounds >= 4 && now - surroundSaidAt > 120_000) {
            surroundSaidAt = now;
            AgentNet.LOG.info("[{}] still boxed in after {} rounds at {} (goal {} {} {}); around it (rows north to south, west to east; # solid . open ~ water L lava ? unknown):\n{}",
                    name, escapeRounds, here, goal.x(), goal.y(), goal.z(), surroundings());
        }
        if (++escapeRounds > 16) return; // dug a long way already without getting higher: leave it to the job
        // Rounds that got it nowhere (it digs, steps, and is still boxed in at the same spot): stop after a few.
        if (escapeFrom != null && escapeFrom.distanceToSqr(here) < 0.5) { if (++escapeStuck >= 3 && !toSurface(goal, now)) { if (now - escapeLoggedAt > 60_000) { escapeLoggedAt = now; log("§ecan't dig its way out from here: staying put"); } escapeRounds = 99; return; } }
        else escapeStuck = 0;
        if (escapeGoal != null) return; // toSurface just set the way up
        escapeFrom = here;
        escapeAt = now;
        escapeGoal = goal;
        escapeRadius = radius;
        escapeDigs = 0;
        if (now - escapeLoggedAt > 10_000) { escapeLoggedAt = now; log("boxed in where it stands: digging (or pillaring) its way out toward " + goal.x() + " " + goal.y() + " " + goal.z()); }
    }

    /**
     * One block at a time: a step toward the goal (up a block if the next column is higher), with
     * head room. Never liquids, bedrock or traps. Once the way is open, the trip is planned again.
     */
    private void tickEscape() {
        if (underwaterTicks >= 20) { escapeGoal = null; escapeStep = null; escapeMoves.clear(); return; }
        // Nothing under it while digging out (a pillar block the server refused, footing someone else mined away):
        // give the escape up and fall, or the server kicks it for flying. A few ticks of grace for the placed block to arrive.
        if (pillarPlace == null && hovering()) {
            if (++escapeHover >= 12) {
                escapeHover = 0;
                AgentNet.LOG.info("[{}] nothing under it while digging out at {}: dropping first", name, pos.position());
                escapeGoal = null; escapeStep = null; escapeMoves.clear();
                gravityCheck = 9; // the gravity check runs on the very next tick
                return;
            }
        } else escapeHover = 0;
        if (isDigging()) return;
        if (pillarPlace != null) {
            // Pillar step, second tick: the block under its feet (and a tick to settle on it).
            lookAt(pillarPlace.getX() + 0.5, pillarPlace.getY() + 1.0, pillarPlace.getZ() + 0.5);
            placeAgainst(pillarPlace.getX(), pillarPlace.getY(), pillarPlace.getZ(), Direction.UP);
            pillarPlace = null;
            escapeMoves.add(pos.position()); // stand still one tick on the new block before planning again
            return;
        }
        if (escapeStep != null) {
            if (!escapeMoves.isEmpty()) { Vec3 v = escapeMoves.remove(0); navBody.moveTo(v.x, v.y, v.z, Float.NaN); return; } // stepping into the gap it dug
            escapeStep = null;
            PathFinder.Node g = escapeGoal;
            escapeGoal = null;
            failedGoal = null;
            navigator.navigate(g.x(), g.y(), g.z(), escapeRadius); // fails again from here: the next step gets dug
            return;
        }
        var w = openWorld();
        if (w == null || ++escapeDigs > 40) { escapeGoal = null; return; }
        Vec3 p = pos.position();
        int fx = (int) Math.floor(p.x), fy = (int) Math.floor(p.y + 0.01), fz = (int) Math.floor(p.z);
        BlockState in = w.get(fx, fy, fz), up = w.get(fx, fy + 1, fz);
        if (in != null && up != null && !dev.agentnet.pathing.Rules.canWalkThrough(in) && dev.agentnet.pathing.Rules.canWalkThrough(up)) fy++;
        // The way out is up and there's room over its head (a shaft it mined down, a pit): pillar up like a
        // player, a block under its feet each step. Much faster than digging a staircase out.
        if (escapeGoal.y() > fy + 1 && pillar(w, fx, fy, fz)) return;
        int gx = escapeGoal.x() - fx, gz = escapeGoal.z() - fz;
        int[][] dirs = Math.abs(gx) >= Math.abs(gz)
                ? new int[][]{{Integer.signum(gx) == 0 ? 1 : Integer.signum(gx), 0}, {0, gz >= 0 ? 1 : -1}, {0, gz >= 0 ? -1 : 1}, {gx >= 0 ? -1 : 1, 0}}
                : new int[][]{{0, Integer.signum(gz) == 0 ? 1 : Integer.signum(gz)}, {gx >= 0 ? 1 : -1, 0}, {gx >= 0 ? -1 : 1, 0}, {0, gz >= 0 ? -1 : 1}};
        for (int[] d : dirs) {
            int nx = fx + d[0], nz = fz + d[1];
            BlockState next = w.get(nx, fy, nz);
            if (next == null) continue;
            boolean stepUp = !dev.agentnet.pathing.Rules.canWalkThrough(next);
            int[][] cells = stepUp ? new int[][]{{fx, fy + 2, fz}, {nx, fy + 1, nz}, {nx, fy + 2, nz}} : new int[][]{{nx, fy, nz}, {nx, fy + 1, nz}};
            boolean blocked = false;
            int[] dig = null;
            for (int[] c : cells) {
                BlockState s = w.get(c[0], c[1], c[2]);
                // Water at its feet with open air over it: it swims there (a pond or river is a way out, not a wall).
                boolean wet = s != null && !stepUp && c[1] == fy && s.getFluidState().is(net.minecraft.tags.FluidTags.WATER) && !dev.agentnet.pathing.Rules.dangerous(s)
                        && dev.agentnet.pathing.Rules.canWalkThrough(w.get(c[0], fy + 1, c[2]));
                if (s == null || !s.getFluidState().isEmpty() && !wet || s.getBlock() == net.minecraft.world.level.block.Blocks.BEDROCK
                        || dev.agentnet.pathing.Rules.dangerous(s) || spawnProtected(c[0], c[2]) || inBase(c[0], c[1], c[2]) && !dev.agentnet.pathing.Rules.canWalkThrough(s) && !baseDiggable(s, c[0], c[1], c[2], fy)
                        || !s.isAir() && dev.agentnet.pathing.Rules.canWalkThrough(s) == false && !escapeDiggable(s, c[0], c[1], c[2])) { blocked = true; break; }
                if (!wet && !dev.agentnet.pathing.Rules.canWalkThrough(s) && dig == null) dig = c;
            }
            if (blocked) continue;
            if (dig != null) { dev.agentnet.role.Gear.holdBestFor(this, w.get(dig[0], dig[1], dig[2])); dig(dig[0], dig[1], dig[2]); return; } // with its pickaxe, not its fist
            // This step is open now: step into it (straight up first if it's a step up, then across:
            // the same kind of short move the walker makes), then plan the whole trip again from there.
            escapeStep = new PathFinder.Node(nx, stepUp ? fy + 1 : fy, nz);
            escapeMoves.clear();
            Vec3 here = pos.position();
            if (stepUp) escapeMoves.add(new Vec3(here.x, fy + 1.0, here.z));
            double ty = stepUp ? fy + 1.0 : fy;
            for (int k = 1; k <= 3; k++) escapeMoves.add(new Vec3(here.x + (nx + 0.5 - here.x) * k / 3, ty, here.z + (nz + 0.5 - here.z) * k / 3));
            return;
        }
        log("§eboxed in and every way out is water, lava, bedrock or protected: staying put");
        escapeGoal = null;
    }

    /** One pillar step: up a block, a cobblestone (or dirt, netherrack...) where its feet were. False if it can't. */
    private boolean pillar(dev.agentnet.pathing.BlockSource w, int fx, int fy, int fz) {
        if (dev.agentnet.role.MineShaft.inShaft(dev.agentnet.map.Servers.key(address), String.valueOf(dimension()), fx, fz)) return false; // in the staircase: walk it, never pillar in it
        BlockState feet = w.get(fx, fy, fz), head = w.get(fx, fy + 1, fz), above = w.get(fx, fy + 2, fz), under = w.get(fx, fy - 1, fz);
        if (feet == null || head == null || above == null || under == null) return false;
        if (!dev.agentnet.pathing.Rules.canWalkThrough(feet)) return false;
        // A block over its head (a ceiling, an overhang): dig it out of the way first, then go on up. Never water,
        // lava, bedrock, traps, or anything of the base.
        for (int dy = 1; dy <= 2; dy++) {
            BlockState s = dy == 1 ? head : above;
            if (dev.agentnet.pathing.Rules.canWalkThrough(s)) continue;
            if (!s.getFluidState().isEmpty() || s.getBlock() == net.minecraft.world.level.block.Blocks.BEDROCK || dev.agentnet.pathing.Rules.dangerous(s)
                    || spawnProtected(fx, fz) || inBase(fx, fy + dy, fz) && !baseDiggable(s, fx, fy + dy, fz, fy)) return false;
            // Water or lava resting on it would pour down on the agent.
            BlockState over = w.get(fx, fy + dy + 1, fz);
            if (over == null || !over.getFluidState().isEmpty()) return false;
            dev.agentnet.role.Gear.holdBestFor(this, s);
            dig(fx, fy + dy, fz);
            return true;
        }
        if (!feet.getFluidState().isEmpty() || dev.agentnet.pathing.Rules.canWalkThrough(under)) return false; // must stand on something to place against
        if (spawnProtected(fx, fz) || inBuilding(fx, fz) && !underBase(fy)) return false; // never a pillar inside the building; in front of it is fine
        int slot = -1;
        java.util.List<ItemStack> inv = inventory();
        for (int i = 0; i < inv.size(); i++) {
            String id = dev.agentnet.role.Gear.id(inv.get(i));
            if (id.equals("minecraft:cobblestone") || id.equals("minecraft:cobbled_deepslate") || id.equals("minecraft:dirt")
                    || id.equals("minecraft:netherrack") || id.equals("minecraft:stone")) { slot = i; break; }
        }
        if (slot < 0) return false;
        int hb = slot < 9 ? slot : 7;
        if (slot >= 9) moveToHotbar(slot, hb);
        selectHotbar(hb);
        lookAt(fx + 0.5, fy - 0.5, fz + 0.5);
        // Up this tick; the block goes in next tick, once the server has it out of the way (in the same tick the
        // server sees the click first, the block would be inside the agent, and it's refused).
        navBody.moveTo(fx + 0.5, fy + 1.0, fz + 0.5, Float.NaN);
        pillarPlace = new BlockPos(fx, fy - 1, fz);
        escapeStep = new PathFinder.Node(fx, fy + 1, fz);
        escapeMoves.clear();
        return true;
    }

    /** Ground that's there by nature (dirt, stone, sand, gravel, leaves...): never what someone built. */
    /**
     * A block it may dig to get out: natural ground anywhere; outside the base also ore, cobblestone and anything else
     * without contents (no chests, furnaces, signs...), but never obsidian or bedrock. Only natural blocks before: an
     * agent wrapped in ore or other agents' pillar cobblestone stayed there for good (most of the "stuck" agents).
     */
    /**
     * In the base: natural ground (not inside the building unless it is under its floor); from under the floor also the
     * floor and foundation blocks themselves (no chests, furnaces...). Agents wedged under the floor stayed there for
     * good. The builder puts a dug floor block back: a missing one marks the base unfinished.
     */
    private boolean baseDiggable(BlockState s, int x, int y, int z, int feetY) {
        if (natural(s) && !(inBuilding(x, z) && !underBase(feetY))) return true;
        var b = dev.agentnet.role.Bases.get(dev.agentnet.map.Servers.key(address), dimension());
        return underBase(feetY) && b != null && b.hub() != null && y < b.hub().getY() && !s.hasBlockEntity();
    }

    private boolean escapeDiggable(BlockState s, int x, int y, int z) {
        if (natural(s)) return true;
        if (inBase(x, y, z) || s.hasBlockEntity()) return false;
        var b = s.getBlock();
        return b != net.minecraft.world.level.block.Blocks.OBSIDIAN && b != net.minecraft.world.level.block.Blocks.CRYING_OBSIDIAN && b != net.minecraft.world.level.block.Blocks.BEDROCK
                && b != net.minecraft.world.level.block.Blocks.REINFORCED_DEEPSLATE && b != net.minecraft.world.level.block.Blocks.SPAWNER && b != net.minecraft.world.level.block.Blocks.TRIAL_SPAWNER;
    }

    private static boolean natural(BlockState s) {
        return s.is(net.minecraft.tags.BlockTags.DIRT) || s.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD) || s.is(net.minecraft.tags.BlockTags.BASE_STONE_NETHER)
                || s.is(net.minecraft.tags.BlockTags.SAND) || s.is(net.minecraft.tags.BlockTags.LEAVES) || s.is(net.minecraft.tags.BlockTags.SNOW)
                || s.getBlock() == net.minecraft.world.level.block.Blocks.GRAVEL || s.getBlock() == net.minecraft.world.level.block.Blocks.CLAY
                || s.getBlock() == net.minecraft.world.level.block.Blocks.SANDSTONE || s.getBlock() == net.minecraft.world.level.block.Blocks.MUD;
    }

    /**
     * Its feet are below the base's floor: it is under the building (a cave, a miner's tunnel, a hole), not in it. From
     * there the natural ground may be dug and pillared through, up through the floor too: the pillar puts a block back
     * under its feet at every step, so the hole it came up through is filled again. Two distributors and the builder
     * spent most of an hour stuck under the base before this.
     */
    private boolean underBase(int feetY) {
        var b = dev.agentnet.role.Bases.get(dev.agentnet.map.Servers.key(address), dimension());
        return b != null && b.hub() != null && feetY < b.hub().getY();
    }

    /** Inside the building itself (walls and the room, not the 4-deep entrance strip in front of the door). */
    private boolean inBuilding(int x, int z) {
        var b = dev.agentnet.role.Bases.get(dev.agentnet.map.Servers.key(address), dimension());
        if (b == null) return false;
        var o = b.origin();
        return x >= o.getX() - 1 && x <= o.getX() + 18 && z >= o.getZ() + 4 - 1 && z <= o.getZ() + 14 + 4;
    }

    /** Inside the crew's base (its walls, chests, furnaces): never dug through. */
    private boolean inBase(int x, int y, int z) {
        var b = dev.agentnet.role.Bases.get(dev.agentnet.map.Servers.key(address), dimension());
        if (b == null) return false;
        var o = b.origin();
        // 17 x 13 building plus the 4-deep entrance strip in front of it (BasePlanner.SX, SZ, FRONT)
        return x >= o.getX() - 1 && x <= o.getX() + 18 && z >= o.getZ() - 1 && z <= o.getZ() + 14 + 4 && y >= o.getY() - 2 && y <= o.getY() + 12;
    }

    private int underwaterTicks;
    private long underwaterSaidAt;

    /**
     * Its head under water for 3 s: straight up to the air, digging out a ceiling in the way. Agents don't feel their
     * air running out, and a path search from a flooded spot finds no moves at all: two drowned in one run.
     */
    private volatile long divingUntil;
    @Override public void dive(int seconds) { divingUntil = System.currentTimeMillis() + seconds * 1000L; }

    @Override public boolean swimToward(double x, double y, double z) {
        var w = openWorld();
        if (w == null || airVel != null) return false;
        Vec3 p = pos.position();
        double dx = x - p.x, dy = y - p.y, dz = z - p.z, len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 0.05) return true;
        double step = Math.min(0.3, len);
        double nx = p.x + dx / len * step, ny = p.y + dy / len * step, nz = p.z + dz / len * step;
        for (double hy : new double[]{0.1, 1.0, 1.7}) {
            BlockState s = w.get((int) Math.floor(nx), (int) Math.floor(ny + hy), (int) Math.floor(nz));
            if (s == null || !(s.isAir() || s.getFluidState().is(net.minecraft.tags.FluidTags.WATER) && s.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty())) return false;
        }
        if (navigator.busy()) navigator.stop();
        navBody.moveTo(nx, ny, nz, Float.NaN);
        return true;
    }

    /**
     * Water it can swim through: a waterlogged block (dripstone, a slab, stairs) holds water too but is solid. Taking it for
     * open water, two miners swam "up" into one again and again, the server putting them back 0.2 each time, and drowned.
     */
    private static boolean openWater(BlockState s) {
        if (!s.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) return false;
        try { return s.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty(); }
        catch (RuntimeException e) { return true; }
    }

    private int sideSwimTicks;
    private long noSideSwimUntil;

    private boolean tickUnderwater() {
        if (System.currentTimeMillis() < divingUntil) return false; // diving on purpose (a shipwreck)
        var w = openWorld();
        if (w == null) return false;
        Vec3 p = pos.position();
        int x = (int) Math.floor(p.x), z = (int) Math.floor(p.z), hy = (int) Math.floor(p.y + 1.62);
        BlockState head = w.get(x, hy, z);
        if (head == null || !head.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) { underwaterTicks = 0; sideSwimTicks = 0; return false; }
        if (++underwaterTicks < 20) return false; // a swim path dips under for a moment
        if (navigator.busy()) navigator.stop();
        long now = System.currentTimeMillis();
        if (now - underwaterSaidAt > 10_000) { underwaterSaidAt = now; log("§eunder water: swimming straight up for air"); }
        BlockState above = w.get(x, hy + 1, z);
        if (above == null) return true;
        if (openWater(above) || dev.agentnet.pathing.Rules.canWalkThrough(above)) {
            navBody.moveTo(p.x, p.y + 0.2, p.z, Float.NaN);
            return true;
        }
        // A ceiling over it: air at head height right beside it (a pocket to breathe in), or a column beside it that
        // goes up through water or air, before digging up through stone with the water following it in.
        // Two seconds of swimming sideways and still under water (the server refused the side it saw as open: _slime_
        // drowned trying the same side over and over): no side ways for 10 s, it digs straight up instead.
        if (sideSwimTicks > 40) { sideSwimTicks = 0; noSideSwimUntil = now + 10_000; AgentNet.LOG.info("[{}] the way out sideways gets it nowhere: digging straight up", name); }
        if (now >= noSideSwimUntil) for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            BlockState side = w.get(x + d[0], hy, z + d[1]), sideUp = w.get(x + d[0], hy + 1, z + d[1]);
            if (side == null || sideUp == null) continue;
            boolean breathe = side.isAir();
            boolean upward = (side.isAir() || openWater(side)) && (sideUp.isAir() || openWater(sideUp));
            if (!breathe && !upward) continue;
            BlockState sideFeet = w.get(x + d[0], (int) Math.floor(p.y), z + d[1]);
            if (sideFeet == null || !(sideFeet.isAir() || openWater(sideFeet))) continue;
            // At swimming speed (0.2 a tick at most), not the whole block in one tick: the server refused the jump
            // ("moved wrongly"), its position stayed a block off the server's, it saw air under it where the server had
            // water, "dropped" and drowned (_slime_, run 1118).
            double tx = x + d[0] + 0.5, tz = z + d[1] + 0.5, ddx = tx - p.x, ddz = tz - p.z, dist = Math.sqrt(ddx * ddx + ddz * ddz);
            double step = Math.min(0.2, dist);
            sideSwimTicks++;
            navBody.moveTo(dist > 1e-3 ? p.x + ddx / dist * step : p.x, p.y + (breathe ? 0 : 0.2), dist > 1e-3 ? p.z + ddz / dist * step : p.z, Float.NaN);
            return true;
        }
        if (!isDigging() && (above.getFluidState().isEmpty() || !openWater(above)) && above.getBlock() != net.minecraft.world.level.block.Blocks.BEDROCK) {
            dev.agentnet.role.Gear.holdBestFor(this, above);
            dig(x, hy + 1, z);
        }
        return true;
    }

    private void tickNavigation() {
        if (unsneakIn > 0 && --unsneakIn == 0) send(new ServerboundPlayerInputPacket(new net.minecraft.world.entity.player.Input(false, false, false, false, false, sneaking, false)));
        if (myVehicle >= 0) return; // the vehicle carries us; the server ignores our position meanwhile
        // An elytra trip: its steering this tick, then the glide physics as for any time in the air. Landed: the rest on foot.
        if (flight.active() && !flight.tick(this)) {
            PathFinder.Node g = flightGoal; flightGoal = null;
            if (g != null) navigate(g.x(), g.y(), g.z(), flightRadius);
        }
        if (tickAir()) return;
        if (flight.active()) return; // putting it on, taking off, taking it off: not walking meanwhile
        if (tickUnderwater()) return;
        // A pillar step a module asked for: the block goes under its feet a tick after it went up.
        if (escapeGoal == null && pillarPlace != null) {
            lookAt(pillarPlace.getX() + 0.5, pillarPlace.getY() + 1.0, pillarPlace.getZ() + 0.5);
            placeAgainst(pillarPlace.getX(), pillarPlace.getY(), pillarPlace.getZ(), Direction.UP);
            pillarPlace = null; escapeStep = null; escapeMoves.clear();
            return;
        }
        if (escapeGoal != null) { tickEscape(); return; }
        tickGravity();
        long now = System.currentTimeMillis();
        if (pendingGoal != null) {
            if (hasWorldHere()) {
                PathFinder.Node g = pendingGoal;
                pendingGoal = null;
                navigator.navigate(g.x(), g.y(), g.z(), pendingRadius);
            } else if (now - pendingSince > WAIT_FOR_WORLD_MS) {
                pendingGoal = null;
                boolean gotChunks = chunks.lastChunkAt() >= onlineSince;
                log(gotChunks && failedWhy != null && now - failedAt < FAILED_GOAL_MS
                        ? "§ccan't travel: " + failedWhy
                        : gotChunks ? "§ccan't travel: no world data where it stands (it was freed after a failed trip)"
                        : "§ccan't travel: the server sent no world data");
            }
        }
        navigator.tick();
        // Not travelling for a while: forget the raw chunks too.
        if (!keepWorld && airVel == null && !isNavigating() && !chunks.isEmpty() && now - chunks.lastChunkAt() > IDLE_WORLD_MS) chunks.drop();
        // It needs the world now (a fighter, a miner) but the chunk it stands in was dropped while it idled, and the
        // server never sends a chunk twice: reconnect for it. Without this it flies and falls with no ground under it.
        if (keepWorld && airVel == null && !isNavigating() && pendingGoal == null && onlineFor() > 8_000 && !hasWorldHere()
                && now - WORLD_ASKED.getOrDefault(name, 0L) > 30_000) {
            WORLD_ASKED.put(name, now);
            log("§7reconnecting: it has no world data where it stands");
            Vec3 here = pos.position();
            AgentNetClient.reconnect(this, new PathFinder.Node((int) Math.floor(here.x), (int) Math.floor(here.y), (int) Math.floor(here.z)), 3);
        }
    }

    /** What the navigator sees and moves. */
    private static final float WALK_PITCH = 25f;
    private volatile Vec3 faceTarget;
    private volatile long faceUntil;
    @Override public void faceWhileMoving(double x, double y, double z) { faceTarget = new Vec3(x, y, z); faceUntil = System.currentTimeMillis() + 1000; }

    private final class NavBody implements Navigator.Body {
        // For the walker: standing on a slab (feet on the top of a partial block in their own cell), it is at the path
        // node over it, the next whole block up. (Read as standing in the slab's cell, it planned from inside a block and
        // stood still.) Its real feet stay where they are: moveTo puts them down on the top again.
        @Override public Vec3 position() {
            Vec3 p = pos.position();
            double frac = p.y - Math.floor(p.y);
            if (frac < 1e-6) return p;
            var w = openWorld();
            if (w == null) return p;
            // Under any corner of its 0.6-wide box (on the edge of a slab it stands on the slab from the column beside it).
            for (double ox : new double[]{-0.29, 0.29}) for (double oz : new double[]{-0.29, 0.29}) {
                BlockState at = w.get((int) Math.floor(p.x + ox), (int) Math.floor(p.y), (int) Math.floor(p.z + oz));
                if (at != null && !airLike(at) && Math.abs(frac - topOf(at)) < 1e-3) return new Vec3(p.x, Math.floor(p.y) + 1, p.z);
            }
            return p;
        }
        @Override public ChunkStore chunks() { return chunks; }
        @Override public RegistryAccess registries() { return gameRegistries; }
        @Override public int minY() { return dimMinY; }
        @Override public int maxY() { return dimMinY + dimHeight; }

        @Override
        public void moveTo(double x, double y, double z, float yaw) {
            float yRot = Float.isNaN(yaw) ? pos.yRot() : yaw;
            float walkPitch = WALK_PITCH;
            Vec3 face = faceTarget;
            if (face != null && System.currentTimeMillis() < faceUntil) {
                double fdx = face.x - x, fdy = face.y - (y + 1.62), fdz = face.z - z;
                yRot = (float) Math.toDegrees(Math.atan2(-fdx, fdz));
                walkPitch = (float) -Math.toDegrees(Math.atan2(fdy, Math.sqrt(fdx * fdx + fdz * fdz)));
            }
            // Eyes a little down while walking: a level gaze down the path met the eyes of an enderman standing a dozen
            // blocks ahead, and that is all it takes for one to attack.
            // On the ground (a whole-number height from the path): feet down on the real top of what is under it, as vanilla
            // gravity would put them (a bottom slab 0.5 lower, farmland 1/16). Mid-jump heights are left as they are.
            if (Math.abs(y - Math.rint(y)) < 1e-6) y = groundTop(x, Math.rint(y), z);
            pos = new PositionMoveRotation(new Vec3(x, y, z), Vec3.ZERO, yRot, walkPitch);
            // onGround=true: no fall damage and no anti-fly kicks for agents that have no physics.
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Float.isFinite(yRot)) { badMove("move " + x + " " + y + " " + z + " " + yRot); return; }
            sendPosition(new ServerboundMovePlayerPacket.PosRot(x, y, z, yRot, walkPitch, true, false));
        }

        @Override
        public void tripEnded(boolean arrived, String why) {
            // Arrived: free everything (unless working in this area). Failed: keep the world for
            // now - the job usually retries or picks another goal right away, and dropping it
            // here forced a reconnect whose chunks were thrown away by the next failure, ending
            // in a misleading "server sent no world data". The idle timer frees it later.
            if (arrived && !keepWorld) chunks.drop();
            if (!arrived) {
                PathFinder.Node g = navigator.goal();
                failedGoal = g;
                failedAt = System.currentTimeMillis();
                failedWhy = why;
                // No way out from where it stands (a pit, a cave pocket): dig or pillar a step out, then try again.
                var tiny = java.util.regex.Pattern.compile("\\((\\d+) spots searched\\)").matcher(why == null ? "" : why);
                // A closed space (a hole, a cave pocket): the search ran out of places to go. Up to 300 spots is still
                // a pocket, not a long way round (that searches tens of thousands).
                // Only for a trip somewhere (over 8 blocks away): a block or two away the job digs its own way (miners tunnel).
                Vec3 me = pos.position();
                // (A goal 2 or more above counts as a trip too: that's a hole it has to climb out of, however close.)
                boolean far = g != null && (Math.abs(g.x() - me.x) + Math.abs(g.z() - me.z) > 8 || g.y() - me.y >= 2);
                boolean pocket = tiny.find() && Integer.parseInt(tiny.group(1)) <= 300;
                // Or the same trip failing again from about the same place (the bottom of a stair tunnel, a dead end that a
                // big search still found no way out of): dig or pillar toward the goal instead of failing there forever.
                long nowMs = System.currentTimeMillis();
                boolean again = lastFailAt != null && lastFailAt.distanceToSqr(me) < 4 * 4 && nowMs - lastFailMs < 90_000;
                lastFailAt = me;
                lastFailMs = nowMs;
                if (far && (pocket || again)) {
                    if (nowMs - escapeSaidAt > 10_000) { escapeSaidAt = nowMs; AgentNet.LOG.info("[{}] no way to {} {} {} from {} {} {} ({}): digging toward it", name, g.x(), g.y(), g.z(), (int) me.x, (int) me.y, (int) me.z, pocket ? "closed in" : "failed here twice"); }
                    startEscape(g, navigator.radius());
                }
            }
            if (task.startsWith("goto") || task.startsWith("come")) {
                log(arrived ? "arrived" : "§ccouldn't get there: " + why);
                task = "idle";
            }
        }

        @Override
        public int maxFall() { return role == Role.WARRIOR ? dev.agentnet.role.Clutch.hurtingFall(Agent.this) : dev.agentnet.role.Clutch.maxFall(Agent.this); }

        @Override
        public java.util.List<int[]> avoid() {
            java.util.List<int[]> out = new java.util.ArrayList<>(dev.agentnet.role.GroundDrops.avoidFor(name));
            long now = System.currentTimeMillis();
            holes.removeIf(h -> now - h[3] > 120_000);
            for (long[] h : holes) out.add(new int[]{(int) h[0], (int) h[1], (int) h[2]});
            // Where a mob killed one of us in the last 10 minutes: 6 blocks round it. Three of the crew died within five
            // minutes at the same zombies in a cave (run 1118, ~32 46 12), every route going through it.
            DEATH_SPOTS.removeIf(d -> now - d.at() > 600_000);
            String dim = String.valueOf(dimension());
            if (role != Role.WARRIOR) for (DeathSpot d : DEATH_SPOTS) if (d.dim().equals(dim)) out.add(new int[]{d.x(), d.y(), d.z(), 6});
            return out;
        } // others' loads on the ground

        @Override
        public void fall() { if (airVel == null) { airPeakY = pos.position().y; airVel = Vec3.ZERO; airTicks = 0; } }

        @Override
        public boolean hasPickaxe() {
            synchronized (inventory) { for (ItemStack s : inventory) if (s != null && !s.isEmpty() && dev.agentnet.role.Gear.id(s).endsWith("_pickaxe")) return true; }
            return false;
        }

        public double speed() { return sneaking ? 0.3 : feetInWater() ? 0.45 : 1; } // swimming: about a player's swim speed

        @Override
        public boolean openDoorAt(int x, int y, int z) {
            var w = openWorld();
            if (w == null) return false;
            for (int dy = 0; dy <= 1; dy++) {
                BlockState s = w.get(x, y + dy, z);
                if (!dev.agentnet.pathing.Rules.openable(s)) continue;
                var open = s.getBlock() instanceof net.minecraft.world.level.block.DoorBlock
                        ? net.minecraft.world.level.block.DoorBlock.OPEN : net.minecraft.world.level.block.FenceGateBlock.OPEN;
                if (s.getValue(open)) return false; // already open
                lookAt(x + 0.5, y + dy + 0.5, z + 0.5);
                useItemOn(x, y + dy, z, faceToward(new BlockPos(x, y + dy, z)));
                // Remember it as open right away (the server's block update follows).
                chunks.blockChanged(x, y + dy, z, s.setValue(open, true));
                AgentNet.LOG.info("[{}] opened {} at {} {} {}", name, s.getBlock(), x, y + dy, z);
                return true;
            }
            return false;
        }

        @Override
        public void needWorld() {
            Vec3 p = pos.position();
            PathFinder.Node g = navigator.goal();
            if (g != null) AgentNetClient.reconnect(Agent.this, g, 1);
        }
    }

    private void stopModule() {
        if (module != null && moduleStarted) {
            try { module.onStop(this); } catch (RuntimeException ignored) {}
        }
        moduleStarted = false;
    }

    /** Container 0 slot -> Inventory index, or -1 for crafting/armor/offhand slots. */
    private static int menuSlotToInventory(int slot) {
        if (slot >= 9 && slot <= 35) return slot;
        if (slot >= 36 && slot <= 44) return slot - 36;
        return -1;
    }

    /** Inventory index (hotbar 0-8, main 9-35) -> container 0 slot. */
    private static int inventoryToMenuSlot(int index) { return index < 9 ? index + 36 : index; }

    private void setInventorySlot(int index, ItemStack stack) {
        if (index < 0 || index >= 36) return;
        synchronized (inventory) { inventory[index] = stack == null ? ItemStack.EMPTY : stack.copy(); }
    }

    /** Container 0 slots 5-8 (head, chest, legs, feet) and 45 (offhand). */
    private void setMenuSlot0(int slot, ItemStack stack) {
        ItemStack s = stack == null ? ItemStack.EMPTY : stack.copy();
        if (slot >= 5 && slot <= 8) synchronized (inventory) { armor[slot - 5] = s; }
        else if (slot == 45) synchronized (inventory) { offhand = s; }
        else setInventorySlot(menuSlotToInventory(slot), stack);
    }

    // ---- containers (written on the netty thread, read on the main thread)
    private final ItemStack[] armor = {ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY};
    private ItemStack offhand = ItemStack.EMPTY;
    private volatile int inventoryStateId;
    private volatile int openContainerId = -1;
    private volatile int openStateId;
    private volatile String openMenu = "";
    /** The open container's slots (its own slots first, then 36 player slots). */
    private volatile List<ItemStack> openItems = List.of();

    @Override public List<ItemStack> armor() { synchronized (inventory) { return List.of(armor); } }
    @Override public ItemStack offhand() { synchronized (inventory) { return offhand; } }
    @Override public boolean containerOpen() { return openContainerId > 0 && !openItems.isEmpty(); }
    @Override public String containerType() { return openMenu; }

    @Override
    public List<ItemStack> containerSlots() {
        List<ItemStack> all = openItems;
        int own = all.size() - 36;
        return own <= 0 ? List.of() : List.copyOf(all.subList(0, own));
    }

    private void click(int containerId, int stateId, int slot, int button, net.minecraft.world.inventory.ContainerInput input) {
        // The server applies the click and corrects us with a full resync if our guess (none) differs.
        send(new net.minecraft.network.protocol.game.ServerboundContainerClickPacket(containerId, stateId, (short) slot,
                (byte) button, input, new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>(), net.minecraft.network.HashedStack.EMPTY));
    }

    @Override
    public void moveToHotbar(int index, int hotbarSlot) {
        if (index < 0 || index >= 36 || hotbarSlot < 0 || hotbarSlot > 8 || index == hotbarSlot) return;
        click(0, inventoryStateId, inventoryToMenuSlot(index), hotbarSlot, net.minecraft.world.inventory.ContainerInput.SWAP);
        synchronized (inventory) { ItemStack t = inventory[index]; inventory[index] = inventory[hotbarSlot]; inventory[hotbarSlot] = t; }
    }

    @Override
    public void dropStack(int index) {
        if (index < 0 || index >= 36) return;
        if (containerOpen()) { // the inventory menu isn't the active one: drop through the open container
            int own = openItems.size() - 36;
            int slot = own + (index < 9 ? 27 + index : index - 9);
            click(openContainerId, openStateId, slot, 1, net.minecraft.world.inventory.ContainerInput.THROW);
        } else {
            click(0, inventoryStateId, inventoryToMenuSlot(index), 1, net.minecraft.world.inventory.ContainerInput.THROW);
        }
        setInventorySlot(index, ItemStack.EMPTY);
    }

    @Override
    public void swapIntoContainer(int menuSlot, int hotbarSlot) {
        if (!containerOpen() || menuSlot < 0 || menuSlot >= openItems.size() || hotbarSlot < 0 || hotbarSlot > 8) return;
        click(openContainerId, openStateId, menuSlot, hotbarSlot, net.minecraft.world.inventory.ContainerInput.SWAP);
    }

    @Override
    public void quickMoveContainerSlot(int slot) {
        if (!containerOpen()) return;
        click(openContainerId, openStateId, slot, 0, net.minecraft.world.inventory.ContainerInput.QUICK_MOVE);
    }

    @Override
    public void quickMoveToContainer(int index) {
        if (!containerOpen() || index < 0 || index >= 36) return;
        int own = openItems.size() - 36;
        click(openContainerId, openStateId, own + (index < 9 ? 27 + index : index - 9), 0,
                net.minecraft.world.inventory.ContainerInput.QUICK_MOVE);
    }

    @Override
    public void clickContainer(int menuSlot, int button) {
        if (!containerOpen() || menuSlot < 0 || menuSlot >= openItems.size()) return;
        click(openContainerId, openStateId, menuSlot, button, net.minecraft.world.inventory.ContainerInput.PICKUP);
    }

    @Override
    public int containerMenuSlot(int index) {
        if (!containerOpen() || index < 0 || index >= 36) return -1;
        return openItems.size() - 36 + (index < 9 ? 27 + index : index - 9);
    }

    @Override
    public void closeContainer() {
        int id = openContainerId;
        if (id > 0) send(new net.minecraft.network.protocol.game.ServerboundContainerClosePacket(id));
        openContainerId = -1;
        openItems = List.of();
        openMenu = "";
    }

    // ---------------------------------------------------------------- lifecycle

    void start() {
        status = "connecting";
        Thread t = new Thread(this::connect, "AgentNet-connect-" + name);
        t.setDaemon(true);
        t.start();
    }

    private void connect() {
        try {
            ServerAddress addr = ServerAddress.parseString(address);
            Optional<InetSocketAddress> resolved = ServerNameResolver.DEFAULT.resolveAddress(addr)
                    .map(ResolvedServerAddress::asInetSocketAddress);
            if (resolved.isEmpty()) { fail("cannot resolve " + address); return; }
            InetSocketAddress inet = resolved.get();

            Connection conn = new Connection(PacketFlow.CLIENTBOUND);
            Connection.connect(inet, EventLoopGroupHolder.remote(mc.options.useNativeTransport()), conn)
                    .syncUninterruptibly();
            this.connection = conn;
            if (closed) { conn.disconnect(Component.literal("Agent removed")); return; }

            conn.initiateServerboundPlayConnection(inet.getHostName(), inet.getPort(),
                    proxy(ClientLoginPacketListener.class, new LoginHandler()));
            conn.send(new ServerboundHelloPacket(name, UUIDUtil.createOfflinePlayerUUID(name)));
            status = "logging in";
        } catch (Exception e) {
            fail(e.toString());
        }
    }

    /** Called every client tick from the main thread. */
    void tick() {
        Connection c = connection;
        if (closed) { stopModule(); return; }
        if (c == null) return;
        if (c.isConnected()) {
            c.tick();
            if (inGame) {
                flushMoves();
                tickNavigation();
                tickDigging();
                c.send(ServerboundClientTickEndPacket.INSTANCE);
                tickEnded();
                movedThisTick = false;
                tickModule();
                tickStuckWatch();
            }
        } else if (!c.isConnecting()) {
            c.handleDisconnection();
            closed = true;
            stopModule();
        }
    }

    void leave() {
        stopModule();
        closed = true;
        Connection c = connection;
        if (c != null && c.isConnected()) c.disconnect(Component.literal("Agent left"));
        status = "left";
    }

    private void fail(String why) {
        closed = true;
        status = "failed: " + why;
        AgentNetClient.notify("§c[" + name + "] " + why);
    }

    private void onDisconnect(DisconnectionDetails details) {
        if (!closed || !"left".equals(status)) {
            status = "disconnected";
            AgentNetClient.notify("§e[" + name + "] disconnected: " + details.reason().getString());
            String why = details.reason().getString();
            if (why.toLowerCase(java.util.Locale.ROOT).contains("invalid move")) synchronized (lastMoves) {
                AgentNet.LOG.warn("[{}] kicked for an invalid move; the last movement packets:\n  {}", name, String.join("\n  ", lastMoves));
            }
            // Also when it never got into the game: a reset while logging in (35 agents joining at once) left agents
            // off the server for good. Retries are capped, and bans/whitelist kicks are never retried.
            mc.execute(() -> AgentNetClient.autoReconnect(this, why));
        }
        closed = true;
        inGame = false;
    }

    /** The last movement packets sent, for "Invalid move player packet" kicks: what did the server reject? */
    private final java.util.ArrayDeque<String> lastMoves = new java.util.ArrayDeque<>();
    private long sentTicks;

    private void send(Packet<?> p) {
        if (p instanceof ServerboundMovePlayerPacket mp) {
            synchronized (lastMoves) {
                lastMoves.add(String.format(java.util.Locale.ROOT, "t%d %s pos=%s %.3f %.3f %.3f rot=%s %.1f %.1f ground=%s air=%s nav=%s",
                        sentTicks, mp.getClass().getSimpleName(), mp.hasPosition(), mp.getX(0), mp.getY(0), mp.getZ(0), mp.hasRotation(), mp.getYRot(0), mp.getXRot(0),
                        mp.isOnGround(), airVel != null, navigator.busy()));
                while (lastMoves.size() > 12) lastMoves.poll();
            }
        }
        if (p instanceof ServerboundClientTickEndPacket) sentTicks++;
        Connection c = connection;
        if (c != null && c.isConnected()) c.send(p);
    }

    // ---------------------------------------------------------------- handlers

    /** Packets every phase understands (config + game). Returns true if handled. */
    private boolean handleCommon(Object packet) {
        if (packet instanceof ClientboundKeepAlivePacket p) { send(new ServerboundKeepAlivePacket(p.getId())); return true; }
        if (packet instanceof ClientboundPingPacket p) { send(new ServerboundPongPacket(p.getId())); return true; }
        if (packet instanceof ClientboundDisconnectPacket p) { connection.disconnect(p.reason()); return true; }
        if (packet instanceof ClientboundCookieRequestPacket p) { send(new ServerboundCookieResponsePacket(p.key(), null)); return true; }
        if (packet instanceof ClientboundResourcePackPushPacket p) {
            // Pretend the pack loaded so servers with a required pack don't kick the agent.
            send(new ServerboundResourcePackPacket(p.id(), ServerboundResourcePackPacket.Action.ACCEPTED));
            send(new ServerboundResourcePackPacket(p.id(), ServerboundResourcePackPacket.Action.DOWNLOADED));
            send(new ServerboundResourcePackPacket(p.id(), ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED));
            return true;
        }
        return false;
    }

    private final class LoginHandler implements Handler {
        @Override public void handle(Object packet) {
            if (packet instanceof ClientboundLoginCompressionPacket p) {
                connection.setupCompression(p.getCompressionThreshold(), false);
            } else if (packet instanceof ClientboundHelloPacket) {
                connection.disconnect(Component.literal("Server is in online mode; agents need an offline-mode (cracked) server"));
            } else if (packet instanceof ClientboundCustomQueryPacket p) {
                send(new ServerboundCustomQueryAnswerPacket(p.transactionId(), null));
            } else if (packet instanceof ClientboundLoginDisconnectPacket p) {
                connection.disconnect(p.reason());
            } else if (packet instanceof ClientboundCookieRequestPacket p) {
                send(new ServerboundCookieResponsePacket(p.key(), null));
            } else if (packet instanceof ClientboundLoginFinishedPacket) {
                enterConfiguration(true);
            }
        }
    }

    private void enterConfiguration(boolean fromLogin) {
        registryCollector = new RegistryDataCollector();
        inGame = false;
        connection.setupInboundProtocol(ConfigurationProtocols.CLIENTBOUND,
                proxy(ClientConfigurationPacketListener.class, new ConfigHandler()));
        send(fromLogin ? ServerboundLoginAcknowledgedPacket.INSTANCE : ServerboundConfigurationAcknowledgedPacket.INSTANCE);
        connection.setupOutboundProtocol(ConfigurationProtocols.SERVERBOUND);
        send(new ServerboundCustomPayloadPacket(new BrandPayload("vanilla")));
        send(new ServerboundClientInformationPacket(info()));
        status = "configuring";
    }

    private final class ConfigHandler implements Handler {
        @Override public void handle(Object packet) {
            if (handleCommon(packet)) return;
            // Registry work touches shared game state, so do it on the main thread like vanilla.
            mc.execute(() -> handleOnMain(packet));
        }

        private void handleOnMain(Object packet) {
            if (closed) return;
            if (packet instanceof ClientboundSelectKnownPacks p) {
                if (knownPacks == null) knownPacks = new KnownPacksManager();
                send(new ServerboundSelectKnownPacks(knownPacks.trySelectingPacks(p.knownPacks())));
            } else if (packet instanceof ClientboundRegistryDataPacket p) {
                registryCollector.appendContents(p.registry(), p.entries());
            } else if (packet instanceof ClientboundUpdateTagsPacket p) {
                registryCollector.appendTags(p.tags());
            } else if (packet instanceof ClientboundCodeOfConductPacket) {
                send(ServerboundAcceptCodeOfConductPacket.INSTANCE);
            } else if (packet instanceof ClientboundFinishConfigurationPacket) {
                finishConfiguration();
            }
        }
    }

    private void finishConfiguration() {
        RegistryAccess.Frozen registries = pickRegistries();
        gameRegistries = registries;
        onlineSince = System.currentTimeMillis();
        connection.setupInboundProtocol(
                GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registries)),
                proxy(ClientGamePacketListener.class, new GameHandler()));
        send(ServerboundFinishConfigurationPacket.INSTANCE);
        connection.setupOutboundProtocol(GameProtocols.SERVERBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(registries), new GameProtocols.Context() {
                    @Override public boolean hasInfiniteMaterials() { return false; }
                    @Override public boolean canUseCommandBlocks() { return false; }
                }));
        inGame = true;
        everInGame = true;
        sentLoaded = false;
        status = "online";
        AgentNetClient.notify("§a[" + name + "] joined " + address);
        AgentNetClient.crewOnline(this);
    }

    /**
     * If you're on the same server, reuse your client's registries (no extra work, no
     * changes to shared game state). Otherwise build them from what the server sent.
     */
    private RegistryAccess.Frozen pickRegistries() {
        ClientPacketListener main = mc.getConnection();
        if (main != null && dev.agentnet.map.Servers.sameAsYours(address)) {
            return main.registryAccess();
        }
        RegistryAccess.Frozen base = ClientRegistryLayer.createRegistryAccess().compositeAccess();
        if (knownPacks == null) knownPacks = new KnownPacksManager();
        try (var rm = knownPacks.createResourceManager()) {
            return registryCollector.collectGameRegistries(rm, base, false);
        }
    }

    static boolean sameServer(String a, String b) {
        ServerAddress x = ServerAddress.parseString(a), y = ServerAddress.parseString(b);
        return x.getHost().equalsIgnoreCase(y.getHost()) && x.getPort() == y.getPort();
    }

    private final class GameHandler implements Handler {
        @Override public void handle(Object packet) {
            if (handleCommon(packet)) return;
            if (packet instanceof ClientboundBundlePacket b) {
                for (Packet<?> sub : b.subPackets()) handle(sub);
            } else if (packet instanceof ClientboundPlayerPositionPacket p) {
                Vec3 was = pos.position();
                pos = PositionMoveRotation.calculateAbsolute(pos, p.change(), p.relatives());
                Vec3 v = pos.position();
                long nowTp = System.currentTimeMillis();
                if (sentLoaded && nowTp - tpSaidAt > 20_000) {
                    tpSaidAt = nowTp;
                    String lastSent; synchronized (lastMoves) { lastSent = lastMoves.peekLast(); }
                    AgentNet.LOG.info("[{}] the server put it back: from {} {} {} to {} {} {} ({} blocks); last sent: {}", name,
                            String.format("%.2f", was.x), String.format("%.2f", was.y), String.format("%.2f", was.z),
                            String.format("%.2f", v.x), String.format("%.2f", v.y), String.format("%.2f", v.z), String.format("%.2f", was.distanceTo(v)), lastSent);
                }
                synchronized (this) { moveQueue.clear(); afterMove.clear(); movedThisTick = false; lastMoveNs = 0; } // moves from before the correction are stale
                send(new ServerboundAcceptTeleportationPacket(p.id(), v.x, v.y, v.z, pos.yRot(), pos.xRot()));
                // onGround=true so the server's anti-fly check never kicks a hovering agent.
                if (Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z) && Float.isFinite(pos.yRot()) && Float.isFinite(pos.xRot()))
                    sendPosition(new ServerboundMovePlayerPacket.PosRot(v.x, v.y, v.z, pos.yRot(), pos.xRot(), true, false));
                else badMove("teleport ack " + v + " " + pos.yRot() + " " + pos.xRot());
                if (!sentLoaded) { send(new ServerboundPlayerLoadedPacket()); sentLoaded = true; }
                else if (navigator.busy()) {
                    // Put back again and again for the same step (a block the server has and it doesn't see, a corner it
                    // clips): the 3rd time in 15 s it drops the trip and avoids the spot. Re-planning the same step a dozen
                    // times ended in an "invalid move" kick.
                    corrections.removeIf(t -> nowTp - t > 15_000);
                    corrections.add(nowTp);
                    if (corrections.size() >= 3) {
                        corrections.clear();
                        final Vec3 bad = was;
                        mc.execute(() -> {
                            holes.add(new long[]{(long) Math.floor(bad.x), (long) Math.floor(bad.y + 0.01), (long) Math.floor(bad.z), System.currentTimeMillis()});
                            navigator.stop();
                            AgentNet.LOG.info("[{}] the server keeps putting it back near {}: giving up this way", name, bad);
                        });
                    } else mc.execute(navigator::onTeleported);
                }
                // Put back again and again whatever it is doing (digging out, pillaring: no trip running): its picture of the
                // blocks around it no longer matches the server's. Retrying every tick ended in an "invalid move" kick; leaving
                // and coming straight back loads the blocks afresh. At most once a minute.
                if (sentLoaded) {
                    putBacks.removeIf(t -> nowTp - t > 20_000);
                    putBacks.add(nowTp);
                    if (putBacks.size() >= 8 && nowTp - PUT_BACK_REJOIN.getOrDefault(name, 0L) > 180_000) {
                        putBacks.clear();
                        PUT_BACK_REJOIN.put(name, nowTp);
                        mc.execute(() -> { log("§eput back by the server again and again: leaving and coming straight back to see the blocks afresh"); AgentNetClient.leaveClutch(Agent.this); });
                    }
                }
                // Moved by the server: a dig-out in progress was about the old spot.
                mc.execute(() -> { escapeGoal = null; escapeStep = null; escapeMoves.clear(); });
            } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundSetTimePacket p) {
                for (var e : p.clockUpdates().entrySet()) {
                    if (!e.getKey().is(net.minecraft.world.clock.WorldClocks.OVERWORLD)) continue;
                    dayTicks = e.getValue().totalTicks();
                    dayRate = e.getValue().rate();
                    dayAt = System.currentTimeMillis();
                }
            } else if (packet instanceof ClientboundLevelChunkWithLightPacket p) {
                if (dimHeight > 0) chunks.putChunk(p.x(), p.z(), p.chunkData(), dimHeight / 16);
                dev.agentnet.map.MapFeed.offer(dimension, p.x(), p.z(), p.chunkData());
                dev.agentnet.map.Explored.add(server(), dimension, p.x(), p.z());
            } else if (packet instanceof ClientboundForgetLevelChunkPacket p) {
                chunks.forgetChunk(p.pos().x(), p.pos().z());
            } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket p) {
                // Someone breaking a block: in the crew's base, a player who isn't one of ours goes on the hitlist.
                int breaker = p.getId();
                BlockPos at = p.getPos();
                Minecraft.getInstance().execute(() -> {
                    if (!inCrewBase(at.getX(), at.getY(), at.getZ())) return;
                    Tracked t = tracked.get(breaker);
                    if (t != null && t.type == EntityTypes.PLAYER) intruder(PLAYER_NAMES.get(t.uuid), "broke a block in the base");
                });
            } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundBlockEventPacket p) {
                // A chest lid opening in the base (the packet says which chest, not who): the nearest outsider by it.
                if ((p.getBlock() instanceof net.minecraft.world.level.block.ChestBlock || p.getBlock() instanceof net.minecraft.world.level.block.BarrelBlock)
                        && p.getB0() == 1 && p.getB1() > 0) {
                    BlockPos at = p.getPos();
                    Minecraft.getInstance().execute(() -> {
                        if (!inCrewBase(at.getX(), at.getY(), at.getZ())) return;
                        String best = null;
                        double bestD = 6 * 6;
                        for (Tracked t : tracked.values()) {
                            if (t.type != EntityTypes.PLAYER || t.pos == null) continue;
                            String who = PLAYER_NAMES.get(t.uuid);
                            if (who == null || isFriendly(who)) continue;
                            double d = t.pos.distanceToSqr(Vec3.atCenterOf(at));
                            if (d < bestD) { bestD = d; best = who; }
                        }
                        if (best != null) intruder(best, "opened a chest in the base");
                    });
                }
            } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket p) {
                ackedUpTo(p.sequence());
            } else if (packet instanceof ClientboundBlockUpdatePacket p) {
                serverBlock(p.getPos());
                chunks.blockChanged(p.getPos().getX(), p.getPos().getY(), p.getPos().getZ(), p.getBlockState());
            } else if (packet instanceof ClientboundSectionBlocksUpdatePacket p) {
                p.runUpdates((bp, st) -> { serverBlock(bp); chunks.blockChanged(bp.getX(), bp.getY(), bp.getZ(), st); });
            } else if (packet instanceof ClientboundRespawnPacket p) {
                if (died) { died = false; mc.execute(() -> afterDeath()); }
                groundItems.clear();
                sentLoaded = false;
                damageThisLife.clear();
                lastHealth = Float.NaN;
                bobberId = -1;
                bobberBiting = false;
                setDimension(p.commonPlayerSpawnInfo());
            } else if (packet instanceof ClientboundSetDefaultSpawnPositionPacket p) {
                worldSpawn = p.respawnData().pos();
                spawnDimension = p.respawnData().dimension().identifier().toString();
            } else if (packet instanceof ClientboundEntityEventPacket p) {
                // Events 24-28 tell a player its own op level (0-4).
                int ev = p.getEventId();
                if (ev >= 24 && ev <= 28 && eventEntity(p) == myEntityId) opLevel = ev - 24;
                // 35 = a totem of undying just saved it.
                if (ev == 35 && eventEntity(p) == myEntityId) AgentNet.LOG.info("[{}] totem popped (#{})", name, ++totemPops);
            } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundGameEventPacket p) {
                if (p.getEvent() == net.minecraft.network.protocol.game.ClientboundGameEventPacket.START_RAINING) raining = true;
                else if (p.getEvent() == net.minecraft.network.protocol.game.ClientboundGameEventPacket.STOP_RAINING) raining = false;
            } else if (packet instanceof ClientboundLoginPacket p) {
                myEntityId = p.playerId();
                setDimension(p.commonPlayerSpawnInfo());
            } else if (packet instanceof ClientboundContainerSetContentPacket p) {
                if (p.containerId() == 0) {
                    inventoryStateId = p.stateId();
                    var items = p.items();
                    for (int s = 0; s < items.size(); s++) setMenuSlot0(s, items.get(s));
                } else if (p.containerId() == openContainerId) {
                    openStateId = p.stateId();
                    List<ItemStack> copy = new ArrayList<>(p.items().size());
                    for (ItemStack s : p.items()) copy.add(s.copy());
                    openItems = copy;
                    // The player part of an open container mirrors the inventory.
                    int own = copy.size() - 36;
                    for (int i = 0; own > 0 && i < 36; i++) setInventorySlot(i < 27 ? i + 9 : i - 27, copy.get(own + i));
                }
            } else if (packet instanceof ClientboundContainerSetSlotPacket p) {
                if (p.getContainerId() == 0) { inventoryStateId = p.getStateId(); setMenuSlot0(p.getSlot(), p.getItem()); }
                else if (p.getContainerId() == openContainerId && p.getSlot() >= 0 && p.getSlot() < openItems.size()) {
                    openStateId = p.getStateId();
                    List<ItemStack> copy = new ArrayList<>(openItems);
                    copy.set(p.getSlot(), p.getItem().copy());
                    openItems = copy;
                    int own = copy.size() - 36, i = p.getSlot() - own;
                    if (own > 0 && i >= 0) setInventorySlot(i < 27 ? i + 9 : i - 27, p.getItem());
                }
            } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket p) {
                merchantOffers = List.copyOf(p.getOffers());
            } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundOpenScreenPacket p) {
                merchantOffers = List.of();
                openContainerId = p.getContainerId();
                openItems = List.of();
                var key = net.minecraft.core.registries.BuiltInRegistries.MENU.getKey(p.getType());
                openMenu = key == null ? "" : key.toString();
            } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundContainerClosePacket p) {
                if (p.getContainerId() == openContainerId) { openContainerId = -1; openItems = List.of(); openMenu = ""; }
            } else if (packet instanceof ClientboundSetPlayerInventoryPacket p) {
                setInventorySlot(p.slot(), p.contents());
            } else if (packet instanceof ClientboundPlayerInfoUpdatePacket p) {
                if (p.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) {
                    for (var e : p.entries()) {
                        if (e.profile() != null) PLAYER_NAMES.put(e.profileId(), e.profile().name());
                    }
                }
            } else if (packet instanceof ClientboundAddEntityPacket p) {
                if (p.getType() == EntityTypes.PLAYER) playerEntities.put(p.getId(), p.getUUID());
                trackEntity(p);
                // A bobber's spawn data is its owner's entity id: ours if we cast it.
                if (p.getType() == EntityTypes.FISHING_BOBBER && p.getData() == myEntityId) {
                    bobberId = p.getId();
                    bobberBiting = false;
                }
                if (p.getType() == EntityTypes.ITEM) {
                    // Guess where a thrown item lands: it slides ~8 ticks' worth of its starting speed.
                    Vec3 m = p.getMovement();
                    groundItems.put(p.getId(), new Vec3(p.getX() + m.x * 8, p.getY(), p.getZ() + m.z * 8));
                }
            } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket p) {
                if (groundItems.containsKey(p.id())) groundItems.put(p.id(), p.position().endPosition());
                Tracked t = tracked.get(p.id());
                if (t != null) { t.codec.setBase(p.position().endPosition()); t.pos = p.position().endPosition(); }
            } else if (packet instanceof ClientboundMoveEntityPacket p) {
                Tracked t = tracked.get(moveEntityId(p));
                if (t != null && p.hasPosition()) {
                    Vec3 end = p.getPositionDelta().decode(t.codec).endPosition();
                    t.codec.setBase(end);
                    t.pos = end;
                }
            } else if (packet instanceof ClientboundTeleportEntityPacket p) {
                Tracked t = tracked.get(p.id());
                if (t != null && p.relatives().isEmpty()) { t.pos = p.change().position(); t.codec.setBase(t.pos); }
            } else if (packet instanceof ClientboundRemoveEntitiesPacket p) {
                p.entityIds().forEach(id -> {
                    groundItems.remove(id);
                    tracked.remove(id);
                    playerEntities.remove(id);
                    if (id == bobberId) { bobberId = -1; bobberBiting = false; }
                });
            } else if (packet instanceof ClientboundSetEquipmentPacket p) {
                Tracked t = tracked.get(p.getEntity());
                if (t != null) {
                    for (var pair : p.getSlots()) {
                        var slot = pair.getFirst();
                        ItemStack st = pair.getSecond();
                        if (slot == net.minecraft.world.entity.EquipmentSlot.MAINHAND) t.mainHand = st;
                        else if (slot == net.minecraft.world.entity.EquipmentSlot.OFFHAND) t.offHand = st;
                        if (slot == net.minecraft.world.entity.EquipmentSlot.HEAD) t.bannerHead = !st.isEmpty() && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath().endsWith("_banner");
                        else if (slot.getType() == net.minecraft.world.entity.EquipmentSlot.Type.HUMANOID_ARMOR) {
                            int bit = 1 << slot.getIndex();
                            t.armorPieces = st.isEmpty() ? t.armorPieces & ~bit : t.armorPieces | bit;
                        }
                    }
                }
            } else if (packet instanceof ClientboundSetPassengersPacket p) {
                boolean me = false;
                for (int id : p.getPassengers()) if (id == myEntityId) me = true;
                if (me && myVehicle != p.getVehicle()) { int veh = p.getVehicle(); mc.execute(() -> mounted(veh)); }
                else if (!me && myVehicle == p.getVehicle()) { myVehicle = -1; AgentNet.LOG.info("[{}] out of the vehicle", name); }
                for (Tracked t : tracked.values()) if (t.vehicle == p.getVehicle()) t.vehicle = -1;
                for (int id : p.getPassengers()) { Tracked t = tracked.get(id); if (t != null) t.vehicle = p.getVehicle(); }
            } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket p) {
                if (p.id() == myEntityId) { Vec3 v = p.movement(); mc.execute(() -> knock(v)); }
            } else if (packet instanceof ClientboundExplodePacket p) {
                p.playerKnockback().ifPresent(v -> mc.execute(() -> launch(v)));
            } else if (packet instanceof ClientboundSetEntityDataPacket p) {
                // Its own flags too: whether the server has it using an item (its shield up) right now.
                if (p.id() == myEntityId) for (var v : p.packedItems()) if (v.id() == LIVING_FLAGS_ID && v.value() instanceof Byte b) selfFlags = b;
                Tracked tr = tracked.get(p.id());
                if (tr != null) {
                    for (var v : p.packedItems()) {
                        if (v.id() == LIVING_FLAGS_ID && v.value() instanceof Byte b) tr.livingFlags = b;
                        else if (v.id() == HEALTH_ID && v.value() instanceof Float hf) tr.health = hf;
                        else if (tr.type == EntityTypes.CREEPER && v.id() == SWELL_ID && v.value() instanceof Integer i) tr.swell = i;
                        else if (tr.type == EntityTypes.ENDERMAN && v.id() == CREEPY_ID && v.value() instanceof Boolean b2) tr.angry = b2;
                        // Lit up (charging fireballs) passed on the way a creeper's swelling is: Seen.swelling().
                        else if (tr.type == EntityTypes.BLAZE && v.id() == BLAZE_FLAGS_ID && v.value() instanceof Byte b3) tr.swell = (b3 & 1) != 0 ? 1 : 0;
                    }
                }
                if (p.id() == bobberId && BITING_DATA_ID >= 0) {
                    for (var v : p.packedItems()) {
                        if (v.id() == BITING_DATA_ID && v.value() instanceof Boolean b) bobberBiting = b;
                    }
                }
            } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket p) {
                if (p.replace()) recipeBook.clear();
                for (var e : p.entries()) recipeBook.put(e.contents().id(), e.contents());
            } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundRecipeBookRemovePacket p) {
                p.recipes().forEach(recipeBook::remove);
            } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket p) {
                stonecutter = p.stonecutterRecipes();
            } else if (packet instanceof ClientboundSetHeldSlotPacket p) {
                selectedSlot = p.slot();
            } else if (packet instanceof ClientboundUpdateAttributesPacket p) {
                if (p.getEntityId() == myEntityId) updateMaxHealth(p);
            } else if (packet instanceof ClientboundDamageEventPacket p) {
                if (p.entityId() == myEntityId) {
                    // sourceCauseId is the responsible entity: the shooter for arrows, etc.
                    UUID uuid = playerEntities.get(p.sourceCauseId());
                    lastAttacker = uuid == null ? null : PLAYER_NAMES.get(uuid);
                    lastAttackAt = System.currentTimeMillis();
                    if (lastAttacker != null && !AgentNetClient.AGENTS.containsKey(lastAttacker.toLowerCase())) {
                        ATTACKERS.put(lastAttacker.toLowerCase(), lastAttackAt); // a threat to the whole crew for a while, friend or not
                    }
                } else if (p.sourceCauseId() == myEntityId || p.sourceDirectId() == myEntityId) {
                    lastHitEntity = p.entityId();
                    lastHitAt = System.currentTimeMillis();
                    AgentNet.LOG.info("[{}] hit entity #{}", name, p.entityId());
                }
            } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket p) {
                if (p.getEntityId() == myEntityId) p.getEffect().unwrapKey().ifPresent(k -> effects.put(k.identifier().toString(), System.currentTimeMillis() + (p.getEffectDurationTicks() < 0 ? 3_600_000L : p.getEffectDurationTicks() * 50L)));
            } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket p) {
                if (p.entityId() == myEntityId) p.effect().unwrapKey().ifPresent(k -> effects.remove(k.identifier().toString()));
            } else if (packet instanceof ClientboundSetHealthPacket p) {
                food = p.getFood();
                onHealth(p.getHealth());
                if (p.getHealth() <= 0f) { noteDeathSpot(); died = true; respawn(); }
            } else if (packet instanceof ClientboundPlayerCombatKillPacket) {
                noteDeathSpot();
                died = true;
                respawn();
            } else if (packet instanceof ClientboundChunkBatchFinishedPacket) {
                send(new ServerboundChunkBatchReceivedPacket(5f));
            } else if (packet instanceof ClientboundStartConfigurationPacket) {
                mc.execute(() -> { if (!closed) enterConfiguration(false); });
            }
        }
    }

    /**
     * The server reports who hit us (damage event) and, a moment later, our new health.
     * Pair the two, add the damage to that player's total for this life, and put them
     * on the hitlist once the total passes 1/3 of max health.
     */
    private final Map<String, Long> attackSaid = new java.util.concurrent.ConcurrentHashMap<>();

    private void onHealth(float health) {
        float before = lastHealth;
        lastHealth = health;

        // Under half health: hand over to you (see Takeover). Fires once per drop.
        boolean low = health > 0 && health < maxHealth / 2;
        if (low && !lowHealth && System.currentTimeMillis() - lowHealthAt > 60_000) {
            lowHealthAt = System.currentTimeMillis();
            lowHealth = true;
            mc.execute(() -> Takeover.onLowHealth(this));
        } else if (!low) {
            lowHealth = false;
        }

        if (Float.isNaN(before) || health >= before) return;
        String attacker = lastAttacker;
        if (attacker == null || System.currentTimeMillis() - lastAttackAt > 2000) return;

        float total = damageThisLife.merge(attacker, before - health, Float::sum);
        // Attacked by a player: always worth saying (once per 30 s per attacker), friends included.
        Long said = attackSaid.get(attacker);
        if (said == null || System.currentTimeMillis() - said > 30_000) {
            attackSaid.put(attacker, System.currentTimeMillis());
            AgentNetClient.notify("§4[" + name + "] attacked by " + attacker + " (" + String.format("%.1f", health) + "/" + String.format("%.0f", maxHealth) + " HP left)");
        }
        if (total * 3 < maxHealth || isFriendly(attacker)) return;
        String note = AgentFiles.now() + ", hit " + name + " for " + String.format("%.1f", total)
                + "/" + String.format("%.0f", maxHealth) + " HP";
        if (AgentFiles.HITLIST.add(attacker, note)) {
            AgentNetClient.notify("§4[" + name + "] " + attacker + " added to the hitlist (" + note + ")");
        }
    }

    /** This player hit one of the agents in the last {@link #ATTACKER_MS}. */
    static boolean recentlyHit(String player) {
        Long hit = player == null ? null : ATTACKERS.get(player.toLowerCase());
        return hit != null && System.currentTimeMillis() - hit < ATTACKER_MS;
    }

    private static boolean isFriendly(String player) {
        return player.equalsIgnoreCase(Takeover.yourName())
                || player.equalsIgnoreCase(Takeover.controlledName())
                || AgentNetClient.AGENTS.containsKey(player.toLowerCase())
                || AgentFiles.WHITELIST.contains(player);
    }

    private void updateMaxHealth(ClientboundUpdateAttributesPacket p) {
        for (var snap : p.getValues()) {
            if (!snap.attribute().equals(Attributes.MAX_HEALTH)) continue;
            double add = snap.base(), base = 0, total = 0;
            for (AttributeModifier m : snap.modifiers()) {
                if (m.operation() == AttributeModifier.Operation.ADD_VALUE) add += m.amount();
                else if (m.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_BASE) base += m.amount();
            }
            double value = add * (1 + base);
            for (AttributeModifier m : snap.modifiers()) {
                if (m.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL) value *= 1 + m.amount();
            }
            if (value > 0) maxHealth = (float) value;
        }
    }

    private void setDimension(CommonPlayerSpawnInfo info) {
        tracked.clear();
        var type = info.dimensionType().value();
        dimMinY = type.minY();
        dimHeight = type.height();
        dimensionKey = info.dimension();
        dimension = info.dimension().identifier().toString();
        chunks.drop(); // a new world: the old chunks are meaningless
        if (navigator.busy()) mc.execute(() -> navigator.stop());
    }

    private volatile boolean died;

    /**
     * Back after dying: everything it carried is gone. Its own picture of its inventory is wiped (the server sends the
     * real one), and its job starts over from the beginning with the same task, so it checks again what it has. It
     * kept "knowing" it had a pickaxe and dug stone with its fist.
     */
    private long diedAt = -1_000_000;

    private void afterDeath() {
        synchronized (inventory) {
            java.util.Arrays.fill(inventory, ItemStack.EMPTY);
            java.util.Arrays.fill(armor, ItemStack.EMPTY);
            offhand = ItemStack.EMPTY;
        }
        if (navigator.busy()) navigator.stop();
        escapeGoal = null; escapeStep = null; escapeMoves.clear();
        airVel = null;
        if (role != null) {
            stopModule();
            module = role.createModule();
            moduleStarted = false;
            diedAt = System.currentTimeMillis();
            String t = task;
            log("§edied: its inventory is gone, starting its job over (" + role.id() + ", " + t + ")");
            if (t != null && !t.equals("idle")) mc.execute(() -> { if (!closed) setTask(t); });
        }
    }

    /** Spots where one of the crew just died (any agent's routes go round them for 10 minutes). */
    private record DeathSpot(String dim, int x, int y, int z, long at) {}
    private static final java.util.List<DeathSpot> DEATH_SPOTS = new java.util.concurrent.CopyOnWriteArrayList<>();

    private void noteDeathSpot() {
        if (died) return; // the health packet and the kill packet both come: once
        Vec3 p = pos.position();
        // Not a fall or lava death in the middle of nowhere: only where something was after it.
        if (System.currentTimeMillis() - threatSeenAt > 15_000) return;
        DEATH_SPOTS.add(new DeathSpot(String.valueOf(dimension()), (int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z), System.currentTimeMillis()));
        AgentNet.LOG.info("[{}] died where mobs were at {} {} {}: the crew's routes go round it for 10 minutes", name, (int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z));
    }

    private void respawn() {
        send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
    }

    // ---------------------------------------------------------------- listener plumbing

    private interface Handler { void handle(Object packet); }

    /**
     * Vanilla listener interfaces have ~150 methods. A dynamic proxy lets each phase
     * implement only the few packets it cares about; everything else is ignored.
     */
    @SuppressWarnings("unchecked")
    private <T extends PacketListener> T proxy(Class<T> type, Handler handler) {
        InvocationHandler ih = (self, method, args) -> {
            String n = method.getName();
            switch (n) {
                case "onDisconnect": onDisconnect((DisconnectionDetails) args[0]); return null;
                case "isAcceptingMessages": { Connection c = connection; return c != null && c.isConnected(); }
                case "toString": return "AgentNet[" + name + "]";
                case "hashCode": return System.identityHashCode(self);
                case "equals": return self == args[0];
                default: break;
            }
            if (n.startsWith("handle") && args != null && args.length == 1 && args[0] instanceof Packet<?>) {
                try {
                    handler.handle(args[0]);
                } catch (Exception e) {
                    AgentNet.LOG.warn("[{}] error handling {}", name, args[0].getClass().getSimpleName(), e);
                }
                return null;
            }
            if (method.isDefault()) return InvocationHandler.invokeDefault(self, method, args);
            return defaultValue(method);
        };
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, ih);
    }

    private static Object defaultValue(Method m) {
        Class<?> r = m.getReturnType();
        if (r == boolean.class) return false;
        if (r == int.class || r == short.class || r == byte.class) return 0;
        if (r == long.class) return 0L;
        if (r == float.class) return 0f;
        if (r == double.class) return 0d;
        if (r == char.class) return '\0';
        return null;
    }

    String describe() {
        String inv = isOnline() ? ", inv " + usedSlots() + "/36" : "";
        return name + " [" + importance + "] " + (role == null ? "no role" : role.id()) + ", " + status + inv
                + (task.equals("idle") ? "" : ", task: " + task);
    }
}
