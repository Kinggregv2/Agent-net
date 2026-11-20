package dev.agentnet.role;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.agentnet.bus.Message;
import dev.agentnet.pathing.BlockSource;
import dev.agentnet.pathing.BlockView;
import dev.agentnet.pathing.Rules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Speedrunner: beats the game on its own, the way a runner does. Into the Nether with a speed portal (no diamond
 * pickaxe, no mining obsidian): a lava source turns to obsidian the moment it touches water.
 * <ol>
 *   <li>Kit: 7 iron (two buckets, flint and steel), flint from gravel, ~24 cobblestone, one bucket of water.</li>
 *   <li>A lava pool, and flat ground 8-20 blocks from it for the portal (its water must not reach the pool).</li>
 *   <li>The frame (along +x, 4 wide, 5 high; cobblestone corners), one obsidian at a time, bottom row first, then
 *       the sides, then the top: water poured into the block in front of the frame spot (on a cobblestone scaffold),
 *       lava poured into the frame spot (it turns to obsidian against the water), the water picked back up, and the
 *       next bucket of lava fetched from the pool.</li>
 *   <li>Wait for the water to run off, light it, walk in.</li>
 * </ol>
 * Next (in the Nether): a fortress for blaze rods, ender pearls, then the End.
 */
public final class SpeedrunnerModule implements RoleModule {
    private enum Stage { IRON, SMELT, BUCKETS, FLINT, LIGHTER, SHIELD, COBBLE, WATER, POOL, CAST, LIGHT, ENTER, NETHER, STRONGHOLD, END, DONE, MACE }

    private final MinerModule miner = new MinerModule();
    private final MakerModule crafter = new MakerModule();
    /** The looter's skills: structure loot near its way (iron, obsidian, gold), kept for itself. */
    private final LooterModule looter = new LooterModule();
    private boolean lootMode;
    /** Off: it mines its own iron rather than going after structure loot. */
    private static final boolean LOOT_ON_THE_WAY = false;
    /** The warrior's skills: it fights back instead of running. */
    private int foe = -1, swingCd, riseWait;
    private boolean jumped, blocking;
    private int loweredAt = -100, swings, engagedId = -1;
    /** In the blaze spree: no shield between swings (it goes up only against a lit blaze). */
    private boolean spree;

    private static boolean hasShield(AgentHandle a) { return count(a, "minecraft:shield") > 0 || Gear.id(a.offhand()).equals("minecraft:shield"); }
    private Stage stage = Stage.IRON;
    private String minerJob;
    private int ticks, stageTicks, stepTicks, tries;
    private long startedAt;
    private String status = "";

    /** The frame's bottom-left corner; the frame runs along +x, its front (where it stands) is -z. */
    private BlockPos origin, pool;
    private final List<Step> steps = new ArrayList<>();
    private int step, sub;
    private final Set<BlockPos> badSpots = new HashSet<>();
    private boolean nearBad(BlockPos p) { for (BlockPos b : badSpots) if (b.distSqr(p) <= 8 * 8) return true; return false; }
    /** Fetching a fluid: the spot to stand on and the fluid block to aim at. */
    private BlockPos fetchStand, fetchAt;


    SpeedrunnerModule() { miner.keepsLoad = true; looter.keepsLoad = true; looter.ironOnly = true; }

    // In its box by the spawner it holds its ground: not chased off by the agent's own "run from mobs" (that took it out
    // into the open, low on health, and it burned).
    // Near the spawner too: a magma cube set the agent running around the blaze room for fourteen seconds with the blaze
    // fight switched off, and it burned. (It fights anything that comes right up to it there.)
    // In the nether it never runs: running there ends in lava or in more mobs (a magma cube had it running around for
    // twenty seconds with the blaze fight switched off). It fights what comes right up to it and eats when it can.
    // Hurt in a trial chamber (an ominous wave is a crowd): not "fighting", so the agent's own run-from-mobs and eating
    // take over until it is better. Standing its ground at any health, the whole team went down in a minute.
    @Override public boolean fighting() {
        if ((stage == Stage.MACE || stage == Stage.IRON) && lastHp < 10) return false; // (helpers stay in IRON in the overworld)
        return foe >= 0 || stage == Stage.NETHER || stage == Stage.END || stage == Stage.MACE && trialSpawner != null;
    }
    private volatile float lastHp = 20;
    /** The mob the main speedrunner is fighting: the others go for it too (one at a time, all together). */
    private static volatile int TEAM_FOCUS = -1;

    /** A hostile mob close by that it can see: fought with its best weapon. Not creepers, and not when hurt. */
    private boolean fight(AgentHandle a) {
        if (swingCd > 0) swingCd--;
        lastHp = a.health();
        Seen near = null;
        var w = a.openWorld();
        if (archerGuard(a, w)) return true;
        // In the nether, with a blaze about, the blazes are all it fights (the blaze fight picks them): it spent its
        // swings on magma cubes, each one it killed split into more, and the blazes shot it meanwhile.
        if (stage == Stage.NETHER) for (Seen s : a.entitiesNear(8)) if (s.name().equals("blaze")) { foe = -1; return false; }
        for (Seen s : a.entitiesNear(6)) {
            if (s.player() || !s.hostile() || s.name().equals("creeper") || s.name().equals("blaze") || s.name().startsWith("guardian") || s.name().equals("elder_guardian") || s.name().equals("enderman") && !s.angry()) continue;
            if (w != null && !Sight.clear(w, a.x(), a.y() + 1.62, a.z(), s.pos().x, s.pos().y + 1.0, s.pos().z)) continue;
            // In the nether the blazes come first: anything else only once it is right on top of it. (It spent twenty
            // seconds on a magma cube while two blazes shot at it.)
            if (stage == Stage.NETHER && s.distance() > (s.name().equals("magma_cube") ? 1.8 : 2.5)) continue;
            // The team's target, if it is one of these: that one (the list is nearest first otherwise).
            if (near == null) near = s;
            if (s.id() == TEAM_FOCUS && s.distance() < 8) { near = s; break; }
        }
        if (near != null && MAIN != null && MAIN.equalsIgnoreCase(a.name())) TEAM_FOCUS = near.id();
        if (near == null || a.health() < 8) {
            foe = -1;
            // In the nether the blaze fight keeps its own shield: lowered here every tick, it never stayed up the quarter
            // second a shield needs before it blocks, and every fireball hit.
            if (blocking && stage != Stage.NETHER) { a.releaseUse(); blocking = false; }
            // The jump for a critical hit is left alone: the enderman fight (a calm enderman is no "hostile" here) had its
            // jump undone every tick, jumped four hundred times and never swung.
            return false;
        }
        foe = near.id();
        status = "fighting a " + near.name();
        engage(a, near);
        return true;
    }

    /**
     * A skeleton (stray, pillager) drawing its bow at it from out of reach: the shield up toward it and on at it, so
     * the arrows hit the shield; when it lets go and is close, the shield comes down for the swing (engage). Two
     * skeletons shot it dead in a trial chamber, never hit.
     */
    private boolean archerGuard(AgentHandle a, dev.agentnet.pathing.BlockSource w) {
        if (stage == Stage.NETHER || stage == Stage.END) return false; // those fights keep their own shield
        if (!Gear.id(a.offhand()).equals("minecraft:shield")) return false;
        Seen archer = null;
        for (Seen s : a.entitiesNear(16)) {
            String n = s.name();
            if (!(n.equals("skeleton") || n.equals("stray") || n.equals("bogged") || n.equals("pillager"))) continue;
            if (s.distance() < 3.2) continue; // close: the normal fight hits it
            if (w != null && !Sight.clear(w, a.x(), a.y() + 1.62, a.z(), s.pos().x, s.pos().y + 1.5, s.pos().z)) continue;
            if (archer == null || s.usingItem() && !archer.usingItem() || s.usingItem() == archer.usingItem() && s.distance() < archer.distance()) archer = s;
        }
        if (archer == null) { if (guarding) { a.releaseUse(); blocking = false; guarding = false; } return false; }
        // Drawing on it: shield up. Between shots: shield down and on at it all the same (only guarding, it stood and
        // took arrows from three of them and never swung).
        if (archer.usingItem()) { if (!blocking) { a.useOffhand(); blocking = true; } }
        else if (blocking) { a.releaseUse(); blocking = false; }
        guarding = true;
        foe = archer.id();
        status = "shield up against a " + archer.name() + " (" + Math.round(archer.distance()) + " blocks)";
        if (ticks - guardSaidAt > 100) { guardSaidAt = ticks; a.log("[speedrun] " + status + ", hp " + Math.round(a.health())); }
        a.faceWhileMoving(archer.pos().x, archer.pos().y + 1.5, archer.pos().z);
        if (!a.isNavigating() || ticks % 20 == 0) {
            BlockPos g = BlockPos.containing(archer.pos().x, archer.pos().y + 0.2, archer.pos().z);
            a.navigate(g.getX(), g.getY(), g.getZ(), 2);
        }
        return true;
    }
    private boolean guarding;
    private int guardSaidAt = -1000;

    /** Up to it and hitting it: axe first, shield up between swings, every hit timed as a critical one. */
    private void engage(AgentHandle a, Seen near) {
        List<ItemStack> inv = a.inventory();
        // An axe before a sword: it hits harder, and every hit is a critical one (below).
        int wpn = Gear.bestTool(inv, "axe");
        boolean axe = wpn >= 0;
        if (wpn < 0) wpn = Gear.bestTool(inv, "sword");
        int cd = axe ? 25 : 13; // a full swing: an axe takes 1.25 s, a sword 0.65 s
        if (wpn >= 0) { if (wpn >= 9) { a.moveToHotbar(wpn, 0); wpn = 0; } if (a.selectedSlot() != wpn) a.selectHotbar(wpn); }
        if (near.name().equals("blaze") && eyeDist(a, near) > 3.1) {
            // A blaze just out of reach: faced, shield up, no walking at it in mid-air.
            if (a.isNavigating()) a.stopNavigation();
            a.lookAt(near.pos().x, near.pos().y + 0.9, near.pos().z);
            if (Gear.id(a.offhand()).equals("minecraft:shield") && !blocking) { a.useOffhand(); blocking = true; }
            return;
        }
        // Reach from its eyes to the middle of the body (an enderman's is high up); one in a boat is walked up to two blocks
        // off (its spot is the boat's: walking onto it, it never got a swing in).
        if (eyeDist(a, near) > 3.1) {
            if (a.airborne()) return;
            if (!a.isNavigating() || ticks % 10 == 0) a.navigate((int) Math.floor(near.pos().x), (int) Math.floor(near.pos().y), (int) Math.floor(near.pos().z), near.vehicle() >= 0 ? 2 : 1);
            return;
        }
        if (a.isNavigating()) a.stopNavigation();
        a.lookAt(near.pos().x, near.pos().y + 0.6, near.pos().z);
        // The shield is up all the time except the moment of the swing: lowered only once the swing is charged and it
        // is falling from its jump, two ticks before the hit, and raised again right after. (Lowered for the last 11
        // ticks of every swing as before, and a shield needs 5 ticks up before it blocks, it stopped little more than a
        // third of what came at it.)
        boolean shield = !spree && Gear.id(a.offhand()).equals("minecraft:shield");
        // A critical hit: a jump timed so the swing is ready as it starts to fall, and the hit lands on the way down.
        // The hit goes out well into the fall, not at the top: its moves reach the server a moment behind the hit, and
        // a hit at the top landed while the server still had it going up: no critical hit.
        boolean falling = a.airborne() && (a.verticalSpeed() <= -0.2 || riseWait >= 20);
        boolean ready = swingCd == 0 && jumped && (falling || !a.airborne());
        if (!ready) {
            if (shield && !blocking) { a.useOffhand(); blocking = true; }
            if (!jumped && !a.airborne() && swingCd <= 9) { a.jump(); jumped = true; riseWait = 0; }
            else if (a.airborne()) riseWait++;
            return;
        }
        if (blocking) { a.releaseUse(); blocking = false; loweredAt = ticks; return; }
        if (ticks - loweredAt < 2) return;
        a.attack(near.id());
        swings++;
        dev.agentnet.AgentNet.LOG.info("[{}] swing at a {} #{} ({} blocks, {}, its health {})", a.name(), near.name() + (a.captain(near.id()) ? " captain" : ""), near.id(), String.format("%.1f", near.distance()), a.airborne() ? "falling" : "on the ground", a.healthOf(near.id()));
        swingCd = cd; jumped = false;
    }

    // ------------------------------------------------------------------ food: animals on the way, their meat cooked

    private static final Set<String> PREY = Set.of("cow", "pig", "sheep", "chicken", "rabbit", "mooshroom");
    private static final Set<String> MEAT = Set.of("beef", "porkchop", "mutton", "chicken", "rabbit", "cod", "salmon");
    private int prey = -1, huntStart, noHuntUntil, carcassTicks, cookAt = -1000, cookTries, cookCount = -1;
    private net.minecraft.world.phys.Vec3 preyAt;
    private BlockPos carcass;

    /** Food for healing: it kills the animals it passes, picks up the meat and cooks it in its own furnace. True while busy with that. */
    private boolean feed(AgentHandle a) { return feed(a, 6, false); }

    /** Food up to "want" (raw counted, it gets cooked); "kit": also during the portal stages (the nether kit). */
    private boolean feed(AgentHandle a, int want, boolean kit) {
        if (nether(a) || !kit && (stage == Stage.CAST || stage == Stage.LIGHT || stage == Stage.ENTER)) return false;
        int ready = 0, rawN = 0, rawAll = 0;
        String raw = null;
        for (ItemStack s : a.inventory()) {
            String id = Gear.id(s).replace("minecraft:", "");
            if (MEAT.contains(id)) { rawAll += s.getCount(); if (s.getCount() > rawN) { raw = id; rawN = s.getCount(); } }
            else if (Gear.isFood(s) && !id.contains("golden_apple") && !id.equals("rotten_flesh") && !id.equals("spider_eye")) ready += s.getCount();
        }
        // The meat on the ground where the animal fell.
        if (carcass != null) {
            if (++carcassTicks > 100 || carcassTicks > 20 && carcass.distToCenterSqr(a.x(), a.y(), a.z()) < 1.5 * 1.5) { carcass = null; if (a.isNavigating()) a.stopNavigation(); }
            else { status = "picking up the meat"; if (!a.isNavigating() && carcassTicks % 10 == 1) a.navigate(carcass.getX(), carcass.getY(), carcass.getZ(), 0); return true; }
        }
        // Still chasing one: done when it is gone.
        Seen t = null, nearest = null;
        for (Seen s : a.entitiesNear(20)) {
            if (s.player() || !PREY.contains(s.name())) continue;
            if (s.id() == prey) t = s;
            // A new one only if it can see it: from down in a mine it went after the cows on the surface above.
            var sw = a.openWorld();
            if (sw == null || !Sight.clear(sw, a.x(), a.y() + 1.62, a.z(), s.pos().x, s.pos().y + 0.6, s.pos().z)) continue;
            if (nearest == null || s.distance() < nearest.distance()) nearest = s;
        }
        if (prey >= 0 && t == null) {
            prey = -1; huntStart = 0;
            if (preyAt != null) { carcass = BlockPos.containing(preyAt); carcassTicks = 0; return true; }
        }
        // Raw meat in its pack, nothing left to chase: into the furnace (three tries, then it eats it as it is).
        // Only with a furnace, or the 8 cobblestone for one: without, the crafter skipped the recipe ("doesn't know how")
        // three times over and the meat was never cooked. Until then the raw meat is kept (and eaten raw if it must).
        if (prey < 0 && raw != null && (nearest == null || ready + rawAll >= want) && MakerModule.canHaveFurnace(a)) {
            if (rawN != cookCount) { cookCount = rawN; cookTries = 0; }
            if (cookTries < 3 && ticks - cookAt > 200) {
                cookAt = ticks; cookTries++;
                if (a.isNavigating()) a.stopNavigation();
                status = "cooking " + rawN + " " + raw;
                a.log("[speedrun] cooking " + rawN + " " + raw);
                crafter.onTask(a, "make cooked_" + raw + " " + rawN);
                return true;
            }
            return false;
        }
        if (ready + rawAll >= want || ticks < noHuntUntil) return false;
        if (t == null) t = nearest;
        if (t == null) return false;
        if (prey != t.id()) { prey = t.id(); huntStart = ticks; }
        if (ticks - huntStart > 20 * 40) { prey = -1; preyAt = null; noHuntUntil = ticks + 20 * 120; if (a.isNavigating()) a.stopNavigation(); return false; } // it got away
        preyAt = t.pos();
        status = "hunting a " + t.name() + " for food";
        engage(a, t);
        return true;
    }

    @Override public void onStart(AgentHandle a) { a.keepWorld(true); miner.onStart(a); startedAt = System.currentTimeMillis() - Clock.played(a.name()); }
    @Override public void onStop(AgentHandle a) { miner.onStop(a); a.keepWorld(false); Clock.save(a.name(), System.currentTimeMillis() - startedAt); }

    /**
     * The run clock, kept across a rejoin and a game restart: each speedrunner's play time so far, in
     * agentnet/speedrun-clock.txt (saved every minute and on stop). Time offline does not count. A new run (the saved
     * crew set aside) sets this file aside too.
     */
    static final class Clock {
        private static java.nio.file.Path file() { return dev.agentnet.AgentFiles.dir().resolve("speedrun-clock.txt"); }

        private static java.util.Map<String, Long> read() {
            java.util.Map<String, Long> m = new java.util.TreeMap<>();
            try {
                if (java.nio.file.Files.exists(file())) for (String l : java.nio.file.Files.readAllLines(file())) {
                    String[] p = l.trim().split("\\s+");
                    if (p.length == 2 && !p[0].startsWith("#")) try { m.put(p[0], Long.parseLong(p[1])); } catch (NumberFormatException ignored) {}
                }
            } catch (java.io.IOException e) { dev.agentnet.AgentNet.LOG.warn("couldn't read speedrun-clock.txt", e); }
            return m;
        }

        static synchronized long played(String name) { return Math.max(0, read().getOrDefault(name, 0L)); }

        /** Which speedrunner is the main one in this run (agentnet/speedrun-main.txt): it stays the main after a restart. */
        private static java.nio.file.Path mainFile() { return dev.agentnet.AgentFiles.dir().resolve("speedrun-main.txt"); }
        static synchronized String savedMain() {
            try { return java.nio.file.Files.exists(mainFile()) ? java.nio.file.Files.readString(mainFile()).trim() : null; } catch (java.io.IOException e) { return null; }
        }
        static synchronized void saveMain(String name) {
            try { java.nio.file.Files.writeString(mainFile(), name + "\n"); } catch (java.io.IOException e) { dev.agentnet.AgentNet.LOG.warn("couldn't write speedrun-main.txt", e); }
        }

        static synchronized void save(String name, long ms) {
            java.util.Map<String, Long> m = read();
            m.put(name, ms);
            StringBuilder b = new StringBuilder("# AgentNet: each speedrunner's play time in this run (ms)\n");
            m.forEach((k, v) -> b.append(k).append(' ').append(v).append('\n'));
            try { java.nio.file.Files.writeString(file(), b.toString()); } catch (java.io.IOException e) { dev.agentnet.AgentNet.LOG.warn("couldn't write speedrun-clock.txt", e); }
        }
    }
    @Override public String status() { return "speedrun " + stage.name().toLowerCase() + ": " + status; }
    @Override public void onMessage(AgentHandle a, Message m) { miner.onMessage(a, m); }

    @Override
    public void onTask(AgentHandle a, String task) {
        if (task.equals("idle")) { mine(a, null); return; }
        if (task.equals("step")) { if (!stepMode) { stepMode = true; a.log("§b[speedrun] step-by-step: /agent step " + a.name() + " for each next step"); } stepGo = true; return; }
        if (task.startsWith("speedrun")) stepMode = false;
        if (task.equals("speedrun main")) { MAIN = a.name(); a.log("§a[speedrun] " + a.name() + " is the main speedrunner"); }
        if (task.startsWith("speedrun stage ")) {
            try { go(a, Stage.valueOf(task.substring(15).trim().toUpperCase())); }
            catch (IllegalArgumentException e) { a.log("§c[speedrun] no stage " + task.substring(15)); }
            return;
        }
        if (task.startsWith("speedrun") && stage == Stage.NETHER && !nether(a)) go(a, Stage.IRON);
    }

    private static int count(AgentHandle a, String id) {
        int n = 0;
        for (ItemStack s : a.inventory()) if (Gear.id(s).equals(id)) n += s.getCount();
        return n;
    }

    private static int buckets(AgentHandle a) {
        return count(a, "minecraft:bucket") + count(a, "minecraft:water_bucket") + count(a, "minecraft:lava_bucket");
    }

    private static boolean nether(AgentHandle a) { return a.dimension() != null && a.dimension().endsWith("the_nether"); }

    private void go(AgentHandle a, Stage s) {
        if (s != stage) a.log("[speedrun] " + s.name().toLowerCase().replace('_', ' ') + String.format(" (%.1f min in)", (System.currentTimeMillis() - startedAt) / 60000.0));
        stage = s;
        stageTicks = 0;
    }

    /** Its own mining job (kept: nothing goes to the crew), or none. */
    private void mine(AgentHandle a, String job) {
        if (job == null ? minerJob == null : job.equals(minerJob)) return;
        minerJob = job;
        miner.onTask(a, job == null ? "idle" : job);
    }

    /** Iron still needed: 3 per missing bucket, 1 for flint and steel, 1 for a shield (8 in all). */
    private static int ironNeeded(AgentHandle a) {
        return Math.max(0, 2 - buckets(a)) * 3 + (count(a, "minecraft:flint_and_steel") > 0 ? 0 : 1) + (hasShield(a) ? 0 : 1);
    }

    @Override
    public void tick(AgentHandle a) {
        ticks++;
        // Its world stays, whatever its miner or crafter did when they stopped (their onStop turns it off: run 1118d, the
        // main's chunks were freed while it was dug in for the night and, not "keeping" its world, it never reconnected
        // for them, and stood blind for 50 minutes).
        a.keepWorld(true);
        if (a.role() == Role.SPEEDRUNNER) FIRST_SEEN.putIfAbsent(a.name(), System.currentTimeMillis()); // its start, for the first-is-main rule
        if (ticks % 1200 == 0) Clock.save(a.name(), System.currentTimeMillis() - startedAt);
        stageTicks++;
        if (ticks < 60) return; // a moment for its inventory to arrive
        if (ticks == 60 && stage != Stage.CAST && resume(a)) return;
        if (crafter.busy()) { crafter.tick(a); return; }
        if (fight(a)) return;
        // The furnace had nothing to burn: logs first (4: fuel, and planks to spare), then the cooking or smelting again.
        if (crafter.outOfFuel && !nether(a)) {
            if (MakerModule.hasFuel(a) && (countLogs(a) >= 3 || !"mine 4 logs".equals(minerJob))) {
                crafter.outOfFuel = false;
                if ("mine 4 logs".equals(minerJob)) mine(a, null);
                cookAt = -10000; cookTries = 0; // cook again at once
                a.log("[speedrun] has fuel again: back to the furnace");
            } else {
                status = "getting wood: the furnace had no fuel";
                if (!"mine 4 logs".equals(minerJob)) a.log("[speedrun] the furnace had no fuel: getting logs first");
                if (minerJob != null && miner.idle() && miner.status().startsWith("done") && ticks - jobRestart > 100) { minerJob = null; jobRestart = ticks; }
                mine(a, "mine 4 logs");
                miner.tick(a);
                return;
            }
        }
        // A stone axe first of all, as soon as it has the 3 cobblestone: made only in the cobblestone stage, it went
        // through the iron and the smelting with bare hands and a zombie killed it at the furnace.
        if (!nether(a) && stage.ordinal() < Stage.POOL.ordinal() && ticks % 200 == 37 && !crafter.busy()) {
            int axeAt = Gear.bestTool(a.inventory(), "axe");
            boolean goodAxe = axeAt >= 0 && !Gear.id(a.inventory().get(axeAt)).equals("minecraft:wooden_axe");
            if (!goodAxe && count(a, "minecraft:cobblestone") + count(a, "minecraft:cobbled_deepslate") >= 3 && crafter.queueTool(a, "minecraft:stone_axe")) {
                status = "making a stone axe";
                return;
            }
            // No pickaxe at all (it broke, or it died): a new one at once, stone if it has the cobblestone, else wood. The
            // main lost its pickaxe and wandered the mountains for logs it couldn't dig down to, coal it couldn't mine.
            if (Gear.bestTool(a.inventory(), "pickaxe") < 0) {
                int wood = 0;
                for (ItemStack s : a.inventory()) { String id = Gear.id(s); if (id.endsWith("_planks")) wood += s.getCount(); else if (id.endsWith("_log") && !id.contains("stripped")) wood += 4 * s.getCount(); }
                String pick = count(a, "minecraft:cobblestone") + count(a, "minecraft:cobbled_deepslate") >= 3 && wood >= 2 ? "minecraft:stone_pickaxe" : wood >= 5 ? "minecraft:wooden_pickaxe" : null;
                if (pick != null && crafter.queueTool(a, pick)) {
                    a.log("[speedrun] no pickaxe: making a " + pick.substring(10).replace('_', ' '));
                    status = "making a pickaxe";
                    return;
                }
            }
        }
        if (feed(a)) return;
        // Loot on the way: a look every 30 s while gathering in the overworld; once going for it, until it's done.
        if (lootMode) { looter.tick(a); if (!looter.looting()) { lootMode = false; a.log("[speedrun] done looting: back to the run"); } return; }
        if (LOOT_ON_THE_WAY && !nether(a) && ticks % 600 == 300 && (stage == Stage.IRON || stage == Stage.FLINT || stage == Stage.COBBLE || stage == Stage.WATER || stage == Stage.POOL)) {
            looter.lookOnce(a);
            // Only loot near its own level: a trial chamber barrel 80 blocks down took it through caves and along lava.
            if (looter.looting() && looter.target() != null && looter.target().getY() < a.y() - 8) looter.onTask(a, "idle");
            if (looter.looting()) { lootMode = true; mine(a, null); a.log("[speedrun] structure loot nearby: looting it on the way"); return; }
        }
        // Water and the lava pool are looked for from the surface. Down in its own tunnels after the gravel (which falls
        // and closes them behind it) it picked one unreachable water after another, ran out of food where there are no
        // animals, and a skeleton killed it.
        if ((stage == Stage.WATER || stage == Stage.POOL) && !nether(a)) {
            // Between its dig steps the climb hands over to the walker: a climb still under way is left alone. Restarted
            // every 10 s, it never got out of a cave 5 blocks down (run 1118d, 30 minutes in the pool stage).
            if (a.escaping() || upTrip && a.isNavigating() && ticks - upAt < 1800) { status = "on its way up to the surface"; return; }
            if (ticks - upAt > 200 && ticks > upGiveUpUntil && underground(a)) {
                if (upTrip && a.y() < upFromY + 1.5) {
                    if (++upFails >= 6) {
                        upFails = 0; upTrip = false; upGiveUpUntil = ticks + 6000;
                        a.log("§e[speedrun] can't get up to the surface from here: looking from down here for 5 minutes");
                    }
                } else upFails = 0;
                if (ticks <= upGiveUpUntil) { upTrip = false; }
                else {
                    upAt = ticks; upFromY = a.y();
                    if (a.isNavigating()) a.stopNavigation();
                    upTrip = a.goUp();
                    if (upTrip) { fetchStand = null; status = "up to the surface first"; a.log("[speedrun] underground: up to the surface first (" + roofSeen + ")"); return; }
                }
            } // (upTrip stays set between tries: cleared here, the count of climbs that got nowhere never started)
        }
        // Back in the overworld (it died down there and came back at spawn): the run goes on from what it carries.
        if (!nether(a) && stage == Stage.NETHER && ticks > 100) { rodDrop = null; blazeId = -1; spawner = null; foe = -1; go(a, Stage.IRON); }
        if (nether(a) && stage.ordinal() < Stage.NETHER.ordinal()) {
            mine(a, null); go(a, Stage.NETHER); a.log("§a[speedrun] in the nether");
            netherPortal = BlockPos.containing(a.x(), a.y() + 0.2, a.z()); // where it came out: the way home
        }
        // A falloff back home with its rods and wart: a brewer from now on.
        if (a.role() == Role.FALLOFF && falloffHome && !nether(a)) { if (!becameBrewer) { becameBrewer = true; a.log("§a[speedrun] home with " + count(a, "minecraft:blaze_rod") + " blaze rods and " + count(a, "minecraft:nether_wart") + " nether wart: a brewer now"); dev.agentnet.AgentNetClient.changeRole(a.name(), "brewer"); } return; }
        if (!isMain(a)) { helper(a); return; }
        mainStage = stage; mainRods = count(a, "minecraft:blaze_rod"); mainPearls = count(a, "minecraft:ender_pearl");
        mainIronShort = Math.max(0, ironNeeded(a) - count(a, "minecraft:iron_ingot") - count(a, "minecraft:raw_iron"));
        // The nether kit too, once the portal frame is up: its chestplate, leggings and boots (8, 7 and 4 iron) are asked of
        // the crew like the buckets are (run 1120s: the frame done at 17.6 min, the main mining 19 iron for its kit alone).
        { int kit = 0; int[] cost = {0, 8, 7, 4}; for (int s = 1; s <= 3; s++) if (!hasArmor(a, s)) kit += cost[s];
          mainKitShort = stage.ordinal() >= Stage.LIGHT.ordinal() && !nether(a) ? Math.max(0, kit - count(a, "minecraft:iron_ingot") - count(a, "minecraft:raw_iron")) : 0; }
        mainNeedsFlint = count(a, "minecraft:flint_and_steel") == 0 && count(a, "minecraft:flint") == 0;
        { int best = -1; for (ItemStack s : a.inventory()) if (Gear.id(s).endsWith("_pickaxe")) best = Math.max(best, Gear.tier(s));
          mainNeedsCobble = best < 2 && count(a, "minecraft:cobblestone") + count(a, "minecraft:cobbled_deepslate") < 3; }
        mainNeedsFuel = stage.ordinal() <= Stage.SMELT.ordinal() && count(a, "minecraft:raw_iron") > 0 && !MakerModule.hasFuel(a);
        askCrewForIron(a);
        askCrewForWood(a);
        if (netherPortal != null) mainNetherPortal = netherPortal;
        switch (stage) {
            case IRON -> {
                // The dragon is already dead on this world (kept in speedrun-beaten.txt): straight on to the mace.
                if (stageTicks < 2 && !regear && beaten()) { a.log("[speedrun] the game is beaten on this world already: on to a mace"); go(a, Stage.MACE); return; }
                int need = ironNeeded(a);
                int have = count(a, "minecraft:iron_ingot") + count(a, "minecraft:raw_iron");
                if (have >= need) { mine(a, null); go(a, Stage.SMELT); return; }
                gather(a, "minecraft:raw_iron", need - count(a, "minecraft:iron_ingot"), "mine 8 iron_ore", Stage.SMELT);
            }
            case SMELT -> {
                int need = ironNeeded(a), ingots = count(a, "minecraft:iron_ingot");
                if (ingots >= need) { go(a, Stage.BUCKETS); return; }
                if (count(a, "minecraft:raw_iron") == 0) { go(a, Stage.IRON); return; }
                if (furnaceKit(a)) return;
                if (stageTicks % 200 == 1) { crafter.onTask(a, "make iron_ingot " + Math.min(need - ingots, count(a, "minecraft:raw_iron"))); status = "smelting iron"; }
            }
            case BUCKETS -> {
                if (buckets(a) >= 2) { go(a, Stage.FLINT); return; }
                if (count(a, "minecraft:iron_ingot") < 3) { go(a, Stage.IRON); return; }
                if (tableKit(a)) return;
                if (stageTicks % 100 == 1) { crafter.onTask(a, "make bucket 1"); status = "crafting a bucket"; }
            }
            case FLINT -> {
                if (count(a, "minecraft:flint_and_steel") > 0) { go(a, Stage.SHIELD); return; }
                gather(a, "minecraft:flint", 1, "mine 12 gravel", Stage.LIGHTER);
            }
            case LIGHTER -> {
                if (count(a, "minecraft:flint_and_steel") > 0) { go(a, Stage.SHIELD); return; }
                if (count(a, "minecraft:iron_ingot") < 1) { go(a, Stage.IRON); return; }
                if (tableKit(a)) return;
                if (stageTicks % 100 == 1) { crafter.onTask(a, "make flint_and_steel 1"); status = "crafting flint and steel"; }
                if (stageTicks > 20 * 60) { a.log("§e[speedrun] couldn't craft flint and steel: getting the parts again"); go(a, Stage.FLINT); }
            }
            case SHIELD -> {
                // A shield (6 planks and the 8th iron), into its off hand. It does not hold the run up for long.
                if (Gear.id(a.offhand()).equals("minecraft:shield")) { mine(a, null); go(a, regear ? Stage.MACE : Stage.COBBLE); return; }
                if (stageTicks > 20 * 240) { a.log("[speedrun] no shield after 4 minutes: going on without one"); mine(a, null); go(a, regear ? Stage.MACE : Stage.COBBLE); return; }
                if (count(a, "minecraft:shield") > 0) {
                    mine(a, null);
                    status = "taking its shield into its off hand";
                    if (stageTicks % 20 != 0) return;
                    List<ItemStack> inv = a.inventory();
                    int slot = -1;
                    for (int i = 0; i < inv.size(); i++) if (Gear.id(inv.get(i)).equals("minecraft:shield")) { slot = i; break; }
                    if (slot >= 9) { a.moveToHotbar(slot, 8); return; }
                    if (slot >= 0) { if (a.selectedSlot() != slot) { a.selectHotbar(slot); return; } a.swapOffhand(); }
                    return;
                }
                if (count(a, "minecraft:iron_ingot") < 1) { mine(a, null); go(a, Stage.IRON); return; }
                int wood = 0;
                for (ItemStack s : a.inventory()) {
                    String id = Gear.id(s);
                    if (id.endsWith("_planks")) wood += s.getCount();
                    else if (id.endsWith("_log") && !id.contains("stripped")) wood += 4 * s.getCount();
                }
                // Plus a crafting table's 4 when it carries none (run 1118d: 9 planks' worth, 4 into a table, 5 left, and the
                // shield "missing ingredients" for four minutes after every restart).
                int woodNeed = count(a, "minecraft:crafting_table") > 0 ? 6 : 10;
                if (wood < woodNeed) {
                    status = "getting wood for a shield";
                    if (minerJob != null && miner.idle() && miner.status().startsWith("done") && ticks - jobRestart > 100) { minerJob = null; jobRestart = ticks; }
                    mine(a, "mine 3 logs");
                    miner.tick(a);
                    return;
                }
                mine(a, null);
                if (stageTicks % 100 == 1) {
                    // Six planks, as planks: the craft does not saw its logs up by itself. With 3 planks and 3 logs it
                    // "made a shield" every 5 s for four minutes, each one missing ingredients (run 1118d, every regear).
                    int planks = 0;
                    for (ItemStack s : a.inventory()) if (Gear.id(s).endsWith("_planks")) planks += s.getCount();
                    String fromLog = MakerModule.planksFromLog(a);
                    if (planks < 6 && fromLog != null) crafter.queueAll(a, fromLog);
                    crafter.onTask(a, "make shield 1");
                    status = "crafting a shield";
                }
            }
            case COBBLE -> {
                // A stone axe too (its weapon: harder hits than a sword), once it has the cobblestone.
                int axeAt = Gear.bestTool(a.inventory(), "axe");
                boolean goodAxe = axeAt >= 0 && !Gear.id(a.inventory().get(axeAt)).equals("minecraft:wooden_axe");
                if (count(a, "minecraft:cobblestone") >= 24 && !goodAxe && stageTicks % 200 == 1
                        && crafter.queueTool(a, "minecraft:stone_axe")) { status = "making a stone axe"; return; }
                gather(a, "minecraft:cobblestone", 24, "mine 32 stone", Stage.WATER);
            }
            case WATER -> {
                if (count(a, "minecraft:water_bucket") > 0) { fetchStand = null; go(a, Stage.POOL); return; }
                fetch(a, false);
            }
            case POOL -> { if (resumeLater && resume(a)) return; findPool(a); } // geared up again after a death: its own portal, not a new pool
            case CAST -> cast(a);
            case LIGHT -> light(a);
            case ENTER -> enter(a);
            case NETHER -> blazes(a);
            case STRONGHOLD -> stronghold(a);
            case END -> theEnd(a);
            case DONE -> { status = "beat the game (the dragon is dead): next, a mace"; if (stageTicks > 100) go(a, Stage.MACE); }
            case MACE -> {
                mace(a);
                if (ticks % 400 == 0) {
                    int breezes = 0; for (Seen s : a.entitiesNear(24)) if (s.name().equals("breeze")) breezes++;
                    a.log(String.format("[speedrun] mace: %s | hp %.0f | at %.0f %.0f %.0f | %d breeze rod(s), %d breeze(s) near", status, a.health(), a.x(), a.y(), a.z(), count(a, "minecraft:breeze_rod"), breezes));
                }
            }
        }
    }

