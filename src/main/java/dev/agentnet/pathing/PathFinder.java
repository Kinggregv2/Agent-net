/*
 * Derived from Baritone (https://github.com/cabaletta/baritone): the search loop of
 * baritone/pathing/calc/AStarPathFinder.java and AbstractNodeCostSearch.java, and the
 * movement checks of MovementTraverse / MovementDiagonal / MovementAscend /
 * MovementDescend, branch 26.3.
 * Baritone is free software under the GNU Lesser General Public License v3.0;
 * this file is distributed under the same license.
 */
package dev.agentnet.pathing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.BooleanSupplier;

/**
 * A* over block positions, like Baritone's AStarPathFinder: same costs, same heuristic,
 * same "best so far" fallback (when the goal is outside the known chunks, return the
 * path to the node that got closest, so the agent walks that way, sees new chunks and
 * plans again).
 *
 * <p>Moves: traverse and diagonal (flat), ascend (jump up 1), descend (down 1) and
 * fall (down 2-3), and swimming on the surface of water (slow: land is preferred). No block
 * breaking/placing, no parkour, no diving.
 */
public final class PathFinder {
    /** Feet position of a step. */
    public record Node(int x, int y, int z) {}

    public record Result(List<Node> path, boolean reachesGoal, int nodesSearched) {}

    private static final int MAX_FALL = 3;
    /** Longest drop a path may take: 3, or more for an agent that can clutch (water bucket, boat). */
    private final int maxFall;
    /** Partial paths must get at least this far to count (Baritone's MIN_DIST_PATH). */
    private static final int MIN_DIST = 5;

    private final BlockSource world;
    /** Others' items on the ground (x, y, z): stepping within a block of them costs extra, so paths go around. */
    private java.util.List<int[]> avoid = java.util.List.of();
    public void avoid(java.util.List<int[]> spots) { avoid = spots == null ? java.util.List.of() : spots; }
    private final int minY, maxY;

    public PathFinder(BlockSource world, int minY, int maxY) { this(world, minY, maxY, MAX_FALL); }

    public PathFinder(BlockSource world, int minY, int maxY, int maxFall) {
        this.maxFall = Math.max(MAX_FALL, Math.min(60, maxFall));
        this.world = world;
        this.minY = minY;
        this.maxY = maxY;
    }

    private static final class PathNode {
        final int x, y, z;
        double cost = Double.MAX_VALUE, estimate, combined;
        PathNode previous;
        boolean closed;
        PathNode(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }

    /**
     * @param radius   goal reached within this many blocks (0 = exact block)
     * @param maxNodes give up after this many expanded nodes
     * @param cancel   checked now and then; true aborts the search
     */
    private boolean noPickaxe;
    private int startY;
    /**
     * No pickaxe: never deeper underground than the start. Agents without one went down into caves and tunnels again
     * and again and were stuck there for good (nothing to dig out with). Up, out and along the surface stay open.
     */
    public void noPickaxe(boolean on) { noPickaxe = on; }

    /** Rock (or anything solid) over this spot within 24 blocks, below y 60: underground. */
    private boolean covered(int x, int y, int z) {
        if (y >= 60) return false;
        for (int dy = 2; dy <= 24; dy++) { var s = world.get(x, y + dy, z); if (s == null) return false; if (!s.isAir() && s.getFluidState().isEmpty() && !s.is(net.minecraft.tags.BlockTags.LEAVES)) return true; }
        return false;
    }

