package dev.agentnet.role;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.agentnet.pathing.BlockSource;
import dev.agentnet.pathing.BlockView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Miner: "/agent mine &lt;agent&gt; &lt;block&gt; [amount]", e.g. {@code mine Bob diamond_ore 16}.
 *
 * <p>Like Baritone's {@code #mine}: it looks through the chunks the server sent it for the
 * nearest block of that type (deepslate variants count too), then digs a 1x2 staircase
 * tunnel straight to it, one step at a time, and walks into the hole so the drops land on
 * it. Veins come for free: after each block it looks for the nearest one again.
 *
 * <p>Safety rules for every block it digs: never bedrock, never next to lava or water (it
 * tries another direction, or gives that target up), never the block under its own feet.
 * Gravel and sand that fall into the tunnel are simply dug again.
 *
 * <p>Inventory alerts and hand-offs to distributors come from {@link GathererModule}.
 */
final class MinerModule extends GathererModule {
    private static final int SEARCH_RADIUS = 64;
    private static final int SEARCH_LIMIT = 4096;
    private static final double REACH = 4.5;
    private static final int SKIP_FOR = 20 * 60 * 5;     // unreachable target: leave it 5 minutes
    private static final int MAX_DIG_TICKS = 20 * 20;    // a block taking longer (obsidian by hand) counts as a wall
    private static final int MAX_REDIGS = 12;            // gravel keeps falling: give up that step
    private static final int MAX_STEP_FAILS = 3;

    private enum State { IDLE, SCAN, APPROACH, PICKUP, STEP, DIG, MOVE, COLLECT, WAIT, CAST, PORTAL, SHAFT }

    // ------------------------------------------------------------------ the crew's staircase

    private MineShaft.Shaft shaft;
    private int shaftIdle;
    private long shaftOffUntil;

    /** Ore well below it, near the base: down the crew's one staircase (dug deeper if needed), not a staircase of its own. */
    private boolean useShaft(AgentHandle a, BlockPos ore) {
        if (keepsLoad) return false; // the speedrunner digs its own way down: waiting its turn at the staircase held it up for hours
        if (near != null || a.y() < ore.getY() + 10 || System.currentTimeMillis() < shaftOffUntil) return false;
        MineShaft.Shaft s = MineShaft.of(a);
        if (s == null) return false;
        if (Math.hypot(s.x() - a.x(), s.z() - a.z()) > 200 || Math.hypot(s.x() - ore.getX(), s.z() - ore.getZ()) > 160) return false;
        if (MineShaft.blockedAt(s) <= 1) return false;
        shaft = s;
        shaftIdle = 0;
        return true;
    }

    private void shaft(AgentHandle a) {
        BlockSource w = a.openWorld();
        if (w == null) { pause(40, State.SHAFT); return; }
        if (++shaftIdle > 20 * 90) { a.log("§ecouldn't get on with the crew's staircase: tunnelling as before"); shaftOffUntil = System.currentTimeMillis() + 300_000; go(State.APPROACH); return; }
        int want = Math.min(shaft.stepAt(target.getY() + 1), MineShaft.blockedAt(shaft) - 1);
        int depth = MineShaft.depth(w, shaft);
        if (depth > want) {
            BlockPos st = shaft.step(want);
            status = "down the crew's staircase to y=" + st.getY();
            if (Math.abs(feetY(a) - st.getY()) <= 2 && Math.hypot(st.getX() + 0.5 - a.x(), st.getZ() + 0.5 - a.z()) < 3) { go(State.APPROACH); return; }
            if (!a.isNavigating()) a.navigate(st.getX(), st.getY(), st.getZ(), 1);
            return;
        }
        // Down at the bottom first; only there does it take the digging turn.
        BlockPos bottom = shaft.step(Math.max(0, depth - 1));
        if (bottom.distToCenterSqr(a.x(), a.y(), a.z()) > 6 * 6) {
            status = "going down the crew's staircase to its bottom (y=" + bottom.getY() + ")";
            if (!a.isNavigating()) a.navigate(bottom.getX(), bottom.getY(), bottom.getZ(), 1);
            return;
        }
        if (!MineShaft.mayDig(a)) {
            status = "waiting for " + MineShaft.digger() + " to dig the crew's staircase deeper";
            shaftIdle = 0; // waiting its turn is not being stuck
            BlockPos st = shaft.step(Math.max(0, depth - 3));
            if (!a.isNavigating() && st.distToCenterSqr(a.x(), a.y(), a.z()) > 16) a.navigate(st.getX(), st.getY(), st.getZ(), 2);
            return;
        }
        if (a.isNavigating()) return;
        BlockPos next = shaft.step(depth);
        status = "digging the crew's staircase: step " + depth + " (y=" + next.getY() + ")";
        // Within reach of all three blocks of the next step: dig from wherever it stands (it needn't be on the step before;
        // insisting on that exact block left the digger "planning" next to it while four miners waited).
        boolean reach = true;
        for (int dy = 0; dy <= 2; dy++) if (next.above(dy).distToCenterSqr(a.x(), a.y() + 1.62, a.z()) > 4.3 * 4.3) reach = false;
        if (depth > 0 && !reach) {
            BlockPos stand = shaft.step(depth - 1);
            a.navigate(stand.getX(), stand.getY(), stand.getZ(), 1);
            return;
        } else if (next.distToCenterSqr(a.x(), a.y() + 1.6, a.z()) > 16) { a.navigate(next.getX(), next.getY(), next.getZ(), 2); return; }
        for (int dy = 2; dy >= 0; dy--) {
            BlockPos c = next.above(dy);
            BlockState st = w.get(c.getX(), c.getY(), c.getZ());
            if (st == null) return;
            if (dev.agentnet.pathing.Rules.canWalkThrough(st)) continue;
            if (!safeToDig(w, c) || st.getBlock() == net.minecraft.world.level.block.Blocks.BEDROCK) {
                MineShaft.block(shaft, depth);
                a.log("the crew's staircase hit water or lava at y=" + c.getY() + ": going on from its bottom");
                go(State.APPROACH);
                return;
            }
            if (!holdTool(a, st, false)) return;
            a.lookAt(c.getX() + 0.5, c.getY() + 0.5, c.getZ() + 0.5);
            int t = a.dig(c.getX(), c.getY(), c.getZ());
            if (t < 0) { MineShaft.block(shaft, depth); go(State.APPROACH); return; }
            MineShaft.dugOne(a);
            shaftIdle = 0;
            pause(t + 1, State.SHAFT);
            return;
        }
        // Open: step down onto it, then the next one.
        shaftIdle = 0;
        a.navigate(next.getX(), next.getY(), next.getZ(), 0);
        pause(10, State.SHAFT);
    }

    /** What to mine (null = no task) and how many (0 = until stopped). */
    private Set<Block> targets;
    private String targetName;
    private final BaseDelivery delivery = new BaseDelivery();
    private boolean delivering;
    /** Mining for the base going up: only within nearRadius of it (and not deep under it), never the base itself. */
    private BlockPos near;
    private int nearRadius;
    private Bases.Base nearBase;

    /** This block is under another agent (one of the 2 below its feet, any corner of its hitbox). */
    private static boolean underAgent(AgentHandle me, BlockPos p) {
        for (AgentHandle h : dev.agentnet.bus.AgentBus.agents.get()) {
            if (h == me || !h.isOnline() || !AgentHandle.sameDim(h.dimension(), me.dimension())) continue;
            int fy = (int) Math.floor(h.y() + 0.01);
            if (p.getY() != fy - 1 && p.getY() != fy - 2) continue;
            for (double ox : new double[]{-0.3, 0.3}) for (double oz : new double[]{-0.3, 0.3})
                if ((int) Math.floor(h.x() + ox) == p.getX() && (int) Math.floor(h.z() + oz) == p.getZ()) return true;
        }
        return false;
    }

    private boolean nearOk(BlockPos p) {
        int dx = p.getX() - near.getX(), dz = p.getZ() - near.getZ();
        if (dx * dx + dz * dz > nearRadius * nearRadius) return false;
        if (p.getY() < near.getY() - 12) return false;
        BlockPos o = nearBase.origin();
        // 6 blocks clear of the base all round, at any depth: no pits at its walls for the builder to fall into
        boolean inBase = p.getX() >= o.getX() - 6 && p.getX() <= o.getX() + BasePlanner.SX + 5 && p.getZ() >= o.getZ() - 6
                && p.getZ() <= o.getZ() + BasePlanner.SZ + BasePlanner.FRONT + 5;
        return !inBase;
    }
    private int boredTicks;
    private long axeTriedAt;
    private long pickTriedAt;
    private int amount, mined;

    private State state = State.IDLE;
    private BlockPos target;
    private BlockPos next;                     // feet position of the tunnel step being dug
    private final Deque<BlockPos> toDig = new ArrayDeque<>();
    private List<BlockPos> stepCells = List.of();
    private int redigs, stepFails;
    private int wait, timer, ticks;
    private State afterWait;
    private final Map<BlockPos, Integer> skipped = new HashMap<>();
    /** Blocks its tunnel steps stood on (oldest first): never dug away. */
    private final java.util.LinkedHashSet<BlockPos> floors = new java.util.LinkedHashSet<>();
    private int toolWaits;
    private long lastSkipLog;
    private int deathSkipped;
    /** When it last gave targets up, and the area it has given up on (all of it, for three minutes). */
    private final java.util.ArrayDeque<Integer> skipTicks = new java.util.ArrayDeque<>();
    private BlockPos areaBad;
    private int areaBadUntil;
    /** Skips in a row with nothing mined between, and the place where six of them happened: left alone for two minutes. */
    private int skipRun, badUntil;
    private BlockPos badSpot;
    private String lastWarning;
    private String status = "";

    @Override
    public void onStart(AgentHandle a) {
        a.keepWorld(true); // it needs to see the blocks around its tunnel the whole time
        if (targets != null) go(State.SCAN);
    }

    @Override
    public void onStop(AgentHandle a) {
        a.keepWorld(false);
        if (a.isNavigating()) a.stopNavigation();
    }

