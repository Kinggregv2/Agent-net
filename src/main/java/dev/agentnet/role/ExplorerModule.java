package dev.agentnet.role;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import dev.agentnet.map.Explored;
import dev.agentnet.map.Pois;

/**
 * Explorer: keeps the shared map fresh.
 * <ul>
 *   <li><b>Frontier</b>: walks toward the nearest chunk nobody has seen (no agent received
 *       it, you never loaded it), within its radius of home. As soon as the server sends that
 *       chunk (long before it gets there: view distance), it picks the next one. Explorers
 *       claim their target so two never chase the same area.</li>
 *   <li><b>POIs</b>: every few minutes it walks to the POI checked longest ago and marks it
 *       visited.</li>
 * </ul>
 * Tasks: "/agent explore &lt;agent&gt; [radius]" (blocks, default 256) around where it stands;
 * "/agent stop" pauses it. Everything it sees also feeds Xaero's World Map.
 */
final class ExplorerModule implements RoleModule {
    private static final int DEFAULT_RADIUS = 512;
    private static final long POI_EVERY_MS = 10 * 60_000;     // revisit a POI if none was checked this long
    private static final long FAILED_FOR_MS = 10 * 60_000;    // unreachable chunk: leave it alone this long
    private static final int GIVE_UP_TICKS = 20 * 90;         // one target taking longer: try another

    private enum Mode { OFF, FRONTIER, POI, ESCAPE }
    /** Searches smaller than this mean the explorer itself is boxed in (pit, hollow), not that the target is unreachable. */
    private static final int BOXED_IN_NODES = 800;
    private final Tunnel tunnel = new Tunnel();
    private net.minecraft.core.BlockPos escapeGoal;
    private int boxed;

    private Mode mode = Mode.FRONTIER;
    private int radius = DEFAULT_RADIUS;
    private int homeX, homeZ;
    private boolean homeSet;
    private Long target;
    private Pois.Poi poi;
    private int timer;
    private long lastPoiAt = System.currentTimeMillis();
    private final Map<Long, Long> failed = new HashMap<>();
    private String status = "";

    /** Everything around is explored: nothing left for it to do here. */
    @Override public boolean idle() { return status.contains("is explored"); }
    private String lastWarning;
    private int found;

    /** The POI the explorer should check next: the one visited longest ago. */
    static Optional<Pois.Poi> nextPoi(AgentHandle a) {
        return Pois.list(a.server(), a.dimension()).stream()
                .min((p, q) -> Long.compare(p.lastVisited(), q.lastVisited()));
    }

    @Override
    public void onStart(AgentHandle a) {
        a.keepWorld(true); // always travelling: keep what the server sends instead of reconnecting for it
        if (!homeSet) setHome(a);
    }

    @Override
    public void onStop(AgentHandle a) {
        a.keepWorld(false);
        Explored.release(a.name());
        if (a.isNavigating()) a.stopNavigation();
        Explored.save();
    }

    @Override
    public void onTask(AgentHandle a, String task) {
        String[] p = task.trim().split("\\s+");
        if (p[0].equals("scout")) {
            scout = true;
            radius = 1024;
            setHome(a);
            mode = Mode.FRONTIER;
            target = null;
            a.log("[scout] looking for a lava pool for the speedrunners within " + radius + " blocks");
            return;
        }
        if (p[0].equals("explore")) {
            radius = DEFAULT_RADIUS;
            for (String s : p) {
                try { radius = Math.max(16, Math.min(4096, Integer.parseInt(s))); } catch (NumberFormatException ignored) {}
            }
            setHome(a);
            mode = Mode.FRONTIER;
            target = null;
            a.log("exploring within " + radius + " blocks of " + homeX + " " + homeZ);
        } else if (p[0].equals("idle")) {
            mode = Mode.OFF;
            target = null;
            Explored.release(a.name());
            status = "stopped";
        }
    }


