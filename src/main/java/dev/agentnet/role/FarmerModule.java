package dev.agentnet.role;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import dev.agentnet.map.Farms;
import dev.agentnet.pathing.BlockSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Farmer: works one farm area (/agent farm add ... crops|trees).
 *
 * <p><b>Crops</b> (wheat, carrots, potatoes, beetroot): harvest fully grown crops by
 * standing on them (so the drops get picked up), replant the same crop, plant any
 * empty farmland with whatever seeds it carries.
 *
 * <p><b>Trees</b>: learns tree spots from the saplings/trunks in the area (plant the
 * first saplings yourself), chops grown trunks bottom-up with its best axe (break time
 * like a real player), and replants the sapling.
 *
 * <p>Cheap by design: the farm's raw chunks are kept, but blocks are only decoded during
 * a scan every few seconds and thrown away right after. Inventory alerts come from
 * {@link GathererModule}.
 */
final class FarmerModule extends GathererModule {
    private static final int SCAN_EVERY = 100;      // ticks between scans while idle (5 s)
    private static final int MAX_TRUNK = 6;         // logs reachable from the ground next to a tree
    private static final int SKIP_FOR = 20 * 60;    // a target we couldn't reach is skipped for a minute

    private enum Kind { HARVEST, PLANT, TILL, CHOP, COLLECT, PLANT_TREE, GRASS, LEVEL }
    /**
     * Stand this far (blocks) from a trunk while chopping. Short oaks have leaves at head
     * height on every block within 2 of the trunk, so closer spots often don't exist.
     */
    private static final int CHOP_DISTANCE = 3;
    /** Server reach for breaking/using blocks, measured from the eyes. */
    private static final double REACH = 4.5;
    private record Job(Kind kind, BlockPos pos, Item item) {}
    private enum State { CLAIM, GO_TO_FARM, SCAN, IDLE, NEXT_JOB, WALK, ACT, WAIT }

    private State state = State.CLAIM;
    private Farms.Farm farm;
    private final Deque<Job> jobs = new ArrayDeque<>();
    private Job job;
    private int timer, wait;
    private State afterWait;
    private int trunkY;                             // next log to chop
    private final Map<BlockPos, Item> treeSpots = new HashMap<>();   // sapling position -> sapling item
    private final Map<BlockPos, Integer> skipped = new HashMap<>();  // unreachable -> tick it was skipped
    private int ticks;
    private String lastWarning;
    private int harvested, planted, chopped;

    @Override
    public void onStart(AgentHandle a) {
        a.keepWorld(true);
        state = State.CLAIM;
    }

    @Override
    public void onStop(AgentHandle a) {
        a.keepWorld(false);
        if (a.isNavigating()) a.stopNavigation();
        Farms.release(a.name());
        FarmWork.releaseAll(a.name());
        farm = null;
        jobs.clear();
    }

    /** The block a job is "about": what other farmers must not take at the same time. */
    private static BlockPos key(Job j) {
        return j.kind() == Kind.TILL ? j.pos().above() : j.pos();
    }

