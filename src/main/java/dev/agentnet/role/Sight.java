package dev.agentnet.role;

import dev.agentnet.pathing.BlockSource;
import dev.agentnet.pathing.Rules;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Line of sight for hits: a real player can't hit what's behind a wall (the crosshair lands on the
 * block), so an agent doesn't either. A hit is allowed when a straight line from its eyes reaches the
 * target's feet, middle or head without passing through a block that has a collision box.
 */
public final class Sight {
    private Sight() {}

    /** Nothing solid on the straight line between the two points (unknown blocks count as open). */
    public static boolean clear(BlockSource w, double ex, double ey, double ez, double tx, double ty, double tz) {
        if (w == null) return true;
        // Every block the line passes through, exactly (a walk from block to block). Points every 0.2 blocks missed the
        // corner of a block the line only clipped, and a bucket aimed past that corner went onto the block instead.
        double dx = tx - ex, dy = ty - ey, dz = tz - ez;
        int x = (int) Math.floor(ex), y = (int) Math.floor(ey), z = (int) Math.floor(ez);
        int gx = (int) Math.floor(tx), gy = (int) Math.floor(ty), gz = (int) Math.floor(tz);
        int sx = dx > 0 ? 1 : dx < 0 ? -1 : 0, sy = dy > 0 ? 1 : dy < 0 ? -1 : 0, sz = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double inf = Double.POSITIVE_INFINITY;
        double ddx = sx == 0 ? inf : 1 / Math.abs(dx), ddy = sy == 0 ? inf : 1 / Math.abs(dy), ddz = sz == 0 ? inf : 1 / Math.abs(dz);
        double mx = sx == 0 ? inf : (sx > 0 ? x + 1 - ex : ex - x) * ddx;
        double my = sy == 0 ? inf : (sy > 0 ? y + 1 - ey : ey - y) * ddy;
        double mz = sz == 0 ? inf : (sz > 0 ? z + 1 - ez : ez - z) * ddz;
        for (int guard = 0; guard < 512; guard++) {
            BlockState s = w.get(x, y, z);
            if (s != null && !s.isAir() && s.getFluidState().isEmpty() && !Rules.canWalkThrough(s)
                    && !(s.getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock)) return false; // water, lava and fire don't stop a hit
            if (x == gx && y == gy && z == gz) return true;
            double m = Math.min(mx, Math.min(my, mz));
            if (m > 1) return true;
            if (m == mx) { x += sx; mx += ddx; }
            else if (m == my) { y += sy; my += ddy; }
            else { z += sz; mz += ddz; }
        }
        return true;
    }

    /** From eyes at (x, y + 1.62, z) to a target standing at feet: any of feet, middle, head in plain view. */
    public static boolean canHit(BlockSource w, double x, double y, double z, Vec3 feet, boolean tall) {
        if (w == null || feet == null) return true;
        double ey = y + 1.62;
        if (clear(w, x, ey, z, feet.x, feet.y + 0.9, feet.z)) return true;
        if (clear(w, x, ey, z, feet.x, feet.y + 0.25, feet.z)) return true;
        return tall && clear(w, x, ey, z, feet.x, feet.y + 1.6, feet.z);
    }

    public static boolean canHit(AgentHandle a, Seen target) {
        return canHit(a.openWorld(), a.x(), a.y(), a.z(), target.pos(), target.player());
    }
}
