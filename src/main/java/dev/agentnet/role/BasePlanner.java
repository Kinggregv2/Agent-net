package dev.agentnet.role;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.agentnet.pathing.BlockSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.FluidState;

/**
 * Plans the crew's base for a site near an agent and draws it as a schematic the builders put up.
 * Nothing decorative: a 17 x 13 cobblestone wall, 3 high, around a cobblestone floor (with a
 * foundation down to the ground where it slopes, up to 3 blocks), one wooden door in the middle of
 * the front wall, inside:
 * <ul>
 *   <li>storage: 24 chests along the back and left walls;</li>
 *   <li>a smelting array: 9 furnaces along the right wall, a crafting table next to them;</li>
 *   <li>the hub: the open middle, where agents with nothing to do wait;</li>
 *   <li>torches on the floor so nothing spawns inside.</li>
 * </ul>
 */
final class BasePlanner {
    private BasePlanner() {}

    static final String FILE = "agentnet_base.litematic";
    static final int SX = 17, SZ = 13, SY = 4;
    /** Rows in front of the door kept for the entrance steps (the floor can sit up to 4 above the ground there). */
    static final int FRONT = 4;
    private static final int MAX_SLOPE = 4, SEARCH = 64;

    private record Site(int ox, int oz, int[][] ground, int lo, int hi) {}

    /** Plan a base near the agent (sites in {@code bad} skipped), write its schematic; null if there's no spot. */
    static Bases.Base plan(AgentHandle a, Set<Long> bad) {
        // The site is the building plus a FRONT-deep strip before its door, for the entrance steps.
        Site site = findSite(a, SX, SZ + FRONT, SY, MAX_SLOPE, bad);
        if (site == null) return null;
        // No built floor: the natural ground is the floor, at its most common height in the building's footprint.
        // Only holes (columns lower than that) get filled up to it; ground a block higher stays as it is
        // (anything where a wall, chest or furnace goes is dug out when that block is placed).
        Map<Integer, Integer> heights = new HashMap<>();
        for (int x = 0; x < SX; x++) for (int z = FRONT; z < FRONT + SZ; z++) heights.merge(site.ground[x][z], 1, Integer::sum);
        int floorY = heights.entrySet().stream().max(Map.Entry.<Integer, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey())).get().getKey();
        int base = Math.min(floorY, site.lo + 1); // the schematic starts at the lowest block it fills
        int f = floorY - base; // floor height inside the schematic
        Map<BlockPos, BlockState> m = new HashMap<>();
        List<BlockPos> chests = new ArrayList<>(), furnaces = new ArrayList<>();
        BlockState stone = Blocks.COBBLESTONE.defaultBlockState();
        final int Z = FRONT; // the building starts here; z < FRONT is the strip in front of the door
        for (int x = 0; x < SX; x++) for (int z = 0; z < SZ; z++) {
            boolean edge = x == 0 || z == 0 || x == SX - 1 || z == SZ - 1;
            int g = site.ground[x][z + Z];
            for (int y = Math.min(g + 1 - base, f); y <= f; y++) m.put(new BlockPos(x, y, z + Z), stone); // a flat cobblestone floor (holes filled up to it)
            if (edge) for (int y = 1; y <= 3; y++) m.put(new BlockPos(x, f + y, z + Z), stone);           // wall
        }
        int door = SX / 2;
        m.put(new BlockPos(door, f + 1, Z), Blocks.OAK_DOOR.defaultBlockState());
        m.put(new BlockPos(door, f + 2, Z), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        // Entrance steps: the floor can be up to a few blocks above the ground in front, and agents only climb
        // one block. A cobblestone staircase down from the door, one block lower per step, to the ground.
        for (int zz = Z - 1, top = f; zz >= 0 && top > site.ground[door][zz] - base; zz--, top--) {
            for (int y = site.ground[door][zz] + 1 - base; y <= top; y++) m.put(new BlockPos(door, y, zz), stone);
        }
        // Chests: a few to start with; more are added one at a time as they fill up (see moreChests).
        for (BlockPos c : chestSlots(f).subList(0, START_CHESTS)) { m.put(c, Blocks.CHEST.defaultBlockState()); chests.add(c); }
        for (int z = 2; z <= SZ - 4; z++) { BlockPos c = new BlockPos(SX - 2, f + 1, Z + z); m.put(c, Blocks.FURNACE.defaultBlockState()); furnaces.add(c); } // right: furnaces
        BlockPos table = new BlockPos(SX - 3, f + 1, Z + 2);
        m.put(table, Blocks.CRAFTING_TABLE.defaultBlockState());
        for (int[] t : new int[][]{{4, 3}, {12, 3}, {4, 8}, {12, 8}, {8, 6}}) m.put(new BlockPos(t[0], f + 1, Z + t[1]), Blocks.TORCH.defaultBlockState());
        Path dir = dev.agentnet.AgentFiles.dir().resolve("schematics"), file = dir.resolve(FILE);
        try {
            Files.createDirectories(dir);
            Litematic.save(file, "AgentNet base", "AgentNet", getter(m), BlockPos.ZERO, new BlockPos(SX - 1, f + SY - 1, Z + SZ - 1));
            Litematic.forget(file);
        } catch (IOException e) {
            dev.agentnet.AgentNet.LOG.warn("couldn't write the base schematic", e);
            return null;
        }
        BlockPos origin = new BlockPos(site.ox, base, site.oz);
        return new Bases.Base(a.server(), a.dimension(), origin, FILE, new BlockPos(door, f + 1, Z + SZ / 2 - 1).offset(origin),
                new BlockPos(door, f + 1, Z).offset(origin), chests.stream().map(c -> c.offset(origin)).toList(),
                furnaces.stream().map(c -> c.offset(origin)).toList(), table.offset(origin), false);
    }