    @Override
    public void onTask(AgentHandle a, String task) {
        if (!subJob) { resumeTask = null; resumePending = false; resumeStage = 0; errands = 0; pickWaitTask = null; pickRefused = false; resumeSample = null; } // a new order from outside replaces any pickaxe errand
        if (!subJob && task.trim().startsWith("mine")) { currentTask = task; delivering = false; } // a new job: the load stays with it
        String[] p = task.trim().split("\\s+");
        if (p[0].equals("make") || p[0].equals("brew")) { crafter.onTask(a, task); return; } // crafts, then keeps mining
        if (!p[0].equals("mine")) {
            // stop / idle / anything else: drop the mining job.
            if (targets != null) a.log("stopped mining (" + mined + " " + targetName + " mined)");
            targets = null;
            status = "";
            if (a.isNavigating()) a.stopNavigation();
            go(State.IDLE);
            return;
        }
        String block;
        int n = 0;
        try {
            if (p.length >= 3) { n = Integer.parseInt(p[1]); block = p[2]; }
            else if (p.length == 2) block = p[1];
            else { a.log("§cusage: /agent mine <agent> <block> [amount]"); return; }
        } catch (NumberFormatException e) {
            a.log("§cusage: /agent mine <agent> <block> [amount]");
            return;
        }
        Set<Block> blocks = resolve(block);
        if (blocks.isEmpty()) { a.log("§cunknown block '" + block + "'"); return; }
        if (block.equals("useful")) blocks.removeIf(b -> !canHarvest(a, b.defaultBlockState())); // bored: only what its pickaxe gets drops from
        if (blocks.isEmpty()) blocks = resolve("logs");
        targets = blocks;
        targetName = block.replace("minecraft:", "");
        // The crew's base going up: its stone and logs come from right next to it (a quarry by the base, the trees
        // around it), not from caves 60 blocks away and 50 down that nobody gets the load out of.
        near = null;
        Bases.Base site = Bases.crew(a.server(), a.dimension());
        if (site != null && !site.built() && (targetName.equals("stone") || targetName.equals("logs"))) {
            near = site.origin().offset(BasePlanner.SX / 2, 0, (BasePlanner.SZ + BasePlanner.FRONT) / 2);
            nearRadius = targetName.equals("stone") ? 24 : 48;
            nearBase = site;
        }
        amount = n;
        mined = 0;
        skipped.clear();
        stepFails = 0;
        a.log("mining " + (amount > 0 ? amount + "x " : "") + targetName
                + (blocks.size() > 1 ? " (deepslate too)" : ""));
        // Know before walking there: does anything we carry get drops from it? If not, craft the next
        // pickaxe up from what we carry, or ask for the right one (debris: diamond, diamonds: iron, iron: stone, stone: wood).
        BlockState sample = blocks.iterator().next().defaultBlockState();
        if (!canHarvest(a, sample) && !craftPickaxe(a, sample)) {
            String need = requiredPickaxe(sample);
            // Wooden and stone pickaxes it makes itself: fetch the stuff, craft, come back.
            // No pickaxe: logs -> wooden. Wooden: cobblestone -> stone (+8 for a furnace if iron comes next).
            // Stone: iron ore -> smelted in its own furnace -> iron. Iron: diamonds -> diamond.
            // (Netherite needs the Nether: that one it asks a distributor for.)
            int have = bestPickTier(a);
            int needT = need == null ? 0 : need.contains("wooden") ? 1 : need.contains("stone") ? 2 : need.contains("iron") ? 3 : need.contains("diamond") ? 4 : 5;
            // Fixed order, every time: 1. ask a distributor for one from its stock;
            // 2. it has none (or none answers in 45 s): make our own.
            if (!pickRefused && pickWaitTask == null && needT > 0 && dev.agentnet.bus.AgentBus.closest(Role.DISTRIBUTOR, a).isPresent()) {
                pickWaitTask = task;
                pickWaitSample = sample;
                pickWaitSince = ticks;
                BuilderModule.requestMaterial(a, "pickaxe:" + needT, 1);
                a.log(targetName + " needs a " + need.replace("minecraft:", "").replace('_', ' ') + " or better: asking a distributor first");
                status = "waiting for a distributor's answer about a pickaxe";
                targets = null;
                go(State.IDLE);
                return;
            }
            String errand = needT <= 4 && errands < 8 ? nextErrand(a, have, needT) : null;
            if (errand != null) {
                errands++;
                a.log("§e" + targetName + " needs a " + need.replace("minecraft:", "").replace('_', ' ') + ": getting the stuff to make one first (" + errand.substring(5) + ")");
                resumeTask = task;
                resumeSample = sample;
                errandWant = Integer.parseInt(errand.split(" ")[1]); errandExtra = 0;
                subJob = true;
                onTask(a, errand);
                subJob = false;
                return;
            }
            needsPick = need;
            a.log("§e" + targetName + " needs " + (need == null ? "a better tool" : "a " + need.replace("minecraft:", "").replace('_', ' ')) + "; asking a distributor for one");
            if (need != null) requestTool(a, need);
        } else needsPick = null;
        // Enough wood along for sticks, a crafting table and torches (8 planks' worth), unless wood is the job.
        if (!targetName.equals("logs") && resumeTask == null && errands < 8 && woodAsPlanks(a) + countId(a, "minecraft:stick") / 2 < 8
                && countId(a, "minecraft:crafting_table") == 0) {
            errands++;
            a.log("taking some wood along first (sticks, a crafting table, torches)");
            resumeTask = task;
            errandWant = 2; errandExtra = 0;
            subJob = true;
            onTask(a, "mine 2 logs");
            subJob = false;
            return;
        }
        go(State.SCAN);
    }

    // ---- asking a distributor for a pickaxe before making one

    private String pickWaitTask;
    /** What the errand's job needs a pickaxe for (null: the errand is for wood). */
    private BlockState resumeSample;
    private BlockState pickWaitSample;
    private int pickWaitSince;
    /** A distributor said it has none (for this job): make our own. */
    private boolean pickRefused;

    @Override
    public void onMessage(AgentHandle a, dev.agentnet.bus.Message m) {
        super.onMessage(a, m);
        if (m.type() == dev.agentnet.bus.Message.Type.TEXT && m.text() != null && m.text().startsWith("none available: pickaxe")) {
            pickRefused = true;
        }
    }

    /** Waiting on the distributor's answer. True while waiting (nothing else to do). */
    private boolean waitForPickaxe(AgentHandle a) {
        if (pickWaitTask == null) return false;
        boolean got = canHarvest(a, pickWaitSample);
        boolean timeout = ticks - pickWaitSince > 20 * 45;
        if (!got && !pickRefused && !timeout) { status = "waiting for a distributor's answer about a pickaxe"; return true; }
        if (!got) {
            pickRefused = true;
            a.log(timeout ? "no answer about a pickaxe in 45 s: making its own" : "the distributor has no pickaxe: making its own");
        } else a.log("got a pickaxe from the distributor");
        String t = pickWaitTask;
        pickWaitTask = null;
        subJob = true; onTask(a, t); subJob = false;
        return true;
    }

    /** "diamond_ore" -> diamond_ore + deepslate_diamond_ore; "deepslate_iron_ore" -> both too; "logs" -> every natural log. */
    public static Set<Block> resolve(String name) {
        Set<Block> out = new HashSet<>();
        String bare = name.replace("minecraft:", "");
        if (bare.equals("useful")) {
            // A bored miner: any ore worth having, and logs. (Cobblestone comes along from the tunnels.)
            for (String ore : new String[]{"diamond_ore", "iron_ore", "gold_ore", "lapis_ore", "coal_ore", "redstone_ore", "copper_ore"}) out.addAll(resolve(ore));
            out.addAll(resolve("logs"));
            return out;
        }
        if (bare.equals("logs") || bare.equals("log") || bare.equals("wood")) {
            for (Identifier id : BuiltInRegistries.BLOCK.keySet()) {
                String path = id.getPath();
                if (id.getNamespace().equals("minecraft") && path.endsWith("_log") && !path.startsWith("stripped_")) add(out, id);
            }
            return out;
        }
        Identifier id = Identifier.tryParse(name.contains(":") ? name : "minecraft:" + name);
        if (id == null) return out;
        add(out, id);
        String path = id.getPath();
        if (path.endsWith("_ore")) {
            add(out, Identifier.fromNamespaceAndPath(id.getNamespace(),
                    path.startsWith("deepslate_") ? path.substring("deepslate_".length()) : "deepslate_" + path));
        }
        return out;
    }

    private static void add(Set<Block> out, Identifier id) {
        if (!BuiltInRegistries.BLOCK.containsKey(id)) return;
        Block b = BuiltInRegistries.BLOCK.getValue(id);
        if (b != Blocks.AIR) out.add(b);
    }

    @Override
    public String status() { return status; }

    @Override
    public boolean idle() { return state == State.IDLE && resumeTask == null && !crafter.busy(); }

    // ------------------------------------------------------------------ work loop

    // ---- claims: one miner per block (and its neighbours), so two never go for the same one

    private record Claim(String who, BlockPos pos, long at) {}
    static final Map<String, Claim> CLAIMS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long CLAIM_MS = 30_000;

    private static void claim(AgentHandle a, BlockPos p) {
        if (p == null) CLAIMS.remove(a.name());
        else CLAIMS.put(a.name(), new Claim(a.name(), p.immutable(), System.currentTimeMillis()));
    }

    /** Another miner is on this block or right next to it. */
    private static boolean claimedByOther(AgentHandle a, BlockPos p) { return claimedByOther(a, p, 1, 1); }

    /** Another miner is working within `across` blocks (and `updown` in height) of this block. */
    private static boolean claimedByOther(AgentHandle a, BlockPos p, int across, int updown) {
        long now = System.currentTimeMillis();
        for (Claim c : CLAIMS.values()) {
            if (c.who().equals(a.name()) || now - c.at() > CLAIM_MS) continue;
            if (Math.abs(c.pos().getX() - p.getX()) <= across && Math.abs(c.pos().getY() - p.getY()) <= updown && Math.abs(c.pos().getZ() - p.getZ()) <= across) return true;
        }
        return false;
    }

