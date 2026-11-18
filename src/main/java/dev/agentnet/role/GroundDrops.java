package dev.agentnet.role;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.agentnet.bus.AgentBus;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Loads a miner left on the ground for one distributor to pick up, so the miner keeps mining instead of
 * walking its load out of a deep pit. Each drop belongs to one distributor: every other agent's item
 * pickup (sweeps, collecting, a miner picking up its own drops) leaves items near it alone.
 *
 * <p>Items vanish 5 minutes after they're dropped. A drop is only made when a distributor can get
 * there in time with a 50% safety buffer: its travel time (path length at walking speed) plus the
 * drops it already has to fetch, times 1.5, must fit in those 5 minutes.
 *
 * <p>What a client-side mod can't prevent: any player walking right over the items picks them up
 * (that's the server). Miners drop in their own dead-end tunnels, where nobody else walks.
 */
public final class GroundDrops {
    record Drop(String by, String distributor, BlockPos pos, long despawnAt) {}

    /** Item despawn time (vanilla: 6000 ticks). */
    static final long LIFETIME_MS = 5 * 60_000;
    /** Items within this of a drop belong to it. */
    static final double RADIUS = 5;
    /** Walking speed (blocks/s) and how much longer a real path is than the straight line. */
    private static final double WALK = 4.3, DETOUR = 1.5;
    /** Time a distributor needs per drop it already has to fetch (walk there, collect, take it to the builder). */
    private static final long PER_QUEUED_MS = 120_000; // fetch it and carry it to the builder

    private static final List<Drop> ACTIVE = new CopyOnWriteArrayList<>();

    private GroundDrops() {}

    private static void prune() {
        long now = System.currentTimeMillis();
        ACTIVE.removeIf(d -> d.despawnAt() < now);
    }

    static void add(Drop d) { prune(); ACTIVE.add(d); }

    /** Picked up (or given up on): the items near it are free for anyone again. */
    static void done(BlockPos pos) { ACTIVE.removeIf(d -> d.pos().equals(pos)); }

    /** This item lies at someone else's drop: leave it for that distributor. */
    static boolean reservedFor(String me, Vec3 item) {
        if (ACTIVE.isEmpty()) return false;
        prune();
        for (Drop d : ACTIVE) {
            if (d.distributor().equalsIgnoreCase(me)) continue;
            if (d.pos().distToCenterSqr(item.x, item.y, item.z) <= RADIUS * RADIUS) return true;
        }
        return false;
    }

    /** Spots of other agents' drops (x, y, z): paths keep clear of them, so nobody walks over and takes the items. */
    public static List<int[]> avoidFor(String me) {
        if (ACTIVE.isEmpty()) return List.of();
        prune();
        List<int[]> out = new java.util.ArrayList<>();
        for (Drop d : ACTIVE) if (!d.distributor().equalsIgnoreCase(me) && !d.by().equalsIgnoreCase(me)) out.add(new int[]{d.pos().getX(), d.pos().getY(), d.pos().getZ()});
        return out;
    }

    /** How long this distributor would need to get to p, in ms (its queue of drops counted in). */
    static long eta(AgentHandle h, BlockPos p) {
        double d = Math.sqrt(p.distToCenterSqr(h.x(), h.y(), h.z()));
        long queued = ACTIVE.stream().filter(x -> x.distributor().equalsIgnoreCase(h.name())).count();
        return (long) (d * DETOUR / WALK * 1000) + queued * PER_QUEUED_MS;
    }

    /** The distributor that can reach p soonest, if it gets there in time with the 50% buffer; else null. */
    static AgentHandle courier(AgentHandle from, BlockPos p) {
        prune();
        AgentHandle best = null;
        long bestEta = Long.MAX_VALUE;
        for (AgentHandle h : AgentBus.agents.get()) {
            if (!h.isOnline() || h.role() != Role.DISTRIBUTOR || !AgentHandle.sameDim(h.dimension(), from.dimension())) continue;
            long eta = eta(h, p);
            if (eta < bestEta) { bestEta = eta; best = h; }
        }
        return best != null && bestEta * 3 / 2 <= LIFETIME_MS ? best : null;
    }
}
