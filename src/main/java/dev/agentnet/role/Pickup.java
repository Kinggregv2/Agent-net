package dev.agentnet.role;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * After a distributor throws something to an agent: walk over the items that landed
 * nearby (thrown items can't be picked up for 2 s and may slide past you).
 */
final class Pickup {
    private final long durationMs;
    private final double radius;

    Pickup() { this(5, 12_000); }
    /** A wider, longer sweep (a builder collecting the wood off a site it cleared). */
    Pickup(double radius, long durationMs) { this.radius = radius; this.durationMs = durationMs; }

    private long until;
    private int wait;
    private final Set<Long> tried = new HashSet<>();

    void start() {
        until = System.currentTimeMillis() + durationMs;
        wait = 45; // pickup delay of thrown items, plus a little
        tried.clear();
    }

    /** True while picking up (skip the role's own work this tick). */
    boolean tick(AgentHandle a) {
        if (until == 0) return false;
        if (System.currentTimeMillis() > until) { until = 0; return false; }
        if (wait > 0) { wait--; return false; } // keep working while the items can't be taken anyway
        if (a.isNavigating()) return true;
        for (Vec3 v : a.itemsNear(radius)) {
            if (GroundDrops.reservedFor(a.name(), v)) continue; // another distributor's pickup
            if (v.distanceToSqr(a.x(), a.y(), a.z()) < 0.8) continue; // standing on it: it'll be picked up
            if (!tried.add(BlockPos.containing(v).asLong())) continue;
            a.navigate((int) Math.floor(v.x), (int) Math.floor(v.y + 0.2), (int) Math.floor(v.z), 0);
            return true;
        }
        until = 0;
        return false;
    }
}