    @Override
    protected void work(AgentHandle a) {
        ticks++;
        timer++;
        if (ticks % 100 == 0) claim(a, state == State.IDLE ? null : target); // keep it fresh while working on it
        if (crafter.busy() && !a.isDigging() && state != State.MOVE) {
            status = "crafting: " + crafter.status();
            crafter.tick(a);
            return;
        }
        // No request for a while after the last job: bored, it mines whatever useful ore is near (the autopilot
        // counts a bored miner as free, so the next real request takes it straight away).
        if (state == State.IDLE && targets == null && !delivering && (status.startsWith("done") || status.startsWith("waiting for /agent mine"))) {
            if (++boredTicks > 20 * 20) {
                boredTicks = 0;
                // Diamonds are what the crew is always short of (gear, then netherite upgrades, enchanting): no coal runs.
                boolean iron = canHarvest(a, net.minecraft.world.level.block.Blocks.DIAMOND_ORE.defaultBlockState());
                a.log("no requests: bored, mining " + (iron ? "diamonds" : "iron for a pickaxe that can take diamonds"));
                if (keepsLoad) return; // inside the speedrunner: its own stages say what to mine
                a.assignTask(iron ? "mine 16 diamond_ore" : "mine 6 iron_ore");
                return;
            }
        } else boredTicks = 0;
        // Always a working pickaxe, whatever it's doing (carrying a load back too: without one it can't dig itself
        // out). None, only a wooden one, or its only one nearly worn out (under 15% left): a new stone pickaxe from
        // the 3 cobblestone it keeps.
        if (ticks % 60 == 0 && !a.isDigging() && System.currentTimeMillis() - pickTriedAt > 45_000 // not again right after a try that failed
                && countId(a, "minecraft:cobblestone") + countId(a, "minecraft:cobbled_deepslate") >= 3) {
            int picks = 0;
            double freshest = 0;
            for (ItemStack s : a.inventory()) if (Gear.id(s).endsWith("_pickaxe")) { picks++; freshest = Math.max(freshest, Gear.durability(s)); }
            if ((picks < 2 || bestPickTier(a) < 2) && crafter.queueTool(a, "minecraft:stone_pickaxe")) { // always a spare: a broken pickaxe deep down was a stranded miner
                pickTriedAt = System.currentTimeMillis();
                a.log(picks == 0 ? "no pickaxe: making a stone one" : picks == 1 ? "making a spare stone pickaxe" : "upgrading to a stone pickaxe");
                return;
            }
        }
        if (delivering) {
            // Job done and the base is going up: its load goes to the builder before it's free for the next job.
            if (delivery.tick(a)) { status = "bringing base materials to the builder"; return; }
            delivering = false;
            status = "done (" + mined + " " + targetName + ")";
            return;
        }
        // No pickaxe or just a wooden one (it breaks after 59 blocks) and 3 cobblestone at hand: a stone pickaxe now,
        // not after a stretch of mining stone with its fist (which drops nothing).
        if (ticks % 100 == 0 && !a.isDigging() && bestPickTier(a) < 2
                && countId(a, "minecraft:cobblestone") + countId(a, "minecraft:cobbled_deepslate") >= 3
                && crafter.queueTool(a, "minecraft:stone_pickaxe")) { a.log("upgrading to a stone pickaxe"); return; }
        // Cutting wood: an axe (logs by hand take 3 s each, with a stone axe under 1 s). Stone if it carries 3
        // cobblestone, else wooden once it has 2 logs' worth of wood for it; tried again every 30 s until it has one.
        if (ticks % 100 == 50 && !a.isDigging() && "logs".equals(targetName) && Gear.bestTool(a.inventory(), "axe") < 0
                && System.currentTimeMillis() - axeTriedAt > 30_000) {
            int wood = 0;
            for (ItemStack s : a.inventory()) { String id = Gear.id(s); if (id.endsWith("_log")) wood += 4 * s.getCount(); else if (id.endsWith("_planks")) wood += s.getCount(); }
            boolean stone = countId(a, "minecraft:cobblestone") + countId(a, "minecraft:cobbled_deepslate") >= 3;
            if (wood >= (stone ? 2 : 5)) {
                axeTriedAt = System.currentTimeMillis();
                if (crafter.queueTool(a, stone ? "minecraft:stone_axe" : "minecraft:wooden_axe")) { a.log("making itself " + (stone ? "a stone" : "a wooden") + " axe for the wood"); return; }
            }
        }
        if (waitForPickaxe(a)) return;
        // On a materials errand but a pickaxe arrived meanwhile (a distributor came through after all): back to the job.
        if (resumeTask != null && !resumePending && resumeSample != null && !crafter.busy() && canHarvest(a, resumeSample)) {
            a.log("got a pickaxe meanwhile: back to " + resumeTask);
            String t = resumeTask; resumeTask = null; resumeSample = null;
            if (a.isNavigating()) a.stopNavigation();
            subJob = true; onTask(a, t); subJob = false;
            return;
        }
        if (resumePending && crafter.busy()) return; // let the queued crafting finish first (it can still be "digging" the last block)
        if (resumePending) {
            // Errand done: raw iron -> ingots (its own furnace), then the best pickaxe it can make, then back to the real job.
            if (resumeStage == 0 && countId(a, "minecraft:raw_iron") >= 3 && countId(a, "minecraft:iron_ingot") < 3 && !hasSmeltFuel(a, 3)) {
                if (errands < 8) {
                    // Nothing to burn: coal, or planks/logs (2 planks' worth smelt 3, and it keeps one for sticks).
                    errands++;
                    a.log("nothing to smelt with: getting some wood first");
                    resumePending = false;
                    targets = null;
                    errandWant = 2; errandExtra = 0;
                    subJob = true; onTask(a, "mine 2 logs"); subJob = false;
                    return;
                }
                a.log("§eno fuel to smelt its raw iron, and too many errands already");
            }
            if (resumeStage == 0) { resumeStage = 1; if (countId(a, "minecraft:raw_iron") >= 3 && countId(a, "minecraft:iron_ingot") < 3) { crafter.onTask(a, "make iron_ingot 3"); return; } }
            // Craft the pickaxe; right after the materials arrive the recipe can lag a moment: retry a few seconds.
            if (resumeStage >= 1 && resumeStage < 6) {
                if (ticks % 20 != 0) return;
                resumeStage++;
                craftTriedAt.clear();
                // Has a pickaxe already (a wood-along errand): back to the job, which upgrades by itself if it must.
                if (Gear.bestTool(a.inventory(), "pickaxe") >= 0 && (resumeSample == null || canHarvest(a, resumeSample))) { resumeStage = 6; }
                else if (craftPickaxe(a, null)) { resumeStage = 6; return; }
                if (resumeStage < 6) return;
                a.log("§ecouldn't craft a pickaxe from what it carries (cobblestone " + countId(a, "minecraft:cobblestone") + ", sticks " + countId(a, "minecraft:stick") + ", planks+logs as planks " + woodAsPlanks(a) + ")");
            }
            resumePending = false; resumeStage = 0;
            String t = resumeTask; resumeTask = null; resumeSample = null;
            subJob = true; onTask(a, t); subJob = false; // keeps the errand count: the next step up (if any) follows
            return;
        }
        // Wood but no pickaxe: make one now (planks, sticks, a table, the pickaxe) instead of waiting for an ore that needs it.
        if (resumeTask == null && !resumePending && ticks % 40 == 0 && Gear.bestTool(a.inventory(), "pickaxe") < 0 && woodAsPlanks(a) >= 9 && craftPickaxe(a, null)) return;
        if (targets == null) { status = "waiting for /agent mine"; return; }
        if (amount > 0 && mined >= amount && state != State.PICKUP) { // collect the last drops first
            if (resumeTask != null && errandHave(a) < errandWant && errandExtra < 6) {
                // Blocks broken isn't items carried (drops fall out of reach, a leaf eats a log): keep going.
                errandExtra++;
                amount += Math.max(1, errandWant - errandHave(a));
                a.log("mined " + mined + " " + targetName + " but carries only " + errandHave(a) + ": a few more");
                return;
            }
            if (resumeTask != null) {
                // Got the stuff for the pickaxe: craft it (planks, sticks, table...), then back to the real job.
                a.log("got " + mined + " " + targetName + " for a pickaxe; crafting it, then back to: " + resumeTask);
                targets = null;
                resumePending = true;
                go(State.IDLE);
                return;
            }
            a.log("done: mined " + mined + " " + targetName);
            targets = null;
            status = "done (" + mined + " " + targetName + ")";
            if (!keepsLoad && delivery.start(a)) { delivering = true; status = "bringing base materials to the builder"; }
            go(State.IDLE);
            return;
        }
        // Stuck on one block (walking back and forth within a few blocks): give it up after 30 s.
        if (Math.abs(a.x() - anchorX) + Math.abs(a.y() - anchorY) + Math.abs(a.z() - anchorZ) > 4) { anchorX = a.x(); anchorY = a.y(); anchorZ = a.z(); targetSince = ticks; } // moving on: that's progress
        if (target != null && state != State.IDLE && mined == minedAtTarget && ticks - targetSince > 20 * 30) {
            skip(a, "no progress in 30 s");
            targetSince = ticks;
            if (a.isNavigating()) a.stopNavigation();
            go(State.SCAN);
            return;
        }
        if (a.usedSlots() >= 36) {
            // Completely full: drops would stay on the ground. Wait for a distributor.
            status = "inventory full, waiting for a distributor";
            warn(a, "inventory is full; waiting for a distributor to collect");
            return;
        }
        switch (state) {
            case IDLE -> {}
            case SCAN -> scan(a);
            case APPROACH -> approach(a);
            case PICKUP -> pickup(a);
            case STEP -> step(a);
            case DIG -> dig(a);
            case MOVE -> move(a);
            case CAST -> cast(a);
            case PORTAL -> portal(a);
            case SHAFT -> shaft(a);
            case COLLECT -> {
                // The block is gone: the drops fall into the tunnel and get picked up.
                if (timer >= 8) go(State.SCAN);
            }
            case WAIT -> {
                if (a.isDigging() || --wait > 0) return;
                go(afterWait);
            }
        }
    }

    private int wanderRounds;
    private int wanderTries;
    private final int wanderDir = java.util.concurrent.ThreadLocalRandom.current().nextInt(360); // miners spread out

    /** A log with open sky over it (only leaves and more logs above): part of a tree, not of something underground. */
    private static boolean treeLog(BlockSource w, BlockPos p) {
        for (int dy = 1; dy <= 24; dy++) {
            BlockState s = w.get(p.getX(), p.getY() + dy, p.getZ());
            if (s == null) return true;
            if (s.isAir() || s.is(net.minecraft.tags.BlockTags.LEAVES) || s.is(net.minecraft.tags.BlockTags.LOGS) || !s.getFluidState().isEmpty() && dy > 3) continue;
            if (dev.agentnet.pathing.Rules.canWalkThrough(s)) continue;
            return false;
        }
        return true;
    }