    // ------------------------------------------------------------------ lava-pool scout ("to add" 2)
    //
    // Explores like any explorer; every agent already tells the main about surface lava it sees. Once a pool is known
    // within reach, the scout walks there, marks it on the map (a POI), walks into the lava, and never joins again.

    /** Scouts that went into their pool: gone for good once they are back (after the death) or out. */
    private static final java.util.Set<String> DONE = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private boolean scout, marked;
    private net.minecraft.core.BlockPos pool;
    private int poolTicks, inLavaTicks;

    /** True while the scout part has the agent. */
    private boolean scoutTick(AgentHandle a) {
        // Done: retired once it has died in the lava (it comes back with a fresh module, out of the lava), not while it
        // stands in it: retired at once, it only disconnected in the lava (run 1120q).
        if (DONE.contains(a.name().toLowerCase()) && !inLava(a)) { status = "retiring"; AgentNetClient().accept(a); return true; }
        if (pool == null) {
            pool = SpeedrunnerModule.crewLavaNear(a.x(), a.z(), 192);
            if (pool == null) return false; // nothing yet: explore on
            poolTicks = 0;
            a.log("§a[scout] a lava pool at " + pool.toShortString() + ": going to mark it");
        }
        poolTicks++;
        double d = Math.hypot(pool.getX() + 0.5 - a.x(), pool.getZ() + 0.5 - a.z());
        if (!marked && d > 5) {
            status = String.format("[scout] to the lava pool at %s (%.0f blocks)", pool.toShortString(), d);
            if (!a.isNavigating() && poolTicks % 40 == 1) a.navigate(pool.getX(), pool.getY() + 1, pool.getZ(), 4);
            if (poolTicks > 20 * 240) { a.log("§e[scout] couldn't get to the pool at " + pool.toShortString() + " in 4 minutes: exploring on"); pool = null; }
            return true;
        }
        if (!marked) {
            marked = true;
            if (a.isNavigating()) a.stopNavigation();
            Pois.add(a.server(), a.dimension(), pool.getX(), pool.getY(), pool.getZ(), "lava pool (scout " + a.name() + ")");
            a.log("§a[scout] marked the lava pool at " + pool.toShortString() + " for the speedrunners; into the lava now, and not coming back");
            poolTicks = 0;
        }
        status = "[scout] into the lava pool at " + pool.toShortString();
        var w = a.openWorld();
        var feet = w == null ? null : w.get((int) Math.floor(a.x()), (int) Math.floor(a.y() + 0.1), (int) Math.floor(a.z()));
        if (feet != null && feet.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) {
            DONE.add(a.name().toLowerCase());
            if (++inLavaTicks > 20 * 15) AgentNetClient().accept(a); // still alive after 15 s in it (fire resistance?): out anyway
            return true;
        }
        a.stepToward(pool.getX() + 0.5, pool.getZ() + 0.5);
        if (poolTicks > 20 * 30) { DONE.add(a.name().toLowerCase()); a.log("§7[scout] couldn't step into the lava: leaving for good anyway"); }
        return true;
    }

    private static boolean inLava(AgentHandle a) {
        var w = a.openWorld();
        var feet = w == null ? null : w.get((int) Math.floor(a.x()), (int) Math.floor(a.y() + 0.1), (int) Math.floor(a.z()));
        return feet != null && feet.getFluidState().is(net.minecraft.tags.FluidTags.LAVA);
    }

    private static java.util.function.Consumer<AgentHandle> AgentNetClient() { return dev.agentnet.AgentNetClient::retire; }
    private void setHome(AgentHandle a) {
        homeX = (int) Math.floor(a.x());
        homeZ = (int) Math.floor(a.z());
        homeSet = true;
    }

    @Override
    public String status() { return status; }