    /**
     * What it takes to carry its own furnace: one already, or 8 cobblestone and a crafting table (or the wood for one).
     * Without, the crafter only said "needs a furnace within 24 blocks" every 10 s and the iron was never smelted.
     * True while it is off getting the missing part. (A furnace nearby would do too; the crafter finds that itself.)
     */
    private boolean furnaceKit(AgentHandle a) {
        int wood = 0;
        for (ItemStack s : a.inventory()) {
            String id = Gear.id(s);
            if (id.endsWith("_planks")) wood += s.getCount();
            else if (id.endsWith("_log") && !id.contains("stripped")) wood += 4 * s.getCount();
        }
        boolean furnace = count(a, "minecraft:furnace") > 0;
        int tableWood = furnace || count(a, "minecraft:crafting_table") > 0 ? 0 : 4;
        // Fuel for all of it: a plank burns 1.5 items, coal 8. (It made its table and furnace, then had nothing to burn.)
        int coal = count(a, "minecraft:coal") + count(a, "minecraft:charcoal");
        int toSmelt = count(a, "minecraft:raw_iron");
        boolean fuel = coal * 8 >= toSmelt || (wood - tableWood) * 3 / 2 >= toSmelt;
        int stone = count(a, "minecraft:cobblestone") + count(a, "minecraft:cobbled_deepslate");
        // Fuel short but wood enough for the table: coal ore if there is some in sight (8 items a coal; in snowy mountains
        // with no trees about it walked 120-block hops for 3 minutes looking for logs, coal ore all round it).
        boolean coalNear = !fuel && wood >= tableWood && a.openWorld() instanceof BlockView cw
                && !cw.findAll((int) Math.floor(a.x()), (int) Math.floor(a.z()), 32, s -> s.getBlock() == Blocks.COAL_ORE || s.getBlock() == Blocks.DEEPSLATE_COAL_ORE, 1).isEmpty();
        if (!fuel && isMain(a) && ticks - fuelAskedAt > 1200) { fuelAskedAt = ticks; BuilderModule.requestMaterial(a, "minecraft:coal", Math.max(1, (toSmelt + 7) / 8)); }
        String job = wood < tableWood ? "mine 3 logs" : !fuel ? (coalNear ? "mine 3 coal_ore" : "mine 3 logs") : !furnace && stone < 8 ? "mine 12 stone" : null;
        if (job == null) { if (kitJob) { kitJob = false; mine(a, null); } return false; }
        kitJob = true;
        status = job.contains("coal") ? "mining coal for its furnace" : job.contains("logs") ? "getting wood (a crafting table for its furnace, and fuel)" : "getting cobblestone for a furnace";
        if (minerJob != null && miner.idle() && miner.status().startsWith("done") && ticks - jobRestart > 100) { minerJob = null; jobRestart = ticks; }
        mine(a, job);
        miner.tick(a);
        return true;
    }

    private boolean kitJob;
    private int fuelAskedAt = -10000;

    /**
     * A crafting table to craft at: one carried, one within 24 blocks, or the 4 planks for one; else it chops logs itself.
     * Run 1118d: it left its table behind digging out of a hole, had 1 plank, and asked "make 1x bucket" every 5 s for
     * 80 minutes while waiting on the crew for logs.
     */
    private boolean tableKit(AgentHandle a) {
        int wood = 0;
        for (ItemStack s : a.inventory()) {
            String id = Gear.id(s);
            if (id.endsWith("_planks")) wood += s.getCount();
            else if ((id.endsWith("_log") || id.endsWith("_stem")) && !id.contains("stripped")) wood += 4 * s.getCount();
        }
        boolean ok = wood >= 4 || count(a, "minecraft:crafting_table") > 0
                // A table standing near counts only for the first 15 s of the stage: one the crafter cannot get to (across water,
                // up a cliff) kept this "fine" while "make 1x bucket" was queued 68 times with nothing made (run 1118d).
                || stageTicks < 300 && (a.openWorld() instanceof BlockView w && !w.findAll((int) Math.floor(a.x()), (int) Math.floor(a.z()), 24, s -> s.getBlock() == Blocks.CRAFTING_TABLE, 1).isEmpty());
        if (ok) { if (tableJob) { tableJob = false; mine(a, null); } return false; }
        if (!tableJob) a.log("[speedrun] no crafting table near and " + wood + " planks' worth of wood: chopping logs for one");
        tableJob = true;
        status = "getting wood for a crafting table";
        if (minerJob != null && miner.idle() && miner.status().startsWith("done") && ticks - jobRestart > 100) { minerJob = null; jobRestart = ticks; }
        mine(a, "mine 3 logs");
        miner.tick(a);
        return true;
    }

    private boolean tableJob;

    // ------------------------------------------------------------------ the nether: a fortress, its blazes, their rods
    //
    // Seven rods (fourteen eyes of ender, more than the twelve a portal can need). It looks for nether bricks in what
    // it can see, travels in 64-block hops over solid ground (never a lava sea) until it finds some, then for the
    // fortress's blaze spawner, and waits by it. Every blaze it sees it goes right up to (they hover and shoot from
    // range) and hits like any other mob: shield up between swings, every swing a critical one. Where one fell, it
    // walks over to pick the rod up.

    static final int RODS = 7;

    // ---- the team: one main speedrunner and helpers (the user's plan: four on the task, the main carries the rods
    //      and pearls and builds the portals; the others go with it and kill blazes and endermen for it, and throw
    //      what drops to it).
    private static volatile String MAIN;
    private static volatile int mainRods, mainPearls;
    private static volatile Stage mainStage = Stage.IRON;
    /** The main's portal: the overworld one (inside it) and where it came out in the nether. */
    private static volatile BlockPos mainPortal, mainNetherPortal;
    /** The block in front of the main's overworld portal: the way in is from there, straight ahead (not at a slant). */
    private static volatile BlockPos mainPortalFront;
    /** The portal's other column and the block in front of it (a helper stuck at one way in tries the other). */
    private static volatile BlockPos mainPortal2, mainPortalFront2;

    private static boolean online(String name) {
        AgentHandle h = name == null ? null : GathererModule.find(name);
        return h != null && h.isOnline() && h.role() == Role.SPEEDRUNNER;
    }

    /** When the main was last seen online. Going through a portal it is "offline" for a moment, and a helper took over. */
    private static volatile long mainSeenAt;
    /** When each speedrunner first started (this game session): the first one is the main (the user, 2026-11-20). */
    private static final java.util.Map<String, Long> FIRST_SEEN = new java.util.concurrent.ConcurrentHashMap<>();

    /** The speedrunner online now that started first, or null. */
    private static String firstRunner() {
        String best = null; long bt = Long.MAX_VALUE;
        for (var e : FIRST_SEEN.entrySet()) if (e.getValue() < bt && online(e.getKey())) { bt = e.getValue(); best = e.getKey(); }
        return best;
    }

    private boolean isMain(AgentHandle a) {
        if (a.role() == Role.FALLOFF) return false; // a falloff never leads the run
        FIRST_SEEN.putIfAbsent(a.name(), System.currentTimeMillis());
        long now = System.currentTimeMillis();
        if (MAIN != null && online(MAIN)) mainSeenAt = now;
        // After a restart the one with the most play time in the run is the main again (whichever ticked first took it:
        // itsCyber, 30 minutes in, became a helper and threw its iron to a fresh one). The main is saved by name; it gets 30 s to come back online.
        if (MAIN == null) { String back = Clock.savedMain(); if (back != null && !back.isEmpty() && !back.equalsIgnoreCase(a.name()) && (online(back) || ticks < 600)) return false; }
        // (Away on purpose, out of the game for a minute because of mobs: still the main. A helper took over while it
        // was away, run 1119p.)
        if (MAIN != null && dev.agentnet.Agent.away(MAIN)) mainSeenAt = now;
        // No main yet: the speedrunner that started first takes it, not whichever ticked first (the user: "the first
        // speedrunner that spawned in should be the main"). The others wait for it.
        if (MAIN == null) { String first = firstRunner(); if (first != null && !first.equalsIgnoreCase(a.name())) return false; }
        if (MAIN == null || !online(MAIN) && now - mainSeenAt > 15_000) {
            if (MAIN != null) a.log("§e[speedrun] " + MAIN + " is gone: " + a.name() + " is the main speedrunner now");
            MAIN = a.name();
            Clock.saveMain(a.name());
            if (ticks > 0) a.log("§a[speedrun] " + a.name() + " is the main speedrunner");
        }
        return MAIN.equalsIgnoreCase(a.name());
    }
    private BlockPos spawner, rodDrop, brickGoal;
    private net.minecraft.world.phys.Vec3 blazeAt;
    private int blazeId = -1, rodTicks, hopAt = -1000, brickAt = -1000;
    private final Set<BlockPos> bricksSeen = new HashSet<>();

    private static boolean netherBrick(BlockState s) {
        return s != null && (s.getBlock() == Blocks.NETHER_BRICKS || s.getBlock() == Blocks.NETHER_BRICK_FENCE || s.getBlock() == Blocks.NETHER_BRICK_STAIRS);
    }

    // ------------------------------------------------------------------ falloff speedrunners
    //
    // The user: they "tag along the speedrunners until they leave a blaze spawner"; then they "won't leave and will farm
    // blaze rods until they have a combined 2 stacks (for potions)", "pickup netherwart", and "after they return they
    // should become brewers".

    /** Each falloff's blaze rods (name -> count): two stacks between them and they're done. */
    private static final java.util.Map<String, Integer> FALLOFF_RODS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int FALLOFF_TARGET = 128, WART_WANTED = 32;
    private boolean falloffHome, becameBrewer, falloffSaid;
    private int wartStart = -1, wartDigAt = -100;
    private BlockPos wartGoal, wartDrop;

    /** The blaze rods all falloffs online hold. */
    private static int falloffRods() {
        int n = 0;
        for (var e : FALLOFF_RODS.entrySet()) {
            AgentHandle h = GathererModule.find(e.getKey());
            if (h != null && h.isOnline() && h.role() == Role.FALLOFF) n += e.getValue();
        }
        return n;
    }

    /**
     * The main has its rods and moved on: a falloff stays. False while it farms on (the blaze fight below goes on as
     * before, rods kept); true while it picks wart or goes home.
     */
    private boolean falloff(AgentHandle a) {
        int total = falloffRods();
        if (!falloffSaid) { falloffSaid = true; a.log("§d[speedrun] " + MAIN + " has its rods and moves on: staying at the spawner to farm blaze rods for potions"); }
        if (!falloffHome && total < FALLOFF_TARGET) {
            status = "falloff: farming blaze rods (" + count(a, "minecraft:blaze_rod") + " here, " + total + "/" + FALLOFF_TARGET + " between the falloffs)";
            return false;
        }
        if (!falloffHome) {
            if (wartStart < 0) { wartStart = ticks; a.log("§a[speedrun] the falloffs have " + total + " blaze rods: picking nether wart, then home"); }
            if (pickWart(a)) return true;
            falloffHome = true;
            a.log("[speedrun] " + count(a, "minecraft:nether_wart") + " nether wart: going home through the portal");
        }
        BlockPos p = netherPortal != null ? netherPortal : mainNetherPortal;
        if (p == null) { status = "falloff: no portal home known"; return true; }
        status = "falloff: home with " + count(a, "minecraft:blaze_rod") + " rods and " + count(a, "minecraft:nether_wart") + " wart";
        double d = Math.hypot(a.x() - (p.getX() + 0.5), a.z() - (p.getZ() + 0.5));
        if (d < 1.4 && Math.abs(a.y() - p.getY()) < 1.5) { if (a.isNavigating()) a.stopNavigation(); a.stepToward(p.getX() + 0.5, p.getZ() + 0.5); return true; }
        if (!a.isNavigating() && ticks % 20 == 0) a.navigate(p.getX(), p.getY(), p.getZ(), 0);
        return true;
    }

    /** Nether wart round the fortress (its soul sand by the stairs): broken and picked up. True while busy with it. */
    private boolean pickWart(AgentHandle a) {
        if (count(a, "minecraft:nether_wart") >= WART_WANTED || ticks - wartStart > 20 * 240) return false;
        if (!(a.openWorld() instanceof BlockView w)) return true;
        // Its drop first: walked over (items are picked up by walking into them).
        if (wartDrop != null) {
            if (ticks - wartDigAt > 60) wartDrop = null;
            else { if (!a.isNavigating() || ticks % 10 == 0) a.navigate(wartDrop.getX(), wartDrop.getY(), wartDrop.getZ(), 0); return true; }
        }
        if (wartGoal == null || ticks % 100 == 0) {
            wartGoal = null;
            double bd = Double.MAX_VALUE;
            for (BlockPos p : w.findAll((int) Math.floor(a.x()), (int) Math.floor(a.z()), 64, s -> s.getBlock() == Blocks.NETHER_WART, 64)) {
                double d = p.distToCenterSqr(a.x(), a.y(), a.z());
                if (d < bd) { bd = d; wartGoal = p; }
            }
            if (wartGoal == null) { a.log("[speedrun] no nether wart in sight"); return false; }
        }
        BlockState s = w.get(wartGoal.getX(), wartGoal.getY(), wartGoal.getZ());
        if (s == null || s.getBlock() != Blocks.NETHER_WART) { wartGoal = null; return true; }
        status = "falloff: picking nether wart (" + count(a, "minecraft:nether_wart") + "/" + WART_WANTED + ")";
        double d = Math.sqrt(wartGoal.distToCenterSqr(a.x(), a.y() + 1.62, a.z()));
        if (d > 4) { if (!a.isNavigating() || ticks % 20 == 0) a.navigate(wartGoal.getX(), wartGoal.getY(), wartGoal.getZ(), 2); return true; }
        if (a.isDigging()) return true;
        if (a.isNavigating()) a.stopNavigation();
        a.lookAt(wartGoal.getX() + 0.5, wartGoal.getY() + 0.3, wartGoal.getZ() + 0.5);
        a.dig(wartGoal.getX(), wartGoal.getY(), wartGoal.getZ());
        wartDrop = wartGoal; wartDigAt = ticks; wartGoal = null;
        return true;
    }

    private boolean teamGathered;
    private int rodsAsked;
    private int gatherFrom = -1;
    private String gatherSaid;

    private void blazes(AgentHandle a) {
        if (ticks % 40 == 0) {
            int seen = 0;
            for (Seen s : a.entitiesNear(24)) if (s.name().equals("blaze")) seen++;
            dev.agentnet.AgentNet.LOG.info("[{}] nether: {} | hp {} | shield {} (server: {}) | blazes {} | swings {} | hit lately {} | at {} {} {}", a.name(), status, a.health(), blocking, a.usingItem(), seen, swings, a.hitRecently(blazeId, 3000),
                    String.format("%.1f", a.x()), String.format("%.1f", a.y()), String.format("%.1f", a.z()));
        }
        mine(a, null);
        lastX = a.x(); lastZ = a.z();
        // Fire resistance (a bastion's, or bartered from piglins) before the blazes: the burning is what kills, more
        // than the fireballs. Drunk once a spawner or a blaze is about, again when it runs out.
        if (ticks < drinkUntil) { status = "drinking fire resistance"; return; }
        if (!a.hasEffect("fire_resistance") && (spawner != null || !a.entitiesNear(24).stream().noneMatch(s -> s.name().equals("blaze")))) {
            int slot = Potions.find(a, "fire_resistance", false);
            if (slot >= 0) {
                if (blocking) { a.releaseUse(); blocking = false; }
                if (a.isNavigating()) a.stopNavigation();
                a.log("[speedrun] drinking fire resistance before the blazes");
                drinkUntil = ticks + Potions.use(a, slot);
                return;
            }
        }
        int rods = count(a, "minecraft:blaze_rod");
        boolean main = MAIN != null && MAIN.equalsIgnoreCase(a.name());
        boolean fall = !main && a.role() == Role.FALLOFF;
        if (fall) FALLOFF_RODS.put(a.name(), rods);
        // A falloff asks for its rods to be fetched every 16 it has (the user, 2026-11-20: "so that if they die most of the
        // blaze rods are saved"): a pickup request to the nearest distributor in the same dimension.
        if (fall && rods >= rodsAsked + 16) {
            rodsAsked = rods - rods % 16;
            AgentHandle best = null;
            for (AgentHandle h : dev.agentnet.bus.AgentBus.agents.get())
                if (h.isOnline() && h.role() == Role.DISTRIBUTOR && AgentHandle.sameDim(h.dimension(), a.dimension())
                        && (best == null || dev.agentnet.bus.AgentBus.distSq(a, h) < dev.agentnet.bus.AgentBus.distSq(a, best))) best = h;
            if (best != null) {
                dev.agentnet.bus.AgentBus.send(dev.agentnet.bus.Message.of(a.name(), best.name(), dev.agentnet.bus.Message.Type.INVENTORY_FULL, rods + " blaze rods to keep safe",
                        java.util.Map.of("x", String.valueOf((int) Math.floor(a.x())), "y", String.valueOf((int) Math.floor(a.y())), "z", String.valueOf((int) Math.floor(a.z())), "fill", "blaze_rod")));
                a.log("[speedrun] " + rods + " blaze rods: asked " + best.name() + " to fetch them (safe if it dies)");
            } else a.log("§e[speedrun] " + rods + " blaze rods and no distributor in this dimension to fetch them: keeping them for now");
        }
        if ((main ? rods : mainRods) >= RODS) { if (!fall) { pearls(a); return; } if (falloff(a)) return; }
        if (!(a.openWorld() instanceof BlockView w)) return;
        // Hoglins, and piglins while it wears no gold: round them, never through them. Walking on toward its fortress it
        // went into a crimson forest, swung at a piglin and a hoglin, and was dead 26 seconds after entering the nether
        // (run 1119b, 70 minutes in, the spawner just found). Not while it stands at the spawner fighting blazes.
        if (spawner == null || spawner.distToCenterSqr(a.x(), a.y(), a.z()) > 24 * 24) {
            boolean gold = false;
            for (ItemStack s : a.armor()) if (Gear.id(s).contains("golden_")) gold = true;
            Seen beast = null;
            for (Seen s : a.entitiesNear(14)) {
                String nm = s.name();
                if (!(nm.equals("hoglin") || nm.equals("piglin_brute") || nm.equals("piglin") && !gold)) continue;
                if (beast == null || s.distance() < beast.distance()) beast = s;
            }
            if (beast != null) {
                foe = -1;
                if (blocking) { a.releaseUse(); blocking = false; }
                double bdx = a.x() - beast.pos().x, bdz = a.z() - beast.pos().z, bd = Math.max(0.5, Math.hypot(bdx, bdz));
                if (!a.isNavigating() || ticks % 20 == 0) a.navigate((int) Math.floor(a.x() + bdx / bd * 20), (int) Math.floor(a.y() + 0.2), (int) Math.floor(a.z() + bdz / bd * 20), 4);
                hopAt = ticks; hopGoal = null; // no hop back toward it right away
                status = "keeping away from a " + beast.name().replace('_', ' ');
                return;
            }
        }
        if (!main && rods > 0 && !(fall && mainRods >= RODS) && handOver(a, "minecraft:blaze_rod")) return; // a falloff keeps its own once the main has its rods
        // A rod where the last blaze fell: walk over it (items are picked up by walking into them). Not with a blaze close
        // by: that one first.
        // A lit blaze in sight (burning: it is charging a volley): the shield goes to that one, before anything else.
        Seen lit = null;
        for (Seen s : a.entitiesNear(20)) {
            if (!s.name().equals("blaze") || !s.swelling()) continue;
            if (!Sight.clear(w, a.x(), a.y() + 1.62, a.z(), s.pos().x, s.pos().y + 0.9, s.pos().z)) continue;
            if (lit == null || s.distance() < lit.distance()) lit = s;
        }
        // The tank holds its post only where there is something to hold: a blaze about, or the spawner found. Until then it
        // goes with the rest (run 1119a: it stood in the portal, shield up, "holding the blazes", with no fortress yet).
        boolean blazeAbout = lit != null || spawner != null;
        if (!blazeAbout) for (Seen s : a.entitiesNear(24)) if (s.name().equals("blaze")) { blazeAbout = true; break; }
        if (!main && isTank(a) && blazeAbout) { tank(a, w, lit, rods); return; }
        if (rodDrop != null && lit == null) {
            if (blocking) { a.releaseUse(); blocking = false; }
            if (++rodTicks > 20 * 12 || rodTicks > 30 && a.itemsNear(1.5).isEmpty() && rodDrop.distToCenterSqr(a.x(), a.y(), a.z()) < 2.5 * 2.5) { rodDrop = null; if (a.isNavigating()) a.stopNavigation(); }
            else { status = "picking up a blaze rod (" + rods + "/" + RODS + ")"; if (!a.isNavigating() && rodTicks % 10 == 1) a.navigate(rodDrop.getX(), rodDrop.getY(), rodDrop.getZ(), 0); return; }
        }
        // A blaze it can see: up to it and hit it. (Too hurt: it lets the agent back off and eat, then comes back.)
        Seen blaze = null;
        for (Seen s : a.entitiesNear(24)) {
            if (!s.name().equals("blaze")) continue;
            if (!Sight.clear(w, a.x(), a.y() + 1.62, a.z(), s.pos().x, s.pos().y + 0.9, s.pos().z)) continue;
            // The one it is already hitting, while it can see it: a blaze takes two or three hits, and spread over
            // whichever was nearest, three blazes took one hit each and none died.
            if (s.id() == engagedId && s.distance() <= 6) { blaze = s; break; }
            // Blazes the tank has hit are busy with it: those first (they don't turn on whoever hits them from behind).
            boolean tagged = TAGGED.containsKey(s.id()), bestTagged = blaze != null && TAGGED.containsKey(blaze.id());
            if (blaze == null || tagged && !bestTagged || tagged == bestTagged && s.distance() < blaze.distance()) blaze = s;
        }
        if (boxEat(a, blaze)) return;
        // The spree. A blaze only fires while it is lit up (burning), three fireballs, then it goes dark again: the
        // shield is up, turned to the lit one, only then. The rest of the time it goes for the blaze it is after
        // (the same one until it dies) and hits it, every swing a critical one, shield down. (Shield up all the time
        // and sitting in a box by the spawner, it lived and killed nothing.)
        // The main, with helpers here: it stays back (it carries everything; it died in the thick of it with its rods).
        // About eight blocks from the spawner, shield to the lit ones, a swing only at one that comes right up to it.
        // Drops on the ground (blaze rods: half the blazes leave one): picked up whenever no blaze is lit and none is in
        // reach. Looking only when no blaze was in sight, it never did: there always was one, and the rods stayed there.
        if (lit == null && !(blaze != null && eyeDist(a, blaze) <= 3.1)) {
            net.minecraft.world.phys.Vec3 item = null;
            for (net.minecraft.world.phys.Vec3 v : a.itemsNear(main ? 4 : 8))
                if (item == null || v.distanceToSqr(a.x(), a.y(), a.z()) < item.distanceToSqr(a.x(), a.y(), a.z())) item = v;
            if (item != null && item.distanceToSqr(a.x(), a.y(), a.z()) > 0.8) {
                if (blocking) { a.releaseUse(); blocking = false; }
                if (!a.isNavigating() || ticks % 10 == 0) a.navigate((int) Math.floor(item.x), (int) Math.floor(item.y + 0.2), (int) Math.floor(item.z), 0);
                foe = blaze != null ? blaze.id() : -1;
                status = "picking up a drop (" + rods + "/" + RODS + " rods)";
                return;
            }
        }
        boolean hangBack = main && helpersHere(a) > 0;
        // Helpers on the way back (they died and come through its portal again): out of the fight until they are here.
        if (main && !hangBack && helpersAnywhere(a) > 0 && spawner != null) {
            foe = blaze != null ? blaze.id() : -1;
            if (lit != null && Gear.id(a.offhand()).equals("minecraft:shield")) { if (!blocking) { a.useOffhand(); blocking = true; } a.faceWhileMoving(lit.pos().x, lit.pos().y + 0.9, lit.pos().z); }
            else if (blocking) { a.releaseUse(); blocking = false; }
            double dx = a.x() - (spawner.getX() + 0.5), dz = a.z() - (spawner.getZ() + 0.5), d = Math.hypot(dx, dz);
            if (d < 18 && !a.isNavigating() && ticks % 20 == 0) { if (d < 0.5) { dx = 1; d = 1; } a.navigate((int) Math.floor(spawner.getX() + 0.5 + dx / d * 22), (int) Math.floor(a.y() + 0.2), (int) Math.floor(spawner.getZ() + 0.5 + dz / d * 22), 4); }
            status = "out of the fight until its helpers are back (" + rods + "/" + RODS + " rods)";
            return;
        }
        if (hangBack && blaze != null) {
            foe = blaze.id();
            boolean shieldIn = Gear.id(a.offhand()).equals("minecraft:shield");
            if (eyeDist(a, blaze) <= 3.1 && lit == null) {
                if (blocking) { a.releaseUse(); blocking = false; }
                spree = true; engage(a, blaze); spree = false;
                status = "hitting a blaze that came too close (" + rods + "/" + RODS + " rods)";
                return;
            }
            if (lit != null && shieldIn) { if (!blocking) { a.useOffhand(); blocking = true; } a.faceWhileMoving(lit.pos().x, lit.pos().y + 0.9, lit.pos().z); if (!a.isNavigating()) a.lookAt(lit.pos().x, lit.pos().y + 0.9, lit.pos().z); }
            else if (blocking) { a.releaseUse(); blocking = false; }
            keepBack(a);
            status = "staying back while the helpers kill blazes (" + rods + "/" + RODS + " rods)";
            return;
        }
        if (blaze != null && a.health() >= 6) {
            foe = blaze.id(); blazeId = blaze.id(); blazeAt = blaze.pos(); engagedId = blaze.id();
            boolean shield = Gear.id(a.offhand()).equals("minecraft:shield");
            boolean closing = eyeDist(a, blaze) > 3.1;
            if (closing && !a.airborne() && (!a.isNavigating() || ticks % 20 == 0)) {
                BlockPos g = groundUnder(w, blaze.pos());
                a.navigate(g.getX(), g.getY(), g.getZ(), 1);
            }
            if (lit != null && shield) {
                if (!blocking) { a.useOffhand(); blocking = true; }
                a.faceWhileMoving(lit.pos().x, lit.pos().y + 0.9, lit.pos().z);
                if (!closing) { if (a.isNavigating()) a.stopNavigation(); a.lookAt(lit.pos().x, lit.pos().y + 0.9, lit.pos().z); }
                status = "blocking a lit blaze (" + rods + "/" + RODS + " rods)";
                return;
            }
            if (blocking) { a.releaseUse(); blocking = false; }
            status = "killing blazes (" + rods + "/" + RODS + " rods)";
            if (closing) { a.faceWhileMoving(blaze.pos().x, blaze.pos().y + 0.9, blaze.pos().z); return; }
            spree = true;
            engage(a, blaze);
            spree = false;
            return;
        }
        foe = -1;
        if (blocking) { a.releaseUse(); blocking = false; }
        // The one it fought is gone (dead, or out of sight): its rod, if any, is on the ground under where it was.
        // Only one it was hitting a moment ago: a blaze that just flew out of sight left no rod, and it walked into
        // five others looking for one.
        if (blazeId >= 0) {
            boolean killed = a.hitRecently(blazeId, 4000);
            blazeId = -1;
            if (killed && blazeAt != null) { rodDrop = groundUnder(w, blazeAt); rodTicks = 0; blazeAt = null; return; }
            blazeAt = null;
        }
        // The fortress's spawner: wait by it (blazes come out of it every few seconds while a player is near).
        int fx = (int) Math.floor(a.x()), fz = (int) Math.floor(a.z());
        if (spawner == null && ticks % 40 == 0) {
            for (BlockPos p : w.findAll(fx, fz, 64, s -> s.getBlock() == Blocks.SPAWNER, 20)) {
                boolean fortress = false;
                for (int dx = -3; dx <= 3 && !fortress; dx++) for (int dy = -2; dy <= 2 && !fortress; dy++) for (int dz = -3; dz <= 3 && !fortress; dz++)
                    fortress = netherBrick(w.get(p.getX() + dx, p.getY() + dy, p.getZ() + dz));
                if (fortress) { spawner = p; a.log("§a[speedrun] found a blaze spawner at " + p.toShortString()); break; }
            }
        }
        if (spawner != null && main && helpersHere(a) > 0) { keepBack(a); status = "staying back while the helpers kill blazes (" + rods + "/" + RODS + " rods)"; return; }
        if (spawner != null) {
            status = "waiting by the blaze spawner (" + rods + "/" + RODS + " rods)";
            // Right beside it (speedrunners hug the spawner): the blazes it lets out are within reach at once.
            if (boxDone && boxAt != null) { if (!atBox() && !a.isNavigating() && ticks % 20 == 0) a.navigate(boxAt.getX(), boxAt.getY(), boxAt.getZ(), 0); else if (!atBox() && Math.hypot(a.x() - boxAt.getX() - 0.5, a.z() - boxAt.getZ() - 0.5) < 1.5) a.stepToward(boxAt.getX() + 0.5, boxAt.getZ() + 0.5); }
            else if (!atSpawner(a) && !a.isNavigating() && ticks % 20 == 0) a.navigate(spawner.getX(), spawner.getY(), spawner.getZ(), 1);
            return;
        }
        // Fortress in sight but no spawner yet: along its bricks, somewhere it hasn't been.
        if (ticks - brickAt > 20 * 20 || brickGoal != null && brickGoal.distToCenterSqr(a.x(), a.y(), a.z()) < 4 * 4) {
            brickGoal = null;
            List<BlockPos> bricks = w.findAll(fx, fz, 64, SpeedrunnerModule::netherBrick, 600);
            BlockPos best = null;
            double bd = -1;
            for (BlockPos b : bricks) {
                BlockState over = w.get(b.getX(), b.getY() + 1, b.getZ()), over2 = w.get(b.getX(), b.getY() + 2, b.getZ());
                if (over == null || over2 == null || !over.isAir() || !over2.isAir()) continue; // a floor it can stand on
                BlockPos key = new BlockPos(b.getX() >> 3, 0, b.getZ() >> 3);
                if (bricksSeen.contains(key)) continue;
                double d = b.distToCenterSqr(a.x(), a.y(), a.z());
                if (d > bd && d < 48 * 48) { bd = d; best = b; }
            }
            if (best != null) {
                brickAt = ticks;
                brickGoal = best.above();
                bricksSeen.add(new BlockPos(best.getX() >> 3, 0, best.getZ() >> 3));
                if (bricksSeen.size() == 1) a.log("§a[speedrun] found a nether fortress: looking for its blaze spawner");
                a.navigate(brickGoal.getX(), brickGoal.getY(), brickGoal.getZ(), 2);
            }
        }
        // The whole team into the fortress at once (the user, 2026-11-20: "the speedrunner, speedrunner helpers and falloff
        // speedrunners should all enter the fortress at the same time"): the main waits at the first fortress bricks it sees
        // until every helper and falloff online is in the nether and within 16 blocks of it, four minutes at most.
        if (main && !teamGathered && !bricksSeen.isEmpty()) {
            if (gatherFrom < 0) gatherFrom = ticks;
            int team = 0, here = 0;
            for (AgentHandle h : dev.agentnet.bus.AgentBus.agents.get()) {
                if (h == a || !h.isOnline() || h.role() != Role.SPEEDRUNNER && h.role() != Role.FALLOFF) continue;
                team++;
                if (AgentHandle.sameDim(h.dimension(), a.dimension()) && dev.agentnet.bus.AgentBus.distSq(a, h) <= 16 * 16) here++;
            }
            if (here >= team || ticks - gatherFrom > 20 * 240) {
                teamGathered = true;
                a.log("§a[speedrun] " + (here >= team ? "the whole team is here (" + here + ")" : "waited four minutes (" + here + " of " + team + " here)") + ": into the fortress together");
            } else {
                if (a.isNavigating()) a.stopNavigation();
                status = "waiting for the team at the fortress (" + here + " of " + team + " here)";
                if (!status.equals(gatherSaid)) { gatherSaid = status; a.log("[speedrun] " + status); }
                return;
            }
        }
        if (brickGoal != null) { status = "walking the fortress for its blaze spawner (" + rods + "/" + RODS + " rods)"; return; }
        // Nothing yet: a hop over solid ground.
        status = "looking for a nether fortress";
        if (lavaBridge(a, w)) return;
        if (!a.isNavigating() && ticks - hopAt > 20 * 30) { hopAt = ticks; hopGoal = netherHop(a, w, 64); }
    }

    // ------------------------------------------------------------------ ender pearls: endermen, hunted where it finds them
    //
    // Twelve pearls (with the rods' fourteen eyes: the portal's twelve and a few lost finding it). Endermen are fought
    // like anything else: walked up to and hit (that makes one fight back instead of teleporting off for good), shield
    // up between swings. Where one it was hitting died, it walks over for the pearl (half of them drop one).

    static final int PEARLS = 12;
    private BlockPos pearlDrop;
    private net.minecraft.world.phys.Vec3 enderAt;
    private int enderId = -1, pearlTicks;