    private static final Set<String> flattened = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Older bases used the natural ground as the floor: agents dug holes in it (dirt is fair game) and got trapped
     * under the chests. Adds a full cobblestone floor to the base plan, once; the builder then puts it down.
     */
    static void flatten(Bases.Base b) {
        if (b == null || b.hub() == null || !FILE.equals(b.file()) || !flattened.add(b.server() + "|" + b.dimension() + "|" + b.origin())) return;
        try {
            Path p = Litematic.find(dev.agentnet.AgentFiles.dir(), b.file());
            if (p == null) return;
            Litematic.Schematic s = Litematic.load(p);
            int f = b.hub().getY() - 1 - b.origin().getY();
            Map<BlockPos, BlockState> m = new HashMap<>();
            for (Litematic.Placed pl : s.blocks()) m.put(pl.pos(), pl.state());
            int added = 0;
            for (int x = 0; x < SX; x++) for (int z = FRONT; z < FRONT + SZ; z++)
                if (m.putIfAbsent(new BlockPos(x, f, z), Blocks.COBBLESTONE.defaultBlockState()) == null) added++;
            if (added == 0) return;
            Litematic.save(p, "AgentNet base", "AgentNet", getter(m), s.min(), s.max());
            Litematic.forget(p);
            dev.agentnet.AgentNet.LOG.info("[Base] a flat cobblestone floor added to the base plan: {} blocks", added);
        } catch (Exception e) {
            dev.agentnet.AgentNet.LOG.warn("couldn't add a floor to the base plan", e);
        }
    }

    static final int START_CHESTS = 4;

    /** Every place a chest can go, in the order they are added: the back wall from the middle out, then the left wall. */
    static List<BlockPos> chestSlots(int f) {
        List<BlockPos> out = new ArrayList<>();
        int mid = SX / 2;
        for (int d = 0; d < SX; d++) for (int x : new int[]{mid - d, mid + d + 1}) {
            if (x < 1 || x > SX - 2) continue;
            BlockPos c = new BlockPos(x, f + 1, FRONT + SZ - 2);
            if (!out.contains(c)) out.add(c);
        }
        for (int z = SZ - 4; z >= 2; z--) out.add(new BlockPos(1, f + 1, FRONT + z));
        return out;
    }