    @Override
    public void tick(AgentHandle a) {
        timer++;
        if (mode == Mode.OFF) return;
        if (scout && scoutTick(a)) return;
        if (mode == Mode.POI) { poiTick(a); return; }
        if (System.currentTimeMillis() < stuckUntil) { status = "stuck in a hole (water or lava all around): waiting"; return; }
        if (mode == Mode.ESCAPE) { escapeTick(a); return; }

        long now = System.currentTimeMillis();
        if (now - lastPoiAt > POI_EVERY_MS) {
            Optional<Pois.Poi> next = nextPoi(a);
            lastPoiAt = now;
            if (next.isPresent() && now - next.get().lastVisited() > POI_EVERY_MS) {
                poi = next.get();
                a.log("checking POI '" + poi.name() + "'");
                a.navigate(poi.x(), poi.y(), poi.z(), 3);
                mode = Mode.POI;
                timer = 0;
                return;
            }
        }

        // Current target arrived from the server (or we gave up on it): next one.
        boolean reached = target != null && Explored.seen(a.server(), a.dimension(), Explored.cx(target), Explored.cz(target));
        if (reached) found++;
        boolean stuck = target != null && !a.isNavigating() && a.navStatus().startsWith("failed") && timer > 5;
        if (stuck && searched(a.navStatus()) < BOXED_IN_NODES) {
            // Tiny search: the explorer is boxed in (pit, hollow), the target is probably fine.
            if (++boxed >= 2) { startEscape(a); return; }
            timer = 0;
            goToTarget(a);
            return;
        }
        if (target != null && (stuck || timer > GIVE_UP_TICKS)) {
            failed.put(target, now);
            dev.agentnet.AgentNet.LOG.info("[{}] explorer gave up on chunk {} {}: {}", a.name(), Explored.cx(target), Explored.cz(target), a.navStatus());
        }
        if (target == null || reached || stuck || timer > GIVE_UP_TICKS || (!a.isNavigating() && timer % 40 == 0)) {
            if (target == null || reached || stuck || timer > GIVE_UP_TICKS) pickTarget(a, now);
            else goToTarget(a); // walking stopped (partial path finished): plan the next part
        }
    }

    private void pickTarget(AgentHandle a, long now) {
        failed.values().removeIf(t -> now - t > FAILED_FOR_MS);
        int cx = (int) Math.floor(a.x()) >> 4, cz = (int) Math.floor(a.z()) >> 4;
        Long next = Explored.claimFrontier(a.server(), a.dimension(), a.name(), cx, cz, homeX >> 4, homeZ >> 4,
                Math.max(1, radius >> 4), failed.keySet());
        timer = 0;
        target = next;
        if (next == null) {
            status = "everything within " + radius + " blocks is explored";
            warn(a, "everything within " + radius + " blocks of " + homeX + " " + homeZ + " is explored (or unreachable); "
                    + "give it a bigger radius with /agent explore " + a.name() + " <radius>");
            if (a.isNavigating()) a.stopNavigation();
            return;
        }
        lastWarning = null;
        goToTarget(a);
    }

    private void goToTarget(AgentHandle a) {
        int x = (Explored.cx(target) << 4) + 8, z = (Explored.cz(target) << 4) + 8;
        status = String.format("exploring toward %d %d (%d chunks found, %d known)", x, z, found,
                Explored.count(a.server(), a.dimension()));
        // The height there is unknown: aim at our own height; the pathfinder walks as far toward
        // it as the known world allows and plans again as new chunks arrive.
        a.navigate(x, (int) Math.floor(a.y()), z, 12);
    }

    // ------------------------------------------------------------------ getting unstuck

    private static final java.util.regex.Pattern SEARCHED = java.util.regex.Pattern.compile("\\((\\d+) spots searched\\)");

    private static int searched(String navStatus) {
        var m = SEARCHED.matcher(navStatus);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }

    /** Dig a staircase out toward the target: aim at the ground surface ~12 blocks that way. */
    private int escapeFails;
    private long stuckUntil;

