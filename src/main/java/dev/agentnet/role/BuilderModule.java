package dev.agentnet.role;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.agentnet.bus.AgentBus;
import dev.agentnet.bus.Message;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Builder: builds its share of a .litematic schematic.
 *
 * <p>"/agent build &lt;agent|all&gt; &lt;file&gt;" (file in agentnet/schematics/ or the game's
 * schematics/ folder) builds it with its origin where you stand. With several builders on
 * the same file, each takes a slab of it (split by amount of blocks, {@link BuildPlan#split}),
 * so nobody walks into anyone else's work.
 *
 * <p>Bottom layer first. For every block: skip it if it's already right, dig out whatever
 * is in the way, walk within reach, and place it against a solid neighbor (the block below
 * first). Missing materials are asked for from the nearest distributor while it carries on
 * with what it has. Blocks with nothing to place against yet wait for a later pass.
 *
 * <p>Limits: block orientation (stairs, logs) comes from how the block is placed, not from
 * the schematic; blocks out of reach of the ground and of the structure itself are skipped.
 */
final class BuilderModule implements RoleModule {
    private static final double REACH = 4.5;
    private static final long REQUEST_EVERY_MS = 2 * 60_000;
    private static final int MAX_TRIES = 3;

    private enum State { IDLE, SPLIT, NEXT, WALK, PLACE, CHECK, DIG, WAIT_MATERIALS, DONE }

    /** The schematic file and this builder's part of it, once assigned. */
    String file;
    BuildPlan.Box area;
    private BlockPos origin;

    private State state = State.IDLE;
    private List<Litematic.Placed> todo = List.of();   // world positions, this builder's part
    private int cursor;
    private Litematic.Placed current;
    private final Map<BlockPos, Integer> tries = new HashMap<>();
    private final Set<BlockPos> skipped = new HashSet<>();
    private final Map<Item, Long> requestedAt = new HashMap<>();
    private int timer, placed, passProgress, passes, ensureTicks;
    private String status = "";
    private String lastWarning;

    @Override
    public void onStart(AgentHandle a) { a.keepWorld(true); }

    @Override
    public void onStop(AgentHandle a) {
        a.keepWorld(false);
        if (a.isNavigating()) a.stopNavigation();
    }

    @Override
    public void onTask(AgentHandle a, String task) {
        if (task.equals("idle")) { state = State.IDLE; status = "stopped"; if (a.isNavigating()) a.stopNavigation(); return; }
        if (!task.startsWith("build ")) return;
        // "build <file> <x> <y> <z>" (the command adds where you stood)
        String[] p = task.substring(6).trim().split("\\s+");
        if (p.length < 4) { a.log("§cusage: /agent build <agent|all> <file>"); return; }
        try {
            origin = new BlockPos(Integer.parseInt(p[p.length - 3]), Integer.parseInt(p[p.length - 2]), Integer.parseInt(p[p.length - 1]));
        } catch (NumberFormatException e) { a.log("§cbad build origin"); return; }
        file = String.join(" ", java.util.Arrays.copyOf(p, p.length - 3));
        go(State.SPLIT); // next tick: by then every builder given this job has it, so the split is fair
    }

    @Override
    public void onMessage(AgentHandle a, Message m) {
        if (m.type() == Message.Type.ITEM_DELIVERED) {
            // A load left a few blocks off: go for it, unless it's already fetching one (each new message used to
            // restart the trip and its time limit, so it fetched forever), it gave up on one a moment ago, or the
            // spot is far below or above (down a pit: not somewhere it should go).
            long now = System.currentTimeMillis();
            if (m.get("x") != null && fetchAt == null && now >= fetchPauseUntil) {
                BlockPos at = new BlockPos(parseInt(m.get("x")), parseInt(m.get("y")), parseInt(m.get("z")));
                if (Math.abs(at.getY() - a.y()) <= 4 && atBase(a, at.getX(), at.getY(), at.getZ())) { fetchAt = at; fetchSince = now; } // never off the base for it
                else GroundDrops.done(at);
            }
            pickup.start();
        }
    }

    private final Pickup pickup = new Pickup();
    /** After clearing trees off the site: the logs they dropped are the base's chests, doors and crafting table. */
    private final Pickup sweep = new Pickup(8, 30_000);
    private boolean clearedAny, swept;
    /** Chests, doors, the crafting table: made from the wood it carries instead of waiting for a distributor. */
    private final MakerModule crafter = new MakerModule();
    private long craftedAt;
    private int noSupport;
    private long stuckWarnedAt;
    private BlockPos unloaded;

    @Override
    public boolean idle() { return state == State.IDLE || state == State.DONE; }

    @Override
    public String status() { return status; }

    @Override
    public void tick(AgentHandle a) {
        timer++;
        // Keep the crew's base list up to date whatever it's doing: what the base still needs (the miners the
        // autopilot hires come from this), progress, and finishing it. Nobody else may be around to do it.
        if (++ensureTicks % 200 == 0) BaseWork.ensure(a);
        if (ensureTicks % 20 == 0 && status.startsWith("waiting for materials")) {
            // For the supply-chain map: the miners getting what it waits for.
            for (AgentHandle h : AgentBus.agents.get()) {
                if (!h.isOnline() || h.role() != Role.MINER || h.task() == null) continue;
                String t = h.task();
                if (t.contains("stone") || t.contains("logs") || t.contains("coal")) Links.mark(a.name(), h.name(), Links.Kind.WAITING, "waiting for its " + t.replace("mine ", ""));
            }
        }
        if (leash(a)) return;
        if (fetchAt != null) {
            // A load left for it a few blocks off (the deliverer couldn't get the last bit): go and get it.
            boolean there = fetchAt.distToCenterSqr(a.x(), a.y(), a.z()) <= 2.5 * 2.5;
            boolean noWay = System.currentTimeMillis() - fetchSince > 3_000 && a.navStatus().startsWith("failed");
            if (there || noWay || System.currentTimeMillis() - fetchSince > 30_000) {
                if (!there) fetchPauseUntil = System.currentTimeMillis() + 60_000; // building comes first: no more fetch trips for a minute
                GroundDrops.done(fetchAt);
                fetchAt = null;
                pickup.start();
            } else {
                status = "fetching a load left for it at " + fetchAt.toShortString();
                if (!a.isNavigating() && timer % 20 == 1) a.navigate(fetchAt.getX(), fetchAt.getY(), fetchAt.getZ(), 1);
                return;
            }
        }
        if (pickup.tick(a)) return;
        if (sweep.tick(a)) { status = "picking up the wood from the trees it cut"; return; }
        if (crafter.busy()) { status = "crafting: " + crafter.status(); crafter.tick(a); return; }
        switch (state) {
            case IDLE, DONE -> {} // nothing to build: the check above (every 10 s) sees whether the crew needs a base
            case SPLIT -> split(a);
            case NEXT -> next(a);
            case WALK -> walk(a);
            case PLACE -> place(a);
            case CHECK -> check(a);
            case DIG -> { if (!a.isDigging() && timer > 2) go(State.PLACE); }
            case WAIT_MATERIALS -> {
                if (timer == 2 && status.startsWith("waiting for materials")) waitOutside(a);
                if (timer > 20 * 15) { cursor = 0; passProgress = 0; go(State.NEXT); }
            }
        }
    }

    // ------------------------------------------------------------------ planning

    private void split(AgentHandle a) {
        Path path = Litematic.find(dev.agentnet.AgentFiles.dir(), file);
        if (path == null) { a.log("§cno schematic '" + file + "' in agentnet/schematics/ or schematics/"); go(State.IDLE); return; }
        Litematic.Schematic s;
        try { s = Litematic.load(path); }
        catch (Exception e) { a.log("§ccouldn't read " + file + ": " + e.getMessage()); go(State.IDLE); return; }

        // Everyone building the same file from the same spot shares it, in name order.
        String job = a.task();
        List<AgentHandle> crew = new ArrayList<>();
        for (AgentHandle h : AgentBus.agents.get()) if (h.isOnline() && job.equals(h.task()) && h.role() == Role.BUILDER) crew.add(h);
        crew.sort(Comparator.comparing(AgentHandle::name, String.CASE_INSENSITIVE_ORDER));
        int me = Math.max(0, crew.indexOf(a));
        BlockPos min = s.min().offset(origin), max = s.max().offset(origin);
        BuildPlan.Box whole = new BuildPlan.Box(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ());
        boolean alongX = whole.sizeX() >= whole.sizeZ();
        int length = alongX ? whole.sizeX() : whole.sizeZ();
        int[] perSlice = new int[length];
        for (Litematic.Placed b : s.blocks()) perSlice[alongX ? b.pos().getX() - s.min().getX() : b.pos().getZ() - s.min().getZ()]++;
        List<BuildPlan.Box> parts = BuildPlan.split(whole, Math.max(1, crew.size()), perSlice);
        area = me < parts.size() ? parts.get(me) : null;
        if (area == null) { a.log("nothing left for it to build in " + file + " (more builders than slices)"); go(State.DONE); return; }

        List<Litematic.Placed> mine = new ArrayList<>();
        for (Litematic.Placed b : s.blocks()) {
            BlockPos w = b.pos().offset(origin);
            if (w.getX() < area.x1() || w.getX() > area.x2() || w.getZ() < area.z1() || w.getZ() > area.z2()) continue;
            if (b.state().getBlock().asItem() == Items.AIR) continue; // water, fire, piston heads...: nothing to place
            mine.add(new Litematic.Placed(w, b.state()));
        }
        // Trees inside the site (the planner allows them): trunks and leaves out first, for a clear inside (a leaf it
        // can't reach in 4 s is left: it decays once the trunk is gone), top down so nothing is left hanging
        // in reach. Only inside the base's box: the rest of a tree above the walls can stay.
        List<Litematic.Placed> clear = new ArrayList<>();
        var view = a.openWorld();
        if (view != null) {
            Set<BlockPos> planned = new HashSet<>();
            for (Litematic.Placed b : mine) planned.add(b.pos());
            for (int x = Math.max(area.x1(), min.getX()); x <= Math.min(area.x2(), max.getX()); x++)
                for (int z = Math.max(area.z1(), min.getZ()); z <= Math.min(area.z2(), max.getZ()); z++)
                    for (int y = max.getY(); y >= min.getY(); y--) {
                        BlockPos p = new BlockPos(x, y, z);
                        BlockState st = view.get(x, y, z);
                        if (st != null && tree(st) && !planned.contains(p)) clear.add(new Litematic.Placed(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()));
                    }
        }
        // The base: a flat inside. Natural ground standing above the floor (a mound, a slope) is dug away.
        if (view != null && BasePlanner.FILE.equals(file)) {
            int fy = Integer.MAX_VALUE;
            for (Litematic.Placed b : s.blocks()) if (b.state().getBlock() == net.minecraft.world.level.block.Blocks.OAK_DOOR) fy = Math.min(fy, b.pos().getY());
            if (fy != Integer.MAX_VALUE) {
                Set<BlockPos> planned = new HashSet<>();
                for (Litematic.Placed b : mine) planned.add(b.pos());
                for (int x = 1; x <= BasePlanner.SX - 2; x++) for (int z = BasePlanner.FRONT + 1; z <= BasePlanner.FRONT + BasePlanner.SZ - 2; z++)
                    for (int y = fy + 2; y >= fy; y--) {
                        BlockPos p = new BlockPos(x, y, z).offset(origin);
                        if (p.getX() < area.x1() || p.getX() > area.x2() || p.getZ() < area.z1() || p.getZ() > area.z2() || planned.contains(p)) continue;
                        BlockState st = view.get(p.getX(), p.getY(), p.getZ());
                        if (st != null && !st.isAir() && !st.canBeReplaced() && st.getFluidState().isEmpty())
                            clear.add(new Litematic.Placed(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()));
                    }
            }
        }
        // Bottom-up, then a back-and-forth sweep so it doesn't zig-zag across the layer.
        mine.sort(Comparator.<Litematic.Placed>comparingInt(b -> b.pos().getY())
                .thenComparingInt(b -> b.pos().getX())
                .thenComparingInt(b -> (b.pos().getX() & 1) == 0 ? b.pos().getZ() : -b.pos().getZ()));
        clear.addAll(mine);
        todo = clear;
        if (clear.size() > mine.size()) a.log("clearing " + (clear.size() - mine.size()) + " tree block(s) off the site first");
        cursor = 0;
        passProgress = 0;
        passes = 0;
        placed = 0;
        tries.clear();
        skipped.clear();
        a.log("building " + file + " at " + origin.toShortString() + ": " + mine.size() + " blocks"
                + (crew.size() > 1 ? " (part " + (me + 1) + " of " + crew.size() + ")" : ""));
        go(State.NEXT);
    }

    /** The next block that isn't right yet (in order); after a full pass, start over. */
    private void next(AgentHandle a) {
        var w = a.openWorld();
        if (w == null) { status = "waiting for world data"; return; }
        Map<Item, Integer> missing = new HashMap<>();
        while (cursor < todo.size()) {
            Litematic.Placed b = todo.get(cursor++);
            if (skipped.contains(b.pos())) continue;
            BlockState now = w.get(b.pos().getX(), b.pos().getY(), b.pos().getZ());
            if (now == null) { if (unloaded == null) unloaded = b.pos(); continue; } // not loaded here: not done either
            if (done(now, b.state())) continue; // done (orientation not checked)
            if (b.state().isAir()) { // a tree in the way: walk over and cut it out
                if (a.spawnProtected(b.pos().getX(), b.pos().getZ())) { skipped.add(b.pos()); continue; }
                current = b;
                status = String.format("clearing the site for %s: %d to go", file, todo.size() - cursor);
                go(State.WALK);
                requestMissing(a, missing);
                return;
            }
            Item item = b.state().getBlock().asItem();
            if (clearedAny && !swept) { swept = true; sweep.start(); cursor--; a.log("site cleared: picking up the wood first"); return; }
            if (count(a, item) == 0) { missing.merge(item, 1, Integer::sum); continue; }
            if (a.spawnProtected(b.pos().getX(), b.pos().getZ())) { skipped.add(b.pos()); continue; }
            if (support(w, b.pos()) == null) { noSupport++; continue; } // nothing to place against yet: a later pass
            current = b;
            status = String.format("building %s: %d placed, %d to go", file, placed, todo.size() - cursor);
            go(State.WALK);
            requestMissing(a, missing);
            return;
        }
        if (craftOwn(a, missing)) { cursor = 0; passProgress = 0; return; } // a fresh pass once the crafting is done
        requestMissing(a, missing);
        // End of a pass.
        if (unloaded != null) {
            // Part of the site is out of its view: go there before calling anything done.
            BlockPos u = unloaded;
            unloaded = null;
            cursor = 0;
            status = "walking to the build site at " + u.toShortString();
            if (!a.isNavigating()) a.navigate(u.getX(), u.getY(), u.getZ(), 6);
            go(State.WAIT_MATERIALS);
            return;
        }
        int left = remaining(a);
        if (left == 0 && !skipped.isEmpty() && passProgress > 0) {
            // Only skipped ones left, and the last pass got somewhere: try those again.
            skipped.clear();
            tries.clear();
            cursor = 0;
            passProgress = 0;
            return;
        }
        if (left == 0 && !skipped.isEmpty()) {
            a.log("§efinished its part of " + file + " except " + skipped.size() + " block(s) it couldn't reach or place (" + placed + " placed)");
            status = "done: " + file + " (" + skipped.size() + " skipped)";
            go(State.DONE);
            return;
        }
        if (left == 0) {
            a.log("finished its part of " + file + " (" + placed + " blocks placed)");
            status = "done: " + file;
            go(State.DONE);
            return;
        }
        passes++;
        if (!missing.isEmpty()) {
            status = "waiting for materials: " + describe(missing);
            warn(a, "waiting for materials: " + describe(missing));
            go(State.WAIT_MATERIALS);
            return;
        }
        if (passProgress == 0 && passes > 1) {
            // Don't give up on it: a half-built base is worse than a slow one. Say why, wait, try again.
            String why = left + " blocks of " + file + " can't be placed right now (" + noSupport + " with nothing to place against, "
                    + skipped.size() + " out of reach)";
            if (System.currentTimeMillis() - stuckWarnedAt > 2 * 60_000) { stuckWarnedAt = System.currentTimeMillis(); a.log("§e" + why + ": trying again in 15 s"); }
            status = "stuck for now: " + left + " blocks left";
            skipped.clear();
            tries.clear();
            cursor = 0;
            passProgress = 0;
            noSupport = 0;
            go(State.WAIT_MATERIALS);
            return;
        }
        // Progress this pass: give the skipped ones another chance (the new blocks may make
        // them reachable or give them something to stand against).
        skipped.clear();
        noSupport = 0;
        tries.clear();
        cursor = 0;
        passProgress = 0;
    }

    /** Waiting for materials on the crew's base: wait in front of its door, where miners bringing them can
     *  always get to it (inside the half-built walls, on a raised floor, they often can't). */
    private void waitOutside(AgentHandle a) {
        Bases.Base b = Bases.crew(a.server(), a.dimension());
        if (b == null || origin == null || !b.origin().equals(origin)) return; // some other schematic
        BlockPos spot = b.door().offset(0, 0, -3);
        // On the ground: the door is up to 4 above the ground in front (a spot at door height is in mid-air,
        // and a path there searches tens of thousands of spots and fails).
        var w = a.openWorld();
        if (w != null) {
            for (int y = spot.getY(); y > spot.getY() - 8; y--) {
                BlockState below = w.get(spot.getX(), y - 1, spot.getZ());
                BlockState at = w.get(spot.getX(), y, spot.getZ());
                if (below == null || at == null) break;
                if (dev.agentnet.pathing.Rules.canStandOn(below) && dev.agentnet.pathing.Rules.canWalkThrough(at)) { spot = new BlockPos(spot.getX(), y, spot.getZ()); break; }
            }
        }
        if (spot.distToCenterSqr(a.x(), a.y(), a.z()) <= 3 * 3 || a.isNavigating()) return;
        if (failedSpot != null && failedSpot.equals(spot) && System.currentTimeMillis() - failedSpotAt < 60_000) return; // no way there: don't search again yet
        a.navigate(spot.getX(), spot.getY(), spot.getZ(), 3);
        failedSpot = spot;
        failedSpotAt = System.currentTimeMillis();
    }

    private long leashLoggedAt, pickTriedAt;

    /** Inside the crew's base area: the building, its entrance strip and 3 blocks around, from 2 below the floor up. */
    private static boolean atBase(AgentHandle a, double x, double y, double z) {
        Bases.Base b = Bases.crew(a.server(), a.dimension());
        if (b == null) return true; // no base yet: it's looking for a site
        BlockPos o = b.origin();
        return x >= o.getX() - 3 && x <= o.getX() + BasePlanner.SX + 3 && z >= o.getZ() - 3 && z <= o.getZ() + BasePlanner.SZ + BasePlanner.FRONT + 3
                && y >= o.getY() - 2 && y <= o.getY() + 16;
    }

    /** The builder never leaves the base. Off it (fell in a hole, pushed, wandered after a drop): straight back to the
     *  drop-off point, digging or pillaring out with its own pickaxe if it has to. True while on its way back. */
    private boolean leash(AgentHandle a) {
        long now = System.currentTimeMillis();
        // A pickaxe of its own, so it can dig out of anything (stone if it carries 3 cobblestone, else wood).
        if (now - pickTriedAt > 30_000 && !crafter.busy() && Gear.bestTool(a.inventory(), "pickaxe") < 0) {
            pickTriedAt = now;
            int cobble = 0;
            for (ItemStack s : a.inventory()) { String id = Gear.id(s); if (id.equals("minecraft:cobblestone") || id.equals("minecraft:cobbled_deepslate")) cobble += s.getCount(); }
            if (crafter.queueTool(a, cobble >= 3 ? "minecraft:stone_pickaxe" : "minecraft:wooden_pickaxe")) a.log("making itself a pickaxe (to dig out if it ever gets stuck)");
        }
        if (origin == null || atBase(a, a.x(), a.y(), a.z())) return false;
        Bases.Base b = Bases.crew(a.server(), a.dimension());
        if (b == null || !b.origin().equals(origin)) return false; // building something else somewhere else
        BlockPos home = BaseDelivery.dropOff(a);
        if (home == null) return false;
        fetchAt = null;
        status = "off the base: going back to it";
        if (now - leashLoggedAt > 30_000) { leashLoggedAt = now; a.log("off the base at " + (int) a.x() + " " + (int) a.y() + " " + (int) a.z() + ": going back"); }
        if (!a.isNavigating() && timer % 20 == 1) a.navigate(home.getX(), home.getY(), home.getZ(), 2);
        return true;
    }

    private BlockPos fetchAt;
    private long fetchSince, fetchPauseUntil;

    private static int parseInt(String s) { try { return Integer.parseInt(s); } catch (RuntimeException e) { return 0; } }

    private BlockPos failedSpot;
    private long failedSpotAt;

    /** Missing chests, doors or a crafting table and it carries wood: make them itself. */
    private boolean craftOwn(AgentHandle a, Map<Item, Integer> missing) {
        long now = System.currentTimeMillis();
        if (now - craftedAt < 20_000) return false;
        int wood = 0;
        for (ItemStack s : a.inventory()) {
            String id = Gear.id(s);
            if (id.endsWith("_log") && !id.contains("stripped")) wood += 4 * s.getCount();
            else if (id.endsWith("_planks")) wood += s.getCount();
        }
        int tables = missing.getOrDefault(Items.CRAFTING_TABLE, 0), doors = missing.getOrDefault(Items.OAK_DOOR, 0), chests = missing.getOrDefault(Items.CHEST, 0);
        List<String> plan = new ArrayList<>();
        if (tables > 0 && wood >= 4) { plan.add("crafting_table 1"); wood -= 4; }
        if (doors > 0 && wood >= 6) { int sets = (doors + 2) / 3; plan.add("oak_door " + doors); wood -= 6 * sets; }
        int c = Math.min(chests, wood / 8);
        // The chest recipe unlocks once the server has seen planks in its inventory: planks first, chests next time.
        boolean chestKnown = a.recipes().stream().anyMatch(e -> Gear.id(a.resolve(e.display().result())).equals("minecraft:chest"));
        if (c > 0 && chestKnown) plan.add("chest " + c);
        else if (c > 0 && MakerModule.planksFromLog(a) != null) { craftedAt = now - 15_000; crafter.queueAll(a, MakerModule.planksFromLog(a)); a.log("making planks first (for " + c + " chests)"); return true; }
        // Furnaces from cobblestone left over at the end of a pass (every cobblestone block it could place is
        // placed), torches from coal: it used to wait on a distributor for these and the base stalled at the end.
        int cobble = 0, coal = 0;
        for (ItemStack s : a.inventory()) {
            String id = Gear.id(s);
            if (id.equals("minecraft:cobblestone") || id.equals("minecraft:cobbled_deepslate")) cobble += s.getCount();
            else if (id.equals("minecraft:coal") || id.equals("minecraft:charcoal")) coal += s.getCount();
        }
        int furnaces = Math.min(missing.getOrDefault(Items.FURNACE, 0), cobble / 8);
        if (furnaces > 0) plan.add("furnace " + furnaces);
        if (missing.getOrDefault(Items.TORCH, 0) > 0 && coal > 0 && crafter.queueWithParts(a, "minecraft:torch", true)) { craftedAt = now; a.log("making its own torches from its coal"); return true; }
        if (plan.isEmpty()) return false;
        craftedAt = now;
        String planks = MakerModule.planksFromLog(a);
        if (planks != null) crafter.queueAll(a, planks);
        for (String p : plan) crafter.onTask(a, "make " + p);
        a.log("making its own " + String.join(", ", plan) + " from the wood it carries");
        return true;
    }

    /** Trees (trunks, leaves) inside a build site: cleared before building. */
    static boolean tree(BlockState s) {
        return s.is(net.minecraft.tags.BlockTags.LOGS) || s.is(net.minecraft.tags.BlockTags.LEAVES);
    }

    /** This spot is as the plan wants it (an air entry = cleared: air or grass, flowers and the like). */
    private static boolean done(BlockState now, BlockState want) {
        return want.isAir() ? now.isAir() || now.canBeReplaced() : now.getBlock() == want.getBlock();
    }

    private int remaining(AgentHandle a) {
        var w = a.openWorld();
        if (w == null) return todo.size();
        int n = 0;
        for (Litematic.Placed b : todo) {
            if (skipped.contains(b.pos())) continue;
            BlockState now = w.get(b.pos().getX(), b.pos().getY(), b.pos().getZ());
            if (now != null && !done(now, b.state())) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ doing it

    private void walk(AgentHandle a) {
        BlockPos p = current.pos();
        boolean inside = Math.abs(a.x() - (p.getX() + 0.5)) < 0.85 && Math.abs(a.z() - (p.getZ() + 0.5)) < 0.85
                && a.y() < p.getY() + 1 && a.y() + 1.8 > p.getY();
        if (reach(a, p) && !inside) { if (a.isNavigating()) a.stopNavigation(); go(State.PLACE); return; }
        if (a.isNavigating()) return;
        if (inside && timer % 20 == 1) {
            // Standing where the block goes (often on top of the layer it just built):
            // step to the nearest free spot next to us that isn't this block.
            BlockPos off = stepAside(a, p);
            if (off != null) { a.navigate(off.getX(), off.getY(), off.getZ(), 0); return; }
        }
        boolean stuckThere = timer > 60 && a.navStatus().startsWith("arrived");
        int giveUp = current.state().isAir() ? 20 * 4 : 20 * 30; // clearing: a leaf it can't reach quickly isn't worth half a minute
        if (timer > 2 && a.navStatus().startsWith("failed") || stuckThere || timer > giveUp) { fail(a, p, "can't get within reach"); return; }
        if (timer % 20 == 1) {
            // Stand beside it, not in it: aim two blocks out on the side we're on. Aim at our own
            // height (the ground) rather than the block's, which may be up a wall in mid-air.
            int sx = a.x() < p.getX() + 0.5 ? -2 : 2;
            int gy = Math.abs(p.getY() - a.y()) <= 4 ? (int) Math.floor(a.y() + 0.2) : p.getY();
            a.navigate(p.getX() + (inside ? sx : 0), gy, p.getZ(), inside ? 1 : 4);
        }
    }

    private void place(AgentHandle a) {
        var w = a.openWorld();
        if (w == null) return;
        BlockPos p = current.pos();
        BlockState now = w.get(p.getX(), p.getY(), p.getZ());
        if (now != null && !now.isAir() && !now.canBeReplaced() && now.getFluidState().isEmpty()) {
            // Something else is there: dig it out first.
            if (now.getBlock() == current.state().getBlock()) { go(State.NEXT); return; }
            Tunnel.holdBest(a, now);
            if (a.dig(p.getX(), p.getY(), p.getZ()) < 0) { fail(a, p, "the block there can't be broken"); return; }
            go(State.DIG);
            return;
        }
        if (current.state().isAir()) { passProgress++; clearedAny = true; go(State.NEXT); return; } // cleared
        // Someone standing where this block goes (a miner bringing a load, a distributor): the block can't go in
        // while they're there. Ask them to step aside, do another block meanwhile, come back to this one later.
        for (AgentHandle h : AgentBus.agents.get()) {
            if (h == a || !h.isOnline() || !AgentHandle.sameDim(h.dimension(), a.dimension())) continue;
            if (Math.abs(h.x() - (p.getX() + 0.5)) < 0.8 && Math.abs(h.z() - (p.getZ() + 0.5)) < 0.8 && h.y() < p.getY() + 1 && h.y() + 1.8 > p.getY()) {
                AgentBus.send(Message.of(a.name(), h.name(), Message.Type.STEP_ASIDE, "you're standing where a block goes",
                        Map.of("x", String.valueOf(p.getX()), "y", String.valueOf(p.getY()), "z", String.valueOf(p.getZ()))));
                skipped.add(p);
                go(State.NEXT);
                return;
            }
        }
        BlockState want = current.state();
        Direction face = support(w, p, preferredSides(want));
        if (face == null) { go(State.NEXT); return; }
        Item item = want.getBlock().asItem();
        int slot = find(a, item);
        if (slot < 0) { go(State.NEXT); return; }
        int hotbar = slot < 9 ? slot : 5;
        if (slot >= 9) a.moveToHotbar(slot, hotbar);
        a.selectHotbar(hotbar);
        BlockPos against = p.relative(face);
        a.lookAt(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
        // Orientation: turn so the server places it the way the schematic has it.
        Float yaw = placementYaw(want);
        if (yaw != null) a.look(yaw, 30f);
        a.placeAgainst(against.getX(), against.getY(), against.getZ(), face.getOpposite());
        go(State.CHECK);
    }

    private void check(AgentHandle a) {
        if (timer < 4) return;
        var w = a.openWorld();
        BlockPos p = current.pos();
        BlockState now = w == null ? null : w.get(p.getX(), p.getY(), p.getZ());
        if (now != null && now.getBlock() == current.state().getBlock()) {
            placed++;
            passProgress++;
            tries.remove(p);
        } else if (tries.merge(p, 1, Integer::sum) >= MAX_TRIES) {
            fail(a, p, "the server refused it " + MAX_TRIES + " times");
            return;
        }
        go(State.NEXT);
    }

    // ------------------------------------------------------------------ orientation

    /**
     * Which way to look when placing so it comes out like the schematic: stairs face the
     * way the player looks; furnaces, chests, barrels-on-the-side, etc. face back at the
     * player. Null = orientation doesn't depend on looking.
     */
    private static Float placementYaw(BlockState s) {
        var prop = net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING;
        if (!s.hasProperty(prop)) return null;
        Direction facing = s.getValue(prop);
        boolean facesAway = s.getBlock() instanceof net.minecraft.world.level.block.StairBlock
                || s.getBlock() instanceof net.minecraft.world.level.block.DoorBlock
                || s.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock;
        return (facesAway ? facing : facing.getOpposite()).toYRot();
    }

    /** Logs, pillars, basalt...: the axis comes from the clicked face, so click along it. */
    private static Direction[] preferredSides(BlockState s) {
        var axisProp = net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS;
        if (!s.hasProperty(axisProp)) return DEFAULT_SIDES;
        Direction.Axis axis = s.getValue(axisProp);
        java.util.List<Direction> order = new ArrayList<>();
        for (Direction d : DEFAULT_SIDES) if (d.getAxis() == axis) order.add(d);
        for (Direction d : DEFAULT_SIDES) if (d.getAxis() != axis) order.add(d);
        return order.toArray(new Direction[0]);
    }

    private static final Direction[] DEFAULT_SIDES = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP};

    private static Direction support(dev.agentnet.pathing.BlockSource w, BlockPos p) { return support(w, p, DEFAULT_SIDES); }

    /** Side of p with a solid block to click (in the given order of preference), or null. */
    private static Direction support(dev.agentnet.pathing.BlockSource w, BlockPos p, Direction[] order) {
        for (Direction d : order) {
            BlockPos n = p.relative(d);
            BlockState s = w.get(n.getX(), n.getY(), n.getZ());
            if (s == null || s.isAir() || !s.getFluidState().isEmpty() || s.canBeReplaced()) continue;
            try { if (s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) continue; }
            catch (RuntimeException e) { continue; }
            return d;
        }
        return null;
    }

    /** A standable spot 1-2 blocks away (same height, one up or down) whose hitbox is clear of p. */
    private static BlockPos stepAside(AgentHandle a, BlockPos p) {
        var w = a.openWorld();
        if (w == null) return null;
        int fx = (int) Math.floor(a.x()), fy = (int) Math.floor(a.y() + 0.2), fz = (int) Math.floor(a.z());
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -1; dy <= 1; dy++) {
            int x = fx + dx, y = fy + dy, z = fz + dz;
            if (Math.abs(x - p.getX()) <= 0 && Math.abs(z - p.getZ()) <= 0) continue; // that's the block itself
            BlockState floor = w.get(x, y - 1, z), feet = w.get(x, y, z), head = w.get(x, y + 1, z);
            if (floor == null || feet == null || head == null) continue;
            if (!Tunnel.solid(floor) || !Tunnel.passable(feet) || !Tunnel.passable(head)) continue;
            if (y <= p.getY() && y + 2 > p.getY() && x == p.getX() && z == p.getZ()) continue;
            double d = dx * dx + dz * dz + dy * dy * 2;
            if (d > 0 && d < bestD) { bestD = d; best = new BlockPos(x, y, z); }
        }
        return best;
    }

    private static boolean reach(AgentHandle a, BlockPos p) {
        double dx = p.getX() + 0.5 - a.x(), dy = p.getY() + 0.5 - (a.y() + 1.62), dz = p.getZ() + 0.5 - a.z();
        return dx * dx + dy * dy + dz * dz <= REACH * REACH;
    }

    private void fail(AgentHandle a, BlockPos p, String why) {
        skipped.add(p);
        dev.agentnet.AgentNet.LOG.info("[{}] skipping {} at {}: {}", a.name(), current.state().getBlock(), p.toShortString(), why);
        if (a.isNavigating()) a.stopNavigation();
        go(State.NEXT);
    }

    // ------------------------------------------------------------------ materials

    private void requestMissing(AgentHandle a, Map<Item, Integer> missing) {
        long now = System.currentTimeMillis();
        for (var e : missing.entrySet()) {
            Long at = requestedAt.get(e.getKey());
            if (at != null && now - at < REQUEST_EVERY_MS) continue;
            requestedAt.put(e.getKey(), now);
            requestMaterial(a, BuiltInRegistries.ITEM.getKey(e.getKey()).toString(), Math.min(64, e.getValue()));
        }
    }

    private static String describe(Map<Item, Integer> missing) {
        StringBuilder sb = new StringBuilder();
        missing.entrySet().stream().sorted(Map.Entry.<Item, Integer>comparingByValue().reversed()).limit(5).forEach(e ->
                sb.append(e.getValue()).append("x ").append(BuiltInRegistries.ITEM.getKey(e.getKey()).getPath()).append(", "));
        return sb.length() > 2 ? sb.substring(0, sb.length() - 2) : "";
    }

    private static int count(AgentHandle a, Item item) {
        int n = 0;
        for (ItemStack s : a.inventory()) if (s.getItem() == item) n += s.getCount();
        return n;
    }

    private static int find(AgentHandle a, Item item) {
        List<ItemStack> inv = a.inventory();
        for (int i = 0; i < inv.size(); i++) if (inv.get(i).getItem() == item) return i;
        return -1;
    }

    /** Ask the nearest distributor for materials this builder is missing. */
    static void requestMaterial(AgentHandle a, String item, int count) {
        AgentBus.closest(Role.DISTRIBUTOR, a).ifPresentOrElse(
                d -> AgentBus.send(Message.of(a.name(), d.name(), Message.Type.ITEM_REQUEST,
                        "need " + count + "x " + item,
                        Map.of("item", item, "count", String.valueOf(count),
                               "x", GathererModule.coord(a.x()), "y", GathererModule.coord(a.y()),
                               "z", GathererModule.coord(a.z())))),
                () -> a.log("needs " + count + "x " + item + " but no distributor is online"));
    }

    private void go(State s) { state = s; timer = 0; }

    private void warn(AgentHandle a, String m) {
        if (m.equals(lastWarning)) return;
        lastWarning = m;
        a.log(m);
    }
}
