package dev.agentnet.role;

import java.util.List;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Long trips by elytra, for any agent (not just warriors): with an elytra (worn or carried), rockets and open sky over
 * it, a trip longer than {@link #MIN_TRIP} blocks is flown instead of walked. Elytra on (the chestplate it wore goes in
 * the pack), a jump, open it, rockets up to cruising height, glide toward the goal with a rocket whenever it slows or
 * sinks too low, then a glide down onto the goal, the chestplate back on, and the last bit is walked as usual.
 * Ticked by the agent in place of its walker while {@link #active()}.
 */
public final class ElytraTravel {
    /** Shorter trips are walked. */
    public static final int MIN_TRIP = 160;
    /** Rockets kept back for the climb out of a bad landing. */
    private static final int RESERVE = 1;
    private static final double CRUISE_ABOVE = 40;

    private enum State { OFF, EQUIP, JUMP, OPEN, CLIMB, CRUISE, DESCEND, UNEQUIP }

    private State state = State.OFF;
    private int timer, total;
    private double tx, tz;
    private int ty;
    private boolean woreChestplate;
    private double cruiseY;
    private String status = "";

    public boolean active() { return state != State.OFF; }
    public String status() { return status; }

    /** Everything for a flight: an elytra, rockets, and open sky over it. */
    public static boolean ready(AgentHandle a) {
        if (count(a, Items.FIREWORK_ROCKET) < 3) return false;
        boolean wears = a.armor().size() > 1 && a.armor().get(1).getItem() == Items.ELYTRA;
        if (!wears && slot(a, Items.ELYTRA) < 0) return false;
        if (a.dimension() != null && a.dimension().endsWith("the_nether")) return false; // the roof: nowhere to fly
        var w = a.openWorld();
        if (w == null) return false;
        int x = (int) Math.floor(a.x()), y = (int) Math.floor(a.y()), z = (int) Math.floor(a.z());
        for (int dy = 2; dy < 24; dy++) {
            var s = w.get(x, y + dy, z);
            if (s != null && !s.isAir()) return false;
        }
        return true;
    }

    public void start(AgentHandle a, int x, int y, int z) {
        tx = x + 0.5; ty = y; tz = z + 0.5;
        state = State.EQUIP; timer = 0; total = 0;
        woreChestplate = Gear.id(a.armor().get(1)).endsWith("_chestplate");
        a.log(String.format("§bflying there by elytra (%.0f blocks)", Math.hypot(tx - a.x(), tz - a.z())));
    }

    /** Lands (or gives up) now: the walker takes over. */
    public void stop(AgentHandle a) {
        if (state == State.OFF) return;
        // In the air: glide down right here (letting go of the elytra 40 blocks up would kill it). On the ground: done.
        if (a.gliding()) { tx = a.x(); tz = a.z(); ty = (int) Math.floor(groundBelow(a)); go(State.DESCEND); return; }
        go(State.UNEQUIP);
    }

    /** A new goal while flying: fly there instead. */
    public void retarget(int x, int y, int z) { tx = x + 0.5; ty = y; tz = z + 0.5; if (state == State.DESCEND) go(State.CRUISE); }

    /** One tick. False once it is done (on the ground, chestplate back on): then the walker goes the last bit. */
    public boolean tick(AgentHandle a) {
        if (state == State.OFF) return false;
        timer++;
        if (++total > 20 * 60 * 5 && state != State.UNEQUIP) { a.log("§eelytra: flying too long, landing"); stop(a); }
        double dx = tx - a.x(), dz = tz - a.z(), flat = Math.hypot(dx, dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        double ground = groundBelow(a);
        double above = a.y() - ground;
        switch (state) {
            case EQUIP -> {
                status = "putting the elytra on";
                if (a.armor().get(1).getItem() == Items.ELYTRA) { go(State.JUMP); return true; }
                if (timer % 6 != 1) return true;
                int e = slot(a, Items.ELYTRA);
                if (e < 0 || timer > 30) { a.log("§eelytra: couldn't put it on, walking"); state = State.OFF; return false; }
                hold(a, e);
                a.useItem(); // right-click swaps it with the chestplate it wears
            }
            case JUMP -> { a.jump(); go(State.OPEN); }
            case OPEN -> {
                status = "taking off";
                if (!a.airborne()) { if (timer > 10) { a.log("§eelytra: didn't get off the ground, walking"); go(State.UNEQUIP); } return true; }
                if (timer < 3) return true;
                if (!a.startGliding()) { a.log("§eelytra: it didn't open, walking"); go(State.UNEQUIP); return true; }
                cruiseY = Math.min(300, Math.max(a.y(), ground) + CRUISE_ABOVE);
                rocket(a, yaw, -60f);
                go(State.CLIMB);
            }
            case CLIMB -> {
                status = String.format("climbing to cruising height (%.0f of %.0f)", a.y(), cruiseY);
                if (!a.gliding()) { go(State.UNEQUIP); return true; }
                a.look(yaw, -55f);
                if (a.y() >= cruiseY || timer > 20 * 15) { go(State.CRUISE); return true; }
                if (a.rocketLeft() <= 0 && a.verticalSpeed() < 0.4 && count(a, Items.FIREWORK_ROCKET) > RESERVE) rocket(a, yaw, -55f);
            }
            case CRUISE -> {
                status = String.format("flying by elytra: %.0f blocks to go, %.0f up", flat, above);
                if (!a.gliding()) { go(State.UNEQUIP); return true; }
                // Down onto the goal once a glide reaches it from here (about a block down per two along).
                if (flat < Math.max(30, (a.y() - ty) * 1.6)) { go(State.DESCEND); return true; }
                // Land ahead higher than it can clear: up.
                double ahead = groundAt(a, a.x() + dx / flat * 24, a.z() + dz / flat * 24);
                boolean low = a.y() < Math.max(ahead, ground) + 15;
                boolean rockets = count(a, Items.FIREWORK_ROCKET) > RESERVE;
                if (low) {
                    a.look(yaw, -35f);
                    if (rockets && a.rocketLeft() <= 0) rocket(a, yaw, -35f);
                    else if (!rockets && above < 6) { a.log("§eelytra: out of rockets, landing here"); go(State.DESCEND); }
                    return true;
                }
                // Level glide; a rocket when it slows down.
                a.look(yaw, a.y() > cruiseY + 10 ? 12f : 4f);
                // (At 1.1 it burned 16 rockets in 75 s of flight; a glide holds about 0.8 on its own.)
                if (rockets && a.rocketLeft() <= 0 && a.horizontalSpeed() < 0.75) rocket(a, yaw, -8f);
            }
            case DESCEND -> {
                status = String.format("gliding down onto the goal (%.0f out, %.0f up)", flat, above);
                if (!a.gliding() || !a.airborne()) { go(State.UNEQUIP); return true; }
                // Steep enough to get down, never straight at the ground (fast into the ground hurts).
                float pitch = (float) Math.max(10, Math.min(40, Math.toDegrees(Math.atan2(a.y() - ty, Math.max(1, flat)))));
                if (above < 4) pitch = 5f; // flare: skim in
                a.look(yaw, pitch);
                if (flat < 3 && above < 6) { a.stopGliding(); go(State.UNEQUIP); }
            }
            case UNEQUIP -> {
                status = "landed: chestplate back on";
                if (a.airborne() && timer < 20 * 20) return true;
                if (woreChestplate && a.armor().get(1).getItem() == Items.ELYTRA) {
                    int c = chestplate(a);
                    if (c >= 0 && timer % 6 == 1) { hold(a, c); a.useItem(); }
                    if (c >= 0 && timer < 40) return true;
                }
                a.log(String.format("§belytra: landed %.0f blocks from the goal", flat));
                state = State.OFF;
                return false;
            }
            default -> { return false; }
        }
        return true;
    }

    private void go(State s) { state = s; timer = 0; }

    private static void rocket(AgentHandle a, float yaw, float pitch) {
        int r = slot(a, Items.FIREWORK_ROCKET);
        if (r < 0) return;
        a.look(yaw, pitch);
        hold(a, r);
        a.useItem();
        a.rocketBoost();
    }

    /** The highest solid block under it (the top of the ground or a tree). */
    private static double groundBelow(AgentHandle a) { return groundAt(a, a.x(), a.z()); }

    private static double groundAt(AgentHandle a, double x, double z) {
        var w = a.openWorld();
        if (w == null) return a.y() - 50;
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        for (int y = Math.min(319, (int) Math.floor(a.y()) + 40); y > -64; y--) {
            var s = w.get(bx, y, bz);
            if (s == null) return a.y() - 50; // not loaded: assume it's below
            if (!s.isAir() && !dev.agentnet.pathing.Rules.canWalkThrough(s)) return y + 1;
            if (!s.getFluidState().isEmpty()) return y + 1;
        }
        return -64;
    }

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