    @Override
    protected void work(AgentHandle a) {
        ticks++;
        timer++;
        if (crafter.busy()) { crafter.tick(a); return; } // making its hoe
        switch (state) {
            case CLAIM -> {
                if (timer % 100 != 1) return;
                Optional<Farms.Farm> f = Farms.claim(a.name(), a.server(), a.dimension(), a.x(), a.z());
                if (f.isPresent() && Math.hypot(f.get().centerX() - a.x(), f.get().centerZ() - a.z()) > 256) { Farms.release(a.name()); f = Optional.empty(); }
                if (f.isEmpty()) {
                    // No farm to work: make one (a field around water, tilled and planted from scratch).
                    if (makeFarm(a)) return;
                    warn(a, "no free farm and no spot to make one within 40 blocks (no flat dirt or grass patch)");
                    return;
                }
                farm = f.get();
                a.log("working farm '" + farm.name() + "' (" + farm.type().id() + ")");
                go(State.GO_TO_FARM);
            }
            case GO_TO_FARM -> {
                // On it, or arrived next to it a block or two higher (the field is at the water's level): looping "arrived, not
                // there yet, plan again" kept three farmers planning the same trip for an hour.
                boolean over = a.x() >= farm.x1() - 1 && a.x() < farm.x2() + 2 && a.z() >= farm.z1() - 1 && a.z() < farm.z2() + 2 && Math.abs(a.y() - farm.y1()) <= 4;
                if (farm.contains(a.x(), a.y(), a.z()) || over) { go(State.SCAN); return; }
                if (a.isNavigating()) return;
                if (timer > 1 && a.navStatus().startsWith("arrived")) { go(State.SCAN); return; }
                if (a.navStatus().startsWith("failed") && timer > 1) {
                    warn(a, "can't walk to farm '" + farm.name() + "': " + a.navStatus() + ". Retrying in 30 s");
                    pause(20 * 30, State.GO_TO_FARM);
                    return;
                }
                a.navigate(farm.centerX(), farm.y2() + 1, farm.centerZ(), Math.max(2, Math.min(farm.x2() - farm.x1(), farm.z2() - farm.z1()) / 2));
            }
            case SCAN -> {
                BlockSource world = a.openWorld();
                if (world == null) { pause(40, State.SCAN); return; }
                if (pourAt != null) { pour(a, world); return; }
                if (farm.type() == Farms.Type.CROPS) scanCrops(a, world); else scanTrees(a, world);
                // 'world' goes out of scope here: its decoded chunks are garbage.
                go(jobs.isEmpty() ? State.IDLE : State.NEXT_JOB);
            }
            case IDLE -> {
                if (timer >= SCAN_EVERY) go(State.SCAN);
            }
            case NEXT_JOB -> {
                // Done with the previous job: let others have that block again, unless the
                // next job is about the same block (harvest -> replant, till -> plant).
                Job previous = job;
                job = jobs.poll();
                if (previous != null && (job == null || !key(job).equals(key(previous)))) {
                    FarmWork.release(farm, key(previous), a.name());
                }
                if (job == null) { go(State.SCAN); return; }
                // Another farmer on this field already has it: leave it to them.
                if (!FarmWork.reserve(farm, key(job), a.name())) { job = null; return; }
                if (!holdFor(a, job)) return;
                go(State.WALK);
            }
            case WALK -> {
                if (inReach(a, job)) { go(State.ACT); return; }
                if (a.isNavigating()) return;
                if (timer > 1 && a.navStatus().startsWith("failed")) {
                    skipped.put(job.pos(), ticks);
                    go(State.NEXT_JOB);
                    return;
                }
                BlockPos stand = standFor(job);
                a.navigate(stand.getX(), stand.getY(), stand.getZ(),
                        job.kind() == Kind.CHOP || job.kind() == Kind.PLANT_TREE ? CHOP_DISTANCE : job.kind() == Kind.COLLECT ? 0 : 1);
            }
            case ACT -> act(a);
            case WAIT -> {
                if (a.isDigging() || --wait > 0) return;
                go(afterWait);
            }
        }
    }

    // ------------------------------------------------------------------ crops

    private void scanCrops(AgentHandle a, BlockSource w) {
        jobs.clear();
        List<Job> found = new ArrayList<>();
        List<BlockPos> farmlandSeen = new ArrayList<>();
        Set<BlockPos> knownFarmland = FarmWork.farmland(farm);
        Item anySeed = bestSeed(a);
        for (int x = farm.x1(); x <= farm.x2(); x++) {
            for (int z = farm.z1(); z <= farm.z2(); z++) {
                for (int y = farm.y1() - 1; y <= farm.y2() + 1; y++) {
                    BlockState s = w.get(x, y, z);
                    if (s == null) continue;
                    BlockPos p = new BlockPos(x, y, z);
                    if (s.getBlock() == Blocks.FARMLAND) farmlandSeen.add(p);
                    if (isSkipped(p)) continue;
                    if (s.getBlock() instanceof CropBlock crop && crop.isMaxAge(s)) {
                        if (!FarmWork.takenByOther(farm, p, a.name())) found.add(new Job(Kind.HARVEST, p, seedFor(s.getBlock())));
                    } else if (s.getBlock() == Blocks.FARMLAND) {
                        // Any empty farmland gets planted, crop there before or not.
                        BlockState above = w.get(x, y + 1, z);
                        if (above != null && above.isAir()) needSeeds++;
                        if (above != null && above.isAir() && anySeed != null && !FarmWork.takenByOther(farm, p.above(), a.name())) {
                            found.add(new Job(Kind.PLANT, p.above(), anySeed));
                        }
                    } else if (knownFarmland.contains(p) && isTillable(s.getBlock())) {
                        // Farmland to be (a field it laid out) or once farmland, turned back into dirt: till it.
                        BlockState above = w.get(x, y + 1, z);
                        if (above != null && isPlant(above) && !FarmWork.takenByOther(farm, p.above(), a.name())) {
                            found.add(new Job(Kind.GRASS, p.above(), null)); // grass or a flower on it first (seeds, too)
                        } else if (above != null && above.isAir() && !FarmWork.takenByOther(farm, p.above(), a.name())) {
                            found.add(new Job(Kind.TILL, p, null));
                            needSeeds++;
                        } else if (above != null && isBank(above) && !FarmWork.takenByOther(farm, p.above(), a.name())) {
                            BlockState above2 = w.get(x, y + 2, z);
                            if (above2 != null && (above2.isAir() || isPlant(above2))) found.add(new Job(Kind.LEVEL, p.above(), null)); // the bank off first
                        }
                    }
                }
            }
        }
        FarmWork.rememberFarmland(farm, farmlandSeen);
        // Empty farmland and no seeds: break grass around the field for wheat seeds (1 in 8 drops one).
        if (anySeed == null && needSeeds > 0) {
            int grass = 0;
            for (int r = 0; r <= 40 && grass < 24; r++) { // 40 out: within 12 there often was none, and the seeds asked for never came
                for (int x = farm.x1() - r; x <= farm.x2() + r && grass < 24; x++) {
                    for (int z = farm.z1() - r; z <= farm.z2() + r && grass < 24; z++) {
                        if (x > farm.x1() - r && x < farm.x2() + r && z > farm.z1() - r && z < farm.z2() + r) continue; // this ring only
                        for (int y = farm.y1() - 6; y <= farm.y2() + 12; y++) {
                            BlockState s = w.get(x, y, z);
                            if (s == null || (s.getBlock() != Blocks.SHORT_GRASS && s.getBlock() != Blocks.TALL_GRASS && s.getBlock() != Blocks.FERN)) continue;
                            BlockPos p = new BlockPos(x, y, z);
                            if (isSkipped(p) || FarmWork.takenByOther(farm, p, a.name())) continue;
                            found.add(new Job(Kind.GRASS, p, null));
                            grass++;
                            break;
                        }
                    }
                }
            }
            if (grass == 0) { warn(a, "no seeds to plant and no grass around to get some from: give it wheat seeds, carrots or potatoes"); requestSeeds(a); }
            else warn(a, "no seeds: breaking grass around the field for some");
        }
        needSeeds = 0;
        orderNearestFirst(a, found);
    }

