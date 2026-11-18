package dev.agentnet.role;

import net.minecraft.core.BlockPos;

/**
 * Nothing to do: wait inside the crew's storage base instead of standing wherever the last job ended (out in the open
 * at night, in a cave, half a map away). Each agent gets its own spot on the floor so they don't all pile onto the hub.
 * Only in the dimension the base is in; an agent somewhere else (the nether) is left where it is.
 */
public final class HomeBase {
    private HomeBase() {}

    /** True while it is on its way home (the caller does nothing else that tick). */
    public static boolean goHome(AgentHandle a, int tick) {
        if (tick % 40 != 0 || a.isNavigating() || a.airborne() || a.riding()) return a.isNavigating() && tick % 40 != 0 && homing(a);
        Bases.Base b = Bases.crew(a.server(), a.dimension());
        if (b == null || b.hub() == null) return false;
        // Still being built (or repaired): wait outside the door, off the floor. The server won't place a block where a
        // player stands, and agents idling on their spots left the same holes in every base's floor.
        BlockPos spot = b.built() ? spot(a, b) : outside(a, b);
        double dx = a.x() - (spot.getX() + 0.5), dz = a.z() - (spot.getZ() + 0.5);
        if (dx * dx + dz * dz <= 2.5 * 2.5 && Math.abs(a.y() - spot.getY()) < 2) return false; // home
        if (dx * dx + dz * dz > 300 * 300) return false; // far away on some errand of its own: not dragged back across the map
        a.navigate(spot.getX(), spot.getY(), spot.getZ(), 1);
        return true;
    }

    /** Out in front of the door, spread along a row, clear of the entrance steps. */
    static BlockPos outside(AgentHandle a, Bases.Base b) {
        int h = Math.abs(a.name().toLowerCase().hashCode());
        int ox = h % 9 - 4;
        if (ox == 0) ox = 5;
        return b.door().offset(ox, 0, -6);
    }

    private static boolean homing(AgentHandle a) { return true; }

    /** Its own place on the base floor around the hub, from its name (stable, spread over a 7 x 5 area). */
    static BlockPos spot(AgentHandle a, Bases.Base b) {
        int h = Math.abs(a.name().toLowerCase().hashCode());
        int ox = h % 7 - 3, oz = (h / 7) % 5 - 2;
        return b.hub().offset(ox, 0, oz);
    }
}
