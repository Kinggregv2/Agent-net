package dev.agentnet.pathing;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.phys.Vec3;

/**
 * Gets one agent from where it is to a goal: plan (A* on a background thread), walk the
 * path with movement packets, re-plan when the path was partial or the server pushed the
 * agent back. When the trip ends (arrived or failed), every piece of pathfinding state is
 * released: the decoded blocks lived only inside the search, the path is cleared here, and
 * the agent drops its raw chunks too.
 *
 * <p>Main thread only, except the search itself.
 */
public final class Navigator {
    public enum Status { IDLE, PLANNING, WALKING, ARRIVED, FAILED }

    /** What the navigator needs from its agent. */
    public interface Body {
        Vec3 position();
        ChunkStore chunks();
        RegistryAccess registries();
        int minY();
        int maxY();
        /** Move to this spot this tick (sends a movement packet). */
        void moveTo(double x, double y, double z, float yaw);
        /** Called once a trip ends: release the world. */
        void tripEnded(boolean arrived, String why);
        /** The agent has no world data around it: reconnect so the server sends chunks again. */
        void needWorld();
        /** If a closed wooden door / fence gate is at this feet position (or above it), click it open. True if it clicked. */
        default boolean openDoorAt(int x, int y, int z) { return false; }
        /** Longest drop a path may take (more than 3 = it can clutch). */
        default int maxFall() { return 3; }
        /** Spots to keep clear of (others' items on the ground), x y z. */
        default java.util.List<int[]> avoid() { return java.util.List.of(); }
        /** Step off into a real fall (physics + clutch take over until we land). */
        default void fall() {}
        /** Walking speed factor: 1 normal, 0.3 sneaking. */
        default double speed() { return 1; }
        /** Carries a pickaxe: without one, no path goes deeper underground than where it stands. */
        default boolean hasPickaxe() { return true; }
    }

