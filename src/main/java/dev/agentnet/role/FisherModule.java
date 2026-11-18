package dev.agentnet.role;

import java.util.List;
import java.util.Optional;

import dev.agentnet.map.Spots;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Fisher: AFK fishing at a fishing spot. The cheapest role: no pathfinding while
 * fishing, no world model, just a small state machine reading the agent's own bobber.
 * Inventory-full alerts to the nearest distributor come from {@link GathererModule}.
 *
 * <p>Flow: claim the nearest free fishing spot -> (be at it) -> hold a rod -> face the
 * spot's direction -> cast -> wait for a bite -> reel in -> short pause -> cast again.
 *
 * <p>Getting to the spot: the Baritone-derived pathfinder walks it there; the pathfinder's
 * memory is released on arrival, so a fishing agent holds no world data while it fishes.
 */
final class FisherModule extends GathererModule {
    /** How close (blocks) the agent must be to its spot to fish. */
    private static final double AT_SPOT = 1.5;
    private static final int RECAST_DELAY = 10;       // ticks after reeling before the next cast
    private static final int CAST_TIMEOUT = 60;       // no bobber this long after casting -> try again
    private static final int BITE_TIMEOUT = 20 * 60;  // nothing bites for a minute -> reel in, recast
    private static final int REEL_DELAY = 2;          // react to a bite after this many ticks (looks human)

    private enum State { FIND_SPOT, GO_TO_SPOT, NEED_ROD, READY, CASTING, WAITING, REELING, COOLDOWN }

    private State state = State.FIND_SPOT;
    private Spots.Spot spot;
    private int timer;
    private int idleTicks;
    private String lastWarning;
    private int caught;

    @Override
    public void onStart(AgentHandle a) {
        state = State.FIND_SPOT;
    }

    @Override
    public void onStop(AgentHandle a) {
        if (a.bobberId() >= 0 && a.isOnline()) a.useItem(); // reel in before switching jobs
        if (a.isNavigating()) a.stopNavigation();
        Spots.release(a.name());
        spot = null;
        AUTO.remove(a.name().toLowerCase());
    }

    @Override
    protected void work(AgentHandle a) {
        timer++;
        // No rod: ask now, not only after the walk to the spot (rate-limited inside).
        if (timer % 20 == 1 && Gear.bestRod(a.inventory()) < 0) requestTool(a, "minecraft:fishing_rod");
        switch (state) {
            case FIND_SPOT -> {
                if (timer % 100 != 1) return; // look for a spot every 5 s
                Optional<Spots.Spot> s = Spots.claim(Spots.Type.FISHING, a.name(), a.server(), a.dimension(), a.x(), a.y(), a.z());
                // A fishing spot you set up is used first; without one, any open water nearby will do.
                if (s.isPresent() && s.get().distSq(a.x(), a.y(), a.z()) > 256 * 256) { Spots.release(a.name()); s = Optional.empty(); }
                if (s.isEmpty()) {
                    Spots.Spot w = waterSpot(a);
                    if (w == null) { warn(a, "no open water within 48 blocks to fish in: walk it to a lake, river or the sea"); return; }
                    spot = w;
                    a.log("fishing in the water at " + (int) Math.floor(w.x()) + ", " + (int) Math.floor(w.y()) + ", " + (int) Math.floor(w.z()));
                    go(State.GO_TO_SPOT);
                    return;
                }
                spot = s.get();
                a.log("claimed fishing spot '" + spot.name() + "'");
                go(State.GO_TO_SPOT);
            }
            case GO_TO_SPOT -> {
                if (atSpot(a)) { go(State.NEED_ROD); return; }
                travelTo(a);
            }
            case NEED_ROD -> {
                if (!atSpot(a)) { go(State.GO_TO_SPOT); return; }
                if (holdRod(a)) { a.look(spot.yaw(), spot.pitch()); go(State.READY); }
            }
            case READY -> {
                if (!atSpot(a)) { go(State.GO_TO_SPOT); return; }
                if (!holdRod(a)) { go(State.NEED_ROD); return; }
                a.look(spot.yaw(), spot.pitch());
                a.useItem();
                go(State.CASTING);
            }
            case CASTING -> {
                if (a.bobberId() >= 0) go(State.WAITING);
                else if (timer > CAST_TIMEOUT) go(State.READY); // cast didn't happen (lag, no rod) - retry
            }
            case WAITING -> {
                if (a.bobberId() < 0) { go(State.COOLDOWN); return; } // bobber gone (hooked a block, broke)
                if (a.bobberBiting()) go(State.REELING);
                else if (timer > BITE_TIMEOUT) { a.useItem(); go(State.COOLDOWN); }
            }
            case REELING -> {
                if (timer < REEL_DELAY) return;
                a.useItem();
                caught++;
                go(State.COOLDOWN);
            }
            case COOLDOWN -> {
                if (timer >= RECAST_DELAY) go(State.READY);
            }
        }
    }