    private int needSeeds;
    private long seedsAskedAt;

    private void requestSeeds(AgentHandle a) {
        long now = System.currentTimeMillis();
        if (now - seedsAskedAt < 5 * 60_000) return;
        seedsAskedAt = now;
        BuilderModule.requestMaterial(a, "minecraft:wheat_seeds", 16);
    }

    /** Grass, ferns, flowers: broken with a click, and in the way of tilling. */
    private static boolean isPlant(BlockState s) {
        Block b = s.getBlock();
        return b == Blocks.SHORT_GRASS || b == Blocks.TALL_GRASS || b == Blocks.FERN || b == Blocks.LARGE_FERN
                || s.is(net.minecraft.tags.BlockTags.SMALL_FLOWERS);
    }

    // ------------------------------------------------------------------ making a farm

    /** Middle of a field whose water still has to be poured (a hole dug there, a bucket emptied into it). */
    private BlockPos pourAt;
    private int pourTries;
    private long madeFailedAt;

    /**
     * No farm to work: lay out a 9x9 field (water reaches 4 blocks) around a water source on flat
     * dirt or grass, or around a hole it fills with its water bucket. Its tillable blocks are noted as
     * the field's farmland, so the usual scan tills, plants and later harvests them.
     */
    private boolean makeFarm(AgentHandle a) {
        long now = System.currentTimeMillis();
        if (now - madeFailedAt < 60_000) return false;
        if (!(a.openWorld() instanceof dev.agentnet.pathing.BlockView view)) return false;
        int ax = (int) Math.floor(a.x()), az = (int) Math.floor(a.z());
        List<Farms.Farm> others = Farms.list(a.server());
        BlockPos best = null;
        List<BlockPos> bestField = null;
        // 1. Water that's already there.
        List<BlockPos> water = view.findAll(ax, az, 40, st -> st.getBlock() == Blocks.WATER && st.getFluidState().isSource(), 3000);
        water.removeIf(p -> Math.abs(p.getY() - a.y()) > 12);
        water.sort(Comparator.comparingDouble(p -> p.distToCenterSqr(a.x(), a.y(), a.z())));
        int checked = 0;
        for (BlockPos wp : water) {
            if (++checked > 400) break;
            BlockState up = view.get(wp.getX(), wp.getY() + 1, wp.getZ());
            if (up == null || !(up.isAir() || isPlant(up))) continue;
            if (overlaps(others, wp)) continue;
            List<BlockPos> field = field(view, wp);
if (field.size() >= 16 && (bestField == null || field.size() > bestField.size() + 8)) { best = wp; bestField = field; }
            if (bestField != null && bestField.size() >= 60) break;
        }
        boolean pour = false, dry = false;
        // 2. No water: a flat patch it can water itself. 3. No bucket either: a dry field (crops grow on dry farmland,
        // only slower, and planted farmland never turns back to dirt): food now beats a perfect farm later.
        boolean bucket = has(a, Items.WATER_BUCKET);
        if (best == null) {
            BlockPos feet = feet(a);
            for (int r = 2; r <= 24 && best == null; r += 2) {
                for (int dx = -r; dx <= r && best == null; dx += 2) for (int dz = -r; dz <= r && best == null; dz += 2) {
                    for (int dy = -3; dy <= 2; dy++) {
                        BlockPos c = feet.offset(dx, dy - 1, dz);
                        BlockState s = view.get(c.getX(), c.getY(), c.getZ()), up = view.get(c.getX(), c.getY() + 1, c.getZ());
                        if (s == null || up == null || !isTillable(s.getBlock()) || !(up.isAir() || isPlant(up))) continue;
                        if (overlaps(others, c)) continue;
                        List<BlockPos> field = field(view, c);
                        if (field.size() >= (bucket ? 50 : 30)) { best = c; bestField = field; pour = bucket; dry = !bucket; }
                        break;
                    }
                }
            }
        }
        if (best == null) { madeFailedAt = now; return false; }
        String name = "farm-" + a.name().toLowerCase() + "-" + best.getX() + "_" + best.getZ();
        Farms.Farm f = new Farms.Farm(Farms.Type.CROPS, a.server(), a.dimension(), best.getX() - 4, best.getY(), best.getZ() - 4,
                best.getX() + 4, best.getY() + 1, best.getZ() + 4, name);
        if (!Farms.add(f)) { madeFailedAt = now; return false; }
        if (pour) bestField.remove(best);
        if (dry && !bestField.contains(best)) bestField.add(best);
        FarmWork.rememberFarmland(f, bestField);
        pourAt = pour ? best : null;
        pourTries = 0;
        a.log("§amade a new farm '" + name + "': " + bestField.size() + " blocks to till around " + (pour ? "a hole it waters with its bucket" : dry ? "a dry spot (no water near: slower crops)" : "the water") + " at " + best.toShortString());
        return true;
    }

