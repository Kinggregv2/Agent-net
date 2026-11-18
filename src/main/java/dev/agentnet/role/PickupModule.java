package dev.agentnet.role;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.agentnet.bus.AgentBus;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Picker-upper: walks to items lying on the ground (what mobs dropped, what fell off a tunnel, loads nobody came
 * back for) and picks them up. Its load goes to the storage keeper (dropped at the keeper's feet, who puts it in the
 * chests); with no keeper online, a distributor is called to collect it, as for any gatherer.
 */
final class PickupModule extends GathererModule {
    private static final int SEARCH = 48;
    private enum State { LOOK, FETCH, DELIVER }

    private State state = State.LOOK;
    private Vec3 item;
    private int timer, idle, dropTicks;
    private final Map<Long, Integer> gaveUp = new HashMap<>();
    private int ticks;
    private String status = "looking for items on the ground";

    @Override public void onStart(AgentHandle a) { a.keepWorld(true); }
    @Override public void onStop(AgentHandle a) { a.keepWorld(false); if (a.isNavigating()) a.stopNavigation(); }
    @Override public String status() { return status; }
    /** A long while with nothing on the ground and nothing to bring in. */
    @Override public boolean idle() { return state == State.LOOK && idle > 30; }

    private static AgentHandle keeper(AgentHandle a) {
        var k = AgentBus.closest(Role.KEEPER, a);
        return k.isPresent() && k.get().isOnline() && AgentHandle.sameDim(k.get().dimension(), a.dimension()) ? k.get() : null;
    }

    /** A keeper to take the load: no distributor called. */
    @Override protected boolean carriesHome(AgentHandle a) { return keeper(a) != null; }

    private int loose(AgentHandle a) {
        Set<Integer> keep = keepSlots(a);
        List<ItemStack> inv = a.inventory();
        int n = 0;
        for (int i = 0; i < inv.size(); i++) if (!inv.get(i).isEmpty() && !keep.contains(i)) n++;
        return n;
    }

    private static long key(Vec3 v) { return ((long) Math.floor(v.x) << 32) ^ ((long) Math.floor(v.z) & 0xffffffffL) ^ ((long) Math.floor(v.y) << 52); }

    @Override
    protected void work(AgentHandle a) {
        ticks++;
        timer++;
        switch (state) {
            case LOOK -> {
                if (ticks % 10 != 0) return;
                gaveUp.values().removeIf(t -> ticks - t > 20 * 300);
                // Full enough, or nothing more around while carrying something: take it in.
                if (keeper(a) != null && (a.inventoryFill() > 0.5 || idle > 6 && loose(a) > 0)) { state = State.DELIVER; timer = 0; dropTicks = 0; return; }
                Vec3 best = null;
                double bd = Double.MAX_VALUE;
                for (Vec3 v : a.itemsNear(SEARCH)) {
                    if (gaveUp.containsKey(key(v)) || GroundDrops.reservedFor(a.name(), v) || Math.abs(v.y - a.y()) > 12) continue;
                    double d = v.distanceToSqr(a.x(), a.y(), a.z());
                    if (d < bd) { bd = d; best = v; }
                }
                if (best == null) { idle++; status = "no items on the ground around"; HomeBase.goHome(a, ticks); return; }
                idle = 0;
                item = best;
                timer = 0;
                state = State.FETCH;
                a.navigate((int) Math.floor(item.x), (int) Math.floor(item.y + 0.2), (int) Math.floor(item.z), 0);
            }
            case FETCH -> {
                status = String.format("picking up an item at %.0f %.0f %.0f", item.x, item.y, item.z);
                boolean still = false;
                for (Vec3 v : a.itemsNear(SEARCH)) if (v.distanceToSqr(item) < 1.5 * 1.5) { still = true; break; }
                if (!still) { if (a.isNavigating()) a.stopNavigation(); state = State.LOOK; return; } // picked up (or someone else got it)
                if (timer > 20 * 40 || timer > 40 && !a.isNavigating() && a.navStatus().startsWith("failed")) {
                    gaveUp.put(key(item), ticks);
                    if (a.isNavigating()) a.stopNavigation();
                    state = State.LOOK;
                    return;
                }
                if (!a.isNavigating() && timer % 40 == 0) a.navigate((int) Math.floor(item.x), (int) Math.floor(item.y + 0.2), (int) Math.floor(item.z), 0);
            }
            case DELIVER -> {
                AgentHandle k = keeper(a);
                if (k == null || loose(a) == 0 || timer > 20 * 180) { state = State.LOOK; idle = 0; return; }
                status = "bringing what it picked up to " + k.name();
                double d = Math.sqrt(AgentBus.distSq(a, k));
                if (d > 2.5) { if (!a.isNavigating() && timer % 40 == 1) a.navigate((int) Math.floor(k.x()), (int) Math.floor(k.y()), (int) Math.floor(k.z()), 1); return; }
                if (a.isNavigating()) a.stopNavigation();
                if (++dropTicks % 3 != 0) return;
                Set<Integer> keep = keepSlots(a);
                List<ItemStack> inv = a.inventory();
                for (int i = 0; i < inv.size(); i++) {
                    if (inv.get(i).isEmpty() || keep.contains(i)) continue;
                    a.lookAt(k.x(), k.y() + 0.5, k.z());
                    a.dropStack(i);
                    return;
                }
                a.log("handed what it picked up to " + k.name());
                state = State.LOOK;
                idle = 0;
            }
        }
    }
}