    private void scan(AgentHandle a) {
        // Ancient debris, quartz, nether gold, glowstone only exist in the Nether: through the crew's portal first.
        if (netherOnly() && a.dimension() != null && a.dimension().endsWith("overworld")) { go(State.PORTAL); return; }
        BlockSource w = a.openWorld();
        if (!(w instanceof BlockView view)) { status = "waiting for world data"; pause(40, State.SCAN); return; }
        int fx = (int) Math.floor(a.x()), fy = feetY(a), fz = (int) Math.floor(a.z());
        int[] prot = {0};
        // Columns inside spawn protection are left out of the search itself (non-ops can't dig
        // there), so a common block like stone near spawn doesn't use up the whole result.
        // For the base: search around the base, not around itself (the closest matches to it may all be elsewhere).
        int sx = near != null ? near.getX() : fx, sz = near != null ? near.getZ() : fz;
        // Trees: as far as the chunks it has go (about 128 blocks): in treeless snowy mountains (run 1118d) the whole crew
        // hunted for logs in hops while trees stood 80-120 blocks off in chunks it already had.
        boolean woodJob = targets.stream().anyMatch(b -> BuiltInRegistries.BLOCK.getKey(b).getPath().endsWith("_log"));
        List<BlockPos> found = view.findAll(sx, sz, woodJob ? 128 : SEARCH_RADIUS, s -> targets.contains(s.getBlock()), SEARCH_LIMIT,
                (x, z) -> { if (a.spawnProtected(x, z)) { prot[0]++; return true; } return false; });
        int protectedSeen = prot[0];
        boolean wood = targets.stream().anyMatch(b -> BuiltInRegistries.BLOCK.getKey(b).getPath().endsWith("_log"));
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        boolean obsidian = targets.contains(net.minecraft.world.level.block.Blocks.OBSIDIAN);
        for (BlockPos p : found) {
            if (isSkipped(p)) continue;
            // Eight given up in a minute with nothing mined: nothing round here is reachable (logs over water, ore behind
            // lava: one miner skipped 247 in twelve minutes, each after a failed path search). Nothing within 48 blocks of
            // where it stood then, for three minutes: it goes to look somewhere else.
            if (areaBad != null && ticks < areaBadUntil && (p.getX() - areaBad.getX()) * (p.getX() - areaBad.getX()) + (p.getZ() - areaBad.getZ()) * (p.getZ() - areaBad.getZ()) < 48 * 48) continue;
            // Not where one of the crew was just killed: a dark cave with the iron in it took eight of them in fifteen
            // minutes, each one back to the same ore as soon as it respawned (run 1119c). Ten minutes, then again.
            if (a.nearDeathSpot(p.getX(), p.getY(), p.getZ(), 20)) { deathSkipped++; continue; }
            // Six in a row out of reach round here (stone under a beach, all of it under water: run 1118d, 65 failed path
            // searches in six minutes on one spot): nothing within 28 blocks of there for a while, so it goes to look elsewhere.
            if (badSpot != null && ticks < badUntil && (p.getX() - badSpot.getX()) * (p.getX() - badSpot.getX()) + (p.getZ() - badSpot.getZ()) * (p.getZ() - badSpot.getZ()) < 28 * 28) continue;
            if (obsidian && !obsidianOk(w, p)) continue; // lava at its side or under it: not that one
            if (near != null && !nearOk(p)) continue; // the base's quarry: close to it, not deep, never the base itself
            if (underAgent(a, p)) continue; // holding another agent up: don't dig it out from under it
            if (claimedByOther(a, p)) continue; // another miner has it
            boolean crowded = claimedByOther(a, p, 6, 4); // another miner is working right here: somewhere else if there is anywhere
            if (a.spawnProtected(p.getX(), p.getZ())) { protectedSeen++; continue; } // non-ops cant break blocks near spawn
            // Going down/up costs more than walking across (staircase: one block per step anyway).
            double dx = p.getX() - fx, dy = p.getY() - fy, dz = p.getZ() - fz;
            double d = dx * dx + dz * dz + dy * dy * 1.5;
            // Trees: only logs reachable from the ground (up to 3 above the trunk's foot), lowest first.
            if (wood && !treeLog(w, p)) continue; // only trees: logs underground (mineshaft beams, buried cabins) took it nowhere
            if (wood) { int h = heightAboveGround(w, p); if (h > 3) continue; d += h * 6; } // out of reach from the ground: leave it
            if (crowded) d += 1_000_000; // only when there's nowhere with room
            if (d < bestD) { bestD = d; best = p; }
        }
        // No obsidian it can take safely: make some, the way players do: water poured onto a lava pool.
        if (best == null && obsidian) { castStage = 0; go(State.CAST); return; }
        if (best == null && near != null) {
            // None by the base (bare rock, no trees): look anywhere, as before.
            a.log("no " + targetName + " right by the base: looking further out");
            near = null;
            pause(5, State.SCAN);
            return;
        }
        if (best == null && wanderTries < 8) {
            // Nothing in sight: go and look. Walk ~40 blocks one way (turning a quarter plus a bit each
            // time), then scan again from there, instead of waiting where there's nothing.
            double ang = Math.toRadians(wanderDir + wanderTries * 100);
            wanderTries++;
            // Each round that found nothing goes further out: eight 40-block hops turning as they go keep it in about
            // the same circle, and round after round it searched the same cleared ground by the base for minutes.
            int hop = Math.min(200, 40 * (1 + wanderRounds));
            int tx = fx + (int) Math.round(Math.cos(ang) * hop), tz = fz + (int) Math.round(Math.sin(ang) * hop);
            status = "no " + targetName + " in sight: looking further out (" + wanderTries + "/8, " + hop + " blocks a hop)";
            if (wanderTries == 1) a.log("no " + targetName + " in sight: going to look for some" + (wanderRounds > 0 ? " further out (" + hop + "-block hops)" : ""));
            a.navigate(tx, fy, tz, 12);
            pause(20 * 25, State.SCAN);
            return;
        }
        if (best == null) {
            wanderTries = 0;
            wanderRounds++;
            status = "no " + targetName + " found nearby";
            warn(a, "no reachable " + targetName + " in the chunks around it (" + SEARCH_RADIUS + " blocks)"
                    + (protectedSeen > 0 ? "; " + protectedSeen + " found are inside spawn protection, where it isn't allowed to dig" : "")
                    + "; move it somewhere else");
            pause(20 * 10, State.SCAN);
            return;
        }
        wanderTries = 0;
        wanderRounds = 0;
        if (!best.equals(target)) stepFails = 0;
        if (!best.equals(target)) { targetSince = ticks; minedAtTarget = mined; }
        target = best;
        claim(a, best);
        lastWarning = null;
        if (useShaft(a, best)) { go(State.SHAFT); return; }
        // Walk first (trees, exposed ore, anything on the surface or in a cave); tunnel only
        // when walking can't get it within reach.
        go(State.APPROACH);
    }

    // ------------------------------------------------------------------ walking to it

    /** Feet within this of the target is close enough to try reaching it. */
    private static final int APPROACH_RADIUS = 3;

    private void approach(AgentHandle a) {
        BlockSource w = a.openWorld();
        if (w == null) { pause(40, State.APPROACH); return; }
        BlockState ts = w.get(target.getX(), target.getY(), target.getZ());
        if (ts == null || !targets.contains(ts.getBlock())) { if (a.isNavigating()) a.stopNavigation(); go(State.SCAN); return; }
        status = String.format("walking to %s at %d %d %d", targetName, target.getX(), target.getY(), target.getZ());

        if (canReach(a, target) && !a.spawnProtected(target.getX(), target.getZ())) {
            if (a.isNavigating()) a.stopNavigation();
            if (!holdTool(a, ts, true)) return;
            if (!safeToDig(w, target)) { skip(a, "it touches lava or water"); return; }
            a.lookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
            int t = a.dig(target.getX(), target.getY(), target.getZ());
            if (t < 0) { skip(a, "it can't be broken"); return; }
            mined++; skipRun = 0; skipTicks.clear();
            minedAt = target;
            pickupTried.clear();
            uncovered.clear();
            pause(t + 1, State.PICKUP);
            return;
        }
        if (a.isNavigating()) return;
        if (timer == 1) {
            // A spot to stand on with the block in reach (under a tall tree's upper logs, beside a
            // cliff ore...); without one, just head for the block itself.
            BlockPos stand = standingSpot(w, target);
            if (stand != null) a.navigate(stand.getX(), stand.getY(), stand.getZ(), 1);
            else a.navigate(target.getX(), target.getY(), target.getZ(), APPROACH_RADIUS);
            return;
        }
        if (timer > 3) {
            // Walking got as close as it can (or nowhere): dig the rest of the way from here.
            dev.agentnet.AgentNet.LOG.info("[{}] can't walk within reach of {} ({}), tunnelling", a.name(), target.toShortString(), a.navStatus());
            go(State.STEP);
        }
    }

    /** The drops: walk over the items lying around the block it just mined (logs fall, ore pops out). */
    private final Map<Long, Integer> pickupTried = new HashMap<>();
    private final java.util.Set<Long> uncovered = new java.util.HashSet<>();
    /** The block it last broke: its drops are the ones lying within 4 blocks of it. */
    private BlockPos minedAt;

    private void pickup(AgentHandle a) {
        if (timer > 20 * 20) { if (a.isNavigating()) a.stopNavigation(); go(State.SCAN); return; }
        if (a.isDigging()) return; // opening a pocket a drop fell into
        if (a.isNavigating() && timer % 40 != 0) return; // re-check every 2 s in case it's walking the wrong way
        if (timer % 5 != 0) return;
        boolean left = false;
        for (net.minecraft.world.phys.Vec3 v : a.itemsNear(8)) {
            if (GroundDrops.reservedFor(a.name(), v)) continue; // another distributor's pickup
            if (minedAt != null && v.distanceToSqr(minedAt.getX() + 0.5, minedAt.getY() + 0.5, minedAt.getZ() + 0.5) > 16) continue; // not ours
            if (v.distanceToSqr(a.x(), a.y(), a.z()) < 0.8) continue; // on top of it: picked up
            left = true;
            long key = BlockPos.containing(v).asLong();
            int tries = pickupTried.getOrDefault(key, 0);
            if (tries >= 3 && !uncovered.contains(key) && !a.isNavigating()) {
                // Can't walk to it: it fell into a pocket under the ground (a block mined below another).
                // Break the block over it, once, so it's open from above, then try again.
                uncovered.add(key);
                BlockPos item = BlockPos.containing(v);
                BlockSource w = a.openWorld();
                for (int up = 1; up <= 2 && w != null; up++) {
                    BlockPos c = item.above(up);
                    BlockState st = w.get(c.getX(), c.getY(), c.getZ());
                    if (st == null || !Tunnel.solid(st) || a.spawnProtected(c.getX(), c.getZ())) continue;
                    double ex = c.getX() + 0.5 - a.x(), ey = c.getY() + 0.5 - (a.y() + 1.62), ez = c.getZ() + 0.5 - a.z();
                    if (ex * ex + ey * ey + ez * ez > REACH * REACH) continue;
                    if (BlockPos.containing(a.x(), a.y() - 0.5, a.z()).equals(c)) continue; // not the block we stand on
                    a.dig(c.getX(), c.getY(), c.getZ());
                    pickupTried.put(key, 1);
                    return;
                }
            }
            if (tries >= 3 || a.isNavigating()) continue;
            pickupTried.put(key, tries + 1);
            // Into the item's own block (a hole is fine: it climbs out by itself afterwards).
            a.navigate((int) Math.floor(v.x), (int) Math.floor(v.y + 0.2), (int) Math.floor(v.z), tries == 0 ? 0 : 1); // then next to it: pickup reaches a block around
            return;
        }
        if (!left && timer > 10 || !a.isNavigating() && timer > 20 * 4 && !left) go(State.SCAN);
        else if (!a.isNavigating() && left && pickupTried.values().stream().allMatch(n -> n >= 3)) {
            a.log("couldn't reach some drops near " + minedAt.toShortString());
            go(State.SCAN);
        }
    }