    public Result find(Node start, Node goal, int radius, int maxNodes, long timeoutMs, BooleanSupplier cancel) {
        startY = start.y();
        Map<Long, PathNode> nodes = new HashMap<>();
        PriorityQueue<PathNode> open = new PriorityQueue<>((a, b) -> Double.compare(a.combined, b.combined));
        PathNode s = node(nodes, start.x(), start.y(), start.z(), goal);
        s.cost = 0;
        s.combined = s.estimate;
        open.add(s);
        PathNode best = s;
        long deadline = System.currentTimeMillis() + timeoutMs;
        int expanded = 0;
        int[] out = new int[1];

        while (!open.isEmpty() && expanded < maxNodes) {
            if ((expanded & 63) == 0 && (System.currentTimeMillis() > deadline || cancel.getAsBoolean())) break;
            PathNode cur = open.poll();
            if (cur.closed) continue;
            cur.closed = true;
            expanded++;
            if (inGoal(cur, goal, radius)) return new Result(trace(cur), true, expanded);
            if (cur.estimate < best.estimate) best = cur;

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int nx = cur.x + dx, nz = cur.z + dz;
                    if (!world.isLoaded(nx, nz)) continue;
                    boolean diagonal = dx != 0 && dz != 0;
                    if (diagonal) {
                        relax(open, nodes, cur, nx, cur.y, nz, diagonal(cur, dx, dz), goal);
                        continue;
                    }
                    relax(open, nodes, cur, nx, cur.y, nz, traverse(cur, nx, nz), goal);
                    relax(open, nodes, cur, nx, cur.y + 1, nz, ascend(cur, nx, nz), goal);
                    double fall = descend(cur, nx, nz, out);
                    relax(open, nodes, cur, nx, cur.y - out[0], nz, fall, goal);
                }
            }
        }
        // Didn't reach the goal: walk to the node that got closest, if it's worth it.
        double moved = Math.abs(best.x - start.x()) + Math.abs(best.z - start.z()) + Math.abs(best.y - start.y());
        return new Result(moved >= MIN_DIST ? trace(best) : List.of(), false, expanded);
    }

    // ------------------------------------------------------------------ moves (Baritone rules)

    private boolean standable(int x, int y, int z) {
        return y > minY && y + 1 < maxY && Rules.canStandOn(world.get(x, y - 1, z))
                && Rules.canWalkThrough(world.get(x, y, z)) && Rules.canWalkThrough(world.get(x, y + 1, z));
    }

    private boolean clear(int x, int y, int z) {
        return Rules.canWalkThrough(world.get(x, y, z));
    }

    /** Water an agent can swim in (kelp, seagrass and bubble-free water included; not lava). */
    private boolean water(int x, int y, int z) {
        var s = world.get(x, y, z);
        return s != null && s.getFluidState().is(net.minecraft.tags.FluidTags.WATER) && !Rules.dangerous(s)
                && s.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO).isEmpty();
    }

    /** Swimming on the surface: feet in water, head in the open (no diving: agents can't tell they're drowning). */
    private boolean swimmable(int x, int y, int z) {
        return y > minY && y + 1 < maxY && water(x, y, z) && Rules.canWalkThrough(world.get(x, y + 1, z));
    }

    /** Swimming is about 2.5x slower than walking. */
    private static final double SWIM_ONE_BLOCK = Costs.WALK_ONE_BLOCK * 2.5;

    /** MovementTraverse: one block flat. Through a closed wooden door or gate too: it gets opened on the way. */
    private double traverse(PathNode c, int nx, int nz) {
        if (standable(nx, c.y, nz)) return Costs.WALK_ONE_BLOCK;
        if (swimmable(nx, c.y, nz)) return SWIM_ONE_BLOCK;
        if (doorway(nx, c.y, nz)) return Costs.WALK_ONE_BLOCK + 4; // a few ticks to open it
        return Costs.INF;
    }

    /** Standable once the door/gate in it is opened. */
    private boolean doorway(int x, int y, int z) {
        if (y <= minY || y + 1 >= maxY || !Rules.canStandOn(world.get(x, y - 1, z))) return false;
        var feet = world.get(x, y, z);
        var head = world.get(x, y + 1, z);
        return (Rules.openable(feet) || Rules.openable(head)) && Rules.canWalkThroughOrOpen(feet) && Rules.canWalkThroughOrOpen(head);
    }

    /** Standing in a doorway: only straight moves out of it (diagonals and jumps would clip the door). */
    private boolean inDoorway(PathNode c) {
        return Rules.openable(world.get(c.x, c.y, c.z)) || Rules.openable(world.get(c.x, c.y + 1, c.z));
    }

    /** MovementDiagonal: both side columns must be clear too, so the agent never clips a corner. */
    private double diagonal(PathNode c, int dx, int dz) {
        if (inDoorway(c)) return Costs.INF;
        int nx = c.x + dx, nz = c.z + dz;
        boolean swim = swimmable(nx, c.y, nz);
        if (!swim && !standable(nx, c.y, nz)) return Costs.INF;
        if (!clearOrWater(nx, c.y, c.z) || !clear(nx, c.y + 1, c.z)) return Costs.INF;
        if (!clearOrWater(c.x, c.y, nz) || !clear(c.x, c.y + 1, nz)) return Costs.INF;
        return (swim ? SWIM_ONE_BLOCK : Costs.WALK_ONE_BLOCK) * Costs.SQRT_2;
    }

    private boolean clearOrWater(int x, int y, int z) { return clear(x, y, z) || water(x, y, z); }

    /** MovementAscend: jump up one block; needs headroom above the start. */
    private double ascend(PathNode c, int nx, int nz) {
        if (inDoorway(c)) return Costs.INF;
        if (!clear(c.x, c.y + 2, c.z)) return Costs.INF;
        if (!standable(nx, c.y + 1, nz)) return Costs.INF;
        return Math.max(Costs.JUMP_ONE_BLOCK, Costs.WALK_ONE_BLOCK) + Costs.WALK_ONE_BLOCK / 2;
    }

    /** MovementDescend / MovementFall: step off and drop 1..MAX_FALL blocks. */
    private double descend(PathNode c, int nx, int nz, int[] dropOut) {
        dropOut[0] = 1;
        if (inDoorway(c)) return Costs.INF;
        // Walk off the edge at the current height first.
        if (!clear(nx, c.y, nz) || !clear(nx, c.y + 1, nz)) return Costs.INF;
        if (Rules.canStandOn(world.get(nx, c.y - 1, nz))) return Costs.INF; // that's a traverse, not a drop
        // Water takes any fall: past the safe height, only a landing in water is good (a cave ledge over a pool was a
        // trap otherwise: no way down under 4 blocks, so the agent dug about on the ledge for minutes).
        for (int drop = 1; drop <= 40; drop++) {
            int y = c.y - drop;
            if (y <= minY) return Costs.INF;
            if (swimmable(nx, y, nz)) { dropOut[0] = drop; return Costs.WALK_OFF_BLOCK + Costs.FALL_N_BLOCKS[drop] + 2 + (drop > maxFall ? 10 : 0); } // into water
            if (!clear(nx, y, nz)) return Costs.INF; // landed in something
            if (drop > maxFall) continue; // too far for solid ground: keep looking for water below
            if (Rules.canStandOn(world.get(nx, y - 1, nz))) {
                dropOut[0] = drop;
                // A drop past 3 blocks means a clutch: only worth it when it saves a real detour.
                return Costs.WALK_OFF_BLOCK + Math.max(Costs.FALL_N_BLOCKS[drop], Costs.CENTER_AFTER_FALL) + (drop > MAX_FALL ? 40 + (drop - MAX_FALL) * 12 : 0); // a clutch or a fall that hurts: only to save a real detour
            }
        }
        return Costs.INF; // too far to fall safely
    }

    // ------------------------------------------------------------------ A* bookkeeping

    private final Map<Long, Boolean> caveCache = new HashMap<>();

    /** Open space underground: rock overhead, below y 56, room around its head (a cave, not a miner's 1-wide tunnel). */
    /** Lava beside its feet or head, or under its feet. */
    private boolean nearLava(int x, int y, int z) {
        for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (Math.abs(dx) + Math.abs(dz) > 1) continue;
            var s = world.get(x + dx, y + dy, z + dz);
            if (s != null && s.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) return true;
        }
        return false;
    }

    private boolean cave(int x, int y, int z) {
        if (y >= 56) return false;
        long k = ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF);
        Boolean c = caveCache.get(k);
        if (c != null) return c;
        boolean covered = false;
        for (int dy = 2; dy <= 24; dy++) { var s = world.get(x, y + dy, z); if (s == null) break; if (!s.isAir() && s.getFluidState().isEmpty()) { covered = true; break; } }
        boolean res = false;
        if (covered) {
            int open = 0;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) { var s = world.get(x + dx, y + 1, z + dz); if (s != null && s.isAir()) open++; }
            res = open >= 6;
        }
        caveCache.put(k, res);
        return res;
    }

    private void relax(PriorityQueue<PathNode> open, Map<Long, PathNode> nodes, PathNode cur,
                       int x, int y, int z, double step, Node goal) {
        if (step >= Costs.INF) return;
        if (noPickaxe && y < startY && covered(x, y, z)) return; // no pickaxe: not one block deeper underground
        // x y z [radius]: one block round others' items, wider round a spot where a mob just killed one of us.
        if (!avoid.isEmpty()) for (int[] s : avoid) { int r = s.length > 3 ? s[3] : 1; if (Math.abs(x - s[0]) <= r && Math.abs(z - s[2]) <= r && Math.abs(y - s[1]) <= Math.min(r, 4)) { step += 40; break; } }
        if (cave(x, y, z)) step += 6 * Costs.WALK_ONE_BLOCK; // caves: where agents kept dying (mobs in the dark, drops, lava)
        // Never along a lava fall or pool edge if there is any other way; but near the goal only a little (the goal is at the
        // lava: the speedrunner's pool shore). At 20 a step there, the search spent all its 60000 spots on detours and said
        // "no way" to a shore a few blocks off (the user: "baritone is scared of lava").
        if (nearLava(x, y, z)) step += (Math.abs(x - goal.x()) + Math.abs(y - goal.y()) + Math.abs(z - goal.z()) <= 6 ? 2 : 20) * Costs.WALK_ONE_BLOCK;
        PathNode n = node(nodes, x, y, z, goal);
        if (n.closed) return;
        double cost = cur.cost + step;
        if (cost < n.cost - 0.01) { // Baritone's minimumImprovement
            n.cost = cost;
            n.combined = cost + n.estimate;
            n.previous = cur;
            open.add(n); // lazy decrease-key: stale copies are skipped via 'closed'
        }
    }

    private PathNode node(Map<Long, PathNode> nodes, int x, int y, int z, Node goal) {
        return nodes.computeIfAbsent(ChunkStore.blockKey(x, y, z), k -> {
            PathNode n = new PathNode(x, y, z);
            n.estimate = heuristic(x, y, z, goal);
            return n;
        });
    }

    /** Baritone's GoalBlock heuristic: octile distance on XZ plus a Y term. */
    static double heuristic(int x, int y, int z, Node g) {
        int dx = Math.abs(x - g.x()), dz = Math.abs(z - g.z());
        double straight = Math.abs(dx - dz), diagonal = Math.min(dx, dz);
        double xz = (straight + diagonal * Costs.SQRT_2) * Costs.HEURISTIC;
        int dy = g.y() - y;
        double yCost = dy > 0 ? dy * Costs.JUMP_ONE_BLOCK : -dy * Costs.FALL_N_BLOCKS[2] / 2;
        return xz + yCost;
    }

    private static boolean inGoal(PathNode n, Node g, int radius) {
        if (radius <= 0) return n.x == g.x() && n.y == g.y() && n.z == g.z();
        int dx = n.x - g.x(), dy = n.y - g.y(), dz = n.z - g.z();
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    private static List<Node> trace(PathNode end) {
        List<Node> path = new ArrayList<>();
        for (PathNode n = end; n != null; n = n.previous) path.add(new Node(n.x, n.y, n.z));
        Collections.reverse(path);
        return path;
    }
}
