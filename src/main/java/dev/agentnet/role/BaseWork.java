package dev.agentnet.role;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import dev.agentnet.bus.AgentBus;
import net.minecraft.core.BlockPos;

/**
 * Getting the crew a base: builders and distributors call {@link #ensure} every few seconds.
 * No base in this dimension and a builder online: plan one near the caller and give it to every
 * builder there. While it goes up: watch its progress (from an agent close enough to see it);
 * done: register its chests as storage. No progress for 5 minutes (unreachable spot...): drop
 * that spot and plan another.
 */
final class BaseWork {
    private BaseWork() {}

    /** Blocks the crew's base still needs (last check), -1 = unknown: the autopilot hires builders by it. */
    public static volatile int blocksLeft = -1;
    static final Set<Long> BAD = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static long plannedAt, progressAt, checkedAt, warnedAt;
    private static int bestStanding, scoutTries;

    static void ensure(AgentHandle a) {
        long now = System.currentTimeMillis();
        Bases.Base b = Bases.get(a.server(), a.dimension());
        if (b != null && abandoned(b)) b = null; // left behind somewhere else: the crew needs one here
        if (b != null) { check(a, b, now); return; }
        if (now - plannedAt < 60_000) return;
        plannedAt = now;
        List<AgentHandle> builders = builders(a);
        if (builders.isEmpty()) {
            if (now - warnedAt > 5 * 60_000) { warnedAt = now; a.log("§ethe crew has no base yet: it needs a builder to put one up (/agent spawn 1 builder)"); }
            return;
        }
        AgentHandle scout = builders.get(0);
        Bases.Base nb = BasePlanner.plan(scout, BAD); // where a builder is: it sees the ground there
        if (nb == null) {
            // Nothing here (hills, trees, water): the builder looks somewhere else, a new direction each time,
            // and the next try comes sooner.
            int dir = scoutTries++ % 8;
            double ang = dir * Math.PI / 4;
            int tx = (int) Math.floor(scout.x() + Math.cos(ang) * 40), tz = (int) Math.floor(scout.z() + Math.sin(ang) * 40);
            StringBuilder why = new StringBuilder();
            for (int i = 0; i < BasePlanner.REJECTS.length; i++) if (BasePlanner.REJECTS[i] > 0) why.append(why.length() == 0 ? "" : ", ").append(BasePlanner.REJECT_NAMES[i]).append(" ").append(BasePlanner.REJECTS[i]);
            a.log("§eno spot for a base around " + (int) scout.x() + " " + (int) scout.z() + " (needs dry ground with at most 4 blocks of slope; trees are fine) [turned down: " + why + "]: "
                    + scout.name() + " looks around " + tx + " " + tz);
            scout.navigate(tx, (int) Math.floor(scout.y()), tz, 6);
            plannedAt = now - 30_000;
            return;
        }
        scoutTries = 0;
        Bases.put(nb);
        progressAt = now;
        bestStanding = 0;
        assign(builders, nb);
        a.log("§aplanned the crew's base at " + nb.origin().toShortString() + ": " + builders.size() + " builder(s) are putting it up");
    }

    // ------------------------------------------------------------------ getting the materials

    /** Raw materials the base is still short of (cobblestone, logs, coal -> how many): miners' jobs. */
    private static final java.util.Map<String, Integer> short_ = new java.util.concurrent.ConcurrentHashMap<>();
    private static long suppliedAt, supplyWarnedAt;

    /** What the base still needs that nobody has, as miner tasks ("mine stone 64"), for the civ autopilot. */
    /** One entry per miner wanted: a big base needs several stone miners at once to go up in minutes. */
    public static List<String> needs() {
        List<String> out = new ArrayList<>();
        short_.forEach((block, n) -> { for (int i = 0; i < minersWanted(block, n); i++) out.add("mine " + block + " " + Math.min(batch(block), n)); });
        return out;
    }

    /** How many miners at once for this much of it: stone up to 4 (one per 120), logs up to 2 (40 each), the rest 1. */
    static int minersWanted(String block, int n) {
        return block.equals("stone") ? Math.max(1, Math.min(4, (n + 119) / 120)) : block.equals("logs") ? Math.max(1, Math.min(2, (n + 39) / 40)) : 1;
    }

    /** One miner's batch: a stack, delivered as soon as it's mined (big batches sit in pockets while the builder waits). */
    static int batch(String block) { return 64; }

