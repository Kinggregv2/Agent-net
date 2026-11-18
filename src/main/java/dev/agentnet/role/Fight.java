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

    private int shieldOutUntil = -1, breaks, breakAt = -100;

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

    // ------------------------------------------------------------------ step 5: the bow
    //
    // Vanilla (BowItem.getPowerForTime, AbstractArrow.tick): the draw is full after 20 ticks; a full-draw arrow leaves
    // at 3 blocks a tick from just under the eyes, along where the archer looks when it lets go; every tick it moves
    // by its speed, then the speed is times 0.99 and 0.05 comes off its upward part. So: the angle is worked out by
    // flying the arrow in the head, aimed at where the enemy will be when it gets there.
    // Used while the enemy is far and not itself drawing a bow at us (against an archer the shield and the sword are
    // better: step 3 took no arrow at all that way).

    private int drawing = -1, shots, bowTarget = -1;
    private net.minecraft.world.phys.Vec3 lastSeen;
    private double evx, evy, evz;

    private boolean bow(AgentHandle a, Seen enemy) {
        // How the enemy moves, blocks a tick (smoothed), for the lead.
        if (bowTarget == enemy.id() && lastSeen != null) {
            evx += (enemy.pos().x - lastSeen.x - evx) * 0.3; evy += (enemy.pos().y - lastSeen.y - evy) * 0.3; evz += (enemy.pos().z - lastSeen.z - evz) * 0.3;
        } else { evx = 0; evy = 0; evz = 0; }
        bowTarget = enemy.id(); lastSeen = enemy.pos();
        double d = enemy.distance();
        int bowAt = -1, arrows = 0;
        List<ItemStack> inv = a.inventory();
        for (int i = 0; i < inv.size() && i < 36; i++) {
            if (inv.get(i).getItem() == net.minecraft.world.item.Items.BOW && bowAt < 0) bowAt = i;
            if (inv.get(i).getItem() instanceof net.minecraft.world.item.ArrowItem) arrows += inv.get(i).getCount();
        }
        var w = a.openWorld();
        boolean sight = w == null || Sight.clear(w, a.x(), a.y() + 1.52, a.z(), enemy.pos().x, enemy.pos().y + 1.0, enemy.pos().z);
        boolean want = bowAt >= 0 && arrows > 0 && !enemy.aiming() && !a.airborne() && sight && d <= 40 && (drawing >= 0 ? d > 5 : d > 8);
        if (!want) { if (drawing >= 0) { a.releaseUse(); drawing = -1; charge = 0; } return false; }
        lower(a);
        if (a.isNavigating()) a.stopNavigation();
        if (drawing < 0) {
            int hb = bowAt < 9 ? bowAt : 5;
            if (bowAt >= 9) a.moveToHotbar(bowAt, hb);
            a.selectHotbar(hb);
            a.useItem();
            drawing = 0;
        }
        // Where it will be when the arrow gets there (two rounds: the flight time depends on the aim point).
        double ax = enemy.pos().x, ay = enemy.pos().y + 1.0, az = enemy.pos().z;
        double sy = a.y() + 1.52;
        int flight = 0;
        double[] shot = null;
        for (int k = 0; k < 2; k++) {
            shot = aim(Math.hypot(ax - a.x(), az - a.z()), ay - sy);
            flight = (int) shot[1];
            ax = enemy.pos().x + evx * flight; ay = enemy.pos().y + 1.0 + Math.max(-0.2, Math.min(0.2, evy)) * flight; az = enemy.pos().z + evz * flight;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-(ax - a.x()), az - a.z()));
        a.look(yaw, (float) -Math.toDegrees(shot[0]));
        tactic = "drawing the bow at " + enemy.name();
        if (++drawing >= 21) {
            a.releaseUse();
            drawing = -1;
            shots++;
            charge = 0;
            dev.agentnet.AgentNet.LOG.info("[{}] arrow {} at {}: {} blocks, {} degrees up, {} ticks of flight, led by {} blocks", a.name(), shots, enemy.name(),
                    String.format("%.1f", d), String.format("%.1f", Math.toDegrees(shot[0])), flight, String.format("%.1f", Math.hypot(evx, evz) * flight));
        }
        return true;
    }

    /** The angle up (radians) a full-draw arrow needs to pass through a point this far off and this much higher, and its flight time in ticks. */
    static double[] aim(double dist, double rise) {
        double lo = -1.2, hi = 0.9;
        int ticks = 1;
        for (int k = 0; k < 22; k++) {
            double mid = (lo + hi) / 2, vx = 3.0 * Math.cos(mid), vy = 3.0 * Math.sin(mid), x = 0, y = 0;
            int t = 0;
            while (x + vx < dist && t < 200) { x += vx; y += vy; vx *= 0.99; vy = vy * 0.99 - 0.05; t++; }
            y += vy * (dist - x) / Math.max(0.01, vx); // the last part of a tick
            ticks = t + 1;
            if (y < rise) lo = mid; else hi = mid;
        }
        return new double[]{(lo + hi) / 2, ticks};
    }

    // ------------------------------------------------------------------ step 6: creepers
    //
    // Vanilla (SwellGoal, Creeper): the fuse starts when the creeper is within 3 blocks of its target and sees it, runs
    // 30 ticks, and stops when the target is more than 7 blocks off (or out of sight). The blast reaches about 6 blocks;
    // a raised shield blocks it from the front.
    //  - With a bow: shot from over 8 blocks (step 5); when it gets within 6, back to 10 blocks and shoot again.
    //  - With a blade that kills it in two critical hits (20 health: 7 damage and up): fought like anything else. Two
    //    full-charge swings take about 21 ticks from when it comes into reach, inside the 30 of its fuse.
    //  - Otherwise: one hit, and when it hisses, away past 7 blocks until the fuse is out, then in again.
    //  - Caught beside a hissing one: the shield up toward it.

    private boolean creeperBack, plainHits;
    private int swingsAtApproach;
    private int fleeAt = -100;

    private boolean creeper(AgentHandle a, Seen c) {
        double d = c.distance();
        List<ItemStack> inv = a.inventory();
        boolean hasBow = false, hasArrow = false;
        for (int i = 0; i < inv.size() && i < 36; i++) { if (inv.get(i).getItem() == net.minecraft.world.item.Items.BOW) hasBow = true; if (inv.get(i).getItem() instanceof net.minecraft.world.item.ArrowItem) hasArrow = true; }
        boolean archer = hasBow && hasArrow;
        int w = bestWeapon(a);
        double crit = (w >= 0 ? damage(inv.get(w)) : 1.0) * 1.5;
        float hp = a.healthOf(c.id());
        boolean twoHits = crit * 2 >= (Float.isNaN(hp) ? 20 : hp), oneHit = crit >= (Float.isNaN(hp) ? 20 : hp);
        if (creeperBack && (archer ? d >= 10 : d >= 8 && !c.swelling())) { creeperBack = false; swingsAtApproach = swings; if (a.isNavigating()) a.stopNavigation(); }
        plainHits = !oneHit && crit < (Float.isNaN(hp) ? 20 : hp); // no jump against a creeper unless that hit kills: the 7 ticks of a jump are a quarter of its fuse
        if (!creeperBack) {
            if (archer && d < 6) creeperBack = true;
            // Hissing and not dead after the hit just made: away, whatever the blade. (The plan to land two critical hits inside
            // the fuse failed in the test: the first one knocks it out of reach and the second comes too late.) One swing
            // is allowed first: when it kills, or when none has been made on this approach yet and it is ready now.
            else if (!archer && c.swelling() && !(oneHit || swings == swingsAtApproach && charge >= Math.ceil(w >= 0 ? chargeTicks(inv.get(w)) : 5) - 1 && eyeToBox(a, c) <= REACH)) creeperBack = true;
            if (creeperBack) a.log(String.format("§ea creeper %.0f blocks off%s: backing away", d, c.swelling() ? ", hissing" : ""));
        }
        if (!creeperBack) return false; // the bow (far off) or the blade deal with it
        if (drawing >= 0) { a.releaseUse(); drawing = -1; }
        if (c.swelling() && d < 4.5) raise(a); else lower(a);
        a.faceWhileMoving(c.pos().x, c.pos().y + 1.0, c.pos().z);
        tactic = c.swelling() ? "away from a hissing creeper" : "getting distance from a creeper";
        if (ticks - fleeAt >= 10 || !a.isNavigating()) { fleeAt = ticks; away(a, c, 12); }
        return true;
    }

    /** A trip about this many blocks straight away from it, or the nearest direction to that with ground to stand on. */
    private static void away(AgentHandle a, Seen from, int blocks) {
        var w = a.openWorld();
        double back = Math.atan2(a.z() - from.pos().z, a.x() - from.pos().x);
        for (int k = 0; k < 9; k++) {
            double ang = back + (k == 0 ? 0 : (k % 2 == 1 ? 1 : -1) * ((k + 1) / 2) * Math.PI / 6);
            int gx = (int) Math.floor(a.x() + Math.cos(ang) * blocks), gz = (int) Math.floor(a.z() + Math.sin(ang) * blocks), y = (int) Math.floor(a.y() + 0.2);
            if (w == null) { a.navigate(gx, y, gz, 2); return; }
            for (int dy = 0; dy <= 5; dy++) for (int s : dy == 0 ? new int[]{0} : new int[]{dy, -dy}) {
                var ground = w.get(gx, y + s - 1, gz); var feet = w.get(gx, y + s, gz); var head = w.get(gx, y + s + 1, gz);
                if (ground == null || feet == null || head == null) continue;
                if (Tunnel.solid(ground) && Tunnel.passable(feet) && Tunnel.passable(head)) { a.navigate(gx, y + s, gz, 2); return; }
            }
        }
    }

    private final ElytraMace air = new ElytraMace();

    /** In the middle of a move that must not be cut short (the elytra mace): the warrior keeps its hands off and keeps the target. */
    boolean busy() { return air.active(); }
    int targetId() { return target; }
    boolean blocksClutch(AgentHandle a) { return air.active() && air.blocksClutch(a); }

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
        if (drawing >= 0) { a.releaseUse(); drawing = -1; }
        if (shots > 0) { a.log(shots + " arrow(s) shot"); shots = 0; }
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
        if (enemy.id() != target) { target = enemy.id(); chasedTo = null; swingsAtApproach = swings; creeperBack = false; }
        // Did the last swing count? (The server's damage event names us as the source.)
        if (checkAt >= 0 && ticks >= checkAt) {
            if (a.hitRecently(enemy.id(), 600)) landed++;
            float now = a.healthOf(enemy.id());
            if (!Float.isNaN(hpBefore) && !Float.isNaN(now)) dev.agentnet.AgentNet.LOG.info("[{}] that swing took {} off {} ({} -> {})", a.name(), String.format("%.1f", hpBefore - now), enemy.name(), String.format("%.1f", hpBefore), String.format("%.1f", now));
            checkAt = -1;
        }

        // Step 7, the mace from the air. Vanilla (MaceItem): a smash needs a fall of over 1.5 blocks and no gliding; it adds
        // 4 a block for the first 3 blocks fallen, 2 a block up to 8, 1 a block after. A jump is 1.25 blocks, so on the
        // ground a mace is only a slow club: its use is from the elytra (ElytraMace: up on a firework, chestplate on at
        // the top, elytra again, down on a firework, chestplate and mace).
        if (air.cooldown > 0) air.cooldown--;
        if (air.active()) { tactic = air.tactic; if (air.tick(a, enemy)) return; }
        float myHp = a.health(), myMax = Math.max(1f, a.maxHealth());
        if ((enemy.player() || CombatSettings.aerialOnMobs && enemy.distance() >= 8) && enemy.distance() <= 40 && air.cooldown == 0 && !a.airborne()
                && (Float.isNaN(myHp) || myHp / myMax > 0.5f) && ElytraMace.ready(a) && ElytraMace.openAbove(a, enemy.pos())) {
            lower(a);
            if (drawing >= 0) { a.releaseUse(); drawing = -1; }
            air.start(a);
            charge = 0;
            return;
        }
        if (enemy.name().equals("creeper") && creeper(a, enemy)) return; // step 6
        if (bow(a, enemy)) return; // step 5: arrows while it is far off

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
        // Its shield is up again though the axe just went in: that swing was not blocked (a shield only blocks from 5 ticks
        // after it is raised, so the axe did plain damage and knocked nothing out). Not waiting out 5 s: the axe again.
        if (enemy.blocking() && ticks < shieldOutUntil && ticks - breakAt > 8) shieldOutUntil = ticks;
        if (enemy.blocking() && ticks >= shieldOutUntil) {
            int ax = breaker(a);
            if (ax >= 0) {
                lower(a);
                float secs = a.inventory().get(ax).get(DataComponents.WEAPON).disableBlockingForSeconds();
                a.attackWith(enemy.id(), ax);
                charge = 0; // the item changed twice: vanilla starts the charge again
                shieldOutUntil = ticks + (int) (secs * 20) - 4;
                breaks++;
                breakAt = ticks;
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
        boolean canJump = !a.airborne() && !a.riding() && !inWater(a) && !(plainHits && enemy.name().equals("creeper"));
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
