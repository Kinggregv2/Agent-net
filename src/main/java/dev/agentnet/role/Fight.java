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
    boolean busy() { return air.active() || cart.active(); }
    int targetId() { return target; }
    boolean blocksClutch(AgentHandle a) { return air.active() && air.blocksClutch(a); }

    // ------------------------------------------------------------------ step 8: healing and backing off
    //
    // Vanilla: eating or drinking takes 32 ticks, and nothing can be swung or blocked with meanwhile; a golden apple gives
    // 4 absorption and Regeneration II for 5 s (4 health back); a splash potion works the moment it lands.
    //  - potions (Potions.fightTick): healing when hurt, the buffs as a fight starts;
    //  - under half health: a golden apple, where it stands;
    //  - under 30% with nothing to heal with: away from the enemy, shield toward it, ordinary food once it is 6 blocks
    //    off; back into the fight at 60% (or after 20 s).

    private int busyTicks, retreatAt = -1000, appleAt = -1000, ate, topUpAt = -1000;
    private boolean retreating;

    private boolean heal(AgentHandle a, Seen enemy) {
        if (busyTicks > 0) {
            busyTicks--;
            a.faceWhileMoving(enemy.pos().x, enemy.pos().y + 1.0, enemy.pos().z);
            // Eating on the move, away from it: standing still to eat among three husks, it took 9 damage a second and died
            // with the apple in its mouth. (The server does not slow an eating agent down.)
            if ((retreating || enemy.distance() < 7) && (ticks - fleeAt >= 40 || !a.isNavigating())) { fleeAt = ticks; away(a, enemy, 14); }
            charge = 0;
            return true;
        }
        if (a.airborne()) return false;
        float hp = a.health(), max = Math.max(1f, a.maxHealth());
        if (Float.isNaN(hp)) return false;
        int wait = Potions.fightTick(a, enemy.distance(), false);
        if (wait > 0) { lower(a); if (drawing >= 0) { a.releaseUse(); drawing = -1; } busyTicks = wait; tactic = "a potion"; return true; }
        List<ItemStack> inv = a.inventory();
        if (hp / max < 0.5f) {
            int g = -1;
            for (int i = 0; i < inv.size() && i < 36; i++) { String id = Gear.id(inv.get(i)); if (id.equals("minecraft:enchanted_golden_apple") || id.equals("minecraft:golden_apple") && g < 0) g = i; }
            // Not on top of the last one: its Regeneration II runs 5 s. (It ate four in six seconds.) Unless it is nearly dead.
            if (g >= 0 && ticks - appleAt < 110 && hp / max >= 0.2f) g = -1;
            if (g >= 0) { appleAt = ticks; eat(a, g); a.log(String.format("§e%.0f of %.0f health: a golden apple", hp, max)); tactic = "eating a golden apple"; return true; }
        }
        // Topping up in the gaps: hurt, the hunger bar not full (sprinting drains it) and the enemy just out of reach (its
        // own knockback hit or ours): ordinary food now, eaten on the way back, so regeneration keeps running. Against VexBot
        // with swords the agent had it at 5.9 HP while it had 11.4; VexBot ate its steak in the gaps and came back to 11,
        // the agent never ate and lost (bench 2026-11-23, round 1). Steak gives 12.8 saturation: a heart a second for 12 s.
        if (!retreating && a.food() < 20 && hp < max - 3f && enemy.distance() > 3.6 && ticks - topUpAt > 50) {
            int f = -1;
            for (int i = 0; i < inv.size() && i < 36; i++) {
                ItemStack s = inv.get(i);
                if (!Gear.isFood(s) || Gear.id(s).contains("golden_apple") || Gear.id(s).equals("minecraft:chorus_fruit")) continue;
                if (f < 0 || Gear.nutrition(s) > Gear.nutrition(inv.get(f))) f = i;
            }
            if (f >= 0) { topUpAt = ticks; eat(a, f); ate++; tactic = "eating in the gap (" + Math.round(hp) + " HP, hunger " + a.food() + ")"; return true; }
        }
        if (!retreating && hp / max < 0.3f) { retreating = true; retreatAt = ticks; a.log(String.format("§e%.0f of %.0f health and nothing to heal with: backing off from %s", hp, max, enemy.name())); }
        if (!retreating) return false;
        if (hp / max >= 0.6f || ticks - retreatAt > 400) { retreating = false; if (a.isNavigating()) a.stopNavigation(); a.log(String.format("back into the fight at %.0f health (ate %d time(s))", hp, ate)); ate = 0; return false; }
        if (drawing >= 0) { a.releaseUse(); drawing = -1; }
        tactic = "backing off from " + enemy.name();
        a.faceWhileMoving(enemy.pos().x, enemy.pos().y + 1.0, enemy.pos().z);
        // One trip, walked to its end: planned afresh every half second, it stopped and started and was hit from behind.
        // A new one when it has arrived, or every 2 s (the enemy has moved).
        if (ticks - fleeAt >= 40 || !a.isNavigating()) { fleeAt = ticks; away(a, enemy, 14); }
        // Out of its reach is enough (a zombie reaches about 2 blocks, and walks slower than an agent): waiting for 6 blocks,
        // it never got to eat with three husks behind it.
        if (enemy.distance() > 3.5) {
            lower(a);
            int f = -1;
            for (int i = 0; i < inv.size() && i < 36; i++) if (Gear.isFood(inv.get(i)) && (f < 0 || Gear.nutrition(inv.get(i)) > Gear.nutrition(inv.get(f)))) f = i;
            // Vanilla: nothing but a golden apple can be eaten on a full hunger bar. Then the health comes back by itself
            // (1 every half second while the bar is full and has saturation): it only has to stay out of reach.
            if (f >= 0 && a.food() < 20) { eat(a, f); ate++; tactic = "eating, away from " + enemy.name(); }
            else tactic = "out of reach of " + enemy.name() + ", healing";
        } else raise(a);
        return true;
    }

    // ------------------------------------------------------------------ dodging a mace from above
    //
    // VexBot's dives (a wind charge or a pearl up, the mace down) took 7 and 8 a hit, and the agent stood under them (bench
    // 2026-11-24, mace and spear mace kits, 0-5). A smash needs it within reach at the moment of the hit: when the enemy is
    // 2.5+ blocks up, coming down, a mace in hand and nearly over it, it steps out sideways, away from where it lands.
    static final boolean TRACE_ENEMY = Boolean.parseBoolean(System.getProperty("agentnet.traceEnemy", "true"));
    private int dodgeUntil = -1;
    private boolean dodge(AgentHandle a, Seen e) {
        double up = e.pos().y - a.y(), hz = Math.hypot(e.pos().x - a.x(), e.pos().z - a.z());
        boolean mace = e.mainHand().getItem() == net.minecraft.world.item.Items.MACE;
        // From the top of its jump on (not once it is already coming down: walking 8 ticks moved it 2 blocks and the smash
        // still landed, bench 2026-11-24), and every tick until it is down: out of reach of where it lands.
        if (!mace || up < 1.5 || enemyVel.y > 0.08 || hz > 6) { dodgeUntil = -1; return false; }
        // Where it comes down (its fall so far, led), and the way out: straight away from that, sideways if it is right on top.
        double t = Math.min(12, up / Math.max(0.3, -enemyVel.y));
        double lx = e.pos().x + enemyVel.x * t, lz = e.pos().z + enemyVel.z * t;
        double ax = a.x() - lx, az = a.z() - lz, n = Math.hypot(ax, az);
        if (n < 0.3) { ax = -(e.pos().z - a.z()); az = e.pos().x - a.x(); n = Math.max(0.01, Math.hypot(ax, az)); }
        int gx = (int) Math.floor(a.x() + ax / n * 4), gz = (int) Math.floor(a.z() + az / n * 4);
        // A wind charge into the ground between it and where the mace comes down: the blast throws it several blocks away at
        // once. Walking away was too slow against a dive from 29 blocks up that steered onto it (bench 2026-11-24: a
        // totem gone from full health in one smash). When the dive is close: 3 to 14 blocks up, nearly over it.
        if (windCd == 0 && !a.airborne() && up >= 3 && up <= 22 && hz < 4.5 && enemyVel.y < -0.2) {
            int wc = -1; List<ItemStack> inv = a.inventory();
            for (int i = 0; i < 9 && i < inv.size(); i++) if (inv.get(i).getItem() == net.minecraft.world.item.Items.WIND_CHARGE) { wc = i; break; }
            if (wc >= 0) {
                float yaw = (float) Math.toDegrees(Math.atan2(-(lx - a.x()), lz - a.z())); // toward where it lands: the push is the other way
                // 45 degrees down: the charge bursts on the ground about 1.6 blocks off, level with its legs, and the push is mostly
                // sideways. At 70 it burst at its feet and threw it straight up (1.0 up, 0.2 across) into the dive.
                a.useItemWith(wc, yaw, 45f);
                windCd = 30; dodgeUntil = ticks;
                tactic = "wind charge away from " + e.name() + "'s mace";
                dev.agentnet.AgentNet.LOG.info("[{}] wind-charge dodge: {} is {} up, {} over", a.name(), e.name(), String.format("%.1f", up), String.format("%.1f", hz));
                return true;
            }
        }
        if (a.isNavigating()) a.stopNavigation();
        a.stepToward(gx + 0.5, gz + 0.5);
        boolean first = dodgeUntil < 0; dodgeUntil = ticks;
        if (!first) { tactic = "out from under " + e.name() + "'s mace"; return true; }
        tactic = "out from under " + e.name() + "'s mace";
        dev.agentnet.AgentNet.LOG.info("[{}] dodging {}'s mace: it is {} up, {} over, falling {} a tick", a.name(), e.name(), String.format("%.1f", up), String.format("%.1f", hz), String.format("%.2f", enemyVel.y));
        return true;
    }

    private void eat(AgentHandle a, int slot) {
        lower(a);
        if (drawing >= 0) { a.releaseUse(); drawing = -1; }
        int hb = slot < 9 ? slot : 7;
        if (slot >= 9) a.moveToHotbar(slot, hb);
        a.selectHotbar(hb);
        a.useItem();
        busyTicks = 34;
        charge = 0;
    }

    // ------------------------------------------------------------------ step 9a: wind charge and mace
    //
    // A wind charge thrown straight down at its own feet throws it up (the server sends the push; the agent's air
    // physics fly it). From the top of that, the fall is well over the 1.5 blocks a mace smash needs. Vanilla also
    // forgives the fall damage of a wind-charge jump down to the height it started from, so a miss costs nothing.
    // Without an elytra this is how the mace gets used: a few blocks from the enemy, up, over it, down on it.

    private int windAt = -1000, windCd, windSmashes, maceHeldAt = -1000;
    private boolean windStruck, windDashed;
    private double windPeak = -1e9;

    /** The enemy's movement a tick (smoothed), for leading jumps and dives at it. */
    private net.minecraft.world.phys.Vec3 enemyVel = net.minecraft.world.phys.Vec3.ZERO, lastEnemyPos;
    private int lastEnemyId = -1;
    private void trackEnemy(Seen enemy) {
        if (enemy.id() != lastEnemyId || lastEnemyPos == null) { lastEnemyId = enemy.id(); lastEnemyPos = enemy.pos(); enemyVel = net.minecraft.world.phys.Vec3.ZERO; return; }
        net.minecraft.world.phys.Vec3 v = enemy.pos().subtract(lastEnemyPos);
        if (v.lengthSqr() > 4) v = net.minecraft.world.phys.Vec3.ZERO; // a teleport (a pearl): no speed to lead by
        enemyVel = enemyVel.scale(0.5).add(v.scale(0.5));
        lastEnemyPos = enemy.pos();
    }

    private boolean windSmash(AgentHandle a, Seen enemy) {
        List<ItemStack> inv = a.inventory();
        int mace = -1, wind = -1;
        for (int i = 0; i < inv.size() && i < 36; i++) { if (inv.get(i).getItem() == net.minecraft.world.item.Items.MACE && mace < 0) mace = i; if (inv.get(i).getItem() == net.minecraft.world.item.Items.WIND_CHARGE && wind < 0) wind = i; }
        if (ticks - windAt < 100) { // in the move
            if (!a.airborne()) {
                if (ticks - windAt <= 8) return true; // the push is on its way
                dev.agentnet.AgentNet.LOG.info("[{}] wind-charge jump over: {}", a.name(), windStruck ? "the smash was swung" : "no swing (it never came into reach on the way down)");
                windAt = -1000;
                return false;
            }
            // Toward where it will be when this fall reaches it, not where it is: it walks on meanwhile and the jump came down
            // beside it (bench 2026-11-24, mace kit vs VexBot: "never came into reach on the way down").
            double above = Math.max(0, a.y() - enemy.pos().y), vy = a.verticalSpeed();
            double land = (vy + Math.sqrt(Math.max(0, vy * vy + 2 * 0.08 * above))) / 0.08; // ticks until it is down at its height
            land = Math.min(land, 20);
            double px = enemy.pos().x + enemyVel.x * land, pz = enemy.pos().z + enemyVel.z * land;
            a.steer(px - a.x(), pz - a.z());
            // Spear dash at the top (VexBot's "speardash"): a Lunge spear pushes it flat toward the target from the apex, so a
            // jump that went up beside it still comes down on it. Once a jump.
            double hz = Math.hypot(px - a.x(), pz - a.z());
            if (!windDashed && Math.abs(vy) < 0.12 && hz > 2.5 && hz < 12) {
                int sp = -1, lv = 0;
                for (int i = 0; i < 9; i++) { int l = Gear.id(inv.get(i)).endsWith("_spear") ? Gear.level(inv.get(i), net.minecraft.world.item.enchantment.Enchantments.LUNGE) : 0; if (l > lv) { lv = l; sp = i; } }
                if (sp >= 0) {
                    windDashed = true;
                    int back = a.selectedSlot();
                    a.look((float) Math.toDegrees(Math.atan2(-(px - a.x()), pz - a.z())), 0f);
                    a.selectHotbar(sp); a.stab(); a.swingArm(); a.selectHotbar(back);
                    dev.agentnet.AgentNet.LOG.info("[{}] spear dash at the top of the jump: {} blocks flat to {}", a.name(), String.format("%.1f", hz), enemy.name());
                }
            }
            a.faceWhileMoving(enemy.pos().x, enemy.pos().y + 1.0, enemy.pos().z);
            if (mace >= 0 && mace < 9) a.selectHotbar(mace);
            // Its own highest point since the jump (a pearl teleport is no jump: the air peak was still the old one), and 2.5
            // blocks of fall before the swing: the server runs a couple of blocks behind, and at "1.9 fallen" it had 0.02.
            windPeak = Math.max(windPeak, a.y());
            double fallen = windPeak - a.y();
            tactic = String.format("wind-charge jump over %s (%.1f up)", enemy.name(), a.y() - enemy.pos().y);
            // As late in the fall as it can: the mace adds damage for every block fallen, and swinging the moment it came into
            // reach smashed after 2 of 6 blocks (bench 2026-11-24: 5 of 8 smashes "after 2.0 blocks"). It waits while it is still
            // over 2 blocks above the target with reach to spare (half a block a tick at this speed), then swings.
            double rch = eyeToBox(a, enemy);
            boolean wait = a.y() - enemy.pos().y > 2.0 && rch < REACH - Math.max(0.5, -a.verticalSpeed());
            if (!windStruck && !wait && mace >= 0 && a.verticalSpeed() < -0.1 && fallen > 2.5 && rch <= REACH && Sight.canHit(a, enemy)) {
                a.attack(enemy.id());
                windStruck = true;
                windSmashes++;
                swings++;
                checkAt = ticks + 8;
                hpBefore = a.healthOf(enemy.id());
                charge = 0;
                dev.agentnet.AgentNet.LOG.info("[{}] wind-charge smash on {} after {} blocks of fall, falling {} a tick; the server has: {}", a.name(), enemy.name(), String.format("%.1f", fallen), String.format("%.2f", a.verticalSpeed()), serverSide(a));
            }
            return true;
        }
        double d = enemy.distance();
        if (mace < 0 || wind < 0 || windCd > 0 || a.airborne() || a.riding() || air.active() || ElytraMace.ready(a)) return false;
        if (!(enemy.player() || CombatSettings.aerialOnMobs) || enemy.name().equals("creeper") || d < 2.0 || d > 8.0 || !ElytraMace.openAbove(a, enemy.pos())) return false;
        // Not at one in the air (its own launch, a jump): it is somewhere else by the time this jump comes down.
        if (Math.abs(enemyVel.y) > 0.1) return false;
        // The mace in hand first, charging (a mace takes 33 ticks to charge; selected only up in the air, the smashes went in
        // at 55-67% charge, and Density is scaled by the charge: bench 2026-11-24). Then the wind charge from its slot and
        // straight back to the mace in the same tick, so the mace keeps its charge.
        lower(a);
        if (drawing >= 0) { a.releaseUse(); drawing = -1; }
        if (mace >= 9) { a.moveToHotbar(mace, 2); return true; }
        int hb = wind < 9 ? wind : 6;
        if (wind >= 9) { a.moveToHotbar(wind, hb); return true; }
        if (a.selectedSlot() != mace) { a.selectHotbar(mace); maceHeldAt = ticks; charge = 0; }
        // (No charging before the jump: in 26.3 a wind charge use swings the arm, and a swing resets the attack charge
        // (ServerPlayer.swingAndResetAttackStrength), whatever is held. The charge at the smash is the flight time; bench
        // 2026-11-24: 0.40 at the launch after 14 ticks of holding, 0.67 at the smash 22 ticks later.)
        if (a.isNavigating()) a.stopNavigation();
        a.useItemWith(hb, a.yaw(), 90f); // straight down at its own feet
        windPeak = a.y();
        windDashed = false;
        dev.agentnet.AgentNet.LOG.info("[{}] wind-charge launch: mace held {} ticks; the server has: {}", a.name(), ticks - maceHeldAt, serverSide(a));
        windAt = ticks; windCd = 60; windStruck = false;
        a.log("§cwind charge at its feet: up and over " + enemy.name() + " with the mace");
        tactic = "wind-charge jump at " + enemy.name();
        return true;
    }

    // ------------------------------------------------------------------ step 9a+: the pearl catch
    //
    // VexBot's way up (traced from the server, 2026-11-24; the user: "the bot can ender pearl wind charge up"): a pearl
    // thrown straight up, then a wind charge straight up after it. A wind charge does not fall; the pearl slows down; the
    // charge catches it, the pearl hits it and takes its thrower up there (vanilla: teleported where the pearl hit, no
    // speed, fall distance reset). With the charge 2 ticks behind, they meet about 20 blocks up 16-19 ticks later. From
    // there, the mace: the wind-charge smash's air part steers it down onto the target and swings at the end of the fall.
    // Both thrown standing still (a projectile takes on its thrower's speed and they would miss each other sideways).
    private int pcAt = -1000, pcCd;
    private double pcY;
    private boolean pearlCatch(AgentHandle a, Seen enemy) {
        if (pcCd > 0) pcCd--;
        List<ItemStack> inv = a.inventory();
        int mace = -1, wind = -1, pearl = -1;
        for (int i = 0; i < 9 && i < inv.size(); i++) {
            var it = inv.get(i).getItem();
            if (it == net.minecraft.world.item.Items.MACE && mace < 0) mace = i;
            if (it == net.minecraft.world.item.Items.WIND_CHARGE && wind < 0) wind = i;
            if (it == net.minecraft.world.item.Items.ENDER_PEARL && pearl < 0) pearl = i;
        }
        int since = ticks - pcAt;
        if (since <= 45) {
            if (since == 5 && wind >= 0) { a.useItemWith(wind, a.yaw(), -90f); tactic = "pearl catch: the wind charge up after the pearl"; return true; }
            if (since == 6 && mace >= 0) { a.selectHotbar(mace); charge = 0; }
            // Up there: the pearl took it (a jump of over 8 blocks in a tick). The wind-charge smash's air part from here.
            if (a.y() > pcY + 8) {
                pcAt = -1000; windAt = ticks; windStruck = false; windDashed = true; windPeak = a.y();
                dev.agentnet.AgentNet.LOG.info("[{}] pearl catch: up {} blocks, {} below", a.name(), String.format("%.1f", a.y() - pcY), enemy.name());
                a.log("§cpearl catch: up " + Math.round(a.y() - pcY) + " blocks over " + enemy.name());
                return false;
            }
            if (since < 45) { if (a.isNavigating()) a.stopNavigation(); a.faceWhileMoving(enemy.pos().x, enemy.pos().y + 1.0, enemy.pos().z); tactic = "pearl catch: waiting for the pearl"; return true; }
            pcAt = -1000;
            dev.agentnet.AgentNet.LOG.info("[{}] pearl catch: the charge missed the pearl", a.name());
            return false;
        }
        if (mace < 0 || wind < 0 || pearl < 0 || pcCd > 0 || a.airborne() || a.riding() || air.active() || ElytraMace.ready(a) || ticks - windAt < 100) return false;
        if (!enemy.player() || enemy.distance() > 9 || Math.abs(enemy.pos().y - a.y()) > 3 || !ElytraMace.openAbove(a, new net.minecraft.world.phys.Vec3(a.x(), a.y(), a.z()))) return false;
        float hp = a.health(), max = Math.max(1f, a.maxHealth());
        if (!Float.isNaN(hp) && hp < max * 0.5f) return false; // the pearl costs 5 (less in Feather Falling boots)
        if (a.isNavigating()) a.stopNavigation();
        lower(a);
        a.useItemWith(pearl, a.yaw(), -90f);
        pcAt = ticks; pcY = a.y(); pcCd = 200;
        tactic = "pearl catch: a pearl straight up";
        dev.agentnet.AgentNet.LOG.info("[{}] pearl catch: pearl up, {} is {} off", a.name(), enemy.name(), String.format("%.1f", enemy.distance()));
        return true;
    }

    /** On your own world: what the server itself has for this agent right now (fall distance, height, on the ground). For the logs of a test. */
    static String serverSide(AgentHandle a) {
        try {
            var srv = net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();
            if (srv == null) return "(not your own world)";
            var p = srv.getPlayerList().getPlayerByName(a.name());
            if (p == null) return "(no such player)";
            return String.format("fall distance %.2f, y %.2f (ours %.2f), on ground %s, gliding %s, holding %s, charge %.2f, sprinting %s, vy %.2f, damage dealt so far %.1f", p.fallDistance, p.getY(), a.y(), p.onGround(), p.isFallFlying(), Gear.id(p.getMainHandItem()).replace("minecraft:", ""),
                    p.getAttackStrengthScale(0.5f), p.isSprinting(), p.getDeltaMovement().y,
                    p.getStats().getValue(net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.DAMAGE_DEALT)) / 10.0);
        } catch (RuntimeException e) { return "(" + e + ")"; }
    }

    private final CartPvp cart = new CartPvp();
    private final CrystalPvp crystal = new CrystalPvp();

    // ------------------------------------------------------------------ step 9b: the boat trap
    //
    // Vanilla: an enderman sitting in a boat cannot teleport out of it, and walks into one put in its way. So: a boat
    // on the ground between it and us while it comes; then it is hit like anything else, and it stays put.

    private int boatAt = -1000;

    private boolean boatTrap(AgentHandle a, Seen e) {
        if (!e.angry() || ticks - boatAt < 160 || a.airborne()) return false;
        List<ItemStack> inv = a.inventory();
        int boat = -1;
        for (int i = 0; i < inv.size() && i < 36 && boat < 0; i++) { String id = Gear.id(inv.get(i)); if (id.endsWith("_boat") || id.endsWith("_raft")) boat = i; }
        double d = e.distance();
        var w = a.openWorld();
        if (boat < 0 || d > 7 || d < 2 || w == null) return false;
        double dx = e.pos().x - a.x(), dz = e.pos().z - a.z(), len = Math.max(0.1, Math.hypot(dx, dz));
        int bx = (int) Math.floor(a.x() + dx / len * 1.6), bz = (int) Math.floor(a.z() + dz / len * 1.6), by = (int) Math.floor(a.y() + 0.2);
        var ground = w.get(bx, by - 1, bz); var at = w.get(bx, by, bz);
        if (ground == null || at == null || !Tunnel.solid(ground) || !Tunnel.passable(at)) return false;
        lower(a);
        if (a.isNavigating()) a.stopNavigation();
        int hb = boat < 9 ? boat : 8;
        if (boat >= 9) a.moveToHotbar(boat, hb);
        a.selectHotbar(hb);
        a.lookAt(bx + 0.5, by, bz + 0.5);
        a.useItemOn(bx, by - 1, bz, net.minecraft.core.Direction.UP);
        boatAt = ticks;
        charge = 0;
        tactic = "a boat in the enderman's way";
        a.log("put a boat down between it and the " + e.name());
        return true;
    }

    // ------------------------------------------------------------------ step 9c: the Lunge spear
    //
    // A jab with a spear that has Lunge throws its holder forward along where it looks (and costs hunger). Swapped to
    // for the jab and straight back: a dash at an enemy 6 to 14 blocks off that is being chased.

    private int lungeAt = -1000;

    private boolean lunge(AgentHandle a, Seen e) {
        double d = e.distance();
        if (d < 6 || d > 14 || ticks - lungeAt < 20 || a.airborne() || a.riding() || a.food() <= 6) return false;
        List<ItemStack> inv = a.inventory();
        int spear = -1, lvl = 0;
        for (int i = 0; i < 9; i++) {
            int l = Gear.id(inv.get(i)).endsWith("_spear") ? Gear.level(inv.get(i), net.minecraft.world.item.enchantment.Enchantments.LUNGE) : 0;
            if (l > lvl) { lvl = l; spear = i; }
        }
        if (spear < 0) return false;
        lower(a);
        int back = a.selectedSlot();
        a.look((float) Math.toDegrees(Math.atan2(-(e.pos().x - a.x()), e.pos().z - a.z())), 0f); // the push goes where it looks, flat
        a.selectHotbar(spear);
        a.stab();
        a.swingArm();
        a.selectHotbar(back);
        lungeAt = ticks;
        charge = 0;
        lunges++;
        tactic = "a lunge at " + e.name();
        dev.agentnet.AgentNet.LOG.info("[{}] lunge {} at {} from {} blocks", a.name(), lunges, e.name(), String.format("%.1f", d));
        return true;
    }
    private int lunges;

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
        retreating = false; busyTicks = 0;
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

        trackEnemy(enemy);
        // Study (-Dagentnet.traceEnemy or a mace kit test): the enemy's height and the pearls and wind charges by it, every tick it
        // is rising fast or high: how VexBot gets 29 blocks up with wind charges and pearls.
        if (TRACE_ENEMY && (enemyVel.y > 0.3 || enemy.pos().y - a.y() > 6)) {
            StringBuilder sb = new StringBuilder();
            try { // the server's own projectiles (the agent does not track them): on your own world only
                var srv = net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();
                var sp = srv == null ? null : srv.getPlayerList().getPlayerByName(enemy.name());
                if (sp != null) for (var en : sp.level().getEntities((net.minecraft.world.entity.Entity) null, sp.getBoundingBox().inflate(48), x -> x instanceof net.minecraft.world.entity.projectile.Projectile)) {
                    var pr = (net.minecraft.world.entity.projectile.Projectile) en;
                    sb.append(String.format(" %s%s@(%.1f,%.1f,%.1f v%.2f)", net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(en.getType()).getPath(), pr.getOwner() == sp ? "(its)" : "",
                            en.getX() - sp.getX(), en.getY() - sp.getY(), en.getZ() - sp.getZ(), en.getDeltaMovement().y));
                }
                if (sp != null) sb.append(String.format(" | server: y %.1f, vy %.2f, vehicle %s, fall %.1f", sp.getY() - a.y(), sp.getDeltaMovement().y, sp.getVehicle() == null ? "-" : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(sp.getVehicle().getType()).getPath(), sp.fallDistance));
            } catch (RuntimeException ex) { sb.append(" (").append(ex).append(")"); }
            dev.agentnet.AgentNet.LOG.info("[{}] trace {}: y {} over us, vy {}, holding {}{}", a.name(), enemy.name(), String.format("%.1f", enemy.pos().y - a.y()), String.format("%.2f", enemyVel.y), Gear.id(enemy.mainHand()).replace("minecraft:", ""), sb);
        }
        if (windCd > 0) windCd--; // every tick (only in windSmash it stood still while dodging, and the dodge never fired)
        if (!air.active() && dodge(a, enemy)) return; // a mace coming down on it: out from under
        if (!air.active() && heal(a, enemy)) return; // step 8

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
        // Step 9d, the TNT minecart (CartPvp: rail, cart, a burning bolt): against players, with the whole kit in the pack.
        if (cart.cooldown > 0) cart.cooldown--;
        if (cart.active()) { tactic = cart.tactic; if (cart.tick(a, enemy)) return; }
        if (enemy.player() && !cart.active() && ticks % 100 == 0 && (CartPvp.railSlot(a) >= 0 || CartPvp.cartSlot(a) >= 0))
            dev.agentnet.AgentNet.LOG.info("[{}] cart check: cooldown {}, airborne {}, distance {}, ready {}, raining {}, rail {}, cart {}, crossbow {}, flint {}, flame bow {}, arrows {}, pack {}", a.name(), cart.cooldown, a.airborne(), String.format("%.1f", enemy.distance()), CartPvp.ready(a), a.raining(), CartPvp.railSlot(a), CartPvp.cartSlot(a), CartPvp.crossbowSlot(a), CartPvp.flintSlot(a), CartPvp.flameBow(a), War.have(a, "minecraft:arrow"), a.inventory().stream().filter(s -> !s.isEmpty()).map(s -> Gear.id(s).replace("minecraft:", "") + "x" + s.getCount()).toList());
        if (enemy.player() && cart.cooldown == 0 && !a.airborne() && !air.active() && enemy.distance() <= 20 && CartPvp.ready(a)) {
            lower(a);
            if (drawing >= 0) { a.releaseUse(); drawing = -1; }
            cart.start(a);
            charge = 0;
            return;
        }
        // Crystal PvP: with end crystals in the pack, against a player in reach (CrystalPvp).
        if (enemy.player() && CrystalPvp.ready(a) && !a.airborne()) { lower(a); if (crystal.tick(a, enemy)) { tactic = crystal.tactic; charge = 0; return; } }
        if (pearlCatch(a, enemy)) return; // step 9a+: pearl and wind charge up, then the mace from 20 blocks
        if (windSmash(a, enemy)) return; // step 9a
        if (enemy.name().equals("enderman") && enemy.vehicle() < 0 && boatTrap(a, enemy)) return; // step 9b
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
            if (!walled && lunge(a, enemy)) return; // step 9c
            // Jumping in: just out of reach with the swing nearly charged, the jump on the way, so the hit comes on the way
            // down as it arrives (knock-back puts the enemy 3 blocks off after every hit; walking in and only then jumping
            // gave it half a second to step away again).
            if (!walled && !a.airborne() && !a.riding() && !inWater(a) && reach <= REACH + 1.8 && (int) Math.ceil(full) - charge <= JUMP_LEAD && ticks - jumpedAt > 4) {
                a.jump(); jumpedAt = ticks; tactic = "jumping in on " + enemy.name();
            }
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
            // Crit spam (the user, 2026-11-23: "Fsword is horrible at critspaming"): the next jump the moment it lands, so jump
            // after jump lines up with the sword's 12.5-tick charge. Waiting 14 ticks after each jump made it one crit in 16.
            if (canJump && left <= JUMP_LEAD && ticks - jumpedAt > 4) { a.jump(); jumpedAt = ticks; tactic = "jumping for a critical hit on " + enemy.name(); return; }
            tactic = a.airborne() ? "in the air over " + enemy.name() : "in reach of " + enemy.name() + ", charging the swing";
            return;
        }
        // Charged. On the ground with no jump under way (it just got here): the jump first, the hit on the way down.
        if (canJump && ticks - jumpedAt > 4) { a.jump(); jumpedAt = ticks; tactic = "jumping for a critical hit on " + enemy.name(); return; }
        // After the jump: the swing only on the way DOWN (the user: "he's supposed to hit when falling down not jumping
        // up"), and a tick into the fall, so the server has a falling move from us before the hit (its fall distance must
        // be over 0). Leaving the ground takes a tick, so "not in the air yet" right after a jump is waited out too.
        // Only when the jump is over and it stands again (a low roof, a ledge) does it swing from the ground.
        boolean falling = a.airborne() && a.verticalSpeed() < -0.1;
        if (!falling && ticks - jumpedAt <= 12 && (a.airborne() || ticks - jumpedAt <= 3)) { tactic = "in the air over " + enemy.name(); return; }
        boolean crit = falling;
        lower(a); // no swinging with the shield up
        hpBefore = a.healthOf(enemy.id());
        // Breach swap: against armor, the Breach mace swapped in for the hit and the blade straight back, all in one tick
        // (the server reads the held item when the attack packet arrives and resets the charge only at the end of the tick
        // if the item then differs, so the hit has the blade's charge with the mace's damage and Breach).
        int breach = breachSlot(a);
        if (breach >= 0 && breach != a.selectedSlot() && enemy.armorPieces() >= 2 && !Gear.id(hand).equals("minecraft:mace")) { a.attackWith(enemy.id(), breach); breachSwaps++; }
        else a.attack(enemy.id());
        if (crit) crits++;
        swings++;
        checkAt = ticks + 8;
        dev.agentnet.AgentNet.LOG.info("[{}] swing {} at {}{}: {} from its eyes, charge {} of {} ticks, {} ({} damage); server: {}", a.name(), swings, enemy.name(), crit ? " (falling: a critical hit)" : " (on the ground)",
                String.format("%.2f", reach), charge, String.format("%.1f", full), Gear.id(hand).replace("minecraft:", ""), String.format("%.0f", damage(hand)), serverSide(a));
        if (breach >= 0 && breachSwaps > 0) dev.agentnet.AgentNet.LOG.info("[{}] (that swing was a breach swap, #{})", a.name(), breachSwaps);
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
    private int breachSwaps;
    /** Hotbar slot of a mace with Breach, or -1. */
    private static int breachSlot(AgentHandle a) {
        List<ItemStack> inv = a.inventory();
        int best = -1, lvl = 0;
        for (int i = 0; i < 9 && i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (s.getItem() != net.minecraft.world.item.Items.MACE) continue;
            int l = Gear.level(s, net.minecraft.world.item.enchantment.Enchantments.BREACH);
            if (l > lvl) { lvl = l; best = i; }
        }
        return best;
    }

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
