package dev.agentnet.role;

import java.util.List;

import dev.agentnet.pathing.BlockSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Not taking fall damage, for every agent (warriors have their own, fight-aware version):
 * <ul>
 *   <li><b>water bucket</b> (not in the Nether): as soon as the ground is in reach, water goes down
 *       under us (crouching over leaves/slabs, which a bucket would just waterlog); after landing
 *       it's scooped back up;</li>
 *   <li><b>leave clutch</b>: no bucket and a fall that would hurt: just before touching down the
 *       agent leaves the server and rejoins a moment later.</li>
 * </ul>
 * With a bucket the pathfinder may also take big drops on purpose (see {@link #maxFall}).
 */
public final class Clutch {
    /** Leave-clutch on/off for everyone (/agent leaveclutch on|off). */
    public static boolean leaveClutch = false; // tested 2026-11-15: this server keeps the fall across a rejoin (took 14 of 17 damage)

    private int[] at;       // where the water went
    private int ticks;
    private boolean leaving;

    /** How far a path may drop this agent: far with a water bucket outside the Nether, else a fall it survives. */
    public static int maxFall(AgentHandle a) {
        boolean nether = a.dimension() != null && a.dimension().endsWith("the_nether");
        if (!nether && slot(a.inventory(), Items.WATER_BUCKET) >= 0) return 40;
        return hurtingFall(a);
    }

    /** No clutch: a fall that hurts but leaves it standing (fall damage = blocks - 3), up to 12 blocks.
     *  Warriors keep more health for a fight. Better than being stuck up a tree or on a ledge. */
    public static int hurtingFall(AgentHandle a) {
        float keep = a.role() == Role.WARRIOR ? 8 : 4;
        int spare = (int) Math.floor(a.health() - keep);
        return Math.max(3, Math.min(12, 3 + spare));
    }

    /** True while clutching or cleaning up after one. Call every tick before the role's work. */
    public boolean tick(AgentHandle a) {
        if (at != null) return after(a);
        if (!a.airborne() || a.verticalSpeed() >= 0 || a.riding()) { leaving = false; return false; }
        BlockSource w = a.openWorld();
        if (w == null) return false;
        int x = (int) Math.floor(a.x()), z = (int) Math.floor(a.z());
        int gy = Integer.MIN_VALUE;
        for (int y = (int) Math.floor(a.y()); y > (int) Math.floor(a.y()) - 64; y--) {
            var s = w.get(x, y, z);
            if (s == null) return false;
            if (!s.getFluidState().isEmpty()) return false; // landing in water/lava already
            if (!Tunnel.passable(s)) { gy = y; break; }
        }
        if (gy == Integer.MIN_VALUE) return false;
        double fall = a.airPeakY() - (gy + 1), above = a.y() - (gy + 1);
        if (fall <= 3.5) return false; // no damage from this one
        boolean nether = a.dimension() != null && a.dimension().endsWith("the_nether");
        int water = nether ? -1 : slot(a.inventory(), Items.WATER_BUCKET);
        if (water >= 0) {
            if (above > 2.8) return true; // ground not in reach yet: hold still, keep the hands free
            List<ItemStack> inv = a.inventory();
            int hb = water < 9 ? water : 8;
            if (water >= 9) a.moveToHotbar(water, hb);
            a.selectHotbar(hb);
            a.look(a.yaw(), 90f);
            var ground = w.get(x, gy, z);
            boolean loggable = ground != null && (ground.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)
                    || ground.getBlock() instanceof net.minecraft.world.level.block.LiquidBlockContainer);
            if (loggable) a.useItemSneaking(); else a.useItem();
            a.expectWater(x, gy + 1, z);
            at = new int[]{x, gy + 1, z};
            ticks = 0;
            a.log("§bwater clutch (" + Math.round(fall) + " block fall)");
            return true;
        }
        // No bucket: leave just before touching down (the next tick would land us).
        if (leaveClutch && !leaving && above < -a.verticalSpeed() * 1.5 + 0.3) {
            leaving = true;
            a.log("§bleave clutch (" + Math.round(fall) + " block fall, no water bucket)");
            a.leaveClutch();
            return true;
        }
        return false;
    }

    private boolean after(AgentHandle a) {
        ticks++;
        if (a.airborne() && ticks < 60) return true;
        if (ticks < 3) return true;
        // Landed in our water: scoop it back up.
        int bucket = slot(a.inventory(), Items.BUCKET);
        if (bucket >= 0) {
            int hb = bucket < 9 ? bucket : 8;
            if (bucket >= 9) a.moveToHotbar(bucket, hb);
            a.selectHotbar(hb);
            a.lookAt(at[0] + 0.5, at[1] + 0.2, at[2] + 0.5);
            a.useItem();
        }
        a.clearClutch();
        at = null;
        return false;
    }

    private static int slot(List<ItemStack> inv, net.minecraft.world.item.Item item) {
        for (int i = 0; i < inv.size(); i++) if (inv.get(i).getItem() == item) return i;
        return -1;
    }
}
