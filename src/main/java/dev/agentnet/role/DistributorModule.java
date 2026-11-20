package dev.agentnet.role;

import java.util.ArrayDeque;
import java.util.ArrayList;
import net.minecraft.world.level.block.Blocks;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.agentnet.bus.AgentBus;
import dev.agentnet.bus.Message;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Distributor: the crew's logistics.
 * <ul>
 *   <li><b>Pickups</b>: a farmer/fisher says its inventory is filling up. Walk over, say
 *       COLLECT_DROP; it throws everything but its job items; pick it all up.</li>
 *   <li><b>Deliveries</b>: someone asks for an item ("food", a hoe, a rod, ...). Find it in
 *       the inventory, or in a carried shulker box; walk over and throw it to them.</li>
 *   <li><b>Storage</b>: when its own inventory gets full, it places a shulker box next
 *       to itself, opens it, packs the produce in, mines the box and picks it back up.</li>
 * </ul>
 * Main thread only.
 */
final class DistributorModule implements RoleModule {
    /** "Come collect" alerts from farmers/miners/fishers, oldest first. */
    final Deque<Message> pickups = new ArrayDeque<>();
    /** Item requests from builders etc., oldest first. */
    final Deque<Message> deliveries = new ArrayDeque<>();

    private enum State { BASE_RUN, KEEPER_GO, KEEPER_DROP, KEEPER_FETCH, IDLE, BACK_OFF, GO_PICKUP, WAIT_DROP, COLLECT, GO_DELIVER, SHULKER_PLACE, SHULKER_OPEN, SHULKER_PACK, SHULKER_TAKE, SHULKER_MINE, SHULKER_PICKUP, LEAVE_SPAWN, CHEST_GO, CHEST_OPEN, CHEST_PUT, CHEST_TAKE }
    /** Spots tried for the current box (a refused one isn't tried again). */
    private final Set<BlockPos> triedSpots = new HashSet<>();
    /** After every spot failed: don't try boxes again until then. */
    private long placeBackoffUntil;
    /** What the box was for while it walks out of spawn protection. */
    private String pendingPurpose;
    private static final int MAX_PLACE_TRIES = 4;
    private static final long PLACE_BACKOFF_MS = 60_000;

    private static final double PACK_AT = 0.75;
    private static final double HANDOVER_DIST = 2.5;
    /** Deliveries are thrown from close by, so they land where the receiver stands. */
    private static final double THROW_DIST = 1.4;

    private State state = State.IDLE;

    /** Nothing queued and nothing under way: free for another job. */
    @Override public boolean idle() { return state == State.IDLE && pickups.isEmpty() && deliveries.isEmpty(); }
    private int timer;
    private Message job;
    private String shulkerPurpose = "";  // "pack" or the item id wanted
    private BlockPos shulkerPos;
    private int shulkerItemCount;
    private Vec3 collectTarget;
    private final Set<Long> triedItems = new HashSet<>();
    private int delivered, collected;
    private String lastWarning;

    @Override
    public void onStart(AgentHandle a) {
        a.keepWorld(true); // places and mines shulker boxes: needs to see the blocks around it
        state = State.IDLE;
    }

    @Override
    public void onStop(AgentHandle a) {
        a.keepWorld(false);
        if (a.containerOpen()) a.closeContainer();
        if (a.isNavigating()) a.stopNavigation();
    }

    @Override
    public void onMessage(AgentHandle a, Message m) {
        switch (m.type()) {
            case INVENTORY_FULL -> {
                pickups.removeIf(old -> old.from().equals(m.from())); // keep only the latest per agent
                pickups.add(m);
                a.log("pickup queued for " + m.from() + " (" + pickups.size() + " waiting)");
            }
            case GROUND_PICKUP -> {
                // A load left on the ground for us: it despawns in 5 minutes, so it goes before everything else.
                pickups.addFirst(m);
                a.log(m.from() + " left " + m.get("items") + " on the ground at " + m.get("x") + " " + m.get("y") + " " + m.get("z") + ": fetching it");
            }
            case ITEM_REQUEST -> {
                if (m.get("item") != null && m.get("item").startsWith("pickaxe:") && !pickaxeInStock(a, parse(m.get("item").split(":")[1], 1))) {
                    // Answer right away, even mid-craft: the miner shouldn't wait on us to say no.
                    a.log("no pickaxe in stock for " + m.from() + ": it'll make its own");
                    AgentBus.send(Message.of(a.name(), m.from(), Message.Type.TEXT, "none available: pickaxe", Map.of()));
                    return;
                }
                Long gone = unavailable.get(m.get("item"));
                AgentHandle asker = GathererModule.find(m.from());
                long quiet = asker != null && asker.role() == Role.BUILDER ? 45_000 : 5 * 60_000; // the base's materials come in bit by bit: look again soon
                if (gone != null && System.currentTimeMillis() - gone < quiet) return; // found none a moment ago: don't look again yet
                deliveries.removeIf(old -> old.from().equals(m.from()) && old.get("item").equals(m.get("item")));
                // Builders first (no base = no storage, keeper or portal), then the keeper and miners, warriors last.
                List<Message> queue = new ArrayList<>(deliveries);
                queue.add(m);
                queue.sort(java.util.Comparator.comparingInt(DistributorModule::priority)); // stable: same rank keeps its order
                deliveries.clear();
                deliveries.addAll(queue);
                a.log("delivery queued: " + m.get("count") + "x " + m.get("item") + " for " + m.from());
            }
            case DROP_DONE -> {
                if (state == State.WAIT_DROP && job != null && job.from().equalsIgnoreCase(m.from())) startCollect(a);
            }
            case TEXT -> {
                if (state == State.KEEPER_FETCH && m.text().startsWith("none available")) { a.log("the keeper has none: " + m.get("item")); unavailable.put(m.get("item"), System.currentTimeMillis()); deliveries.removeIf(d -> m.get("item") != null && m.get("item").equals(d.get("item"))); go(State.IDLE); }
            }
            case ITEM_DELIVERED -> { if (state == State.KEEPER_FETCH) keeperPickup.start(); }
            case CHEST_FOUND -> {
                if (m.get("x") != null) {
                    ChestMemory.remember(a.server(), m.get("dimension"), Integer.parseInt(m.get("x")),
                            Integer.parseInt(m.get("y")), Integer.parseInt(m.get("z")), "reported by " + m.from());
                }
            }
            default -> {}
        }
    }

    /** Crafting (and smelting etc.) for /agent make and for requests it has no stock for. */
    private final MakerModule crafter = new MakerModule();
    private int pickCheck;
    /** Its own woodcutting for a pickaxe, when nobody has wood for it. */
    private final MinerModule woodcutter = new MinerModule();
    { woodcutter.keepsLoad = true; }
    private boolean chopping, chopStarted;
    private int chopTicks;
    private long pickAskedAt, pickCraftAt;
    private boolean nowCraftOk() { return System.currentTimeMillis() - pickCraftAt > 60_000; }
    private final Map<String, Long> craftTriedAt = new java.util.HashMap<>();
    private static final long CRAFT_RETRY_MS = 2 * 60_000;

    @Override
    public void onTask(AgentHandle a, String task) {
        String p = task.trim().split("\\s+")[0];
        if (p.equals("make") || p.equals("brew")) crafter.onTask(a, task);
    }

    private int smeltChecks;

    /**
     * Raw ore collected from miners (8 or more) and something to burn: smelt it into ingots,
     * in a furnace nearby or one it carries or makes from cobblestone. About every 30 s idle.
     */
    private boolean autoSmelt(AgentHandle a) {
        boolean fuel = a.inventory().stream().anyMatch(s -> { String id = Gear.id(s);
            return id.equals("minecraft:coal") || id.equals("minecraft:charcoal") || id.endsWith("_planks") || id.endsWith("_log"); });
        if (!fuel) return false;
        String[][] ores = {{"minecraft:raw_iron", "minecraft:iron_ingot"}, {"minecraft:raw_gold", "minecraft:gold_ingot"}, {"minecraft:raw_copper", "minecraft:copper_ingot"}};
        for (String[] o : ores) {
            if (War.have(a, o[0]) < 8) continue;
            Long at = craftTriedAt.get(o[1]);
            if (at != null && System.currentTimeMillis() - at < CRAFT_RETRY_MS) continue;
            craftTriedAt.put(o[1], System.currentTimeMillis());
            a.log("smelting its " + o[0].replace("minecraft:", ""));
            crafter.queueAll(a, o[1]);
            return true;
        }
        return false;
    }