    private static boolean overlaps(List<Farms.Farm> farms, BlockPos p) {
        for (Farms.Farm f : farms) if (p.getX() >= f.x1() - 9 && p.getX() <= f.x2() + 9 && p.getZ() >= f.z1() - 9 && p.getZ() <= f.z2() + 9 && Math.abs(p.getY() - f.y1()) < 6) return true;
        return false;
    }

    /** Dirt and grass level with the water within 4 blocks of it, open above (grass and flowers are fine). */
    private static List<BlockPos> field(BlockSource w, BlockPos water) {
        List<BlockPos> out = new ArrayList<>();
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
            if (dx == 0 && dz == 0) continue;
            int x = water.getX() + dx, z = water.getZ() + dz;
            // Level with the water only: water hydrates farmland at its own height, not a block above it. A bank one
            // higher (most ponds and rivers) is dug off first, so the field is the dirt under it, at the water's level.
            int y = water.getY();
            BlockState s = w.get(x, y, z), up = w.get(x, y + 1, z), up2 = w.get(x, y + 2, z), up3 = w.get(x, y + 3, z);
            if (s == null || up == null || up2 == null || up3 == null || !isTillable(s.getBlock())) continue;
            boolean open = (up.isAir() || isPlant(up)) && (up2.isAir() || isPlant(up2));
            boolean bank = isBank(up) && (up2.isAir() || isPlant(up2)) && (up3.isAir() || isPlant(up3));
            if (open || bank) out.add(new BlockPos(x, y, z));
        }
        return out;
    }

    /** Dig out the field's middle and empty the water bucket into it. */
    private void pour(AgentHandle a, BlockSource w) {
        BlockPos c = pourAt;
        BlockState s = w.get(c.getX(), c.getY(), c.getZ());
        if (s != null && s.getBlock() == Blocks.WATER) { a.log("watered its new field"); pourAt = null; go(State.SCAN); return; }
        if (++pourTries > 12) { a.log("§ecouldn't water its new field at " + c.toShortString()); pourAt = null; go(State.SCAN); return; }
        double d = Math.sqrt(c.distToCenterSqr(a.x(), a.y() + 1.6, a.z()));
        if (d > 4 || Math.abs(feet(a).getX() - c.getX()) + Math.abs(feet(a).getZ() - c.getZ()) == 0) {
            a.navigate(c.getX() + 2, c.getY() + 1, c.getZ(), 1);
            pause(40, State.SCAN);
            return;
        }
        if (s != null && !s.isAir()) { a.dig(c.getX(), c.getY(), c.getZ()); pause(30, State.SCAN); return; }
        if (!select(a, Items.WATER_BUCKET)) { pourAt = null; go(State.SCAN); return; }
        a.lookAt(c.getX() + 0.5, c.getY() + 0.02, c.getZ() + 0.5); // a bucket empties where the agent looks
        a.useItem();
        pause(10, State.SCAN);
    }

    /** Blocks a hoe turns into farmland in one use. */
    /** One block of bank over a field spot: dug off to bring the field down to the water. */
    private static boolean isBank(BlockState s) {
        Block b = s.getBlock();
        return b == Blocks.DIRT || b == Blocks.GRASS_BLOCK || b == Blocks.DIRT_PATH || b == Blocks.FARMLAND || b == Blocks.COARSE_DIRT || b == Blocks.SAND || b == Blocks.GRAVEL;
    }

    private static boolean isTillable(Block b) {
        return b == Blocks.DIRT || b == Blocks.GRASS_BLOCK || b == Blocks.DIRT_PATH;
    }

    // ------------------------------------------------------------------ trees

    private void scanTrees(AgentHandle a, BlockSource w) {
        jobs.clear();
        List<Job> found = new ArrayList<>();
        for (int x = farm.x1(); x <= farm.x2(); x++) {
            for (int z = farm.z1(); z <= farm.z2(); z++) {
                for (int y = farm.y1() - 1; y <= farm.y2() + 1; y++) {
                    BlockState s = w.get(x, y, z), below = w.get(x, y - 1, z);
                    if (s == null || below == null || !isDirt(below.getBlock())) continue;
                    BlockPos p = new BlockPos(x, y, z);
                    if (s.getBlock() instanceof SaplingBlock) {
                        treeSpots.put(p, s.getBlock().asItem()); // learn the spot; nothing to do yet
                    } else if (isLog(s.getBlock())) {
                        treeSpots.putIfAbsent(p, saplingFor(s.getBlock()));
                        if (!isSkipped(p) && !FarmWork.takenByOther(farm, p, a.name())) found.add(new Job(Kind.CHOP, p, null));
                    } else if (s.isAir() && treeSpots.containsKey(p) && !isSkipped(p) && !FarmWork.takenByOther(farm, p, a.name())) {
                        Item sapling = treeSpots.get(p);
                        if (sapling != null && has(a, sapling) && !inBase(a, p)) found.add(new Job(Kind.PLANT_TREE, p, sapling));
                        else warn(a, "out of saplings to replant (" + name(sapling) + ")");
                    }
                }
            }
        }
        if (treeSpots.isEmpty()) warn(a, "no saplings or trees in farm '" + farm.name() + "'; plant the first saplings yourself");
        orderNearestFirst(a, found);
    }

    // ------------------------------------------------------------------ doing a job

    private void act(AgentHandle a) {
        BlockPos p = job.pos();
        switch (job.kind()) {
            case HARVEST -> {
                // Everything grown in the 3x3 around us, in one go: a player's pickup box reaches
                // 1.3 blocks on each axis, so all those drops land in range without walking.
                List<Job> batch = new ArrayList<>();
                batch.add(job);
                BlockPos f = feet(a);
                for (var it = jobs.iterator(); it.hasNext(); ) {
                    Job j = it.next();
                    if (j.kind() == Kind.HARVEST && within3x3(f, j.pos()) && FarmWork.reserve(farm, key(j), a.name())) {
                        batch.add(j);
                        it.remove();
                    }
                }
                for (Job b : batch) {
                    BlockPos q = b.pos();
                    if (a.dig(q.getX(), q.getY(), q.getZ()) < 0) continue;
                    harvested++;
                    // Replant in the same tick: the server handles the break first, so the
                    // farmland is free again by the time the seed click arrives.
                    if (b.item() != null && selectQuiet(a, b.item())) {
                        a.useItemOn(q.getX(), q.getY() - 1, q.getZ(), Direction.UP);
                        planted++;
                        FarmWork.release(farm, key(b), a.name());
                    } else {
                        jobs.addFirst(new Job(Kind.PLANT, q, b.item())); // seed not in the hotbar: try again as its own job
                    }
                }
                job = null; // all reservations handled above
                pause(2, State.NEXT_JOB);
            }
            case COLLECT -> pause(10, State.NEXT_JOB); // standing on the drops: wait for pickup
            case GRASS -> {
                // One click breaks it; a seed it drops lands right there: step on it.
                a.dig(p.getX(), p.getY(), p.getZ());
                jobs.addFirst(new Job(Kind.COLLECT, p, null));
                pause(6, State.NEXT_JOB);
            }
            case LEVEL -> {
                // The bank block over a field spot: dug off (a shovel if it has one), then the spot is tilled next scan.
                BlockSource lw = a.openWorld();
                BlockState ls = lw == null ? null : lw.get(p.getX(), p.getY(), p.getZ());
                if (ls != null) dev.agentnet.role.Gear.holdBestFor(a, ls);
                int t = a.dig(p.getX(), p.getY(), p.getZ());
                pause(Math.max(4, t + 2), State.NEXT_JOB);
            }
            case TILL -> {
                if (selectHoe(a)) {
                    a.useItemOn(p.getX(), p.getY(), p.getZ(), Direction.UP);
                    tilled++;
                    jobs.addFirst(new Job(Kind.PLANT, p.above(), bestSeed(a)));
                }
                pause(3, State.NEXT_JOB);
            }
            case PLANT, PLANT_TREE -> {
                if (job.item() != null && select(a, job.item())) {
                    a.useItemOn(p.getX(), p.getY() - 1, p.getZ(), Direction.UP);
                    planted++;
                }
                if (job.kind() == Kind.PLANT) {
                    // Every other empty farmland in reach, now, instead of one job each.
                    BlockPos f = feet(a);
                    for (var it = jobs.iterator(); it.hasNext(); ) {
                        Job j = it.next();
                        if (j.kind() != Kind.PLANT || !within3x3(f, j.pos()) || j.item() == null) continue;
                        if (!FarmWork.reserve(farm, key(j), a.name()) || !selectQuiet(a, j.item())) continue;
                        it.remove();
                        a.useItemOn(j.pos().getX(), j.pos().getY() - 1, j.pos().getZ(), Direction.UP);
                        planted++;
                        FarmWork.release(farm, key(j), a.name());
                    }
                }
                pause(2, State.NEXT_JOB);
            }
            case CHOP -> {
                BlockSource w = a.openWorld();
                int y = p.getY() + trunkY;
                BlockState s = w == null ? null : w.get(p.getX(), y, p.getZ());
                if (trunkY >= MAX_TRUNK || s == null || !isLog(s.getBlock()) || !canReach(a, p.getX(), y, p.getZ())) {
                    // Trunk done (or the rest is out of reach): step onto the trunk spot to
                    // pick up the logs, then plant the new sapling from there.
                    trunkY = 0;
                    Item sapling = treeSpots.get(p);
                    if (sapling != null && has(a, sapling) && !inBase(a, p)) jobs.addFirst(new Job(Kind.PLANT_TREE, p, sapling));
                    jobs.addFirst(new Job(Kind.COLLECT, p, null));
                    pause(5, State.NEXT_JOB);
                    return;
                }
                if (trunkY == 0) selectAxe(a);
                a.dig(p.getX(), p.getY() + trunkY, p.getZ());
                chopped++;
                trunkY++;
                pause(2, State.ACT); // waits for the dig to finish, then the next log
            }
        }
    }

    /** Where to stand for a job: on the crop, on top of the dirt to till, or near the tree. */
    private static BlockPos standFor(Job j) {
        return key(j);
    }

    private static boolean inReach(AgentHandle a, Job j) {
        BlockPos at = standFor(j);
        double dx = at.getX() + 0.5 - a.x(), dz = at.getZ() + 0.5 - a.z();
        double flat = Math.sqrt(dx * dx + dz * dz);
        boolean tree = j.kind() == Kind.CHOP || j.kind() == Kind.PLANT_TREE;
        if (tree) return flat <= CHOP_DISTANCE + 0.6 && Math.abs(a.y() - at.getY()) < 1.5;
        // Dropped logs: stand right on them.
        if (j.kind() == Kind.COLLECT) return flat <= 0.4 && Math.abs(a.y() - at.getY()) < 1.0;
        // Crops, planting, tilling: anywhere in the 3x3 around the spot (drops are still picked up).
        return within3x3(feet(a), at) && Math.abs(a.y() - at.getY()) < 1.0;
    }

    private static BlockPos feet(AgentHandle a) {
        return new BlockPos((int) Math.floor(a.x()), (int) Math.floor(a.y() + 0.2), (int) Math.floor(a.z()));
    }

    /** Same height (or one off), at most one block away on each axis. */
    private static boolean within3x3(BlockPos f, BlockPos p) {
        return Math.abs(f.getX() - p.getX()) <= 1 && Math.abs(f.getZ() - p.getZ()) <= 1 && Math.abs(f.getY() - p.getY()) <= 1;
    }

    /** Hold a hoe from the hotbar. Without one, ask the nearest distributor (once). */
    private long hoeAskedAt;

    private boolean selectHoe(AgentHandle a) {
        List<ItemStack> inv = a.inventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (!s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().endsWith("_hoe")) {
                if (i >= 9) { a.moveToHotbar(i, 1); i = 1; } // a delivered hoe lands in the main inventory
                a.selectHotbar(i);
                return true;
            }
        }
        // Wood on it: make a wooden hoe itself.
        long nowMs = System.currentTimeMillis();
        if (nowMs - hoeCraftAt > 10_000 && crafter.queueTool(a, "minecraft:wooden_hoe")) { hoeCraftAt = nowMs; a.log("making itself a wooden hoe"); return false; }
        // No wood either: one log makes a wooden hoe (planks, sticks). Chop the nearest tree for it, then till.
        if (nowMs - woodSoughtAt > 45_000 && a.openWorld() instanceof dev.agentnet.pathing.BlockView view) {
            woodSoughtAt = nowMs;
            BlockPos best = null;
            double bd = Double.MAX_VALUE;
            for (BlockPos q : view.findAll((int) Math.floor(a.x()), (int) Math.floor(a.z()), 40, st -> isLog(st.getBlock()), 128)) {
                BlockState below = view.get(q.getX(), q.getY() - 1, q.getZ());
                if (below == null || isLog(below.getBlock()) || Math.abs(q.getY() - a.y()) > 5) continue; // the foot of a trunk, near its level
                double d = q.distToCenterSqr(a.x(), a.y(), a.z());
                if (d < bd) { bd = d; best = q; }
            }
            if (best != null) {
                trunkY = 0;
                jobs.addFirst(new Job(Kind.CHOP, best, null));
                a.log("no hoe and no wood: chopping the tree at " + best.toShortString() + " for a wooden hoe");
                return false;
            }
        }
        if (nowMs - hoeAskedAt > 120_000) { // again every 2 minutes: one lost request left farmers idle for an hour
            hoeAskedAt = nowMs;
            BuilderModule.requestMaterial(a, "minecraft:stone_hoe", 1);
        }
        warn(a, "needs a hoe (in the hotbar) to till dirt back into farmland");
        return false;
    }

    private boolean askedForHoe;
    private long hoeCraftAt, woodSoughtAt;
    private boolean madeHoe;
    private final MakerModule crafter = new MakerModule();

    private static final Set<String> PRODUCTS = Set.of("minecraft:carrot", "minecraft:potato", "minecraft:beetroot",
            "minecraft:poisonous_potato", "minecraft:apple");

    /** Keep what it plants (one stack of each), its best hoe and axe, and one stack of food. */
    @Override
    protected Set<Integer> keepSlots(AgentHandle a) {
        List<ItemStack> inv = a.inventory();
        Set<Integer> keep = new java.util.HashSet<>();
        Map<Item, Integer> biggest = new HashMap<>();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (s.isEmpty()) continue;
            Item it = s.getItem();
            String id = Gear.id(s);
            boolean plants = it == Items.WHEAT_SEEDS || it == Items.CARROT || it == Items.POTATO
                    || it == Items.BEETROOT_SEEDS || id.endsWith("_sapling") || id.endsWith("_propagule");
            if (!plants) continue;
            Integer b = biggest.get(it);
            if (b == null || s.getCount() > inv.get(b).getCount()) biggest.put(it, i);
        }
        keep.addAll(biggest.values());
        int hoe = Gear.bestTool(inv, "hoe"), axe = Gear.bestTool(inv, "axe");
        if (hoe >= 0) keep.add(hoe);
        if (axe >= 0) keep.add(axe);
        keepFood(inv, keep, PRODUCTS);
        return keep;
    }
    private int tilled;

    /** Within the server's reach: eye to block center, like vanilla. */
    private static boolean canReach(AgentHandle a, int x, int y, int z) {
        double dx = x + 0.5 - a.x(), dy = y + 0.5 - (a.y() + 1.62), dz = z + 0.5 - a.z();
        return dx * dx + dy * dy + dz * dz <= (REACH + 0.5) * (REACH + 0.5);
    }

    /** Check we hold what the job needs; skip the job (and say why) if not. */
    private boolean holdFor(AgentHandle a, Job j) {
        if (j.kind() == Kind.PLANT && (j.item() == null || !has(a, j.item()))) {
            Item seed = bestSeed(a);
            if (seed == null) { warn(a, "no seeds to plant"); go(State.NEXT_JOB); return false; }
            job = new Job(Kind.PLANT, j.pos(), seed);
        }
        return true;
    }

    // ------------------------------------------------------------------ helpers

    private void orderNearestFirst(AgentHandle a, List<Job> found) {
        // Greedy nearest-neighbour route: cheap and avoids zig-zagging across the farm.
        double cx = a.x(), cz = a.z();
        List<Job> left = new ArrayList<>(found);
        while (!left.isEmpty()) {
            final double fx = cx, fz = cz;
            Job next = left.stream().min(Comparator.comparingDouble(j -> sq(j.pos().getX() + 0.5 - fx) + sq(j.pos().getZ() + 0.5 - fz))).get();
            left.remove(next);
            jobs.add(next);
            cx = next.pos().getX() + 0.5;
            cz = next.pos().getZ() + 0.5;
        }
    }

    private boolean isSkipped(BlockPos p) {
        Integer at = skipped.get(p);
        if (at == null) return false;
        if (ticks - at > SKIP_FOR) { skipped.remove(p); return false; }
        return true;
    }

    private static Item seedFor(Block crop) {
        if (crop == Blocks.WHEAT) return Items.WHEAT_SEEDS;
        if (crop == Blocks.CARROTS) return Items.CARROT;
        if (crop == Blocks.POTATOES) return Items.POTATO;
        if (crop == Blocks.BEETROOTS) return Items.BEETROOT_SEEDS;
        return null;
    }

    /** The seed the agent has most of. */
    private static Item bestSeed(AgentHandle a) {
        Map<Item, Integer> count = new HashMap<>();
        for (ItemStack s : a.inventory()) {
            Item i = s.getItem();
            if (i == Items.WHEAT_SEEDS || i == Items.CARROT || i == Items.POTATO || i == Items.BEETROOT_SEEDS) count.merge(i, s.getCount(), Integer::sum);
        }
        return count.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    private static final Set<String> DIRT = Set.of("dirt", "grass_block", "podzol", "coarse_dirt", "rooted_dirt",
            "mycelium", "moss_block", "mud", "muddy_mangrove_roots", "farmland");

    private static boolean isDirt(Block b) { return DIRT.contains(BuiltInRegistries.BLOCK.getKey(b).getPath()); }

    /** In or around the crew's base (its building, the strip before the door, 4 blocks round): never a tree there. */
    private static boolean inBase(AgentHandle a, BlockPos p) {
        Bases.Base b = Bases.crew(a.server(), a.dimension());
        if (b == null) return false;
        var o = b.origin();
        return p.getX() >= o.getX() - 5 && p.getX() <= o.getX() + BasePlanner.SX + 4 && p.getZ() >= o.getZ() - 5 && p.getZ() <= o.getZ() + BasePlanner.FRONT + BasePlanner.SZ + 4;
    }

    private static boolean isLog(Block b) { return BuiltInRegistries.BLOCK.getKey(b).getPath().endsWith("_log"); }

    /** oak_log -> oak_sapling, etc. Null if there's no such sapling (mangrove uses propagules). */
    private static Item saplingFor(Block log) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(log);
        String path = id.getPath().replace("stripped_", "").replace("_log", "_sapling");
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath(id.getNamespace(), path));
        return item == Items.AIR ? null : item;
    }

    private static boolean has(AgentHandle a, Item item) {
        return a.inventory().stream().anyMatch(s -> s.getItem() == item);
    }

    /** Like select, without the chat warning (batch work: the job is retried on its own if this fails). */
    private static boolean selectQuiet(AgentHandle a, Item item) {
        if (a.inventory().get(a.selectedSlot()).getItem() == item) return true;
        List<ItemStack> inv = a.inventory();
        for (int i = 0; i < 9; i++) {
            if (inv.get(i).getItem() == item) { a.selectHotbar(i); return true; }
        }
        return false;
    }

    /** Put an item in hand; it must be in the hotbar (items picked up land there first). */
    private boolean select(AgentHandle a, Item item) {
        List<ItemStack> inv = a.inventory();
        for (int i = 0; i < 9; i++) {
            if (inv.get(i).getItem() == item) { a.selectHotbar(i); return true; }
        }
        warn(a, name(item) + " isn't in the hotbar");
        return false;
    }

    /** Hold the best axe in the hotbar, if any (bare hands work too, just slower). */
    private static void selectAxe(AgentHandle a) {
        List<ItemStack> inv = a.inventory();
        int best = -1;
        float bestSpeed = 1f;
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.get(i);
            if (s.isEmpty() || !BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().endsWith("_axe")) continue;
            float speed = s.getDestroySpeed(Blocks.OAK_LOG.defaultBlockState());
            if (speed > bestSpeed) { bestSpeed = speed; best = i; }
        }
        if (best >= 0) a.selectHotbar(best);
    }

    private static String name(Item i) { return i == null ? "?" : BuiltInRegistries.ITEM.getKey(i).getPath(); }
    private static double sq(double v) { return v * v; }

    private void go(State next) {
        state = next;
        timer = 0;
    }

    private void pause(int ticks, State then) {
        wait = ticks;
        afterWait = then;
        state = State.WAIT;
        timer = 0;
    }

    private void warn(AgentHandle a, String message) {
        if (message.equals(lastWarning)) return;
        lastWarning = message;
        a.log(message);
    }

    String stats() { return harvested + " harvested, " + planted + " planted, " + tilled + " tilled, " + chopped + " logs"; }
}
