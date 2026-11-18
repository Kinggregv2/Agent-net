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
    /** A jump rises for about 6 ticks: it leaves the ground this many ticks before the swing is charged. */
    private static final int JUMP_LEAD = 7;
    private int jumpedAt = -100, crits;
    private float hpBefore = Float.NaN;

    private int shieldOutUntil = -1, breaks;

    /** The hotbar slot of what knocks a shield out for longest (an axe), -1 if nothing it carries does. Moved to hotbar slot 1 if it sits in the pack. */
    private static int breaker(AgentHandle a) {
        List<ItemStack> inv = a.inventory();
        int best = -1;
        float secs = 0;
        for (int i = 0; i < inv.size() && i < 36; i++) {
            var w = inv.get(i).get(DataComponents.WEAPON);
            if (w != null && w.disableBlockingForSeconds() > secs) { secs = w.disableBlockingForSeconds(); best = i; }
        }
        if (best >= 9) { int hb = a.selectedSlot() == 1 ? 2 : 1; a.moveToHotbar(best, hb); return hb; }
        return best;
    }

    private boolean shieldUp;
    private int blocks, upAt;
    private float prevHp = Float.NaN;

    private void raise(AgentHandle a) {
        if (shieldUp || !Gear.id(a.offhand()).equals("minecraft:shield")) return;
        a.useOffhand();
        shieldUp = true;
        upAt = ticks;
        blocks++;
    }

    private void lower(AgentHandle a) {
        if (!shieldUp) return;
        a.releaseUse();
        shieldUp = false;
    }

    private static boolean inWater(AgentHandle a) {
        var w = a.openWorld();
        if (w == null) return false;
        var s = w.get((int) Math.floor(a.x()), (int) Math.floor(a.y() + 0.01), (int) Math.floor(a.z()));
        return s != null && !s.getFluidState().isEmpty();
    }

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
        lower(a);
        charge = Math.min(charge + 1, 100);
        tactic = "";
        if (swings > 0) { a.log(String.format("fight over: %d of %d swings landed, %d critical, %d shield breaks", landed, swings, crits, breaks)); swings = 0; landed = 0; crits = 0; breaks = 0; shieldOutUntil = -1; }
        target = -1;
    }

    void tick(AgentHandle a, Seen enemy) {
        ticks++;
        // A hit that got through: was the shield up, for how long, and where was the enemy (a shield blocks the front half)?
        float hpNow = a.health();
        if (!Float.isNaN(hpNow) && !Float.isNaN(prevHp) && hpNow < prevHp - 0.01f) {
            double toYaw = Math.toDegrees(Math.atan2(-(enemy.pos().x - a.x()), enemy.pos().z - a.z()));
            double off = Math.abs(((toYaw - a.yaw()) % 360 + 540) % 360 - 180);
            dev.agentnet.AgentNet.LOG.info("[{}] took {}: shield {}, {} is {} degrees off its facing, {} away, shield in off hand: {}", a.name(), String.format("%.1f", prevHp - hpNow),
                    shieldUp ? "up for " + (ticks - upAt) + " ticks" : "down", enemy.name(), String.format("%.0f", off), String.format("%.1f", enemy.distance()), Gear.id(a.offhand()).equals("minecraft:shield"));
        }
        prevHp = hpNow;
        charge = Math.min(charge + 1, 100);
        if (enemy.id() != target) { target = enemy.id(); chasedTo = null; }
        // Did the last swing count? (The server's damage event names us as the source.)
        if (checkAt >= 0 && ticks >= checkAt) {
            if (a.hitRecently(enemy.id(), 600)) landed++;
            float now = a.healthOf(enemy.id());
            if (!Float.isNaN(hpBefore) && !Float.isNaN(now)) dev.agentnet.AgentNet.LOG.info("[{}] that swing took {} off {} ({} -> {})", a.name(), String.format("%.1f", hpBefore - now), enemy.name(), String.format("%.1f", hpBefore), String.format("%.1f", now));
            checkAt = -1;
        }

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

        boolean walled = reach <= REACH && !Sight.canHit(a, enemy);
        // In the air (its own jump, a knock-back): no new trip from up there; it swings if it comes into reach, else lands first.
        if (a.airborne() && (reach > REACH || walled)) { a.faceWhileMoving(enemy.pos().x, enemy.pos().y + 1.0, enemy.pos().z); tactic = "in the air by " + enemy.name(); return; }
        if (reach > REACH || walled) {
            tactic = walled ? "getting around a wall to " + enemy.name() : "closing in on " + enemy.name();
            // Facing it as it walks, never a look of its own: a look is a move packet, and only one goes out a tick, so
            // looking every tick swallowed every step (it stood "closing in" for 40 s in the first test).
            a.faceWhileMoving(enemy.pos().x, enemy.pos().y + 1.0, enemy.pos().z);
            // Step 3: the shield up on the way in, against arrows from any distance and against a melee enemy about to reach it.
            if (enemy.aiming() || reach <= 4.5) raise(a); else lower(a);
            chase(a, enemy, walled ? 0 : 1);
            return;
        }
        // In reach: stand and wait for the full charge, then swing. A swing before that does a fraction and starts the wait over.
        if (a.isNavigating()) a.stopNavigation(); // in reach: it stands (and jumps) here; out of reach again, it walks again
        if (!a.isNavigating() && !a.airborne()) a.lookAt(enemy.pos().x, enemy.pos().y + 1.0, enemy.pos().z); else a.faceWhileMoving(enemy.pos().x, enemy.pos().y + 1.0, enemy.pos().z);
        // Step 4, a raised shield. Vanilla (LivingEntity.blockedByItem, the weapon's disableBlockingForSeconds: an axe's is 5):
        // a hit that is blocked by a shield knocks that shield out for that long, whatever the swing's charge. So: the
        // axe at once, one swing, the sword straight back in hand, and critical hits while the shield is down.
        if (enemy.blocking() && ticks >= shieldOutUntil) {
            int ax = breaker(a);
            if (ax >= 0) {
                lower(a);
                float secs = a.inventory().get(ax).get(DataComponents.WEAPON).disableBlockingForSeconds();
                a.attackWith(enemy.id(), ax);
                charge = 0; // the item changed twice: vanilla starts the charge again
                shieldOutUntil = ticks + (int) (secs * 20) - 4;
                breaks++;
                dev.agentnet.AgentNet.LOG.info("[{}] {} has its shield up: one swing with the {} ({} s without a shield), then the {} again", a.name(), enemy.name(),
                        Gear.id(a.inventory().get(ax)).replace("minecraft:", ""), secs, Gear.id(hand).replace("minecraft:", ""));
                tactic = "breaking " + enemy.name() + "'s shield";
                return;
            }
        }
        // Step 2, critical hits. Vanilla (Player.canCriticalAttack): falling, off the ground, not climbing, not in water,
        // not riding, not sprinting, and the swing charged past 90%: 1.5 times the damage. A jump rises for 6 ticks, so it
        // jumps 7 ticks before the charge is full and swings on the way down.
        int left = (int) Math.ceil(full) - charge;
        boolean canJump = !a.airborne() && !a.riding() && !inWater(a);
        if (left > 0) {
            raise(a); // between its swings: the shield (it blocks from 5 ticks after it goes up, vanilla's block delay)
            if (canJump && left <= JUMP_LEAD && ticks - jumpedAt > 14) { a.jump(); jumpedAt = ticks; tactic = "jumping for a critical hit on " + enemy.name(); return; }
            tactic = a.airborne() ? "in the air over " + enemy.name() : "in reach of " + enemy.name() + ", charging the swing";
            return;
        }
        // Charged. On the ground with no jump under way (it just got here): the jump first, the hit on the way down.
        if (canJump && ticks - jumpedAt > 14) { a.jump(); jumpedAt = ticks; tactic = "jumping for a critical hit on " + enemy.name(); return; }
        // After the jump: the swing only on the way DOWN (the user: "he's supposed to hit when falling down not jumping
        // up"), and a tick into the fall, so the server has a falling move from us before the hit (its fall distance must
        // be over 0). Leaving the ground takes a tick, so "not in the air yet" right after a jump is waited out too.
        // Only when the jump is over and it stands again (a low roof, a ledge) does it swing from the ground.
        boolean falling = a.airborne() && a.verticalSpeed() < -0.1;
        if (!falling && ticks - jumpedAt <= 12 && (a.airborne() || ticks - jumpedAt <= 3)) { tactic = "in the air over " + enemy.name(); return; }
        boolean crit = falling;
        lower(a); // no swinging with the shield up
        hpBefore = a.healthOf(enemy.id());
        a.attack(enemy.id());
        if (crit) crits++;
        swings++;
        checkAt = ticks + 8;
        dev.agentnet.AgentNet.LOG.info("[{}] swing {} at {}{}: {} from its eyes, charge {} of {} ticks, {} ({} damage)", a.name(), swings, enemy.name(), crit ? " (falling: a critical hit)" : " (on the ground)",
                String.format("%.2f", reach), charge, String.format("%.1f", full), Gear.id(hand).replace("minecraft:", ""), String.format("%.0f", damage(hand)));
        charge = 0;
        tactic = "hitting " + enemy.name();
    }

    private void chase(AgentHandle a, Seen enemy, int radius) {
        if (--navTicks <= 0 || !a.isNavigating() || chasedTo == null || chasedTo.distanceTo(enemy.pos()) > 1.5) {
            navTicks = 10;
            chasedTo = enemy.pos();
            // To the ground under it, not to where it is: an enemy in the air (a jump, a knock-back, a test husk without
            // gravity two blocks over a ledge) is no place to walk to, and the trip failed for 40 s.
            int gx = (int) Math.floor(enemy.pos().x), gy = (int) Math.floor(enemy.pos().y + 0.2), gz = (int) Math.floor(enemy.pos().z);
            var w = a.openWorld();
            if (w != null) for (int d = 0; d <= 6; d++) { var under = w.get(gx, gy - d - 1, gz); if (under == null) break; if (Tunnel.solid(under)) { gy -= d; break; } }
            a.navigate(gx, gy, gz, radius);
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