    /** Nobody has this item in stock: make it (tools get their sticks/planks made too). */
    private boolean tryCraft(AgentHandle a, String item) {
        if (!item.contains(":")) return false; // "food" etc.: no single recipe
        long now = System.currentTimeMillis();
        Long at = craftTriedAt.get(item);
        if (at != null && now - at < CRAFT_RETRY_MS) return false;
        craftTriedAt.put(item, now);
        var it = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(item));
        boolean tool = Gear.toolType(new ItemStack(it)) != null;
        boolean queued = tool ? crafter.queueTool(a, item) : MakerModule.canMake(a, item);
        if (!tool && queued) crafter.queue(a, item);
        if (queued) a.log("has no " + item + " in stock: crafting it");
        return queued;
    }

    @Override
    public void tick(AgentHandle a) {
        timer++;
        // Crafting (its own pickaxe above all) goes ahead whatever it is doing, except in the middle of a handover.
        if (crafter.busy() && state != State.WAIT_DROP && state != State.COLLECT && state != State.KEEPER_DROP) { if (a.isNavigating()) a.stopNavigation(); crafter.tick(a); return; }
        // A pickaxe before going anywhere: a pickup or delivery underground without one left distributors stuck in the
        // first cave. Stone if it carries 3 cobblestone, else wooden (it carries wood for chests anyway).
        if (++pickCheck % 200 == 0 && Gear.bestTool(a.inventory(), "pickaxe") < 0) {
            int cobble = 0, wood = 0, sticks = 0;
            for (net.minecraft.world.item.ItemStack s : a.inventory()) {
                String id = Gear.id(s);
                if (id.equals("minecraft:cobblestone") || id.equals("minecraft:cobbled_deepslate")) cobble += s.getCount();
                else if (id.endsWith("_planks")) wood += s.getCount();
                else if (id.endsWith("_log") && !id.contains("stripped")) wood += 4 * s.getCount();
                else if (id.equals("minecraft:stick")) sticks += s.getCount();
            }
            String pick = cobble >= 3 ? "minecraft:stone_pickaxe" : "minecraft:wooden_pickaxe";
            boolean table = false;
            for (net.minecraft.world.item.ItemStack s : a.inventory()) if (Gear.id(s).equals("minecraft:crafting_table")) table = true;
            // Enough for the whole thing: head (3 planks or cobblestone), sticks (2 planks), and a table (4 planks) unless it carries one.
            int needWood = (cobble >= 3 ? 0 : 3) + (sticks >= 2 ? 0 : 2) + (table ? 0 : 4);
            boolean canMake = wood >= needWood && nowCraftOk();
            long nowP = System.currentTimeMillis();
            if (!canMake) {
                if (nowP - pickAskedAt > 120_000) { pickAskedAt = nowP; BuilderModule.requestMaterial(a, "minecraft:stone_pickaxe", 1); a.log("no pickaxe and nothing to make one from: asking for one"); }
                // And not waiting for it: the other distributor has none either (they asked each other, and both stood
                // at the mouth of the mine for minutes). A tree gives it all it needs.
                if (!chopping && wood < needWood && state != State.WAIT_DROP && state != State.COLLECT && state != State.KEEPER_DROP) {
                    chopping = true; chopTicks = 0;
                    if (!chopStarted) { chopStarted = true; woodcutter.onStart(a); }
                    if (a.isNavigating()) a.stopNavigation();
                    woodcutter.onTask(a, "mine 3 logs");
                    a.log("no pickaxe: chopping a tree for one itself");
                }
            } else if (crafter.queueTool(a, pick) && (pickCraftAt = System.currentTimeMillis()) > 0) { a.log("no pickaxe: making a " + pick.replace("minecraft:", "").replace('_', ' ') + " before going anywhere"); if (a.isNavigating()) a.stopNavigation(); return; }
        }
        if (chopping) {
            int woodNow = 0;
            for (net.minecraft.world.item.ItemStack s : a.inventory()) {
                String id = Gear.id(s);
                if (id.endsWith("_planks")) woodNow += s.getCount();
                else if (id.endsWith("_log") && !id.contains("stripped")) woodNow += 4 * s.getCount();
            }
            if (Gear.bestTool(a.inventory(), "pickaxe") >= 0 || woodNow >= 12 || ++chopTicks > 20 * 240) {
                chopping = false;
                woodcutter.onTask(a, "idle");
                if (a.isNavigating()) a.stopNavigation();
                pickCheck = 199; // the pickaxe right away
            } else { woodcutter.tick(a); return; }
        }
        // A pickaxe at all times, on the road too (its old one broke, or it never had one): without it it can't dig out.
        if (timer % 100 == 50 && state != State.IDLE && System.currentTimeMillis() >= pickaxeRetryAt && ownPickaxe(a)) return;
        if (++linkTicks % 20 == 0) markLinks(a);
        switch (state) {
            case IDLE -> idle(a);
            case BACK_OFF -> { if (timer > 50) go(State.IDLE); }
            case GO_PICKUP -> goPickup(a);
            case BASE_RUN -> { if (!baseRun.tick(a)) go(State.IDLE); }
            case WAIT_DROP -> { if (timer > 20 * 20) startCollect(a); } // no DROP_DONE: collect what's there
            case COLLECT -> collect(a);
            case GO_DELIVER -> goDeliver(a);
            case SHULKER_PLACE -> { if (timer > 10 && shulkerAt(a, shulkerPos)) open(a); else if (timer > 30) retryPlace(a); }
            case LEAVE_SPAWN -> leaveSpawn(a);
            case KEEPER_GO, KEEPER_DROP -> keeperDrop(a);
            case KEEPER_FETCH -> keeperFetch(a);
            case CHEST_GO -> chestGo(a);
            case CHEST_OPEN -> chestOpen(a);
            case CHEST_PUT -> chestPut(a);
            case CHEST_TAKE -> chestTake(a);
            case SHULKER_OPEN -> {
                if (a.containerOpen() && a.containerType().contains("shulker")) { go(shulkerPurpose.equals("pack") ? State.SHULKER_PACK : State.SHULKER_TAKE); }
                else if (timer % 20 == 0) a.useItemOn(shulkerPos.getX(), shulkerPos.getY(), shulkerPos.getZ(), Direction.UP);
                if (timer > 100) { a.log("the shulker box didn't open"); mine(a); }
            }
            case SHULKER_PACK -> pack(a);
            case SHULKER_TAKE -> take(a);
            case SHULKER_MINE -> {
                if (a.isDigging()) return;
                if (timer > 5) { go(State.SHULKER_PICKUP); a.navigate(shulkerPos.getX(), shulkerPos.getY(), shulkerPos.getZ(), 0); }
            }
            case SHULKER_PICKUP -> {
                boolean picked = a.inventory().stream().filter(Gear::isShulker).count() >= shulkerItemCount;
                if (picked || (!a.isNavigating() && timer > 60) || timer > 200) {
                    if (picked && lastSeen != null) {
                        List<ItemStack> inv = a.inventory();
                        for (int i = 0; i < inv.size(); i++) {
                            if (Gear.isShulker(inv.get(i)) && !shulkerSlotsBefore.contains(i)) { boxMemory.put(i, lastSeen); break; }
                        }
                    }
                    if (!picked) a.log("lost track of its shulker box at " + shulkerPos.toShortString());
                    shulkerPos = null;
                    go(State.IDLE);
                }
            }
        }
    }

    // ------------------------------------------------------------------ choosing work

    /** Delivery order by who asks: builder 0, keeper 1, miner 2, others 3, warrior 4. */
    private static int priority(Message m) {
        AgentHandle h = GathererModule.find(m.from());
        Role r = h == null ? null : h.role();
        // The main speedrunner, until the dragon is dead: before everyone, the base included.
        if (m.from().equalsIgnoreCase(String.valueOf(SpeedrunnerModule.priorityRunner()))) return -1;
        if (r == Role.BUILDER) return 0;
        if (r == Role.KEEPER) return 1;
        if (r == Role.MINER) return 2;
        if (r == Role.WARRIOR) return 4;
        return 3;
    }

    private long pickaxeRetryAt;
    private int linkTicks;

    /** For the supply-chain map: who waits on this distributor, and whom it's collecting from or bringing to. */
    private void markLinks(AgentHandle a) {
        for (Message m : deliveries) Links.mark(m.from(), a.name(), Links.Kind.WAITING, "waiting for " + m.get("count") + "x " + String.valueOf(m.get("item")).replace("minecraft:", ""));
        if (job != null && (state == State.GO_PICKUP || state == State.WAIT_DROP || state == State.COLLECT))
            Links.mark(a.name(), job.from(), Links.Kind.COLLECTING, job.type() == Message.Type.GROUND_PICKUP ? "fetching its load from the ground" : "collecting from it");
        if (keeper != null && (state == State.KEEPER_GO || state == State.KEEPER_DROP || state == State.KEEPER_FETCH))
            Links.mark(a.name(), keeper.name(), Links.Kind.BRINGING, state == State.KEEPER_FETCH ? "fetching from storage" : "dropping off at storage");
    }
    private final BaseDelivery baseRun = new BaseDelivery();
    /** Miners a distributor is on its way to collect from (name -> when): the others pick someone else. */
    private static final Map<String, Long> COLLECTING_FROM = new java.util.concurrent.ConcurrentHashMap<>();

    private boolean proactiveBase(AgentHandle a) {
        if (BaseDelivery.builder(a) == null) return false; // no base going up, or nobody building it
        int carry = 0;
        for (ItemStack s : a.inventory()) if (BaseDelivery.forBase(s)) carry += s.getCount();
        if (carry >= 16 && baseRun.start(a, false)) { go(State.BASE_RUN); return true; }
        long now = System.currentTimeMillis();
        AgentHandle best = null;
        String bestItem = null;
        int bestSpare = 0;
        double bestD = Double.MAX_VALUE;
        for (AgentHandle h : AgentBus.agents.get()) {
            if (!h.isOnline() || h.role() != Role.MINER || !AgentHandle.sameDim(h.dimension(), a.dimension())) continue;
            Long at = COLLECTING_FROM.get(h.name().toLowerCase());
            if (at != null && now - at < 2 * 60_000) continue; // another distributor is on its way
            Map<String, Integer> kinds = new java.util.HashMap<>();
            for (ItemStack s : h.inventory()) if (BaseDelivery.forBase(s)) kinds.merge(Gear.id(s), 0, Integer::sum);
            for (String id : kinds.keySet()) {
                int spare = Spare.spare(h, id);
                if (spare < 16) continue;
                double d = AgentBus.distSq(a, h);
                if (d < bestD) { bestD = d; best = h; bestItem = id; bestSpare = spare; }
            }
        }
        if (best == null) return false;
        COLLECTING_FROM.put(best.name().toLowerCase(), now);
        a.log("collecting " + bestSpare + "x " + bestItem.replace("minecraft:", "") + " from " + best.name() + " for the base");
        job = Message.of(best.name(), a.name(), Message.Type.HAND_OVER, "fetch", Map.of("item", bestItem, "count", String.valueOf(bestSpare)));
        go(State.GO_PICKUP);
        return true;
    }


    /** No pickaxe of its own: make one (stone if it carries 3 cobblestone, else wood). Hand-digging out of a cave takes forever. */
    private boolean ownPickaxe(AgentHandle a) {
        if (Gear.bestTool(a.inventory(), "pickaxe") >= 0) return false;
        int cobble = 0;
        for (ItemStack s : a.inventory()) { String id = Gear.id(s); if (id.equals("minecraft:cobblestone") || id.equals("minecraft:cobbled_deepslate")) cobble += s.getCount(); }
        String tool = cobble >= 3 ? "minecraft:stone_pickaxe" : "minecraft:wooden_pickaxe";
        // Enough wood for the whole job: 2 planks of sticks, 3 more for a wooden head, 4 for a crafting table unless it
        // carries one. (With less it made the sticks, had nothing left for the table, and tried again for ever.)
        int wood = 0, sticks = 0;
        boolean table = false;
        for (ItemStack s : a.inventory()) {
            String id = Gear.id(s);
            if (id.endsWith("_log") && !id.contains("stripped")) wood += 4 * s.getCount();
            else if (id.endsWith("_planks")) wood += s.getCount();
            else if (id.equals("minecraft:stick")) sticks += s.getCount();
            else if (id.equals("minecraft:crafting_table")) table = true;
        }
        int need = (table ? 0 : 4) + (sticks >= 2 ? 0 : 2) + (cobble >= 3 ? 0 : 3);
        // In order, so nothing runs out half way: all its logs into planks, a crafting table if it has none, then
        // the sticks and the pickaxe.
        boolean ok = wood >= need;
        if (ok) {
            String planks = MakerModule.planksFromLog(a);
            if (planks != null) crafter.queueAll(a, planks);
            if (!table) crafter.onTask(a, "make crafting_table 1");
            ok = crafter.queueTool(a, tool);
        }
        if (!ok) {
            // Nothing to make one from: get 3 logs from the nearest agent that can spare them (or 5 planks), then make it.
            pickaxeRetryAt = System.currentTimeMillis() + 30_000;
            if (state != State.IDLE) return false;
            AgentHandle best = null;
            String bestItem = null;
            double bestD = Double.MAX_VALUE;
            for (AgentHandle h : AgentBus.agents.get()) {
                if (h == a || !h.isOnline() || !AgentHandle.sameDim(h.dimension(), a.dimension())) continue;
                for (ItemStack s : h.inventory()) {
                    String id = Gear.id(s);
                    boolean log = id.endsWith("_log") && !id.contains("stripped"), planks = id.endsWith("_planks");
                    if (!log && !planks || Spare.spare(h, id) < (log ? 3 : 9)) continue; // enough for the table, the sticks and the head
                    double d = AgentBus.distSq(a, h);
                    if (d < bestD) { bestD = d; best = h; bestItem = id; }
                }
            }
            if (best == null) return false;
            a.log("no pickaxe and nothing to make one from: getting wood for it from " + best.name());
            job = Message.of(best.name(), a.name(), Message.Type.HAND_OVER, "fetch", Map.of("item", bestItem, "count", bestItem.endsWith("_log") ? "3" : "10"));
            go(State.GO_PICKUP);
            return true;
        }
        pickaxeRetryAt = System.currentTimeMillis() + 45_000; // not again right after a try that failed
        a.log("making itself a " + tool.replace("minecraft:", "").replace('_', ' ') + " (to dig itself out of caves)");
        return true;
    }

    private void idle(AgentHandle a) {
        if (timer % 10 != 0) return;
        if (War.active() && ++warChecks % 20 == 0 && deliveries.isEmpty() && redundant(a)) {
            a.log("every warrior has a closer distributor: takes a war kit and becomes a warrior");
            a.changeRole(Role.WARRIOR);
            return;
        }
        if (timer % 200 == 0 && KeeperModule.PENDING.contains(a.name().toLowerCase()) && deliveries.isEmpty() && pickups.isEmpty()
                && Bases.crewBuilt(a.server(), a.dimension()) != null) {
            KeeperModule.PENDING.remove(a.name().toLowerCase());
            a.log("the base is finished: back to keeping its storage");
            a.changeRole(Role.KEEPER);
            return;
        }
        if (++smeltChecks % 60 == 0 && autoSmelt(a)) return;
        // Before any delivery or pickup: it follows miners into caves and has to be able to dig itself out.
        if (System.currentTimeMillis() >= pickaxeRetryAt && ownPickaxe(a)) return;
        if (a.inventoryFill() > PACK_AT && hasPackable(a)) {
            if (startKeeperDeposit(a)) return; // the base's storage keeper first
            if (startChest(a, "put")) return; // storage chests first, shulker boxes when there are none
            if (startShulker(a, "pack")) return;
            BaseWork.ensure(a); // nowhere left: the base's chests (have one built if there's none)
            if (System.currentTimeMillis() >= placeBackoffUntil && state == State.IDLE) warn(a,"inventory is full and it has no shulker box with room; give it an empty shulker box");
        }
        if (timer % 100 == 0) BaseWork.ensure(a); // a base going up: watch it, register its chests when done
        // A load on the ground comes first: it's gone in 5 minutes.
        if (!pickups.isEmpty() && pickups.peekFirst().type() == Message.Type.GROUND_PICKUP) { job = pickups.pollFirst(); go(State.GO_PICKUP); return; }
        // Deliveries first: someone is waiting on a tool or food.
        while (!deliveries.isEmpty()) {
            Message m = deliveries.peekFirst();
            AgentHandle to = GathererModule.find(m.from());
            if (to == null || !to.isOnline()) { deliveries.pollFirst(); continue; }
            // One it could not reach a few minutes ago, still about where it was: the rest of its orders too (it asks again
            // once it is somewhere reachable). Each one tried for 2 minutes in turn kept a distributor tunnelling toward the
            // main's underground pool for 20 minutes with ten food orders (run 1120g: melon16).
            Unreached u = unreached.get(m.from());
            if (u != null && System.currentTimeMillis() - u.at() < 4 * 60_000 && Math.abs(to.x() - u.x()) + Math.abs(to.z() - u.z()) < 24) {
                deliveries.pollFirst();
                warn(a, "§edropped " + m.from() + "'s orders: it couldn't be reached where it is now");
                continue;
            }
            String item = m.get("item");
            if (item != null && item.startsWith("pickaxe:")) {
                if (pickaxeFromStock(a, m)) return;
                // None in stock: say so right away; the miner makes its own.
                deliveries.pollFirst();
                a.log("no pickaxe in stock for " + m.from() + ": it'll make its own");
                AgentBus.send(Message.of(a.name(), m.from(), Message.Type.TEXT, "none available: pickaxe", Map.of()));
                continue;
            }
            if (item != null && (item.startsWith("weapon:") || item.startsWith("armor:"))) {
                if (weapon(a, m)) return;
                deliveries.pollFirst();
                unavailable.put(item, System.currentTimeMillis());
                a.log("can't make " + m.from() + " a better " + item.split(":")[1] + " from what's around; dropped the request");
                continue;
            }
            if (slotWith(a, item) >= 0) { job = deliveries.pollFirst(); go(State.GO_DELIVER); return; }
            if (shulkerWith(a, item) >= 0) { startShulker(a, item); return; }
            if (startKeeperFetch(a, m)) return; // the base's storage, through its keeper
            if (startChest(a, item)) return;
            if (tryCraft(a, item)) return;
            if (craftFromRaw(a, m)) return; // chests, furnaces, doors, torches: from logs, cobblestone, coal it fetches first
            if (fetchFrom(a, m)) return; // someone else carries a spare one: go get it
            // Not in stock, not in a chest, can't craft it, nobody can spare one: accept it and drop the
            // request (repeat requests for it are ignored for 5 minutes).
            deliveries.pollFirst();
            unavailable.put(item, System.currentTimeMillis());
            a.log("no " + item.replace("minecraft:", "") + " anywhere for " + m.from() + "; dropped the request (give it one to change that)");
            AgentBus.send(Message.of(a.name(), m.from(), Message.Type.TEXT, "none available: " + item, Map.of()));
            continue;
        }
        if (!pickups.isEmpty()) {
            job = pickups.pollFirst();
            AgentHandle from = GathererModule.find(job.from());
            if (from == null || !from.isOnline()) { job = null; return; }
            go(State.GO_PICKUP);
            return;
        }
        // Nothing asked of it while the base is going up: don't stand around. Bring the builder what it carries,
        // or collect from a miner holding a load for the base (the builder doesn't need it all at once).
        if (deliveries.isEmpty() && proactiveBase(a)) return;
        // Truly nothing to do: wait in the base, by the storage, where the next request most likely starts anyway.
        if (deliveries.isEmpty() && pickups.isEmpty()) HomeBase.goHome(a, timer);
    }

    // ------------------------------------------------------------------ pickaxe requests

    /**
     * "pickaxe:2" from a miner: a pickaxe of tier 2 (stone) or better, from what it already has:
     * its inventory, a shulker box, a storage chest; the best one first. It doesn't craft or fetch
     * one: with none in stock it says so at once, and the miner makes its own.
     */
    private boolean pickaxeFromStock(AgentHandle a, Message m) {
        int need = parse(m.get("item").split(":")[1], 1);
        String[] byTier = {"netherite", "diamond", "iron", "stone", "copper", "golden", "wooden"};
        int[] tierOf = {5, 4, 3, 2, 2, 1, 1};
        for (int i = 0; i < byTier.length; i++) {
            if (tierOf[i] < need) continue;
            String item = "minecraft:" + byTier[i] + "_pickaxe";
            if (exactSlot(a, item) >= 0) {
                deliveries.pollFirst();
                job = Message.of(m.from(), a.name(), Message.Type.ITEM_REQUEST, m.text(), Map.of("item", item, "count", "1"));
                a.log("bringing " + m.from() + " a " + byTier[i] + " pickaxe");
                go(State.GO_DELIVER);
                return true;
            }
            if (shulkerWith(a, item) >= 0 && shulkerHasExact(a, item)) { startShulker(a, item); return true; }
            boolean inChest = CHEST_CONTENTS.values().stream().anyMatch(c -> c.stream().anyMatch(s -> Gear.id(s).equals(item)));
            if (inChest && startChest(a, item)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ weapon requests

    /** request -> tries so far (crafting or fetching that keeps failing gives up). */
    private final Map<String, Integer> weaponTries = new java.util.HashMap<>();

    /**
     * "weapon:sword:2" from a warrior: the best sword (axe, ...) better than its tier 2 that the
     * materials around allow (this inventory, the storage chests, other agents' spares): diamond,
     * iron (raw iron gets smelted), stone, wood. Gets the materials, crafts it, delivers it.
     * False if nothing better can be made.
     */
    private boolean queueThen(AgentHandle a, String item) { crafter.queue(a, item); return true; }

    private boolean weapon(AgentHandle a, Message m) {
        String[] p = m.get("item").split(":");
        String type = p[1];
        int haveTier = p.length > 2 ? parse(p[2], 0) : 0;
        String key = m.from() + "|" + type;
        if (weaponTries.merge(key, 1, Integer::sum) > 40) { weaponTries.remove(key); return false; }
        boolean armor = p[0].equals("armor");
        int heads = armor ? switch (type) { case "helmet" -> 5; case "chestplate" -> 8; case "leggings" -> 7; default -> 4; }
                : type.equals("axe") || type.equals("pickaxe") ? 3 : type.equals("shovel") ? 1 : 2;
        String[][] tiers = armor ? new String[][]{ // armor: diamond, iron (raw iron gets smelted), leather
                {"4", "diamond", "minecraft:diamond"},
                {"3", "iron", "minecraft:iron_ingot", "minecraft:raw_iron"},
                {"1", "leather", "minecraft:leather"}}
                : new String[][]{ // tier, item prefix, materials that count (the first is what the recipe takes)
                {"4", "diamond", "minecraft:diamond"},
                {"3", "iron", "minecraft:iron_ingot", "minecraft:raw_iron"},
                {"2", "stone", "minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:blackstone"},
                {"1", "wooden", "_planks", "_log"}};
        for (String[] t : tiers) {
            int tier = Integer.parseInt(t[0]);
            if (tier <= haveTier) break;
            String item = "minecraft:" + t[1] + "_" + type;
            int need = heads;
            // Already made: deliver it.
            if (exactSlot(a, item) >= 0) {
                weaponTries.remove(key);
                deliveries.pollFirst();
                job = Message.of(m.from(), a.name(), Message.Type.ITEM_REQUEST, m.text(), Map.of("item", item, "count", "1"));
                a.log("made " + m.from() + " a " + t[1] + " " + type + ": bringing it over");
                go(State.GO_DELIVER);
                return true;
            }
            // One made already, in storage (put away, or picked up by the keeper): fetch that.
            if (startKeeperFetch(a, Message.of(m.from(), a.name(), Message.Type.ITEM_REQUEST, m.text(), Map.of("item", item, "count", "1")))) return true;
            int own = 0, around = 0;
            for (int i = 2; i < t.length; i++) {
                String mat = t[i];
                int perItem = mat.equals("_log") ? 4 : 1;
                own += countMat(a.inventory(), mat) * perItem;
                for (List<ItemStack> chest : CHEST_CONTENTS.values()) around += countMat(chest, mat) * perItem;
                for (AgentHandle h : AgentBus.agents.get()) {
                    if (h == a || !h.isOnline() || !closeBy(a, h)) continue;
                    for (ItemStack s : h.inventory()) if (!s.isEmpty() && matMatches(Gear.id(s), mat)) { around += Math.min(s.getCount(), Spare.spare(h, Gear.id(s))) * perItem; break; }
                }
            }
            if (own + around < need) continue; // not enough anywhere for this tier: try the next one down
            // Enough in its own inventory: smelt if it's raw iron, then craft.
            int ready = t[1].equals("iron") ? countMat(a.inventory(), "minecraft:iron_ingot") : own;
            if (ready >= need) {
                if (armor ? MakerModule.canMake(a, item) && queueThen(a, item) : crafter.queueTool(a, item)) { a.log("crafting a " + t[1] + " " + type + " for " + m.from()); return true; }
                // The heads are here but no wood for the handle (sticks): get some wood, then craft it.
                if (!armor && countWhere(a, MinerModule_isWood) == 0 && countWhere(a, id -> id.equals("minecraft:stick")) < 2
                        && fetchRaw(a, m, MinerModule_isWood, 2)) { a.log("needs wood for " + m.from() + "'s " + type + " handle: fetching some"); return true; }
                continue;
            }
            if (t[1].equals("iron") && countMat(a.inventory(), "minecraft:raw_iron") > 0) {
                a.log("smelting raw iron for " + m.from() + "'s " + type);
                crafter.queueAll(a, "minecraft:iron_ingot");
                return true;
            }
            // Go get the materials: a chest first, then whoever can spare them.
            for (int i = 2; i < t.length; i++) {
                String mat = t[i];
                if (mat.startsWith("_")) continue; // planks/logs come in many kinds: chests by exact id only
                if (startKeeperFetch(a, Message.of(m.from(), a.name(), Message.Type.ITEM_REQUEST, "materials", Map.of("item", mat, "count", String.valueOf(Math.max(1, need - ready)))))) return true;
                if (startChest(a, mat)) return true;
            }
            for (int i = 2; i < t.length; i++) {
                String mat = t[i];
                if (mat.startsWith("_")) continue;
                Message want = Message.of(m.from(), a.name(), Message.Type.ITEM_REQUEST, "materials", Map.of("item", mat, "count", String.valueOf(need - ready)));
                if (fetchFrom(a, want)) return true;
            }
        }
        weaponTries.remove(key);
        return false;
    }

    /** Same dimension and within 128 blocks: worth walking over to fetch from. */
    static boolean closeBy(AgentHandle a, AgentHandle h) {
        return dev.agentnet.role.AgentHandle.sameDim(h.dimension(), a.dimension()) && AgentBus.distSq(a, h) <= 128 * 128;
    }

    private static boolean matMatches(String id, String mat) {
        return mat.startsWith("_") ? id.endsWith(mat) && !id.contains("stripped") : id.equals(mat);
    }

    private static int countMat(List<ItemStack> items, String mat) {
        int n = 0;
        for (ItemStack s : items) if (!s.isEmpty() && matMatches(Gear.id(s), mat)) n += s.getCount();
        return n;
    }

    // ------------------------------------------------------------------ crafting from raw materials

    /** item -> when it last tried (a craft or a fetch for it), so a request doesn't loop every tick. */
    private final Map<String, Long> rawTried = new java.util.HashMap<>();

    /**
     * Base materials nobody has made yet: chests, crafting tables, doors (from wood), furnaces (from
     * cobblestone), torches (from coal). Crafts them when it carries the raw stuff (enough for at
     * least one), else fetches the raw stuff from whoever can spare it (a miner on the base's job).
     * The delivery stays queued and goes out once the items are made.
     */
    private boolean craftFromRaw(AgentHandle a, Message m) {
        String item = m.get("item");
        if (item == null) return false;
        int count = Math.max(1, parse(m.get("count"), 1));
        return switch (item) {
            case "minecraft:chest" -> fromWood(a, m, item, 8, 8 * count, false);
            case "minecraft:crafting_table" -> fromWood(a, m, item, 4, 4 * count, false);
            case "minecraft:oak_door" -> fromWood(a, m, item, 6, 6 * ((count + 2) / 3), true);
            case "minecraft:furnace" -> fromCobble(a, m, item, 8 * count);
            case "minecraft:torch" -> fromCoal(a, m, (count + 3) / 4);
            default -> false;
        };
    }

    /** item -> [crafts queued, since when]: a craft that keeps not producing anything (no recipe) stops after 4 in 5 minutes. */
    private final Map<String, long[]> rawCrafts = new java.util.HashMap<>();

    private boolean craftOk(String item) {
        long now = System.currentTimeMillis();
        long[] c = rawCrafts.computeIfAbsent(item, k -> new long[]{0, now});
        if (now - c[1] > 5 * 60_000) { c[0] = 0; c[1] = now; }
        return c[0] < 4;
    }

    private void crafted(String item) { long[] c = rawCrafts.get(item); if (c != null) c[0]++; }

    private static int countWhere(AgentHandle a, java.util.function.Predicate<String> id) {
        int n = 0;
        for (ItemStack s : a.inventory()) if (!s.isEmpty() && id.test(Gear.id(s))) n += s.getCount();
        return n;
    }

    private static boolean isLog(String id) { return id.endsWith("_log") && !id.contains("stripped"); }

    private boolean fromWood(AgentHandle a, Message m, String item, int per, int need, boolean oakOnly) {
        if (!craftOk(item)) return false;
        int planks = countWhere(a, id -> oakOnly ? id.equals("minecraft:oak_planks") : id.endsWith("_planks"));
        int logs = countWhere(a, id -> oakOnly ? id.equals("minecraft:oak_log") : isLog(id));
        // +4: making it on a crafting table of its own takes 4 planks too.
        if (planks + logs * 4 >= per + 4) {
            if (planks < need + 4 && logs > 0) crafter.queueAll(a, oakOnly ? "minecraft:oak_planks" : MakerModule.planksFromLog(a));
            crafted(item);
            crafter.queue(a, item);
            a.log("crafting " + item.replace("minecraft:", "") + " for " + m.from() + " from its wood");
            return true;
        }
        int wantLogs = Math.min(64, Math.max(1, (need + 4 - planks - logs * 4 + 3) / 4));
        return fetchRaw(a, m, id -> oakOnly ? id.equals("minecraft:oak_log") : isLog(id), wantLogs);
    }

    private boolean fromCobble(AgentHandle a, Message m, String item, int need) {
        if (!craftOk(item)) return false;
        int have = countWhere(a, id -> id.equals("minecraft:cobblestone") || id.equals("minecraft:cobbled_deepslate"));
        if (have >= 8) {
            crafted(item);
            crafter.queue(a, item);
            a.log("crafting " + item.replace("minecraft:", "") + " for " + m.from() + " from its cobblestone");
            return true;
        }
        return fetchRaw(a, m, id -> id.equals("minecraft:cobblestone") || id.equals("minecraft:cobbled_deepslate"), Math.min(64, need - have));
    }

    private boolean fromCoal(AgentHandle a, Message m, int need) {
        if (!craftOk("minecraft:torch")) return false;
        int coal = countWhere(a, id -> id.equals("minecraft:coal") || id.equals("minecraft:charcoal"));
        if (coal > 0 && crafter.queueWithParts(a, "minecraft:torch", true)) {
            crafted("minecraft:torch");
            a.log("crafting torches for " + m.from());
            return true;
        }
        if (coal > 0) return fetchRaw(a, m, MinerModule_isWood, 1); // coal but no wood for sticks
        return fetchRaw(a, m, id -> id.equals("minecraft:coal") || id.equals("minecraft:charcoal"), need);
    }

    private static final java.util.function.Predicate<String> MinerModule_isWood = id -> isLog(id) || id.endsWith("_planks");

    /** Someone close by can spare an item matching this: go and get up to n of it (a known chest first). */
    private boolean fetchRaw(AgentHandle a, Message m, java.util.function.Predicate<String> wanted, int n) {
        String key = m.get("item");
        long now = System.currentTimeMillis();
        Long t = rawTried.get(key);
        if (t != null && now - t < 20_000) return false; // just tried to fetch for this one
        rawTried.put(key, now);
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (AgentHandle h : AgentBus.agents.get()) {
            if (h == a || !h.isOnline() || !closeBy(a, h)) continue;
            for (ItemStack s : h.inventory()) if (!s.isEmpty() && wanted.test(Gear.id(s)) && Spare.spare(h, Gear.id(s)) > 0) ids.add(Gear.id(s));
        }
        for (List<ItemStack> chest : CHEST_CONTENTS.values()) for (ItemStack s : chest) if (!s.isEmpty() && wanted.test(Gear.id(s))) ids.add(Gear.id(s));
        for (String id : ids) {
            Message want = Message.of(m.from(), a.name(), Message.Type.ITEM_REQUEST, "materials", Map.of("item", id, "count", String.valueOf(Math.max(1, n))));
            if (fetchFrom(a, want)) return true;
            if (startKeeperFetch(a, want)) return true;
            if (startChest(a, id)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ fetching from other agents

    /** item|agent -> when it last tried fetching it there (don't pester the same agent every tick). */
    private final Map<String, Long> fetchTried = new java.util.HashMap<>();

    /**
     * Nothing in stock, in chests or craftable: if another agent carries a spare one (more
     * than its own job needs, see {@link Spare}), walk over and have it handed over. The
     * delivery stays queued and goes out as soon as the item is in our inventory.
     */
    private boolean fetchFrom(AgentHandle a, Message m) {
        String item = m.get("item");
        if (item == null || !item.contains(":")) return false; // "food" etc.: the usual way
        int want = 1;
        try { want = Integer.parseInt(m.get("count")); } catch (RuntimeException ignored) {}
        long now = System.currentTimeMillis();
        AgentHandle best = null;
        int bestSpare = 0;
        double bestD = Double.MAX_VALUE;
        for (AgentHandle h : AgentBus.agents.get()) {
            if (h == a || !h.isOnline() || h.name().equalsIgnoreCase(m.from()) || !closeBy(a, h)) continue; // not across the map
            int spare = Spare.spare(h, item);
            if (spare <= 0) continue;
            Long t = fetchTried.get(item + "|" + h.name());
            if (t != null && now - t < 60_000) continue;
            Long claimed = COLLECTING_FROM.get(h.name().toLowerCase());
            if (claimed != null && now - claimed < 60_000) continue; // another distributor is already on its way there
            double d = AgentBus.distSq(a, h);
            if (d < bestD) { bestD = d; best = h; bestSpare = spare; }
        }
        if (best == null) return false;
        fetchTried.put(item + "|" + best.name(), now);
        COLLECTING_FROM.put(best.name().toLowerCase(), now);
        int n = Math.min(bestSpare, want);
        a.log("has no " + item.replace("minecraft:", "") + " for " + m.from() + ", but " + best.name() + " can spare " + bestSpare + ": fetching " + n);
        job = Message.of(best.name(), a.name(), Message.Type.HAND_OVER, "fetch", Map.of("item", item, "count", String.valueOf(n)));
        go(State.GO_PICKUP);
        return true;
    }

    // ------------------------------------------------------------------ pickups

    private void goPickup(AgentHandle a) {
        if (job.type() == Message.Type.GROUND_PICKUP) {
            BlockPos at = new BlockPos(parse(job.get("x"), 0), parse(job.get("y"), 0), parse(job.get("z"), 0));
            if (timer > 20 * 240) { GroundDrops.done(at); a.log("§ecouldn't get to " + job.from() + "'s load at " + at.toShortString()); job = null; go(State.IDLE); return; }
            if (at.distToCenterSqr(a.x(), a.y(), a.z()) <= 3 * 3) { if (a.isNavigating()) a.stopNavigation(); startCollect(a); return; }
            if (!a.isNavigating() && timer % 20 == 1) a.navigate(at.getX(), at.getY(), at.getZ(), 1);
            return;
        }
        AgentHandle g = GathererModule.find(job.from());
        if (g == null || !g.isOnline() || timer > 20 * 120) { fail(a, "couldn't reach " + job.from()); return; }
        double d = Math.sqrt(AgentBus.distSq(a, g));
        if (d <= HANDOVER_DIST + 0.5) {
            if (a.isNavigating()) a.stopNavigation();
            a.lookAt(g.x(), g.y() + 1, g.z());
            if (job.type() == Message.Type.HAND_OVER) {
                AgentBus.send(Message.of(a.name(), g.name(), Message.Type.HAND_OVER, "I'm here, hand it over", Map.of("item", job.get("item"), "count", job.get("count"))));
                go(State.WAIT_DROP);
                return;
            }
            AgentBus.send(Message.of(a.name(), g.name(), Message.Type.COLLECT_DROP, "I'm here, hand it over",
                    Map.of("x", GathererModule.coord(a.x()), "y", GathererModule.coord(a.y()), "z", GathererModule.coord(a.z()))));
            go(State.WAIT_DROP);
            return;
        }
        // (Re)plan toward the gatherer; it may be walking around its field.
        if (!a.isNavigating() && timer % 20 == 1) {
            a.navigate((int) Math.floor(g.x()), (int) Math.floor(g.y() + 0.2), (int) Math.floor(g.z()), 2);
        }
    }

    private void startCollect(AgentHandle a) {
        triedItems.clear();
        collectTarget = null;
        go(State.COLLECT);
    }

    /** Walk over every item lying around (they land within a few blocks). */
    private void collect(AgentHandle a) {
        if (timer < 50) return; // let them land: thrown items can't be picked up for 2 s
        if (timer > 20 * 40) { finishCollect(a); return; }
        if (timer == 110) triedItems.clear(); // a second sweep: some were still in the air (or still protected) the first time
        if (collectTarget != null && a.isNavigating()) return;
        for (Vec3 v : a.itemsNear(10)) {
            if (GroundDrops.reservedFor(a.name(), v)) continue; // another distributor's pickup
            long k = BlockPos.containing(v).asLong();
            if (!triedItems.add(k)) continue;
            collectTarget = v;
            a.navigate((int) Math.floor(v.x), (int) Math.floor(v.y + 0.2), (int) Math.floor(v.z), 0);
            return;
        }
        if (timer > 150) finishCollect(a);
    }

    private void finishCollect(AgentHandle a) {
        collected++;
        if (job != null && job.type() == Message.Type.GROUND_PICKUP)
            GroundDrops.done(new BlockPos(parse(job.get("x"), 0), parse(job.get("y"), 0), parse(job.get("z"), 0)));
        a.log("collected from " + job.from() + " (" + a.usedSlots() + "/36 slots used)");
        job = null;
        // Carrying a load for the base now: take it to the builder first, before the next pickup
        // (fetching drop after drop, the builder got nothing).
        // (With no pickaxe it goes idle first: the pickaxe is made from this wood before anything is handed on.)
        if (Gear.bestTool(a.inventory(), "pickaxe") >= 0 && baseRun.start(a, false)) { go(State.BASE_RUN); return; }
        go(State.IDLE);
    }

    // ------------------------------------------------------------------ deliveries

    private void goDeliver(AgentHandle a) {
        AgentHandle to = GathererModule.find(job.from());
        if (to == null || !to.isOnline() || timer > 20 * 120) {
            if (to != null) unreached.put(job.from(), new Unreached(System.currentTimeMillis(), to.x(), to.z()));
            fail(a, "couldn't reach " + job.from());
            return;
        }
        double d = Math.sqrt(AgentBus.distSq(a, to));
        if (d <= THROW_DIST) {
            if (a.isNavigating()) a.stopNavigation();
            String item = job.get("item");
            int slot = slotWith(a, item);
            if (slot < 0) { go(State.IDLE); return; }
            a.lookAt(to.x(), to.y(), to.z()); // aim at its feet: the item lands where it stands
            int want = parse(job.get("count"), 1);
            ItemStack s = a.inventory().get(slot);
            if (want >= s.getCount() || s.getMaxStackSize() == 1) a.dropStack(slot);
            else for (int i = 0; i < want; i++) a.dropOne(slot);
            delivered++;
            AgentBus.send(Message.of(a.name(), to.name(), Message.Type.ITEM_DELIVERED, "thrown to you",
                    Map.of("item", Gear.id(s), "count", String.valueOf(Math.min(want, s.getCount())))));
            a.log("delivered " + Gear.id(s) + " to " + to.name());
            job = null;
            // Step back: a thrown item can be picked up by anyone after 2 s, the thrower included.
            double bx = a.x() - to.x(), bz = a.z() - to.z(), len = Math.max(0.1, Math.sqrt(bx * bx + bz * bz));
            a.navigate((int) Math.floor(a.x() + bx / len * 3), (int) Math.floor(a.y() + 0.2), (int) Math.floor(a.z() + bz / len * 3), 1);
            go(State.BACK_OFF);
            return;
        }
        if (!a.isNavigating() && timer % 20 == 1) {
            a.navigate((int) Math.floor(to.x()), (int) Math.floor(to.y() + 0.2), (int) Math.floor(to.z()), 1);
        }
    }

    /** Best inventory slot for a request, or -1. Tools: the best one; food: the most filling. */
    /** Slot holding exactly this item id (slotWith also takes "any sword" for a sword). */
    private static int exactSlot(AgentHandle a, String item) {
        List<ItemStack> inv = a.inventory();
        for (int i = 0; i < inv.size(); i++) if (!inv.get(i).isEmpty() && Gear.id(inv.get(i)).equals(item)) return i;
        return -1;
    }

    /** A carried shulker box holds exactly this item. */
    private static boolean shulkerHasExact(AgentHandle a, String item) {
        for (ItemStack s : a.inventory()) {
            var c = s.get(net.minecraft.core.component.DataComponents.CONTAINER);
            if (c != null && c.nonEmptyItemCopyStream().anyMatch(x -> Gear.id(x).equals(item))) return true;
        }
        return false;
    }

    /** A pickaxe of at least this tier somewhere it can get it right away (inventory, shulker, chest). */
    private static boolean pickaxeInStock(AgentHandle a, int need) {
        String[] byTier = {"netherite", "diamond", "iron", "stone", "copper", "golden", "wooden"};
        int[] tierOf = {5, 4, 3, 2, 2, 1, 1};
        for (int i = 0; i < byTier.length; i++) {
            if (tierOf[i] < need) continue;
            String item = "minecraft:" + byTier[i] + "_pickaxe";
            if (exactSlot(a, item) >= 0 || shulkerHasExact(a, item)) return true;
            if (CHEST_CONTENTS.values().stream().anyMatch(c -> c.stream().anyMatch(s -> Gear.id(s).equals(item)))) return true;
        }
        return false;
    }

    private static int slotWith(AgentHandle a, String item) {
        List<ItemStack> inv = a.inventory();
        int best = -1;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (!Gear.matches(s, item)) continue;
            if (best < 0 || better(s, inv.get(best), item)) best = i;
        }
        return best;
    }

    private static boolean better(ItemStack s, ItemStack than, String item) {
        if (item.equals("food")) return Gear.nutrition(s) > Gear.nutrition(than);
        if (s.getItem() == Items.FISHING_ROD) return Gear.rodScore(s) > Gear.rodScore(than);
        return Gear.tier(s) * 10 + Gear.durability(s) > Gear.tier(than) * 10 + Gear.durability(than);
    }

    // ------------------------------------------------------------------ storage chests

    /** Known chests further than this are ignored. */
    private static final int CHEST_RANGE = 96;
    /** What each chest held the last time an agent looked (shared by all distributors). */
    static final Map<BlockPos, List<ItemStack>> CHEST_CONTENTS = ChestMemory.CONTENTS;
    private BlockPos chestPos;
    private String chestPurpose;
    private final Map<BlockPos, Long> chestFailedAt = new java.util.HashMap<>();

    /**
     * Use a known storage chest (/agent chest add): "put" = deposit stock, or an item id =
     * fetch it. Picks the nearest chest that may have room (put) or that held the item last
     * time / was never looked into (fetch). False if there's no such chest.
     */
    private boolean startChest(AgentHandle a, String purpose) {
        long now = System.currentTimeMillis();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        boolean keeperHere = KeeperModule.keeperFor(a) != null && Bases.crewBuilt(a.server(), a.dimension()) != null;
        for (ChestMemory.Chest c : ChestMemory.list(a.server(), a.dimension())) {
            if (keeperHere && "base".equals(c.note())) continue; // the keeper's chests: ask the keeper
            BlockPos p = new BlockPos(c.x(), c.y(), c.z());
            Long failed = chestFailedAt.get(p);
            if (failed != null && now - failed < 5 * 60_000) continue;
            List<ItemStack> seen = CHEST_CONTENTS.get(p);
            if (purpose.equals("put")) {
                if (seen != null && seen.stream().noneMatch(ItemStack::isEmpty) && seen.size() >= 27) continue; // full last time
            } else if (seen != null && seen.stream().noneMatch(s -> Gear.matches(s, purpose))) {
                continue; // didn't have it last time
            }
            double d = Math.sqrt(sq(p.getX() + 0.5 - a.x()) + sq(p.getY() - a.y()) + sq(p.getZ() + 0.5 - a.z()));
            if (d > CHEST_RANGE) continue;
            // Sorted storage: a chest that already holds something we carry first, then an empty
            // (or never opened) one, then any with room.
            if (purpose.equals("put")) d += sortPenalty(a, seen);
            if (d < bestD) { bestD = d; best = p; }
        }
        if (best == null) return false;
        chestPos = best;
        chestPurpose = purpose;
        go(State.CHEST_GO);
        return true;
    }

    private static double sq(double v) { return v * v; }

    // ------------------------------------------------------------------ the storage keeper (distributor = mail)

    private final Pickup keeperPickup = new Pickup();
    private AgentHandle keeper;
    private Message keeperJob;
    private boolean keeperAsked;

    /** Full: take the load to the base's storage keeper (it sorts it into the chests). */
    private boolean startKeeperDeposit(AgentHandle a) {
        keeper = KeeperModule.keeperFor(a);
        if (keeper == null || Bases.crewBuilt(a.server(), a.dimension()) == null) return false; // no finished base here yet: handle it directly
        a.log("taking its load to " + keeper.name() + " at the base");
        go(State.KEEPER_GO);
        return true;
    }

    private void keeperDrop(AgentHandle a) {
        if (keeper == null || !keeper.isOnline() || timer > 20 * 180) { go(State.IDLE); return; }
        if (state == State.KEEPER_GO) {
            if (AgentBus.distSq(a, keeper) > 3 * 3) {
                if (!a.isNavigating() && timer % 20 == 1) a.navigate((int) Math.floor(keeper.x()), (int) Math.floor(keeper.y()), (int) Math.floor(keeper.z()), 2);
                return;
            }
            if (a.isNavigating()) a.stopNavigation();
            AgentBus.send(Message.of(a.name(), keeper.name(), Message.Type.DEPOSIT, "here's my load", Map.of()));
            go(State.KEEPER_DROP);
            return;
        }
        if (timer < 5 || timer % 3 != 0) return;
        List<ItemStack> inv = a.inventory();
        Set<Integer> keep = keepOut(inv);
        for (int i = 0; i < inv.size(); i++) {
            if (inv.get(i).isEmpty() || keep.contains(i)) continue;
            a.lookAt(keeper.x(), keeper.y() + 0.5, keeper.z());
            a.dropStack(i);
            return;
        }
        AgentBus.send(Message.of(a.name(), keeper.name(), Message.Type.DROP_DONE, "that's all", Map.of()));
        a.log("left its load with " + keeper.name());
        // Step back so we don't pick our own throw back up.
        double bx = a.x() - keeper.x(), bz = a.z() - keeper.z(), len = Math.max(0.1, Math.sqrt(bx * bx + bz * bz));
        a.navigate((int) Math.floor(a.x() + bx / len * 4), (int) Math.floor(a.y() + 0.2), (int) Math.floor(a.z() + bz / len * 4), 1);
        go(State.BACK_OFF);
    }

    /** Someone needs something we don't carry: the keeper has it in storage? Go and ask for it. */
    private boolean startKeeperFetch(AgentHandle a, Message m) {
        String item = m.get("item");
        if (item == null) return false;
        keeper = KeeperModule.keeperFor(a);
        if (keeper == null || Bases.crewBuilt(a.server(), a.dimension()) == null) return false;
        String stored = KeeperModule.stock(a.server(), a.dimension()).keySet().stream()
                .filter(id -> Gear.matches(new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(id))), item))
                .findFirst().orElse(null);
        if (stored == null) return false;
        keeperJob = m;
        keeperAsked = false;
        a.log("fetching " + stored.replace("minecraft:", "") + " for " + m.from() + " from " + keeper.name() + "'s storage");
        go(State.KEEPER_FETCH);
        return true;
    }

    private void keeperFetch(AgentHandle a) {
        if (keeperPickup.tick(a)) return;
        String item = keeperJob.get("item");
        if (slotWith(a, item) >= 0) { go(State.IDLE); return; } // got it: the delivery goes out from idle
        if (keeper == null || !keeper.isOnline() || timer > 20 * 120) { a.log("the keeper didn't hand over " + item); unavailable.put(item, System.currentTimeMillis()); go(State.IDLE); return; }
        if (AgentBus.distSq(a, keeper) > 3 * 3) {
            if (!a.isNavigating() && timer % 20 == 1) a.navigate((int) Math.floor(keeper.x()), (int) Math.floor(keeper.y()), (int) Math.floor(keeper.z()), 2);
            return;
        }
        if (a.isNavigating()) a.stopNavigation();
        if (!keeperAsked) {
            keeperAsked = true;
            AgentBus.send(Message.of(a.name(), keeper.name(), Message.Type.KEEPER_REQUEST, "need " + item,
                    Map.of("item", item, "count", String.valueOf(keeperJob.get("count") == null ? 1 : parse(keeperJob.get("count"), 1)))));
        }
    }

    private void chestGo(AgentHandle a) {
        double dx = chestPos.getX() + 0.5 - a.x(), dy = chestPos.getY() + 0.5 - (a.y() + 1.62), dz = chestPos.getZ() + 0.5 - a.z();
        if (dx * dx + dy * dy + dz * dz <= 4.0 * 4.0) {
            if (a.isNavigating()) a.stopNavigation();
            a.lookAt(chestPos.getX() + 0.5, chestPos.getY() + 0.5, chestPos.getZ() + 0.5);
            a.useItemOn(chestPos.getX(), chestPos.getY(), chestPos.getZ(), Direction.UP);
            go(State.CHEST_OPEN);
            return;
        }
        if (timer > 2 && !a.isNavigating() && a.navStatus().startsWith("failed") || timer > 20 * 90) { chestFailed(a, "can't reach it"); return; }
        if (!a.isNavigating() && timer % 20 == 1) a.navigate(chestPos.getX(), chestPos.getY(), chestPos.getZ(), 2);
    }

    private void chestOpen(AgentHandle a) {
        if (a.containerOpen()) { go(chestPurpose.equals("put") ? State.CHEST_PUT : State.CHEST_TAKE); return; }
        if (timer % 20 == 0) a.useItemOn(chestPos.getX(), chestPos.getY(), chestPos.getZ(), Direction.UP);
        if (timer > 80) chestFailed(a, "it didn't open (gone, blocked, or locked?)");
    }

    /** 0: holds an item we carry; 1000: empty or unknown; 3000: something else entirely. */
    private double sortPenalty(AgentHandle a, List<ItemStack> seen) {
        if (seen == null || seen.stream().allMatch(ItemStack::isEmpty)) return 1000;
        Set<String> carried = new HashSet<>();
        List<ItemStack> inv = a.inventory();
        Set<Integer> keep = keepOut(inv);
        for (int i = 0; i < inv.size(); i++) if (!inv.get(i).isEmpty() && !keep.contains(i)) carried.add(Gear.id(inv.get(i)));
        for (ItemStack s : seen) if (!s.isEmpty() && carried.contains(Gear.id(s))) return 0;
        return 3000;
    }

    /** The item kind this chest is for while we fill it (an empty chest takes the first kind we put in). */
    private String sortingFor;

    /**
     * Shift-click stock in, one stack every 2 ticks, until done or the chest is full. Sorted: only
     * kinds the chest already holds (an empty chest gets one kind, the one we carry most of). If
     * nothing we carry belongs here and no chest is empty, anything goes (better than staying full).
     */
    private void chestPut(AgentHandle a) {
        if (!a.containerOpen()) { chestDone(a); return; }
        if (timer < 3 || timer % 2 != 0) return;
        List<ItemStack> slots = a.containerSlots();
        boolean full = slots.stream().noneMatch(ItemStack::isEmpty);
        List<ItemStack> inv = a.inventory();
        Set<Integer> keep = keepOut(inv);
        Set<String> here = new HashSet<>();
        for (ItemStack s : slots) if (!s.isEmpty()) here.add(Gear.id(s));
        if (here.isEmpty() && sortingFor == null) {
            Map<String, Integer> most = new java.util.HashMap<>();
            for (int i = 0; i < inv.size(); i++) if (!inv.get(i).isEmpty() && !keep.contains(i)) most.merge(Gear.id(inv.get(i)), inv.get(i).getCount(), Integer::sum);
            sortingFor = most.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        }
        if (sortingFor != null) here.add(sortingFor);
        boolean anyFits = false;
        for (int i = 0; i < inv.size(); i++) if (!inv.get(i).isEmpty() && !keep.contains(i) && here.contains(Gear.id(inv.get(i)))) anyFits = true;
        boolean noEmptyChest = ChestMemory.list(a.server(), a.dimension()).stream().map(c -> CHEST_CONTENTS.get(new BlockPos(c.x(), c.y(), c.z())))
                .noneMatch(c -> c == null || c.stream().allMatch(ItemStack::isEmpty));
        if (!full) {
            for (int i = 0; i < inv.size(); i++) {
                if (inv.get(i).isEmpty() || keep.contains(i)) continue;
                if (!here.contains(Gear.id(inv.get(i))) && (anyFits || !noEmptyChest)) continue; // belongs in another chest
                a.quickMoveToContainer(i);
                return;
            }
        }
        sortingFor = null;
        a.log("stored its stock in the chest at " + chestPos.toShortString() + (full ? " (it's full now)" : ""));
        chestDone(a);
    }

    private void chestTake(AgentHandle a) {
        if (!a.containerOpen()) { chestDone(a); return; }
        List<ItemStack> slots = a.containerSlots();
        if (timer < 4) return; // let the contents arrive
        for (int i = 0; i < slots.size(); i++) {
            if (Gear.matches(slots.get(i), chestPurpose)) {
                a.quickMoveContainerSlot(i);
                a.log("took " + chestPurpose + " from the chest at " + chestPos.toShortString());
                chestDone(a);
                return;
            }
        }
        dev.agentnet.AgentNet.LOG.info("[{}] chest at {}: no {} in it", a.name(), chestPos.toShortString(), chestPurpose);
        chestDone(a);
    }

    private void chestDone(AgentHandle a) {
        if (a.containerOpen()) {
            CHEST_CONTENTS.put(chestPos, a.containerSlots().stream().map(ItemStack::copy).toList());
            a.closeContainer();
        }
        chestPos = null;
        go(State.IDLE);
    }

    private void chestFailed(AgentHandle a, String why) {
        a.log("couldn't use the chest at " + chestPos.toShortString() + ": " + why);
        chestFailedAt.put(chestPos, System.currentTimeMillis());
        if (a.isNavigating()) a.stopNavigation();
        if (a.containerOpen()) a.closeContainer();
        chestPos = null;
        go(State.IDLE);
    }

    // ------------------------------------------------------------------ shulker boxes

    /** Stays in the inventory: boxes, tools, food (3 stacks), and war supplies. */
    private static Set<Integer> keepOut(List<ItemStack> inv) {
        Set<Integer> keep = new HashSet<>();
        int food = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (s.isEmpty()) continue;
            String id = Gear.id(s);
            if (Gear.isShulker(s) || s.isDamageableItem() || id.contains("golden_apple") || id.equals("minecraft:experience_bottle")
                    || id.equals("minecraft:ender_pearl") || id.equals("minecraft:totem_of_undying")) keep.add(i);
            else if (Gear.isFood(s) && food < 3) { keep.add(i); food++; }
        }
        return keep;
    }

    private static boolean hasPackable(AgentHandle a) {
        List<ItemStack> inv = a.inventory();
        Set<Integer> keep = keepOut(inv);
        for (int i = 0; i < inv.size(); i++) if (!inv.get(i).isEmpty() && !keep.contains(i)) return true;
        return false;
    }

    /**
     * What a carried box holds: from the item itself if the server sends that, else what
     * this distributor saw inside the last time it opened it; null = never looked inside.
     * (Some servers don't send shulker contents to clients, so boxes get opened to look.)
     */
    private final java.util.Map<Integer, List<ItemStack>> boxMemory = new java.util.HashMap<>();
    private Set<Integer> shulkerSlotsBefore = Set.of();
    private List<ItemStack> lastSeen;
    private boolean tookFromBox;

    private List<ItemStack> contentsOf(ItemStack box, int slot) {
        List<ItemStack> c = Gear.contents(box);
        return !c.isEmpty() ? c : boxMemory.get(slot);
    }

    /** Slot of a carried shulker box that holds this item; else one never looked into; else -1. */
    private int shulkerWith(AgentHandle a, String item) {
        List<ItemStack> inv = a.inventory();
        int unknown = -1;
        for (int i = 0; i < inv.size(); i++) {
            if (!Gear.isShulker(inv.get(i))) continue;
            List<ItemStack> c = contentsOf(inv.get(i), i);
            if (c == null) { if (unknown < 0) unknown = i; continue; }
            if (c.stream().anyMatch(s -> Gear.matches(s, item))) return i;
        }
        return unknown;
    }

    /** Slot of a carried shulker box with free slots (emptiest first), or -1. */
    private int shulkerWithRoom(AgentHandle a) {
        List<ItemStack> inv = a.inventory();
        int best = -1, bestUsed = 27;
        for (int i = 0; i < inv.size(); i++) {
            if (!Gear.isShulker(inv.get(i))) continue;
            List<ItemStack> c = contentsOf(inv.get(i), i);
            int used = c == null ? 13 : c.size(); // unknown: worth a look
            if (used < bestUsed) { bestUsed = used; best = i; }
        }
        return best;
    }

    /** Put a shulker box down next to the agent. purpose: "pack", or an item id to take out. */
    private boolean startShulker(AgentHandle a, String purpose) {
        int slot = purpose.equals("pack") ? shulkerWithRoom(a) : shulkerWith(a, purpose);
        if (slot < 0) return false;
        if (System.currentTimeMillis() < placeBackoffUntil) return false;
        int ax = (int) Math.floor(a.x()), az = (int) Math.floor(a.z());
        if (a.spawnProtected(ax, az)) {
            // Non-op players can't place blocks near world spawn: step out of that area first.
            pendingPurpose = purpose;
            a.log("can't place blocks this close to spawn (spawn protection); walking out of it to use a shulker box");
            go(State.LEAVE_SPAWN);
            return true;
        }
        triedSpots.clear();
        BlockPos spot = placeSpot(a, triedSpots);
        if (spot == null) { warn(a, "no free spot next to it to put a shulker box"); return false; }
        if (a.isNavigating()) a.stopNavigation();
        shulkerItemCount = (int) a.inventory().stream().filter(Gear::isShulker).count();
        int hotbar = slot < 9 ? slot : 7;
        if (slot >= 9) {
            a.moveToHotbar(slot, hotbar);
            List<ItemStack> m1 = boxMemory.remove(slot), m2 = boxMemory.remove(hotbar);
            if (m2 != null) boxMemory.put(slot, m2);
            if (m1 != null) boxMemory.put(hotbar, m1);
        }
        boxMemory.remove(hotbar); // that box leaves the inventory; we'll know it again once it's back
        lastSeen = null;
        Set<Integer> before = new HashSet<>();
        List<ItemStack> invNow = a.inventory();
        for (int i = 0; i < invNow.size(); i++) if (i != hotbar && i != slot && Gear.isShulker(invNow.get(i))) before.add(i);
        if (slot >= 9 && Gear.isShulker(invNow.get(hotbar))) before.add(slot); // the box swapped out of the hotbar
        shulkerSlotsBefore = before;
        a.selectHotbar(hotbar);
        a.lookAt(spot.getX() + 0.5, spot.getY() + 0.5, spot.getZ() + 0.5);
        a.placeAgainst(spot.getX(), spot.getY() - 1, spot.getZ(), Direction.UP);
        shulkerPos = spot;
        shulkerPurpose = purpose;
        tookFromBox = false;
        go(State.SHULKER_PLACE);
        return true;
    }

    /** A block next to the agent with solid ground and two free blocks (a box needs room to open). */
    private static BlockPos placeSpot(AgentHandle a, Set<BlockPos> exclude) {
        var world = a.openWorld();
        if (world == null) return null;
        int fx = (int) Math.floor(a.x()), fy = (int) Math.floor(a.y() + 0.2), fz = (int) Math.floor(a.z());
        // Two blocks out first: its own hitbox can never reach there. Right next to it as a fallback.
        int[][] around = {{2, 0}, {-2, 0}, {0, 2}, {0, -2}, {2, 1}, {-2, -1}, {1, -2}, {-1, 2},
                {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
        for (int[] d : around) {
            int x = fx + d[0], z = fz + d[1];
            if (exclude.contains(new BlockPos(x, fy, z)) || a.spawnProtected(x, z)) continue;
            BlockState below = world.get(x, fy - 1, z), at = world.get(x, fy, z), above = world.get(x, fy + 1, z);
            if (below == null || at == null || above == null) continue;
            if (below.isAir() || !below.getFluidState().isEmpty() || below.getBlock() instanceof ShulkerBoxBlock) continue;
            // Clicking the top of a fence/slab/carpet puts the box somewhere else: needs a full top face.
            if (!below.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) continue;
            if (!at.isAir() || !above.isAir()) continue;
            // The server refuses to place a block where a player stands (other agents, the gatherer).
            if (occupied(a, x, fy, z)) continue;
            return new BlockPos(x, fy, z);
        }
        return null;
    }

    /**
     * A player hitbox (0.6 wide, 1.8 tall) overlaps that block: any agent, the distributor
     * itself included (it's often not centered in its own block, so its body pokes into the
     * spot next to it). The server refuses to place a block into a player.
     */
    private static boolean occupied(AgentHandle self, int x, int y, int z) {
        for (AgentHandle h : dev.agentnet.bus.AgentBus.agents.get()) {
            if (!h.isOnline() || !dev.agentnet.role.AgentHandle.sameDim(h.dimension(), self.dimension())) continue;
            if (Math.abs(h.x() - (x + 0.5)) < 0.5 + 0.3 + 0.05 && Math.abs(h.z() - (z + 0.5)) < 0.5 + 0.3 + 0.05
                    && h.y() < y + 1 && h.y() + 1.8 > y) return true;
        }
        return false;
    }

    /** The box didn't appear: try another spot, or give up for a minute (with the reason). */
    private void retryPlace(AgentHandle a) {
        triedSpots.add(shulkerPos);
        BlockPos spot = triedSpots.size() < MAX_PLACE_TRIES ? placeSpot(a, triedSpots) : null;
        var held = a.inventory().get(a.selectedSlot());
        if (spot != null && Gear.isShulker(held)) {
            dev.agentnet.AgentNet.LOG.info("[{}] shulker box didn't appear at {}, trying {}", a.name(), shulkerPos.toShortString(), spot.toShortString());
            a.lookAt(spot.getX() + 0.5, spot.getY() + 0.5, spot.getZ() + 0.5);
            a.placeAgainst(spot.getX(), spot.getY() - 1, spot.getZ(), Direction.UP);
            shulkerPos = spot;
            go(State.SHULKER_PLACE);
            return;
        }
        var world = a.openWorld();
        BlockState below = world == null ? null : world.get(shulkerPos.getX(), shulkerPos.getY() - 1, shulkerPos.getZ());
        BlockPos spawn = a.worldSpawn();
        a.log("§ccouldn't place a shulker box (" + triedSpots.size() + " spots tried, last on "
                + (below == null ? "?" : net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(below.getBlock()).getPath())
                + (Gear.isShulker(held) ? "" : ", the box wasn't in its hand")
                + (spawn == null ? "" : ", " + (int) Math.max(Math.abs(a.x() - spawn.getX()), Math.abs(a.z() - spawn.getZ())) + " blocks from spawn")
                + "). Trying again in a minute. Is it in a protected area (spawn protection, claims)?");
        placeBackoffUntil = System.currentTimeMillis() + PLACE_BACKOFF_MS;
        shulkerPos = null;
        triedSpots.clear();
        go(State.IDLE);
    }

    /** Walk just outside the spawn-protection square, then use the box there. */
    private void leaveSpawn(AgentHandle a) {
        int ax = (int) Math.floor(a.x()), az = (int) Math.floor(a.z());
        if (!a.spawnProtected(ax, az)) {
            // Out (with a block to spare, so the box spot next to it is out too).
            if (a.spawnProtected(ax + 1, az + 1) || a.spawnProtected(ax - 1, az - 1)) { if (!a.isNavigating()) leaveStep(a, ax, az); return; }
            if (a.isNavigating()) a.stopNavigation();
            String p = pendingPurpose;
            pendingPurpose = null;
            go(State.IDLE);
            if (p != null) startShulker(a, p);
            return;
        }
        if (a.isNavigating()) return;
        if (timer > 2 && a.navStatus().startsWith("failed")) {
            a.log("§ccan't walk out of spawn protection (" + a.navStatus() + "); it can't place shulker boxes here. "
                    + "Set spawn-protection=0 on the server, or op the agent");
            placeBackoffUntil = System.currentTimeMillis() + PLACE_BACKOFF_MS * 5;
            pendingPurpose = null;
            go(State.IDLE);
            return;
        }
        leaveStep(a, ax, az);
    }

    private void leaveStep(AgentHandle a, int ax, int az) {
        BlockPos s = a.worldSpawn();
        if (s == null) return;
        int dx = ax - s.getX(), dz = az - s.getZ();
        int out = dev.agentnet.Agent.SPAWN_PROTECTION + 8; // aim well past the edge; it stops once it's out
        int tx = ax, tz = az;
        // Leave along the axis it's already furthest out on (shortest walk).
        if (Math.abs(dx) >= Math.abs(dz)) tx = s.getX() + (dx >= 0 ? out : -out);
        else tz = s.getZ() + (dz >= 0 ? out : -out);
        a.navigate(tx, (int) Math.floor(a.y() + 0.2), tz, 4);
    }

    private static boolean shulkerAt(AgentHandle a, BlockPos p) {
        var world = a.openWorld();
        BlockState s = world == null ? null : world.get(p.getX(), p.getY(), p.getZ());
        return s != null && s.getBlock() instanceof ShulkerBoxBlock;
    }

    private void open(AgentHandle a) {
        a.useItemOn(shulkerPos.getX(), shulkerPos.getY(), shulkerPos.getZ(), Direction.UP);
        go(State.SHULKER_OPEN);
    }

    /** Shift-click produce in, one stack every 2 ticks, until nothing's left or the box is full. */
    private void pack(AgentHandle a) {
        if (!a.containerOpen()) { mine(a); return; }
        if (timer % 2 != 0) return;
        boolean full = a.containerSlots().stream().noneMatch(ItemStack::isEmpty);
        List<ItemStack> inv = a.inventory();
        Set<Integer> keep = keepOut(inv);
        for (int i = 0; i < inv.size() && !full; i++) {
            if (inv.get(i).isEmpty() || keep.contains(i)) continue;
            a.quickMoveToContainer(i);
            return;
        }
        a.log("packed its stock into a shulker box");
        mine(a);
    }

    /** Take the wanted item out of the box. */
    private void take(AgentHandle a) {
        if (tookFromBox) { mine(a); return; }
        if (!a.containerOpen()) { mine(a); return; }
        List<ItemStack> slots = a.containerSlots();
        if (timer < 4 || slots.stream().allMatch(ItemStack::isEmpty) && timer < 20) return; // let the contents arrive
        boolean found = false;
        for (int i = 0; i < slots.size(); i++) {
            if (Gear.matches(slots.get(i), shulkerPurpose)) {
                a.quickMoveContainerSlot(i);
                found = true;
                break;
            }
        }
        tookFromBox = true;
        if (!found) dev.agentnet.AgentNet.LOG.info("[{}] looked in a shulker box: no {} in it", a.name(), shulkerPurpose);
        mine(a);
    }

    /** Close the box, mine it (it keeps its contents), then walk over to pick it up. */
    private void mine(AgentHandle a) {
        if (a.containerOpen()) {
            lastSeen = a.containerSlots().stream().filter(s -> !s.isEmpty()).map(ItemStack::copy).toList();
            a.closeContainer();
        }
        int pick = Gear.bestTool(a.inventory(), "pickaxe");
        if (pick >= 0) {
            int hb = pick < 9 ? pick : 6;
            if (pick >= 9) a.moveToHotbar(pick, hb);
            a.selectHotbar(hb);
        }
        a.lookAt(shulkerPos.getX() + 0.5, shulkerPos.getY() + 0.5, shulkerPos.getZ() + 0.5);
        if (a.dig(shulkerPos.getX(), shulkerPos.getY(), shulkerPos.getZ()) < 0) a.log("couldn't mine its shulker box");
        go(State.SHULKER_MINE);
    }

    private int warChecks;

    /**
     * In a war, a distributor that isn't the closest one for any warrior isn't needed:
     * every warrior has another distributor nearer. The last distributor never is.
     */
    private static boolean redundant(AgentHandle me) {
        List<AgentHandle> distributors = new java.util.ArrayList<>(), warriors = new java.util.ArrayList<>();
        for (AgentHandle h : AgentBus.agents.get()) {
            if (!h.isOnline() || !h.server().equals(me.server()) || h.dimension() == null || !dev.agentnet.role.AgentHandle.sameDim(h.dimension(), me.dimension())) continue;
            if (h.role() == Role.DISTRIBUTOR) distributors.add(h);
            else if (h.role() == Role.WARRIOR) warriors.add(h);
        }
        if (distributors.size() < 2 || warriors.isEmpty()) return false;
        for (AgentHandle w : warriors) {
            double mine = AgentBus.distSq(me, w);
            boolean closerOther = false;
            for (AgentHandle d : distributors) if (d != me && AgentBus.distSq(d, w) < mine) { closerOther = true; break; }
            if (!closerOther) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ helpers

    private void go(State next) {
        state = next;
        timer = 0;
    }

    private record Unreached(long at, double x, double z) {}
    /** Who it last failed to reach in time, when, and where that one was then. */
    private final Map<String, Unreached> unreached = new java.util.HashMap<>();

    private void fail(AgentHandle a, String why) {
        a.log(why);
        if (a.isNavigating()) a.stopNavigation();
        job = null;
        go(State.IDLE);
    }

    private final Map<String, Long> warnedAt = new java.util.HashMap<>();
    /** item -> when it last found none anywhere. */
    private final Map<String, Long> unavailable = new java.util.HashMap<>();

    private void warn(AgentHandle a, String message) {
        Long at = warnedAt.get(message);
        long now = System.currentTimeMillis();
        if (at != null && now - at < 60_000) return; // the same complaint at most once a minute
        warnedAt.put(message, now);
        lastWarning = message;
        a.log(message);
    }

    private static int parse(String s, int def) {
        try { return Integer.parseInt(s); } catch (RuntimeException e) { return def; }
    }

    /** For /agent list etc. */
    @Override
    public String status() {
        return state.name().toLowerCase() + ", " + pickups.size() + " pickup(s), " + deliveries.size()
                + " deliver(ies) queued, " + collected + " collected, " + delivered + " delivered";
    }
}