    /**
     * The chests are nearly full (every one's contents known, under 9 free slots in all): one more chest in the plan.
     * The base counts as unfinished again until the builder has put it down. False if there is no room left for one.
     */
    static boolean moreChests(Bases.Base b) {
        int free = 0;
        for (BlockPos c : b.chests()) {
            List<net.minecraft.world.item.ItemStack> l = ChestMemory.CONTENTS.get(c);
            if (l == null) return false; // not all looked into yet: can't tell
            for (var s : l) if (s.isEmpty()) free++;
        }
        if (free >= 9) return false;
        int f = b.hub().getY() - 1 - b.origin().getY();
        BlockPos next = null;
        for (BlockPos rel : chestSlots(f)) { BlockPos w = rel.offset(b.origin()); if (!b.chests().contains(w)) { next = rel; break; } }
        if (next == null) return false;
        try {
            Path p = Litematic.find(dev.agentnet.AgentFiles.dir(), b.file());
            if (p == null) return false;
            Litematic.Schematic s = Litematic.load(p);
            Map<BlockPos, BlockState> m = new HashMap<>();
            for (Litematic.Placed pl : s.blocks()) m.put(pl.pos(), pl.state());
            m.put(next, Blocks.CHEST.defaultBlockState());
            Litematic.save(p, "AgentNet base", "AgentNet", getter(m), s.min(), s.max());
            Litematic.forget(p);
        } catch (Exception e) { dev.agentnet.AgentNet.LOG.warn("couldn't add a chest to the base plan", e); return false; }
        List<BlockPos> chests = new ArrayList<>(b.chests());
        chests.add(next.offset(b.origin()));
        Bases.put(new Bases.Base(b.server(), b.dimension(), b.origin(), b.file(), b.hub(), b.door(), chests, b.furnaces(), b.table(), false));
        dev.agentnet.AgentNet.LOG.info("[Base] the {} chests are nearly full ({} free slots): chest #{} goes in at {}", b.chests().size(), free, chests.size(), next.offset(b.origin()).toShortString());
        return true;
    }

    static BlockGetter getter(Map<BlockPos, BlockState> m) {
        return new BlockGetter() {
            @Override public BlockEntity getBlockEntity(BlockPos p) { return null; }
            @Override public BlockState getBlockState(BlockPos p) { return m.getOrDefault(p, Blocks.AIR.defaultBlockState()); }
            @Override public FluidState getFluidState(BlockPos p) { return getBlockState(p).getFluidState(); }
            @Override public int getHeight() { return 384; }
            @Override public int getMinY() { return -64; }
        };
    }

    /** Every block of the base standing as planned? (Checks the schematic against the world.) Returns how many stand / of how many. */
    static int[] progress(AgentHandle a, Bases.Base b) {
        Litematic.Schematic s;
        try {
            Path p = Litematic.find(dev.agentnet.AgentFiles.dir(), b.file());
            s = p == null ? null : Litematic.load(p);
        } catch (Exception e) { s = null; }
        BlockSource w = a.openWorld();
        if (s == null || w == null) return new int[]{0, 1};
        int standing = 0;
        for (Litematic.Placed pl : s.blocks()) {
            BlockPos p = pl.pos().offset(b.origin());
            BlockState st = w.get(p.getX(), p.getY(), p.getZ());
            if (st != null && st.getBlock() == pl.state().getBlock()) standing++;
        }
        return new int[]{standing, s.blocks().size()};
    }

    /** Blocks of the base not standing yet, by item id (what still has to be brought). Null if it can't see the site. */
    static java.util.Map<String, Integer> missing(AgentHandle a, Bases.Base b) {
        Litematic.Schematic s;
        try {
            Path p = Litematic.find(dev.agentnet.AgentFiles.dir(), b.file());
            s = p == null ? null : Litematic.load(p);
        } catch (Exception e) { s = null; }
        BlockSource w = a.openWorld();
        if (s == null || w == null) return null;
        java.util.Map<String, Integer> out = new java.util.HashMap<>();
        for (Litematic.Placed pl : s.blocks()) {
            BlockPos p = pl.pos().offset(b.origin());
            BlockState st = w.get(p.getX(), p.getY(), p.getZ());
            if (st == null) return null; // part of it out of view: don't guess
            if (st.getBlock() == pl.state().getBlock()) continue;
            var item = pl.state().getBlock().asItem();
            if (item == net.minecraft.world.item.Items.AIR) continue;
            out.merge(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString(), 1, Integer::sum);
        }
        // A door is two blocks but one item.
        out.computeIfPresent("minecraft:oak_door", (k, v) -> (v + 1) / 2);
        return out;
    }

