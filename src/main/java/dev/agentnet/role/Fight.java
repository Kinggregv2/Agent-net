package dev.agentnet.role;

import java.util.List;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/**
 * The warrior's fight engine, rebuilt from scratch on vanilla's own combat rules (2026-11-18; the old one is at git
 * tag old-fight-engine). One move at a time, each tested alone in the game before the next goes in:
 * <ol>
 *   <li><b>Melee</b> (this step): up to the enemy, and a swing only when the weapon is fully charged and the enemy is
 *       within vanilla's reach. Nothing else yet: no shield, no jumps, no bow, no mace moves.</li>
 *   <li>critical hits, 3. shield, 4. axe against a raised shield, 5. bow, 6. creepers, 7. mace and elytra mace,
 *       8. healing and backing off, 9. the special moves (boat trap, wind charge, TNT cart).</li>
 * </ol>
 *
 * <p>Vanilla's rules used here (26.3, Player.attack and Player.getAttackStrengthScale):
 * <ul>
 *   <li>a swing is fully charged {@code 20 / attack speed} ticks after the last one (or after changing the held item);
 *       the damage is {@code base * (0.2 + charge^2 * 0.8)}: a swing at half charge does 40%;</li>
 *   <li>the held item's attack speed and damage come from its attribute modifiers, on the player's base 4.0 and 1.0;</li>
 *   <li>reach: 3 blocks from the eyes (1.62 up) to the nearest point of the target's box.</li>
 * </ul>
 */
final class Fight {
    /** Vanilla's reach is 3.0; a little inside it, for the tick the packet takes. */
    private static final double REACH = 2.85;

    String tactic = "";
    /** Ticks since its last swing or since it changed the item in its hand (vanilla's attackStrengthTicker). */
    private int charge = 100;
    private int held = -1, navTicks, swings, landed, target = -1, checkAt = -1, ticks;
    private net.minecraft.world.phys.Vec3 chasedTo;

    // ------------------------------------------------------------------ vanilla's numbers

    /** Ticks for a full charge with this item in hand: 20 / attack speed (sword 1.6: 12.5, axe 1.0: 20, mace 0.6: 33.3, fist 4.0: 5). */
    static double chargeTicks(ItemStack s) {
        ItemAttributeModifiers m = s.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        double speed = m.compute(Attributes.ATTACK_SPEED, 4.0, EquipmentSlot.MAINHAND);
        return 20.0 / Math.max(0.1, speed);
    }

    /** What a fully charged swing with this item does, before armor and enchantments (fist: 1). */
    static double damage(ItemStack s) {
        ItemAttributeModifiers m = s.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        return m.compute(Attributes.ATTACK_DAMAGE, 1.0, EquipmentSlot.MAINHAND);
    }

    /** From its eyes to the nearest point of the enemy's box (0.6 wide, 1.8 tall for anything player-sized). */
    static double eyeToBox(AgentHandle a, Seen e) {
        double ex = a.x(), ey = a.y() + 1.62, ez = a.z();
        double cx = Math.max(e.pos().x - 0.3, Math.min(ex, e.pos().x + 0.3));
        double cy = Math.max(e.pos().y, Math.min(ey, e.pos().y + 1.8));
        double cz = Math.max(e.pos().z - 0.3, Math.min(ez, e.pos().z + 0.3));
        return Math.sqrt((ex - cx) * (ex - cx) + (ey - cy) * (ey - cy) + (ez - cz) * (ez - cz));
    }

    // ------------------------------------------------------------------ the fight

    void idle(AgentHandle a) {
        charge = Math.min(charge + 1, 100);
        tactic = "";
        if (swings > 0) { a.log(String.format("fight over: %d of %d swings landed", landed, swings)); swings = 0; landed = 0; }
        target = -1;
    }

    void tick(AgentHandle a, Seen enemy) {
        ticks++;
        charge = Math.min(charge + 1, 100);
        if (enemy.id() != target) { target = enemy.id(); chasedTo = null; }
        // Did the last swing count? (The server's damage event names us as the source.)
        if (checkAt >= 0 && ticks >= checkAt) { if (a.hitRecently(enemy.id(), 600)) landed++; checkAt = -1; }

        // The best thing to hit with: by the damage a full swing does a second (a sword beats an axe beats a fist).
        int w = bestWeapon(a);
        if (w >= 0 && w != a.selectedSlot()) {
            int hb = w < 9 ? w : 0;
            if (w >= 9) a.moveToHotbar(w, hb);
            a.selectHotbar(hb);
            charge = 0; // vanilla: a new item in hand starts the charge again
            held = hb;
        }
        ItemStack hand = a.inventory().get(a.selectedSlot());
        double full = chargeTicks(hand);
        double reach = eyeToBox(a, enemy);

        a.lookAt(enemy.pos().x, enemy.pos().y + 1.0, enemy.pos().z);
        boolean walled = reach <= REACH && !Sight.canHit(a, enemy);
        if (reach > REACH || walled) {
            tactic = walled ? "getting around a wall to " + enemy.name() : "closing in on " + enemy.name();
            chase(a, enemy, walled ? 0 : 1);
            return;
        }
        // In reach: stand and wait for the full charge, then swing. A swing before that does a fraction and starts the wait over.
        if (a.isNavigating() && reach < REACH - 0.6) a.stopNavigation();
        if (charge < Math.ceil(full)) { tactic = "in reach of " + enemy.name() + ", charging the swing"; return; }
        a.attack(enemy.id());
        swings++;
        checkAt = ticks + 8;
        dev.agentnet.AgentNet.LOG.info("[{}] swing {} at {}: {} from its eyes, charge {} of {} ticks, {} ({} damage)", a.name(), swings, enemy.name(),
                String.format("%.2f", reach), charge, String.format("%.1f", full), Gear.id(hand).replace("minecraft:", ""), String.format("%.0f", damage(hand)));
        charge = 0;
        tactic = "hitting " + enemy.name();
    }

    private void chase(AgentHandle a, Seen enemy, int radius) {
        if (--navTicks <= 0 || !a.isNavigating() || chasedTo == null || chasedTo.distanceTo(enemy.pos()) > 1.5) {
            navTicks = 10;
            chasedTo = enemy.pos();
            a.navigate((int) Math.floor(enemy.pos().x), (int) Math.floor(enemy.pos().y + 0.2), (int) Math.floor(enemy.pos().z), radius);
        }
    }

    /** The slot of the item with the most damage a second at full charge (ties: the one already in hand). -1: bare hands. */
    private static int bestWeapon(AgentHandle a) {
        List<ItemStack> inv = a.inventory();
        int best = -1;
        double bestDps = 1.0 * 4.0 + 0.01; // a fist: 1 damage, 4 swings a second
        for (int i = 0; i < inv.size() && i < 36; i++) {
            ItemStack s = inv.get(i);
            if (s.isEmpty()) continue;
            String t = Gear.toolType(s);
            if (!"sword".equals(t) && !"axe".equals(t)) continue; // step 1: swords and axes (maces come with the smash)
            double dps = damage(s) * 20.0 / chargeTicks(s);
            if (dps > bestDps + (i == a.selectedSlot() ? -0.01 : 0.01)) { bestDps = dps; best = i; }
        }
        return best < 0 ? -1 : best;
    }
}