    private void pearls(AgentHandle a) {
        int pearls = count(a, "minecraft:ender_pearl");
        int rods = count(a, "minecraft:blaze_rod");
        boolean main = MAIN != null && MAIN.equalsIgnoreCase(a.name());
        if (!main) {
            if (rods > 0 && handOver(a, "minecraft:blaze_rod")) return;
            if (pearls > 0 && handOver(a, "minecraft:ender_pearl")) return;
            if (mainPearls >= PEARLS) { foe = -1; follow(a); return; }
        }
        if (main && pearls >= PEARLS) { foe = -1; if (a.isNavigating()) a.stopNavigation(); a.log("§a[speedrun] " + rods + " blaze rods and " + pearls + " ender pearls: home, then the stronghold"); go(a, Stage.STRONGHOLD); return; }
        if (!(a.openWorld() instanceof BlockView w)) return;
        // A piglin brute: a bastion. Away from it (one killed a helper that wandered in), all of them.
        Seen brute = null;
        for (Seen s : a.entitiesNear(16)) if (s.name().equals("piglin_brute") && (brute == null || s.distance() < brute.distance())) brute = s;
        if (brute != null) {
            foe = -1;
            if (blocking) { a.releaseUse(); blocking = false; }
            double dx = a.x() - brute.pos().x, dz = a.z() - brute.pos().z, d = Math.max(0.5, Math.hypot(dx, dz));
            if (!a.isNavigating() || ticks % 20 == 0) a.navigate((int) Math.floor(a.x() + dx / d * 20), (int) Math.floor(a.y() + 0.2), (int) Math.floor(a.z() + dz / d * 20), 4);
            hopAt = ticks; // no hop back toward it right away
            status = "keeping away from a piglin brute";
            return;
        }
        Seen ender = null;
        for (Seen s : a.entitiesNear(32)) {
            if (!s.name().equals("enderman")) continue;
            if (!Sight.clear(w, a.x(), a.y() + 1.62, a.z(), s.pos().x, s.pos().y + 1.4, s.pos().z)) continue;
            if (ender == null || s.id() == enderId || s.distance() < ender.distance() && ender.id() != enderId) ender = s;
        }
        if (boxEat(a, null)) return;
        // Gold and piglins about: trade for pearls (the user's way, a bastion's piglins). Before the endermen.
        if (count(a, "minecraft:gold_ingot") > 0 && barter(a, w, ender)) return;
        // Drops nearby (half the endermen leave a pearl): picked up when no enderman is right on it.
        if (!(ender != null && ender.distance() <= 3.5)) {
            net.minecraft.world.phys.Vec3 item = null;
            for (net.minecraft.world.phys.Vec3 v : a.itemsNear(main ? 4 : 10))
                if (item == null || v.distanceToSqr(a.x(), a.y(), a.z()) < item.distanceToSqr(a.x(), a.y(), a.z())) item = v;
            if (item != null && item.distanceToSqr(a.x(), a.y(), a.z()) > 0.8) {
                if (blocking) { a.releaseUse(); blocking = false; }
                if (!a.isNavigating() || ticks % 10 == 0) a.navigate((int) Math.floor(item.x), (int) Math.floor(item.y + 0.2), (int) Math.floor(item.z), 0);
                status = "picking up a drop (" + pearls + "/" + PEARLS + " pearls)";
                return;
            }
        }
        // Endermen are only fought in a boat (the user's way): one sitting in a boat can't teleport off. A helper with a
        // boat puts it down where a calm one stands, and it sits in it; then they kill it. One that is angry and right on
        // it is fought off wherever it is.
        Seen trapped = null, calm = null;
        java.util.Set<Integer> ridden = new java.util.HashSet<>();
        List<Seen> around = a.entitiesNear(32);
        for (Seen s : around) if (s.vehicle() >= 0) ridden.add(s.vehicle());
        for (Seen s : around) {
            if (!s.name().equals("enderman") || !Sight.clear(w, a.x(), a.y() + 1.62, a.z(), s.pos().x, s.pos().y + 1.4, s.pos().z)) continue;
            if (s.vehicle() >= 0) { if (trapped == null || s.distance() < trapped.distance()) trapped = s; }
            else if (!s.angry()) { if (calm == null || s.distance() < calm.distance()) calm = s; }
        }
        boolean helpers = helpersHere(a) > 0;
        if (ticks == boatCheckAt && boatTargetPos != null) {
            int boatsNear = 0; String sits = "gone";
            for (Seen s : around) {
                if ((s.name().endsWith("_boat") || s.name().endsWith("_raft")) && s.pos().distanceTo(boatTargetPos) < 4) boatsNear++;
                if (s.id() == boatTarget) sits = s.vehicle() >= 0 ? "in a boat" : "not in a boat, " + String.format("%.1f", s.pos().distanceTo(boatTargetPos)) + " from where it stood";
            }
            dev.agentnet.AgentNet.LOG.info("[{}] half a second later: {} boat(s) by that spot, the enderman {}", a.name(), boatsNear, sits);
        }
        if (ender != null && ender.angry() && ender.vehicle() < 0 && ender.distance() <= 4 && a.health() >= 6) {
            foe = ender.id(); status = "fighting off an angry enderman"; engage(a, ender); return;
        }
        if (trapped != null && !(main && helpers)) {
            foe = trapped.id(); enderId = trapped.id(); enderAt = trapped.pos();
            status = "killing an enderman in a boat (" + (main ? pearls : mainPearls) + "/" + PEARLS + " pearls)";
            spree = true; engage(a, trapped); spree = false;
            return;
        }
        foe = -1;
        if (blocking) { a.releaseUse(); blocking = false; }
        // An empty boat close by (its enderman is dead): broken, and the boat picked up again for the next one.
        for (Seen s : around) {
            if (!(s.name().endsWith("_boat") || s.name().endsWith("_raft")) || ridden.contains(s.id()) || s.distance() > 5) continue;
            if (s.distance() > 2.8) { if (!a.isNavigating() || ticks % 20 == 0) a.navigate((int) Math.floor(s.pos().x), (int) Math.floor(s.pos().y + 0.2), (int) Math.floor(s.pos().z), 1); }
            else if (ticks - boatHitAt > 6) { boatHitAt = ticks; if (a.isNavigating()) a.stopNavigation(); a.lookAt(s.pos().x, s.pos().y + 0.3, s.pos().z); a.attack(s.id()); }
            status = "taking its boat back";
            return;
        }
        if (main && helpers) { status = "staying back while the helpers trap endermen (" + mainPearls + "/" + PEARLS + " pearls)"; return; }
        int boat = -1;
        List<ItemStack> inv = a.inventory();
        for (int i = 0; i < inv.size(); i++) { String id = Gear.id(inv.get(i)); if (id.endsWith("_boat") || id.endsWith("_raft")) { boat = i; break; } }
        if (calm != null && boat >= 0) {
            // Close, then the boat on the ground right under it.
            if (calm.distance() > 3.2) {
                if (!a.isNavigating() || ticks % 20 == 0) a.navigate((int) Math.floor(calm.pos().x), (int) Math.floor(calm.pos().y + 0.2), (int) Math.floor(calm.pos().z), 2);
                status = "taking a boat to an enderman";
                return;
            }
            if (a.isNavigating()) a.stopNavigation();
            if (ticks - boatPlacedAt < 30) { status = "waiting for the enderman to sit in its boat"; return; }
            boatPlacedAt = ticks;
            if (boat >= 9) { a.moveToHotbar(boat, 8); boat = 8; }
            a.selectHotbar(boat);
            // Right beside it, not under it: a boat can't be put where it would overlap a mob that can be pushed (the server
            // refused every one), but one within a hair of it pulls it in on its next tick. Boat half 0.69 wide, enderman
            // half 0.3: 1.08 from its middle, on the side toward the agent.
            double bdx = a.x() - calm.pos().x, bdz = a.z() - calm.pos().z;
            double tx = calm.pos().x, tz = calm.pos().z;
            if (Math.abs(bdx) >= Math.abs(bdz)) tx += Math.signum(bdx) * 1.08; else tz += Math.signum(bdz) * 1.08;
            a.lookAt(tx, Math.floor(calm.pos().y + 0.01) + 0.02, tz); // the top of the block beside it
            a.useItem();
            boatCheckAt = ticks + 10; boatTarget = calm.id(); boatTargetPos = calm.pos();
            int carried = 0;
            for (ItemStack s : a.inventory()) { String id = Gear.id(s); if (id.endsWith("_boat") || id.endsWith("_raft")) carried += s.getCount(); }
            dev.agentnet.AgentNet.LOG.info("[{}] boat placed at the feet of enderman #{} (in hand: slot {} {}; {} blocks off, {} boats carried, eye {} {} {}, aim pitch {})", a.name(), calm.id(), a.selectedSlot(), Gear.id(a.inventory().get(Math.max(0, a.selectedSlot()))),
                    String.format("%.1f", calm.distance()), carried, String.format("%.1f", a.x()), String.format("%.1f", a.y() + 1.62), String.format("%.1f", a.z()), String.format("%.0f", a.pitch()));
            status = "putting a boat under an enderman";
            return;
        }
        if (calm != null && boat < 0) { status = "out of boats: endermen only in boats"; }
        foe = -1;
        if (blocking) { a.releaseUse(); blocking = false; }
        // None in sight: the helpers stay with the main, and the main picks where to go (they scattered across the
        // nether one by one, and died one by one).
        if (!main && MAIN != null && online(MAIN)) { follow(a); status = "with " + MAIN + ", looking for endermen"; return; }
        status = "looking for endermen (" + pearls + "/" + PEARLS + " pearls, " + rods + " rods)";
        if (nether(a) && lavaBridge(a, w)) return;
        if (!a.isNavigating() && ticks - hopAt > 20 * 30) { hopAt = ticks; if (nether(a)) hopGoal = netherHop(a, w, 48); else wanderOnLand(a, w, 48); }
    }

    // ------------------------------------------------------------------ bartering: gold for pearls at a bastion
    //
    // A piglin given a gold ingot looks at it for about six seconds and drops something: pearls about one time in
    // twenty, two to four of them. One gold piece worn keeps them from attacking. Each piglin takes one ingot at a time:
    // the next one goes to the next piglin.

    private final java.util.Map<Integer, Integer> piglinBusy = new java.util.HashMap<>();
    private int barterAt = -100, barters;

    /** True while it is busy trading (or getting ready to). */
    private boolean barter(AgentHandle a, BlockView w, Seen ender) {
        if (ender != null && ender.angry() && ender.distance() < 6) return false; // a fight first
        piglinBusy.values().removeIf(t -> t < ticks);
        Seen piglin = null;
        for (Seen s : a.entitiesNear(24)) {
            if (!s.name().equals("piglin") || piglinBusy.containsKey(s.id())) continue;
            if (!Sight.clear(w, a.x(), a.y() + 1.62, a.z(), s.pos().x, s.pos().y + 1.0, s.pos().z)) continue;
            if (piglin == null || s.distance() < piglin.distance()) piglin = s;
        }
        if (piglin == null) return false;
        // One gold piece worn, or they attack.
        boolean wearsGold = false;
        for (ItemStack s : a.armor()) if (Gear.id(s).startsWith("minecraft:golden_")) wearsGold = true;
        if (!wearsGold) {
            List<ItemStack> inv = a.inventory();
            int g = -1;
            for (int i = 0; i < inv.size(); i++) { String id = Gear.id(inv.get(i)); if (id.equals("minecraft:golden_helmet") || id.equals("minecraft:golden_boots")) { g = i; break; } }
            if (g >= 0) {
                if (ticks - barterAt < 10) return true;
                barterAt = ticks;
                if (g >= 9) { a.moveToHotbar(g, 8); g = 8; }
                a.selectHotbar(g);
                a.useItem(); // into its armor slot (the iron one comes off into the pack)
                status = "putting on gold so the piglins leave it alone";
                return true;
            }
            if (count(a, "minecraft:gold_ingot") >= 5 && !crafter.busy() && ticks - craftAt > 200) {
                craftAt = ticks;
                crafter.onTask(a, "make golden_helmet 1");
                status = "making a gold helmet for the piglins";
                return true;
            }
        }
        foe = -1;
        if (blocking) { a.releaseUse(); blocking = false; }
        if (piglin.distance() > 2.6) {
            if (!a.isNavigating() || ticks % 20 == 0) a.navigate((int) Math.floor(piglin.pos().x), (int) Math.floor(piglin.pos().y + 0.2), (int) Math.floor(piglin.pos().z), 1);
            status = "going to trade gold with a piglin (" + count(a, "minecraft:gold_ingot") + " gold)";
            return true;
        }
        if (a.isNavigating()) a.stopNavigation();
        if (ticks - barterAt < 10) return true;
        List<ItemStack> inv = a.inventory();
        int slot = -1;
        for (int i = 0; i < inv.size(); i++) if (Gear.id(inv.get(i)).equals("minecraft:gold_ingot")) { slot = i; break; }
        if (slot < 0) return false;
        a.lookAt(piglin.pos().x, piglin.pos().y + 0.3, piglin.pos().z);
        a.dropOne(slot);
        barterAt = ticks;
        piglinBusy.put(piglin.id(), ticks + 20 * 8);
        if (++barters % 8 == 1) a.log("[speedrun] trading gold with the piglins (" + barters + " so far, " + count(a, "minecraft:ender_pearl") + " pearls)");
        status = "trading gold with a piglin (" + (count(a, "minecraft:gold_ingot") - 1) + " gold left)";
        return true;
    }

    // ------------------------------------------------------------------ the stronghold: home through its portal, eyes, follow them
    //
    // Back through the portal it came out of. Rods into blaze powder, pearl and powder into eyes. An eye thrown flies
    // toward the stronghold for a few seconds: the way it went is the way to walk (about 120 blocks), then the next one.
    // One that drops straight down instead of flying off: the stronghold is under it. Most eyes come back down as items:
    // picked up where they fell.

    private BlockPos netherPortal, eyeDrop, legGoal;
    private net.minecraft.world.phys.Vec3 eyeFirst, eyeLast;
    private int eyeId = -1, thrownAt = -10000, eyeFirstAt, legAt, eyeTicks, craftAt = -1000;
    private boolean strongholdHere;
    private int legLen = 120;
    private double lastDirX, lastDirZ;

    private void stronghold(AgentHandle a) {
        foe = -1;
        if (!(a.openWorld() instanceof BlockView w)) return;
        if (nether(a)) {
            // Home: to the portal it came out of, and into it.
            if (netherPortal == null) { status = "can't remember where its portal is"; return; }
            BlockState here = w.get(netherPortal.getX(), netherPortal.getY(), netherPortal.getZ());
            double d = Math.hypot(a.x() - (netherPortal.getX() + 0.5), a.z() - (netherPortal.getZ() + 0.5));
            status = "going home through its portal at " + netherPortal.toShortString();
            if (d < 1.2 && Math.abs(a.y() - netherPortal.getY()) < 1.5) { if (a.isNavigating()) a.stopNavigation(); a.stepToward(netherPortal.getX() + 0.5, netherPortal.getZ() + 0.5); return; }
            if (!a.isNavigating() && ticks % 20 == 0) a.navigate(netherPortal.getX(), netherPortal.getY(), netherPortal.getZ(), 0);
            if (here != null && here.getBlock() != Blocks.NETHER_PORTAL && ticks % 600 == 0) a.log("§e[speedrun] its portal at " + netherPortal.toShortString() + " isn't lit any more");
            return;
        }
        if (a.dimension() != null && a.dimension().endsWith("the_end")) { go(a, Stage.END); return; }
        if (portalRoom(a, w)) return;
        if (strongholdHere) {
            // Under here, but the room is not in its chunks yet: down to stronghold depth (it tunnels), then look again.
            int ty = Math.max(a.y() > 40 ? 30 : (int) Math.floor(a.y()) - 8, 12);
            status = "digging down to the stronghold (y " + Math.round(a.y()) + " -> " + ty + ")";
            if (!staircase(a, w, ty) && ticks % 200 == 0) a.log("[speedrun] at stronghold depth and still no portal room in sight");
            return;
        }
        int pearls = count(a, "minecraft:ender_pearl"), powder = count(a, "minecraft:blaze_powder"), rods = count(a, "minecraft:blaze_rod");
        int eyes = count(a, "minecraft:ender_eye");
        // Eyes first: as many as it has pearls.
        if (pearls > 0 && (powder > 0 || rods > 0)) {
            if (crafter.busy()) return;
            if (ticks - craftAt > 100) {
                craftAt = ticks;
                if (powder < pearls && rods > 0) { crafter.onTask(a, "make blaze_powder " + Math.min(rods * 2, pearls - powder + 1)); status = "making blaze powder"; }
                else { crafter.onTask(a, "make ender_eye " + Math.min(pearls, powder)); status = "making eyes of ender"; }
            }
            return;
        }
        // An eye that came down: pick it up.
        if (eyeDrop != null) {
            if (++eyeTicks > 20 * 12 || eyeTicks > 30 && a.itemsNear(1.5).isEmpty() && eyeDrop.distToCenterSqr(a.x(), a.y(), a.z()) < 2.5 * 2.5) { eyeDrop = null; if (a.isNavigating()) a.stopNavigation(); }
            else { status = "picking up its eye of ender"; if (!a.isNavigating() && eyeTicks % 10 == 1) a.navigate(eyeDrop.getX(), eyeDrop.getY(), eyeDrop.getZ(), 0); return; }
        }
        // The eye in the air: where it goes.
        if (ticks - thrownAt < 20 * 6) {
            Seen eye = null;
            for (Seen s : a.entitiesNear(24)) if (s.name().equals("eye_of_ender") && (eyeId < 0 || s.id() == eyeId)) { eye = s; break; }
            if (eye != null) {
                if (eyeId < 0) { eyeId = eye.id(); eyeFirst = eye.pos(); eyeFirstAt = ticks; }
                eyeLast = eye.pos();
                return;
            }
            if (eyeId < 0) return; // not seen yet
            // Gone (broke, or dropped as an item): judge the flight.
            finishFlight(a, w);
            return;
        }
        if (eyeId >= 0) { finishFlight(a, w); return; }
        // On the way to the next throw.
        if (legGoal != null) {
            boolean there = legGoal.distToCenterSqr(a.x(), legGoal.getY(), a.z()) < 10 * 10;
            if (!there && ticks - legAt < 20 * 120) { status = "following the eye: " + Math.round(Math.sqrt(legGoal.distToCenterSqr(a.x(), legGoal.getY(), a.z()))) + " blocks to go (" + eyes + " eyes)"; if (!a.isNavigating() && ticks % 20 == 0) a.navigate(legGoal.getX(), legGoal.getY(), legGoal.getZ(), 6); return; }
            legGoal = null;
        }
        if (eyes == 0) { status = "out of eyes of ender: needs more pearls and blaze powder"; return; }
        // Throw: level, straight ahead.
        if (a.isNavigating()) a.stopNavigation();
        if (!hold(a, "minecraft:ender_eye")) return;
        double yaw = Math.random() * Math.PI * 2;
        a.lookAt(a.x() + Math.cos(yaw) * 10, a.y() + 1.62 + 2, a.z() + Math.sin(yaw) * 10);
        a.useItem();
        thrownAt = ticks; eyeId = -1; eyeFirst = null; eyeLast = null;
        status = "threw an eye of ender (" + (eyes - 1) + " left)";
    }

    // ------------------------------------------------------------------ the portal room

    private int frameAt = -100, portalSeenAt = -1;

    private static boolean hasEye(BlockState s) {
        return s.getBlock() == Blocks.END_PORTAL_FRAME && s.getValue(net.minecraft.world.level.block.EndPortalFrameBlock.HAS_EYE);
    }

    /**
     * The end portal: its frames in the chunks it knows (they come whole, underground too, so from up top it sees them
     * once it is close): to them (it tunnels down), an eye into every empty one, then into the portal. True while busy.
     */
    private boolean portalRoom(AgentHandle a, BlockView w) {
        int ax = (int) Math.floor(a.x()), az = (int) Math.floor(a.z());
        if (ticks % 20 != 0 && roomFrames == null) return false;
        List<BlockPos> portal = w.findAll(ax, az, 96, s -> s.getBlock() == Blocks.END_PORTAL, 9);
        if (!portal.isEmpty()) {
            BlockPos p = portal.get(0);
            for (BlockPos q : portal) if (q.distToCenterSqr(a.x(), a.y(), a.z()) < p.distToCenterSqr(a.x(), a.y(), a.z())) p = q;
            if (portalSeenAt < 0) { portalSeenAt = ticks; a.log("§a[speedrun] the end portal is open at " + p.toShortString()); }
            if (MAIN != null && MAIN.equalsIgnoreCase(a.name())) mainEndPortal = p;
            status = "into the end portal";
            // From the frame it stands on (one up from the portal), a step in: it drops into it.
            double d = Math.hypot(a.x() - (p.getX() + 0.5), a.z() - (p.getZ() + 0.5));
            if (d < 2.2 && a.y() >= p.getY() - 0.2) { if (a.isNavigating()) a.stopNavigation(); a.stepToward(p.getX() + 0.5, p.getZ() + 0.5); return true; }
            if (!a.isNavigating() && ticks % 20 == 0) a.navigate(p.getX(), p.getY() + 1, p.getZ(), 2);
            return true;
        }
        if (ticks % 20 == 0 || roomFrames == null) roomFrames = w.findAll(ax, az, 96, s -> s.getBlock() == Blocks.END_PORTAL_FRAME, 16);
        if (roomFrames.size() < 12) { roomFrames = null; return false; }
        List<BlockPos> empty = new ArrayList<>();
        for (BlockPos f : roomFrames) { BlockState s = w.get(f.getX(), f.getY(), f.getZ()); if (s != null && s.getBlock() == Blocks.END_PORTAL_FRAME && !hasEye(s)) empty.add(f); }
        if (empty.isEmpty()) { status = "all twelve eyes in: waiting for the portal"; return true; }
        int eyes = count(a, "minecraft:ender_eye");
        if (eyes < empty.size()) { status = "the portal needs " + empty.size() + " more eyes, it has " + eyes; return true; }
        BlockPos f = empty.get(0);
        for (BlockPos q : empty) if (q.distToCenterSqr(a.x(), a.y() + 1.62, a.z()) < f.distToCenterSqr(a.x(), a.y() + 1.62, a.z())) f = q;
        double ex = f.getX() + 0.5 - a.x(), ey = f.getY() + 0.9 - (a.y() + 1.62), ez = f.getZ() + 0.5 - a.z();
        double reach = Math.sqrt(ex * ex + ey * ey + ez * ez);
        status = "putting eyes in the portal (" + empty.size() + " to go)";
        if (reach > 4.2) {
            // Down from dry land. Over a lake it tunnelled down from the lake bed and swam up for air again and again,
            // stuck at the bottom for a minute and a half: first onto land near the room, then down from there.
            int ax2 = (int) Math.floor(a.x()), az2 = (int) Math.floor(a.z());
            if (!dryDone && dryStart == null && (wet(w, ax2, (int) Math.floor(a.y()), az2) || wet(w, ax2, surface(w, ax2, az2), az2))) {
                dryStart = dryLand(w, f.getX(), f.getZ());
                if (dryStart != null) { dryAt = ticks; a.log("[speedrun] water over the stronghold: digging down from dry land at " + dryStart.toShortString()); }
                else dryDone = true;
            }
            if (dryStart != null && !dryDone && ticks - dryAt > 20 * 30) { dryDone = true; a.log("[speedrun] can't get to dry land: digging down from here"); }
            if (dryStart != null && !dryDone) {
                status = "onto dry land to dig down to the portal room";
                if (Math.hypot(a.x() - (dryStart.getX() + 0.5), a.z() - (dryStart.getZ() + 0.5)) < 1.5 && Math.abs(a.y() - dryStart.getY()) < 2) { dryDone = true; if (a.isNavigating()) a.stopNavigation(); }
                else if (!a.isNavigating() && ticks % 20 == 0) a.navigate(dryStart.getX(), dryStart.getY(), dryStart.getZ(), 1);
                return true;
            }
            // Far above the room: its own staircase down (the walker only tunnels sideways and up: told to go 70 blocks
            // down it shuffled about in a cave and never got deeper).
            if (a.y() > f.getY() + 1.5 && shaftIntoRoom(a, w, roomFrames)) return true;
            if (a.y() > f.getY() + 1.5 && staircase(a, w, f.getY() + 1)) { status = "digging a staircase down to the portal room (y " + Math.round(a.y()) + " -> " + (f.getY() + 1) + ")"; return true; }
            // To the room: next to the frame, on its outer side (the middle is the drop over lava).
            if (!a.isNavigating() && ticks % 20 == 0) a.navigate(f.getX(), f.getY() + 1, f.getZ(), 2);
            return true;
        }
        if (a.isNavigating()) a.stopNavigation();
        if (ticks - frameAt < 6) return true;
        if (!hold(a, "minecraft:ender_eye")) return true;
        frameAt = ticks;
        a.lookAt(f.getX() + 0.5, f.getY() + 0.8125, f.getZ() + 0.5);
        a.useItemOn(f.getX(), f.getY(), f.getZ(), Direction.UP);
        return true;
    }
    private List<BlockPos> roomFrames;

    // ------------------------------------------------------------------ straight down into the portal room
    //
    // The room is in its chunks, so the whole way down can be checked first: a spot in the room beside a frame (floor
    // under it, room for a player), and the column over it from the surface: no lava, no water, no drop of more than a
    // few blocks before the room. Then from the top of that column it digs straight down and lands beside the frames.
    // (A staircase down landed it in the stronghold's walls, where its picture of the blocks went wrong and it
    // suffocated.)

    private BlockPos shaftCol;
    private boolean shaftNone;
    private int shaftDigAt, shaftWalkAt = -1;

    /** True while it is going down its checked column. */
    private boolean shaftIntoRoom(AgentHandle a, BlockView w, List<BlockPos> frames) {
        if (shaftNone) return false;
        if (shaftCol == null) {
            shaftCol = pickColumn(w, frames);
            if (shaftCol == null) { shaftNone = true; a.log("[speedrun] no safe straight way down into the portal room: a staircase then"); return false; }
            shaftWalkAt = -1;
            a.log("[speedrun] straight down at " + shaftCol.toShortString() + " (checked: no lava, no water, no big drop)");
        }
        int cx = shaftCol.getX(), cz = shaftCol.getZ();
        boolean over = (int) Math.floor(a.x()) == cx && (int) Math.floor(a.z()) == cz;
        if (!over && a.y() < surface(w, cx, cz) - 2) return false; // underground already (it came back in a tunnel): its staircase from here
        if (!over) {
            int top = surface(w, cx, cz) + 1;
            status = "to the top of its way down";
            double d = Math.hypot(a.x() - (cx + 0.5), a.z() - (cz + 0.5));
            if (d < 1.3 && Math.abs(a.y() - top) < 2) { if (a.isNavigating()) a.stopNavigation(); a.stepToward(cx + 0.5, cz + 0.5); return true; }
            if (!a.isNavigating() && ticks % 20 == 0) a.navigate(cx, top, cz, 0);
            if (shaftWalkAt < 0) shaftWalkAt = ticks;
            if (ticks - shaftWalkAt > 1200) { shaftNone = true; a.log("[speedrun] can't get over its way down: a staircase then"); }
            return true;
        }
        if (a.y() <= shaftCol.getY() + 0.2) return false; // in the room
        if (a.isNavigating()) a.stopNavigation();
        status = "digging straight down into the portal room (y " + Math.round(a.y()) + " -> " + shaftCol.getY() + ")";
        if (a.isDigging() || a.airborne()) return true;
        if (Math.hypot(a.x() - (cx + 0.5), a.z() - (cz + 0.5)) > 0.2) { a.stepToward(cx + 0.5, cz + 0.5); return true; }
        int fy = (int) Math.floor(a.y() + 0.01);
        BlockPos b = new BlockPos(cx, fy - 1, cz);
        BlockState s = w.get(b.getX(), b.getY(), b.getZ());
        if (s == null) return true;
        if (!s.getFluidState().isEmpty()) { shaftNone = true; a.log("[speedrun] liquid in its way down after all: a staircase then"); return false; }
        if (Rules.canWalkThrough(s)) return true; // falling
        if (ticks - shaftDigAt < 3) return true;
        shaftDigAt = ticks;
        Gear.holdBestFor(a, s);
        a.lookAt(cx + 0.5, fy - 0.5, cz + 0.5);
        a.dig(b.getX(), b.getY(), b.getZ());
        return true;
    }