    // ------------------------------------------------------------------ finding a site

    private static Site findSite(AgentHandle a, int sx, int sz, int sy, int slope, Set<Long> bad) {
        java.util.Arrays.fill(REJECTS, 0);
        BlockSource w = a.openWorld();
        if (w == null) return null;
        int cx = (int) Math.floor(a.x()), cy = (int) Math.floor(a.y()), cz = (int) Math.floor(a.z());
        // Flat ground first: every block of slope is a layer of foundation (cobblestone the miners have to dig
        // and the builder has to place). Keep looking a few rings past the first site for a flatter one nearby.
        Site best = null;
        double bestD = Double.MAX_VALUE;
        int foundAt = -1;
        for (int r = 3; r <= SEARCH; r += 2) {
            if (foundAt > 0 && r > foundAt + 16) break;
            for (int ox = cx - r; ox <= cx + r; ox++) for (int oz = cz - r; oz <= cz + r; oz++) {
                int ring = Math.max(Math.abs(ox - cx), Math.abs(oz - cz));
                if (ring != r && ring != r - 1) continue; // this ring only
                if (bad.contains(BlockPos.asLong(ox, 0, oz))) continue;
                Site s = site(a, w, ox, cy, oz, sx, sz, sy, slope);
                if (s == null) continue;
                double d = Math.hypot(ox + sx / 2.0 - cx, oz + sz / 2.0 - cz) + (s.hi - s.lo) * 12; // flattest, then nearest
                if (d < bestD) { bestD = d; best = s; if (foundAt < 0) foundAt = r; }
            }
        }
        return best;
    }

    /** Why sites were turned down in the last search (for the log when there is no spot). */
    static final int[] REJECTS = new int[8];
    static final String[] REJECT_NAMES = {"spawn protection", "a farm", "not loaded", "water", "fence or other block", "no ground", "too steep", "something in the way"};
    private static Site no(int why) { REJECTS[why]++; return null; }

    private static Site site(AgentHandle a, BlockSource w, int ox, int cy, int oz, int sx, int sz, int sy, int slope) {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        int[][] ground = new int[sx][sz];
        for (int x = ox; x < ox + sx; x++) for (int z = oz; z < oz + sz; z++) {
            if (a.spawnProtected(x, z)) return no(0);
            for (var f : dev.agentnet.map.Farms.list(a.server())) if (dev.agentnet.role.AgentHandle.sameDim(f.dimension(), a.dimension()) && f.contains(x, cy, z)) return no(1);
            Integer g = null;
            for (int y = cy + 20; y >= cy - 20; y--) { // the ground anywhere near: a builder on a hill still sees the valley
                BlockState s = w.get(x, y, z);
                if (s == null) return no(2);
                if (!s.getFluidState().isEmpty()) return no(3);
                if (BuilderModule.tree(s)) continue; // trunks and leaves: the builder clears them, the ground is below
                if (s.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) { g = y; break; }
                if (!s.isAir() && !s.canBeReplaced()) return no(4); // a fence, a slab: not on top of those
            }
            if (g == null) return no(5);
            ground[x - ox][z - oz] = g;
            lo = Math.min(lo, g);
            hi = Math.max(hi, g);
            if (hi - lo > slope) return no(6);
        }
        for (int x = ox; x < ox + sx; x++) for (int z = oz; z < oz + sz; z++) {
            for (int y = ground[x - ox][z - oz] + 1; y <= hi + sy; y++) {
                BlockState s = w.get(x, y, z);
                if (s == null) return no(2);
                if (!s.getFluidState().isEmpty()) return no(3);
                if (!s.isAir() && !s.canBeReplaced() && !BuilderModule.tree(s)) return no(7); // trees get cut down
            }
        }
        return new Site(ox, oz, ground, lo, hi);
    }
}
