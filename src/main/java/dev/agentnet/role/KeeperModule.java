package dev.agentnet.role;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.agentnet.bus.AgentBus;
import dev.agentnet.bus.Message;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Storage keeper: lives at the crew's base and never leaves it. It knows what's in every base
 * chest (it looks into each one when it starts, and keeps the list up to date with everything it
 * puts in and takes out), and it's the only one who touches them:
 * <ul>
 *   <li><b>deposits</b>: a distributor comes to the hub and drops its load; the keeper picks it
 *       up and sorts it in, one kind of item per chest (a kind that's already stored goes with
 *       the rest of it, a new kind gets an empty chest);</li>
 *   <li><b>requests</b>: a distributor asks for an item; the keeper takes it out of the chest that
 *       has it and throws it to the distributor at the hub (or answers "none available").</li>
 * </ul>
 * Distributors are the mail: they carry things between the keeper and everyone else.
 */
final class KeeperModule implements RoleModule {
    private enum State { WAIT_BASE, HOME, INDEX, IDLE, COLLECT, STORE_GO, STORE_PUT, FETCH_GO, FETCH_TAKE, HAND }

    private State state = State.WAIT_BASE;
    private int timer;
    private String status = "";
    private Bases.Base base;
    private final Deque<BlockPos> toIndex = new ArrayDeque<>();
    private BlockPos chest;
    private String storeKind;
    private final Deque<Message> requests = new ArrayDeque<>();
    private Message request;
    private int taken, handed;
    private final Pickup pickup = new Pickup();
    private String depositFrom;
    private long depositUntil;

    @Override
    public void onStart(AgentHandle a) { a.keepWorld(true); }

    @Override
    public void onStop(AgentHandle a) { a.keepWorld(false); if (a.isNavigating()) a.stopNavigation(); if (a.containerOpen()) a.closeContainer(); }

    @Override
    public String status() { return status; }

    @Override
    public void onMessage(AgentHandle a, Message m) {
        if (m.type() == Message.Type.DEPOSIT) {
            depositFrom = m.from();
            depositUntil = System.currentTimeMillis() + 30_000;
            a.log(m.from() + " is dropping off its load");
        }
        if (m.type() == Message.Type.DROP_DONE && m.from().equalsIgnoreCase(depositFrom)) pickup.start();
        if (m.type() == Message.Type.KEEPER_REQUEST) requests.add(m);
    }

    // ------------------------------------------------------------------ the chest list

    /** What the keeper knows is in this base chest (null = never looked). */
    private static List<ItemStack> contents(BlockPos c) { return ChestMemory.CONTENTS.get(c); }

    private int count(String item) {
        int n = 0;
        for (BlockPos c : chests(base)) {
            List<ItemStack> l = contents(c);
            if (l != null) for (ItemStack s : l) if (Gear.matches(s, item)) n += s.getCount();
        }
        return n;
    }

    /** The other half of a double chest (the base's chests stand side by side, so they pair up): its contents are
     *  the same container, counted once under the first half. */
    static final Set<BlockPos> HALVES = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Base chests that aren't there (the builders left a gap): never used until a chest stands there again. */
    static final Set<BlockPos> MISSING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The base's chests, one entry per container. */
    static List<BlockPos> chests(Bases.Base b) { return b.chests().stream().filter(c -> !HALVES.contains(c) && !MISSING.contains(c)).toList(); }

    /** Is there a chest at this spot? null while that part of the world isn't loaded for this agent. */
    private static Boolean chestAt(AgentHandle a, BlockPos c) {
        var w = a.openWorld();
        var st = w == null ? null : w.get(c.getX(), c.getY(), c.getZ());
        return st == null ? null : st.getBlock() instanceof net.minecraft.world.level.block.ChestBlock;
    }