    /** A spot in the room beside a frame with a safe column over it, nearest the frames' middle first, or null. */
    private static BlockPos pickColumn(BlockView w, List<BlockPos> frames) {
        double mx = 0, mz = 0;
        for (BlockPos f : frames) { mx += f.getX(); mz += f.getZ(); }
        mx /= frames.size(); mz /= frames.size();
        int fy = frames.get(0).getY();
        List<BlockPos> spots = new ArrayList<>();
        for (BlockPos f : frames) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                for (int up = 0; up <= 1; up++) {
                    BlockPos p = f.relative(d).above(up);
                    if (spots.contains(p)) continue;
                    BlockState at = w.get(p.getX(), p.getY(), p.getZ()), head = w.get(p.getX(), p.getY() + 1, p.getZ()), under = w.get(p.getX(), p.getY() - 1, p.getZ());
                    if (at == null || head == null || under == null) continue;
                    if (!at.isAir() || !head.isAir() || !solid(under) || under.getBlock() == Blocks.END_PORTAL_FRAME && up == 0) continue;
                    spots.add(p);
                }
            }
        }
        final double fmx = mx, fmz = mz;
        spots.sort(java.util.Comparator.comparingDouble(p -> -((p.getX() - fmx) * (p.getX() - fmx) + (p.getZ() - fmz) * (p.getZ() - fmz)))); // the outside of the ring first (the inside is over lava)
        for (BlockPos p : spots) if (safeColumn(w, p)) return p;
        return null;
    }

    /** From the surface down to this spot: no liquid at all, and no open drop longer than 4 before the room. */
    private static boolean safeColumn(BlockView w, BlockPos p) {
        int top = surface(w, p.getX(), p.getZ());
        int air = 0;
        for (int y = top; y > p.getY() + 1; y--) {
            BlockState s = w.get(p.getX(), y, p.getZ());
            if (s == null || !s.getFluidState().isEmpty() || s.getBlock() == Blocks.BEDROCK || Rules.dangerous(s)) return false;
            // Next to lava or water: digging opens it into the shaft.
            for (Direction d : Direction.Plane.HORIZONTAL) { BlockState n = w.get(p.getX() + d.getStepX(), y, p.getZ() + d.getStepZ()); if (n != null && !n.getFluidState().isEmpty()) return false; }
            if (Rules.canWalkThrough(s)) { if (++air > 4 && y > p.getY() + 4) return false; } else air = 0;
        }
        return true;
    }

    // ------------------------------------------------------------------ its own way down
    //
    // A spiral staircase like the crew's shaft: a 4x4 ring, a block lower each step, three blocks of head room. Water or
    // lava in a step gets a cobblestone plugged into it first (then dug out like stone).

    private static final int[][] STAIR = {{0, 0}, {1, 0}, {2, 0}, {3, 0}, {3, 1}, {3, 2}, {3, 3}, {2, 3}, {1, 3}, {0, 3}, {0, 2}, {0, 1}};
    private BlockPos stairOrigin;
    private int stairK, stairAt;

    /** True while it is digging its staircase down to this height. */
    private boolean staircase(AgentHandle a, BlockView w, int targetY) {
        // Started again only when it is well below where it should be (a drop into a cave: on from where it landed) or far
        // above it. Its hop down onto the next step puts it over 1.5 above for a moment, and that started a new staircase
        // at every step (run 1120m: "digging a staircase down from ..." every 2-10 s, a block further along each time).
        double stairOff = stairOrigin == null ? 0 : a.y() - (stairOrigin.getY() - stairK);
        if (stairOrigin == null || stairOff < -1.5 || stairOff > 3.5) {
            stairOrigin = BlockPos.containing(a.x(), a.y() + 0.01, a.z());
            stairK = 0;
            stairAt = ticks; // (left as it was, a restart counted as stuck at once and logged so every tick)
            dryDone = true; // on its way down now: water it meets is plugged, not a reason to go back up to dry land
            a.log("[speedrun] digging a staircase down from " + stairOrigin.toShortString() + " to y " + targetY);
        }
        int[] c = STAIR[stairK % STAIR.length];
        BlockPos feet = new BlockPos(stairOrigin.getX() + c[0], stairOrigin.getY() - stairK, stairOrigin.getZ() + c[1]);
        if (feet.getY() < targetY) return false;
        if (a.isNavigating()) a.stopNavigation();
        // Its own steps, not a dig-out the stuck watch started: while one runs its steps do nothing (stepToward waits for it),
        // so it stood beside the next step, "stuck", and the watch started another (run 1120zf, 2 minutes on one step).
        if (a.escaping()) a.cancelEscape();
        if (a.isDigging()) return true;
        if ((int) Math.floor(a.x()) == feet.getX() && (int) Math.floor(a.z()) == feet.getZ() && Math.abs(a.y() - feet.getY()) < 0.6) { stairK++; stairAt = ticks; return true; }
        // Over its step but higher, with air under it: it hovers (the fall only starts when it moves, and it is already
        // where it wants to be; the server said "standing on air" every 10 s). A hop puts it in the air and it falls.
        if ((int) Math.floor(a.x()) == feet.getX() && (int) Math.floor(a.z()) == feet.getZ() && a.y() - feet.getY() >= 0.6 && !a.airborne()) {
            // The block right under its feet (a.y 37.0 stands on y 36; the "- 1" here read y 35, solid copper ore, and it
            // hovered over its step for 30 s until the staircase was thrown away, 4 blocks above the pool: run 1120g).
            BlockState below = w.get(feet.getX(), (int) Math.floor(a.y() - 0.01), feet.getZ());
            if (below != null && Rules.canWalkThrough(below) && below.getFluidState().isEmpty()) {
                // On the very edge of its step (x 61.07: its body still on the step before, 1.8 up), a hop lands it back on the
                // same edge: 30-40 s on every step (run 1120n). Into the middle of the step first, then the hop.
                double cx = feet.getX() + 0.5 - a.x(), cz = feet.getZ() + 0.5 - a.z();
                if (cx * cx + cz * cz > 0.04) a.stepToward(feet.getX() + 0.5, feet.getZ() + 0.5);
                else if (ticks % 10 == 0) a.jump();
                return true;
            }
        }
        if (ticks - stairAt > 20 * 30) { // stuck on this step: say why, start again from here
            StringBuilder sb = new StringBuilder();
            for (int dy = -1; dy <= 2; dy++) { BlockState s = w.get(feet.getX(), feet.getY() + dy, feet.getZ()); sb.append(" y").append(feet.getY() + dy).append("=").append(s == null ? "?" : s.getBlock().getDescriptionId().replace("block.minecraft.", "")); }
            a.log("[speedrun] stuck on staircase step " + stairK + " at " + feet.toShortString() + " (it is at " + String.format("%.2f %.2f %.2f", a.x(), a.y(), a.z()) + "):" + sb + "; status " + status);
            // Stuck in water it cannot plug (no solid block beside it to place against): this start is no good. Another
            // one when it is on its way down to a pool (run 1120d: stuck on step 1 in an underground pond for minutes).
            // Only when there is water in that step: stuck over dry rock, it starts the staircase again from where it is (run
            // 1120g threw away a 27-step staircase over copper ore and climbed back to the surface).
            boolean wet = false;
            for (int dy = -1; dy <= 2; dy++) for (int ox = -1; ox <= 1; ox++) for (int oz = -1; oz <= 1; oz++) { if (ox != 0 && oz != 0) continue; BlockState s = w.get(feet.getX() + ox, feet.getY() + dy, feet.getZ() + oz); if (s != null && !s.getFluidState().isEmpty()) wet = true; } // beside it too
            if (wet && poolDeep && descendTop != null) { badDescend.add(descendTop); descendTop = null; a.log("[speedrun] water it cannot plug on its way down: another place to dig down from"); }
            stairOrigin = null; return true;
        }
        // Water touching any of the three (beside them, or over the top one): plugged first. Only the step itself was, then
        // the plug was dug out like stone and the water ran straight back in from the side: three staircases given up in
        // a row in one underground water layer (run 1120t, y 53-55, 10 minutes).
        for (int dy = 2; dy >= 0; dy--) {
            BlockPos b = feet.above(dy);
            for (Direction d : Direction.values()) {
                if (d == Direction.DOWN || d == Direction.UP && dy < 2) continue;
                BlockPos nb = b.relative(d);
                BlockState ns = w.get(nb.getX(), nb.getY(), nb.getZ());
                if (ns != null && ns.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) { status = "plugging the water beside its staircase"; plug(a, w, nb); return true; }
            }
        }
        // Its three blocks: plugged if wet, dug if solid.
        for (int dy = 2; dy >= 0; dy--) {
            BlockPos b = feet.above(dy);
            BlockState s = w.get(b.getX(), b.getY(), b.getZ());
            if (s == null) return true;
            if (!s.getFluidState().isEmpty()) { plug(a, w, b); return true; }
            if (s.getBlock() == Blocks.BEDROCK) { stairOrigin = null; return false; }
            if (!Rules.canWalkThrough(s)) {
                if (ticks % 4 != 0) return true;
                Gear.holdBestFor(a, s);
                a.dig(b.getX(), b.getY(), b.getZ());
                return true;
            }
        }
        // Something to stand on.
        BlockPos under = feet.below();
        BlockState u = w.get(under.getX(), under.getY(), under.getZ());
        if (u != null && (Rules.canWalkThrough(u) || !u.getFluidState().isEmpty())) {
            // A cave under the step: floor a few blocks down is a safe drop (into it, and on from there); deeper, or
            // water or lava, a block plugged in (with nothing beside it to place against, it stood there half a minute).
            int drop = 0;
            for (int d = 1; d <= 4; d++) { BlockState s = w.get(under.getX(), under.getY() - d + 1, under.getZ()); if (s == null || !s.getFluidState().isEmpty()) { drop = 99; break; } if (!Rules.canWalkThrough(s)) break; drop = d; }
            if (u.getFluidState().isEmpty() && drop <= 3) { a.stepToward(feet.getX() + 0.5, feet.getZ() + 0.5); return true; }
            plug(a, w, under); return true;
        }
        a.stepToward(feet.getX() + 0.5, feet.getZ() + 0.5);
        return true;
    }

    /** A cobblestone into this cell (water, lava or air), against any solid block beside it. */
    private void plug(AgentHandle a, BlockView w, BlockPos b) {
        if (ticks % 5 != 0) return;
        if (!hold(a, "minecraft:cobblestone") && !hold(a, "minecraft:cobbled_deepslate") && !hold(a, "minecraft:netherrack")) { status = "needs cobblestone to plug the water"; return; }
        for (Direction d : Direction.values()) {
            BlockPos n = b.relative(d);
            if (!solid(w.get(n.getX(), n.getY(), n.getZ()))) continue;
            Direction face = d.getOpposite();
            a.lookAt(n.getX() + 0.5 + face.getStepX() * 0.5, n.getY() + 0.5 + face.getStepY() * 0.5, n.getZ() + 0.5 + face.getStepZ() * 0.5);
            a.placeAgainst(n.getX(), n.getY(), n.getZ(), face);
            return;
        }
    }
    private BlockPos dryStart;
    private boolean dryDone;
    private int dryAt;

    private static boolean wet(BlockView w, int x, int y, int z) {
        BlockState s = w.get(x, y, z);
        return s != null && s.getFluidState().is(net.minecraft.tags.FluidTags.WATER);
    }

    /** The top block of this column (water counts). */
    private static int surface(BlockView w, int x, int z) {
        for (int y = 200; y > -60; y--) { BlockState s = w.get(x, y, z); if (s != null && !s.isAir()) return y; }
        return -60;
    }

    /** The nearest land (solid top, air over it) within 48 blocks of x/z: where to stand to dig down. */
    private static BlockPos dryLand(BlockView w, int cx, int cz) {
        for (int r = 0; r <= 48; r += 2) {
            BlockPos best = null; double bd = Double.MAX_VALUE;
            for (int dx = -r; dx <= r; dx += 2) for (int dz = -r; dz <= r; dz += 2) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                int x = cx + dx, z = cz + dz, y = surface(w, x, z);
                BlockState top = w.get(x, y, z);
                if (!solid(top)) continue;
                double d = dx * dx + dz * dz;
                if (d < bd) { bd = d; best = new BlockPos(x, y + 1, z); }
            }
            if (best != null) return best;
        }
        return null;
    }
    /** The main's end portal (a block of it), for helpers to follow it in. */
    private static volatile BlockPos mainEndPortal;

    // ------------------------------------------------------------------ the end

    private void theEnd(AgentHandle a) {
        if (!(a.dimension() != null && a.dimension().endsWith("the_end"))) { go(a, dragonDead || beaten() ? Stage.DONE : Stage.STRONGHOLD); if (dragonDead) a.log("§a[speedrun] home from the end: the game is beaten"); return; }
        status = "in the end";
        if (endAt < 0) { endAt = ticks; a.log("§a[speedrun] in the end (" + String.format("%.1f", (System.currentTimeMillis() - startedAt) / 60000.0) + " min in)"); }
        dragon(a);
        if (endLog != null) { a.log(String.format("[speedrun] end: %s | hp %.0f | at %.0f %.0f %.0f | %s", status, a.health(), a.x(), a.y(), a.z(), endLog)); endLog = null; }
    }
    private int endAt = -1;
    private String endLog;

    // ------------------------------------------------------------------ the dragon
    //
    // The crystals on the pillars heal it: shot down with a bow first, if it has one (arrows drop: the aim allows for
    // it). Then on the fountain in the middle, where it lands from time to time: hit while it sits there. The dragon
    // itself can't be hit, only its parts (ids right after its own): the head (+1) takes full damage, the body (+3) a
    // quarter. Its breath (a purple cloud) is kept clear of.

    private int drawAt = -1, dragonSwingCd, dragonSwings, crystalShots, dragonSeenAt = -1;
    private int crystalId = -1;
    private final Set<Integer> cagedCrystals = new HashSet<>();
    private boolean dragonDead;
    private int dragonDeadAt;

    /** The dragon is dead on this world: kept in agentnet/speedrun-beaten.txt (one line per server or world). */
    private static void markBeaten() {
        try {
            String key = String.valueOf(dev.agentnet.map.Servers.current());
            java.nio.file.Path f = dev.agentnet.AgentFiles.dir().resolve("speedrun-beaten.txt");
            java.util.List<String> lines = java.nio.file.Files.exists(f) ? java.nio.file.Files.readAllLines(f) : new ArrayList<>();
            if (!lines.contains(key)) { lines.add(key); java.nio.file.Files.write(f, lines); }
        } catch (java.io.IOException ignored) {}
    }

    /**
     * The speedrunners' lit nether portal (the cell to stand in), for the miners going after netherite once the game is
     * beaten. Kept on disk per server: the miners need it after a restart too. Null if there is none.
     */
    public static BlockPos runnersPortal() {
        if (mainPortal != null) return mainPortal;
        try {
            java.nio.file.Path f = dev.agentnet.AgentFiles.dir().resolve("speedrun-portal.txt");
            if (!java.nio.file.Files.exists(f)) return null;
            String server = String.valueOf(dev.agentnet.map.Servers.current());
            for (String line : java.nio.file.Files.readAllLines(f)) {
                String[] p = line.split("[|]");
                if (p.length == 4 && p[0].equals(server)) return new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
            }
        } catch (java.io.IOException | RuntimeException e) { /* none */ }
        return null;
    }

    private static void savePortal(BlockPos p) {
        try {
            java.nio.file.Path f = dev.agentnet.AgentFiles.dir().resolve("speedrun-portal.txt");
            String server = String.valueOf(dev.agentnet.map.Servers.current());
            List<String> keep = new ArrayList<>();
            if (java.nio.file.Files.exists(f)) for (String line : java.nio.file.Files.readAllLines(f)) if (!line.startsWith(server + "|")) keep.add(line);
            keep.add(server + "|" + p.getX() + "|" + p.getY() + "|" + p.getZ());
            java.nio.file.Files.write(f, keep);
        } catch (java.io.IOException e) { /* only a convenience */ }
    }

    /** The game is beaten on this world (the dragon is dead). */
    public static boolean gameBeaten() { return beaten(); }

    static boolean beaten() {
        try {
            java.nio.file.Path f = dev.agentnet.AgentFiles.dir().resolve("speedrun-beaten.txt");
            return java.nio.file.Files.exists(f) && java.nio.file.Files.readAllLines(f).contains(String.valueOf(dev.agentnet.map.Servers.current()));
        } catch (java.io.IOException e) { return false; }
    }
    private final java.util.Map<Integer, Integer> breathSeen = new java.util.HashMap<>();
    private boolean dragonJumped;
    private int lastPart = -1, goodPart = -1;
    private float lastDragonHp = 200;
    private int bridgeAt;

    private void dragon(AgentHandle a) {
        if (!(a.openWorld() instanceof BlockView w)) return;
        if (dragonSwingCd > 0) dragonSwingCd--;
        Seen dragon = null;
        List<Seen> crystals = new ArrayList<>();
        Seen breath = null;
        for (Seen s : a.entitiesNear(200)) {
            switch (s.name()) {
                case "ender_dragon" -> dragon = s;
                case "end_crystal" -> crystals.add(s);
                case "area_effect_cloud" -> {
                    if (s.distance() >= 6 || Math.abs(s.pos().y - a.y()) > 3) break;
                    // The same cloud for 5 s: stale (it moved away from it already, or the cloud is gone and not forgotten).
                    // (Per cloud: with two about, one shared timer flipped between them and never ran out.)
                    int since = breathSeen.computeIfAbsent(s.id(), k -> ticks);
                    if (ticks - since > 100) break;
                    if (breath == null || s.distance() < breath.distance()) breath = s;
                }
                default -> {}
            }
        }
        if (dragon != null) dragonSeenAt = ticks;
        END_TEAM.put(a.name(), System.currentTimeMillis());
        if (ticks % 400 == 0) {
            int seen = 0;
            for (Seen c : crystals) if (Sight.clear(w, a.x(), a.y() + 1.62, a.z(), c.pos().x, c.pos().y + 1.8, c.pos().z)) seen++;
            endLog = String.format("dragon %s | %d crystals up, %d in sight | %d arrows", dragon == null ? "not seen" : String.format("%.0f blocks, %.0f hp", dragon.distance(), a.healthOf(dragon.id())), crystals.size(), seen, count(a, "minecraft:arrow"));
        }
        // Gone for 10 s after a fight, or never seen in its first 30 s here (it came back after the kill): dead.
        if (dragon == null && !dragonDead && (dragonSeenAt >= 0 ? ticks - dragonSeenAt > 200 : endAt >= 0 && ticks - endAt > 600)) {
            dragonDead = true;
            a.log("§a[speedrun] THE DRAGON IS DEAD (" + String.format("%.1f", (System.currentTimeMillis() - startedAt) / 60000.0) + " min in)");
            dragonDeadAt = ticks;
            markBeaten();
        }
        if (dragonDead) { goHomeFromEnd(a, w); return; }
        // Out of the breath first.
        if (breath != null) {
            double dx = a.x() - breath.pos().x, dz = a.z() - breath.pos().z, h = Math.max(0.1, Math.hypot(dx, dz));
            status = "out of the dragon's breath";
            if (drawAt >= 0) { a.releaseUse(); drawAt = -1; }
            // A walk away, not a step: down in the fountain a step away went nowhere and it stood in it for a minute.
            int ox = (int) Math.floor(a.x() + dx / h * 8), oz = (int) Math.floor(a.z() + dz / h * 8);
            if (!a.isNavigating() || ticks % 20 == 0) a.navigate(ox, surface(w, ox, oz) + 1, oz, 2);
            return;
        }
        // The crystals: the nearest one it can see and hasn't given up on (a cage stops arrows: 5 shots and it moves on).
        int arrows = count(a, "minecraft:arrow");
        boolean bow = count(a, "minecraft:bow") > 0;
        Seen target = null;
        if (bow && arrows > 0) {
            for (Seen c : crystals) {
                if (cagedCrystals.contains(c.id())) continue;
                if (c.distance() > SHOT_RANGE) continue; // further off it misses (5 misses at 104 blocks wrote a crystal off as caged)
                if (!Sight.clear(w, a.x(), a.y() + 1.62, a.z(), c.pos().x, c.pos().y + 1.8, c.pos().z)) continue;
                if (target == null || c.distance() < target.distance()) target = c;
            }
        }
        int top = fountainTop(w);
        boolean perched = dragon != null && Math.hypot(dragon.pos().x, dragon.pos().z) < 8 && dragon.pos().y < top + 6;
        if (target != null && !(perched && dragon.distance() < 12)) {
            if (target.id() != crystalId) { crystalId = target.id(); crystalShots = 0; }
            if (crystalShots >= 5) { cagedCrystals.add(target.id()); crystalId = -1; a.log("[speedrun] can't hit that crystal (a cage?): the next one"); return; }
            if (a.isNavigating()) a.stopNavigation();
            status = "shooting the end crystals (" + crystals.size() + " left, " + arrows + " arrows)";
            if (!hold(a, "minecraft:bow")) return;
            double dx = target.pos().x - a.x(), dz = target.pos().z - a.z();
            double dy = target.pos().y + 1.6 - (a.y() + 1.62); // the top half of it: from below, the middle hides behind the pillar's edge
            float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            a.look(yaw, bowPitch(Math.hypot(dx, dz), dy));
            if (drawAt < 0) { a.useItem(); drawAt = ticks; return; }
            if (ticks - drawAt >= 22) { a.releaseUse(); drawAt = -1; crystalShots++; a.log(String.format("[speedrun] arrow at the crystal #%d, %.0f blocks off (shot %d)", target.id(), target.distance(), crystalShots)); }
            return;
        }
        if (drawAt >= 0) { a.releaseUse(); drawAt = -1; }
        // The fountain: wait by it for the dragon to land, and hit it there.
        if (dragon != null && dragon.distance() < 9) {
            status = "hitting the dragon (" + Math.round(a.healthOf(dragon.id())) + " hp)";
            // In close: under it (out at 9 blocks every swing missed). Walking, it still swings.
            if (dragon.distance() > 4.5) { if (!a.isNavigating() || ticks % 10 == 0) { BlockPos g = groundUnder(w, dragon.pos()); a.navigate(g.getX(), g.getY(), g.getZ(), 1); } }
            else if (a.isNavigating()) a.stopNavigation();
            if (!holdWeapon(a)) return;
            a.lookAt(dragon.pos().x, dragon.pos().y + 1.5, dragon.pos().z);
            // A full swing every time (an axe needs 1.25 s to charge: swung every 0.65 s, each hit did about half), and
            // a critical one: a jump timed so the swing is charged as it starts to fall.
            boolean axe = Gear.id(a.inventory().get(a.selectedSlot())).endsWith("_axe");
            int full = axe ? 25 : 13;
            if (dragonSwingCd <= 9 && dragonSwingCd > 0 && !a.airborne() && !dragonJumped) { a.jump(); dragonJumped = true; }
            boolean falling = a.airborne() && a.verticalSpeed() < 0;
            if (dragonSwingCd == 0 && (falling || !a.airborne() && !dragonJumped || dragonJumped && !a.airborne())) {
                // The part that took damage last time, else head, neck, body in turn (out of reach the server ignores it).
                float hp = a.healthOf(dragon.id());
                if (lastPart > 0 && lastDragonHp - hp >= 4) goodPart = lastPart;
                int part = goodPart > 0 && dragonSwings % 4 != 3 ? goodPart : dragon.id() + 1 + dragonSwings % 3;
                lastPart = part; lastDragonHp = hp;
                a.attack(part);
                dragonSwings++;
                dragonSwingCd = full;
                dragonJumped = false;
                dev.agentnet.AgentNet.LOG.info("[{}] swing at the dragon, part #{} ({} blocks, its health {})", a.name(), part, String.format("%.1f", dragon.distance()), a.healthOf(dragon.id()));
            }
            return;
        }
        if (bridge(a, w)) return;
        // Crystals up and a bow, but none in sight (down in the fountain it sees none): out onto the island, 20 blocks
        // from the middle, a new spot every 15 s until one is in sight.
        if (bow && arrows > 0 && !crystals.isEmpty() && target == null && !(dragon != null && dragon.distance() < 12)) {
            if (shotSpot == null || ticks - shotSpotAt > 300) { shotSpot = shotSpot(w, crystals); shotSpotAt = ticks; }
            if (shotSpot != null) {
                status = "out to where it can see a crystal (" + crystals.size() + " up, spot " + shotSpot.toShortString() + ")";
                if (!a.isNavigating() && ticks % 20 == 0) a.navigate(shotSpot.getX(), shotSpot.getY(), shotSpot.getZ(), 1);
                return;
            }
            // None in sight from the ground (caged, or hidden behind its pillar's edge): up after it.
            if (crystalHunt(a, w, crystals)) return;
        }
        double fd = Math.hypot(a.x() - 0.5, a.z() - 3.5);
        status = dragon == null ? "looking for the dragon" : "waiting by the fountain for the dragon to land (" + crystals.size() + " crystals up)";
        if (fd > 3 && !a.isNavigating() && ticks % 20 == 0) a.navigate(0, top - 3, 4, 2);
    }

    /**
     * The End drops it on a little obsidian platform over the void: it bridges over to the island like a player, a
     * block placed against the one under its feet toward the middle, then a step onto it. True while bridging.
     */
    private boolean bridge(AgentHandle a, BlockView w) {
        double dx = -a.x(), dz = -a.z(), h = Math.hypot(dx, dz);
        if (h < 40) return false; // on the island
        // One block ahead toward the middle, at its feet' level: floor there? Then it walks.
        int fx = (int) Math.floor(a.x()), fy = (int) Math.floor(a.y() + 0.01), fz = (int) Math.floor(a.z());
        Direction dir = Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        BlockPos under = new BlockPos(fx, fy - 1, fz), next = under.relative(dir);
        BlockState n = w.get(next.getX(), next.getY(), next.getZ()), nFeet = w.get(next.getX(), fy, next.getZ());
        if (n == null || nFeet == null) return false;
        // Void under the next block for a long way: a bridge. (A short drop or a wall is the walker's job.)
        boolean voidAhead = true;
        for (int y = fy - 1; y > fy - 30; y--) { BlockState s = w.get(next.getX(), y, next.getZ()); if (s != null && !s.isAir()) { voidAhead = false; break; } }
        if (!voidAhead && !n.isAir()) {
            if (a.isNavigating()) return true;
            // Floor ahead: walk on (and keep the old trip going).
            if (Rules.canWalkThrough(nFeet)) { a.stepToward(next.getX() + 0.5, next.getZ() + 0.5); return true; }
            // The island's edge, a wall of end stone: up on the spot (a block under its feet each time) until the way
            // ahead is open. (Left to the walker it climbed a block every 10 s.)
            BlockState nHead = w.get(next.getX(), fy + 1, next.getZ());
            if (nHead != null && !Rules.canWalkThrough(nHead) || !Rules.canWalkThrough(nFeet)) {
                status = "climbing the island's edge";
                if (a.isNavigating()) a.stopNavigation();
                if (ticks % 4 == 0 && !a.airborne()) { hold(a, "minecraft:cobblestone"); a.pillarUp(); }
                return true;
            }
            return false;
        }
        if (!voidAhead) return false;
        if (a.isNavigating()) a.stopNavigation();
        status = String.format("bridging over the void to the island (%.0f blocks to the middle)", h);
        if (!solid(w.get(under.getX(), under.getY(), under.getZ()))) return true; // still stepping onto the last block
        // To the edge of its block first (sneak-walk: not past it), then the block against the side of the one it stands on.
        double ex = fx + 0.5 + dir.getStepX() * 0.35, ez = fz + 0.5 + dir.getStepZ() * 0.35;
        if (Math.hypot(a.x() - ex, a.z() - ez) > 0.12) { a.stepToward(ex, ez); return true; }
        if (ticks - bridgeAt < 5) return true;
        bridgeAt = ticks;
        if (!hold(a, "minecraft:cobblestone") && !hold(a, "minecraft:cobbled_deepslate") && !hold(a, "minecraft:netherrack") && !hold(a, "minecraft:end_stone")) { status = "out of blocks to bridge with"; return true; }
        a.lookAt(under.getX() + 0.5 + dir.getStepX() * 0.5, under.getY() + 0.5, under.getZ() + 0.5 + dir.getStepZ() * 0.5);
        a.placeAgainst(under.getX(), under.getY(), under.getZ(), dir);
        return true;
    }

    private BlockPos hopGoal;
    private Direction lavaDir;
    private boolean lavaBridging;
    private int lavaAt = -1000, lavaWalked, noBridgeUntil, bridgeMineAt;
    private final MinerModule bridgeMiner = new MinerModule();
    { bridgeMiner.keepsLoad = true; }
    private boolean bridgeMining;
    private int bridgeHitAt = -1000;

    /**
     * Lava between it and where its hop goes (the user: "give them the ability to bridge over lava"; run 1119a's portal
     * came out on a ledge in a lava sea and nobody could leave it): over it like a player, a block against the side of
     * the one under its feet, a step onto it, the next. With cobblestone or netherrack; out of both, it mines netherrack
     * first. Only once the walker has found no way round. True while it is at it.
     */
    private boolean lavaBridge(AgentHandle a, BlockView w) {
        if (hopGoal == null || a.airborne() || ticks < noBridgeUntil) return false;
        double dx = hopGoal.getX() + 0.5 - a.x(), dz = hopGoal.getZ() + 0.5 - a.z();
        if (Math.hypot(dx, dz) < 6) { lavaBridging = false; return false; }
        int fx = (int) Math.floor(a.x()), fy = (int) Math.floor(a.y() + 0.01), fz = (int) Math.floor(a.z());
        if (!lavaBridging) lavaDir = Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        Direction dir = lavaDir;
        BlockPos under = new BlockPos(fx, fy - 1, fz), next = under.relative(dir);
        BlockState n = w.get(next.getX(), next.getY(), next.getZ()), nFeet = w.get(next.getX(), fy, next.getZ()), nHead = w.get(next.getX(), fy + 1, next.getZ());
        if (n == null || nFeet == null || nHead == null) { lavaBridging = false; return false; }
        // Something hostile on or by the bridge (a magma cube hopping along it): no step toward it. It is hit when it
        // comes in reach, from where it stands. Walking on, it stepped straight into one and died (run 1119a).
        if (lavaBridging) {
            Seen foeNear = null;
            for (Seen s : a.entitiesNear(6)) {
                if (s.player() || !s.hostile()) continue;
                if (foeNear == null || s.distance() < foeNear.distance()) foeNear = s;
            }
            if (foeNear != null) {
                if (a.isNavigating()) a.stopNavigation();
                status = "on its bridge: a " + foeNear.name().replace('_', ' ') + " in the way";
                a.lookAt(foeNear.pos().x, foeNear.pos().y + 0.4, foeNear.pos().z);
                if (eyeDist(a, foeNear) <= 3.0 && ticks - bridgeHitAt >= 13) {
                    bridgeHitAt = ticks;
                    int axe = Gear.bestTool(a.inventory(), "axe"), sword = Gear.bestTool(a.inventory(), "sword");
                    int w2 = sword >= 0 ? sword : axe;
                    if (w2 >= 0 && w2 < 9) a.selectHotbar(w2);
                    a.attack(foeNear.id());
                }
                return true;
            }
        }
        boolean lavaAhead = lavaAt(n);
        if (!lavaAhead && n.isAir()) for (int y = next.getY() - 1; y >= next.getY() - 8; y--) {
            BlockState s = w.get(next.getX(), y, next.getZ());
            if (s == null || !s.isAir()) { lavaAhead = s != null && lavaAt(s); break; }
        }
        boolean room = Rules.canWalkThrough(nFeet) && Rules.canWalkThrough(nHead);
        if (!lavaAhead) {
            if (!lavaBridging) return false;
            // Floor ahead (the block it just put down, or land): onto it. Three steps with nothing to bridge: it is across.
            if (solid(n) && room && ++lavaWalked < 60) {
                if (a.isNavigating()) a.stopNavigation();
                a.cancelEscape();
                a.stepToward(next.getX() + 0.5, next.getZ() + 0.5);
                status = "bridging over lava";
                return true;
            }
            lavaBridging = false;
            a.log("§a[speedrun] across the lava: walking on");
            hopAt = ticks;
            a.navigate(hopGoal.getX(), hopGoal.getY(), hopGoal.getZ(), 6);
            return false;
        }
        if (!room) { lavaBridging = false; return false; } // a wall over the lava: not this way
        if (!lavaBridging) {
            // The walker first: it may know a way round. Five seconds after the hop with no trip running: over it.
            if (a.isNavigating() || ticks - hopAt < 100) return false;
            lavaBridging = true;
            a.log("§e[speedrun] lava in the way: bridging over it");
        }
        lavaWalked = 0;
        if (a.isNavigating()) a.stopNavigation();
        a.cancelEscape();
        status = "bridging over lava";
        if (!solid(w.get(under.getX(), under.getY(), under.getZ()))) return true; // still stepping onto the last block
        if (count(a, "minecraft:cobblestone") + count(a, "minecraft:netherrack") + count(a, "minecraft:cobbled_deepslate") == 0) {
            // Netherrack only drops for a pickaxe: without one there is nothing to bridge with. Not this way, for two minutes.
            if (Gear.bestTool(a.inventory(), "pickaxe") < 0) {
                lavaBridging = false; noBridgeUntil = ticks + 2400;
                a.log("§e[speedrun] lava in the way and nothing to bridge with (no blocks, no pickaxe): another way");
                return false;
            }
            status = "out of blocks to bridge with: mining netherrack";
            // Its own miner: the nether stage stops the shared one every tick ("mining 16x netherrack" / "stopped", 20 a second).
            if (!bridgeMining || bridgeMiner.idle() && bridgeMiner.status().startsWith("done") && ticks - bridgeMineAt > 100) { bridgeMiner.onTask(a, "mine 16 netherrack"); bridgeMining = true; bridgeMineAt = ticks; }
            bridgeMiner.tick(a);
            return true;
        }
        if (bridgeMining) { bridgeMiner.onTask(a, "idle"); bridgeMining = false; }
        // To the edge of its block first (not past it), then the block against the side of the one it stands on.
        double ex = fx + 0.5 + dir.getStepX() * 0.35, ez = fz + 0.5 + dir.getStepZ() * 0.35;
        if (Math.hypot(a.x() - ex, a.z() - ez) > 0.12) { a.stepToward(ex, ez); return true; }
        if (ticks - lavaAt < 5) return true;
        lavaAt = ticks;
        if (!hold(a, "minecraft:netherrack") && !hold(a, "minecraft:cobblestone") && !hold(a, "minecraft:cobbled_deepslate")) return true;
        a.lookAt(under.getX() + 0.5 + dir.getStepX() * 0.5, under.getY() + 0.5, under.getZ() + 0.5 + dir.getStepZ() * 0.5);
        a.placeAgainst(under.getX(), under.getY(), under.getZ(), dir);
        return true;
    }

    private static boolean lavaAt(BlockState s) { return s != null && s.getFluidState().is(net.minecraft.tags.FluidTags.LAVA); }

    private static final double SHOT_RANGE = 75;
    // ------------------------------------------------------------------ the mace
    //
    // After the dragon, a mace each: a heavy core and a breeze rod on a crafting table. Breeze rods drop from the breezes
    // of a trial chamber's spawners, deep underground (y -40 to -20). Its chunks come whole, so the spawners show up in
    // what it knows from up on the surface: it looks, and if none is near it travels on in long legs (flown, with an
    // elytra) looking again, then down to the spawner the checked way and fights what comes out. The heavy core is the
    // long part (an ominous vault: an ominous bottle from a raid captain first): not done yet, it says so.

    /** With the omen: any spawner will do (ominous ones all drop keys), and it stays for the wave. */
    private boolean anySpawner;

    private static boolean readySpawner(BlockState s) {
        var st = s.getValue(net.minecraft.world.level.block.TrialSpawnerBlock.STATE);
        return st == net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerState.WAITING_FOR_PLAYERS || st == net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerState.ACTIVE;
    }

    // ------------------------------------------------------------------ the heavy core
    //
    // It only comes out of an ominous vault, opened with an ominous trial key, which ominous trial spawners drop; those
    // turn ominous for a player with Trial Omen, which Bad Omen becomes on entering a trial chamber; Bad Omen comes from
    // drinking an ominous bottle, which a raid captain drops (an outpost always has one). Step one here: the bottle and
    // the omen. (The ominous spawners and the vault are next; not done yet.)

    private int pillagerSaidAt = -10000, drankAt = -10000;
    private BlockPos killSpot, captainSpot;
    private int killSpotAt;

    private void heavyCore(AgentHandle a, BlockView w) {
        int rods = count(a, "minecraft:breeze_rod");
        boolean omen = hasEffect(a, "bad_omen") || hasEffect(a, "trial_omen");
        if (count(a, "minecraft:ominous_trial_key") > 0) { ominousVault(a, w); return; }
        if (omen) { status = "has the omen: on to the trial spawners"; return; }
        // A bottle: drunk (it gives Bad Omen).
        if (count(a, "minecraft:ominous_bottle") > 0) {
            status = "drinking an ominous bottle";
            if (ticks - drankAt > 60 && hold(a, "minecraft:ominous_bottle")) { a.useItem(); drankAt = ticks; a.log("[speedrun] drinking an ominous bottle: Bad Omen, for an ominous trial chamber"); }
            return;
        }
        // Drops where a pillager died: picked up (the captain's bottle among them).
        if (killSpot != null && ticks - killSpotAt < 400) {
            for (net.minecraft.world.phys.Vec3 it : a.itemsNear(8)) {
                status = "picking up what the pillagers dropped";
                if (!a.isNavigating() || ticks % 10 == 0) a.navigate((int) Math.floor(it.x), (int) Math.floor(it.y), (int) Math.floor(it.z), 0);
                return;
            }
        }
        // Pillagers in sight: after them (the fight itself is fight() and archerGuard: a crossbow is shielded like a bow).
        Seen pill = null;
        // The captain first (a banner on its head: only it drops the bottle). Six plain pillagers killed, no bottle.
        Seen captain = null;
        for (Seen s : a.entitiesNear(64)) {
            if (!s.name().equals("pillager") && !s.name().equals("vindicator")) continue;
            if (a.captain(s.id())) { if (captain == null || s.distance() < captain.distance()) captain = s; }
            else if (s.name().equals("pillager") && (pill == null || s.distance() < pill.distance())) pill = s;
        }
        if (captain != null) {
            pill = captain;
            if (ticks - pillagerSaidAt > 600) { pillagerSaidAt = ticks; a.log(String.format("[speedrun] the raid captain %.0f blocks off: after its ominous bottle", captain.distance())); }
        }
        if (captain != null && captain.distance() < 6) captainSpot = BlockPos.containing(captain.pos());
        if (captainSpot != null && captain == null && ticks - killSpotAt < 600) {
            // The captain is gone from where it was: what it dropped is there.
            status = "to where the captain fell, for its bottle";
            for (net.minecraft.world.phys.Vec3 it : a.itemsNear(10)) { if (!a.isNavigating() || ticks % 10 == 0) a.navigate((int) Math.floor(it.x), (int) Math.floor(it.y), (int) Math.floor(it.z), 0); return; }
            if (captainSpot.distToCenterSqr(a.x(), a.y(), a.z()) > 4) { if (!a.isNavigating() || ticks % 20 == 0) a.navigate(captainSpot.getX(), captainSpot.getY(), captainSpot.getZ(), 1); return; }
            captainSpot = null;
        }
        if (pill != null) {
            killSpot = BlockPos.containing(pill.pos()); killSpotAt = ticks;
            if (ticks - pillagerSaidAt > 600) { pillagerSaidAt = ticks; a.log(String.format("[speedrun] pillagers %.0f blocks off: after the captain's ominous bottle", pill.distance())); }
            status = String.format("after the pillagers for an ominous bottle (%.0f blocks)", pill.distance());
            if (!a.isNavigating() || ticks % 20 == 0) { BlockPos g = BlockPos.containing(pill.pos().x, pill.pos().y + 0.2, pill.pos().z); a.navigate(g.getX(), g.getY(), g.getZ(), 2); }
            return;
        }
        status = "has " + rods + " breeze rod(s): looking for pillagers (an outpost) for an ominous bottle";
        // Legs of 300 blocks, as when looking for a trial chamber.
        if (Math.hypot(a.x() - maceLegX, a.z() - maceLegZ) < 20 || ticks - maceLegAt > 20 * 300 || maceLegAt < -1000) {
            int[][] dir = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
            int len = 300 * (1 + maceLeg / 2);
            maceLegX = a.x() + dir[maceLeg % 4][0] * len; maceLegZ = a.z() + dir[maceLeg % 4][1] * len;
            maceLeg++; maceLegAt = ticks;
        }
        if (!a.isNavigating() && ticks % 40 == 0) { int gx = (int) maceLegX, gz = (int) maceLegZ; a.navigate(gx, surface(w, gx, gz) + 1, gz, 8); }
    }

    private BlockPos vault;
    private int vaultUsedAt = -1000;

    /** An ominous key: to an ominous vault (active), the key on it, and what it throws out picked up. */
    private void ominousVault(AgentHandle a, BlockView w) {
        int ax = (int) Math.floor(a.x()), az = (int) Math.floor(a.z());
        if (vault == null && ticks % 40 == 0) {
            // Any ominous one: a vault only shows "active" with a player right by it (from afar they all looked inactive).
            for (BlockPos p : w.findAll(ax, az, 160, s -> s.getBlock() == Blocks.VAULT && s.getValue(net.minecraft.world.level.block.VaultBlock.OMINOUS), 16))
                if (vault == null || p.distToCenterSqr(a.x(), a.y(), a.z()) < vault.distToCenterSqr(a.x(), a.y(), a.z())) vault = p;
            if (vault != null) a.log("[speedrun] an ominous vault at " + vault.toShortString() + ": the key goes in it");
        }
        // What came out of it: picked up.
        if (ticks - vaultUsedAt < 300) {
            for (net.minecraft.world.phys.Vec3 it : a.itemsNear(6)) { status = "picking up what the vault threw out"; if (!a.isNavigating() || ticks % 10 == 0) a.navigate((int) Math.floor(it.x), (int) Math.floor(it.y), (int) Math.floor(it.z), 0); return; }
            if (ticks - vaultUsedAt > 200) { if (count(a, "minecraft:heavy_core") > 0) a.log("§a[speedrun] A HEAVY CORE out of the ominous vault"); vault = null; }
            return;
        }
        if (vault == null) { status = "has an ominous key: looking for an ominous vault"; return; }
        double d = Math.sqrt(vault.distToCenterSqr(a.x(), a.y() + 1.62, a.z()));
        status = String.format("to the ominous vault (%.0f blocks)", d);
        if (d > 4) {
            if (dist3(a, vault) < spawnerBest - 1) { spawnerBest = dist3(a, vault); spawnerBestAt = ticks; }
            if (ticks - spawnerBestAt > 400) { if (a.isNavigating()) a.stopNavigation(); tunnelTo(a, w, vault); return; }
            if (!a.isNavigating() || ticks % 20 == 0) a.navigate(vault.getX(), vault.getY(), vault.getZ(), 2);
            return;
        }
        if (a.isNavigating()) a.stopNavigation();
        if (!hold(a, "minecraft:ominous_trial_key")) return;
        a.lookAt(vault.getX() + 0.5, vault.getY() + 0.5, vault.getZ() + 0.5);
        a.useItemOn(vault.getX(), vault.getY(), vault.getZ(), Direction.UP);
        vaultUsedAt = ticks;
        spawnerBest = Double.MAX_VALUE;
        a.log("[speedrun] the ominous key in the vault at " + vault.toShortString());
    }

    private static double dist3(AgentHandle a, BlockPos p) { return Math.sqrt(p.distToCenterSqr(a.x(), a.y(), a.z())); }

    private static boolean hasEffect(AgentHandle a, String id) {
        try { return a.hasEffect(id); } catch (RuntimeException e) { return false; }
    }

    private BlockPos trialSpawner;
    /** Spawners found to make other mobs (kept across deaths: it went back to the same skeleton spawner and died again). */
    private static final Set<BlockPos> spawnersTried = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private int spawnerAt = -1, spawnerBestAt, tunnelAt;
    private double spawnerBest = Double.MAX_VALUE;
    private boolean tunnelling;

    /**
     * One step of its own tunnel toward a block: a column across (the bigger way first), or a block down while it is
     * still above it. Whatever fills the two cells it moves into is dug (slabs and stairs too), liquid is plugged, and
     * it steps in itself: no walker, no half-block floors to stand on.
     */
    private void tunnelTo(AgentHandle a, BlockView w, BlockPos goal) {
        if (a.isDigging() || a.airborne()) return;
        int fx = (int) Math.floor(a.x()), fy = (int) Math.floor(a.y() + 0.01), fz = (int) Math.floor(a.z());
        int dx = goal.getX() - fx, dz = goal.getZ() - fz;
        BlockPos next;
        if (fy > goal.getY() + 1 && Math.abs(dx) + Math.abs(dz) <= 2) next = new BlockPos(fx, fy - 1, fz); // over it: down
        else if (Math.abs(dx) >= Math.abs(dz)) next = new BlockPos(fx + Integer.signum(dx), fy > goal.getY() + 1 ? fy - 1 : fy, fz);
        else next = new BlockPos(fx, fy > goal.getY() + 1 ? fy - 1 : fy, fz + Integer.signum(dz));
        // Never dig the spawner itself.
        for (int dy = 0; dy <= 1; dy++) if (next.above(dy).equals(goal)) { next = new BlockPos(next.getX(), next.getY() + 2, next.getZ()); break; }
        boolean down = next.getX() == fx && next.getZ() == fz;
        // The cells: feet and head of the next column (and the one over its head when going down a step).
        BlockPos[] cells = down ? new BlockPos[]{next} : new BlockPos[]{next, next.above(), new BlockPos(fx, fy + 2, fz)};
        if (!down && next.getY() < fy) cells = new BlockPos[]{next, next.above(), next.above(2)};
        for (BlockPos c : cells) {
            BlockState s = w.get(c.getX(), c.getY(), c.getZ());
            if (s == null) return;
            if (s.getBlock() == Blocks.TRIAL_SPAWNER || s.getBlock() == Blocks.VAULT || s.getBlock() == Blocks.BEDROCK) { tunnelling = false; spawnerBestAt = ticks; return; }
            if (!s.getFluidState().isEmpty()) { plug(a, w, c); return; }
            if (!Rules.canWalkThrough(s)) {
                if (ticks - tunnelAt < 3) return;
                tunnelAt = ticks;
                Gear.holdBestFor(a, s);
                a.lookAt(c.getX() + 0.5, c.getY() + 0.5, c.getZ() + 0.5);
                a.dig(c.getX(), c.getY(), c.getZ());
                return;
            }
        }
        // Something to stand on in the next column (a slab counts: it walks onto its top or drops beside it).
        BlockPos under = next.below();
        BlockState u = w.get(under.getX(), under.getY(), under.getZ());
        if (u != null && (Rules.canWalkThrough(u) || !u.getFluidState().isEmpty())) {
            int drop = 0;
            for (int d = 1; d <= 4; d++) { BlockState s = w.get(under.getX(), under.getY() - d + 1, under.getZ()); if (s == null || !s.getFluidState().isEmpty()) { drop = 99; break; } if (!Rules.canWalkThrough(s)) break; drop = d; }
            if (drop > 3 || !u.getFluidState().isEmpty()) { plug(a, w, under); return; }
        }
        a.stepToward(next.getX() + 0.5, next.getZ() + 0.5);
    }
    /** Re-equipping after a death on the way to a mace: the first stages, then straight back to the mace. */
    private boolean regear;
    private int maceLeg, maceLegAt = -10000, maceCraftAt = -1000;
    private double maceLegX, maceLegZ;

    private void mace(AgentHandle a) {
        if (!(a.openWorld() instanceof BlockView w)) return;
        if (count(a, "minecraft:mace") > 0) { status = "has its mace"; return; }
        // No weapon (it died down there and lost it all): iron, tools and a shield again (the speedrun's first stages),
        // then back here. Sent down bare-handed it would only die again.
        boolean armed = false;
        for (ItemStack s : a.inventory()) { String id = Gear.id(s); if (id.endsWith("_axe") || id.endsWith("_sword")) armed = true; }
        if (!armed) { regear = true; a.log("[speedrun] no weapon: getting iron, tools and a shield again before the trial chamber"); go(a, Stage.IRON); return; }
        // Hurt by the spawner: back up its way down a bit and eat before going on.
        if (a.health() < 8 && trialSpawner != null && trialSpawner.distToCenterSqr(a.x(), a.y(), a.z()) < 16 * 16) {
            status = "hurt: backing off from the spawner to eat";
            double dx = a.x() - trialSpawner.getX(), dz = a.z() - trialSpawner.getZ(), h = Math.max(0.5, Math.hypot(dx, dz));
            if (!a.isNavigating() || ticks % 20 == 0) a.navigate((int) Math.floor(a.x() + dx / h * 12), (int) Math.floor(a.y()), (int) Math.floor(a.z() + dz / h * 12), 3);
            return;
        }
        int rods = count(a, "minecraft:breeze_rod"), cores = count(a, "minecraft:heavy_core");
        if (rods > 0 && cores > 0) {
            status = "making its mace";
            if (ticks - maceCraftAt > 200) { maceCraftAt = ticks; crafter.onTask(a, "make mace 1"); }
            return;
        }
        boolean omenRun = rods > 0 && (hasEffect(a, "trial_omen") || hasEffect(a, "bad_omen")) && count(a, "minecraft:ominous_trial_key") == 0;
        if (rods > 0 && !omenRun) { heavyCore(a, w); return; }
        anySpawner = omenRun;
        // Drops by the spawner (where the breezes die): picked up. Not elsewhere: on its way down it chased the stone its
        // own digging dropped.
        if (trialSpawner != null && trialSpawner.distToCenterSqr(a.x(), a.y(), a.z()) < 14 * 14) for (net.minecraft.world.phys.Vec3 it : a.itemsNear(6)) {
            status = "picking up a drop";
            if (!a.isNavigating() || ticks % 10 == 0) a.navigate((int) Math.floor(it.x), (int) Math.floor(it.y), (int) Math.floor(it.z), 0);
            return;
        }
        int ax = (int) Math.floor(a.x()), az = (int) Math.floor(a.z());
        if (trialSpawner == null && ticks % 40 == 0) {
            // Only ones ready to make mobs: waiting for a player, or at it now (a beaten one rests half an hour: it stood a
            // minute at each of three of those, and nothing came).
            List<BlockPos> sp = w.findAll(ax, az, 160, s -> s.getBlock() == Blocks.TRIAL_SPAWNER && readySpawner(s), 16);
            BlockPos best = null;
            for (BlockPos p : sp) if (!spawnersTried.contains(p)) if (best == null || p.distToCenterSqr(a.x(), a.y(), a.z()) < best.distToCenterSqr(a.x(), a.y(), a.z())) best = p;
            if (best != null) { trialSpawner = best; BlockState bs = w.get(best.getX(), best.getY(), best.getZ()); a.log("[speedrun] a trial chamber: its spawner at " + best.toShortString() + " (" + (bs == null ? "?" : bs.getValue(net.minecraft.world.level.block.TrialSpawnerBlock.STATE).getSerializedName()) + "), going down for breeze rods"); }
        }
        if (trialSpawner == null && stageTicks < 200) { status = "looking round for a trial chamber"; return; } // its chunks first
        if (trialSpawner == null) {
            // Look further: legs of 300 blocks, turning a quarter each time (a growing square), flown if it can.
            if (maceLegAt < -1000 || Math.hypot(a.x() - maceLegX, a.z() - maceLegZ) < 20 || ticks - maceLegAt > 20 * 300) {
                int[][] dir = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
                int len = 300 * (1 + maceLeg / 2);
                maceLegX = a.x() + dir[maceLeg % 4][0] * len; maceLegZ = a.z() + dir[maceLeg % 4][1] * len;
                maceLeg++; maceLegAt = ticks;
                a.log(String.format("[speedrun] no trial chamber in sight: on %d blocks to %.0f %.0f", len, maceLegX, maceLegZ));
            }
            status = String.format("looking for a trial chamber (%.0f blocks to the end of this leg)", Math.hypot(a.x() - maceLegX, a.z() - maceLegZ));
            if (!a.isNavigating() && ticks % 40 == 0) { int gx = (int) maceLegX, gz = (int) maceLegZ; a.navigate(gx, surface(w, gx, gz) + 1, gz, 8); }
            return;
        }
        // Down to it: a spot beside the spawner with room over it, the column checked, straight down; else a staircase.
        double dist = Math.sqrt(trialSpawner.distToCenterSqr(a.x(), a.y() + 1, a.z()));
        status = String.format("going down to the trial spawner (%.0f blocks)", dist);
        if (a.y() > trialSpawner.getY() + 3) {
            if (shaftIntoRoom(a, w, List.of(trialSpawner))) return;
            if (staircase(a, w, trialSpawner.getY())) return;
        }
        // On the way: mobs about the spawner and not a breeze among them (a skeleton spawner shot it dead on the way in
        // before it ever got there to see): the next one.
        if (dist < 18) {
            boolean breezeNear = false, othersNear = false;
            for (Seen s : a.entitiesNear(24)) {
                if (s.pos().distanceToSqr(trialSpawner.getX() + 0.5, trialSpawner.getY(), trialSpawner.getZ() + 0.5) > 14 * 14) continue;
                if (s.name().equals("breeze")) breezeNear = true; else if (s.hostile() && !s.player()) othersNear = true;
            }
            if (othersNear && !breezeNear && !anySpawner) {
                a.log("[speedrun] mobs come out of the spawner " + trialSpawner.toShortString() + " and not breezes: the next one");
                spawnersTried.add(trialSpawner); trialSpawner = null; spawnerAt = -1; shaftCol = null; shaftNone = false; tunnelling = false; spawnerBest = Double.MAX_VALUE;
                return;
            }
        }
        if (dist > 5) {
            // The walk there stalls on the chamber's slabs and stairs (half-block floors the walker doesn't stand on;
            // the server put it back 29 times in 7 minutes): no closer in 20 s, it digs its own way straight there.
            if (dist < spawnerBest - 1) { spawnerBest = dist; spawnerBestAt = ticks; }
            if (ticks - spawnerBestAt > 400 || tunnelling) {
                if (!tunnelling) { tunnelling = true; a.log(String.format("[speedrun] the walk to the spawner stalls %.0f blocks off: digging straight there", dist)); }
                if (a.isNavigating()) a.stopNavigation();
                status = String.format("digging straight to the trial spawner (%.0f blocks)", dist);
                tunnelTo(a, w, trialSpawner);
                return;
            }
            if (!a.isNavigating() && ticks % 20 == 0) a.navigate(trialSpawner.getX(), trialSpawner.getY(), trialSpawner.getZ(), 3);
            return;
        }
        tunnelling = false; spawnerBest = Double.MAX_VALUE;
        if (a.isNavigating()) a.stopNavigation();
        status = "by the trial spawner, fighting what comes out for breeze rods";
        // Each spawner makes one kind of mob (a breeze one is what it wants) and rests for half an hour once beaten: no
        // breeze in a minute here, the next spawner.
        boolean breeze = false, others = false;
        for (Seen s : a.entitiesNear(20)) { if (s.name().equals("breeze")) breeze = true; else if (s.hostile() && !s.player()) others = true; }
        if (breeze) spawnerAt = ticks;
        else if (!anySpawner && others && spawnerAt >= 0 && ticks - spawnerAt > 60) {
            // Other mobs and no breeze: a skeleton or zombie spawner (one shot it dead). The next one.
            a.log("[speedrun] the spawner " + trialSpawner.toShortString() + " makes other mobs, not breezes: the next one");
            spawnersTried.add(trialSpawner); trialSpawner = null; spawnerAt = -1; shaftCol = null; shaftNone = false;
            return;
        }
        else if (spawnerAt < 0) spawnerAt = ticks;
        else if (!anySpawner && ticks - spawnerAt > 20 * 60) {
            a.log("[speedrun] no breeze at the spawner " + trialSpawner.toShortString() + " in a minute: the next one");
            spawnersTried.add(trialSpawner); trialSpawner = null; spawnerAt = -1; shaftCol = null; shaftNone = false;
        }
    }

    // ------------------------------------------------------------------ crystals out of sight
    //
    // A crystal no spot on the ground sees (its pillar's edge hides it, or a cage of iron bars): a pillar of its own
    // 11 blocks off, on the side toward the middle, up to the crystal's height, and the arrow goes level (the blast
    // that far off is a few hearts in iron). A cage: first a pillar right beside it, the bars on that side broken,
    // back down, then the same through the gap.

    private enum Hunt { NONE, TO_CAGE, CLIMB_CAGE, OPEN, DOWN_CAGE, TO_SHOT, CLIMB_SHOT, SHOOT, DOWN_SHOT }
    private Hunt hunt = Hunt.NONE;
    private int huntId = -1, huntAt;
    private net.minecraft.world.phys.Vec3 huntPos;
    private Direction huntDir;
    private BlockPos huntCol;
    private final Set<Integer> opened = new HashSet<>();
    /** Speedrunners in the end, and when each last said so (to share out the crystals). */
    private static final java.util.Map<String, Long> END_TEAM = new java.util.concurrent.ConcurrentHashMap<>();

    /** True while it is after a crystal the ground can't see. */
    private boolean crystalHunt(AgentHandle a, BlockView w, List<Seen> crystals) {
        Seen c = null;
        for (Seen s : crystals) if (s.id() == huntId) c = s;
        if (c == null) {
            // Its crystal is gone (shot, or blown up): down if it is up a pillar, then the next one.
            if (hunt == Hunt.CLIMB_SHOT || hunt == Hunt.SHOOT || hunt == Hunt.DOWN_SHOT || hunt == Hunt.CLIMB_CAGE || hunt == Hunt.OPEN || hunt == Hunt.DOWN_CAGE) {
                if (huntCol != null && a.y() > surface(w, huntCol.getX(), huntCol.getZ()) + 1.5) { digDown(a, w); status = "down its pillar"; return true; }
            }
            hunt = Hunt.NONE; huntId = -1;
            // One crystal each: the team in the end, by name, takes them in order (all four up the same column got in
            // each other's way, and one dug the pillar out from under another).
            END_TEAM.put(a.name(), System.currentTimeMillis());
            List<String> team = new ArrayList<>();
            for (var e : END_TEAM.entrySet()) if (System.currentTimeMillis() - e.getValue() < 15_000) team.add(e.getKey());
            java.util.Collections.sort(team);
            List<Seen> left = new ArrayList<>();
            for (Seen s : crystals) if (!cagedCrystals.contains(s.id())) left.add(s);
            if (left.isEmpty()) return false;
            left.sort(java.util.Comparator.comparingInt(Seen::id));
            int me = Math.max(0, team.indexOf(a.name()));
            c = left.get(me % left.size());
            huntId = c.id(); huntPos = c.pos();
            double ix = -c.pos().x, iz = -c.pos().z;
            huntDir = Math.abs(ix) >= Math.abs(iz) ? (ix > 0 ? Direction.EAST : Direction.WEST) : (iz > 0 ? Direction.SOUTH : Direction.NORTH);
            boolean caged = caged(w, c);
            hunt = caged && !opened.contains(c.id()) ? Hunt.TO_CAGE : Hunt.TO_SHOT;
            huntAt = ticks;
            a.log(String.format("[speedrun] after the crystal #%d at %.0f %.0f %.0f%s: up a pillar of its own", c.id(), c.pos().x, c.pos().y, c.pos().z, caged ? " (caged: the bars first)" : ""));
        }
        if (ticks - huntAt > 20 * 150) { a.log("[speedrun] too long after that crystal: leaving it"); cagedCrystals.add(huntId); hunt = Hunt.NONE; huntId = -1; return true; }
        int cx = (int) Math.floor(c.pos().x), cz = (int) Math.floor(c.pos().z), cy = (int) Math.floor(c.pos().y);
        int r = pillarRadius(w, cx, cy, cz, huntDir);
        // Knocked off its pillar (the dragon) or fell: back to the foot of it and up again.
        boolean onCol = huntCol != null && (int) Math.floor(a.x()) == huntCol.getX() && (int) Math.floor(a.z()) == huntCol.getZ();
        if ((hunt == Hunt.OPEN && (a.y() < cy - 1 || !onCol)) || (hunt == Hunt.SHOOT && (a.y() < cy || !onCol))) {
            a.log("[speedrun] off its pillar: back up");
            hunt = hunt == Hunt.OPEN ? Hunt.TO_CAGE : Hunt.TO_SHOT;
        }
        switch (hunt) {
            case TO_CAGE, TO_SHOT -> {
                int off = hunt == Hunt.TO_CAGE ? Math.max(3, r + 1) : 11;
                int x = cx + huntDir.getStepX() * off, z = cz + huntDir.getStepZ() * off;
                huntCol = new BlockPos(x, surface(w, x, z) + 1, z);
                status = "to the foot of its pillar for the crystal #" + c.id();
                if ((int) Math.floor(a.x()) == x && (int) Math.floor(a.z()) == z) { if (a.isNavigating()) a.stopNavigation(); hunt = hunt == Hunt.TO_CAGE ? Hunt.CLIMB_CAGE : Hunt.CLIMB_SHOT; return true; }
                double d = Math.hypot(a.x() - (x + 0.5), a.z() - (z + 0.5));
                if (d < 1.3) { if (a.isNavigating()) a.stopNavigation(); a.stepToward(x + 0.5, z + 0.5); return true; }
                if (!a.isNavigating() && ticks % 20 == 0) a.navigate(x, huntCol.getY(), z, 0);
            }
            case CLIMB_CAGE, CLIMB_SHOT -> {
                // Up to the crystal's height (its feet), a block at a time.
                int want = hunt == Hunt.CLIMB_CAGE ? cy : cy + 1;
                status = String.format("pillaring up for the crystal #%d (%.0f of %d)", c.id(), a.y(), want);
                if (a.y() >= want - 0.01) { hunt = hunt == Hunt.CLIMB_CAGE ? Hunt.OPEN : Hunt.SHOOT; return true; }
                if (a.isNavigating()) a.stopNavigation();
                if (ticks % 4 == 0 && !a.airborne()) {
                    if (!hold(a, "minecraft:cobblestone") && !hold(a, "minecraft:end_stone") && !hold(a, "minecraft:cobbled_deepslate")) { status = "out of blocks to pillar with"; cagedCrystals.add(c.id()); hunt = Hunt.NONE; return true; }
                    a.pillarUp();
                }
            }
            case OPEN -> {
                // The bars on its side of the cage, level with the crystal and above (the line the arrow takes).
                status = "breaking the cage round the crystal #" + c.id();
                if (a.isDigging()) return true;
                for (int dy = 0; dy <= 2; dy++) {
                    for (int k = 1; k <= 3; k++) {
                        int bx = cx + huntDir.getStepX() * k, bz = cz + huntDir.getStepZ() * k;
                        BlockState s = w.get(bx, cy + dy, bz);
                        if (s != null && s.getBlock() == Blocks.IRON_BARS) {
                            if (ticks % 4 != 0) return true;
                            Gear.holdBestFor(a, s);
                            a.lookAt(bx + 0.5, cy + dy + 0.5, bz + 0.5);
                            a.dig(bx, cy + dy, bz);
                            return true;
                        }
                    }
                }
                opened.add(c.id());
                a.log("[speedrun] the cage round the crystal #" + c.id() + " is open on its side");
                hunt = Hunt.DOWN_CAGE;
            }
            case DOWN_CAGE, DOWN_SHOT -> {
                status = "down its pillar";
                if (a.y() <= huntCol.getY() + 0.2) { hunt = hunt == Hunt.DOWN_CAGE ? Hunt.TO_SHOT : Hunt.NONE; return true; }
                digDown(a, w);
            }
            case SHOOT -> {
                if (!hold(a, "minecraft:bow")) return true;
                double dx = c.pos().x - a.x(), dz = c.pos().z - a.z(), dy = c.pos().y + 1.0 - (a.y() + 1.62);
                status = String.format("shooting the crystal #%d from its pillar (%.0f blocks)", c.id(), Math.hypot(dx, dz));
                float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
                a.look(yaw, bowPitch(Math.hypot(dx, dz), dy));
                if (drawAt < 0) { a.useItem(); drawAt = ticks; return true; }
                if (ticks - drawAt >= 22) {
                    a.releaseUse(); drawAt = -1;
                    if (++crystalShots > 12) { a.log("[speedrun] 12 arrows and the crystal #" + c.id() + " still stands: leaving it"); cagedCrystals.add(c.id()); hunt = Hunt.DOWN_SHOT; }
                }
            }
            default -> { return false; }
        }
        return true;
    }

    /** A cage of iron bars round this crystal. */
    private static boolean caged(BlockView w, Seen c) {
        int cx = (int) Math.floor(c.pos().x), cy = (int) Math.floor(c.pos().y), cz = (int) Math.floor(c.pos().z);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = 0; dy <= 3; dy++) {
            BlockState s = w.get(cx + dx, cy + dy, cz + dz);
            if (s != null && s.getBlock() == Blocks.IRON_BARS) return true;
        }
        return false;
    }

    /** How far the obsidian of this pillar goes out from its middle in this direction (a few blocks under the top). */
    private static int pillarRadius(BlockView w, int cx, int cy, int cz, Direction d) {
        int r = 0;
        for (int k = 1; k <= 8; k++) {
            BlockState s = w.get(cx + d.getStepX() * k, cy - 3, cz + d.getStepZ() * k);
            if (s == null || s.getBlock() != Blocks.OBSIDIAN) break;
            r = k;
        }
        return r;
    }

    /** Down its own pillar: the block under its feet dug, it drops a block, again. */
    private void digDown(AgentHandle a, BlockView w) {
        if (a.isDigging() || a.airborne() || ticks % 3 != 0) return;
        if (drawAt >= 0) { a.releaseUse(); drawAt = -1; }
        int x = (int) Math.floor(a.x()), y = (int) Math.floor(a.y() + 0.01) - 1, z = (int) Math.floor(a.z());
        BlockState s = w.get(x, y, z);
        if (s == null || Rules.canWalkThrough(s)) return;
        Gear.holdBestFor(a, s);
        a.lookAt(x + 0.5, y + 0.5, z + 0.5);
        a.dig(x, y, z);
    }

    private BlockPos shotSpot;
    private int shotSpotAt = -1000;

    /** A place on the island that sees the most crystals (the nearest of those), or null. */
    private BlockPos shotSpot(BlockView w, List<Seen> crystals) {
        BlockPos best = null; int bestN = 0;
        for (int r = 20; r <= 90; r += 10) for (int k = 0; k < 24; k++) {
            int x = (int) Math.round(r * Math.cos(k * Math.PI / 12)), z = (int) Math.round(r * Math.sin(k * Math.PI / 12));
            int y = surface(w, x, z);
            BlockState top = w.get(x, y, z);
            if (top == null || top.getBlock() != Blocks.END_STONE) continue;
            int n = 0;
            for (Seen c : crystals) {
                if (cagedCrystals.contains(c.id())) continue;
                double dd = Math.sqrt((c.pos().x - x) * (c.pos().x - x) + (c.pos().y - y) * (c.pos().y - y) + (c.pos().z - z) * (c.pos().z - z));
                if (dd <= SHOT_RANGE - 5 && Sight.clear(w, x + 0.5, y + 1 + 1.62, z + 0.5, c.pos().x, c.pos().y + 1.8, c.pos().z)) n++;
            }
            if (n > bestN) { bestN = n; best = new BlockPos(x, y + 1, z); }
        }
        return best;
    }

    private int fountainTopCache = -1;
    /** The top of the bedrock in the middle of the exit portal (where the dragon lands). */
    private int fountainTop(BlockView w) {
        if (fountainTopCache > 0) return fountainTopCache;
        for (int y = 120; y > 30; y--) {
            BlockState s = w.get(0, y, 0);
            if (s != null && s.getBlock() == Blocks.BEDROCK) { fountainTopCache = y; return y; }
        }
        return 64;
    }

    /** The pitch (degrees, down positive) that lands a full-draw arrow dy above it, h away: arrows drop. */
    private static float bowPitch(double h, double dy) {
        float best = 0; double bestErr = Double.MAX_VALUE;
        for (float p = -70; p <= 40; p += 0.25f) {
            double r = Math.toRadians(-p), vx = 3 * Math.cos(r), vy = 3 * Math.sin(r), x = 0, y = 0;
            for (int t = 0; t < 300 && x < h; t++) { x += vx; y += vy; vx *= 0.99; vy = vy * 0.99 - 0.05; }
            if (x < h) continue;
            double err = Math.abs(y - dy);
            if (err < bestErr) { bestErr = err; best = p; }
        }
        return best;
    }

    /** Its best weapon in hand: an axe, else a sword. */
    private boolean holdWeapon(AgentHandle a) {
        for (String id : new String[]{"minecraft:netherite_axe", "minecraft:diamond_axe", "minecraft:iron_axe", "minecraft:stone_axe", "minecraft:netherite_sword", "minecraft:diamond_sword", "minecraft:iron_sword", "minecraft:stone_sword", "minecraft:wooden_axe"})
            if (count(a, id) > 0) return hold(a, id);
        return true;
    }

    /** After the dragon: the exit portal in the middle (it opens when the dragon dies) takes it home. */
    private void goHomeFromEnd(AgentHandle a, BlockView w) {
        List<BlockPos> portal = w.findAll((int) Math.floor(a.x()), (int) Math.floor(a.z()), 64, s -> s.getBlock() == Blocks.END_PORTAL, 20);
        if (portal.isEmpty() && ticks - dragonDeadAt > 400) {
            // Not in its picture of the blocks (it came as block updates): into the ring round the pillar anyway, where
            // the portal is; touching it is enough.
            status = "the dragon is dead: into the exit portal round the pillar";
            double d = Math.hypot(a.x() - 2.5, a.z() - 0.5);
            if (d < 1.2) { if (a.isNavigating()) a.stopNavigation(); a.stepToward(1.5, 0.5); }
            else if (!a.isNavigating() && ticks % 20 == 0) a.navigate(2, fountainTop(w) - 3, 0, 0);
            return;
        }
        if (portal.isEmpty()) {
            status = "the dragon is dead: waiting for the exit portal";
            if (ticks % 400 == 0) {
                int top = fountainTop(w);
                StringBuilder sb = new StringBuilder();
                for (int y = top - 6; y <= top + 1; y++) { BlockState s = w.get(1, y, 1); sb.append(" y").append(y).append("=").append(s == null ? "?" : s.getBlock().getDescriptionId().replace("block.minecraft.", "")); }
                a.log("[speedrun] the fountain at 1 ? 1:" + sb);
            }
            return;
        }
        BlockPos p = portal.get(0);
        for (BlockPos q : portal) if (q.distToCenterSqr(a.x(), a.y(), a.z()) < p.distToCenterSqr(a.x(), a.y(), a.z())) p = q;
        status = "the dragon is dead: home through the exit portal";
        double d = Math.hypot(a.x() - (p.getX() + 0.5), a.z() - (p.getZ() + 0.5));
        if (d < 1.8) { if (a.isNavigating()) a.stopNavigation(); a.stepToward(p.getX() + 0.5, p.getZ() + 0.5); return; }
        if (!a.isNavigating() && ticks % 20 == 0) a.navigate(p.getX(), p.getY() + 1, p.getZ(), 1);
    }

    /** The eye's flight is over: walk the way it went, or the stronghold is right here. */
    private void finishFlight(AgentHandle a, BlockView w) {
        net.minecraft.world.phys.Vec3 first = eyeFirst, last = eyeLast;
        eyeId = -1;
        if (first == null || last == null) return;
        double dx = last.x - first.x, dz = last.z - first.z, h = Math.hypot(dx, dz);
        eyeDrop = groundUnder(w, last); eyeTicks = 0;
        if (h < 1.5 && last.y < first.y + 0.5) {
            strongholdHere = true;
            a.log("§a[speedrun] the eye went straight down: the stronghold is under " + BlockPos.containing(last).toShortString());
            return;
        }
        // Pointing back the way it came: it walked past the stronghold. Each time that happens the walk is halved, so it
        // closes in instead of overshooting back and forth; down to a few blocks, it is standing over it.
        double ux = dx / h, uz = dz / h;
        if (lastDirX * ux + lastDirZ * uz < -0.3) legLen = Math.max(4, legLen / 2);
        lastDirX = ux; lastDirZ = uz;
        if (legLen <= 8) {
            strongholdHere = true;
            a.log("§a[speedrun] the eyes keep pointing back and forth here: the stronghold is under " + BlockPos.containing(a.x(), a.y(), a.z()).toShortString());
            return;
        }
        int gx = (int) Math.round(a.x() + ux * legLen), gz = (int) Math.round(a.z() + uz * legLen);
        legGoal = new BlockPos(gx, (int) Math.floor(a.y()), gz);
        legAt = ticks;
        a.log("[speedrun] the eye flew " + compass(dx, dz) + ": walking " + legLen + " blocks that way, to " + gx + " " + gz);
    }

    private static String compass(double dx, double dz) {
        String[] n = {"south", "south-west", "west", "north-west", "north", "north-east", "east", "south-east"};
        double ang = Math.toDegrees(Math.atan2(-dx, dz));
        return n[(int) Math.floorMod(Math.round(ang / 45.0), 8)];
    }

    // ------------------------------------------------------------------ the box by the spawner
    //
    // Four blazes around it in the open: its shield faced one, the other three burned it down in seconds. So, like a
    // speedrunner: cobblestone on three sides at feet and head height and a roof, the side toward the spawner left open.
    // Fireballs only come in through that side, where the shield is; new blazes come out right in front of it, in reach.

    private BlockPos boxAt;
    private Direction boxOpen;
    private boolean boxDone;
    private int boxTicks, eatUntil, boxIdle;

    private boolean atBox() {
        return boxAt != null && Math.abs(lastX - (boxAt.getX() + 0.5)) < 0.7 && Math.abs(lastZ - (boxAt.getZ() + 0.5)) < 0.7;
    }
    private double lastX, lastZ;

    /** True while it is building. */
    private boolean buildBox(AgentHandle a, BlockView w, Seen blaze) {
        lastX = a.x(); lastZ = a.z();
        if (boxAt == null) {
            boxAt = BlockPos.containing(a.x(), a.y() + 0.2, a.z());
            // Open toward the spawner (the blazes come from there), else toward the blaze.
            double tx = spawner != null ? spawner.getX() + 0.5 : blaze != null ? blaze.pos().x : a.x() + 1;
            double tz = spawner != null ? spawner.getZ() + 0.5 : blaze != null ? blaze.pos().z : a.z();
            double dx = tx - a.x(), dz = tz - a.z();
            boxOpen = Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
            boxTicks = 0;
            a.log("[speedrun] boxing itself in" + (spawner != null && atSpawner(a) ? " by the spawner" : " where the blazes caught it") + ", open to the " + boxOpen.getName());
        }
        if (++boxTicks > 20 * 20) { boxDone = true; return false; } // as far as it got
        if (a.isNavigating()) a.stopNavigation();
        // Into the middle of its cell first.
        if (Math.abs(a.x() - (boxAt.getX() + 0.5)) > 0.15 || Math.abs(a.z() - (boxAt.getZ() + 0.5)) > 0.15) { a.stepToward(boxAt.getX() + 0.5, boxAt.getZ() + 0.5); return true; }
        if (boxTicks % 4 != 0) return true;
        List<BlockPos> cells = new ArrayList<>();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (d == boxOpen) continue;
            cells.add(boxAt.relative(d));
            cells.add(boxAt.relative(d).above());
        }
        cells.add(boxAt.above(2));
        for (BlockPos c : cells) {
            BlockState s = w.get(c.getX(), c.getY(), c.getZ());
            if (s == null || solid(s)) continue;
            // Something solid next to it to place against: below, or beside, or the block above the open side.
            Direction face = null;
            BlockPos against = null;
            for (Direction d : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP}) {
                BlockPos n = c.relative(d);
                if (n.equals(boxAt) || n.equals(boxAt.above())) continue;
                if (solid(w.get(n.getX(), n.getY(), n.getZ()))) { against = n; face = d.getOpposite(); break; }
            }
            if (against == null) continue;
            if (!hold(a, "minecraft:cobblestone")) { boxDone = true; return false; }
            a.lookAt(against.getX() + 0.5 + face.getStepX() * 0.5, against.getY() + 0.5 + face.getStepY() * 0.5, against.getZ() + 0.5 + face.getStepZ() * 0.5);
            a.placeAgainst(against.getX(), against.getY(), against.getZ(), face);
            status = "boxing itself in by the spawner";
            return true;
        }
        boxDone = true;
        a.log("[speedrun] boxed in by the spawner");
        return false;
    }

    /** In the box and hurt, with no blaze in reach: shield down, a bite, shield back up. True while eating. */
    private boolean boxEat(AgentHandle a, Seen blaze) {
        lastX = a.x(); lastZ = a.z();
        if (ticks < eatUntil) return true;
        // With blazes in sight, only when it must: eating takes a second and a half with the shield down.
        if (a.health() >= (blaze != null ? 8 : 12) || blaze != null && blaze.distance() <= 3.4) return false;
        for (Seen s : a.entitiesNear(3.5)) if (!s.player() && s.hostile()) return false; // something right on it: fight first
        List<ItemStack> inv = a.inventory();
        int food = -1;
        for (int i = 0; i < inv.size(); i++) if (Gear.isFood(inv.get(i)) && !Gear.id(inv.get(i)).contains("golden_apple")) { food = i; break; }
        if (food < 0) return false;
        if (blocking) { a.releaseUse(); blocking = false; }
        if (food >= 9) { a.moveToHotbar(food, 8); food = 8; }
        a.selectHotbar(food);
        a.useItem();
        eatUntil = ticks + 36;
        status = "eating (" + Math.round(a.health()) + " hp)";
        return true;
    }

    /** Beside the spawner, measured along the ground (the block often sits a step above the floor). */
    private boolean atSpawner(AgentHandle a) {
        if (spawner == null) return false;
        double dx = a.x() - (spawner.getX() + 0.5), dz = a.z() - (spawner.getZ() + 0.5);
        return dx * dx + dz * dz <= 2.6 * 2.6 && Math.abs(a.y() - spawner.getY()) <= 2.5;
    }

    // ---- the tank (the user's idea): one helper hits every blaze once, so they all go for it; the others hit them from
    //      behind while they are busy with it. Damage doesn't matter; it keeps its shield to the lit ones.
    private static volatile String TANK;
    private static final java.util.Map<Integer, Long> TAGGED = new java.util.concurrent.ConcurrentHashMap<>();

    private boolean isTank(AgentHandle a) {
        if (TANK == null || !online(TANK) || TANK.equalsIgnoreCase(MAIN)) { TANK = a.name(); a.log("§a[speedrun] " + a.name() + " draws the blazes (the tank)"); }
        return TANK.equalsIgnoreCase(a.name());
    }

    private void tank(AgentHandle a, BlockView w, Seen lit, int rods) {
        long now = System.currentTimeMillis();
        TAGGED.values().removeIf(t -> now - t > 20_000);
        Seen next = null;
        for (Seen s : a.entitiesNear(20)) {
            if (!s.name().equals("blaze") || TAGGED.containsKey(s.id())) continue;
            if (!Sight.clear(w, a.x(), a.y() + 1.62, a.z(), s.pos().x, s.pos().y + 0.9, s.pos().z)) continue;
            if (next == null || s.distance() < next.distance()) next = s;
        }
        boolean shield = Gear.id(a.offhand()).equals("minecraft:shield");
        foe = next != null ? next.id() : lit != null ? lit.id() : -1;
        if (next != null && eyeDist(a, next) <= 3.1 && swingCd == 0) {
            if (blocking) { a.releaseUse(); blocking = false; return; }
            if (a.isNavigating()) a.stopNavigation();
            a.lookAt(next.pos().x, next.pos().y + 0.9, next.pos().z);
            a.attack(next.id());
            swingCd = 10;
            TAGGED.put(next.id(), now);
            status = "tagging the blazes: " + TAGGED.size() + " on it";
            return;
        }
        if (next != null && !a.airborne() && (!a.isNavigating() || ticks % 20 == 0)) {
            BlockPos g = groundUnder(w, next.pos());
            a.navigate(g.getX(), g.getY(), g.getZ(), 1);
        }
        Seen face = lit != null ? lit : next;
        if (face != null && shield) {
            if (!blocking) { a.useOffhand(); blocking = true; }
            a.faceWhileMoving(face.pos().x, face.pos().y + 0.9, face.pos().z);
            if (!a.isNavigating()) a.lookAt(face.pos().x, face.pos().y + 0.9, face.pos().z);
        }
        if (next == null) {
            status = "holding the blazes, shield up (" + TAGGED.size() + " on it)";
            if (spawner != null && spawner.distToCenterSqr(a.x(), a.y(), a.z()) > 4 * 4 && !a.isNavigating() && ticks % 20 == 0) a.navigate(spawner.getX(), spawner.getY(), spawner.getZ(), 2);
        } else status = "going to tag a blaze (" + TAGGED.size() + " on it)";
    }

    /** From its eyes to the middle of the mob: what counts for reach (a blaze hovering over it is in reach from below). */
    private static double eyeDist(AgentHandle a, Seen s) {
        double mid = s.name().equals("enderman") ? 1.45 : s.name().equals("magma_cube") || s.name().equals("spider") ? 0.4 : 0.9;
        double dx = s.pos().x - a.x(), dy = s.pos().y + mid - (a.y() + 1.62), dz = s.pos().z - a.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Other speedrunners online anywhere (its helpers, wherever they are). */
    private static int helpersAnywhere(AgentHandle a) {
        int n = 0;
        for (AgentHandle h : dev.agentnet.bus.AgentBus.agents.get())
            if (h != a && !h.name().equalsIgnoreCase(a.name()) && h.isOnline() && (h.role() == Role.SPEEDRUNNER || h.role() == Role.FALLOFF)) n++;
        return n;
    }

    /** Other speedrunners within 32 blocks in its dimension (the helpers with it). */
    private static int helpersHere(AgentHandle a) {
        int n = 0;
        for (AgentHandle h : dev.agentnet.bus.AgentBus.agents.get()) {
            if (h == a || h.name().equalsIgnoreCase(a.name()) || !h.isOnline() || h.role() != Role.SPEEDRUNNER && h.role() != Role.FALLOFF || !AgentHandle.sameDim(h.dimension(), a.dimension())) continue;
            if ((h.x() - a.x()) * (h.x() - a.x()) + (h.z() - a.z()) * (h.z() - a.z()) < 32 * 32) n++;
        }
        return n;
    }

    /** About eight blocks from the spawner, on the side it is on already. */
    private void keepBack(AgentHandle a) {
        if (spawner == null) return;
        double dx = a.x() - (spawner.getX() + 0.5), dz = a.z() - (spawner.getZ() + 0.5), d = Math.hypot(dx, dz);
        if (d >= 6.5) { if (a.isNavigating() && d < 11) a.stopNavigation(); return; }
        if (a.isNavigating() || ticks % 20 != 0) return;
        if (d < 0.5) { dx = 1; dz = 0; d = 1; }
        a.navigate((int) Math.floor(spawner.getX() + 0.5 + dx / d * 9), (int) Math.floor(a.y() + 0.2), (int) Math.floor(spawner.getZ() + 0.5 + dz / d * 9), 2);
    }

    // ------------------------------------------------------------------ a helper

    private void helper(AgentHandle a) {
        AgentHandle m = MAIN == null ? null : GathererModule.find(MAIN);
        if (m == null) { status = "waiting for the main speedrunner"; return; }
        if (a.dimension() != null && a.dimension().endsWith("the_end")) { if (stage != Stage.END) go(a, Stage.END); theEnd(a); return; }
        if (m.dimension() != null && m.dimension().endsWith("the_end") && mainEndPortal != null && !nether(a)) {
            BlockPos p = mainEndPortal;
            status = "following " + MAIN + " into the end";
            double d = Math.hypot(a.x() - (p.getX() + 0.5), a.z() - (p.getZ() + 0.5));
            if (d < 2.2 && a.y() >= p.getY() - 0.2) { if (a.isNavigating()) a.stopNavigation(); a.stepToward(p.getX() + 0.5, p.getZ() + 0.5); return; }
            if (!a.isNavigating() && ticks % 20 == 0) a.navigate(p.getX(), p.getY() + 1, p.getZ(), 2);
            return;
        }
        boolean mNether = m.dimension() != null && m.dimension().endsWith("the_nether");
        if (!nether(a)) {
            // The main went through its portal: after it.
            if (mNether && mainPortal != null) {
                if (netherArmor(a)) return; // full iron and a golden helmet first, like the main
                status = "following " + MAIN + " into the nether";
                // The main's way in: onto the block in front of the frame, to its middle, then one straight step in (the drop
                // does the rest). Stepping in at a slant from wherever it got to clipped the frame; the server put it back
                // every time, and that ended in kicks.
                // On the block in front for 3 s and not in (put back: it stood on something and its head caught the top of
                // the frame): the other column.
                // (By the clock and kept by name: being put back again and again, it leaves and rejoins, and a fresh start
                // forgot how long it had been trying.)
                long since = FRONT_SINCE.getOrDefault(a.name(), -1L), nowMs = System.currentTimeMillis();
                if (since >= 0 && nowMs - since > 3000 && mainPortal2 != null) { OTHER_COLUMN.merge(a.name(), true, (x, y) -> !x); FRONT_SINCE.remove(a.name()); centred = false; a.log("[speedrun] can't get in this side: trying the portal's other column"); }
                boolean otherColumn = OTHER_COLUMN.getOrDefault(a.name(), false);
                BlockPos cell = otherColumn && mainPortal2 != null ? mainPortal2 : mainPortal;
                BlockPos front = otherColumn && mainPortalFront2 != null ? mainPortalFront2 : mainPortalFront != null ? mainPortalFront : mainPortal;
                boolean inside = (int) Math.floor(a.x()) == cell.getX() && (int) Math.floor(a.z()) == cell.getZ() && Math.abs(a.y() - cell.getY()) < 1.2;
                if (inside) { if (a.isNavigating()) a.stopNavigation(); return; }
                boolean onFront = (int) Math.floor(a.x()) == front.getX() && (int) Math.floor(a.z()) == front.getZ() && Math.abs(a.y() - front.getY()) < 1.2;
                if (onFront) {
                    FRONT_SINCE.putIfAbsent(a.name(), System.currentTimeMillis());
                    if (a.isNavigating()) a.stopNavigation();
                    double fx = front.getX() + 0.5, fz = front.getZ() + 0.5;
                    if (!centred && Math.hypot(a.x() - fx, a.z() - fz) > 0.12) { a.stepToward(fx, fz); return; }
                    centred = true;
                    a.stepToward(cell.getX() + 0.5, cell.getZ() + 0.5);
                    return;
                }
                centred = false; FRONT_SINCE.remove(a.name());
                if (!a.isNavigating() && ticks % 20 == 0) a.navigate(front.getX(), front.getY(), front.getZ(), 0);
                return;
            }
            // Rods or pearls in its pack and the main right here: to the main.
            if (count(a, "minecraft:blaze_rod") > 0 && handOver(a, "minecraft:blaze_rod")) return;
            if (count(a, "minecraft:ender_pearl") > 0 && handOver(a, "minecraft:ender_pearl")) return;
            if (!mNether) {
                String before = status;
                boolean busy = prepBoats(a);
                if (!status.equals(before) && !status.equals(prepSaid) && status.contains("boat")) { prepSaid = status; a.log("[speedrun] " + status); }
                if (busy) return;
            }
            if (!mNether && helperGear(a)) return;
            if (!mNether && helperJob(a)) return;
            if (!mNether && helperOwn(a)) return;
            follow(a);
            return;
        }
        // In the nether. The main went home: back through the portal it came out of.
        if (!mNether && mainNetherPortal != null && !(a.role() == Role.FALLOFF && !falloffHome)) {
            status = "following " + MAIN + " home";
            double d = Math.hypot(a.x() - (mainNetherPortal.getX() + 0.5), a.z() - (mainNetherPortal.getZ() + 0.5));
            if (d < 1.4 && Math.abs(a.y() - mainNetherPortal.getY()) < 1.5) { if (a.isNavigating()) a.stopNavigation(); a.stepToward(mainNetherPortal.getX() + 0.5, mainNetherPortal.getZ() + 0.5); return; }
            if (!a.isNavigating() && ticks % 20 == 0) a.navigate(mainNetherPortal.getX(), mainNetherPortal.getY(), mainNetherPortal.getZ(), 0);
            return;
        }
        if (stage != Stage.NETHER) go(a, Stage.NETHER);
        blazes(a); // rods, then pearls, thrown to the main as they drop
    }

    /**
     * Two boats for the endermen, made in the overworld (nether wood makes none): wood, the recipe (it is learnt by
     * stepping into water), then the boats. True while it is busy with that.
     */
    private int plankAt = -1000;
    /** On the block in front of the main's portal and in its middle: now straight in. */
    private boolean centred;
    private static final java.util.Map<String, Long> FRONT_SINCE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, Boolean> OTHER_COLUMN = new java.util.concurrent.ConcurrentHashMap<>();
    private boolean prepBoats(AgentHandle a) {
        int boats = 0;
        for (ItemStack s : a.inventory()) { String id = Gear.id(s); if (id.endsWith("_boat") || id.endsWith("_raft")) boats += s.getCount(); }
        if (boats >= 2) { if (boatJob) { boatJob = false; mine(a, null); } return false; }
        if (crafter.busy()) return true;
        String planks = MakerModule.planksFromLog(a);
        if (planks == null) for (ItemStack s : a.inventory()) { String id = Gear.id(s); if (id.endsWith("_planks") && !id.contains("crimson") && !id.contains("warped") && !id.contains("bamboo")) { planks = id; break; } }
        String type = planks == null ? null : planks.replace("minecraft:", "").replace("_planks", "");
        int wood = 0;
        if (type != null) for (ItemStack s : a.inventory()) {
            String id = Gear.id(s).replace("minecraft:", "");
            if (id.equals(type + "_planks")) wood += s.getCount();
            else if (id.equals(type + "_log") || id.equals(type + "_wood")) wood += 4 * s.getCount();
        }
        int need = 5 * (2 - boats) + (count(a, "minecraft:crafting_table") > 0 ? 0 : 4);
        if (type == null || type.equals("crimson") || type.equals("warped") || wood < need) {
            boatJob = true;
            status = "getting wood for boats";
            if (minerJob != null && miner.idle() && miner.status().startsWith("done") && ticks - jobRestart > 100) { minerJob = null; jobRestart = ticks; }
            mine(a, "mine 4 logs");
            miner.tick(a);
            return true;
        }
        if (boatJob) { boatJob = false; mine(a, null); }
        // Planks first: making boats by hand (recipe not unlocked yet) takes the planks it has, not logs.
        int plankCount = count(a, "minecraft:" + type + "_planks");
        if (plankCount < need && ticks - plankAt > 100) {
            plankAt = ticks;
            int logs = (need - plankCount + 3) / 4;
            crafter.onTask(a, "make " + type + "_planks " + (4 * logs));
            status = "making planks for boats";
            return true;
        }
        // Crafting is tried first (the recipe book it has may not list it even when it knows it); two tries with no boat
        // to show for them: into water for the recipe, then again.
        if (boats != boatsBefore) { boatsBefore = boats; boatTries = 0; }
        // Two tries without a boat: 20 s getting into water (that teaches the recipe), once; two more tries; then it
        // says so and goes on without boats.
        if (boatTries >= 2 && !waterDone && waterAt < 0) waterAt = ticks;
        if (waterAt >= 0 && ticks - waterAt >= 20 * 20) { waterAt = -1; waterDone = true; boatTries = 0; }
        if (boatTries >= 2 && waterDone) { status = "can't make boats"; return false; }
        boolean known = waterAt < 0;
        if (!known) {
            // The boat recipe comes with the first step into water.
            if (!(a.openWorld() instanceof BlockView w)) return true;
            if (ticks % 40 == 0 || waterGoal == null) {
                List<BlockPos> water = w.findAll((int) Math.floor(a.x()), (int) Math.floor(a.z()), 48, SpeedrunnerModule::waterSource, 50);
                waterGoal = null;
                double bd = Double.MAX_VALUE;
                for (BlockPos p : water) {
                    BlockState over = w.get(p.getX(), p.getY() + 1, p.getZ());
                    if (over == null || !over.isAir()) continue;
                    double d = p.distToCenterSqr(a.x(), a.y(), a.z());
                    if (d < bd) { bd = d; waterGoal = p; }
                }
            }
            if (waterGoal == null) { status = "no water near to learn the boat recipe"; return false; }
            status = "stepping into water (that teaches it the boat recipe)";
            // Into it, not beside it: the recipe comes with entering a water block (standing next to the pool taught it nothing).
            double wd = Math.hypot(a.x() - (waterGoal.getX() + 0.5), a.z() - (waterGoal.getZ() + 0.5));
            if (wd < 1.6) { if (a.isNavigating()) a.stopNavigation(); a.stepToward(waterGoal.getX() + 0.5, waterGoal.getZ() + 0.5); }
            else if (!a.isNavigating() && ticks % 20 == 0) a.navigate(waterGoal.getX(), waterGoal.getY() + 1, waterGoal.getZ(), 1);
            return true;
        }
        if (ticks - craftAt > 200) { craftAt = ticks; boatTries++; crafter.onTask(a, "make " + type + "_boat " + (2 - boats)); }
        status = "making boats for the endermen";
        return true;
    }
    private boolean boatJob;
    private String prepSaid = "";
    private int boatTries, boatsBefore = -1, waterAt = -1;
    private boolean waterDone;
    private BlockPos waterGoal;

    private int helperGearAt = -1000;

    /**
     * A helper with no stone axe (its weapon) makes one while the main gears up, instead of standing by it for minutes
     * ("AFK: with FUZZYLuna (3 blocks)") and fighting with its fists when the night comes: stone mined, then the axe.
     */
    private boolean helperGear(AgentHandle a) {
        int axeAt = Gear.bestTool(a.inventory(), "axe");
        boolean goodAxe = axeAt >= 0 && !Gear.id(a.inventory().get(axeAt)).equals("minecraft:wooden_axe");
        // A stone pickaxe too: the main digs its way about (out of caves, up to the surface) and a helper with no
        // pickaxe was left behind in a cave, unable to follow it through the stone.
        int pickAt = Gear.bestTool(a.inventory(), "pickaxe");
        boolean goodPick = pickAt >= 0 && !Gear.id(a.inventory().get(pickAt)).equals("minecraft:wooden_pickaxe");
        if (goodAxe && goodPick) { if ("mine 8 stone".equals(minerJob)) mine(a, null); return false; }
        if (count(a, "minecraft:cobblestone") + count(a, "minecraft:cobbled_deepslate") >= 3) {
            if ("mine 8 stone".equals(minerJob)) mine(a, null);
            // A crafting table to make it on (carried, near, or the wood for one): without, it queued the axe every 5 s for
            // minutes, "making itself a stone axe", and stood still (run 1120c).
            if (tableKit(a)) return true;
            // Two sticks for the handle (a plank's worth of wood): with none at all it "made" the tool every 10 s and stood
            // still for minutes (run 1120n: MrObsidian, 81 cobblestone and no wood). It chops two logs first.
            { int sticks = count(a, "minecraft:stick"), wood = 0;
              for (ItemStack s : a.inventory()) { String id = Gear.id(s); if (id.endsWith("_planks")) wood += s.getCount(); else if ((id.endsWith("_log") || id.endsWith("_stem")) && !id.contains("stripped")) wood += 4 * s.getCount(); }
              if (sticks < 2 && wood < 1) {
                  status = "chopping wood for the handle of its stone tools";
                  if (minerJob != null && miner.idle() && miner.status().startsWith("done") && ticks - jobRestart > 100) { minerJob = null; jobRestart = ticks; }
                  mine(a, "mine 2 logs");
                  miner.tick(a);
                  return true;
              }
              if ("mine 2 logs".equals(minerJob)) mine(a, null); }
            String tool = goodAxe ? "minecraft:stone_pickaxe" : "minecraft:stone_axe";
            if (ticks - helperGearAt > 200) { helperGearAt = ticks; if (!crafter.queueTool(a, tool)) return false; a.log("[speedrun] making itself a " + tool.substring(10).replace('_', ' ') + " while " + MAIN + " gears up"); }
            status = "making a " + tool.substring(10).replace('_', ' ');
            return true;
        }
        status = "mining stone for its stone tools while " + MAIN + " gears up";
        if (minerJob != null && miner.idle() && miner.status().startsWith("done") && ticks - jobRestart > 100) { minerJob = null; jobRestart = ticks; }
        mine(a, "mine 8 stone");
        miner.tick(a);
        return true;
    }

    /**
     * Until the dragon is dead the speedrunner comes first for the whole crew (the user: "everyone should bend their
     * knees and go get him some iron"). Iron the main still lacks, for the autopilot and the distributors; 0 once it
     * has it, past the iron stages, offline, or with the game beaten.
     */
    public static int crewIronNeed() {
        if (MAIN == null || beaten()) return 0;
        if (mainStage.ordinal() > Stage.LIGHTER.ordinal()) { AgentHandle m = GathererModule.find(MAIN); return m == null || !m.isOnline() ? 0 : mainKitShort; }
        AgentHandle m = GathererModule.find(MAIN);
        return m == null || !m.isOnline() ? 0 : mainIronShort;
    }

    /** The main speedrunner (null before there is one): its name is drawn in the role's purple, the helpers' lighter. */
    public static String mainRunner() { return MAIN; }

    /** A speedrunner is online: when it first was, for the first-is-main rule (before anything else it does). */
    public static void noteStart(String name) { FIRST_SEEN.putIfAbsent(name, System.currentTimeMillis()); }

    /** The main has its portal frame up (or is past it): from then on the falloffs are worth hiring. */
    public static boolean mainAtPortal() { return MAIN != null && mainStage.ordinal() >= Stage.LIGHT.ordinal(); }

    /** Logs of any kind it carries. */
    private static int countLogs(AgentHandle a) {
        int n = 0;
        for (ItemStack s : a.inventory()) if (!s.isEmpty() && Gear.id(s).endsWith("_log") && !Gear.id(s).contains("stripped")) n += s.getCount();
        return n;
    }

    /** The main speedrunner while the game isn't beaten yet (its requests go first), else null. */
    public static String priorityRunner() { return MAIN == null || beaten() ? null : MAIN; }

    private int ironAskedAt = -10000;

    private int woodAskedAt = -10000;

    /** Wood running short (sticks, tables, the shield, boats, fuel): the crew is asked for logs of any kind, once a minute.
     * In snowy mountains with no tree in 64 blocks it walked off to look for them and the run stood still. */
    private void askCrewForWood(AgentHandle a) {
        if (stage.ordinal() > Stage.POOL.ordinal() || nether(a) || ticks - woodAskedAt < 1200) return;
        int wood = 0;
        for (ItemStack s : a.inventory()) { String id = Gear.id(s); if (id.endsWith("_planks")) wood += s.getCount(); else if ((id.endsWith("_log") || id.endsWith("_stem")) && !id.contains("stripped")) wood += 4 * s.getCount(); }
        if (wood >= 12) return;
        woodAskedAt = ticks;
        BuilderModule.requestMaterial(a, "logs", 4);
        a.log("[speedrun] asked the crew for logs (" + wood + " planks' worth of wood left)");
    }

    /** The main short of iron: the distributors are asked for it (ingots, and raw iron), once a minute. */
    private void askCrewForIron(AgentHandle a) {
        if (mainIronShort <= 0 || stage.ordinal() > Stage.LIGHTER.ordinal() || ticks - ironAskedAt < 1200) return;
        ironAskedAt = ticks;
        BuilderModule.requestMaterial(a, "minecraft:iron_ingot", mainIronShort);
        BuilderModule.requestMaterial(a, "minecraft:raw_iron", mainIronShort);
        a.log("[speedrun] asked the crew for " + mainIronShort + " iron (the speedrunner comes first until the dragon is dead)");
    }

    /** What the main still needs (set by the main every tick): iron short of its 8, and whether it has flint yet. */
    private static volatile int mainIronShort = 99;
    private static volatile int mainKitShort;
    /** The main has no stone pickaxe (or better) and not the 3 cobblestone for one. */
    private static volatile boolean mainNeedsCobble;
    private static volatile boolean mainNeedsFlint = true;
    /** The main has raw iron to smelt and nothing to burn: the flint helper (its flint done) brings it coal. */
    private static volatile boolean mainNeedsFuel;
    /** Each helper's own job in the overworld, given out in turn: iron, flint, iron. */
    private static final java.util.Map<String, String> HELPER_JOBS = new java.util.concurrent.ConcurrentHashMap<>();
    private boolean helperMining;
    /** The miner is on the helper's OWN gear (helperOwn). Its own flag: sharing helperMining, the job for the main stopped
     *  the miner every tick ("the main needs nothing") and this started it again: 8 400 starts in six minutes, nothing mined. */
    private boolean ownMining;
    private String helperJobSaid;

    /**
     * Split up for the overworld gathering, together for the fights: while the main still needs iron or flint, each
     * helper gets its share on its own (two on iron, one on gravel for flint) and throws it to the main, instead of all
     * four standing round one iron vein ("slow as fuck", the user said: the main mined all 8 iron itself with three
     * helpers watching). From the shield on, they stay with the main (the portal, the nether, the blazes).
     * True while it is busy with its job.
     */
    private boolean helperJob(AgentHandle a) {
        String job = HELPER_JOBS.computeIfAbsent(a.name(), k -> HELPER_JOBS.size() % 3 == 1 ? "flint" : "iron");
        boolean early = mainStage.ordinal() <= Stage.LIGHTER.ordinal();
        // The main without a stone pickaxe and without the cobblestone for one: a helper that has some throws it over. Run
        // 1120k: the main spent five minutes trying to get 3 cobblestone (its drops kept falling where it couldn't walk)
        // while a helper 4 blocks away carried 50.
        if (mainNeedsCobble && MAIN != null) {
            String cobble = count(a, "minecraft:cobblestone") >= 3 ? "minecraft:cobblestone" : count(a, "minecraft:cobbled_deepslate") >= 3 ? "minecraft:cobbled_deepslate" : null;
            if (cobble != null && handOver(a, cobble)) { if (helperMining) { mine(a, null); helperMining = false; } status = "cobblestone to " + MAIN + " for its stone pickaxe"; return true; }
        }
        String item = job.equals("flint") ? "minecraft:flint" : "minecraft:raw_iron";
        int have = count(a, item) + (job.equals("iron") ? count(a, "minecraft:iron_ingot") : 0);
        boolean needed = early && (job.equals("flint") ? mainNeedsFlint : mainIronShort > 0);
        // What it has goes to the main: a load (3 iron, or the flint) while it is still needed, and whatever is left after.
        if (have > 0 && early && (!needed || have >= (job.equals("flint") ? 1 : Math.min(3, mainIronShort)))) {
            // The miner stops only once the hand-over is under way: stopped first, a hand-over that couldn't start (the main
            // dead or away) started the mining again next tick, 27 000 times ("mining 8x iron_ore" / "stopped mining").
            boolean handing = count(a, item) > 0 && handOver(a, item) || job.equals("iron") && count(a, "minecraft:iron_ingot") > 0 && handOver(a, "minecraft:iron_ingot");
            if (handing) { if (helperMining) { mine(a, null); helperMining = false; } return true; }
        }
        // The flint is done and the main has iron to smelt with nothing to burn: coal for it (snowy mountains, no trees).
        if (!needed && early && job.equals("flint") && (mainNeedsFuel || count(a, "minecraft:coal") > 0)) {
            if (count(a, "minecraft:coal") >= 2 || !mainNeedsFuel && count(a, "minecraft:coal") > 0) { if (handOver(a, "minecraft:coal")) { if (helperMining) { mine(a, null); helperMining = false; } return true; } }
            if (mainNeedsFuel) {
                status = "mining coal for " + MAIN + "'s furnace";
                if (!status.equals(helperJobSaid)) { helperJobSaid = status; a.log("[speedrun] " + status); }
                if (minerJob != null && miner.idle() && miner.status().startsWith("done") && ticks - jobRestart > 100) { minerJob = null; jobRestart = ticks; }
                mine(a, "mine 4 coal_ore"); helperMining = true; miner.tick(a);
                return true;
            }
        }
        if (!needed) {
            if (helperMining) { mine(a, null); helperMining = false; }
            return false;
        }
        String mineJob = job.equals("flint") ? "mine 12 gravel" : "mine 8 iron_ore";
        status = job.equals("flint") ? "digging gravel for " + MAIN + "'s flint" : "mining iron for " + MAIN + " (" + mainIronShort + " still needed)";
        if (!status.equals(helperJobSaid)) { helperJobSaid = status; a.log("[speedrun] " + status + ": on its own, the main's helpers split up"); }
        if (minerJob != null && miner.idle() && miner.status().startsWith("done") && ticks - jobRestart > 100) { minerJob = null; jobRestart = ticks; }
        mine(a, mineJob);
        helperMining = true;
        miner.tick(a);
        return true;
    }

    private int ownAt = -1000;
    private String ownSaid;
    private static final String[] ARMOR_SLOTS = {"helmet", "chestplate", "leggings", "boots"};

    /**
     * Its own gear while the main doesn't need it (the user: "there is no need for the assistants to just watch the main
     * guy mine iron"): a shield, an iron chestplate, then an iron helmet, mined, smelted, made and put on by itself, on
     * its own. Until the main starts its portal: from then on they are a team again (the portal, the blazes, the
     * endermen, the dragon). True while busy with it.
     */
    private boolean helperOwn(AgentHandle a) {
        // The main is building its portal (or past it, still in the overworld): its own nether kit meanwhile, wherever the
        // iron and gold are. Before, they had nothing to do from here and five of them stood round the main's pond for
        // minutes (the user: "this isn't a funeral, leave the lake alone").
        if (mainStage.ordinal() >= Stage.CAST.ordinal()) {
            // No shield yet: that first (below), then the kit. (Without one it skipped both and stood by the main for
            // minutes, run 1120g.)
            // Its iron mining stopped only once it has the shield: stopped here every tick and started again below, it said
            // "mining 8x iron_ore" / "stopped mining" 5208 times (run 1120m).
            if (hasShield(a)) { if (ownMining) { mine(a, null); ownMining = false; } return !netherKit(a) && netherArmor(a); }
        }
        if (wearArmor(a)) return true;
        // A shield first, then the whole nether kit (full iron, a golden helmet): ready when the main's portal is.
        if (hasShield(a) && !netherKit(a)) { if (ownMining) { mine(a, null); ownMining = false; } return netherArmor(a); }
        String item = !hasShield(a) ? "minecraft:shield" : null;
        if (item == null) {
            if (ownMining) { mine(a, null); ownMining = false; }
            return false;
        }
        String name = item.substring(10).replace('_', ' ');
        int cost = item.endsWith("shield") ? 1 : item.endsWith("chestplate") ? 8 : 5;
        int ingots = count(a, "minecraft:iron_ingot"), raw = count(a, "minecraft:raw_iron");
        if (ingots >= cost) {
            // A shield is 6 planks and an iron ingot: short of wood (no logs to turn into planks), it chops some first. With
            // 5 planks it tried to make one every 10 s for minutes and never said why (run 1120m: NETHERComet, 53 times).
            if (item.endsWith("shield")) {
                int wood = 0;
                for (ItemStack s : a.inventory()) { String id = Gear.id(s); if (id.endsWith("_planks")) wood += s.getCount(); else if ((id.endsWith("_log") || id.endsWith("_stem")) && !id.contains("stripped")) wood += 4 * s.getCount(); }
                if (wood < 6) {
                    status = "chopping wood for its own shield (" + wood + " of 6 planks)";
                    if (!status.equals(ownSaid)) { ownSaid = status; a.log("[speedrun] " + status); }
                    if (minerJob != null && miner.idle() && miner.status().startsWith("done") && ticks - jobRestart > 100) { minerJob = null; jobRestart = ticks; }
                    mine(a, "mine 2 logs");
                    ownMining = true;
                    miner.tick(a);
                    return true;
                }
            }
            if (ownMining) { mine(a, null); ownMining = false; }
            if (tableKit(a)) return true; // a table to make it on first
            status = "making itself a " + name;
            if (ticks - ownAt > 200) { ownAt = ticks; if (crafter.queueTool(a, item) || queueByHand(a, item)) a.log("[speedrun] making itself a " + name + " while " + MAIN + " works on its own"); }
            return true;
        }
        if (ingots + raw >= cost) {
            if (ownMining) { mine(a, null); ownMining = false; }
            if (furnaceKit(a)) return true;
            status = "smelting iron for its own " + name;
            if (ticks - ownAt > 200) { ownAt = ticks; crafter.onTask(a, "make iron_ingot " + (cost - ingots)); }
            return true;
        }
        status = "mining iron for its own " + name;
        if (!status.equals(ownSaid)) { ownSaid = status; a.log("[speedrun] " + status + " (on its own until " + MAIN + " builds the portal)"); }
        if (minerJob != null && miner.idle() && miner.status().startsWith("done") && ticks - jobRestart > 100) { minerJob = null; jobRestart = ticks; }
        mine(a, "mine 8 iron_ore");
        ownMining = true;
        miner.tick(a);
        return true;
    }

    /** Wears (or carries) a piece of iron armor or better in this slot (0 helmet, 1 chestplate). */
    private static boolean hasArmor(AgentHandle a, int slot) {
        ItemStack on = a.armor().size() > slot ? a.armor().get(slot) : ItemStack.EMPTY;
        if (!on.isEmpty() && on.getItem() != net.minecraft.world.item.Items.ELYTRA && !Gear.id(on).startsWith("minecraft:leather") && !Gear.id(on).startsWith("minecraft:golden")) return true;
        return Gear.bestArmor(a.inventory(), ARMOR_SLOTS[slot]) >= 0 && !Gear.id(a.inventory().get(Gear.bestArmor(a.inventory(), ARMOR_SLOTS[slot]))).startsWith("minecraft:leather");
    }

    private int wornAt = -100;

    /** Puts on armor it carries that beats what it wears, and the shield into its off hand. True while doing so. */
    private boolean wearArmor(AgentHandle a) {
        if (ticks - wornAt < 10) return false;
        List<ItemStack> inv = a.inventory();
        for (int slot = 0; slot < 4; slot++) {
            ItemStack on = a.armor().get(slot);
            if (on.getItem() == net.minecraft.world.item.Items.ELYTRA) continue;
            // The helmet is the golden one (piglins): put on when it has it, and nothing takes its place.
            if (slot == 0) {
                if (Gear.id(on).equals("minecraft:golden_helmet")) continue;
                int g = -1;
                for (int k = 0; k < inv.size(); k++) if (Gear.id(inv.get(k)).equals("minecraft:golden_helmet")) { g = k; break; }
                if (g >= 0) {
                    wornAt = ticks;
                    int ghb = g < 9 ? g : 8;
                    if (g >= 9) a.moveToHotbar(g, ghb);
                    a.selectHotbar(ghb);
                    a.useItem();
                    a.log("[speedrun] puts on its golden helmet");
                    return true;
                }
            }
            int i = Gear.bestArmor(inv, ARMOR_SLOTS[slot]);
            if (i < 0 || !on.isEmpty() && Gear.armorScore(inv.get(i)) <= Gear.armorScore(on) + 1) continue;
            wornAt = ticks;
            int hb = i < 9 ? i : 8;
            if (i >= 9) a.moveToHotbar(i, hb);
            a.selectHotbar(hb);
            a.useItem();
            a.log("[speedrun] puts on its " + inv.get(i).getHoverName().getString());
            return true;
        }
        if (a.offhand().isEmpty()) {
            int s = Gear.bestTool(inv, "shield");
            if (s >= 0) { wornAt = ticks; int hb = s < 9 ? s : 8; if (s >= 9) a.moveToHotbar(s, hb); a.selectHotbar(hb); a.swapOffhand(); return true; }
        }
        return false;
    }

    /** Stays with the main (a few blocks off), fighting what comes. */
    private void follow(AgentHandle a) {
        AgentHandle m = MAIN == null ? null : GathererModule.find(MAIN);
        if (m == null || !AgentHandle.sameDim(m.dimension(), a.dimension())) { status = "waiting for " + MAIN; return; }
        double d = Math.sqrt((m.x() - a.x()) * (m.x() - a.x()) + (m.z() - a.z()) * (m.z() - a.z()));
        status = "with " + MAIN + " (" + Math.round(d) + " blocks)";
        if (d > 6 && !a.isNavigating() && ticks % 20 == 0) a.navigate((int) Math.floor(m.x()), (int) Math.floor(m.y() + 0.2), (int) Math.floor(m.z()), 3);
        else if (d <= 4 && a.isNavigating()) a.stopNavigation();
    }

    /**
     * Throws this item to the main: up to it (not while a lit blaze is about), turned to it, the whole stack thrown.
     * True while it is busy with that.
     */
    private boolean handOver(AgentHandle a, String item) {
        AgentHandle m = MAIN == null ? null : GathererModule.find(MAIN);
        if (m == null || !AgentHandle.sameDim(m.dimension(), a.dimension())) return false;
        for (Seen s : a.entitiesNear(8)) if (s.name().equals("blaze") && s.swelling()) return false; // the fight first
        List<ItemStack> inv = a.inventory();
        int slot = -1;
        for (int i = 0; i < inv.size(); i++) if (Gear.id(inv.get(i)).equals(item)) { slot = i; break; }
        if (slot < 0) return false;
        double d = Math.sqrt((m.x() - a.x()) * (m.x() - a.x()) + (m.y() - a.y()) * (m.y() - a.y()) + (m.z() - a.z()) * (m.z() - a.z()));
        String what = item.replace("minecraft:", "").replace('_', ' ');
        if (d > 2.5) {
            status = "bringing " + count(a, item) + " " + what + " to " + MAIN;
            if (!a.isNavigating() && ticks % 20 == 0) a.navigate((int) Math.floor(m.x()), (int) Math.floor(m.y() + 0.2), (int) Math.floor(m.z()), 1);
            return true;
        }
        if (a.isNavigating()) a.stopNavigation();
        if (blocking) { a.releaseUse(); blocking = false; }
        a.lookAt(m.x(), m.y() + 0.5, m.z());
        if (ticks - handedAt < 10) return true;
        handedAt = ticks;
        int n = inv.get(slot).getCount();
        a.dropStack(slot);
        a.log("[speedrun] threw " + n + " " + what + " to " + MAIN);
        return true;
    }
    private int handedAt = -100, drinkUntil, boatHitAt = -100, boatPlacedAt = -100, boatCheckAt = -1, boatTarget = -1;
    private net.minecraft.world.phys.Vec3 boatTargetPos;

    /** The air block over the ground under this point (where an item falling from there lands). */
    private static BlockPos groundUnder(BlockSource w, net.minecraft.world.phys.Vec3 p) {
        int x = (int) Math.floor(p.x), z = (int) Math.floor(p.z), y = (int) Math.floor(p.y);
        for (int dy = 0; dy < 24; dy++) {
            BlockState s = w.get(x, y - dy - 1, z);
            if (s != null && !s.isAir() && s.getFluidState().isEmpty()) return new BlockPos(x, y - dy, z);
            if (s != null && !s.getFluidState().isEmpty()) break; // over lava: the rod is gone
        }
        return new BlockPos(x, y, z);
    }

    /** A hop of about this far, landing on solid ground a little below or at its level (never lava). */
    private static BlockPos netherHop(AgentHandle a, BlockView w, int dist) {
        int ay = (int) Math.floor(a.y());
        for (int tries = 0; tries < 16; tries++) {
            double ang = Math.random() * Math.PI * 2;
            int tx = (int) (a.x() + Math.cos(ang) * dist), tz = (int) (a.z() + Math.sin(ang) * dist);
            int ground = Integer.MIN_VALUE;
            boolean known = false, lava = false;
            for (int y = ay + 4; y >= ay - 16; y--) {
                BlockState s = w.get(tx, y, tz);
                if (s == null) break;
                known = true;
                if (s.isAir()) continue;
                if (!s.getFluidState().isEmpty()) { lava = true; break; }
                ground = y; break;
            }
            if (lava || known && ground == Integer.MIN_VALUE) continue;
            a.navigate(tx, known ? ground + 1 : ay, tz, 6);
            return new BlockPos(tx, known ? ground + 1 : ay, tz);
            // (unreachable: the goal is returned above)
        }
        return null;
    }

    /** Mine until it carries enough, then the next stage. */
    private int jobRestart = -1000;

    private void gather(AgentHandle a, String id, int want, String job, Stage next) {
        if (count(a, id) >= want) { mine(a, null); go(a, next); return; }
        status = "getting " + want + " " + id.replace("minecraft:", "") + " (" + count(a, id) + " so far)";
        // The job finished without enough (gravel gave no flint): the same job again.
        // Only once the miner has really stopped, and not again within 5 s: its "done" stays up for a while after the
        // new job starts, and starting the job over every tick left it standing at one ore for minutes, never digging.
        if (minerJob != null && miner.idle() && miner.status().startsWith("done") && ticks - jobRestart > 100) { minerJob = null; jobRestart = ticks; }
        mine(a, job);
        miner.tick(a);
    }

    // ------------------------------------------------------------------ fluids

    private static boolean lavaSource(BlockState s) { return s != null && s.getFluidState().is(net.minecraft.tags.FluidTags.LAVA) && s.getFluidState().isSource(); }
    private static boolean waterSource(BlockState s) { return s != null && s.getFluidState().is(net.minecraft.tags.FluidTags.WATER) && s.getFluidState().isSource(); }

    /** No lava in or next to this cell (a safe place to stand). */
    private static boolean noLavaNear(BlockSource w, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 2; dy++) for (int dz = -1; dz <= 1; dz++) {
            BlockState s = w.get(x + dx, y + dy, z + dz);
            if (s == null || s.getFluidState().is(net.minecraft.tags.FluidTags.LAVA) || Rules.dangerous(s) && !s.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) return false;
        }
        return true;
    }

    /** A spot to stand from which the fluid block is within reach and in sight. */
    private static BlockPos standFor(BlockSource w, BlockPos f) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = -1; dy <= 3; dy++) {
            int x = f.getX() + dx, y = f.getY() + dy, z = f.getZ() + dz;
            BlockState floor = w.get(x, y - 1, z), feet = w.get(x, y, z), head = w.get(x, y + 1, z);
            if (floor == null || feet == null || head == null) continue;
            if (!Rules.canStandOn(floor) || !feet.isAir() || !head.isAir() || !noLavaNear(w, x, y, z)) continue;
            double ex = x + 0.5, ey = y + 1.62, ez = z + 0.5;
            double d = (ex - f.getX() - 0.5) * (ex - f.getX() - 0.5) + (ey - f.getY() - 0.8) * (ey - f.getY() - 0.8) + (ez - f.getZ() - 0.5) * (ez - f.getZ() - 0.5);
            if (d > 4.0 * 4.0 || d < 1.5 * 1.5) continue;
            if (!Sight.clear(w, ex, ey, ez, f.getX() + 0.5, f.getY() + 0.8, f.getZ() + 0.5)) continue;
            if (d < bd) { bd = d; best = new BlockPos(x, y, z); }
        }
        return best;
    }

    /** One bucket of lava (lava=true) or water from the nearest source it can stand by. True when it has it. */
    private boolean fetch(AgentHandle a, boolean lava) {
        String full = lava ? "minecraft:lava_bucket" : "minecraft:water_bucket";
        int have = count(a, full);
        if (fetchAt != null && fetchPending && have > fetchedHad) { fetchStand = null; fetchAt = null; fetchPending = false; return true; }
        if (!(a.openWorld() instanceof BlockView w)) return false;
        if (fetchStand == null) {
            int fx = (int) Math.floor(a.x()), fz = (int) Math.floor(a.z());
            List<BlockPos> src = new ArrayList<>(w.findAll(fx, fz, 64, lava ? SpeedrunnerModule::lavaSource : SpeedrunnerModule::waterSource, 300, null));
            final double ax = a.x(), ay = a.y(), az = a.z();
            src.sort(java.util.Comparator.comparingDouble(p -> (p.getX() - ax) * (p.getX() - ax) + (p.getZ() - az) * (p.getZ() - az) + 9 * (p.getY() - ay) * (p.getY() - ay)));
            for (BlockPos p : src) {
                if (badSpots.contains(p)) continue;
                BlockState over = w.get(p.getX(), p.getY() + 1, p.getZ());
                if (over == null || !over.isAir()) continue; // the surface of the pool: reachable from above
                // Under the open sky, not a cave pool: from the surface it went down after water at y 30, 16 and -38,
                // was sent back up ("underground: up to the surface first") and picked the next deep one, for 26 minutes.
                if (!underground(a) && !openSky(w, p)) continue;
                BlockPos s = standFor(w, p);
                if (s == null) continue;
                fetchStand = s; fetchAt = p; stepTicks = 0;
                status = "going to fill a bucket with " + (lava ? "lava" : "water") + " at " + p.toShortString();
                break;
            }
            if (fetchStand == null) {
                status = "no " + (lava ? "lava" : "water") + " it can get to within 64 blocks";
                if (++tries % 20 == 1) { double ang = Math.random() * Math.PI * 2; a.navigate((int) (a.x() + Math.cos(ang) * 40), (int) a.y(), (int) (a.z() + Math.sin(ang) * 40), 8); }
                return false;
            }
        }
        stepTicks++;
        boolean at = Math.abs(a.x() - (fetchStand.getX() + 0.5)) < 0.7 && Math.abs(a.z() - (fetchStand.getZ() + 0.5)) < 0.7 && Math.abs(a.y() - fetchStand.getY()) < 0.6;
        if (!at) {
            if (stepTicks > 20 * 60) { badSpots.add(fetchAt); fetchStand = null; a.log("§e[speedrun] can't get to the " + (lava ? "lava" : "water") + " at " + fetchAt.toShortString() + ": another one"); return false; }
            if (!a.isNavigating() && stepTicks % 20 == 1) a.navigate(fetchStand.getX(), fetchStand.getY(), fetchStand.getZ(), 0);
            return false;
        }
        if (a.isNavigating()) a.stopNavigation();
        if (!hold(a, "minecraft:bucket")) { status = "no empty bucket"; return false; }
        if (stepTicks % 10 == 2) a.lookAt(fetchAt.getX() + 0.5, fetchAt.getY() + 0.8, fetchAt.getZ() + 0.5);
        if (stepTicks % 10 == 5) { fetchedHad = have; fetchPending = true; a.useItem(); }
        if (stepTicks > 20 * 90) { badSpots.add(fetchAt); fetchStand = null; fetchPending = false; }
        return false;
    }

    private int upAt = -1000, upFails, upGiveUpUntil;
    private boolean upTrip;
    private double upFromY;

    /**
     * Sky shows through this block: air, water, leaves (the user: "with the exception of leaves, that would still count
     * exposed to the sky"), logs of the same tree, and anything without a collision box (grass, vines, moss, snow layers,
     * leaf litter). By the block itself, not its item: blocks whose item is air or named otherwise counted as rock, and the
     * main went "up to the surface" while standing on it (2026-11-20).
     */
    private static boolean skyThrough(BlockState s) {
        if (s.isAir() || !s.getFluidState().isEmpty()) return true;
        if (s.is(net.minecraft.tags.BlockTags.LEAVES) || s.is(net.minecraft.tags.BlockTags.LOGS)) return true;
        return s.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
    }

    /** Rock over its head (not just a tree): it is in a cave or a tunnel. */
    /** What the last "underground" answer saw over its head (for the log). */
    private static volatile String roofSeen = "";
    private static boolean underground(AgentHandle a) {
        if (!(a.openWorld() instanceof BlockView w)) return false;
        int x = (int) Math.floor(a.x()), y = (int) Math.floor(a.y() + 0.01), z = (int) Math.floor(a.z());
        for (int dy = 2; dy <= 48; dy++) {
            BlockState s = w.get(x, y + dy, z);
            if (s == null) return false;
            if (skyThrough(s)) continue;
            roofSeen = s.getBlock().getDescriptionId().replace("block.minecraft.", "") + " at " + x + " " + (y + dy) + " " + z + " (" + dy + " over its feet)";
            return true;
        }
        return false;
    }

    /** Nothing but air, water, leaves or logs over this block (48 up): it lies under the open sky. */
    private static boolean openSky(BlockView w, BlockPos p) {
        for (int dy = 1; dy <= 48; dy++) {
            BlockState s = w.get(p.getX(), p.getY() + dy, p.getZ());
            if (s == null) return true; // above the loaded world: sky
            if (skyThrough(s)) continue;
            return false;
        }
        return true;
    }

    private int fetchedHad;
    private boolean fetchPending;

    // ------------------------------------------------------------------ the portal (engine 4: the pool-edge method)
    //
    // The speedrunners' surface portal, built in a lava pool's edge. Local coordinates from 'origin' (the first of 4
    // lava blocks in a row along the edge): x along the edge (0..3), z into the pool (z = 0 the edge row, z = 1 the
    // row the frame stands in, z = -1 the shore), y up from the lava's level.
    //   1. cobblestone into the 2nd lava block of the edge row; water into the 3rd: the 4th and the block behind the
    //      3rd turn to obsidian;
    //   2. the cobblestone broken: the water runs into its place: the 1st and the block behind the 2nd turn to
    //      obsidian. The two blocks behind (x 1 and 2 at z 1) are the portal's bottom;
    //   3. the water taken back, the two holes filled with cobblestone (a way onto the bottom blocks);
    //   4. a cobblestone pillar 4 high on the obsidian at the row's end (x 3, z 0), one more block at its top out over
    //      the pool (x 3, y 4, z 1): the portal's top corner;
    //   5. water on top of that corner, left running: it falls past the frame and runs along its top;
    //   6. lava, scooped from the pool at its feet, placed where the frame goes: the side by the pillar, the top two,
    //      the far side from the bottom up (its foot turns to obsidian under the falling water). Each turns to obsidian
    //      at once, because there is water in or next to its place: it is never poured where there is none;
    //   7. the water taken back; lit; in.
    // It works standing on the two bottom blocks (inside the frame), on a pillar of its own for the high ones.

    private record Step(char kind, int x, int y, int z, int sx, int sz, int h) {}

    /** Along the pool's edge, and from the shore into the pool (unit steps in world x/z). */
    private int alongX = 1, alongZ, inX, inZ = 1;

    /** Local to world: x along the edge, z into the pool (z = -1 is the shore row), y up from the lava's level. */
    private BlockPos c(int x, int y, int z) { return origin.offset(alongX * x + inX * z, y, alongZ * x + inZ * z); }

    private static boolean open(BlockState s) { return s != null && (s.isAir() || s.canBeReplaced() && s.getFluidState().isEmpty()); }
    private static boolean ground(BlockState s) { return s != null && Rules.canStandOn(s); }
    private static boolean solid(BlockState s) { return s != null && !s.isAir() && s.getFluidState().isEmpty() && !s.canBeReplaced(); }
    private static boolean water(BlockState s) { return s != null && s.getFluidState().is(net.minecraft.tags.FluidTags.WATER); }

    private void plan() {
        steps.clear();
        // From the shore: the first block, the water, the block broken, the water back (the two holes stay empty).
        steps.add(new Step('B', 1, 0, 0, 1, -1, 0));
        steps.add(new Step('W', 2, 0, 0, 2, -1, 0)); // from behind the 3rd: it sees the cobblestone's side (a deep pool has no floor in reach)
        steps.add(new Step('D', 1, 0, 0, 2, -1, 0));
        steps.add(new Step('S', 2, 0, 0, 2, -1, 0));
        // Two blocks on the shore behind the 1st and 2nd; the pillar on the shore behind the 4th (2 up for its top).
        steps.add(new Step('B', 0, 1, -1, 2, -1, 0));
        steps.add(new Step('B', 1, 1, -1, 2, -1, 0));
        steps.add(new Step('B', 3, 1, -1, 2, -1, 0));
        steps.add(new Step('B', 3, 2, -1, 2, -1, 0));
        steps.add(new Step('B', 3, 3, -1, 2, -1, 0));
        steps.add(new Step('B', 3, 3, 0, 2, -1, 0)); // the pillar's top block, out over the edge row (the upside-down L)
        steps.add(new Step('W', 2, 3, -1, 2, 1, 0)); // beside the pillar's top block, in the pillar's row (over the shore)
        // Lava, where the user marked it: the side under the overhanging block, then the other side, each two high.
        steps.add(new Step('L', 3, 1, 0, 2, 1, 0));
        steps.add(new Step('L', 3, 2, 0, 2, 1, 0));
        steps.add(new Step('L', 0, 1, 0, 2, 1, 0));
        steps.add(new Step('L', 0, 2, 0, 2, 1, 0));
        // The top: against the overhanging block first, then against that one (seen from in front of the 2nd).
        steps.add(new Step('L', 2, 3, 0, 2, 1, 0));
        steps.add(new Step('L', 1, 3, 0, 1, 1, 0));
        // The bottom, in a pool one block deep: under the two holes is the pool's floor, not obsidian (in a deeper pool
        // the lava down there turned when the water ran in, and these four steps find nothing to do). The floor block
        // comes out and lava goes into its place, under the water still standing in the hole: obsidian.
        steps.add(new Step('X', 1, -1, 0, 1, 1, 0));
        steps.add(new Step('L', 1, -1, 0, 1, 1, 0));
        steps.add(new Step('X', 2, -1, 0, 2, 1, 0));
        steps.add(new Step('L', 2, -1, 0, 2, 1, 0));
        // The water back into its bucket: seen from in front, up through the frame under its top row.
        steps.add(new Step('S', 2, 3, -1, 2, 1, 0));
    }

    private String describe(Step s) {
        String at = c(s.x(), s.y(), s.z()).toShortString();
        String from = (s.sz() < 0 ? "from the shore" : "standing on the obsidian in front of the portal") + (s.h() == 0 ? "" : ", " + s.h() + " up on its own pillar");
        return switch (s.kind()) {
            case 'B' -> "place cobblestone at " + at + ", " + from;
            case 'W' -> "pour the water at " + at + ", " + from;
            case 'L' -> "scoop lava from the pool and place it at " + at + " (it turns to obsidian in the water), " + from;
            case 'D' -> "break the cobblestone at " + at + " (the water runs into its place), " + from;
            case 'X' -> "dig out the pool's floor at " + at + " (under the portal, unless it is obsidian already), " + from;
            default -> "take the water back from " + at + ", " + from;
        };
    }

    /** Lava near the surface that anyone in the crew has seen (overworld): where the main goes when it has no pool yet. */
    private static final java.util.List<BlockPos> CREW_LAVA = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final java.util.Map<String, Long> LAVA_LOOKED = new java.util.concurrent.ConcurrentHashMap<>();
    private BlockPos crewPool;
    private int crewPoolAt;
    private double poolHeading = Double.NaN;

    /** The main has no lava pool yet and nobody has seen one: worth a scout. */
    public static boolean wantsScout() {
        return MAIN != null && !beaten() && mainStage.ordinal() <= Stage.POOL.ordinal() && CREW_LAVA.isEmpty();
    }

    /** The nearest pool the crew has told the main about, within r blocks, or null. */
    public static BlockPos crewLavaNear(double x, double z, int r) {
        BlockPos best = null;
        for (BlockPos k : CREW_LAVA) {
            double d = Math.hypot(k.getX() + 0.5 - x, k.getZ() + 0.5 - z);
            if (d <= r && (best == null || d < Math.hypot(best.getX() + 0.5 - x, best.getZ() + 0.5 - z))) best = k;
        }
        return best;
    }

    /**
     * Every agent, every twenty seconds, while the main still has no portal: lava near the surface in what it can see (ten or
     * more sources, open to the sky) is told to the crew. The main searched 50 minutes for a pool alone (run 1119c) while
     * thirteen others walked about seeing far more ground than it did. Called from the agent's own tick.
     */
    public static void crewLavaLook(AgentHandle a) {
        if (MAIN == null || beaten() || mainStage.ordinal() > Stage.POOL.ordinal() || a.name().equalsIgnoreCase(MAIN)) return;
        if (a.dimension() == null || !a.dimension().endsWith("overworld") || !a.isOnline()) return;
        long now = System.currentTimeMillis();
        Long last = LAVA_LOOKED.get(a.name());
        if (last != null && now - last < 20_000) return;
        LAVA_LOOKED.put(a.name(), now);
        if (!(a.openWorld() instanceof BlockView w)) return;
        int fx = (int) Math.floor(a.x()), fz = (int) Math.floor(a.z());
        List<BlockPos> lava = w.findAll(fx, fz, 64, SpeedrunnerModule::lavaSource, 400, null);
        List<BlockPos> top = new ArrayList<>();
        for (BlockPos p : lava) {
            if (p.getY() < 50) continue;
            boolean sky = true;
            for (int up = 1; up <= 12 && sky; up++) { BlockState s = w.get(p.getX(), p.getY() + up, p.getZ()); sky = s != null && s.isAir(); }
            if (sky) top.add(p);
        }
        if (top.size() < 10) return;
        BlockPos at = top.get(0);
        for (BlockPos k : CREW_LAVA) if (k.distSqr(at) < 48 * 48) return; // known already
        CREW_LAVA.add(at);
        a.log("§a[speedrun] lava near the surface at " + at.toShortString() + " (" + top.size() + " blocks of it): told to " + MAIN);
    }

    /** A hop of about this far along its heading, over land; the heading turns when the way is water. True if it set off. */
    private boolean wanderAhead(AgentHandle a, BlockView w, int dist) {
        if (Double.isNaN(poolHeading)) poolHeading = Math.random() * Math.PI * 2;
        int ay = (int) Math.floor(a.y());
        for (int tries = 0; tries < 8; tries++) {
            double ang = poolHeading + (Math.random() - 0.5) * 0.5; // about 15 degrees either way: on, not back and forth
            int tx = (int) (a.x() + Math.cos(ang) * dist), tz = (int) (a.z() + Math.sin(ang) * dist);
            boolean wet = false, known = false;
            for (int y = ay + 24; y >= ay - 24; y--) {
                BlockState s = w.get(tx, y, tz);
                if (s == null) break;
                known = true;
                if (s.isAir()) continue;
                wet = !s.getFluidState().isEmpty();
                break;
            }
            if (known && wet) { poolHeading += Math.PI / 3; continue; } // water that way: sixty degrees round
            a.navigate(tx, ay, tz, 8);
            return true;
        }
        return false;
    }

    /**
     * A straight piece of pool edge: 4 lava blocks in a row with flat shore behind them (2 deep, level with the
     * lava), lava in the row behind too, and the pool one block deep under the middle two.
     */
    private void findPool(AgentHandle a) {
        if (!(a.openWorld() instanceof BlockView w)) return;
        if (stageTicks % 40 != 1) return;
        int fx = (int) Math.floor(a.x()), fz = (int) Math.floor(a.z());
        List<BlockPos> lava = new ArrayList<>(w.findAll(fx, fz, 64, SpeedrunnerModule::lavaSource, 4000, null));
        BlockPos site = null;
        double sd = Double.MAX_VALUE;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (BlockPos p : lava) {
            double d = p.distToCenterSqr(a.x(), a.y(), a.z());
            if (nearBad(p)) continue; // the pool it gave up on, not only that block: the next one along was picked every 20 s (run 1120zd)
            // Near its own level only: the lava lakes at the bottom of the world are everywhere under it, and it stood
            // on the surface for minutes "getting into place" for one 118 blocks down.
            // Underground pools count too (the user, in "to add" on GitHub, 2026-11-19): down to 70 blocks under it. It
            // goes there by its own staircase (see descend). Near its own level still comes first: a deep one costs as much
            // as fifty blocks more walking.
            if (p.getY() < a.y() - 70 || p.getY() > a.y() + 12) continue;
            boolean deep = p.getY() < a.y() - 12;
            if (deep) d += 2500;
            for (int[] out : dirs) for (int sgn : new int[]{1, -1}) {
                int ax = out[1] * sgn, az = out[0] * sgn; // along the edge: square to "out" (toward the shore)
                boolean ok = true;
                List<BlockPos> need = new ArrayList<>();
                for (int k = 0; k <= 3 && ok; k++) {
                    int x = p.getX() + ax * k, z = p.getZ() + az * k, y = p.getY();
                    ok = lavaSource(w.get(x, y, z)) && lavaSource(w.get(x - out[0], y, z - out[1]));
                    // Open over the lava, or rock that can be dug away (a cave's roof over an underground pool): the frame
                    // needs five blocks of height. Water or lava over it, bedrock, obsidian: not this one.
                    for (int up = 1; up <= 6 && ok; up++) for (int in = 0; in <= 1 && ok; in++) {
                        BlockPos over = new BlockPos(x - out[0] * in, y + up, z - out[1] * in);
                        BlockState s = w.get(over.getX(), over.getY(), over.getZ());
                        if (open(s)) continue;
                        if (deep && solid(s) && s.getBlock() != Blocks.BEDROCK && s.getBlock() != Blocks.OBSIDIAN && !(s.getBlock() instanceof net.minecraft.world.level.block.FallingBlock)) need.add(over);
                        else ok = false;
                    }
                    // The shore: ground level with the lava. A natural pool sits a block below the land around it, so
                    // what stands on the shore (dirt, stone) is dug away first instead of the pool being passed over.
                    for (int back = 1; back <= 2 && ok; back++) {
                        int sx = x + out[0] * back, sz = z + out[1] * back;
                        ok = ground(w.get(sx, y, sz));
                        for (int up = 1; up <= (back == 1 ? 4 : 2) && ok; up++) {
                            BlockState s = w.get(sx, y + up, sz);
                            if (open(s)) continue;
                            if (solid(s) && s.getBlock() != Blocks.BEDROCK && s.getBlock() != Blocks.OBSIDIAN) need.add(new BlockPos(sx, y + up, sz));
                            else ok = false;
                        }
                    }
                }
                if (!ok) continue;
                if (need.size() > 70) continue; // too much rock to move
                double score = d + need.size() * 16; // a shore that needs no digging is worth a few blocks more walking
                if (score >= sd) continue;
                need.sort((p1, p2) -> p2.getY() - p1.getY()); // from the top down
                clear.clear(); clear.addAll(need); clearTicks = 0;
                sd = score; site = p; alongX = ax; alongZ = az; inX = -out[0]; inZ = -out[1]; siteDeep = deep;
            }
        }
        if (site == null) {
            status = lava.isEmpty() ? "looking for a lava pool near the surface" : "no straight pool edge (4 lava blocks, flat shore behind, 1 deep)";
            // Lava one of the crew has seen: there, before any more looking about. One it stood at and could not use is
            // dropped from the list.
            if (crewPool != null && (crewPool.distToCenterSqr(a.x(), crewPool.getY(), a.z()) < 30 * 30 && ticks - crewPoolAt > 600 || ticks - crewPoolAt > 20 * 240)) {
                a.log("§e[speedrun] no usable pool edge at the lava the crew saw at " + crewPool.toShortString() + ": looking on");
                CREW_LAVA.remove(crewPool); crewPool = null;
            }
            if (crewPool == null && !CREW_LAVA.isEmpty()) {
                BlockPos best = null;
                for (BlockPos k : CREW_LAVA) if (best == null || k.distToCenterSqr(a.x(), a.y(), a.z()) < best.distToCenterSqr(a.x(), a.y(), a.z())) best = k;
                crewPool = best; crewPoolAt = ticks;
                a.log("§a[speedrun] going to the lava the crew saw at " + best.toShortString() + " (" + Math.round(Math.sqrt(best.distToCenterSqr(a.x(), a.y(), a.z()))) + " blocks)");
            }
            if (crewPool != null) {
                status = "going to the lava the crew saw at " + crewPool.toShortString();
                if (!a.isNavigating() && stageTicks % 100 == 1) a.navigate(crewPool.getX(), crewPool.getY() + 1, crewPool.getZ(), 6);
                return;
            }
            // On along one heading, 64 blocks a hop: hops in random directions kept it within 300 blocks of where it began.
            if (stageTicks % 400 == 1) wanderAhead(a, w, 64);
            return;
        }
        pool = site;
        origin = site;
        plan();
        step = 0; stepTicks = 0; tries = 0;
        poolDeep = siteDeep; stairOrigin = null; descendSaid = false; descendTop = null;
        if (poolDeep) a.log("§a[speedrun] a lava pool underground at " + origin.toShortString() + " (" + Math.round(a.y() - origin.getY()) + " blocks down): its own staircase down to it");
        a.log("[speedrun] building its portal in the pool edge at " + origin.toShortString() + " (" + steps.size() + " steps" + (clear.isEmpty() ? "" : ", " + clear.size() + " blocks to clear off the shore first") + ")");
        go(a, Stage.CAST);
    }

    /**
     * A hop of about this far in a random direction, over land: a target whose surface is water is passed over (looking
     * for a lava pool it wandered out over the ocean and a guardian killed it). Unknown ground (not loaded) is allowed.
     */
    private static void wanderOnLand(AgentHandle a, BlockView w, int dist) {
        int ay = (int) Math.floor(a.y());
        for (int tries = 0; tries < 12; tries++) {
            double ang = Math.random() * Math.PI * 2;
            int tx = (int) (a.x() + Math.cos(ang) * dist), tz = (int) (a.z() + Math.sin(ang) * dist);
            boolean wet = false, known = false;
            for (int y = ay + 24; y >= ay - 24; y--) {
                BlockState s = w.get(tx, y, tz);
                if (s == null) break;
                known = true;
                if (s.isAir()) continue;
                wet = !s.getFluidState().isEmpty();
                break;
            }
            if (known && wet) continue;
            a.navigate(tx, ay, tz, 8);
            return;
        }
    }

    /** A point on the given face of a block that it can see (nothing solid in between) and reach; null if none. */
    private static double[] aimAt(BlockSource w, AgentHandle a, BlockPos b, Direction face) {
        double ex = a.x(), ey = a.y() + 1.62, ez = a.z();
        double[] best = null;
        double bd = Double.MAX_VALUE;
        // Only a face turned toward it: its eyes at least a little in front of the face's plane. (From on top of a block
        // its far side "looked" clear to the sight check, and the water went onto the block it stood on.)
        double fx = b.getX() + 0.5 + face.getStepX() * 0.5, fy = b.getY() + 0.5 + face.getStepY() * 0.5, fz = b.getZ() + 0.5 + face.getStepZ() * 0.5;
        if ((ex - fx) * face.getStepX() + (ey - fy) * face.getStepY() + (ez - fz) * face.getStepZ() < 0.15) return null;
        for (double u = 0.1; u <= 0.91; u += 0.1) for (double v = 0.1; v <= 0.91; v += 0.1) {
            double px, py, pz;
            switch (face) {
                case UP -> { px = b.getX() + u; py = b.getY() + 1.02; pz = b.getZ() + v; }
                case DOWN -> { px = b.getX() + u; py = b.getY() - 0.02; pz = b.getZ() + v; }
                case EAST -> { px = b.getX() + 1.02; py = b.getY() + u; pz = b.getZ() + v; }
                case WEST -> { px = b.getX() - 0.02; py = b.getY() + u; pz = b.getZ() + v; }
                case SOUTH -> { px = b.getX() + u; py = b.getY() + v; pz = b.getZ() + 1.02; }
                default -> { px = b.getX() + u; py = b.getY() + v; pz = b.getZ() - 0.02; }
            }
            double dist = Math.sqrt((px - ex) * (px - ex) + (py - ey) * (py - ey) + (pz - ez) * (pz - ez));
            if (dist > 4.3 || !clearAround(w, ex, ey, ez, px, py, pz, face)) continue;
            double d = Math.abs(u - 0.5) + Math.abs(v - 0.5);
            if (d < bd) {
                bd = d;
                best = new double[]{px - face.getStepX() * 0.02, py - face.getStepY() * 0.02, pz - face.getStepZ() * 0.02};
            }
        }
        return best;
    }

    /** The line to the point is clear, and so are lines to points a little to every side of it: a point that only just
     *  clears an edge is missed by the server's own aim (it put the lava on the block in front instead of in the hole). */
    private static boolean clearAround(BlockSource w, double ex, double ey, double ez, double px, double py, double pz, Direction face) {
        if (!Sight.clear(w, ex, ey, ez, px, py, pz)) return false;
        // Only along the face: a point moved across it would be inside the block being clicked, which is never clear.
        double m = 0.08;
        boolean ax = face.getStepX() == 0, ay = face.getStepY() == 0, az = face.getStepZ() == 0;
        return (!ax || Sight.clear(w, ex, ey, ez, px + m, py, pz) && Sight.clear(w, ex, ey, ez, px - m, py, pz))
                && (!ay || Sight.clear(w, ex, ey, ez, px, py + m, pz) && Sight.clear(w, ex, ey, ez, px, py - m, pz))
                && (!az || Sight.clear(w, ex, ey, ez, px, py, pz + m) && Sight.clear(w, ex, ey, ez, px, py, pz - m));
    }

    /** What to click to put something into this cell: a solid block next to it and the face toward the cell. */
    private record Click(BlockPos against, Direction face, double[] point) {}

    /** The block under it first, then the ones beside it, then the one over it: the first with a face it can see. */
    private Click clickFor(BlockSource w, AgentHandle a, BlockPos cell) {
        for (Direction d : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP}) {
            BlockPos n = cell.relative(d);
            if (!solid(w.get(n.getX(), n.getY(), n.getZ()))) continue;
            double[] p = aimAt(w, a, n, d.getOpposite());
            if (p != null) return new Click(n, d.getOpposite(), p);
        }
        return null;
    }

    /** Where its own standing pillar is (the feet cell at its base), while there is one. */
    private BlockPos pillarAt;

    /** On its working spot, h blocks up on a cobblestone pillar under its own feet (dug out again to come down). */
    private boolean standAt(AgentHandle a, BlockSource w, BlockPos feet, int h) {
        double cx = feet.getX() + 0.5, cz = feet.getZ() + 0.5;
        int gy = feet.getY();
        boolean atXZ = Math.abs(a.x() - cx) < 0.08 && Math.abs(a.z() - cz) < 0.08; // dead centre: a body over the next cell blocks a block going there
        if (atXZ && Math.abs(a.y() - (gy + h)) < 0.05) return true;
        if (a.isDigging()) return false;
        // Cobblestone standing in its own spot that it is not on top of (what is left of its pillar after it stepped
        // off for lava): out of the way first. Walking into it got it kicked, again and again.
        for (int y = gy; y <= gy + 3; y++) {
            BlockState cs = w.get(feet.getX(), y, feet.getZ());
            if (cs == null || cs.getBlock() != Blocks.COBBLESTONE) continue;
            boolean above = Math.abs(a.x() - cx) < 0.5 && Math.abs(a.z() - cz) < 0.5 && a.y() >= y + 0.95; // it is standing on it
            if (above) continue;
            if (a.isNavigating()) a.stopNavigation();
            if (feet.distToCenterSqr(a.x(), a.y() + 1.62, a.z()) > 4.2 * 4.2) { if (stepTicks % 20 == 1) a.navigate(feet.getX(), gy, feet.getZ(), 2); return false; }
            if (stepTicks % 5 == 0) { Gear.holdBestFor(a, cs); a.lookAt(feet.getX() + 0.5, y + 0.5, feet.getZ() + 0.5); a.dig(feet.getX(), y, feet.getZ()); }
            status = "clearing its old pillar out of its spot";
            return false;
        }
        // The pillar it stood on before, somewhere else (or higher than it needs now): every block of it comes out
        // before it goes on. (It walked off the last block once and left it standing on the shore.)
        if (pillarAt != null && (pillarAt.getX() != feet.getX() || pillarAt.getZ() != feet.getZ())) {
            BlockPos top = null;
            for (int y = pillarAt.getY() + 3; y >= pillarAt.getY(); y--) {
                BlockState ps = w.get(pillarAt.getX(), y, pillarAt.getZ());
                if (ps != null && ps.getBlock() == Blocks.COBBLESTONE) { top = new BlockPos(pillarAt.getX(), y, pillarAt.getZ()); break; }
            }
            if (top == null) pillarAt = null;
            else {
                boolean onIt = (int) Math.floor(a.x()) == top.getX() && (int) Math.floor(a.z()) == top.getZ() && a.y() > top.getY() + 0.5;
                if (onIt || top.distToCenterSqr(a.x(), a.y() + 1.62, a.z()) < 4.2 * 4.2) {
                    if (a.isNavigating()) a.stopNavigation();
                    if (stepTicks % 5 == 0) { Gear.holdBestFor(a, w.get(top.getX(), top.getY(), top.getZ())); a.lookAt(top.getX() + 0.5, top.getY() + 0.5, top.getZ() + 0.5); a.dig(top.getX(), top.getY(), top.getZ()); }
                    status = "taking its own pillar down";
                    return false;
                }
                // Out of reach (it fell off, was moved): back to it first.
                if (!a.isNavigating() && stepTicks % 20 == 1) a.navigate(pillarAt.getX(), pillarAt.getY(), pillarAt.getZ(), 2);
                return false;
            }
        }
        // Up on a pillar and it should be lower (or somewhere else): its own cobblestone under its feet dug out.
        int bx = (int) Math.floor(a.x()), by = (int) Math.floor(a.y() - 0.5), bz = (int) Math.floor(a.z());
        BlockState under = w.get(bx, by, bz);
        boolean onPillar = under != null && under.getBlock() == Blocks.COBBLESTONE && by >= gy && a.y() > gy + 0.05
                && Math.hypot(a.x() - (origin.getX() + 0.5), a.z() - (origin.getZ() + 0.5)) < 8;
        if (onPillar && (!atXZ || a.y() > gy + h + 0.05)) {
            if (a.isNavigating()) a.stopNavigation();
            if (stepTicks % 5 == 0) { Gear.holdBestFor(a, under); a.dig(bx, by, bz); }
            return false;
        }
        // Nothing under its feet (the pillar block it just dug out): it drops first. Walking off from mid-air got it kicked.
        // Any block under its body (0.6 wide), not just under its middle: standing on an edge at x=223.00 over air at 223 it
        // "dropped first" for three minutes while held up by the block at 222 (run 1118d, the portal cast).
        boolean held = false;
        int fy = (int) Math.floor(a.y() - 0.05);
        for (double ox : new double[] { -0.299, 0.299 }) for (double oz : new double[] { -0.299, 0.299 }) {
            BlockState f = w.get((int) Math.floor(a.x() + ox), fy, (int) Math.floor(a.z() + oz));
            if (f != null && !open(f)) held = true;
        }
        if (!held) { if (a.isNavigating()) a.stopNavigation(); return false; }
        if (!atXZ) {
            if (Math.hypot(a.x() - cx, a.z() - cz) < 0.7 && Math.abs(a.y() - gy) < 0.1) { if (a.isNavigating()) a.stopNavigation(); a.stepToward(cx, cz); } // only within its own block: never across a gap
            else if (gap(a, w, feet)) { if (a.isNavigating()) a.stopNavigation(); status = "jumping the hole"; }
            else if (!a.isNavigating() && stepTicks % 20 == 1) a.navigate(feet.getX(), gy, feet.getZ(), 0);
            return false;
        }
        if (a.isNavigating()) a.stopNavigation();
        if (a.y() < gy + h - 0.05 && stepTicks % 10 == 0) {
            if (!hold(a, "minecraft:cobblestone")) { status = "out of cobblestone for its pillar"; return false; }
            pillarAt = feet;
            a.pillarUp();
        }
        return false;
    }

    /** Its spot is straight across a one-block hole (the frame's bottom, between the shore and the row in front): it
     *  jumps it. The path finder would not, and it stood at the edge until it was carried over. */
    private boolean gap(AgentHandle a, BlockSource w, BlockPos feet) {
        int gy = feet.getY();
        if (a.airborne() || Math.abs(a.y() - gy) > 0.1) return false;
        int ax = (int) Math.floor(a.x()), az = (int) Math.floor(a.z());
        int dx = feet.getX() - ax, dz = feet.getZ() - az;
        if (!(Math.abs(dx) == 2 && dz == 0 || Math.abs(dz) == 2 && dx == 0)) return false;
        int mx = ax + dx / 2, mz = az + dz / 2;
        if (ground(w.get(mx, gy - 1, mz)) || !ground(w.get(feet.getX(), gy - 1, feet.getZ()))) return false;
        for (int y = gy; y <= gy + 2; y++)
            if (!open(w.get(mx, y, mz)) || !open(w.get(feet.getX(), y, feet.getZ())) || !open(w.get(ax, y, az))) return false;
        // From the middle of its own block, so it lands in the middle of the other one.
        if (Math.abs(a.x() - (ax + 0.5)) > 0.08 || Math.abs(a.z() - (az + 0.5)) > 0.08) { a.stepToward(ax + 0.5, az + 0.5); return true; }
        a.hop(Integer.signum(dx) * 0.265, Integer.signum(dz) * 0.265);
        return true;
    }

    /** A point inside a water source it can look at with nothing solid in the way (running water does not stop a bucket). */
    private static double[] scoopPoint(BlockSource w, AgentHandle a, BlockPos t) {
        double ex = a.x(), ey = a.y() + 1.62, ez = a.z();
        for (double fy : new double[]{0.5, 0.3, 0.7, 0.18, 0.85, 0.1})
            for (double fx : new double[]{0.5, 0.3, 0.7, 0.15, 0.85})
                for (double fz : new double[]{0.5, 0.3, 0.7, 0.15, 0.85}) {
                    double px = t.getX() + fx, py = t.getY() + fy, pz = t.getZ() + fz;
                    if (Math.sqrt((px - ex) * (px - ex) + (py - ey) * (py - ey) + (pz - ez) * (pz - ez)) > 4.3) continue;
                    boolean ok = true;
                    for (double oy : new double[]{0, 0.05, -0.05}) {
                        for (double s = 0; s <= 1 && ok; s += 0.01) {
                            int bx = (int) Math.floor(ex + (px - ex) * s), by = (int) Math.floor(ey + (py + oy - ey) * s), bz = (int) Math.floor(ez + (pz - ez) * s);
                            if (bx == t.getX() && by == t.getY() && bz == t.getZ()) break;
                            if (solid(w.get(bx, by, bz))) ok = false;
                        }
                        if (!ok) break;
                    }
                    if (ok) return new double[]{px, py, pz};
                }
        return null;
    }

    /** A bucket of lava from the pool: one within reach if there is one (not from where the frame goes), else it walks to some. */
    private boolean getLava(AgentHandle a, BlockSource w) {
        if (count(a, "minecraft:lava_bucket") > 0) return true;
        if (!hold(a, "minecraft:bucket")) { status = "no empty bucket for lava"; return false; }
        double ex = a.x(), ey = a.y() + 1.62, ez = a.z();
        BlockPos best = null, far = null;
        double bd = Double.MAX_VALUE, fd = Double.MAX_VALUE;
        for (int dx = -12; dx <= 12; dx++) for (int dz = -12; dz <= 12; dz++) for (int dy = -1; dy <= 0; dy++) {
            BlockPos p = origin.offset(dx, dy, dz);
            if (!lavaSource(w.get(p.getX(), p.getY(), p.getZ())) || !open(w.get(p.getX(), p.getY() + 1, p.getZ()))) continue;
            double tx = p.getX() + 0.5, ty = p.getY() + 0.9, tz = p.getZ() + 0.5;
            double d = Math.sqrt((tx - ex) * (tx - ex) + (ty - ey) * (ty - ey) + (tz - ez) * (tz - ez));
            if (d < fd) { fd = d; far = p; }
            if (d <= 4.2 && d < bd && Sight.clear(w, ex, ey, ez, tx, ty, tz)) { bd = d; best = p; }
        }
        if (best != null) {
            if (a.isNavigating()) a.stopNavigation();
            status = "scooping lava at " + best.toShortString();
            if (stepTicks % 8 == 2) a.lookAt(best.getX() + 0.5, best.getY() + 0.9, best.getZ() + 0.5);
            if (stepTicks % 8 == 5) a.useItem();
            return false;
        }
        if (far == null) { status = "no lava left in the pool"; return false; }
        // None in reach: toward the nearest, onto whatever it can stand on beside it.
        status = "walking to lava at " + far.toShortString();
        if (!a.isNavigating() && stepTicks % 20 == 1) a.navigate(far.getX(), far.getY() + 1, far.getZ(), 3);
        return false;
    }

    /** Where each speedrunner is with its portal (by name): the site and the step, so a reconnect carries on. */
    private static final java.util.Map<String, int[]> SAVED = new java.util.concurrent.ConcurrentHashMap<>();

    private void remember(AgentHandle a) {
        if (origin != null) SAVED.put(a.name(), new int[]{origin.getX(), origin.getY(), origin.getZ(), alongX, alongZ, inX, inZ, step});
    }

    /** Back after a reconnect (a new module): the same site, the same step. */
    private boolean resumeLater;

    private boolean resume(AgentHandle a) {
        int[] s = SAVED.get(a.name());
        if (s == null) return false;
        // Back from a death with nothing: its kit first. Straight on to "enter", it walked into the nether with empty hands
        // (run 1119a, 23 minutes in: no pickaxe, no shield, no food, and no blocks to bridge the lava with).
        if (!hasShield(a) || Gear.bestTool(a.inventory(), "pickaxe") < 0 || Gear.bestTool(a.inventory(), "axe") < 0 && Gear.bestTool(a.inventory(), "sword") < 0) {
            if (!resumeLater) a.log("[speedrun] back with no kit: gearing up first, then on with its portal at " + s[0] + " " + s[1] + " " + s[2]);
            resumeLater = true;
            return false;
        }
        resumeLater = false;
        origin = new BlockPos(s[0], s[1], s[2]);
        alongX = s[3]; alongZ = s[4]; inX = s[5]; inZ = s[6];
        pool = origin;
        plan();
        step = Math.min(s[7], steps.size());
        stepTicks = 0; tries = 0;
        a.log("[speedrun] back: carrying on with its portal at " + origin.toShortString() + ", step " + (step + 1) + "/" + steps.size());
        go(a, Stage.CAST);
        return true;
    }

    private void nextStep(AgentHandle a, Step s) {
        step++; stepTicks = 0; tries = 0;
        remember(a);
        if (stepMode) { stepGo = false; a.log("§b[speedrun] step " + step + "/" + steps.size() + " done. Next: " + (step < steps.size() ? describe(steps.get(step)) : "light the portal") + "  (/agent step " + a.name() + ")"); }
        else a.log("[speedrun] step " + step + "/" + steps.size() + " done");
    }

    /** Step-by-step: after each step it waits for "/agent step <name>". */
    private boolean stepMode, stepGo;
    /** Ticks it has stood still on its working spot. */
    private int settled;
    /** Blocks standing on the shore of its pool that come out before the first step. */
    private final List<BlockPos> clear = new ArrayList<>();
    private int clearTicks;
    /** Ticks its lava has sat in place without turning to obsidian. */
    private int lavaWait;
    private boolean mapped;

    /** The pool it chose lies underground: it goes down to it first. */
    private boolean poolDeep, siteDeep, descendSaid;
    private BlockPos descendTop;
    /** Staircase starts that got stuck in water: never chosen again (nor anything within 6 blocks). */
    private final List<BlockPos> badDescend = new ArrayList<>();

    /** The top of this column if it is dry land with no water or lava beside it (its staircase starts there); else null. */
    private static BlockPos dryTop(BlockView w, int x, int z, int fromY) {
        for (int y = fromY + 24; y >= fromY - 24; y--) {
            BlockState s = w.get(x, y, z);
            if (s == null) return null;
            // Through a tree's leaves to the ground under them: in a forest nearly every column started in leaves and was "not
            // dry ground", and the main gave the pool up every 20 s (run 1120zd).
            if (s.isAir() || s.canBeReplaced() && s.getFluidState().isEmpty() || s.is(net.minecraft.tags.BlockTags.LEAVES)) continue;
            if (!s.getFluidState().isEmpty() || !solid(s)) return null;
            // The whole staircase: a 4x4 ring going +x +z from here, a block round it, and 8 blocks down. Checking only round
            // the top it started beside a pond and its third step was in the water (run 1119m).
            for (int dx = -1; dx <= 4; dx++) for (int dz = -1; dz <= 4; dz++) for (int dy = -8; dy <= 1; dy++) {
                BlockState n = w.get(x + dx, y + dy, z + dz);
                if (n == null || !n.getFluidState().isEmpty()) return null;
            }
            return new BlockPos(x, y + 1, z);
        }
        return null;
    }


    /**
     * Down to an underground pool: to a spot above the shore (eight blocks back from the lava, so the stairs do not come
     * out in it), then its own spiral staircase down to the pool's level. Lava or water it meets on the way is plugged
     * (the staircase does that). True while it is on its way.
     */
    private boolean descend(AgentHandle a) {
        if (!poolDeep || origin == null) return false;
        if (a.y() <= origin.getY() + 3.5) { poolDeep = false; stairOrigin = null; a.log("§a[speedrun] down at the lava pool (y " + origin.getY() + ")"); return false; }
        if (!(a.openWorld() instanceof BlockView w)) return true;
        // Dry land to start from: agents cannot work under water (the user, 2026-11-19; run 1119i: its staircase began in a
        // lake and stood on step 1, "y60=water y61=water y62=water", until the run ended). The nearest dry column 6 to 20
        // blocks from the pool, the shore side first.
        if (descendTop == null || descendTop.distSqr(origin) > 40 * 40) {
            BlockPos best = null; double bd = Double.MAX_VALUE;
            for (int r = 6; r <= 20; r += 2) for (int deg = 0; deg < 360; deg += 30) {
                int cx = origin.getX() + (int) Math.round(Math.cos(Math.toRadians(deg)) * r), cz = origin.getZ() + (int) Math.round(Math.sin(Math.toRadians(deg)) * r);
                BlockPos t = dryTop(w, cx, cz, (int) Math.floor(a.y()));
                if (t != null) for (BlockPos bad : badDescend) if (Math.abs(bad.getX() - t.getX()) <= 6 && Math.abs(bad.getZ() - t.getZ()) <= 6) { t = null; break; }
                if (t == null) continue;
                double d = t.distToCenterSqr(a.x(), a.y(), a.z());
                if (d < bd) { bd = d; best = t; }
            }
            if (best == null) { if (stepTicks > 20 * 20) fail(a, "no dry ground to dig down from above the pool at " + origin.toShortString()); return true; }
            descendTop = best;
        }
        int tx = descendTop.getX(), tz = descendTop.getZ();
        double hd = Math.hypot(a.x() - (tx + 0.5), a.z() - (tz + 0.5));
        if (stairOrigin == null && hd > 4) {
            status = "walking to above the underground lava pool (" + Math.round(hd) + " blocks)";
            if (!a.isNavigating() && stepTicks % 40 == 1) a.navigate(tx, (int) Math.floor(a.y()), tz, 3);
            // A long way and no getting nearer (water, a cliff): down from here, the tunnel does the rest.
            if (stepTicks > 20 * 120 && hd < 24) stepTicks = 0; else if (stepTicks > 20 * 120) { fail(a, "could not get above the underground pool at " + origin.toShortString()); return true; }
            if (hd > 4 && !(stepTicks > 20 * 90 && hd < 24)) return true;
        }
        if (a.isNavigating() && stairOrigin == null) a.stopNavigation();
        status = "digging down to the lava pool (y " + origin.getY() + ", it is at " + (int) Math.floor(a.y()) + ")";
        if (!descendSaid) { descendSaid = true; dryDone = true; }
        if (!staircase(a, w, origin.getY() + 1) && a.y() > origin.getY() + 3.5 && stairOrigin == null) { fail(a, "could not dig down to the pool at " + origin.toShortString()); }
        return true;
    }

    private final Tunnel shoreTunnel = new Tunnel();
    private boolean shoreTunnelOn;
    private void cast(AgentHandle a) {
        BlockSource w = a.openWorld();
        if (w == null) return;
        stepTicks++;
        if (descend(a)) return;
        if (!clear.isEmpty()) {
            BlockPos b = clear.get(0);
            BlockState cs = w.get(b.getX(), b.getY(), b.getZ());
            if (cs == null) return;
            if (!solid(cs)) { clear.remove(0); return; }
            stepTicks = 0;
            // 150 s, plus 6 s for each block still to go: an underground pool had 52 blocks of rock on its shore, and with
            // a flat 150 s it gave up on the pool it had just dug 60 blocks down to (run 1120d).
            if (++clearTicks > 20 * (150 + 6 * clear.size())) { clear.clear(); fail(a, "could not clear the shore at " + b.toShortString()); return; }
            status = "clearing the shore at " + b.toShortString() + " (" + clear.size() + " to go)";
            if (b.distToCenterSqr(a.x(), a.y() + 1.62, a.z()) > 4.2 * 4.2) {
                // Walked to when there is a way; when not, a tunnel straight to it. Its staircase ends 6 to 20 blocks from an
                // underground pool (on dry ground), in rock, and "no way there" every 10 s kept the main at the foot of
                // its staircase for 6 minutes until it gave the pool up (run 1120p; run 1120d too).
                if (shoreTunnelOn || clearTicks > 60 && !a.isNavigating()) {
                    if (!shoreTunnelOn) { shoreTunnelOn = true; shoreTunnel.reset(); if (a.isNavigating()) a.stopNavigation(); a.log("[speedrun] tunnelling to the pool's shore at " + b.toShortString()); }
                    Tunnel.Result r = shoreTunnel.tick(a, b);
                    if (r == Tunnel.Result.BLOCKED) { shoreTunnelOn = false; clearTicks = Math.max(clearTicks, 61); a.log("§e[speedrun] the tunnel to the shore is blocked (" + shoreTunnel.problem() + "): walking again"); }
                    return;
                }
                if (!a.isNavigating() && clearTicks % 20 == 1) a.navigate(b.getX(), b.getY(), b.getZ(), 3);
                return;
            }
            shoreTunnelOn = false;
            if (a.isNavigating()) a.stopNavigation();
            if (a.isDigging() || clearTicks % 5 != 0) return;
            Gear.holdBestFor(a, cs);
            a.lookAt(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
            a.dig(b.getX(), b.getY(), b.getZ());
            return;
        }
        if (step >= steps.size()) { if (a.isNavigating()) a.stopNavigation(); go(a, Stage.LIGHT); return; }
        if (step >= steps.size()) { status = "that is every step it knows so far (" + steps.size() + ")"; if (a.isNavigating()) a.stopNavigation(); return; }
        Step s = steps.get(step);
        BlockPos t = c(s.x(), s.y(), s.z());
        BlockState here = w.get(t.getX(), t.getY(), t.getZ());
        if (here == null) return;
        boolean done = switch (s.kind()) {
            case 'B' -> solid(here);
            case 'W' -> waterSource(here);
            case 'L' -> here.getBlock() == Blocks.OBSIDIAN || lavaWait > 40;
            case 'D' -> here.getBlock() != Blocks.COBBLESTONE;
            case 'X' -> !solid(here) || here.getBlock() == Blocks.OBSIDIAN;
            default -> !waterSource(here);
        };
        if (s.kind() == 'L' && lavaSource(here)) lavaWait++; else if (!done) lavaWait = 0;
        if (done) { if (lavaWait > 40) a.log("[speedrun] the lava at " + t.toShortString() + " did not turn to obsidian"); lavaWait = 0; nextStep(a, s); return; }
        if (stepMode && !stepGo) { stepTicks = 0; status = "waiting for /agent step: next is to " + describe(s); if (a.isNavigating()) a.stopNavigation(); return; }
        if (stepTicks > 20 * 180) { fail(a, "step " + (step + 1) + " (" + describe(s) + ") took too long"); return; }
        // Lava in hand before it climbs (scooped from the pool at its feet).
        if (s.kind() == 'L' && !lavaSource(here) && count(a, "minecraft:lava_bucket") == 0) {
            // Down from its pillar first (taking it with it): the lava is at the pool's surface.
            if (a.y() > origin.getY() + 1.05 && !standAt(a, w, c(s.sx(), 1, s.sz()), 0)) { status = "coming down for lava"; return; }
            getLava(a, w);
            return;
        }
        if (s.kind() == 'W' && count(a, "minecraft:water_bucket") == 0) { status = "no water bucket for step " + (step + 1); if (stepTicks > 20 * 20) fail(a, "it has no water bucket"); return; }
        if (!standAt(a, w, c(s.sx(), 1, s.sz()), s.h())) {
            settled = 0;
            if (!status.startsWith("out of")) status = "getting into place for step " + (step + 1);
            if (stepTicks % 600 == 300) { // why it isn't there: once every 30 s
                BlockPos f = c(s.sx(), 1, s.sz());
                StringBuilder col = new StringBuilder();
                for (int y = f.getY() - 2; y <= f.getY() + 2; y++) { BlockState bs = w.get(f.getX(), y, f.getZ()); col.append(y).append('=').append(bs == null ? "?" : net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(bs.getBlock()).getPath()).append(' '); }
                a.log(String.format("[speedrun] not in place for step %d: at %.2f %.2f %.2f, wants %d %d %d (+%d up), navigating=%s, pillarAt=%s, travel: %s; its spot: %s",
                        step + 1, a.x(), a.y(), a.z(), f.getX(), f.getY(), f.getZ(), s.h(), a.isNavigating(), pillarAt, a.navStatus(), col));
            }
            return;
        }
        // A bucket goes where the server thinks it is looking from: its last steps are still on their way there for a
        // moment after it stops (they are sent one per tick), and a bucket used in that moment was aimed from the
        // spot before (the water went on top of the cobblestone and flooded the pool).
        if (++settled < 7 && s.kind() != 'B' && s.kind() != 'D') { status = "settling before step " + (step + 1); return; }
        switch (s.kind()) {
            case 'B', 'W', 'L' -> {
                boolean lava = s.kind() == 'L';
                if (lava) {
                    // Only where there is water to turn it: in its place, beside it or over it. Lava with no water runs.
                    boolean wet = water(here) || water(w.get(t.getX(), t.getY() + 1, t.getZ()));
                    for (Direction d : Direction.Plane.HORIZONTAL) wet |= water(w.get(t.getX() + d.getStepX(), t.getY(), t.getZ() + d.getStepZ()));
                    if (lavaSource(here)) wet = false;
                    if (lavaSource(here)) return; // placed: it turns or it does not
                    if (!wet && tries == 0) a.log("[speedrun] no water at " + t.toShortString() + ": the lava goes there anyway");
                }
                if (stepTicks % 8 != 0) return;
                String item = s.kind() == 'B' ? "minecraft:cobblestone" : lava ? "minecraft:lava_bucket" : "minecraft:water_bucket";
                if (!hold(a, item)) { if (s.kind() == 'B') go(a, Stage.COBBLE); return; }
                Click k = clickFor(w, a, t);
                if (k == null && s.kind() == 'B') {
                    // A block needs no line of sight, only a solid neighbour in reach (the shore block's side, for the first one into the lava).
                    for (Direction d : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
                        BlockPos n = t.relative(d);
                        if (!solid(w.get(n.getX(), n.getY(), n.getZ())) || n.distToCenterSqr(a.x(), a.y() + 1.62, a.z()) > 4.5 * 4.5) continue;
                        Direction fc = d.getOpposite();
                        k = new Click(n, fc, new double[]{n.getX() + 0.5 + fc.getStepX() * 0.5, n.getY() + 0.5 + fc.getStepY() * 0.5, n.getZ() + 0.5 + fc.getStepZ() * 0.5});
                        break;
                    }
                }
                if (k == null) { status = "can't see anything to place against for " + t.toShortString(); if (++tries > 60) fail(a, "nothing to place against at " + t.toShortString()); return; }
                if (++tries > 40) { fail(a, "couldn't " + describe(s)); return; }
                status = (s.kind() == 'B' ? "placing cobblestone at " : lava ? "placing lava at " : "pouring water at ") + t.toShortString();
                a.lookAt(k.point()[0], k.point()[1], k.point()[2]);
                if (s.kind() == 'B') a.placeAgainst(k.against().getX(), k.against().getY(), k.against().getZ(), k.face());
                else a.useItem();
            }
            case 'D', 'X' -> {
                if (a.isDigging() || stepTicks % 5 != 0) return;
                status = "breaking the cobblestone at " + t.toShortString();
                Gear.holdBestFor(a, here);
                a.lookAt(t.getX() + 0.5, t.getY() + 0.5, t.getZ() + 0.5);
                a.dig(t.getX(), t.getY(), t.getZ());
            }
            default -> { // taking the water back
                if (stepTicks % 8 != 0) return;
                status = "taking the water back from " + t.toShortString();
                if (++tries > 15) {
                    // It won't come up: a block in its place gets rid of it.
                    Click k = clickFor(w, a, t);
                    if (k != null && hold(a, "minecraft:cobblestone")) { a.lookAt(k.point()[0], k.point()[1], k.point()[2]); a.placeAgainst(k.against().getX(), k.against().getY(), k.against().getZ(), k.face()); }
                    if (tries > 40) fail(a, "can't get rid of the water at " + t.toShortString());
                    return;
                }
                if (!hold(a, "minecraft:bucket")) return;
                double[] sp = scoopPoint(w, a, t);
                if (sp == null) { status = "can't see the water at " + t.toShortString(); return; }
                a.lookAt(sp[0], sp[1], sp[2]);
                a.useItem();
            }
        }
    }

    /** The site in the log: one row per height, x -1..4; columns of z: 2, 1 (the frame), 0 (the edge row), -1 (the shore). */
    private void frameMap(AgentHandle a, String why) {
        BlockSource w = a.openWorld();
        if (w == null || origin == null) return;
        StringBuilder sb = new StringBuilder();
        for (int y = 6; y >= -1; y--) {
            sb.append(String.format("  y%+d: ", y));
            for (int z : new int[]{2, 1, 0, -1}) {
                for (int x = -1; x <= 4; x++) {
                    BlockPos p = c(x, y, z);
                    BlockState s = w.get(p.getX(), p.getY(), p.getZ());
                    char ch = s == null ? '?' : s.getBlock() == Blocks.OBSIDIAN ? 'O' : s.getBlock() == Blocks.COBBLESTONE ? 'c'
                            : s.getFluidState().is(net.minecraft.tags.FluidTags.LAVA) ? (s.getFluidState().isSource() ? 'L' : 'l')
                            : s.getFluidState().is(net.minecraft.tags.FluidTags.WATER) ? (s.getFluidState().isSource() ? 'W' : 'w') : s.isAir() ? '.' : '#';
                    sb.append(ch);
                }
                sb.append("  ");
            }
            sb.append('\n');
        }
        dev.agentnet.AgentNet.LOG.info("[{}] [speedrun] site at {} ({}); it is at {} {} {}; z=2 | z=1 frame | z=0 edge row | z=-1 shore, x -1..4:\n{}", a.name(), origin.toShortString(), why,
                String.format("%.2f", a.x()), String.format("%.2f", a.y()), String.format("%.2f", a.z()), sb);
    }


    /** Going in circles (Agent's watch): the portal site it keeps coming back to is given up. */
    @Override
    public void circling(AgentHandle a, String where) {
        if (!isMain(a)) return;
        if (origin != null && (stage == Stage.CAST || stage == Stage.POOL)) fail(a, "going in circles round " + where);
    }
    private void fail(AgentHandle a, String why) {
        SAVED.remove(a.name());
        a.log("§e[speedrun] " + why + ": starting the portal again somewhere else");
        frameMap(a, "failed: " + why);
        if (origin != null) badSpots.add(origin);
        origin = null;
        go(a, Stage.POOL);
    }

    private boolean hold(AgentHandle a, String id) {
        List<ItemStack> inv = a.inventory();
        for (int i = 0; i < inv.size(); i++) {
            if (!Gear.id(inv.get(i)).equals(id)) continue;
            if (i >= 9) { a.moveToHotbar(i, 7); i = 7; }
            if (a.selectedSlot() != i) a.selectHotbar(i);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ light and in

    private void light(AgentHandle a) {
        BlockSource w = a.openWorld();
        if (w == null) return;
        stepTicks++;
        BlockPos inside = c(1, 1, 0);
        BlockState in = w.get(inside.getX(), inside.getY(), inside.getZ());
        if (in != null && in.getBlock() == Blocks.NETHER_PORTAL) { a.log("§a[speedrun] its nether portal is lit"); mainPortal = c(1, 0, 0); savePortal(mainPortal); mainPortalFront = c(1, 1, 1); mainPortal2 = c(2, 0, 0); mainPortalFront2 = c(2, 1, 1); go(a, Stage.ENTER); return; }
        // The nether kit (full iron, a golden helmet) before it is lit, not after: lit first, the main went off mining iron for
        // its chestplate beside it, walked into it, and a piglin killed it in the nether 2 minutes later (run 1120r, 15.9 min).
        if (!nether(a) && netherArmor(a)) { stepTicks = 0; stageTicks = 0; return; } // (the clock too: it ran on while the kit was made, and the portal "didn't light" the moment it was done, run 1120s)
        if (stageTicks == 1) frameMap(a, "frame done: lighting it");
        // 90 s, and a second for each block it has to walk back (the gold for its helmet took it 140 blocks off, run 1120s).
        BlockPos lightAt = c(1, 1, 1);
        if (stageTicks > 20 * (90 + (int) Math.sqrt(lightAt.distToCenterSqr(a.x(), a.y(), a.z())))) { a.log("§e[speedrun] the portal didn't light: checking the frame again"); frameMap(a, "didn't light"); step = 0; stepTicks = 0; go(a, Stage.CAST); return; }
        if (!standAt(a, w, c(1, 1, 1), 0)) { status = "getting into place to light the portal"; return; }
        // Water still running inside the frame puts the fire out: wait for it to drain.
        for (int dx = 1; dx <= 2; dx++) for (int dy = 0; dy <= 2; dy++) {
            BlockPos p = c(dx, dy, 0);
            BlockState s = w.get(p.getX(), p.getY(), p.getZ());
            if (s == null || !s.getFluidState().isEmpty()) { status = "waiting for the water to run out of the frame"; return; }
        }
        if (stageTicks % 20 != 5) return;
        if (!hold(a, "minecraft:flint_and_steel")) { go(a, Stage.LIGHTER); return; }
        status = "lighting the portal";
        BlockPos bottom = c(1, -1, 0);
        double[] p = aimAt(w, a, bottom, Direction.UP);
        if (p == null) { status = "can't see the bottom of the frame to light it"; return; }
        a.lookAt(p[0], p[1], p[2]);
        a.useItemOn(bottom.getX(), bottom.getY(), bottom.getZ(), Direction.UP);
    }

    // ------------------------------------------------------------------ the nether kit
    //
    // Nobody goes into the Nether without full iron and a golden helmet (the user, 2026-11-19: "make it a requirement to be
    // in full iron with a golden helmet to go into the nether"). Iron against the blazes and the falls, the gold so the
    // piglins leave it alone. Chestplate 8 iron, leggings 7, boots 4, the helmet 5 gold: mined, smelted, made and put on by
    // itself, in that order.

    private static boolean ironOrBetter(ItemStack s) {
        String id = Gear.id(s);
        return id.startsWith("minecraft:iron_") || id.startsWith("minecraft:diamond_") || id.startsWith("minecraft:netherite_");
    }

    /** Wears it all: a golden helmet, and iron (or better) on chest, legs and feet. */
    static boolean netherArmorOn(AgentHandle a) {
        List<ItemStack> on = a.armor();
        if (on.size() < 4) return false;
        return Gear.id(on.get(0)).equals("minecraft:golden_helmet") && ironOrBetter(on.get(1)) && ironOrBetter(on.get(2)) && ironOrBetter(on.get(3));
    }

    /** A tool of this kind, iron or better, in its pack. */
    private static boolean ironTool(AgentHandle a, String kind) {
        for (ItemStack s : a.inventory()) if (kind.equals(Gear.toolType(s)) && ironOrBetter(s)) return true;
        return false;
    }

    /** Food ready to eat (cooked meat and the like; no golden apples, rotten flesh or spider eyes). */
    private static int readyFood(AgentHandle a) {
        int n = 0;
        for (ItemStack s : a.inventory()) {
            String id = Gear.id(s).replace("minecraft:", "");
            if (MEAT.contains(id)) continue;
            if (Gear.isFood(s) && !id.contains("golden_apple") && !id.equals("rotten_flesh") && !id.equals("spider_eye")) n += s.getCount();
        }
        return n;
    }

    /** At least this much food for the nether. */
    static final int KIT_FOOD = 32;

    /**
     * The whole nether kit (the user, 2026-11-20: "iron boots, iron leggings, iron chestplate, gold helmet, iron sword, iron
     * pickaxe, shield and at least 32 cooked meat each"): for the main and every helper before anyone goes through.
     */
    static boolean netherKit(AgentHandle a) {
        return netherArmorOn(a) && ironTool(a, "sword") && ironTool(a, "pickaxe") && hasShield(a) && readyFood(a) >= KIT_FOOD;
    }

    /** Not in its recipe book: queued to be made by hand on a crafting table (the pattern is known to the crafter). */
    private boolean queueByHand(AgentHandle a, String item) { crafter.queue(a, item); return true; }

    private boolean kitMining;
    private String kitSaid;
    private int kitAt = -1000;

    /** Gets and puts on what is missing of the nether kit. True while it is busy with it; false once it wears it all. */

    private int kitFoodAskAt = -100_000, kitWanderAt, kitFoodSaid = -1;

    /**
     * The kit's food: 32 ready to eat. It asks the crew for what it lacks (once a minute), hunts the animals it sees and
     * cooks their meat, and with none in sight walks on to find some. True while busy with that.
     */
    private boolean kitFood(AgentHandle a) {
        int have = readyFood(a);
        if (have >= KIT_FOOD) return false;
        if (kitMining) { mine(a, null); kitMining = false; }
        status = "getting food for the nether (" + have + " of " + KIT_FOOD + ")";
        if (have / 8 != kitFoodSaid) { kitFoodSaid = have / 8; a.log("[speedrun] " + status); }
        if (ticks - kitFoodAskAt > 1200) { kitFoodAskAt = ticks; BuilderModule.requestMaterial(a, "food", KIT_FOOD - have); }
        if (feed(a, KIT_FOOD, true)) return true;
        if (!a.isNavigating() && ticks - kitWanderAt > 300 && a.openWorld() instanceof BlockView w) { kitWanderAt = ticks; wanderAhead(a, w, 24); }
        return true;
    }
    private boolean netherArmor(AgentHandle a) {
        if (wearArmor(a)) return true;
        if (netherKit(a)) { if (kitMining) { mine(a, null); kitMining = false; } return false; }
        List<ItemStack> on = a.armor();
        String item = null;
        for (int slot = 1; slot <= 3 && item == null; slot++) {
            if (ironOrBetter(on.get(slot))) continue;
            int have = Gear.bestArmor(a.inventory(), ARMOR_SLOTS[slot]);
            if (have >= 0 && ironOrBetter(a.inventory().get(have))) return true; // in its pack: wearArmor puts it on next
            item = "minecraft:iron_" + ARMOR_SLOTS[slot];
        }
        if (item == null && !Gear.id(on.get(0)).equals("minecraft:golden_helmet")) {
            if (count(a, "minecraft:golden_helmet") > 0) return true; // in its pack: wearArmor puts it on next
            item = "minecraft:golden_helmet";
        }
        // Then its tools and the shield, and last the food (the rest of the kit, 2026-11-20).
        if (item == null) {
            if (!ironTool(a, "sword")) item = "minecraft:iron_sword";
            else if (!ironTool(a, "pickaxe")) item = "minecraft:iron_pickaxe";
            else if (!hasShield(a)) item = "minecraft:shield";
            else return kitFood(a);
        }
        // Wood for the handle, or the shield's six planks.
        if (!item.contains("helmet") && !item.contains("chestplate") && !item.contains("leggings") && !item.contains("boots")) {
            int wood = 0;
            for (ItemStack s : a.inventory()) { String id = Gear.id(s); if (id.endsWith("_planks")) wood += s.getCount(); else if ((id.endsWith("_log") || id.endsWith("_stem")) && !id.contains("stripped")) wood += 4 * s.getCount(); else if (id.equals("minecraft:stick")) wood += s.getCount() / 2; }
            if (wood < (item.endsWith("shield") ? 6 : 2)) {
                status = "chopping wood for its " + item.substring(10).replace('_', ' ') + " (the nether kit)";
                if (!status.equals(kitSaid)) { kitSaid = status; a.log("[speedrun] " + status); }
                if (minerJob != null && miner.idle() && miner.status().startsWith("done") && ticks - jobRestart > 100) { minerJob = null; jobRestart = ticks; }
                mine(a, "mine 2 logs"); kitMining = true; miner.tick(a);
                return true;
            }
        }
        boolean gold = item.equals("minecraft:golden_helmet");
        String ingot = gold ? "minecraft:gold_ingot" : "minecraft:iron_ingot", raw = gold ? "minecraft:raw_gold" : "minecraft:raw_iron";
        int cost = gold ? 5 : item.endsWith("chestplate") ? 8 : item.endsWith("leggings") ? 7 : item.endsWith("boots") ? 4 : item.endsWith("pickaxe") ? 3 : item.endsWith("sword") ? 2 : 1;
        int ingots = count(a, ingot), rawN = count(a, raw);
        String name = item.substring(10).replace('_', ' ');
        if (ingots >= cost) {
            if (kitMining) { mine(a, null); kitMining = false; }
            if (tableKit(a)) return true; // a table to make it on first
            status = "making its " + name + " (the nether kit)";
            if (ticks - kitAt > 200) { kitAt = ticks; if (!crafter.queueTool(a, item)) crafter.queue(a, item); } // not in its recipe book: by hand on a table
            return true;
        }
        if (ingots + rawN >= cost) {
            if (kitMining) { mine(a, null); kitMining = false; }
            if (furnaceKit(a)) return true;
            status = "smelting for its " + name + " (the nether kit)";
            if (ticks - kitAt > 200) { kitAt = ticks; crafter.onTask(a, "make " + ingot.substring(10) + " " + (cost - ingots)); }
            return true;
        }
        status = "mining " + (gold ? "gold" : "iron") + " for its " + name + " (the nether kit)";
        if (!status.equals(kitSaid)) { kitSaid = status; a.log("[speedrun] " + status); }
        if (minerJob != null && miner.idle() && miner.status().startsWith("done") && ticks - jobRestart > 100) { minerJob = null; jobRestart = ticks; }
        mine(a, "mine " + (cost - ingots - rawN) + (gold ? " gold_ore" : " iron_ore"));
        kitMining = true;
        miner.tick(a);
        return true;
    }

    private void enter(AgentHandle a) {
        if (!nether(a) && netherArmor(a)) { stageTicks = 0; return; } // full iron and a golden helmet first
        status = "walking into its portal";
        BlockPos in = c(1, 0, 0);
        if (stageTicks > 20 * 40) { go(a, Stage.LIGHT); return; }
        boolean there = (int) Math.floor(a.x()) == in.getX() && (int) Math.floor(a.z()) == in.getZ() && Math.abs(a.y() - in.getY()) < 1.2;
        if (there) { if (a.isNavigating()) a.stopNavigation(); return; }
        // Still not in after 5 s of stepping: the path finder takes it in (run 1118d: back from a death it stood a block
        // higher than the portal's floor, its head hit the frame's top on every step, and the server put it back for minutes).
        if (stageTicks > 100) { if (!a.isNavigating() && stageTicks % 20 == 1) a.navigate(in.getX(), in.getY(), in.getZ(), 0); return; }
        // Off the block in front of it and down into the frame: one step, the drop does the rest.
        if (Math.hypot(a.x() - (in.getX() + 0.5), a.z() - (in.getZ() + 0.5)) < 1.6) { if (a.isNavigating()) a.stopNavigation(); a.stepToward(in.getX() + 0.5, in.getZ() + 0.5); return; }
        BlockPos front = c(1, 1, 1);
        if (!a.isNavigating() && stageTicks % 20 == 1) a.navigate(front.getX(), front.getY(), front.getZ(), 0);
    }
}