    /**
     * The base's bill, worked back to raw materials: cobblestone (walls, 8 per furnace), wood
     * (chests 8 planks, the table 4, a door 2, as logs at 4 planks each), coal (a torch each 4).
     * Minus what the crew carries; whatever is short goes to a miner (an idle one, or an agent with
     * no job), so the distributor has something to fetch, craft and deliver.
     */
    private static void supply(AgentHandle a, Bases.Base b, long now) {
        if (now - suppliedAt < 30_000) return;
        suppliedAt = now;
        java.util.Map<String, Integer> miss = BasePlanner.missing(a, b);
        if (miss == null) return;
        int chests = miss.getOrDefault("minecraft:chest", 0), furnaces = miss.getOrDefault("minecraft:furnace", 0);
        // 15% over what the plan needs (the crew aims for an oversupply of everything: losses, repairs, spare tools).
        int cobble = dev.agentnet.Civ.over(miss.getOrDefault("minecraft:cobblestone", 0) + 8 * furnaces);
        int planks = dev.agentnet.Civ.over(8 * chests + 4 * miss.getOrDefault("minecraft:crafting_table", 0) + 2 * miss.getOrDefault("minecraft:oak_door", 0));
        int coal = dev.agentnet.Civ.over((miss.getOrDefault("minecraft:torch", 0) + 3) / 4);
        // What the crew already has (whoever is around the base): finished items count as their materials.
        int haveCobble = 0, havePlanks = 0, haveCoal = 0;
        for (AgentHandle h : AgentBus.agents.get()) {
            if (!h.isOnline() || !dev.agentnet.role.AgentHandle.sameDim(h.dimension(), b.dimension()) || b.origin().distToCenterSqr(h.x(), h.y(), h.z()) > 160 * 160) continue;
            // Only what will really reach the builder: the builder's and the distributors' loads in full, from everyone
            // else just what they can spare. (Miners keep a kit of cobblestone, wood and coal that never gets delivered:
            // counted in full, 5 miners' kits hid a 130-block shortage and no miner was sent for it.)
            boolean all = h.role() == Role.BUILDER || h.role() == Role.DISTRIBUTOR;
            java.util.Set<String> counted = new java.util.HashSet<>();
            for (var s : h.inventory()) {
                if (s.isEmpty()) continue;
                String id = Gear.id(s);
                int n = s.getCount();
                if (!all) {
                    if (!counted.add(id)) continue; // one total per kind
                    n = Math.max(0, Spare.spare(h, id));
                    if (n == 0) continue;
                }
                if (id.equals("minecraft:cobblestone") || id.equals("minecraft:cobbled_deepslate")) haveCobble += n;
                else if (id.equals("minecraft:furnace")) haveCobble += 8 * n;
                else if (id.endsWith("_planks")) havePlanks += n;
                else if (id.endsWith("_log") && !id.contains("stripped")) havePlanks += 4 * n;
                else if (id.equals("minecraft:chest")) havePlanks += 8 * n;
                else if (id.equals("minecraft:coal") || id.equals("minecraft:charcoal")) haveCoal += n;
                else if (id.equals("minecraft:torch")) haveCoal += (n + 3) / 4;
            }
        }
        short_.clear();
        if (cobble > haveCobble) short_.put("stone", cobble - haveCobble);
        if (planks > havePlanks) short_.put("logs", (planks - havePlanks + 3) / 4);
        if (coal > haveCoal) short_.put("coal_ore", coal - haveCoal);
        if (short_.isEmpty()) return;
        List<String> unassigned = new ArrayList<>();
        for (var e : short_.entrySet()) {
            String block = e.getKey();
            int n = Math.min(batch(block), e.getValue() + (block.equals("stone") ? 16 : 2)); // a little extra: miners keep a few for their tools
            String task = "mine " + n + " " + block;
            int onIt = 0;
            for (AgentHandle h : AgentBus.agents.get()) {
                if (h.isOnline() && h.role() == Role.MINER && h.task().contains(block) && dev.agentnet.role.AgentHandle.sameDim(h.dimension(), b.dimension()) && !minerDone(h)) onIt++;
            }
            if (onIt >= minersWanted(block, e.getValue())) continue;
            AgentHandle free = null;
            double best = Double.MAX_VALUE;
            for (AgentHandle h : AgentBus.agents.get()) {
                if (!h.isOnline() || !dev.agentnet.role.AgentHandle.sameDim(h.dimension(), b.dimension())) continue;
                boolean freeMiner = h.role() == Role.MINER && ("idle".equals(h.task()) || minerDone(h));
                if (!freeMiner && !(h.role() == null && "idle".equals(h.task()))) continue; // only idle miners and agents without a job
                double d = b.origin().distToCenterSqr(h.x(), h.y(), h.z());
                if (d < best && d < 160 * 160) { best = d; free = h; }
            }
            if (free != null) {
                free.assignTask(task);
                a.log("§athe base needs " + e.getValue() + " more " + (block.equals("stone") ? "cobblestone" : block.equals("logs") ? "logs" : "coal")
                        + ": " + free.name() + " is getting it");
            } else unassigned.add(e.getValue() + " " + (block.equals("stone") ? "cobblestone" : block.equals("logs") ? "logs" : "coal"));
        }
        if (!unassigned.isEmpty() && now - supplyWarnedAt > 5 * 60_000) {
            supplyWarnedAt = now;
            a.log("§ethe base still needs " + String.join(", ", unassigned) + " and no miner is free to get it (/agent spawn 1 miner, or the civ autopilot adds one)");
        }
    }

