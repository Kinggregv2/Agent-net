package dev.agentnet.role;

import java.util.List;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Elytra mace: fly up high with the elytra and rockets, glide over the target, swap the
 * chestplate back on in the air (the elytra closes, plain falling starts: a mace smash only
 * counts when not gliding), fall onto the target and smash it with the mace. The damage grows
 * with the fall. Stun slam: if the target holds its shield up, hit it with the axe first (the
 * shield is disabled) and the mace in the same tick.
 *
 * <p>Needs: an elytra (worn or carried), a chestplate to swap back to, firework rockets, a mace,
 * open sky above. The warrior hands the fight to this while {@link #active()}.
 */
final class ElytraMace {
    private enum State { OFF, EQUIP, JUMP, OPEN, CLIMB, TOP, CRUISE, SWAP, FALL, REGLIDE, CONFIRM, DIVE }
    /** The chestplate went on once at the top of this attack (the user's sequence): not again on a pull-up or a Wind Burst chain. */
    private boolean topped;

    /** How high above the target before gliding over to it (fall damage of the smash). */
    private static final double HEIGHT = 14;
    private int smashes;
    private State state = State.OFF;
    private int timer, rocketIn, total, struckAt;
    private boolean struck, confirmed;
    private double lastFlat = 99;
    private int targetId = -1;
    /** How the target moves, blocks a tick (smoothed): the drop is aimed at where it will be. */
    private net.minecraft.world.phys.Vec3 lastEnemy;
    private double evx, evz, evy;
    /** The ground the target last stood on, and when: thrown up by a smash, it comes back down to it (an arc, not a flight). */
    private double groundY = Double.NaN, rawVy, rawVx, rawVz;
    /** The rocket on our back may be gone: at the smash it often runs into the target or the floor and goes off. */
    private boolean rocketGone;
    private int groundAt = -1000, still;
    /** The dive is on: no more climbing first because it is "too low" (it gets lower all the way down). */
    private boolean committed;
    /** How far over the target a chained dive starts: the server counts the fall only once it is going down fast. */
    private static final double DIVE_UP = Double.parseDouble(System.getProperty("agentnet.diveUp", "9"));
    private static final boolean TRACE = Boolean.getBoolean("agentnet.diveTrace");
    private static final int SETTLE = Integer.getInteger("agentnet.settle", 7);
    private int reglides, confirmedAt, chain;
    private double lastX = Double.NaN, lastZ;
    int cooldown;
    String tactic = "";

    boolean active() { return state != State.OFF; }

    /** Has everything for it and room to fly (sky above). */
    static boolean ready(AgentHandle a) {
        if (slot(a, Items.MACE) < 0 || count(a, Items.FIREWORK_ROCKET) < 2) return false;
        boolean wearsElytra = a.armor().get(1).getItem() == Items.ELYTRA;
        if (!wearsElytra && slot(a, Items.ELYTRA) < 0) return false;
        if (chestplate(a) < 0 && !Gear.id(a.armor().get(1)).endsWith("_chestplate")) return false;
        var w = a.openWorld();
        if (w == null) return false;
        int x = (int) Math.floor(a.x()), y = (int) Math.floor(a.y()), z = (int) Math.floor(a.z());
        for (int dy = 2; dy < 30; dy++) {
            var s = w.get(x, y + dy, z);
            if (s != null && !s.isAir()) return false;
        }
        return true;
    }

    void start(AgentHandle a) {
        state = State.EQUIP;
        timer = 0;
        total = 0;
        struck = false;
        confirmed = false;
        topped = false;
        smashes = 0;
        lastFlat = 99;
        reglides = 0;
        lastX = Double.NaN;
        lastEnemy = null;
        evx = 0; evz = 0;
        if (a.isNavigating()) a.stopNavigation();
        a.log("§celytra mace: taking off");
    }

    /** One tick of the move. Returns false when it's over (the warrior takes the fight back). */
    boolean tick(AgentHandle a, Seen enemy) {
        if (state == State.OFF) return false;
        timer++;
        if (++total > 20 * 60) { abort(a, "took too long"); return false; }
        double dx = enemy.pos().x - a.x(), dz = enemy.pos().z - a.z(), flat = Math.sqrt(dx * dx + dz * dz);
        if (lastEnemy != null && targetId == enemy.id()) {
            double mx = enemy.pos().x - lastEnemy.x, mz = enemy.pos().z - lastEnemy.z;
            double my = enemy.pos().y - lastEnemy.y;
            if (mx * mx + mz * mz < 9) { evx += (mx - evx) * 0.35; evz += (mz - evz) * 0.35; evy += (my - evy) * 0.35; }
            // Its position comes in every other tick: a tick with no news is not "standing still". Its speed is the last
            // change over the ticks it took; it stands on the ground once nothing has changed for four ticks.
            if (Math.abs(my) < 0.003 && mx * mx + mz * mz < 1e-5) {
                if (++still >= 4) { groundY = enemy.pos().y; groundAt = total; rawVy = 0; rawVx = 0; rawVz = 0; }
                else if (still >= 2) rawVy = (rawVy - 0.08) * 0.98; // no news for a while: it goes on falling meanwhile
            } else {
                if (Math.abs(my) < 4) { rawVy = my / (still + 1); rawVx = mx / (still + 1); rawVz = mz / (still + 1); }
                still = 0;
            }
        } else { evx = 0; evz = 0; evy = 0; rawVy = 0; still = 0; groundY = Double.NaN; groundAt = -1000; }
        // Thrown up (by our own smash, or a jump): it stood on the ground a moment ago and comes back down to it.
        boolean arc = !Double.isNaN(groundY) && total - groundAt < 70;
        // A target that runs or flies (a "bird"): chased on the elytra, not climbed over.
        // Not one our own smash just knocked away: that is a slide of a few blocks that stops by itself, and chasing it
        // like a runner made the next dive a low, flat one (4 blocks of fall).
        boolean knocked = smashes > 0 && total - struckAt < 30;
        boolean moving = !knocked && Math.hypot(evx, evz) > 0.45 || !arc && Math.abs(evy) > 0.3;
        lastEnemy = enemy.pos();
        lastFlat = enemy.distance(); // in all three directions: a target in the air can be right overhead and 20 blocks up
        targetId = enemy.id();
        // Aim point: where the target will be when the fall from this height ends.
        int fallT = fallTicks(a.y() - enemy.pos().y, Math.min(0, a.verticalSpeed()));
        double adx = dx + evx * fallT, adz = dz + evz * fallT, aflat = Math.sqrt(adx * adx + adz * adz);
        float yaw = (float) Math.toDegrees(Math.atan2(-adx, adz));
        // Our own motion this tick, split into toward the aim point and across it: only the part toward it carries
        // the fall there (speed alone says nothing about where it's headed, e.g. right after a steep climb).
        double mvx = Double.isNaN(lastX) ? 0 : a.x() - lastX, mvz = Double.isNaN(lastX) ? 0 : a.z() - lastZ;
        lastX = a.x(); lastZ = a.z();
        double closing = aflat < 0.01 ? 0 : (mvx * adx + mvz * adz) / aflat;
        double across = aflat < 0.01 ? 0 : Math.abs(mvx * adz - mvz * adx) / aflat;
        // Tests only (-Dagentnet.diveTrace=true, your own world): ours beside what the server has, every tick of a dive.
        if (TRACE && (state == State.DIVE || state == State.FALL || state == State.SWAP || state == State.REGLIDE))
            dev.agentnet.AgentNet.LOG.info("[{}] trace {} t{} up {} vy {} pitch {} rocket {} glide {} // {}", a.name(), state, timer, String.format("%.1f", a.y() - enemy.pos().y),
                    String.format("%.2f", a.verticalSpeed()), String.format("%.0f", a.pitch()), a.rocketLeft(), a.gliding(), Fight.serverSide(a).replaceAll(", y .*holding [a-z_]*", "").replaceAll(", sprinting [a-z]*", "").replaceAll(", damage.*", ""));
        switch (state) {
            case EQUIP -> {
                tactic = "putting the elytra on";
                if (a.armor().get(1).getItem() == Items.ELYTRA) { go(State.JUMP); return true; }
                if (timer % 6 != 1) return true;
                int e = slot(a, Items.ELYTRA);
                if (e < 0 || timer > 30) { abort(a, "couldn't put the elytra on"); return false; }
                hold(a, e);
                a.useItem(); // right-click swaps it with the worn chestplate
            }
            case JUMP -> { a.jump(); go(State.OPEN); }
            case OPEN -> {
                tactic = "opening the elytra";
                if (!a.airborne()) { if (timer > 10) { abort(a, "didn't get off the ground"); return false; } return true; }
                if (timer < 3) return true;
                if (!a.startGliding()) { abort(a, "the elytra didn't open"); return false; }
                rocket(a, yaw, -80f);
                go(State.CLIMB);
            }
            case CLIMB -> {
                // Nearly straight up (so a target close by isn't overflown on the way up); the further the
                // target, the higher: height is what the glide over there is paid with.
                double want = moving ? 5 : height(a) + Math.min(16, aflat * 0.25);
                tactic = String.format("climbing (%.0f of %.0f above %s)", a.y() - enemy.pos().y, want, enemy.name());
                if (!a.gliding()) { abort(a, "landed"); return false; }
                a.look(yaw, -80f);
                // The rocket keeps pushing a moment after the nose comes down: stop climbing that much early.
                if (a.y() - enemy.pos().y + Math.max(0, a.verticalSpeed()) * 2 >= want) { go(topped || moving ? State.DIVE : State.TOP); return true; }
                if (a.rocketLeft() <= 1) { if (slot(a, Items.FIREWORK_ROCKET) < 0) { go(State.CRUISE); return true; } rocket(a, yaw, -80f); }
                if (timer > 20 * 12) go(State.CRUISE);
            }
            case DIVE -> {
                // The torpedo: nose at the target, the rocket still burning: down at rocket speed instead of waiting
                // for gravity. The elytra comes off about two ticks before it gets there (the server has to see it
                // closed for the smash to count); the dive is its fall. Against a target that runs or flies it is a
                // chase first: on the elytra, a few blocks over it, nose down only when it is nearly overhead.
                // Still in the air with the elytra on but no longer gliding (it brushed something): open it again.
                if (!a.gliding() && !(a.airborne() && a.armor().get(1).getItem() == Items.ELYTRA && a.startGliding())) { abort(a, "landed"); return false; }
                double sp = Math.max(0.6, Math.sqrt(a.horizontalSpeed() * a.horizontalSpeed() + a.verticalSpeed() * a.verticalSpeed()));
                double upNow = a.y() - enemy.pos().y;
                double t = Math.min(40, Math.sqrt(flat * flat + upNow * upNow) / Math.max(0.8, sp));
                // Where it will be when we get there: along the ground and up or down.
                // Thrown up, it flies an arc back to the ground it stood on; a straight line put the aim far over or under it,
                // and the dive at a Dummy the Wind Burst had thrown up missed every other time.
                if (arc && !committed) t += 3; // the turn at the top
                // Sideways, a knocked target slows by itself (air drag 0.91 a tick): the way it still goes is a sum that ends.
                double slide = (1 - Math.pow(0.91, Math.min(t, 30))) / 0.09;
                double tx = enemy.pos().x + (arc ? rawVx * slide : evx * t), ty = arc ? arcY(enemy.pos().y, rawVy, (int) Math.round(t)) : enemy.pos().y + evy * t, tz = enemy.pos().z + (arc ? rawVz * slide : evz * t);
                double off = Math.hypot(tx - a.x(), tz - a.z()), up = a.y() - ty, d3 = Math.sqrt(off * off + up * up);
                double keep = moving ? 7 : height(a);
                // A dive only from above: arriving level with it or under it there is nothing to smash with (against
                // the_guy7 it got within 1.2 and 1.5 blocks that way and had to pull up). Under 4 blocks over it: climb first.
                // In a chain (thrown up by Wind Burst) higher: the server counts the fall only from where it goes down fast,
                // and a dive from 8 over it came to 4.6 blocks of fall, too little to pop a totem through Protection IV.
                double minUp = topped && !moving ? DIVE_UP : 4;
                // Just hit: its new speed (thrown up, or knocked down out of the air) takes a few ticks to show. Diving at
                // once on the old one met it halfway down, after 4 blocks of fall instead of 9: no pop.
                boolean settling = smashes > 0 && total - struckAt < SETTLE;
                if (!committed && (settling || off > up + 2 || up < minUp)) {
                    tactic = String.format("flying at %s (%.0f out, %.0f up)", enemy.name(), off, up);
                    a.lookAt(tx, ty + keep, tz);
                    // Over it, or a few blocks off (knocked away): steeply up, leaning its way, so the dive is steep too.
                    if (!moving && off < 12) a.look((float) Math.toDegrees(Math.atan2(-(tx - a.x()), tz - a.z())), off < 2.5 ? -86f : (float) (-86 + Math.min(28, off * 4)));
                    else if (up < keep - 2 && off < 14) a.look(a.yaw(), off < 6 ? -60f : -40f); // too low for a dive: gain height as it closes
                    if (a.rocketLeft() <= 0 && slot(a, Items.FIREWORK_ROCKET) >= 0) rocket(a, a.yaw(), a.pitch());
                    if (timer > 20 * 25) { abort(a, "couldn't catch " + enemy.name()); return false; }
                    return true;
                }
                committed = true;
                tactic = String.format("diving at %s (%.0f up, %.1f out)", enemy.name(), up, off);
                a.lookAt(tx, ty + 0.9, tz);
                // The burn ran out on the way down and it has slowed: one more.
                // Or it is still going up from the climb: without a burn the turn at the top took nine ticks and ten blocks.
                if (a.rocketLeft() <= 0 && (sp < 1.0 && up > 8 || a.verticalSpeed() > -0.5 && up > 5) && slot(a, Items.FIREWORK_ROCKET) >= 0) rocket(a, a.yaw(), a.pitch());
                // Four to five ticks out, not two: the server clears "gliding" only on its next tick after the chestplate swap,
                // and a mace swing that arrives while it still glides is no smash (it dropped 3 blocks over the target,
                // swung the next tick, the hit did not count and the fall killed it).
                // With the rocket burning it is still picking up speed: the next ticks cover more than this one did (a swap
                // timed on this tick's speed came two ticks before the hit, and the server still had it gliding: no smash).
                // Only the speed toward it counts: right after the turn it is still going up, fast, and is nowhere near.
                double toward = d3 < 0.01 ? 0 : (mvx * (tx - a.x()) + mvz * (tz - a.z()) + a.verticalSpeed() * (ty - a.y())) / d3;
                double reach = 0, v = Math.max(0, toward);
                for (int i = 0; i < 5; i++) { if (a.rocketLeft() > i) v += 0.1 + (1.5 - Math.min(v, 1.7)) * 0.5; reach += i < 4 ? v : v * 0.5; }
                if (toward > 0.4 && d3 <= 1.2 + Math.max(toward * 4.5, reach)) {
                    // On it: elytra off, smash. Wide of it or under it: stay on the elytra, pull up, come round again.
                    if (off <= 2.2 + sp && up > 0.5) { go(State.SWAP); return true; }
                    a.log(String.format("§7elytra mace: dive %.1f blocks wide of %s: pulling up", off, enemy.name()));
                    a.look(yaw, -75f);
                    committed = false;
                    go(State.CLIMB);
                    return true;
                }
            }
            case TOP -> {
                // The user's sequence: up on the firework, the chestplate on at the top (a plain fall starts), the elytra
                // back on, down on a firework, then the chestplate and the mace.
                tactic = "at the top: chestplate on, falling";
                if (timer == 1) {
                    topped = true;
                    int c = chestplate(a);
                    if (c >= 0) { hold(a, c); a.useItem(); }
                    a.stopGliding();
                    a.log(String.format("§7elytra mace: chestplate on %.0f blocks over %s, then the elytra again for the dive", a.y() - enemy.pos().y, enemy.name()));
                }
                if (!a.airborne()) { abort(a, "landed"); return false; }
                if (timer >= 5) go(State.REGLIDE);
            }
            case CRUISE -> {
                double up = a.y() - enemy.pos().y;
                tactic = String.format("gliding over to %s (%.0f blocks out, %.0f up)", enemy.name(), aflat, up);
                if (!a.gliding()) { abort(a, "landed"); return false; }
                // Right at the target but too low for a smash worth having: straight back up first.
                if (up < 6 && aflat <= 14) {
                    if (slot(a, Items.FIREWORK_ROCKET) >= 0) {
                        if (--rocketIn <= 0) rocket(a, yaw, -70f); else a.look(yaw, -70f);
                        return true;
                    }
                    if (up < 2) { abort(a, "too low over " + enemy.name() + " and out of rockets"); return false; }
                }
                // Nose down a little to pick up speed, flatter when it has the height to spare. Close to the
                // target: nose up, trading speed for height, so the drop is short and nearly straight down
                // (a long fast drift is what misses a target that moves).
                // Letting go: from here plain falling carries it about 10x its speed further (drag 0.91 a tick).
                // Only when it's headed at the target (little sideways drift) and has the height for a smash.
                // Decided before the nose moves this tick: turning the nose (with a rocket still burning) changes
                // the speed at once, and the drop would overshoot by the difference.
                double lead = Math.max(1.0, Math.min(25, closing * 10.3));
                boolean onLine = across * 10.3 <= 1.5;
                // Not when it's already too close for its speed (it would sail past) or flying away from it: it flies on, comes round.
                boolean notPast = aflat >= closing * 10.3 - 2.5 && closing > -0.03;
                if (aflat <= lead && notPast && onLine && up >= 5 || timer > 20 * 30) {
                    a.log(String.format("§7elytra mace: letting go %.1f out, closing %.2f, drift %.2f, %.0f up", aflat, closing, across, up));
                    go(State.SWAP);
                    return true;
                }
                float pitch = up > aflat * 0.5 + 10 ? 25f : 8f;
                if (aflat < 16 && a.horizontalSpeed() > 0.7) pitch = -30f;
                a.look(yaw, pitch);
                // Too low to make it there with a fall worth having: one more rocket, nose up.
                if (up < 10 && aflat > 14 && --rocketIn <= 0 && slot(a, Items.FIREWORK_ROCKET) >= 0) rocket(a, yaw, -35f);
            }
            case SWAP -> {
                tactic = "elytra off: falling onto " + enemy.name();
                int c = chestplate(a);
                if (c >= 0) { hold(a, c); a.useItem(); } // the chestplate takes the elytra's place: no more gliding
                a.stopGliding();
                int m = slot(a, Items.MACE);
                if (m >= 0) hold(a, m);
                a.log(String.format("§celytra mace: dropping from %.0f blocks above %s", a.y() - enemy.pos().y, enemy.name()));
                go(State.FALL);
            }
            case FALL -> {
                // Toward the aim point, eased off by its own drift, so it comes down dead centre instead of sliding
                // past: a smash from straight above throws the target up, not away, and the next one lands too.
                a.steer(adx - mvx * 8, adz - mvz * 8);
                if (struck && !confirmed && a.hitRecently(enemy.id(), 1500)) { confirmed = true; smashes++; }
                int mace = slot(a, Items.MACE);
                // Wind Burst: a smash that lands throws us back up. Going up again = the next drop:
                // swing again on the way down; too far from the target now, open the elytra in the air.
                if (struck && confirmed && a.airborne() && a.verticalSpeed() > 0.2) {
                    struck = false;
                    confirmed = false;
                    if (slot(a, Items.ELYTRA) >= 0 && (a.rocketLeft() > 6 || slot(a, Items.FIREWORK_ROCKET) >= 0)) { chain++; go(State.REGLIDE); return true; }
                    tactic = "wind burst: up again over " + enemy.name();
                    return true;
                }
                if (!a.airborne()) {
                    // The swing went out a tick or two before it landed: give the server a moment to say it hit.
                    // (and, with Wind Burst, to throw us back up for the next one).
                    if (struck) { confirmedAt = 0; go(State.CONFIRM); return true; }
                    a.log(smashes > 0 ? "§celytra mace: " + smashes + " smash(es) landed" : struck ? "§eelytra mace: swung, but the server counted no hit" : "§eelytra mace: missed, landed");
                    state = State.OFF;
                    cooldown = smashes > 0 ? 0 : 20 * 2; // straight back up for the next one
                    return false;
                }
                double fallen = a.airPeakY() - a.y();
                tactic = String.format("falling onto %s (%.0f blocks fallen, %.1f away)", enemy.name(), fallen, enemy.distance());
                // Off target with height left (it moved away, or the drop was off): elytra back on, come round, drop again.
                if (!struck && timer > 5 && reglides < 8 && enemy.distance() > 4.5 && slot(a, Items.ELYTRA) >= 0 && clearBelow(a) >= 10
                        && (a.rocketLeft() > 4 || slot(a, Items.FIREWORK_ROCKET) >= 0)) {
                    reglides++;
                    a.log(String.format("§eelytra mace: missed %s in the air: elytra back on", enemy.name()));
                    go(State.REGLIDE);
                    return true;
                }
                double carry = Math.max(0, closing) * 10.3, upNow = a.y() - enemy.pos().y;
                if (!struck && timer > 4 && reglides < 2 && upNow > 20 && aflat - carry > 3.5 + fallT * 0.1
                        && slot(a, Items.ELYTRA) >= 0 && slot(a, Items.FIREWORK_ROCKET) >= 0) {
                    reglides++;
                    a.log(String.format("§eelytra mace: %.0f blocks off %s: elytra back on for another pass", aflat, enemy.name()));
                    go(State.REGLIDE);
                    return true;
                }
                if (!struck && mace >= 0 && timer > 1) hold(a, mace);
                // The server lets a hit through from a good 4 blocks: swing as soon as it's that close, still falling.
                // As late as it can: every block more of fall is damage, and after a Wind Burst bounce of 7 blocks the
                // last 4 are over half of it. So: the last tick before it is down at the target (or lands).
                boolean last = a.y() + a.verticalSpeed() <= enemy.pos().y + 1.0 || landsNext(a) || moving && enemy.distance() <= 3.0;
                // Within real reach (3 blocks) is enough: at dive speed the "last tick" is one tick wide, and missing it is a fall to its death.
                if (!struck && a.verticalSpeed() < 0 && (last || enemy.distance() <= 3.2) && (timer >= 3 || landsNext(a)) && enemy.distance() <= 4.0 && fallen > 1.6 && Sight.canHit(a, enemy)) { // 3 ticks since the elytra came off: the server has it closed
                    a.lookAt(enemy.pos().x, enemy.pos().y + 1.0, enemy.pos().z);
                    int axe = Gear.bestTool(a.inventory(), "axe");
                    if (enemy.blocking() && axe >= 0 && axe < 9 && mace >= 0 && mace < 9) {
                        // Stun slam: the axe knocks the shield out, the mace lands in the same tick
                        // (the smash bonus doesn't depend on the swing cooldown).
                        a.selectHotbar(axe);
                        a.attack(enemy.id());
                        a.selectHotbar(mace);
                        a.attack(enemy.id());
                        a.log(String.format("§cstun slam on %s after %.0f blocks of fall", enemy.name(), fallen));
                    } else {
                        a.attack(enemy.id());
                        a.log(String.format("§cmace smash on %s after %.0f blocks of fall", enemy.name(), fallen));
                    }
                    struck = true;
                    rocketGone = true;
                    struckAt = total;
                    dev.agentnet.AgentNet.LOG.info("[{}] smash swing: {} ticks after the elytra came off, {} away, {} up, falling {} a tick, target blocking: {}, holding {}", a.name(), timer, String.format("%.1f", enemy.distance()), String.format("%.1f", a.y() - enemy.pos().y), String.format("%.2f", a.verticalSpeed()), enemy.blocking(), Gear.id(a.inventory().get(a.selectedSlot())) + "; the server has: " + Fight.serverSide(a));
                }
            }
            case REGLIDE -> {
                // Thrown up by Wind Burst: elytra straight back on, up on what is left of the rocket (or a new one), dive again.
                tactic = "elytra back on over " + enemy.name();
                if (!a.airborne()) { state = State.OFF; cooldown = 0; return false; }
                if (a.armor().get(1).getItem() != Items.ELYTRA) {
                    int e = slot(a, Items.ELYTRA);
                    if (e < 0) { go(State.FALL); return true; }
                    if (timer % 4 == 1) { hold(a, e); a.useItem(); }
                    if (timer > 16) { go(State.FALL); }
                    return true;
                }
                if (!a.startGliding()) { go(State.FALL); return true; }
                // Already in the sky (after the top, or thrown up by Wind Burst): no climb, straight down at it on a firework.
                // High enough over it already (after the top): straight down. In a chain: up first, the dive decides when.
                boolean high = a.y() - (arc ? arcY(enemy.pos().y, rawVy, 8) : enemy.pos().y) >= DIVE_UP;
                topped = true;
                committed = false;
                if (high) a.lookAt(enemy.pos().x, enemy.pos().y + 0.9, enemy.pos().z); else a.look(yaw, -86f);
                // After a smash always a new rocket: the old one may have gone off on the target, and without one burning
                // the server never sees the dive going down and counts no fall for it until the elytra is off.
                if ((rocketGone || a.rocketLeft() <= 2) && slot(a, Items.FIREWORK_ROCKET) >= 0) { rocket(a, a.yaw(), a.pitch()); rocketGone = false; }
                go(State.DIVE);
            }
            case CONFIRM -> {
                tactic = "landed on " + enemy.name();
                if (!confirmed && a.hitRecently(enemy.id(), 1500)) { confirmed = true; smashes++; confirmedAt = timer; }
                // Wind Burst threw it back up: the next drop, without the elytra.
                if (confirmed && a.airborne() && a.verticalSpeed() > 0.2) {
                    struck = false; confirmed = false; chain++;
                    tactic = "wind burst: up again over " + enemy.name();
                    go(slot(a, Items.ELYTRA) >= 0 && (a.rocketLeft() > 6 || slot(a, Items.FIREWORK_ROCKET) >= 0) ? State.REGLIDE : State.FALL);
                    return true;
                }
                // The throw comes a tick or two after the hit is confirmed: wait for it if the mace has Wind Burst.
                int m = slot(a, Items.MACE);
                boolean burst = m >= 0 && Gear.level(a.inventory().get(m), net.minecraft.world.item.enchantment.Enchantments.WIND_BURST) > 0;
                if (confirmed && (!burst || timer - confirmedAt >= 6) || !confirmed && timer >= 8) {
                    a.log(smashes > 0 ? "§celytra mace: " + smashes + " smash(es) landed" : "§eelytra mace: swung, but the server counted no hit");
                    state = State.OFF;
                    cooldown = smashes > 0 ? 0 : 20 * 2;
                    return false;
                }
            }
            default -> {}
        }
        return true;
    }

    /**
     * How high to climb: every block is time. A smash from h blocks does about (12 + 14 + h * (1 + Density / 2)) * 1.5
     * with a charged swing; 70 goes through full Protection IV netherite. Density V needs 8 blocks for that,
     * a plain mace about 20.
     */
    private static double height(AgentHandle a) {
        int m = slot(a, Items.MACE);
        int density = m < 0 ? 0 : Gear.level(a.inventory().get(m), net.minecraft.world.item.enchantment.Enchantments.DENSITY);
        return Math.max(8, Math.min(HEIGHT + 6, 28 / (1 + density * 0.5))); // measured: 8 blocks pops 87% of smashes through Prot IV netherite at 0.80/s; 12 blocks pops 95% but only 0.67/s
    }

    /** Where a thrown target is in t ticks: gravity 0.08, drag 0.98, no lower than the ground it stood on. */
    private double arcY(double y, double vy, int t) {
        for (int i = 0; i < t; i++) {
            vy = (vy - 0.08) * 0.98;
            y += vy;
            if (y <= groundY) return groundY;
        }
        return y;
    }

    /** Blocks of air under its feet (up to 16). */
    private static int clearBelow(AgentHandle a) {
        var w = a.openWorld();
        if (w == null) return 0;
        int x = (int) Math.floor(a.x()), y = (int) Math.floor(a.y()), z = (int) Math.floor(a.z()), n = 0;
        for (int dy = 1; dy <= 16; dy++) {
            var s = w.get(x, y - dy, z);
            if (s == null || !dev.agentnet.pathing.Rules.canWalkThrough(s)) break;
            n++;
        }
        return n;
    }

    /** Its next tick of falling ends on a block: this is the last moment in the air. */
    private static boolean landsNext(AgentHandle a) {
        var w = a.openWorld();
        if (w == null) return false;
        double ny = a.y() + a.verticalSpeed();
        for (int y = (int) Math.floor(a.y() - 0.01); y >= (int) Math.floor(ny) - 1; y--) {
            var s = w.get((int) Math.floor(a.x()), y, (int) Math.floor(a.z()));
            if (s != null && !dev.agentnet.pathing.Rules.canWalkThrough(s) && y + 1 >= ny) return true;
        }
        return false;
    }

    /** Ticks a plain fall of this many blocks takes, starting at this vertical speed (gravity 0.08, drag 0.98). */
    private static int fallTicks(double height, double vy) {
        if (height <= 0) return 0;
        double y = 0;
        int t = 0;
        while (y > -height && t < 80) { y += vy; vy = (vy - 0.08) * 0.98; t++; }
        return t;
    }

    /** Open sky over the target: under a roof, a tree or in a cave there is nothing to fall onto it from. */
    static boolean openAbove(AgentHandle a, net.minecraft.world.phys.Vec3 p) {
        var w = a.openWorld();
        if (w == null) return false;
        int x = (int) Math.floor(p.x), y = (int) Math.floor(p.y), z = (int) Math.floor(p.z);
        for (int dy = 2; dy < 24; dy++) {
            var s = w.get(x, y + dy, z);
            if (s != null && !s.isAir()) return false;
        }
        return true;
    }

    /**
     * No water or boat clutch now: while gliding (no fall damage), and in the last stretch above the
     * target before the swing (the bucket would take the mace's place), and once the smash landed
     * (the server cancels that fall). A miss far from the target still gets its clutch.
     */
    boolean blocksClutch(AgentHandle a) {
        if (state != State.FALL) return state != State.OFF;
        if (confirmed || smashes > 0 && !struck && lastFlat <= 5) return true; // a Wind Burst chain: the next smash takes the fall too
        if (struck) return total - struckAt < 4 || a.hitRecently(targetId, 1500); // a moment for the server to say it hit
        return lastFlat <= 4.5;
    }

    private void rocket(AgentHandle a, float yaw, float pitch) {
        int r = slot(a, Items.FIREWORK_ROCKET);
        if (r < 0) return;
        a.look(yaw, pitch);
        hold(a, r);
        a.useItem();
        a.rocketBoost();
        rocketIn = 22;
    }

    private void abort(AgentHandle a, String why) {
        a.log("§eelytra mace: " + why);
        if (a.gliding()) a.stopGliding();
        state = State.OFF;
        cooldown = 20 * 5;
    }

    private void go(State s) { if (s == State.DIVE && state != State.DIVE) committed = false; state = s; timer = 0; }

    private static int slot(AgentHandle a, net.minecraft.world.item.Item item) {
        List<ItemStack> inv = a.inventory();
        for (int i = 0; i < inv.size(); i++) if (inv.get(i).getItem() == item) return i;
        return -1;
    }

    private static int count(AgentHandle a, net.minecraft.world.item.Item item) {
        int n = 0;
        for (ItemStack s : a.inventory()) if (s.getItem() == item) n += s.getCount();
        return n;
    }

    /** A chestplate in the inventory (to swap back on in the air). */
    private static int chestplate(AgentHandle a) {
        List<ItemStack> inv = a.inventory();
        for (int i = 0; i < inv.size(); i++) if (Gear.id(inv.get(i)).endsWith("_chestplate")) return i;
        return -1;
    }

    private static void hold(AgentHandle a, int i) {
        if (i >= 9) { a.moveToHotbar(i, 8); i = 8; }
        a.selectHotbar(i);
    }
}
