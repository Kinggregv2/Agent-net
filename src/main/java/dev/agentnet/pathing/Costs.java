/*
 * Derived from Baritone (https://github.com/cabaletta/baritone), file
 * baritone/api/pathing/movement/ActionCosts.java, branch 26.3.
 * Baritone is free software under the GNU Lesser General Public License v3.0;
 * this file is distributed under the same license.
 */
package dev.agentnet.pathing;

/** Movement costs in ticks, straight from Baritone's ActionCosts. */
final class Costs {
    static final double WALK_ONE_BLOCK = 20 / 4.317;                  // 4.633
    static final double WALK_OFF_BLOCK = WALK_ONE_BLOCK * 0.8;        // walk to the edge and start falling
    static final double CENTER_AFTER_FALL = WALK_ONE_BLOCK - WALK_OFF_BLOCK;
    static final double SQRT_2 = Math.sqrt(2);
    static final double INF = 1_000_000;
    static final double[] FALL_N_BLOCKS = new double[64];
    static final double JUMP_ONE_BLOCK = distanceToTicks(1.25) - distanceToTicks(0.25);
    /** Baritone's costHeuristic default: a little optimistic, keeps A* fast. */
    static final double HEURISTIC = 3.563;

    static {
        for (int i = 0; i < FALL_N_BLOCKS.length; i++) FALL_N_BLOCKS[i] = distanceToTicks(i);
    }

    private Costs() {}

    private static double velocity(int ticks) {
        return (Math.pow(0.98, ticks) - 1) * -3.92;
    }

    static double distanceToTicks(double distance) {
        if (distance == 0) return 0;
        double remaining = distance;
        int ticks = 0;
        while (true) {
            double fall = velocity(ticks);
            if (remaining <= fall) return ticks + remaining / fall;
            remaining -= fall;
            ticks++;
        }
    }
}
