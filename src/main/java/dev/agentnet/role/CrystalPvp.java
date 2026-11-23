package dev.agentnet.role;

import java.util.List;

import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Crystal PvP (the user, 2026-11-23: "crystal" among the game modes the agents must beat VexBot in). Vanilla: an end
 * crystal goes on obsidian or bedrock with two blocks of air over it, and any hit makes it explode (power 6: up to about
 * 12 blocks away, harder the closer and the less covered). So, every tick while the enemy is in reach:
 * <ol>
 *   <li>a crystal near the enemy (within 4 blocks of it) and not on top of us: hit it;</li>
 *   <li>else a crystal on the obsidian next to the enemy that is farther from us than from it (it takes more than we do);</li>
 *   <li>no obsidian there (it stands on grass): a block of obsidian first, against the ground beside its feet.</li>
 * </ol>
 * The totem in the off hand is the warrior's own equip step.
 */
public final class CrystalPvp {
    private static final double REACH = 4.4;
    private int ticks, placedAt = -100, brokenAt = -100, placed, broken;
    private String said = "";

    public static boolean ready(AgentHandle a) { return slot(a, Items.END_CRYSTAL) >= 0; }
    public String tactic = "";
    public int placed() { return placed; }
    public int broken() { return broken; }

    private static int slot(AgentHandle a, net.minecraft.world.item.Item item) {
        List<ItemStack> inv = a.inventory();
        for (int i = 0; i < inv.size() && i < 36; i++) if (inv.get(i).getItem() == item) return i;
        return -1;
    }

    private static boolean base(BlockState s) { return s != null && (s.getBlock() == Blocks.OBSIDIAN || s.getBlock() == Blocks.BEDROCK); }

    /** True when it did something this tick (the fight engine does nothing else then). */
    public boolean tick(AgentHandle a, Seen enemy) {
        ticks++;
        Vec3 eye = new Vec3(a.x(), a.y() + 1.62, a.z()), me = new Vec3(a.x(), a.y(), a.z());
        if (enemy.distance() > 7) return false;
        // 1. Break: a crystal near the enemy, in reach, not right by us.
        Seen hit = null; double best = Double.MAX_VALUE;
        for (Seen s : a.entitiesNear(8)) {
            if (!s.name().equals("end_crystal")) continue;
            double toEnemy = s.pos().distanceTo(enemy.pos()), toUs = s.pos().distanceTo(me);
            if (s.pos().add(0, 1, 0).distanceTo(eye) > REACH || toEnemy > 4 || toUs < toEnemy + 0.5 && toUs < 3) continue;
            if (toEnemy < best) { best = toEnemy; hit = s; }
        }
        if (hit != null && ticks - brokenAt >= 1) {
            a.lookAt(hit.pos().x, hit.pos().y + 1.0, hit.pos().z);
            a.attack(hit.id());
            brokenAt = ticks; broken++;
            tactic = String.format("crystal: breaking one %.1f from %s", best, enemy.name());
            return true;
        }
        if (ticks - placedAt < 2) { tactic = "crystal: waiting for the one just placed"; return true; }
        int cs = slot(a, Items.END_CRYSTAL);
        if (cs < 0 || !(a.openWorld() instanceof dev.agentnet.pathing.BlockView w)) return false;
        // 2. Place: obsidian round the enemy's feet, two air above, reachable, farther from us than from it.
        int ex = (int) Math.floor(enemy.pos().x), ey = (int) Math.floor(enemy.pos().y), ez = (int) Math.floor(enemy.pos().z);
        int[] pick = null; double score = -1e9;
        int[] dirt = null; double dirtScore = -1e9;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -2; dy <= 0; dy++) {
            int bx = ex + dx, by = ey - 1 + dy, bz = ez + dz;
            BlockState b = w.get(bx, by, bz), up1 = w.get(bx, by + 1, bz), up2 = w.get(bx, by + 2, bz);
            if (b == null || up1 == null || up2 == null || !up1.isAir() || !up2.isAir()) continue;
            Vec3 c = new Vec3(bx + 0.5, by + 1, bz + 0.5);
            if (c.add(0, -0.5, 0).distanceTo(eye) > REACH) continue;
            // Nobody standing in the crystal's space (the enemy, us): vanilla refuses the placement.
            if (Math.abs(enemy.pos().x - c.x) < 0.8 && Math.abs(enemy.pos().z - c.z) < 0.8 && enemy.pos().y < c.y + 2 && enemy.pos().y + 1.8 > c.y) continue;
            if (Math.abs(me.x - c.x) < 0.8 && Math.abs(me.z - c.z) < 0.8 && me.y < c.y + 2 && me.y + 1.8 > c.y) continue;
            double toEnemy = c.distanceTo(enemy.pos().add(0, 0.9, 0)), toUs = c.distanceTo(me.add(0, 0.9, 0));
            double sc = (toUs - toEnemy) * 2 - toEnemy;
            if (toEnemy > 3.5 || toUs < toEnemy + 0.5) continue;
            if (base(b)) { if (sc > score) { score = sc; pick = new int[]{bx, by, bz}; } }
            else if (!b.isAir() && b.isSolidRender() && sc > dirtScore) { dirtScore = sc; dirt = new int[]{bx, by, bz}; }
        }
        if (pick != null) {
            int hb = cs < 9 ? cs : 8;
            if (cs >= 9) a.moveToHotbar(cs, hb);
            a.selectHotbar(hb);
            a.lookAt(pick[0] + 0.5, pick[1] + 1.0, pick[2] + 0.5);
            a.useItemOn(pick[0], pick[1], pick[2], Direction.UP);
            placedAt = ticks; placed++;
            tactic = "crystal: placed by " + enemy.name();
            if (!said.equals(enemy.name())) { said = enemy.name(); a.log("§ccrystal PvP against " + enemy.name()); }
            return true;
        }
        // 3. No obsidian by it: one placed on the ground beside it (the block replaced by obsidian is the one above that ground).
        int ob = slot(a, Items.OBSIDIAN);
        if (dirt != null && ob >= 0) {
            BlockState above = w.get(dirt[0], dirt[1] + 1, dirt[2]);
            if (above != null && above.isAir()) {
                int hb = ob < 9 ? ob : 7;
                if (ob >= 9) a.moveToHotbar(ob, hb);
                a.selectHotbar(hb);
                a.lookAt(dirt[0] + 0.5, dirt[1] + 1.0, dirt[2] + 0.5);
                a.useItemOn(dirt[0], dirt[1], dirt[2], Direction.UP); // obsidian on top of that ground block
                placedAt = ticks - 1;
                tactic = "crystal: obsidian down by " + enemy.name();
                return true;
            }
        }
        return false;
    }
}