    private void go(State next) {
        state = next;
        timer = 0;
        lastWarning = null;
    }

    private boolean atSpot(AgentHandle a) {
        return spot.distSq(a.x(), a.y(), a.z()) <= AT_SPOT * AT_SPOT;
    }

    /**
     * Hold the best fishing rod (see {@link Gear#rodScore}): Mending first, then Lure, then
     * Luck of the Sea. A better rod in the main inventory is swapped into the hotbar.
     * False (with a one-time warning) if there's no rod at all.
     */
    private boolean holdRod(AgentHandle a) {
        List<ItemStack> inv = a.inventory();
        int best = Gear.bestRod(inv);
        if (best < 0) {
            requestTool(a, "minecraft:fishing_rod");
            warn(a, "needs a fishing rod to fish (asked a distributor)");
            return false;
        }
        int sel = a.selectedSlot();
        if (Gear.rodScore(inv.get(sel)) >= Gear.rodScore(inv.get(best))) return true;
        if (best < 9) { a.selectHotbar(best); return true; }
        // Bring it to the hotbar: in place of a worse rod, else an empty slot, else the held slot.
        int target = -1;
        for (int i = 0; i < 9 && target < 0; i++) if (isRod(inv.get(i))) target = i;
        for (int i = 0; i < 9 && target < 0; i++) if (inv.get(i).isEmpty()) target = i;
        if (target < 0) target = sel;
        a.moveToHotbar(best, target);
        a.selectHotbar(target);
        a.log("switched to its best fishing rod");
        return true;
    }

    private static final java.util.Set<String> FISH = java.util.Set.of("minecraft:cod", "minecraft:salmon",
            "minecraft:tropical_fish", "minecraft:pufferfish");

    /** A fisher keeps only its best fishing rod (and one stack of food that isn't its catch). */
    @Override
    protected java.util.Set<Integer> keepSlots(AgentHandle a) {
        List<ItemStack> inv = a.inventory();
        java.util.Set<Integer> keep = new java.util.HashSet<>();
        int rod = Gear.bestRod(inv);
        if (rod >= 0) keep.add(rod);
        keepFood(inv, keep, FISH);
        return keep;
    }

    private static boolean isRod(ItemStack s) { return !s.isEmpty() && s.getItem() == Items.FISHING_ROD; }

    /**
     * Walk to the spot with the pathfinder. If a trip fails, wait 30 s and try again (a
     * door may have been opened, blocks placed...). After 3 failures, ask for help.
     */
    private void travelTo(AgentHandle a) {
        if (a.isNavigating()) return;
        if (retryWait > 0) { retryWait--; return; }
        String status = a.navStatus();
        if (status.startsWith("failed") && tripStarted) {
            if (spot.name().startsWith("water ")) { tripStarted = false; a.log("can't walk to that shore: looking for other water"); dropAutoSpot(a); return; }
            failures++;
            tripStarted = false;
            if (failures >= 3) {
                warn(a, String.format("can't walk to fishing spot '%s' (%s). Bring it there: /agent control %s, walk, /agent release",
                        spot.name(), status, a.name()));
                retryWait = 20 * 60 * 5; // try again in 5 minutes
                failures = 0;
            } else {
                retryWait = 20 * 30;
            }
            return;
        }
        // Spot coordinates are the block center; the pathfinder wants the block.
        a.navigate((int) Math.floor(spot.x()), (int) Math.floor(spot.y() + 0.01), (int) Math.floor(spot.z()), 0);
        tripStarted = true;
    }