    /**
     * Background threads for every agent's searches. One thread was too few with 30+ agents: failing searches take
     * up to 2 s each, and a queue of them left every other agent "planning" for minutes, standing still.
     */
    private static final ExecutorService SEARCH = Executors.newFixedThreadPool(Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)), r -> {
        Thread t = new Thread(r, "AgentNet-pathfinder");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    });

    private static final double SPEED = 0.25;      // blocks per tick, between walking and sprinting
    private static final double RISE = 0.5, DROP = 0.8;
    private static final int MAX_REPLANS = 25;
    private static final int MAX_NODES = 60_000;
    private static final long SEARCH_MS = 2_000;

    private final Body body;
    private Status status = Status.IDLE;
    private PathFinder.Node goal;
    private int radius;
    private Future<PathFinder.Result> search;
    private volatile boolean cancel;
    private List<PathFinder.Node> path;
    private boolean pathReachesGoal;
    private int index;
    private int doorChecked = -1, doorWait;
    private int replans;
    private String lastProblem = "";

    public Navigator(Body body) { this.body = body; }

    public Status status() { return status; }
    public boolean busy() { return status == Status.PLANNING || status == Status.WALKING; }
    public PathFinder.Node goal() { return goal; }
    public int radius() { return radius; }
    public String lastProblem() { return lastProblem; }

    /** Start (or restart) a trip. radius 0 = stand exactly on the goal block. */
    public void navigate(int x, int y, int z, int radius) {
        stop(false);
        goal = new PathFinder.Node(x, y, z);
        this.radius = radius;
        replans = 0;
        plan();
    }

    /** Cancel the trip. */
    public void stop() { stop(true); }

    private void stop(boolean notify) {
        boolean wasBusy = busy();
        cancel = true;
        if (search != null) search.cancel(false);
        search = null;
        path = null;
        status = Status.IDLE;
        if (notify && wasBusy) body.tripEnded(false, "stopped");
    }

    /** The server moved the agent (rubber-band, /tp): the path is no longer valid. */
    public void onTeleported() {
        if (status == Status.WALKING) replan("server moved the agent");
    }

    public void tick() {
        switch (status) {
            case PLANNING -> collectPlan();
            case WALKING -> walk();
            default -> {}
        }
    }

    // ------------------------------------------------------------------ planning

    private void plan() {
        Vec3 p = body.position();
        ChunkStore chunks = body.chunks();
        if (!chunks.has((int) Math.floor(p.x) >> 4, (int) Math.floor(p.z) >> 4)) {
            status = Status.IDLE;
            body.needWorld(); // the agent reconnects and calls navigate() again when it's back
            return;
        }
        status = Status.PLANNING;
        cancel = false;
        PathFinder.Node target = goal;
        int r = radius;
        RegistryAccess registries = body.registries();
        int minY = body.minY(), maxY = body.maxY();
        search = SEARCH.submit(() -> {
            // The decoded world exists only inside this task and is garbage the moment it returns.
            BlockView view = new BlockView(chunks, registries, minY);
            PathFinder finder = new PathFinder(view, minY, maxY, body.maxFall());
            finder.avoid(body.avoid());
            finder.noPickaxe(!body.hasPickaxe());
            PathFinder.Node start = feet(view, p);
            PathFinder.Result res = finder.find(start, target, r, MAX_NODES, SEARCH_MS, () -> cancel);
            if (res.path().size() >= 2 || res.reachesGoal() || res.nodesSearched() > 4) return res;
            // Boxed in where it stands (on a fence or roof stairs, in a gap, half inside a block): plan
            // from a proper standing spot right next to it instead; the walk steps onto it first.
            for (int dy : new int[]{0, 1, -1}) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0 && dy == 0) continue;
                int x = start.x() + dx, y = start.y() + dy, z = start.z() + dz;
                var floor = view.get(x, y - 1, z); var feetB = view.get(x, y, z); var head = view.get(x, y + 1, z);
                if (floor == null || feetB == null || head == null) continue;
                if (!Rules.canStandOn(floor) || !Rules.canWalkThrough(feetB) || !Rules.canWalkThrough(head)) continue;
                // Up a block: room to jump in its own column first (vanilla: 1.8 tall, it can't rise a block under a ceiling
                // two blocks up). Without this check it rose into the ceiling, was put back, planned the same, again and again.
                if (dy > 0) { var over = view.get(start.x(), start.y() + 2, start.z()); if (over == null || !Rules.canWalkThrough(over)) continue; }
                // A step down beside it: the walker goes across at its own height first, then drops, so that column must have
                // room for its whole body at its own height (a one-high gap: head into the block, put back, 44 times, run 1120f).
                if (dy < 0) { var acrossFeet = view.get(x, start.y(), z); var acrossHead = view.get(x, start.y() + 1, z); if (acrossFeet == null || acrossHead == null || !Rules.canWalkThrough(acrossFeet) || !Rules.canWalkThrough(acrossHead)) continue; }
                // A diagonal one: only round an open corner (both blocks beside the way clear, feet and head, at the level it
                // walks on). Stepped onto through a solid corner, the server put it back, it planned the same step again, and
                // so on for good: this was the "put back" loop (run 1119c: 391 in 28 minutes, 130 for one agent at one spot).
                if (dx != 0 && dz != 0) {
                    int wy = Math.max(start.y(), y);
                    boolean open = true;
                    for (int[] c : new int[][]{{start.x() + dx, start.z()}, {start.x(), start.z() + dz}}) for (int h = 0; h <= 1; h++) {
                        var side = view.get(c[0], wy + h, c[1]);
                        if (side == null || !Rules.canWalkThrough(side)) open = false;
                    }
                    if (!open) continue;
                }
                PathFinder.Result alt = finder.find(new PathFinder.Node(x, y, z), target, r, MAX_NODES, SEARCH_MS, () -> cancel);
                if (alt.path().size() >= 2 || alt.reachesGoal()) { stepFirst = true; return alt; }
            }
            return res;
        });
    }

    /** The block the agent's feet are in (handles standing on farmland, slabs, etc.). */
    private static PathFinder.Node feet(BlockView view, Vec3 p) {
        int x = (int) Math.floor(p.x), z = (int) Math.floor(p.z);
        int y = (int) Math.floor(p.y + 0.01);
        var at = view.get(x, y, z);
        boolean inWater = at != null && at.getFluidState().is(net.minecraft.tags.FluidTags.WATER);
        if (!inWater && !Rules.canWalkThrough(at) && Rules.canWalkThrough(view.get(x, y + 1, z))) y++; // swimming: stay in the water
        // Under water (its head in it too): the trip starts at the surface above, or every search fails from a spot no
        // move leads out of, and it sits on the bottom until it drowns.
        if (inWater) {
            for (int k = 0; k < 24; k++) {
                var head = view.get(x, y + 1, z);
                if (head == null || !head.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) break;
                y++;
            }
        }
        return new PathFinder.Node(x, y, z);
    }

    /** The plan starts from a block next to the agent (it was boxed in where it stood). */
    private volatile boolean stepFirst;

    private void collectPlan() {
        if (search == null || !search.isDone()) return;
        PathFinder.Result result;
        try {
            result = search.get();
        } catch (Exception e) {
            finish(false, "pathfinding error: " + e);
            return;
        }
        search = null;
        if (result.path().size() < 2) {
            if (result.reachesGoal()) finish(true, "already there");
            else {
                Vec3 p = body.position();
                finish(false, String.format("no way from %.0f %.0f %.0f to within %d of %d %d %d (%d spots searched)",
                        p.x, p.y, p.z, radius, goal.x(), goal.y(), goal.z(), result.nodesSearched()));
            }
            return;
        }
        path = result.path();
        pathReachesGoal = result.reachesGoal();
        index = stepFirst ? 0 : 1; // path[0] is where we stand (or the spot next to us we start from)
        stepFirst = false;
        doorChecked = -1;
        status = Status.WALKING;
    }

    private void replan(String why) {
        if (++replans > MAX_REPLANS) { finish(false, "gave up after " + MAX_REPLANS + " re-plans (" + why + ")"); return; }
        lastProblem = why;
        path = null;
        plan();
    }

    // ------------------------------------------------------------------ walking

    private void walk() {
        if (index >= path.size()) {
            if (pathReachesGoal) finish(true, "arrived");
            else replan("partial path done, planning the next part");
            return;
        }
        PathFinder.Node n = path.get(index);
        // A door/gate on the way: open it first, give the server a moment.
        if (doorWait > 0) { doorWait--; return; }
        if (doorChecked != index) {
            doorChecked = index;
            if (body.openDoorAt(n.x(), n.y(), n.z())) { doorWait = 5; return; }
        }
        Vec3 p = body.position();
        double tx = n.x() + 0.5, ty = n.y(), tz = n.z() + 0.5;
        double dx = tx - p.x, dz = tz - p.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = horiz > 1e-4 ? (float) Math.toDegrees(Math.atan2(-dx, dz)) : 0f;

        double nx = p.x, ny = p.y, nz = p.z;
        if (ty > p.y + 1e-3) {
            // Straddling two columns (left off-centre by the server or a dig-out), rising would knock its head on the
            // next column's ceiling and the server puts it back, again and again: to the middle of its own block first.
            PathFinder.Node from = index > 0 ? path.get(index - 1) : null;
            double cx = from != null ? from.x() + 0.5 : Math.floor(p.x) + 0.5, cz = from != null ? from.z() + 0.5 : Math.floor(p.z) + 0.5;
            double ox = cx - p.x, oz = cz - p.z, off = Math.sqrt(ox * ox + oz * oz);
            if (off > 0.15 && Math.abs(ty - p.y) > 0.4 && off < 1.2) {
                double step = Math.min(SPEED, off);
                body.moveTo(p.x + ox / off * step, p.y, p.z + oz / off * step, Float.NaN);
                return;
            }
            ny = Math.min(ty, p.y + RISE);               // step up first, in our own column
        } else if (horiz > 1e-3) {
            // A straight step (along x or along z) from off the line between the two blocks' middles: onto that line
            // first. Its body is 0.6 wide: more than 0.2 off, a shoulder is in the next row, and a block standing there
            // beside the way stops it on the server ("put back", 0.25 blocks, again and again: run 1119b, two helpers 30
            // times each; it gets off-centre by being pushed, put back, or dug out).
            PathFinder.Node from = index > 0 ? path.get(index - 1) : null;
            int cellX = from != null ? from.x() : (int) Math.floor(p.x), cellZ = from != null ? from.z() : (int) Math.floor(p.z);
            if (cellX != n.x() && cellZ == n.z() && Math.abs(dz) > 0.19 && Math.abs(dx) > 0.3) {
                body.moveTo(p.x, p.y, p.z + Math.signum(dz) * Math.min(SPEED * body.speed(), Math.abs(dz)), Float.NaN);
                return;
            }
            if (cellZ != n.z() && cellX == n.x() && Math.abs(dx) > 0.19 && Math.abs(dz) > 0.3) {
                body.moveTo(p.x + Math.signum(dx) * Math.min(SPEED * body.speed(), Math.abs(dx)), p.y, p.z, Float.NaN);
                return;
            }
            double step = Math.min(SPEED * body.speed(), horiz); // then walk across (slower when sneaking)
            nx += dx / horiz * step;
            nz += dz / horiz * step;
        } else if (ty < p.y - 3.5) {
            body.fall();                                 // a big drop: really fall (and clutch); we go on after landing
            return;
        } else if (ty < p.y - 1e-3) {
            ny = Math.max(ty, p.y - DROP);               // stepping down: drop once over the new block
        }
        body.moveTo(nx, ny, nz, horiz > 1e-4 ? yaw : Float.NaN);

        if (Math.abs(nx - tx) < 1e-3 && Math.abs(nz - tz) < 1e-3 && Math.abs(ny - ty) < 1e-3) index++;
    }

    private void finish(boolean arrived, String why) {
        status = arrived ? Status.ARRIVED : Status.FAILED;
        lastProblem = arrived ? "" : why;
        path = null;
        search = null;
        body.tripEnded(arrived, why);
    }
}