    private void startEscape(AgentHandle a) {
        boxed = 0;
        double tx = target == null ? homeX : (Explored.cx(target) << 4) + 8, tz = target == null ? homeZ : (Explored.cz(target) << 4) + 8;
        double dx = tx - a.x(), dz = tz - a.z(), len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
        // Each failed try turns 45 degrees: all 8 directions before giving up for a while.
        double ang = Math.atan2(dz, dx) + escapeFails * Math.PI / 4;
        dx = Math.cos(ang) * len; dz = Math.sin(ang) * len;
        int gx = (int) Math.floor(a.x() + dx / len * 12), gz = (int) Math.floor(a.z() + dz / len * 12);
        int ay = (int) Math.floor(a.y());
        int gy = ay + 2;
        var w = a.openWorld();
        if (w != null) {
            for (int y = ay + 30; y >= ay - 10; y--) {
                var below = w.get(gx, y - 1, gz);
                var at = w.get(gx, y, gz);
                var head = w.get(gx, y + 1, gz);
                if (below == null || at == null || head == null) continue;
                if (Tunnel.solid(below) && Tunnel.passable(at) && Tunnel.passable(head)) { gy = y; break; }
            }
        }
        escapeGoal = new net.minecraft.core.BlockPos(gx, gy, gz);
        tunnel.reset();
        if (a.isNavigating()) a.stopNavigation();
        String line = "boxed in at " + (int) a.x() + " " + ay + " " + (int) a.z() + ": digging its way out toward " + gx + " " + gy + " " + gz;
        if (escapeFails == 0) a.log(line); else dev.agentnet.AgentNet.LOG.info("[{}] {}", a.name(), line); // chat: the first try only
        mode = Mode.ESCAPE;
        timer = 0;
    }

    private void escapeTick(AgentHandle a) {
        status = "digging out of a hole toward " + escapeGoal.toShortString();
        Tunnel.Result r = tunnel.tick(a, escapeGoal);
        if (r == Tunnel.Result.WORKING && tunnel.steps() < 40 && timer < 20 * 120) return;
        if (r == Tunnel.Result.BLOCKED) {
            if (++escapeFails >= 8) {
                escapeFails = 0;
                stuckUntil = System.currentTimeMillis() + 60_000;
                a.log("stuck in a hole: every direction is blocked (" + tunnel.problem() + "); trying again in a minute");
            } else dev.agentnet.AgentNet.LOG.info("[{}] couldn't dig out ({}); trying another direction", a.name(), tunnel.problem());
            if (target != null) failed.put(target, System.currentTimeMillis());
            target = null;
        } else {
            dev.agentnet.AgentNet.LOG.info("[{}] dug out after {} steps", a.name(), tunnel.steps());
            escapeFails = 0;
        }
        mode = Mode.FRONTIER;
        timer = 0;
        if (target != null) goToTarget(a);
    }

    private void poiTick(AgentHandle a) {
        boolean arrived = !a.isNavigating() && a.navStatus().startsWith("arrived");
        boolean failedTrip = !a.isNavigating() && a.navStatus().startsWith("failed") && timer > 5;
        if (arrived || (timer > 20 && Math.abs(a.x() - poi.x()) < 4 && Math.abs(a.z() - poi.z()) < 4)) {
            Pois.markVisited(a.server(), poi.name());
            a.log("checked POI '" + poi.name() + "'");
        } else if (failedTrip || timer > 20 * 180) {
            a.log("couldn't reach POI '" + poi.name() + "': " + a.navStatus());
            Pois.markVisited(a.server(), poi.name()); // don't retry it right away
        } else {
            status = "checking POI '" + poi.name() + "'";
            if (!a.isNavigating() && timer % 40 == 0) a.navigate(poi.x(), poi.y(), poi.z(), 3);
            return;
        }
        if (a.isNavigating()) a.stopNavigation();
        poi = null;
        mode = Mode.FRONTIER;
        target = null;
        timer = 0;
    }

    private void warn(AgentHandle a, String m) {
        if (m.equals(lastWarning)) return;
        lastWarning = m;
        a.log(m);
    }
}
