package dev.agentnet.role;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;

import dev.agentnet.pathing.BlockSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Digs a walkable 1x2 staircase tunnel toward a goal, one step per call sequence: for any
 * role that has to get through terrain the pathfinder can't (out of a pit, through a hill).
 * Same rules as the miner's tunnels:
 * <ul>
 *   <li>never digs bedrock, a block touching lava/water, or a block an earlier step stood
 *       on (so stairs spiral instead of folding into an open shaft);</li>
 *   <li>steps need solid ground; gravel/sand that falls in is dug again;</li>
 *   <li>nothing inside spawn protection (the server would refuse).</li>
 * </ul>
 * Call {@link #tick} every tick until it returns ARRIVED or BLOCKED.
 */
final class Tunnel {
    enum Result { WORKING, ARRIVED, BLOCKED }

    private static final int MAX_DIG_TICKS = 20 * 20;
    private static final int MAX_REDIGS = 12;
    private static final int MAX_STEP_FAILS = 3;

    private enum S { PLAN, DIG, MOVE }

    private S s = S.PLAN;
    private BlockPos next;
    private final Deque<BlockPos> toDig = new ArrayDeque<>();
    private List<BlockPos> stepCells = List.of();
    private final LinkedHashSet<BlockPos> floors = new LinkedHashSet<>();
    private int redigs, stepFails, wait, timer, steps, plugs, digHeld;
    private String problem = "";

    /** Cells this tunnel must never dig or step into (a temple's TNT pit...). */
    java.util.function.Predicate<BlockPos> forbid = p -> false;

    /** Digging these (or their neighbours above) would set off a trap. */
    static boolean trap(BlockSource w, BlockPos p) {
        for (int dy = 0; dy <= 1; dy++) {
            BlockState s = w.get(p.getX(), p.getY() + dy, p.getZ());
            if (s == null) continue;
            var b = s.getBlock();
            if (b == net.minecraft.world.level.block.Blocks.TRIPWIRE || b == net.minecraft.world.level.block.Blocks.TRIPWIRE_HOOK
                    || b == net.minecraft.world.level.block.Blocks.TNT || b == net.minecraft.world.level.block.Blocks.STONE_PRESSURE_PLATE
                    || b == net.minecraft.world.level.block.Blocks.DISPENSER || b == net.minecraft.world.level.block.Blocks.SCULK_SENSOR
                    || b == net.minecraft.world.level.block.Blocks.SCULK_SHRIEKER) return true;
        }
        return false;
    }

    String problem() { return problem; }
    int steps() { return steps; }

    void reset() {
        s = S.PLAN;
        toDig.clear();
        floors.clear();
        redigs = stepFails = wait = timer = steps = 0;
        problem = "";
    }

    Result tick(AgentHandle a, BlockPos goal) {
        timer++;
        if (a.isDigging()) { if (++digHeld > 20 * 60) { digHeld = 0; problem = "a dig that never ended"; return Result.BLOCKED; } return Result.WORKING; }
        digHeld = 0;
        if (wait > 0) { wait--; return Result.WORKING; }
        return switch (s) {
            case PLAN -> plan(a, goal);
            case DIG -> dig(a);
            case MOVE -> move(a);
        };
    }

    private Result plan(AgentHandle a, BlockPos goal) {
        BlockSource w = a.openWorld();
        if (w == null) { wait = 40; return Result.WORKING; }
        BlockPos f = feet(a);
        if (Math.abs(f.getX() - goal.getX()) <= 1 && Math.abs(f.getZ() - goal.getZ()) <= 1 && Math.abs(f.getY() - goal.getY()) <= 1) {
            return Result.ARRIVED;
        }
        floors.add(f.below());
        if (floors.size() > 512) floors.remove(floors.iterator().next());
        // The step toward the goal (down/up when it's lower/higher); when every direction fails,
        // a level step, then the other way up/down: going around something beats giving up.
        int want = Integer.signum(goal.getY() - f.getY());
        java.util.Map<String, Integer> why = new java.util.LinkedHashMap<>();
        for (int dy : want == 0 ? new int[]{0, -1, 1} : new int[]{want, 0, -want}) {
            for (int[] d : directions(f, goal)) {
                List<BlockPos> c = cellsFor(f, d[0], d[1], dy);
                String no = null;
                if (c.stream().anyMatch(b -> a.spawnProtected(b.getX(), b.getZ()))) no = "spawn protection";
                else if (c.stream().anyMatch(forbid)) no = "a no-go area";
                else if (c.stream().anyMatch(b -> trap(w, b))) no = "a trap";
                else no = ok(w, c);
                if (no != null) { why.merge(no, 1, Integer::sum); continue; }
                next = c.get(0);
                stepCells = c.subList(1, c.size());
                toDig.clear();
                toDig.addAll(stepCells);
                redigs = 0; plugs = 0;
                s = S.DIG;
                return Result.WORKING;
            }
        }
        problem = "every way is blocked (" + String.join(", ", why.keySet()) + ")";
        return Result.BLOCKED;
    }

    private Result dig(AgentHandle a) {
        BlockSource w = a.openWorld();
        if (w == null) { wait = 40; return Result.WORKING; }
        { int p = plugWater(a, w, next.below(), stepCells);
          if (p > 0) { if (++plugs > 16) { problem = "water keeps running in"; return Result.BLOCKED; } wait = 6; return Result.WORKING; }
          if (p < 0) { problem = "water in the way and nothing to block it with"; return Result.BLOCKED; } }
        while (!toDig.isEmpty()) {
            BlockPos c = toDig.poll();
            BlockState st = w.get(c.getX(), c.getY(), c.getZ());
            if (st == null || passable(st)) continue;
            holdBest(a, st);
            int t = a.dig(c.getX(), c.getY(), c.getZ());
            if (t < 0 || t > MAX_DIG_TICKS) { problem = "a block in the way can't be dug"; return Result.BLOCKED; }
            wait = t + 1;
            return Result.WORKING;
        }
        for (BlockPos c : stepCells) {
            BlockState st = w.get(c.getX(), c.getY(), c.getZ());
            if (st != null && !passable(st)) {
                if (++redigs > MAX_REDIGS) { problem = "gravel keeps falling in"; return Result.BLOCKED; }
                toDig.addAll(stepCells);
                wait = 4;
                return Result.WORKING;
            }
        }
        a.navigate(next.getX(), next.getY(), next.getZ(), 0);
        s = S.MOVE;
        timer = 0;
        return Result.WORKING;
    }

    private Result move(AgentHandle a) {
        // A one-block step that never ends: the main stood 36 minutes "clearing the shore (54 to go)" with nothing in the log,
        // its tunnel waiting on a walk that went nowhere (run 1120zp, O97). 10 s, then the step counts as failed.
        if (a.isNavigating() && timer < 200) return Result.WORKING;
        if (timer < 2) return Result.WORKING;
        if (a.isNavigating()) a.stopNavigation();
        s = S.PLAN;
        if (feet(a).equals(next)) { stepFails = 0; steps++; return Result.WORKING; }
        if (++stepFails >= MAX_STEP_FAILS) { problem = "couldn't step into its own tunnel"; return Result.BLOCKED; }
        return Result.WORKING;
    }

    // ------------------------------------------------------------------ geometry (same as the miner's)

    static BlockPos feet(AgentHandle a) {
        return new BlockPos((int) Math.floor(a.x()), (int) Math.floor(a.y() + 0.01), (int) Math.floor(a.z()));
    }

    private static List<int[]> directions(BlockPos f, BlockPos t) {
        int dx = t.getX() - f.getX(), dz = t.getZ() - f.getZ();
        List<int[]> out = new ArrayList<>();
        int[] x = {Integer.signum(dx), 0}, z = {0, Integer.signum(dz)};
        if (Math.abs(dx) >= Math.abs(dz)) { if (dx != 0) out.add(x); if (dz != 0) out.add(z); }
        else { out.add(z); if (dx != 0) out.add(x); }
        int[][] all = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
        for (int[] d : all) if (out.stream().noneMatch(o -> o[0] == d[0] && o[1] == d[1])) out.add(d);
        return out;
    }

    private static List<BlockPos> cellsFor(BlockPos f, int dx, int dz, int dy) {
        int ny = f.getY() + Integer.signum(dy);
        BlockPos n = new BlockPos(f.getX() + dx, ny, f.getZ() + dz);
        List<BlockPos> c = new ArrayList<>();
        c.add(n);
        if (ny < f.getY()) c.add(n.above(2));
        if (ny > f.getY()) c.add(f.above(2));
        c.add(n.above());
        c.add(n);
        return c;
    }

    /** Null if this step can be dug and stood in, else why not. */
    private String ok(BlockSource w, List<BlockPos> cells) {
        BlockPos n = cells.get(0);
        BlockState floor = w.get(n.getX(), n.getY() - 1, n.getZ());
        if (floor == null) return "unloaded";
        if (!solid(floor) && !water(floor)) return lava(floor) ? "lava below" : "a drop"; // water below: plugged first
        for (int i = 1; i < cells.size(); i++) {
            BlockPos c = cells.get(i);
            if (floors.contains(c)) return "its own stairs";
            BlockState st = w.get(c.getX(), c.getY(), c.getZ());
            if (st == null) return "unloaded";
            if (passable(st)) continue;
            if (lavaNear(w, c)) return "lava next to it"; // water next to it: plugged first
            if (st.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) < 0) return "bedrock";
        }
        return null;
    }


    // ------------------------------------------------------------------ water: blocked up, not a wall
    //
    // The user, 2026-11-20 (a screenshot of the main in running water in a cave): "the agents are not capable of handling
    // water, make them block up the water so that they can continue moving". Water beside a tunnel step, in it or under
    // it is filled with a block from the pack (placed against something solid next to it) before the step is dug. Lava
    // still turns a step down.

    private static final String[] FILLERS = {"minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:dirt", "minecraft:netherrack",
            "minecraft:andesite", "minecraft:diorite", "minecraft:granite", "minecraft:stone", "minecraft:tuff", "minecraft:deepslate", "minecraft:gravel"};

    static boolean water(BlockState s) { return s != null && s.getFluidState().is(net.minecraft.tags.FluidTags.WATER); }
    static boolean lava(BlockState s) { return s != null && s.getFluidState().is(net.minecraft.tags.FluidTags.LAVA); }

    /** Lava in this block or touching it (water is no reason not to dig: it gets plugged). */
    static boolean lavaNear(BlockSource w, BlockPos c) {
        if (lava(w.get(c.getX(), c.getY(), c.getZ()))) return true;
        for (Direction d : Direction.values()) if (lava(w.get(c.getX() + d.getStepX(), c.getY() + d.getStepY(), c.getZ() + d.getStepZ()))) return true;
        return false;
    }

    /**
     * One water block in, under or beside this step filled: 1 when a block went in (wait a moment, then again), 0 when
     * there is no water left to fill, -1 when there is water but nothing to fill it with (or nothing to place against).
     */
    static int plugWater(AgentHandle a, BlockSource w, BlockPos floor, List<BlockPos> step) { return plugWater(a, w, floor, step, false); }

    /** The same round its own body: skipOwn leaves the step cells themselves alone (nothing can be placed where it stands). */
    public static int plugWater(AgentHandle a, BlockSource w, BlockPos floor, List<BlockPos> step, boolean skipOwn) {
        java.util.LinkedHashSet<BlockPos> wet = new java.util.LinkedHashSet<>();
        if (floor != null && water(w.get(floor.getX(), floor.getY(), floor.getZ()))) wet.add(floor);
        for (BlockPos c : step) {
            if (!skipOwn && water(w.get(c.getX(), c.getY(), c.getZ()))) wet.add(c);
            for (Direction d : Direction.values()) {
                BlockPos n = c.relative(d);
                if (step.contains(n) || n.equals(floor) && d == Direction.DOWN && false) continue;
                if (water(w.get(n.getX(), n.getY(), n.getZ()))) wet.add(n);
            }
        }
        if (wet.isEmpty()) return 0;
        int slot = -1;
        List<ItemStack> inv = a.inventory();
        for (String id : FILLERS) { for (int i = 0; i < inv.size() && i < 36; i++) if (Gear.id(inv.get(i)).equals(id)) { slot = i; break; } if (slot >= 0) break; }
        if (slot < 0) return -1;
        double ex = a.x(), ey = a.y() + 1.62, ez = a.z();
        for (BlockPos t : wet) {
            if ((t.getX() + 0.5 - ex) * (t.getX() + 0.5 - ex) + (t.getY() + 0.5 - ey) * (t.getY() + 0.5 - ey) + (t.getZ() + 0.5 - ez) * (t.getZ() + 0.5 - ez) > 4.4 * 4.4) continue;
            for (Direction d : Direction.values()) {
                BlockPos n = t.relative(d);
                if (!solid(w.get(n.getX(), n.getY(), n.getZ()))) continue;
                int hb = slot < 9 ? slot : 8;
                if (slot >= 9) a.moveToHotbar(slot, hb);
                if (a.selectedSlot() != hb) a.selectHotbar(hb);
                Direction face = d.getOpposite();
                a.lookAt(n.getX() + 0.5 + face.getStepX() * 0.5, n.getY() + 0.5 + face.getStepY() * 0.5, n.getZ() + 0.5 + face.getStepZ() * 0.5);
                a.placeAgainst(n.getX(), n.getY(), n.getZ(), face);
                return 1;
            }
        }
        return -1;
    }
    static boolean noFluidNear(BlockSource w, BlockPos c) {
        for (Direction d : Direction.values()) {
            BlockState st = w.get(c.getX() + d.getStepX(), c.getY() + d.getStepY(), c.getZ() + d.getStepZ());
            if (st == null || !st.getFluidState().isEmpty()) return false;
        }
        BlockState st = w.get(c.getX(), c.getY(), c.getZ());
        return st != null && st.getFluidState().isEmpty();
    }

    static boolean solid(BlockState st) {
        if (st.isAir() || !st.getFluidState().isEmpty()) return false;
        try { return !st.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty(); }
        catch (RuntimeException e) { return true; }
    }

    static boolean passable(BlockState st) {
        if (st.isAir()) return true;
        if (!st.getFluidState().isEmpty()) return false;
        try {
            return st.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()
                    && st.getBlock() != Blocks.COBWEB && st.getBlock() != Blocks.POWDER_SNOW;
        } catch (RuntimeException e) { return false; }
    }

    /** Hold the fastest item for this block (a pickaxe for stone, a shovel for dirt...). */
    static void holdBest(AgentHandle a, BlockState st) {
        List<ItemStack> inv = a.inventory();
        int best = -1;
        float bestSpeed = 1f;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack it = inv.get(i);
            if (it.isEmpty()) continue;
            float sp = it.getDestroySpeed(st);
            if (sp > bestSpeed) { bestSpeed = sp; best = i; }
        }
        if (best < 0) return;
        int slot = best;
        if (slot >= 9) { a.moveToHotbar(slot, 7); slot = 7; }
        if (a.selectedSlot() != slot) a.selectHotbar(slot);
    }
}