    /**
     * The nearest place to stand (solid floor, two free blocks) from which p is in reach: in
     * p's own column below it or in the 8 columns around, up to 12 blocks down. Null if none.
     */
    private static BlockPos standingSpot(BlockSource w, BlockPos p) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            for (int y = p.getY() + 1; y >= p.getY() - 12; y--) {
                int x = p.getX() + dx, z = p.getZ() + dz;
                if (x == p.getX() && z == p.getZ() && y >= p.getY() - 1) continue; // not in/on the block itself
                BlockState floor = w.get(x, y - 1, z), feet = w.get(x, y, z), head = w.get(x, y + 1, z);
                if (floor == null || feet == null || head == null) continue;
                if (!Tunnel.solid(floor) || !Tunnel.passable(feet) || !Tunnel.passable(head)) continue;
                double ex = p.getX() + 0.5 - (x + 0.5), ey = p.getY() + 0.5 - (y + 1.62), ez = p.getZ() + 0.5 - (z + 0.5);
                double d = ex * ex + ey * ey + ez * ez;
                if (d <= (REACH - 0.3) * (REACH - 0.3) && d < bestD) { bestD = d; best = new BlockPos(x, y, z); }
                break; // the first standable spot going down in this column is the one
            }
        }
        return best;
    }

    /** Within the server's reach: eye to block center. */
    private static boolean canReach(AgentHandle a, BlockPos p) {
        double dx = p.getX() + 0.5 - a.x(), dy = p.getY() + 0.5 - (a.y() + 1.62), dz = p.getZ() + 0.5 - a.z();
        return dx * dx + dy * dy + dz * dz <= REACH * REACH;
    }

    /** Plan the next tunnel step toward the target (or mine the target itself if it's right here). */
    private void step(AgentHandle a) {
        BlockSource w = a.openWorld();
        if (w == null) { pause(40, State.STEP); return; }
        BlockState ts = w.get(target.getX(), target.getY(), target.getZ());
        if (ts == null || !targets.contains(ts.getBlock())) { go(State.SCAN); return; } // gone (someone else?)
        if (underAgent(a, target)) { go(State.SCAN); return; } // someone stepped onto it meanwhile: another one
        // No pickaxe: never a tunnel down (it gets one first: the spare-pickaxe check crafts or asks for one).
        if (bestPickTier(a) == 0 && target.getY() < feetY(a)) { warn(a, "no pickaxe: not digging down to " + targetName + " until it has one"); pause(20 * 10, State.SCAN); return; }

        // No pickaxe at all (broke, or never had one): make one if the materials are there.
        if (Gear.bestTool(a.inventory(), "pickaxe") < 0 && craftPickaxe(a, null)) return;

        BlockPos f = new BlockPos((int) Math.floor(a.x()), feetY(a), (int) Math.floor(a.z()));
        floors.add(f.below());
        if (floors.size() > 512) floors.remove(floors.iterator().next());
        status = String.format("mining %s at %d %d %d (%d left)", targetName, target.getX(), target.getY(), target.getZ(),
                amount > 0 ? amount - mined : -1).replace(" (-1 left)", "");

        // Right next to us (beside feet or head, or just above the head): mine it from here.
        if (pickupClose(f, target) && !a.spawnProtected(target.getX(), target.getZ())) {
            if (!holdTool(a, ts, true)) return;
            if (!safeToDig(w, target)) { skip(a, "it touches lava or water"); return; }
            int t = a.dig(target.getX(), target.getY(), target.getZ());
            if (t < 0) { skip(a, "it can't be broken"); return; }
            mined++; skipRun = 0; skipTicks.clear();
            minedAt = target;
            pickupTried.clear();
            uncovered.clear();
            pause(t + 1, State.PICKUP); // drops can pop out sideways or fall into a hole: go get them
            return;
        }

        BlockPos chosen = null;
        List<BlockPos> cells = null;
        for (int[] d : directions(f, target)) {
            List<BlockPos> c = cellsFor(f, d[0], d[1], target);
            BlockPos n = c.get(0);
            if (c.stream().anyMatch(b -> a.spawnProtected(b.getX(), b.getZ()))) continue; // the server would refuse the dig
            if (ok(w, f, n, c)) { chosen = n; cells = c; break; }
        }
        if (chosen == null) { skip(a, "every way there is blocked by lava, water, a drop or bedrock"); return; }
        next = chosen;
        stepCells = cells.subList(1, cells.size());
        toDig.clear();
        toDig.addAll(stepCells);
        redigs = 0;
        go(State.DIG);
    }

    /** Dig every block of the step that isn't air yet, top first. */
    private void dig(AgentHandle a) {
        if (a.isDigging()) return;
        BlockSource w = a.openWorld();
        if (w == null) { pause(40, State.DIG); return; }
        while (!toDig.isEmpty()) {
            BlockPos c = toDig.poll();
            BlockState s = w.get(c.getX(), c.getY(), c.getZ());
            if (s == null || passable(s)) continue;
            if (!holdTool(a, s, false)) return;
            int t = a.dig(c.getX(), c.getY(), c.getZ());
            if (t < 0 || t > MAX_DIG_TICKS) { skip(a, "a block in the way can't be dug (" + name(s) + ")"); return; }
            if (targets.contains(s.getBlock())) mined++; // tunnelled straight through another target: counts
            pause(t + 1, State.DIG);
            return;
        }
        // Everything dug. Gravel/sand may have fallen in; check again before moving.
        for (BlockPos c : stepCells) {
            BlockState s = w.get(c.getX(), c.getY(), c.getZ());
            if (s != null && !passable(s)) {
                if (++redigs > MAX_REDIGS) { skip(a, "gravel keeps falling into the tunnel"); return; }
                toDig.addAll(stepCells);
                pause(4, State.DIG); // give the falling block time to land
                return;
            }
        }
        a.navigate(next.getX(), next.getY(), next.getZ(), 0);
        go(State.MOVE);
    }

    private void move(AgentHandle a) {
        if (a.isNavigating()) return;
        if (timer < 2) return;
        int fx = (int) Math.floor(a.x()), fy = feetY(a), fz = (int) Math.floor(a.z());
        if (fx == next.getX() && fy == next.getY() && fz == next.getZ()) {
            stepFails = 0;
            if (++tunnelSteps % TORCH_EVERY == 0) placeTorch(a, next);
            go(State.STEP);
            return;
        }
        if (++stepFails >= MAX_STEP_FAILS) { skip(a, "couldn't step into its own tunnel (" + a.navStatus() + ")"); return; }
        go(State.STEP);
    }

    // ------------------------------------------------------------------ tunnel geometry

    /**
     * Horizontal directions to try, best first: toward the target along the longer axis,
     * then the shorter one, then sideways. Straight above/below: zig-zag (alternating by height).
     */
    private static List<int[]> directions(BlockPos f, BlockPos t) {
        int dx = t.getX() - f.getX(), dz = t.getZ() - f.getZ();
        List<int[]> out = new ArrayList<>();
        int[] x = {Integer.signum(dx), 0}, z = {0, Integer.signum(dz)};
        if (Math.abs(dx) >= Math.abs(dz)) { if (dx != 0) out.add(x); if (dz != 0) out.add(z); }
        else { out.add(z); if (dx != 0) out.add(x); }
        int[][] all = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
        // Zig-zag when aligned: start direction alternates with height so the stairs fold back.
        int start = Math.floorMod(f.getY(), 2) * 2;
        for (int i = 0; i < 4; i++) {
            int[] d = all[(start + i) % 4];
            if (out.stream().noneMatch(o -> o[0] == d[0] && o[1] == d[1])) out.add(d);
        }
        return out;
    }

    /** [new feet position, then the blocks to clear for that step, top first]. */
    private static List<BlockPos> cellsFor(BlockPos f, int dx, int dz, BlockPos t) {
        int dy = t.getY() - f.getY();
        int ny = dy < 0 ? f.getY() - 1 : dy > 1 ? f.getY() + 1 : f.getY();
        BlockPos n = new BlockPos(f.getX() + dx, ny, f.getZ() + dz);
        List<BlockPos> c = new ArrayList<>();
        c.add(n);
        if (ny < f.getY()) c.add(n.above(2));          // walk across at the old height, then drop
        if (ny > f.getY()) c.add(f.above(2));          // headroom to jump up
        c.add(n.above());
        c.add(n);
        return c;
    }

    /** Can this step be dug and walked safely? */
    private boolean ok(BlockSource w, BlockPos f, BlockPos n, List<BlockPos> cells) {
        BlockState floor = w.get(n.getX(), n.getY() - 1, n.getZ());
        if (floor == null || !solidFloor(floor)) return false;     // cave, hole, fluid below
        for (int i = 1; i < cells.size(); i++) {
            BlockPos c = cells.get(i);
            // Never dig out a block an earlier step stood on: the tunnel must stay walkable for
            // distributors and players (this is what turns a fold-back staircase into a spiral).
            if (floors.contains(c)) return false;
            BlockState s = w.get(c.getX(), c.getY(), c.getZ());
            if (s == null) return false;
            if (passable(s)) continue;
            if (s.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) < 0) return false; // bedrock & co.
            if (!safeToDig(w, c)) return false;
        }
        return true;
    }

    /** No fluid in the block or next to it (digging it would let lava/water into the tunnel). */
    private static boolean safeToDig(BlockSource w, BlockPos c) {
        for (Direction d : Direction.values()) {
            BlockState s = w.get(c.getX() + d.getStepX(), c.getY() + d.getStepY(), c.getZ() + d.getStepZ());
            if (s == null || !s.getFluidState().isEmpty()) return false;
        }
        BlockState s = w.get(c.getX(), c.getY(), c.getZ());
        return s != null && s.getFluidState().isEmpty();
    }

    private static boolean solidFloor(BlockState s) {
        if (s.isAir() || !s.getFluidState().isEmpty()) return false;
        try {
            return !s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static boolean passable(BlockState s) {
        if (s.isAir()) return true;
        if (!s.getFluidState().isEmpty()) return false;
        try {
            return s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()
                    && s.getBlock() != Blocks.COBWEB && s.getBlock() != Blocks.POWDER_SNOW;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Beside the feet or head (not diagonal), or right above the head: drops land on us. */
    private static boolean pickupClose(BlockPos f, BlockPos t) {
        int dx = Math.abs(t.getX() - f.getX()), dz = Math.abs(t.getZ() - f.getZ()), dy = t.getY() - f.getY();
        if (dx + dz == 1) return dy == 0 || dy == 1;
        return dx == 0 && dz == 0 && dy == 2;
    }

    // ------------------------------------------------------------------ tools

    /**
     * Hold the fastest tool for this block. If the block needs a proper tool for drops
     * (ores) and there is none, ask a distributor for a pickaxe and wait.
     */
    private boolean holdTool(AgentHandle a, BlockState s, boolean needDrops) {
        List<ItemStack> inv = a.inventory();
        int best = -1;
        float bestSpeed = 1f;
        boolean bestHarvests = !s.requiresCorrectToolForDrops();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack it = inv.get(i);
            if (it.isEmpty()) continue;
            boolean harvests = !s.requiresCorrectToolForDrops() || it.isCorrectToolForDrops(s);
            float speed = it.getDestroySpeed(s);
            if ((harvests && !bestHarvests) || (harvests == bestHarvests && speed > bestSpeed)) {
                best = i; bestSpeed = speed; bestHarvests = harvests;
            }
        }
        if (needDrops && !bestHarvests && craftPickaxe(a, s)) return false; // crafts one, then comes back to this ore
        if (needDrops && !bestHarvests) {
            status = "needs a better pickaxe for " + name(s);
            warn(a, "needs a better pickaxe to get drops from " + name(s) + "; asking a distributor");
            String needPick = requiredPickaxe(s);
            requestTool(a, needPick != null ? needPick : "minecraft:stone_pickaxe");
            // Wait right here for the delivery (a distributor walks down the tunnel to us),
            // checking every 10 s; give the ore up after 3 minutes.
            if (++toolWaits > 18) { toolWaits = 0; skip(a, "no better pickaxe arrived"); return false; }
            pause(20 * 10, State.STEP);
            return false;
        }
        if (best < 0) {
            // Nothing helps: use whatever is in hand (an empty hand is fine for dirt and stone tunnels).
            return true;
        }
        int slot = best;
        if (slot >= 9) { a.moveToHotbar(slot, 7); slot = 7; }
        if (a.selectedSlot() != slot) a.selectHotbar(slot);
        return true;
    }

    // ------------------------------------------------------------------ torches

    /** Light the tunnel every this many steps so mobs don't spawn in it. */
    private static final int TORCH_EVERY = 6;
    private int tunnelSteps;
    private boolean warnedNoTorches;

    /**
     * Put a torch on the floor of the step it just walked into (torches have no hitbox, so
     * standing in the same block is fine). Out of torches: craft some from coal/charcoal and
     * sticks if it has them (it mines coal anyway), else say so once.
     */
    private void placeTorch(AgentHandle a, BlockPos at) {
        if (a.spawnProtected(at.getX(), at.getZ())) return;
        List<ItemStack> inv = a.inventory();
        int slot = -1;
        for (int i = 0; i < inv.size(); i++) if (Gear.id(inv.get(i)).equals("minecraft:torch")) { slot = i; break; }
        if (slot < 0) {
            boolean coal = inv.stream().anyMatch(s -> Gear.id(s).equals("minecraft:coal") || Gear.id(s).equals("minecraft:charcoal"));
            Integer tried = craftTriedAt.get("minecraft:torch");
            if (coal && (tried == null || ticks - tried > CRAFT_RETRY)) {
                craftTriedAt.put("minecraft:torch", ticks);
                if (crafter.queueWithParts(a, "minecraft:torch", true)) { a.log("crafting torches for its tunnel"); return; }
                // Coal but no wood for the sticks: a couple of logs first, then back to the job.
                if (resumeTask == null && currentTask != null && errands < 8) {
                    a.log("has coal for torches but no wood for sticks: getting 2 logs first");
                    errands++;
                    resumeTask = currentTask;
                    errandWant = 2; errandExtra = 0;
                    subJob = true; onTask(a, "mine 2 logs"); subJob = false;
                    return;
                }
            }
            if (!warnedNoTorches) { warnedNoTorches = true; a.log("has no torches for its tunnel (give it torches, or coal + sticks)"); }
            return;
        }
        warnedNoTorches = false;
        int hotbar = slot < 9 ? slot : 6;
        if (slot >= 9) a.moveToHotbar(slot, hotbar);
        a.selectHotbar(hotbar);
        a.lookAt(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        a.placeAgainst(at.getX(), at.getY() - 1, at.getZ(), Direction.UP);
        torches++;
    }

    private int torches;

    // ------------------------------------------------------------------ crafting

    /** Crafting (and smelting etc.) for /agent make and for its own pickaxes. */
    private final MakerModule crafter = new MakerModule();
    /** The pickaxe this job is missing (null = has what it needs). The autopilot reads it. */
    volatile String needsPick;
    /** The job to go back to after fetching pickaxe materials (null = none). */
    private String resumeTask;
    private boolean resumePending, subJob;

    private int errands, resumeStage;
    private int targetSince, minedAtTarget;
    private String currentTask;
    private double anchorX, anchorY, anchorZ;

    private int errandWant, errandExtra;

    /** Materials the current errand is for, counted in the inventory (logs, cobblestone, raw iron, diamonds). */
    private int errandHave(AgentHandle a) {
        int n = 0;
        for (ItemStack s : a.inventory()) {
            String id = Gear.id(s);
            boolean match = switch (targetName) {
                case "logs" -> id.endsWith("_log") && !id.contains("stripped");
                case "stone" -> id.equals("minecraft:cobblestone") || id.equals("minecraft:cobbled_deepslate");
                case "iron_ore" -> id.equals("minecraft:raw_iron") || id.equals("minecraft:iron_ingot");
                case "diamond_ore" -> id.equals("minecraft:diamond");
                default -> false;
            };
            if (match) n += s.getCount();
        }
        return n;
    }

    /** The next errand up the pickaxe ladder from tier `have` toward `need`, or null. */
    private static String nextErrand(AgentHandle a, int have, int need) {
        // Every pickaxe needs 2 sticks: no sticks and no wood -> a couple of logs first.
        int wood = 0;
        for (ItemStack s : a.inventory()) {
            String id = Gear.id(s);
            if (id.equals("minecraft:stick")) wood += s.getCount() / 2;
            else if (id.endsWith("_planks")) wood += s.getCount();
            else if (id.endsWith("_log") && !id.contains("stripped")) wood += 4 * s.getCount();
        }
        if (have == 0) return "mine 4 logs";
        if (wood < 3) return "mine 2 logs";
        return switch (have) {
            case 1 -> need >= 3 ? "mine 11 stone" : "mine 3 stone";   // iron next: 8 more for its furnace
            case 2 -> "mine 3 iron_ore";
            case 3 -> "mine 3 diamond_ore";
            default -> null;
        };
    }

    private static int countId(AgentHandle a, String id) {
        int n = 0;
        for (ItemStack s : a.inventory()) if (Gear.id(s).equals(id)) n += s.getCount();
        return n;
    }

    /** Best pickaxe carried: 0 none, 1 wood/gold, 2 stone/copper, 3 iron, 4 diamond, 5 netherite. */
    private static int bestPickTier(AgentHandle a) {
        int t = 0;
        for (ItemStack s : a.inventory()) {
            String id = Gear.id(s);
            if (!id.endsWith("_pickaxe")) continue;
            t = Math.max(t, id.contains("netherite") ? 5 : id.contains("diamond") ? 4 : id.contains("iron") ? 3 : id.contains("stone") || id.contains("copper") ? 2 : 1);
        }
        return t;
    }
    private final Map<String, Integer> craftTriedAt = new HashMap<>();
    private static final int CRAFT_RETRY = 20 * 120;

    /**
     * Queue the best pickaxe it can make from what it carries that can mine {@code block}
     * (null = any pickaxe). Iron needs 3 ingots, stone 3 cobblestone (or cobbled deepslate /
     * blackstone), wood 3 planks; sticks and planks are made on the way. False if none.
     */
    /** The weakest pickaxe that gets drops from this block (null = any tool or a hand will do). Debris: diamond; diamonds: iron; iron: stone; stone: wood. */
    public static String requiredPickaxe(BlockState s) {
        if (!s.requiresCorrectToolForDrops()) return null;
        for (String p : new String[]{"minecraft:wooden_pickaxe", "minecraft:stone_pickaxe", "minecraft:iron_pickaxe", "minecraft:diamond_pickaxe", "minecraft:netherite_pickaxe"}) {
            if (new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(p))).isCorrectToolForDrops(s)) return p;
        }
        return null;
    }

    /** Something in the inventory gets drops from this block. */
    private static boolean canHarvest(AgentHandle a, BlockState s) {
        if (!s.requiresCorrectToolForDrops()) return true;
        for (ItemStack it : a.inventory()) if (!it.isEmpty() && it.isCorrectToolForDrops(s)) return true;
        return false;
    }

    /** Logs (4 planks each) plus planks carried. */
    /**
     * Enough fuel to smelt n items: coal/charcoal (8 each), or wood at 1.5 items per plank or log,
     * keeping one plank's worth for the pickaxe's sticks if it has none.
     */
    private static boolean hasSmeltFuel(AgentHandle a, int n) {
        int coal = countId(a, "minecraft:coal") + countId(a, "minecraft:charcoal");
        if (coal * 8 >= n) return true;
        int planks = 0, logs = 0;
        for (ItemStack s : a.inventory()) {
            String id = Gear.id(s);
            if (id.endsWith("_planks")) planks += s.getCount();
            else if (id.endsWith("_log") && !id.contains("stripped")) logs += s.getCount();
        }
        int reserve = countId(a, "minecraft:stick") >= 2 || planks + logs == 0 ? 0 : 1; // a plank (or a log) for sticks
        int left = n - coal * 8;                     // items the wood has to smelt
        int woodNeeded = (left * 2 + 2) / 3;         // ceil(left / 1.5)
        return planks + logs - reserve >= woodNeeded;
    }

    private static int woodAsPlanks(AgentHandle a) {
        int n = 0;
        for (ItemStack s : a.inventory()) {
            if (s.isEmpty()) continue;
            String id = Gear.id(s);
            if (id.endsWith("_planks")) n += s.getCount();
            else if (id.endsWith("_log") && !id.contains("stripped")) n += 4 * s.getCount();
        }
        return n;
    }

    /** Logs: how far above the ground under its trunk (0 = the bottom log). Others: 0. */
    private static int heightAboveGround(BlockSource w, BlockPos p) {
        for (int dy = 1; dy <= 24; dy++) {
            BlockState s = w.get(p.getX(), p.getY() - dy, p.getZ());
            if (s == null) return 0;
            String n = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
            if (n.endsWith("_log") || n.endsWith("_leaves") || s.isAir() || !s.getFluidState().isEmpty()) continue;
            return dy - 1;
        }
        return 24;
    }

    private boolean craftPickaxe(AgentHandle a, BlockState block) {
        String[][] options = {
                {"minecraft:diamond_pickaxe", "minecraft:diamond"},
                {"minecraft:iron_pickaxe", "minecraft:iron_ingot"},
                {"minecraft:stone_pickaxe", "minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:blackstone"},
                {"minecraft:wooden_pickaxe", "_planks"}};
        int carried = bestPickTier(a);
        for (String[] o : options) {
            ItemStack tool = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(o[0])));
            if (block != null && !tool.isCorrectToolForDrops(block)) continue;
            int tier = o[0].contains("diamond") ? 4 : o[0].contains("iron") ? 3 : o[0].contains("stone") ? 2 : 1;
            if (tier <= carried) break; // never a pickaxe as good as (or worse than) the one it has
            int have = 0;
            for (ItemStack s : a.inventory()) {
                String id = Gear.id(s);
                for (int i = 1; i < o.length; i++) if (!s.isEmpty() && (id.equals(o[i]) || (o[i].startsWith("_") && id.endsWith(o[i])))) have += s.getCount();
                if (!s.isEmpty() && o[0].contains("wooden") && id.endsWith("_log") && !id.contains("stripped")) have += 4 * s.getCount(); // a log is 4 planks
            }
            // Wood: 3 for the head + 2 for sticks.
            if (have < (o[0].contains("wooden") ? 5 : 3)) continue;
            Integer at = craftTriedAt.get(o[0]);
            if (at != null && ticks - at < CRAFT_RETRY) continue; // tried lately and it didn't work
            craftTriedAt.put(o[0], ticks);
            if (!crafter.queueTool(a, o[0])) continue;
            a.log("crafting itself a " + o[0].replace("minecraft:", "") + (block == null ? "" : " for " + name(block)));
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ to the nether

    private boolean netherOnly() {
        if (targets == null || targets.isEmpty()) return false;
        for (Block b : targets) {
            String id = BuiltInRegistries.BLOCK.getKey(b).getPath();
            if (!(id.equals("ancient_debris") || id.startsWith("nether_") || id.equals("glowstone") || id.equals("netherrack") || id.equals("soul_sand") || id.equals("blackstone") || id.equals("basalt") || id.equals("magma_block"))) return false;
        }
        return true;
    }

    /** Into the crew's lit portal, and stand there until the server moves it (4 s for a player in survival). */
    private void portal(AgentHandle a) {
        if (a.dimension() != null && a.dimension().endsWith("the_nether")) { a.log("§ain the nether: " + currentTask); wanderTries = 0; go(State.SCAN); return; }
        BlockPos in = PortalWork.inside();
        if (in == null) { status = "waiting for the crew's nether portal to be lit"; pause(20 * 20, State.PORTAL); return; }
        boolean there = (int) Math.floor(a.x()) == in.getX() && (int) Math.floor(a.z()) == in.getZ() && Math.abs(a.y() - in.getY()) < 1.2;
        if (!there) {
            status = "walking to the nether portal";
            if (timer == 1) a.log("going through the nether portal at " + in.toShortString() + " for " + targetName);
            if (!a.isNavigating() && timer % 20 == 1) a.navigate(in.getX(), in.getY(), in.getZ(), 0);
            if (timer > 20 * 120) { a.log("§ecouldn't get into the nether portal: trying again"); timer = 0; }
            return;
        }
        if (a.isNavigating()) a.stopNavigation();
        status = "standing in the nether portal";
        if (timer > 20 * 40) { a.log("§eit stood in the portal 40 s and nothing happened: is it lit?"); timer = 0; }
    }

    // ------------------------------------------------------------------ making obsidian

    // Natural obsidian nearly always touches lava (it is where water met lava), and nothing next to lava is ever dug.
    // So: a lava pool's surface is turned to obsidian with a water bucket (poured from solid ground at its edge,
    // never standing next to lava), the water picked back up, and the obsidian mined from there. No water bucket:
    // an empty one is filled at the nearest water; no bucket: one is made from 3 iron (mined and smelted if need be).

    /** Water it couldn't get to: not picked again (the nearest one was retried every minute for good). */
    private final java.util.Set<BlockPos> badWater = new java.util.HashSet<>();
    private long badWaterAt;
    private int castStage, castWait;
    private BlockPos castLava, castEdge, castStand, castWater;
    private final java.util.Set<BlockPos> castTried = new java.util.HashSet<>();

    /** Obsidian that can be mined: no lava or water beside or above it, and something solid under it (the drop falls there). */
    private static boolean obsidianOk(BlockSource w, BlockPos p) {
        for (Direction d : Direction.values()) {
            if (d == Direction.DOWN) continue;
            BlockState s = w.get(p.getX() + d.getStepX(), p.getY() + d.getStepY(), p.getZ() + d.getStepZ());
            if (s == null || !s.getFluidState().isEmpty()) return false;
        }
        BlockState under = w.get(p.getX(), p.getY() - 1, p.getZ());
        return under != null && under.getFluidState().isEmpty() && solidFloor(under);
    }

    private static boolean lavaSource(BlockState s) { return s != null && s.getFluidState().is(net.minecraft.tags.FluidTags.LAVA) && s.getFluidState().isSource(); }
    private static boolean waterSource(BlockState s) { return s != null && s.getFluidState().is(net.minecraft.tags.FluidTags.WATER) && s.getFluidState().isSource(); }

    /** Lava (or fire) in the block or any of its 6 neighbours. */
    private static boolean nearLava(BlockSource w, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 1) continue;
            BlockState s = w.get(x + dx, y + dy, z + dz);
            if (s == null || s.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) return true;
        }
        return false;
    }

    /** Somewhere to stand within reach of (x, y, z): feet and head free, solid floor, no lava touching any of it. */
    private BlockPos standNear(AgentHandle a, BlockSource w, int x, int y, int z, BlockPos not) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = -1; dy <= 1; dy++) {
            int sx = x + dx, sy = y + dy, sz = z + dz;
            if (not != null && not.getX() == sx && not.getY() == sy && not.getZ() == sz) continue;
            BlockState feet = w.get(sx, sy, sz), head = w.get(sx, sy + 1, sz), floor = w.get(sx, sy - 1, sz);
            if (feet == null || head == null || floor == null) continue;
            if (!feet.isAir() || !head.isAir() || !solidFloor(floor)) continue;
            if (nearLava(w, sx, sy, sz) || nearLava(w, sx, sy - 1, sz) || nearLava(w, sx, sy + 1, sz)) continue;
            double ex = x + 0.5 - (sx + 0.5), ey = y + 0.5 - (sy + 1.62), ez = z + 0.5 - (sz + 0.5);
            if (ex * ex + ey * ey + ez * ez > 4.0 * 4.0 || Math.abs(dx) + Math.abs(dz) == 0) continue;
            double d = Math.abs(sx + 0.5 - a.x()) + Math.abs(sz + 0.5 - a.z()) + Math.abs(sy - a.y()) * 2;
            if (d < bestD) { bestD = d; best = new BlockPos(sx, sy, sz); }
        }
        return best;
    }

    private int slotOf(AgentHandle a, String id) {
        List<ItemStack> inv = a.inventory();
        for (int i = 0; i < inv.size(); i++) if (Gear.id(inv.get(i)).equals(id)) return i;
        return -1;
    }

    private void holdSlot(AgentHandle a, int i) {
        if (i >= 9) { a.moveToHotbar(i, 8); i = 8; }
        a.selectHotbar(i);
    }

    private boolean at(AgentHandle a, BlockPos s) {
        return Math.abs(a.x() - (s.getX() + 0.5)) < 0.6 && Math.abs(a.z() - (s.getZ() + 0.5)) < 0.6 && Math.abs(a.y() - s.getY()) < 0.6;
    }

    private void cast(AgentHandle a) {
        BlockSource w = a.openWorld();
        if (!(w instanceof BlockView view)) { pause(40, State.CAST); return; }
        int fx = (int) Math.floor(a.x()), fz = (int) Math.floor(a.z());
        if (timer > 20 * 120) { a.log("§emaking obsidian took too long: looking again"); castTried.add(castLava == null ? BlockPos.ZERO : castLava); castStage = 0; go(State.SCAN); return; }
        boolean water = slotOf(a, "minecraft:water_bucket") >= 0, bucket = slotOf(a, "minecraft:bucket") >= 0;
        switch (castStage) {
            case 0 -> { // a water bucket first
                if (water) { castStage = 2; timer = 0; return; }
                if (bucket) {
                    List<BlockPos> wat = new java.util.ArrayList<>(view.findAll(fx, fz, 96, MinerModule::waterSource, 400, null));
                    // Near its own height first: a deep aquifer right below it was "the nearest" and never reachable.
                    final double ax = a.x(), ay = a.y(), az = a.z();
                    wat.sort(java.util.Comparator.comparingDouble(p -> (p.getX() - ax) * (p.getX() - ax) + (p.getZ() - az) * (p.getZ() - az) + 9 * (p.getY() - ay) * (p.getY() - ay)));
                    if (wat.size() > 60) wat = new java.util.ArrayList<>(wat.subList(0, 60));
                    BlockPos stand = null, src = null;
                    if (System.currentTimeMillis() - badWaterAt > 300_000) { badWater.clear(); badWaterAt = System.currentTimeMillis(); }
                    // Water within reach where it stands (an aquifer beside its tunnel): filled from right here, no walking.
                    for (BlockPos p : wat) {
                        if (p.distToCenterSqr(a.x(), a.y() + 1.62, a.z()) > 4.0 * 4.0) continue;
                        if (!Sight.clear(w, a.x(), a.y() + 1.62, a.z(), p.getX() + 0.5, p.getY() + 0.9, p.getZ() + 0.5)) continue;
                        src = p; stand = new BlockPos((int) Math.floor(a.x()), feetY(a), (int) Math.floor(a.z())); break;
                    }
                    if (src == null) for (BlockPos p : wat) { if (badWater.contains(p)) continue; BlockPos s = standNear(a, w, p.getX(), p.getY() + 1, p.getZ(), null); if (s != null) { stand = s; src = p; break; } }
                    if (src == null) { status = "no water in reach to fill its bucket"; warn(a, "making obsidian: no water near to fill a bucket"); pause(20 * 15, State.CAST); return; }
                    castWater = src; castStand = stand; castStage = 1; timer = 0;
                    a.log("making obsidian: filling its bucket at " + src.toShortString());
                    a.navigate(stand.getX(), stand.getY(), stand.getZ(), 0);
                    return;
                }
                // No bucket: three iron ingots (from its raw iron, or mined).
                if (countId(a, "minecraft:iron_ingot") >= 3) { a.log("making obsidian: crafting a bucket"); crafter.onTask(a, "make bucket 1"); return; }
                if (countId(a, "minecraft:raw_iron") >= 3) { a.log("making obsidian: smelting iron for a bucket"); crafter.onTask(a, "make iron_ingot 3"); return; }
                if (errands < 8) {
                    errands++;
                    a.log("§emaking obsidian needs a bucket: mining 3 iron for one first");
                    resumeTask = currentTask; resumeSample = null; // not a pickaxe errand: "can mine iron" must not end it at once (that looped)
                    errandWant = 3; errandExtra = 0;
                    subJob = true; onTask(a, "mine 3 iron_ore"); subJob = false;
                    return;
                }
                status = "no bucket for making obsidian"; pause(20 * 30, State.CAST);
            }
            case 1 -> { // walking to the water, then filling
                status = "making obsidian: filling its bucket";
                if (water) { castStage = 2; timer = 0; return; }
                if (!at(a, castStand)) { if (!a.isNavigating() && timer % 20 == 0) a.navigate(castStand.getX(), castStand.getY(), castStand.getZ(), 0); if (timer > 20 * 60) { badWater.add(castWater); a.log("§emaking obsidian: can't get to the water at " + castWater.toShortString() + ": another one"); castStage = 0; timer = 0; } return; }
                if (a.isNavigating()) a.stopNavigation();
                int b = slotOf(a, "minecraft:bucket");
                if (b < 0) { castStage = 0; return; }
                if (timer % 10 == 1) { holdSlot(a, b); a.lookAt(castWater.getX() + 0.5, castWater.getY() + 0.7, castWater.getZ() + 0.5); }
                else if (timer % 10 == 3) a.useItem();
                if (timer > 20 * 70) { castStage = 0; timer = 0; }
            }
            case 2 -> { // a lava pool's edge: lava with air over it, solid ground beside it with air over that, a safe place to stand
                status = "making obsidian: looking for lava";
                List<BlockPos> lava = view.findAll(fx, fz, 56, MinerModule::lavaSource, 300, (x, z) -> a.spawnProtected(x, z));
                BlockPos bestL = null, bestG = null, bestS = null;
                double bestD = Double.MAX_VALUE;
                for (BlockPos l : lava) {
                    if (castTried.contains(l)) continue;
                    BlockState over = w.get(l.getX(), l.getY() + 1, l.getZ());
                    if (over == null || !over.isAir()) continue;
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        BlockPos g = l.relative(d);
                        BlockState gs = w.get(g.getX(), g.getY(), g.getZ()), ga = w.get(g.getX(), g.getY() + 1, g.getZ());
                        if (gs == null || ga == null || !gs.getFluidState().isEmpty() || !solidFloor(gs) || !ga.isAir()) continue;
                        BlockPos wpos = g.above();
                        BlockPos s = standNear(a, w, g.getX(), g.getY() + 1, g.getZ(), wpos);
                        if (s == null) continue;
                        double dd = Math.abs(s.getX() - a.x()) + Math.abs(s.getZ() - a.z()) + Math.abs(s.getY() - a.y()) * 3;
                        if (dd < bestD) { bestD = dd; bestL = l; bestG = g; bestS = s; }
                    }
                }
                if (bestL == null) {
                    if (wanderTries < 8) {
                        double ang = Math.toRadians(wanderDir + wanderTries * 100);
                        wanderTries++;
                        status = "making obsidian: no lava pool in sight, looking further out (" + wanderTries + "/8)";
                        if (wanderTries == 1) a.log("making obsidian: no lava pool in sight, going to look for one");
                        a.navigate(fx + (int) Math.round(Math.cos(ang) * 48), feetY(a), fz + (int) Math.round(Math.sin(ang) * 48), 12);
                        pause(20 * 25, State.CAST);
                        return;
                    }
                    wanderTries = 0;
                    warn(a, "making obsidian: no lava pool it can safely get to nearby");
                    pause(20 * 20, State.CAST);
                    return;
                }
                wanderTries = 0;
                castLava = bestL; castEdge = bestG; castStand = bestS; castWater = bestG.above();
                castStage = 3; timer = 0;
                a.log("making obsidian: pouring water onto the lava at " + bestL.toShortString() + " from " + bestS.toShortString());
                a.navigate(bestS.getX(), bestS.getY(), bestS.getZ(), 0);
            }
            case 3 -> { // there: pour
                status = "making obsidian: pouring water on the lava";
                if (!water) { castStage = 0; return; }
                if (!at(a, castStand)) { if (!a.isNavigating() && timer % 20 == 0) a.navigate(castStand.getX(), castStand.getY(), castStand.getZ(), 0); if (timer > 20 * 60) { castTried.add(castLava); castStage = 2; timer = 0; } return; }
                if (a.isNavigating()) a.stopNavigation();
                int b = slotOf(a, "minecraft:water_bucket");
                if (timer % 10 == 1) { holdSlot(a, b); a.lookAt(castEdge.getX() + 0.5, castEdge.getY() + 1.0, castEdge.getZ() + 0.5); }
                else if (timer % 10 == 3) a.useItem();
                BlockState placed = w.get(castWater.getX(), castWater.getY(), castWater.getZ());
                if (waterSource(placed) || slotOf(a, "minecraft:water_bucket") < 0) { castStage = 4; castWait = 0; timer = 0; }
                if (timer > 20 * 6) { castTried.add(castLava); castStage = 2; timer = 0; }
            }
            case 4 -> { // the water spreads over the pool; then it goes back in the bucket
                status = "making obsidian: letting the water run over the lava";
                if (++castWait < 40) return;
                int b = slotOf(a, "minecraft:bucket");
                if (b < 0) { castStage = 5; timer = 0; return; }
                if (timer % 10 == 1) { holdSlot(a, b); a.lookAt(castWater.getX() + 0.5, castWater.getY() + 0.6, castWater.getZ() + 0.5); }
                else if (timer % 10 == 3) a.useItem();
                if (slotOf(a, "minecraft:water_bucket") >= 0 || timer > 20 * 5) { castStage = 5; timer = 0; }
            }
            case 5 -> { // mine what turned into obsidian
                castTried.add(castLava);
                BlockState now = w.get(castLava.getX(), castLava.getY(), castLava.getZ());
                a.log("making obsidian: the lava at " + castLava.toShortString() + " is " + (now == null ? "?" : now.getBlock().getName().getString()) + " now");
                castStage = 0;
                go(State.SCAN);
            }
            default -> castStage = 0;
        }
    }

    // ------------------------------------------------------------------ helpers

    private static int feetY(AgentHandle a) {
        return (int) Math.floor(a.y() + 0.01);
    }

    private void skip(AgentHandle a, String why) {
        if (target != null) skipped.put(target, ticks);
        if (why != null) {
            skipTicks.addLast(ticks);
            while (!skipTicks.isEmpty() && ticks - skipTicks.peekFirst() > 1200) skipTicks.pollFirst();
            if (skipTicks.size() >= 8) {
                skipTicks.clear();
                areaBad = BlockPos.containing(a.x(), a.y(), a.z()); areaBadUntil = ticks + 3600;
                a.log("§eeight " + targetName + " given up in a minute: nothing within 48 blocks of here for three minutes");
            }
        }
        if (why != null && ++skipRun >= 6) {
            skipRun = 0; badSpot = target != null ? target : BlockPos.containing(a.x(), a.y(), a.z()); badUntil = ticks + 20 * 120;
            a.log("§esix " + targetName + " in a row out of reach here: looking somewhere else");
        }
        if (why != null) {
            String line = "skipping " + targetName + " at " + target.getX() + " " + target.getY() + " " + target.getZ() + ": " + why;
            // Chat gets at most one of these every 30 s (a cave full of unreachable ore would flood it); the log gets all.
            long now = System.currentTimeMillis();
            if (now - lastSkipLog > 30_000) { lastSkipLog = now; a.log(line); }
            else dev.agentnet.AgentNet.LOG.info("[{}] {}", a.name(), line);
        }
        toolWaits = 0;
        toDig.clear();
        if (a.isNavigating()) a.stopNavigation();
        go(State.SCAN);
    }

    private boolean isSkipped(BlockPos p) {
        Integer at = skipped.get(p);
        if (at == null) return false;
        if (ticks - at > SKIP_FOR) { skipped.remove(p); return false; }
        return true;
    }

    private static String name(BlockState s) { return BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath(); }

    private void go(State s) {
        state = s;
        timer = 0;
    }

    private void pause(int t, State then) {
        wait = t;
        afterWait = then;
        state = State.WAIT;
        timer = 0;
    }

    private void warn(AgentHandle a, String message) {
        if (message.equals(lastWarning)) return;
        lastWarning = message;
        a.log(message);
    }

    String stats() { return mined + " " + (targetName == null ? "blocks" : targetName) + " mined"; }
}