    private int retryWait;
    private int failures;
    private boolean tripStarted;

    /** Shore spots other fishers picked (agent -> stand block), so two don't share one. */
    static final java.util.Map<String, net.minecraft.core.BlockPos> AUTO = new java.util.concurrent.ConcurrentHashMap<>();
    /** Shore spots that couldn't be reached (pos -> when). */
    private final java.util.Map<Long, Long> badShore = new java.util.HashMap<>();

    /**
     * Any open water nearby: a dry block on the shore (solid ground, room to stand) next to water
     * whose surface is open sky above and has water 2-5 blocks further out in the same direction (a
     * bobber needs water to land in, and a puddle catches nothing). Facing out over that water.
     */
    private Spots.Spot waterSpot(AgentHandle a) {
        if (!(a.openWorld() instanceof dev.agentnet.pathing.BlockView view)) return null;
        net.minecraft.world.level.block.Block WATER = net.minecraft.world.level.block.Blocks.WATER;
        java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> water =
                st -> st != null && st.getBlock() == WATER && st.getFluidState().isSource();
        List<net.minecraft.core.BlockPos> found = view.findAll((int) Math.floor(a.x()), (int) Math.floor(a.z()), 48, water, 4000);
        found.removeIf(p -> Math.abs(p.getY() - a.y()) > 24);
        found.sort(java.util.Comparator.comparingDouble(p -> p.distToCenterSqr(a.x(), a.y(), a.z())));
        long now = System.currentTimeMillis();
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int checked = 0;
        for (net.minecraft.core.BlockPos w : found) {
            if (++checked > 1500) break;
            var above = view.get(w.getX(), w.getY() + 1, w.getZ());
            if (above == null || !above.isAir()) continue; // the surface, not deep inside
            for (int[] d : dirs) {
                // Stand on the shore block on the other side of this water block (w - d), level with the surface.
                int sx = w.getX() - d[0], sz = w.getZ() - d[1], sy = w.getY() + 1;
                var ground = view.get(sx, sy - 1, sz);
                var feet = view.get(sx, sy, sz);
                var head = view.get(sx, sy + 1, sz);
                if (ground == null || feet == null || head == null) continue;
                if (!dev.agentnet.pathing.Rules.canStandOn(ground) || ground.getBlock() == WATER) continue;
                if (!feet.isAir() || !head.isAir()) continue;
                // Open water out in front: 2..5 blocks of surface water that way.
                int open = 0;
                for (int k = 1; k <= 5; k++) if (water.test(view.get(w.getX() + d[0] * k, w.getY(), w.getZ() + d[1] * k))) open++;
                if (open < 4) continue;
                net.minecraft.core.BlockPos stand = new net.minecraft.core.BlockPos(sx, sy, sz);
                Long bad = badShore.get(stand.asLong());
                if (bad != null && now - bad < 10 * 60_000) continue;
                boolean taken = false;
                for (var e : AUTO.entrySet()) if (!e.getKey().equalsIgnoreCase(a.name()) && e.getValue().distSqr(stand) < 3 * 3) { taken = true; break; }
                if (taken) continue;
                AUTO.put(a.name().toLowerCase(), stand);
                float yaw = (float) Math.toDegrees(Math.atan2(-d[0], d[1]));
                return new Spots.Spot(Spots.Type.FISHING, a.server(), a.dimension(), sx + 0.5, sy, sz + 0.5, yaw, 25f,
                        "water " + sx + " " + sy + " " + sz);
            }
        }
        return null;
    }

    /** The water spot we walked to turned out unreachable: try another one. */
    private void dropAutoSpot(AgentHandle a) {
        if (spot == null || !spot.name().startsWith("water ")) return;
        badShore.put(net.minecraft.core.BlockPos.containing(spot.x(), spot.y(), spot.z()).asLong(), System.currentTimeMillis());
        AUTO.remove(a.name().toLowerCase());
        spot = null;
        go(State.FIND_SPOT);
    }

    /** Log a problem once per state, not every tick. */
    private void warn(AgentHandle a, String message) {
        if (message.equals(lastWarning)) return;
        lastWarning = message;
        a.log(message);
    }

    /** Fish caught since this agent became a fisher (for status displays). */
    int caught() { return caught; }
}