    /** Note the other half of this chest, if it's a double one. */
    private void pairUp(AgentHandle a, BlockPos c) {
        var w = a.openWorld();
        var st = w == null ? null : w.get(c.getX(), c.getY(), c.getZ());
        if (st == null || !(st.getBlock() instanceof net.minecraft.world.level.block.ChestBlock)) return;
        if (st.getValue(net.minecraft.world.level.block.ChestBlock.TYPE) == net.minecraft.world.level.block.state.properties.ChestType.SINGLE) return;
        BlockPos other = c.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(st));
        if (HALVES.contains(c) || !base.chests().contains(other)) return;
        HALVES.add(other);
        ChestMemory.CONTENTS.remove(other);
    }

    /** Everything stored, for the master's state and for "who has X" questions. */
    static Map<String, Integer> stock(String server, String dimension) {
        Map<String, Integer> out = new HashMap<>();
        Bases.Base b = Bases.built(server, dimension);
        if (b == null) return out;
        for (BlockPos c : chests(b)) {
            List<ItemStack> l = ChestMemory.CONTENTS.get(c);
            if (l != null) for (ItemStack s : l) if (!s.isEmpty()) out.merge(Gear.id(s), s.getCount(), Integer::sum);
        }
        return out;
    }

    // ------------------------------------------------------------------ work

    @Override
    public void tick(AgentHandle a) {
        timer++;
        if (pickup.tick(a)) { status = "picking up a delivery"; return; }
        switch (state) {
            case WAIT_BASE -> {
                status = "waiting for the crew's base to be built";
                if (timer % 100 != 1) return;
                if (a.dimension() == null) { timer = 0; return; } // just joined: where it is isn't known yet
                // The crew's finished base: near this keeper, or near anyone in the crew (a keeper that just
                // joined may stand at world spawn, far from the base it's meant to keep). Same test as BaseWork.
                base = Bases.crewBuilt(a.server(), a.dimension());
                if (base == null) {
                    // No storage to keep yet: until the base is finished, carry things around like a distributor
                    // (the base itself needs deliveries to go up). It comes back to this job when it's done.
                    BaseWork.ensure(a);
                    PENDING.add(a.name().toLowerCase());
                    a.log("no finished base yet: working as a distributor until there is one");
                    a.changeRole(Role.DISTRIBUTOR);
                    return;
                }
                a.log("keeping the base's storage (" + base.chests().size() + " chests)");
                go(State.HOME);
            }
            case HOME -> {
                status = "walking to the base";
                if (near(a, base.hub(), 3)) { if (a.isNavigating()) a.stopNavigation(); toIndex.clear(); toIndex.addAll(base.chests()); go(State.INDEX); return; }
                if (!a.isNavigating() && timer % 40 == 1) a.navigate(base.hub().getX(), base.hub().getY(), base.hub().getZ(), 1);
            }
            case INDEX -> index(a);
            case IDLE -> idle(a);
            case COLLECT -> collect(a);
            case STORE_GO, FETCH_GO -> walkToChest(a);
            case STORE_PUT -> storePut(a);
            case FETCH_TAKE -> fetchTake(a);
            case HAND -> hand(a);
        }
    }

    private void go(State s) { state = s; timer = 0; }

    private static boolean near(AgentHandle a, BlockPos p, double r) {
        return p.distToCenterSqr(a.x(), a.y() + 0.5, a.z()) <= r * r;
    }

    /** Look into every chest once (the list of what's where). */
    private void index(AgentHandle a) {
        status = "taking stock: " + (base.chests().size() - toIndex.size()) + "/" + base.chests().size() + " chests";
        if (chest == null) {
            while (!toIndex.isEmpty() && contents(toIndex.peek()) != null && timer > 1) toIndex.poll(); // known already
            if (toIndex.isEmpty()) {
                long gone = base.chests().stream().filter(MISSING::contains).count();
                if (gone > 0) {
                    // Chests broken or never placed: the base isn't finished any more, so the builders put them back.
                    Bases.put(base.withBuilt(false));
                    a.log("§ethe base storage is missing " + gone + " of " + base.chests().size() + " chests: the builders have to put them back");
                    if (chests(base).size() < 4) {
                        base = null;
                        go(State.WAIT_BASE); // nothing worth keeping yet: carry things as a distributor until it's fixed
                        return;
                    }
                }
                a.log("knows what's in all " + chests(base).size() + " chests: " + stock(a.server(), a.dimension()).size() + " kinds of items");
                go(State.IDLE);
                return;
            }
            chest = toIndex.poll();
            if (Boolean.FALSE.equals(chestAt(a, chest))) {
                // The builders left a gap here: skip it at once (and never store into it) instead of clicking at air.
                if (MISSING.add(chest)) a.log("§eno chest at " + chest.getX() + " " + chest.getY() + " " + chest.getZ() + " (the base is missing it)");
                ChestMemory.CONTENTS.remove(chest);
                chest = null;
                return;
            }
            MISSING.remove(chest);
            pairUp(a, chest);
            if (HALVES.contains(chest)) { chest = null; return; } // the other half of one already counted
            timer = 0;
        }
        if (openChest(a, chest)) {
            if (timer < 4) return; // let the contents arrive
            ChestMemory.CONTENTS.put(chest, a.containerSlots().stream().map(ItemStack::copy).toList());
            a.closeContainer();
            chest = null;
        }
        if (timer > 6 * 20) { a.log("couldn't open the chest at " + chest.getX() + " " + chest.getY() + " " + chest.getZ() + ": skipping it for now"); chest = null; } // couldn't open it: skip
    }

    /** Walk within reach and open it; true once it's open. */
    private boolean openChest(AgentHandle a, BlockPos c) {
        if (a.containerOpen()) return true;
        double dx = c.getX() + 0.5 - a.x(), dy = c.getY() + 0.5 - (a.y() + 1.62), dz = c.getZ() + 0.5 - a.z();
        if (dx * dx + dy * dy + dz * dz > 4.0 * 4.0) {
            if (!a.isNavigating() && timer % 20 == 1) a.navigate(c.getX(), c.getY(), c.getZ(), 2);
            return false;
        }
        if (a.isNavigating()) a.stopNavigation();
        if (timer % 10 == 1 || timer < 3) {
            a.lookAt(c.getX() + 0.5, c.getY() + 0.5, c.getZ() + 0.5);
            a.useItemOn(c.getX(), c.getY(), c.getZ(), Direction.UP);
        }
        return false;
    }

    private void idle(AgentHandle a) {
        // Anything to put away? (A deposit picked up, or leftovers.)
        String kind = nextKind(a);
        if (kind != null) { storeKind = kind; chest = chestFor(a, kind); if (chest != null) { go(State.STORE_GO); return; } }
        if (depositFrom != null && System.currentTimeMillis() < depositUntil) {
            status = "waiting for " + depositFrom + "'s load";
            if (!near(a, base.hub(), 3) && !a.isNavigating() && timer % 40 == 1) a.navigate(base.hub().getX(), base.hub().getY(), base.hub().getZ(), 1);
            return;
        }
        depositFrom = null;
        if (!requests.isEmpty()) {
            request = requests.poll();
            String item = request.get("item");
            int want = Math.max(1, parse(request.get("count")));
            if (item == null || count(item) == 0) {
                AgentBus.send(Message.of(a.name(), request.from(), Message.Type.TEXT, "none available: " + item, Map.of("item", String.valueOf(item))));
                a.log("has no " + item + " in storage for " + request.from());
                request = null;
                return;
            }
            chest = chestWith(item);
            taken = 0;
            handed = 0;
            a.log("getting " + want + "x " + item.replace("minecraft:", "") + " out of storage for " + request.from());
            go(State.FETCH_GO);
            return;
        }
        status = "keeping storage (" + stock(a.server(), a.dimension()).values().stream().mapToInt(Integer::intValue).sum() + " items in " + base.chests().size() + " chests)";
        if (!near(a, base.hub(), 3) && !a.isNavigating() && timer % 100 == 1) a.navigate(base.hub().getX(), base.hub().getY(), base.hub().getZ(), 1);
    }

    private static int parse(String s) { try { return Integer.parseInt(s); } catch (RuntimeException e) { return 1; } }

    /** The kind of item we carry the most of that isn't ours to keep (food, tools). */
    private String nextKind(AgentHandle a) {
        Map<String, Integer> kinds = new HashMap<>();
        List<ItemStack> inv = a.inventory();
        Set<Integer> keep = keep(inv);
        for (int i = 0; i < inv.size(); i++) if (!inv.get(i).isEmpty() && !keep.contains(i)) kinds.merge(Gear.id(inv.get(i)), inv.get(i).getCount(), Integer::sum);
        return kinds.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    /** One food stack stays with the keeper; everything else (a tool thrown to someone next to it too) goes into storage. */
    private static Set<Integer> keep(List<ItemStack> inv) {
        Set<Integer> keep = new HashSet<>();
        int food = -1;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (Gear.isFood(s) && food < 0) food = i;
        }
        if (food >= 0) keep.add(food);
        return keep;
    }

    /** The chest for this kind: one that already has it (with room), else an empty one, else any with room. */
    private BlockPos chestFor(AgentHandle a, String kind) {
        BlockPos empty = null, any = null;
        for (BlockPos c : chests(base)) {
            List<ItemStack> l = contents(c);
            boolean room = l == null || l.stream().anyMatch(ItemStack::isEmpty);
            if (!room) continue;
            if (l != null && l.stream().anyMatch(s -> Gear.id(s).equals(kind))) return c;
            if (empty == null && (l == null || l.stream().allMatch(ItemStack::isEmpty))) empty = c;
            if (any == null) any = c;
        }
        return empty != null ? empty : any;
    }

    private BlockPos chestWith(String item) {
        for (BlockPos c : chests(base)) {
            List<ItemStack> l = contents(c);
            if (l != null && l.stream().anyMatch(s -> Gear.matches(s, item))) return c;
        }
        return null;
    }

    private void collect(AgentHandle a) { go(State.IDLE); }

    private void walkToChest(AgentHandle a) {
        status = (state == State.STORE_GO ? "putting away " + storeKind : "fetching " + request.get("item")) + " (" + chest.toShortString() + ")";
        if (timer > 20 * 30) { if (a.containerOpen()) a.closeContainer(); go(State.IDLE); return; }
        if (openChest(a, chest)) go(state == State.STORE_GO ? State.STORE_PUT : State.FETCH_TAKE);
    }

    private void storePut(AgentHandle a) {
        if (!a.containerOpen()) { go(State.IDLE); return; }
        if (timer < 3 || timer % 2 != 0) return;
        List<ItemStack> inv = a.inventory();
        Set<Integer> keep = keep(inv);
        boolean full = a.containerSlots().stream().noneMatch(ItemStack::isEmpty);
        if (!full) for (int i = 0; i < inv.size(); i++) {
            if (inv.get(i).isEmpty() || keep.contains(i) || !Gear.id(inv.get(i)).equals(storeKind)) continue;
            a.quickMoveToContainer(i);
            return;
        }
        ChestMemory.CONTENTS.put(chest, a.containerSlots().stream().map(ItemStack::copy).toList());
        a.closeContainer();
        dev.agentnet.AgentNet.LOG.info("[{}] stored {} in {}", a.name(), storeKind, chest.toShortString());
        chest = null;
        go(State.IDLE);
    }

    private void fetchTake(AgentHandle a) {
        if (!a.containerOpen()) { go(State.IDLE); return; }
        if (timer < 4 || timer % 2 != 0) return;
        String item = request.get("item");
        int want = Math.max(1, parse(request.get("count")));
        List<ItemStack> slots = a.containerSlots();
        if (taken < want) for (int i = 0; i < slots.size(); i++) {
            if (!Gear.matches(slots.get(i), item)) continue;
            taken += slots.get(i).getCount();
            a.quickMoveContainerSlot(i);
            return;
        }
        ChestMemory.CONTENTS.put(chest, a.containerSlots().stream().map(ItemStack::copy).toList());
        a.closeContainer();
        chest = null;
        if (taken < want && count(item) > 0) { chest = chestWith(item); if (chest != null) { go(State.FETCH_GO); return; } }
        go(State.HAND);
    }

    /** Throw the fetched items to the distributor who asked (it comes to the hub). */
    private void hand(AgentHandle a) {
        String item = request.get("item");
        AgentHandle to = null;
        for (AgentHandle h : AgentBus.agents.get()) if (h.name().equalsIgnoreCase(request.from())) to = h;
        status = "handing " + item + " to " + request.from();
        if (to == null || timer > 20 * 60) { a.log(request.from() + " didn't come for its " + item); request = null; go(State.IDLE); return; }
        double d = Math.sqrt(AgentBus.distSq(a, to));
        if (d > 3) {
            if (!a.isNavigating() && timer % 20 == 1) a.navigate((int) Math.floor(to.x()), (int) Math.floor(to.y()), (int) Math.floor(to.z()), 2);
            return;
        }
        if (a.isNavigating()) a.stopNavigation();
        if (timer % 3 != 0) return;
        // Exactly what was asked for: whole stacks while they fit, then one by one. The rest goes back into storage.
        int want = Math.max(1, parse(request.get("count")));
        List<ItemStack> inv = a.inventory();
        if (handed < want) for (int i = 0; i < inv.size(); i++) {
            if (!Gear.matches(inv.get(i), item)) continue;
            a.lookAt(to.x(), to.y() + 0.5, to.z());
            int n = inv.get(i).getCount();
            if (n <= want - handed) { a.dropStack(i); handed += n; }
            else { a.dropOne(i); handed++; n = 1; }
            AgentBus.send(Message.of(a.name(), to.name(), Message.Type.ITEM_DELIVERED, "from storage", Map.of("item", item, "count", String.valueOf(n))));
            return;
        }
        handed = 0;
        a.log("handed " + item.replace("minecraft:", "") + " to " + to.name());
        request = null;
        go(State.IDLE);
    }

    /** Keepers working as distributors while there's no finished base (lower-case names). */
    static final java.util.Set<String> PENDING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** A keeper online in this dimension, if any. */
    static AgentHandle keeperFor(AgentHandle a) {
        for (AgentHandle h : AgentBus.agents.get()) if (h.isOnline() && h.role() == Role.KEEPER && dev.agentnet.role.AgentHandle.sameDim(h.dimension(), a.dimension())) return h;
        return null;
    }

    @Override
    public boolean idle() { return false; } // its place is the base anyway

    Vec3 hubVec() { return base == null ? null : Vec3.atBottomCenterOf(base.hub()); }
}