    /** A miner whose job is over (done, or nothing of its kind around). */
    private static boolean minerDone(AgentHandle h) {
        String s = h.moduleStatus();
        if (h.task() != null && h.task().contains("useful")) return true; // bored: free for any real request
        return s != null && (s.startsWith("done") || s.startsWith("waiting for /agent mine") || s.startsWith("no ") && s.contains("found nearby"));
    }

    /** No agent of the crew anywhere near it: a base left behind somewhere else, the crew needs one where it is now. */
    static boolean abandoned(Bases.Base b) {
        double r = (double) Bases.RANGE * Bases.RANGE;
        for (AgentHandle h : AgentBus.agents.get()) {
            if (h.isOnline() && dev.agentnet.role.AgentHandle.sameDim(h.dimension(), b.dimension()) && b.origin().distToCenterSqr(h.x(), b.origin().getY(), h.z()) <= r) return false;
        }
        return true;
    }

    private static List<AgentHandle> builders(AgentHandle a) {
        List<AgentHandle> out = new ArrayList<>();
        for (AgentHandle h : AgentBus.agents.get()) if (h.isOnline() && h.role() == Role.BUILDER && dev.agentnet.role.AgentHandle.sameDim(h.dimension(), a.dimension())) out.add(h);
        return out;
    }

    private static String task(Bases.Base b) { return "build " + b.file() + " " + b.origin().getX() + " " + b.origin().getY() + " " + b.origin().getZ(); }

    private static void assign(List<AgentHandle> builders, Bases.Base b) {
        for (AgentHandle h : builders) if (!task(b).equals(h.task())) h.assignTask(task(b));
    }

    private static long chestsCheckedAt;

    private static void check(AgentHandle a, Bases.Base b, long now) {
        if (b.built() && now - chestsCheckedAt > 60_000 && b.origin().distToCenterSqr(a.x(), a.y(), a.z()) < 96 * 96) {
            chestsCheckedAt = now;
            if (BasePlanner.moreChests(b)) return;
            // Damaged since it was finished (floor dug out from under it, a creeper): back to the builders to repair.
            int[] pr = BasePlanner.progress(a, b);
            if (pr[1] > 1 && pr[0] < pr[1] * 0.97) { Bases.put(b.withBuilt(false)); a.log("§ethe base has " + (pr[1] - pr[0]) + " blocks missing: the builders repair it"); return; }
        }
        if (b.built() || now - checkedAt < 5_000) return;
        if (b.origin().distToCenterSqr(a.x(), a.y(), a.z()) > 96 * 96) return; // too far to see it
        checkedAt = now;
        // Builders that wandered off to something else (or rejoined) get the job back.
        assign(builders(a), b);
        BasePlanner.flatten(b);
        int[] pr = BasePlanner.progress(a, b);
        blocksLeft = Math.max(0, pr[1] - pr[0]);
        if (pr[0] > bestStanding) { bestStanding = pr[0]; progressAt = now; }
        boolean chestsUp = true;
        var w = a.openWorld();
        for (BlockPos c : b.chests()) {
            var st = w == null ? null : w.get(c.getX(), c.getY(), c.getZ());
            if (st == null || st.getBlock() != net.minecraft.world.level.block.Blocks.CHEST) { chestsUp = false; break; }
        }
        if (chestsUp && pr[0] >= pr[1] * 0.99) { // the floor too: a few missing floor blocks are holes agents fall into
            Bases.put(b.withBuilt(true));
            for (BlockPos c : b.chests()) ChestMemory.remember(a.server(), a.dimension(), c.getX(), c.getY(), c.getZ(), "base");
            a.log("§athe base is finished (" + pr[0] + "/" + pr[1] + " blocks): " + b.chests().size() + " storage chests, "
                    + b.furnaces().size() + " furnaces, hub at " + b.hub().toShortString());
            return;
        }
        supply(a, b, now);
        // Waiting on materials isn't "stuck": the 5-minute replanning only counts while the crew has what's needed.
        if (!short_.isEmpty()) progressAt = Math.max(progressAt, now - 60_000);
        // Only a site that never got going is given up (under 10% standing after 10 minutes): a base that is partly
        // up is never abandoned, however slowly it grows. It once dropped a base that was nearly finished.
        if (now - progressAt > 10 * 60_000 && pr[0] < pr[1] * 0.10) {
            a.log("§eno progress on the base at " + b.origin().toShortString() + " for 5 minutes (" + pr[0] + "/" + pr[1] + "): planning it somewhere else");
            BAD.add(BlockPos.asLong(b.origin().getX(), 0, b.origin().getZ()));
            Bases.remove(a.server(), a.dimension());
            plannedAt = 0;
        }
    }
}
