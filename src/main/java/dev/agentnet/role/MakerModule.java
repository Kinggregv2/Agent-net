package dev.agentnet.role;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Map;
import java.util.Deque;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Maker: turns stuff into other stuff, at the stations near it.
 * <ul>
 *   <li><b>Crafting</b>: recipe-book click at a crafting table (or the 2x2 grid for small
 *       recipes), then shift-click the result (crafts as many as the ingredients allow).</li>
 *   <li><b>Smelting</b>: furnace / smoker / blast furnace from the recipe book, plus fuel.</li>
 *   <li><b>Stonecutting</b>: input in, pick the recipe button, shift-click the result.</li>
 *   <li><b>Brewing</b>: bottles, ingredient and blaze powder into a brewing stand; waits.</li>
 * </ul>
 * Jobs: /agent make &lt;agent&gt; &lt;item&gt; [count] and /agent brew &lt;agent&gt; &lt;ingredient&gt;.
 * Uses the items in its own inventory; recipes must be unlocked for it (its recipe book).
 */
final class MakerModule implements RoleModule {
    private enum Kind { CRAFT, CRAFT_2X2, SMELT, STONECUT, BREW, HAND }
    private enum State { IDLE, WALK, OPEN, WORK, WAIT, TAKE, DONE, PLACE_TABLE, PICKUP_TABLE }

    private record Job(String item, int count, String brewIngredient) {}

    private static final int STATION_RADIUS = 24;
    private static final int[] BREW_BOTTLES = {0, 1, 2};

    private final Deque<Job> jobs = new ArrayDeque<>();
    private Job job;
    private Kind kind;
    private RecipeDisplayEntry recipe;
    private BlockPos station;
    private int stoneIndex = -1;
    private int stoneInput = -1;
    private State state = State.IDLE;
    private int timer, made;
    private String lastWarning;

    @Override
    public void onStart(AgentHandle a) { a.keepWorld(true); }

    @Override
    public void onStop(AgentHandle a) {
        a.keepWorld(false);
        if (a.containerOpen()) a.closeContainer();
    }

    @Override
    public void onTask(AgentHandle a, String task) {
        String[] p = task.split(" ");
        if (p[0].equals("make") && p.length >= 2) {
            jobs.add(new Job(p[1].contains(":") ? p[1] : "minecraft:" + p[1], p.length > 2 ? parse(p[2]) : 1, null));
            a.log("queued: make " + (p.length > 2 ? p[2] + "x " : "") + p[1]);
        } else if (p[0].equals("brew") && p.length >= 2) {
            jobs.add(new Job("potion", 3, p[1].contains(":") ? p[1] : "minecraft:" + p[1]));
            a.log("queued: brew with " + p[1]);
        }
    }

    @Override
    public void tick(AgentHandle a) {
        timer++;
        switch (state) {
            case IDLE -> { if (timer % 10 == 0) next(a); }
            case WALK -> {
                if (kind == Kind.CRAFT_2X2) { go(State.WORK); return; }
                if (near(a, station)) { if (a.isNavigating()) a.stopNavigation(); open(a); return; }
                if (!a.isNavigating() && timer % 40 == 1) a.navigate(station.getX(), station.getY(), station.getZ(), 2);
                if (timer > 20 * 15) done(a, "couldn't reach the " + stationName());
            }
            case OPEN -> {
                if (a.containerOpen()) { go(State.WORK); return; }
                if (timer % 20 == 0) a.useItemOn(station.getX(), station.getY(), station.getZ(), Direction.UP);
                if (timer > 100) done(a, "the " + stationName() + " didn't open");
            }
            case WORK -> work(a);
            case WAIT -> waitForStation(a);
            case TAKE -> take(a);
            case DONE -> {}
            case PLACE_TABLE -> {
                if (timer > 5 && blockAt(a, station) == placedBlock) { go(State.WALK); return; }
                if (timer > 30) {
                    badSpots.put(station, System.currentTimeMillis()); // another spot next time (the same one kept failing)
                    placedTable = false;
                    done(a, "couldn't put down its " + (placedBlock == Blocks.FURNACE ? "furnace" : "crafting table") + " at " + station.toShortString() + " (holding " + Gear.id(a.inventory().get(a.selectedSlot())) + ")");
                }
            }
            case PICKUP_TABLE -> {
                if (a.isDigging()) return;
                if (timer == 3) a.navigate(pickupPos.getX(), pickupPos.getY(), pickupPos.getZ(), 0); // stand on the drop
                if (timer > 3 && !a.isNavigating() && timer > 20 || timer > 100) {
                    station = null;
                    if (!pickupNext(a)) go(State.IDLE);
                }
            }
        }
    }

    // ------------------------------------------------------------------ for other roles

    /** True while it has something to make (other roles let it run until it's done). */
    boolean busy() { return job != null || !jobs.isEmpty() || state != State.IDLE; }

    /** Queue "make item" (shift-click crafts as many as the ingredients allow). */
    void queue(AgentHandle a, String item) { onTask(a, "make " + item.replace("minecraft:", "")); }

    /** Queue "make item" as many times as the ingredients allow (planks: every log carried). */
    void queueAll(AgentHandle a, String item) { onTask(a, "make " + item.replace("minecraft:", "") + " 64"); }

    /**
     * Queue a tool plus the parts it needs from what's carried: logs -> planks -> sticks -> tool.
     * Returns false if it can't possibly work (no recipe unlocked for the tool).
     */
    boolean queueTool(AgentHandle a, String tool) { return queueWithParts(a, tool, false); }

    /** Like queueTool; all = craft as many as the ingredients allow (torches from all the coal). */
    boolean queueWithParts(AgentHandle a, String tool, boolean all) {
        boolean known = a.recipes().stream().anyMatch(e -> Gear.id(a.resolve(e.display().result())).equals(tool));
        if (!known) {
            // Recipes unlock as things are collected: logs -> planks -> sticks -> wooden tools.
            // Make the step that unlocks this one; the caller retries later.
            // Queue the whole chain at once: each step unlocks the next recipe as it finishes.
            String planks = planksFromLog(a);
            if (planks == null && plankCount(a) < 2 && countOf(a, "minecraft:stick") < 2) return false;
            if (planks != null && plankCount(a) < 9) queueAll(a, planks);
            if (countOf(a, "minecraft:stick") < 2) queue(a, "minecraft:stick");
            if (all) queueAll(a, tool); else queue(a, tool);
            return true;
        }
        boolean needSticks = countOf(a, "minecraft:stick") < 2;
        // Planks are a 2x2 recipe (no table needed). Turn every log into planks when short:
        // sticks need 2, a crafting table to carry needs 4, a wooden tool 3.
        if (plankCount(a) < (needSticks ? 2 : 0) + 4) {
            String planks = planksFromLog(a);
            if (planks != null) queueAll(a, planks);
            else if (needSticks && plankCount(a) < 2) return false;
        }
        if (needSticks) queue(a, "minecraft:stick");
        if (all) queueAll(a, tool); else queue(a, tool);
        return true;
    }

    /** Would this craft be possible from the inventory right now (recipe known, ingredients there)? */
    static boolean canMake(AgentHandle a, String item) {
        List<ItemStack> inv = a.inventory();
        for (RecipeDisplayEntry e : a.recipes()) {
            if (!Gear.id(a.resolve(e.display().result())).equals(item)) continue;
            if (e.craftingRequirements().map(reqs -> reqs.stream().allMatch(ing -> inv.stream().anyMatch(ing::test))).orElse(false)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ portable crafting table

    private boolean placedTable;
    /** Stations it put down itself (tables, furnaces): picked up again once nothing is queued. */
    private final java.util.Deque<BlockPos> ownStations = new ArrayDeque<>();
    private BlockPos pickupPos;

    /**
     * Mine the next of its own stations and walk onto the drop. A furnace only drops with a
     * pickaxe: without one it's left standing (and still used by findStation next time).
     */
    private boolean pickupNext(AgentHandle a) {
        while (!ownStations.isEmpty()) {
            BlockPos p = ownStations.pollLast();
            Block b = blockAt(a, p);
            if (b != Blocks.CRAFTING_TABLE && b != Blocks.FURNACE) continue; // gone already
            if (b == Blocks.FURNACE) {
                int pick = Gear.bestTool(a.inventory(), "pickaxe");
                if (pick < 0) { a.log("left its furnace at " + p.toShortString() + " (it needs a pickaxe to pick it up)"); continue; }
                int hb = pick < 9 ? pick : 6;
                if (pick >= 9) a.moveToHotbar(pick, hb);
                a.selectHotbar(hb);
            } else {
                selectAxe(a);
            }
            a.lookAt(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
            if (a.dig(p.getX(), p.getY(), p.getZ()) < 0) continue;
            pickupPos = p;
            go(State.PICKUP_TABLE);
            return true;
        }
        return false;
    }
    private String tableFor;

    private boolean placeTable(AgentHandle a) { return placeStation(a, "minecraft:crafting_table"); }

    private Block placedBlock = Blocks.CRAFTING_TABLE;

    static boolean canHaveFurnace(AgentHandle a) { return find(a.inventory(), "minecraft:furnace") >= 0 || stoneCount(a) >= 8; }

    private static int stoneCount(AgentHandle a) {
        return countOf(a, "minecraft:cobblestone") + countOf(a, "minecraft:cobbled_deepslate") + countOf(a, "minecraft:blackstone");
    }

    /** Put down a carried station block (crafting table, furnace) next to it; it's picked up again after the job. */
    private boolean placeStation(AgentHandle a, String itemId) {
        int slot = find(a.inventory(), itemId);
        if (slot < 0) return false;
        badSpots.values().removeIf(t -> System.currentTimeMillis() - t > 60_000);
        BlockPos spot = freeSpot(a, badSpots.keySet());
        if (spot == null) return false;
        int hotbar = slot < 9 ? slot : 6;
        if (slot >= 9) a.moveToHotbar(slot, hotbar);
        a.selectHotbar(hotbar);
        a.lookAt(spot.getX() + 0.5, spot.getY() + 0.5, spot.getZ() + 0.5);
        a.placeAgainst(spot.getX(), spot.getY() - 1, spot.getZ(), Direction.UP);
        station = spot;
        placedTable = true;
        if (!itemId.equals("minecraft:brewing_stand")) ownStations.add(spot); // a brewing stand stays: picked up, it would lose its fuel
        placedBlock = itemId.equals("minecraft:furnace") ? Blocks.FURNACE : itemId.equals("minecraft:brewing_stand") ? Blocks.BREWING_STAND : Blocks.CRAFTING_TABLE;
        return true;
    }

    /**
     * Room for a station where there is none: a block beside its feet dug out, so the floor under it is free to put a
     * table or furnace on. Never one with water or lava next to it, never bedrock, never in spawn protection.
     */
    private void makeRoom(AgentHandle a) {
        if (a.isDigging()) return;
        var w = a.openWorld();
        // The spots that failed already are not "free" (placeStation skips them too): with them counted it never dug, and
        // said "no room for its crafting table here: digging out a spot" every 10 s for minutes (run 1120p, itsCyber).
        if (w == null || freeSpot(a, badSpots.keySet()) != null) return;
        int fx = (int) Math.floor(a.x()), feet = (int) Math.floor(a.y() + 0.2), fz = (int) Math.floor(a.z());
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int x = fx + d.getStepX(), z = fz + d.getStepZ();
            if (a.spawnProtected(x, z)) continue;
            BlockState at = w.get(x, feet, z), below = w.get(x, feet - 1, z);
            if (at == null || below == null || at.isAir() || !at.getFluidState().isEmpty() || at.getBlock() == Blocks.BEDROCK) continue;
            if (!below.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) continue;
            boolean wet = false;
            for (Direction n : Direction.values()) {
                BlockState s = w.get(x + n.getStepX(), feet + n.getStepY(), z + n.getStepZ());
                if (s != null && !s.getFluidState().isEmpty()) wet = true;
            }
            if (wet) continue;
            Gear.holdBestFor(a, at);
            a.lookAt(x + 0.5, feet + 0.5, z + 0.5);
            a.dig(x, feet, z);
            return;
        }
    }

    /** Next to it (or two blocks out), on a full block, with nobody's hitbox in it, outside spawn protection. */
    private final java.util.Map<BlockPos, Long> badSpots = new java.util.HashMap<>();

    private static BlockPos freeSpot(AgentHandle a, java.util.Set<BlockPos> skip) {
        var w = a.openWorld();
        if (w == null) return null;
        int fx = (int) Math.floor(a.x()), feet = (int) Math.floor(a.y() + 0.2), fz = (int) Math.floor(a.z());
        int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}};
        for (int dy : new int[]{0, 1, -1}) for (int[] d : around) { // its own level first, then a step up or down (slopes, roots)
            int fy = feet + dy;
            int x = fx + d[0], z = fz + d[1];
            if (a.spawnProtected(x, z) || skip.contains(new BlockPos(x, fy, z))) continue;
            BlockState below = w.get(x, fy - 1, z), at = w.get(x, fy, z);
            // Leaf litter, grass, snow layers: a block goes there like into air (in a forest with litter everywhere no spot
            // was ever free, and a builder dug "a spot" 118 times, run 1120zg).
            if (below == null || at == null || !(at.isAir() || at.canBeReplaced() && at.getFluidState().isEmpty())) continue;
            if (!below.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) continue;
            boolean taken = false;
            for (AgentHandle h : dev.agentnet.bus.AgentBus.agents.get()) {
                if (!h.isOnline() || !dev.agentnet.role.AgentHandle.sameDim(h.dimension(), a.dimension())) continue;
                if (Math.abs(h.x() - (x + 0.5)) < 0.85 && Math.abs(h.z() - (z + 0.5)) < 0.85 && h.y() < fy + 1 && h.y() + 1.8 > fy) taken = true;
            }
            if (!taken) return new BlockPos(x, fy, z);
        }
        return null;
    }

    private static Block blockAt(AgentHandle a, BlockPos p) {
        var w = a.openWorld();
        BlockState s = w == null ? null : w.get(p.getX(), p.getY(), p.getZ());
        return s == null ? null : s.getBlock();
    }

    /** Hold the best axe from the hotbar if there is one (bare hands work too, slower). */
    private static void selectAxe(AgentHandle a) {
        List<ItemStack> inv = a.inventory();
        for (int i = 0; i < 9; i++) if ("axe".equals(Gear.toolType(inv.get(i)))) { a.selectHotbar(i); return; }
    }

    private static int plankCount(AgentHandle a) {
        int n = 0;
        for (ItemStack s : a.inventory()) if (!s.isEmpty() && Gear.id(s).endsWith("_planks")) n += s.getCount();
        return n;
    }

    /** oak_log -> minecraft:oak_planks (crimson_stem -> crimson_planks), for the first log carried. */
    static String planksFromLog(AgentHandle a) {
        for (ItemStack s : a.inventory()) {
            String id = Gear.id(s);
            if (s.isEmpty() || !(id.endsWith("_log") || id.endsWith("_stem") || id.endsWith("_wood"))) continue;
            return id.replace("stripped_", "").replaceAll("_(log|stem|wood)$", "_planks");
        }
        return null;
    }

    // ------------------------------------------------------------------ planning a job

    private void next(AgentHandle a) {
        job = jobs.pollFirst();
        if (job == null) return;
        hand3 = false;
        stoneIndex = -1;
        recipe = null;
        if (job.brewIngredient != null) {
            kind = Kind.BREW;
        } else if (!planStonecut(a) && !planRecipe(a) && planHand(a)) {
            // Not in the recipe book yet: by hand in the 2x2 grid.
            kind = Kind.HAND;
            countBefore = countOf(a, job.item);
            a.log("making " + job.item + " by hand (recipe not unlocked yet)");
            go(State.WORK);
            return;
        } else if (!planStonecut(a) && !planRecipe(a)) {
            // Not in its recipe book, but a pattern it knows for a crafting table (boats: the recipe only comes with a
            // step into water): put the items in the grid by hand, like a player.
            if (planHand3(a)) { kind = Kind.CRAFT; hand3 = true; recipe = null; a.log("making " + job.item + " by hand at a crafting table (recipe not unlocked)"); }
            else {
                done(a, "doesn't know how to make " + job.item + " (no unlocked recipe, nothing to stonecut)");
                return;
            }
        }
        // Smelting with nothing better to burn than logs: sawn into planks first. A log burns as long as one plank and
        // makes four (the user, watching it put whole logs under 4 porkchops: "he should craft the logs into planks").
        if (kind == Kind.SMELT && !job.item.endsWith("charcoal")) {
            List<ItemStack> fuelInv = a.inventory();
            boolean coal = find(fuelInv, "minecraft:coal") >= 0 || find(fuelInv, "minecraft:charcoal") >= 0 || find(fuelInv, "minecraft:coal_block") >= 0
                    || find(fuelInv, "minecraft:blaze_rod") >= 0 || find(fuelInv, "minecraft:lava_bucket") >= 0;
            String planks = coal ? null : planksFromLog(a);
            if (planks != null) {
                a.log("logs into planks first: four times the fuel");
                jobs.addFirst(job);
                jobs.addFirst(new Job(planks, 64, null));
                job = null;
                go(State.IDLE);
                return;
            }
        }
        if (kind != Kind.CRAFT_2X2) {
            // Carrying its own crafting table: use that (one seen earlier may be back up a tunnel it can't reach).
            // The same with its own furnace: one seen 12 blocks off down a hole it couldn't reach kept a helper walking at it,
            // its smelting started again every 15 s, for minutes (run 1120n: sam_waffle2012, a furnace in its pack).
            station = kind == Kind.CRAFT && find(a.inventory(), "minecraft:crafting_table") >= 0
                    || kind == Kind.SMELT && find(a.inventory(), "minecraft:furnace") >= 0 ? null : findStation(a, kind);
            if (station == null) {
                if (kind == Kind.CRAFT && !hand3 && fits2x2(recipe)) kind = Kind.CRAFT_2X2;
                else if (kind == Kind.BREW && placeStation(a, "minecraft:brewing_stand")) {
                    placeRetries = 0;
                    a.log("brewing (on its own brewing stand)");
                    go(State.PLACE_TABLE);
                    return;
                }
                else if (kind == Kind.SMELT && placeStation(a, "minecraft:furnace")) {
                    countBefore = countOf(a, job.item);
                    placeRetries = 0;
                    a.log("smelting " + job.item + " (in its own furnace)");
                    go(State.PLACE_TABLE);
                    return;
                } else if (kind == Kind.SMELT && !job.item.equals(tableFor) && stoneCount(a) >= 8) {
                    // Make a furnace (needs a crafting table, carried or made), then smelt.
                    tableFor = job.item;
                    jobs.addFirst(job);
                    jobs.addFirst(new Job("minecraft:furnace", 1, null));
                    job = null;
                    go(State.IDLE);
                    return;
                }
                else if (kind == Kind.CRAFT && placeTable(a)) {
                    // Carries a crafting table: put it down, craft, take it back afterwards.
                    countBefore = countOf(a, job.item);
                    a.log("making " + job.item + " (on its own crafting table)");
                    go(State.PLACE_TABLE);
                    return;
                } else if (kind == Kind.CRAFT && find(a.inventory(), "minecraft:crafting_table") >= 0 && ++placeRetries <= 6) {
                    // Carries a table but there is no floor beside it to put it on (a one-wide tunnel): a block dug out
                    // to make room, then again. It used to make a second table here and a third, and give up.
                    if (placeRetries == 1) a.log("no room for its crafting table here: digging out a spot");
                    makeRoom(a);
                    jobs.addFirst(job);
                    job = null;
                    go(State.IDLE);
                    return;
                } else if (kind == Kind.CRAFT && !job.item.equals(tableFor) && find(a.inventory(), "minecraft:crafting_table") < 0
                        && (plankCount(a) >= 4 || planksFromLog(a) != null)) {
                    // No table anywhere: make one from 4 planks first (2x2, logs -> planks too
                    // if it's short), then this job.
                    tableFor = job.item;
                    jobs.addFirst(job);
                    jobs.addFirst(new Job("minecraft:crafting_table", 1, null));
                    if (plankCount(a) < 4) jobs.addFirst(new Job(planksFromLog(a), 64, null));
                    job = null;
                    go(State.IDLE);
                    return;
                }
                else if (kind == Kind.SMELT && find(a.inventory(), "minecraft:furnace") >= 0 && ++placeRetries <= 6) {
                    // Has a furnace, just no room right here (or it only just got made): try again in a moment.
                    if (placeRetries == 1) a.log("no room to put its furnace down here; trying again");
                    makeRoom(a);
                    jobs.addFirst(job);
                    job = null;
                    go(State.IDLE);
                    return;
                }
                else { placeRetries = 0; done(a, "needs a " + stationName() + " within " + STATION_RADIUS + " blocks (or a crafting table / 4 planks to carry its own)"); return; }
            }
        }
        countBefore = countOf(a, job.item);
        a.log("making " + job.item + " (" + kind.name().toLowerCase().replace("_", " ") + ")");
        go(State.WALK);
    }

    private boolean planRecipe(AgentHandle a) {
        // Prefer a recipe it has the ingredients for (raw iron -> furnace, not nuggets -> table).
        RecipeDisplayEntry best = null;
        int bestScore = -1;
        List<ItemStack> inv = a.inventory();
        for (RecipeDisplayEntry e : a.recipes()) {
            ItemStack out = a.resolve(e.display().result());
            if (out.isEmpty() || !Gear.id(out).equals(job.item)) continue;
            boolean have = e.craftingRequirements().map(reqs -> reqs.stream().allMatch(ing -> inv.stream().anyMatch(ing::test))).orElse(false);
            boolean crafting = e.display() instanceof ShapedCraftingRecipeDisplay || e.display() instanceof ShapelessCraftingRecipeDisplay;
            // Smelting recipes only count if their kind of furnace is nearby (blasting needs a blast furnace).
            if (e.display() instanceof FurnaceRecipeDisplay) {
                recipe = e;
                // No furnace around: fine if it can carry its own (has one, or 8 cobblestone to make one).
                if (findStation(a, Kind.SMELT) == null && !(smeltStation(a) == Blocks.FURNACE && canHaveFurnace(a))) continue;
            }
            int score = (have ? 10 : 0) + (crafting ? 1 : 0);
            if (score > bestScore) { bestScore = score; best = e; }
        }
        if (best == null) return false;
        recipe = best;
        kind = best.display() instanceof FurnaceRecipeDisplay ? Kind.SMELT : Kind.CRAFT;
        return true;
    }

    /** A stonecutter recipe for the wanted item from something in the inventory. */
    private boolean planStonecut(AgentHandle a) {
        var set = a.stonecutterRecipes();
        if (set == null || set.isEmpty()) return false;
        List<ItemStack> inv = a.inventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack in = inv.get(i);
            if (in.isEmpty() || !set.acceptsInput(in)) continue;
            var options = set.selectByInput(in).entries();
            for (int r = 0; r < options.size(); r++) {
                ItemStack out = a.resolve(options.get(r).recipe().optionDisplay());
                if (!out.isEmpty() && Gear.id(out).equals(job.item)) {
                    kind = Kind.STONECUT;
                    stoneIndex = r;
                    stoneInput = i;
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean fits2x2(RecipeDisplayEntry e) {
        if (e == null) return false;
        if (e.display() instanceof ShapedCraftingRecipeDisplay s) return s.width() <= 2 && s.height() <= 2;
        if (e.display() instanceof ShapelessCraftingRecipeDisplay s) return s.ingredients().size() <= 4;
        return false;
    }

    private BlockPos findStation(AgentHandle a, Kind k) {
        var world = a.openWorld();
        if (world == null) return null;
        Block want = switch (k) {
            case CRAFT, CRAFT_2X2, HAND -> Blocks.CRAFTING_TABLE;
            case STONECUT -> Blocks.STONECUTTER;
            case BREW -> Blocks.BREWING_STAND;
            case SMELT -> smeltStation(a);
        };
        int ax = (int) Math.floor(a.x()), ay = (int) Math.floor(a.y()), az = (int) Math.floor(a.z());
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        // A crafting table only if it's right here (5 blocks, 2 up or down): one further off is usually somebody else's,
        // down another pit, and walking to it cost a minute before giving up. It makes or places its own instead.
        int reach = want == Blocks.CRAFTING_TABLE ? 5 : STATION_RADIUS, up = want == Blocks.CRAFTING_TABLE ? 2 : 4;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                for (int dy = -up; dy <= up; dy++) {
                    BlockState s = world.get(ax + dx, ay + dy, az + dz);
                    if (s == null || s.getBlock() != want) continue;
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d < bestD) { bestD = d; best = new BlockPos(ax + dx, ay + dy, az + dz); }
                }
            }
        }
        return best;
    }

    /** Furnace, smoker or blast furnace, as the recipe says. */
    private Block smeltStation(AgentHandle a) {
        if (recipe != null && recipe.display() instanceof FurnaceRecipeDisplay f) {
            String id = Gear.id(a.resolve(f.craftingStation()));
            if (id.endsWith("smoker")) return Blocks.SMOKER;
            if (id.endsWith("blast_furnace")) return Blocks.BLAST_FURNACE;
        }
        return Blocks.FURNACE;
    }

    // ------------------------------------------------------------------ doing it

    private void open(AgentHandle a) {
        a.lookAt(station.getX() + 0.5, station.getY() + 0.5, station.getZ() + 0.5);
        a.useItemOn(station.getX(), station.getY(), station.getZ(), Direction.UP);
        go(State.OPEN);
    }

    private void work(AgentHandle a) {
        switch (kind) {
            case CRAFT, CRAFT_2X2 -> {
                if (hand3) { handCraft3(a); return; }
                if (timer == 1) a.placeRecipe(recipe.id(), job.count > 1);
                if (timer < 6) return; // let the server fill the grid
                // Result slot 0 in both the crafting table and the inventory menu; shift-click crafts all.
                if (kind == Kind.CRAFT) a.quickMoveContainerSlot(0); else a.quickMoveInventoryMenu(0);
                go(State.TAKE);
            }
            case SMELT -> {
                if (timer == 1) a.placeRecipe(recipe.id(), job.count > 1);
                // Fuel: just what the job burns (2 planks smelt 3 iron), one right-click per item, so
                // the rest of the wood stays for sticks and tables. Shift-click only as a fallback.
                if (timer == 5) {
                    fuelFrom = fuelSlot(a.inventory());
                    fuelLeft = 0;
                    List<ItemStack> slots = a.containerSlots();
                    ItemStack in = slots.size() > 1 ? slots.get(1) : ItemStack.EMPTY;
                    if (fuelFrom >= 0) {
                        ItemStack f = a.inventory().get(fuelFrom);
                        int burns = burnItems(Gear.id(f));
                        String fid = Gear.id(f);
                        boolean wood = fid.endsWith("_planks") || fid.endsWith("_log");
                        int need = burns <= 0 ? -1 : wood ? Math.max(1, (smeltCount(a) * 2 + 2) / 3) : Math.max(1, (smeltCount(a) + burns - 1) / burns); // wood: 1.5 items each
                        // The whole stack in by shift-click, and what's left taken back out when the job is done (take()).
                        // Picking the stack up and right-clicking single items into the fuel slot left it empty every time
                        // in run 1118 ("fuel slot air, its fuel pick oak_log"): the speedrunner's food never cooked.
                        putIn(a, fuelFrom, 1);
                    } else if (in.isEmpty()) {
                        // No fuel at all: the input back out and the job ends now, not after a minute of waiting at a cold
                        // furnace (the user's screenshot: raw beef in, the fuel slot empty). The role goes for wood first.
                        a.quickMoveContainerSlot(0);
                        outOfFuel = true;
                        done(a, "no fuel for the furnace (coal, planks or logs): getting some first");
                        return;
                    }
                }
                if (timer > 5 && fuelLeft > 0 && timer % 2 == 0) { a.clickContainer(1, 1); fuelLeft--; if (fuelLeft == 0) fuelReturn = true; return; }
                if (fuelReturn && timer % 2 == 0) { a.clickContainer(a.containerMenuSlot(fuelFrom), 0); fuelReturn = false; return; } // the rest back where it was
                if (timer >= 8 && fuelLeft == 0 && !fuelReturn) go(State.WAIT);
            }
            case STONECUT -> {
                if (timer == 1) a.quickMoveToContainer(stoneInput);
                if (timer == 4) a.clickMenuButton(stoneIndex);
                if (timer == 8) { a.quickMoveContainerSlot(1); go(State.TAKE); }
            }
            case HAND -> handCraft(a);
            case BREW -> {
                List<ItemStack> inv = a.inventory();
                // Up to 3 potions (water bottles, awkward ones) into the bottle slots, one shift-click per tick. Not empty
                // glass bottles: they brew into nothing.
                boolean powderIn = job.brewIngredient.equals("minecraft:blaze_powder");
                if (timer >= 1 && timer <= 3) {
                    int s = -1;
                    for (int i = 0; i < inv.size() && s < 0; i++) if (Gear.id(inv.get(i)).equals("minecraft:potion") && !bottleTaken.contains(i)) s = i;
                    if (s >= 0) { bottleTaken.add(s); a.quickMoveToContainer(s); }
                }
                // The ingredient by a number-key swap into slot 3: a shift-click sends blaze powder to the fuel slot
                // (vanilla puts it there first), so a strength brew never got its powder.
                if (timer == 5) {
                    bottleTaken.clear();
                    int s = find(inv, job.brewIngredient);
                    if (s < 0) { done(a, "has no " + job.brewIngredient); return; }
                    putIn(a, s, 3);
                }
                // Fuel: blaze powder into the empty fuel slot (one powder brews 20 times; what's left comes back out
                // afterwards). Not when the powder is the ingredient: the stand's fuel from before does that brew.
                if (timer == 7 && !powderIn) {
                    List<ItemStack> slots = a.containerSlots();
                    int s = find(inv, "minecraft:blaze_powder");
                    if (s >= 0 && slots.size() > 4 && slots.get(4).isEmpty()) putIn(a, s, 4);
                }
                if (timer >= 9) go(State.WAIT);
            }
        }
    }

    /** Smelting: until the input is used up (or a minute). Brewing: until the ingredient is gone. */
    private int shutSince = -1, walkBackAt;

    private void waitForStation(AgentHandle a) {
        if (!a.containerOpen()) {
            // Shut and not opening again (the furnace gone, out of reach, the click refused): it tried every second for half
            // an hour, the speedrunner frozen "smelting iron" with the whole crew waiting on it. 15 s, then the job ends and
            // whoever asked for it asks again.
            // Chased off by a mob (the furnace shuts once it is out of reach): back to it first. It tried to open it again
            // from where it ran to, gave up, and left its beef and all its logs in it.
            if (!near(a, station)) {
                Block st = blockAt(a, station);
                if (st != null && st != Blocks.AIR && timer - walkBackAt < 20 * 30) {
                    if (!a.isNavigating() && timer % 40 == 0) a.navigate(station.getX(), station.getY(), station.getZ(), 2);
                    shutSince = -1;
                    return;
                }
            } else walkBackAt = timer;
            if (a.isNavigating()) a.stopNavigation();
            if (shutSince < 0) shutSince = timer;
            if (timer - shutSince > 20 * 15) { shutSince = -1; done(a, "couldn't open the " + (kind == Kind.SMELT ? "furnace" : "station") + " at " + station.toShortString() + " again"); return; }
            if (timer % 20 == 0) a.useItemOn(station.getX(), station.getY(), station.getZ(), Direction.UP);
            return;
        }
        shutSince = -1;
        List<ItemStack> slots = a.containerSlots();
        // Smelting: input used up. Brewing: one ingredient consumed, or a brew's time (20 s) passed.
        if (timer == 1 && kind == Kind.BREW) brewStartCount = slots.size() > 3 ? slots.get(3).getCount() : 0;
        if (timer == 1 && kind == Kind.SMELT) smeltStart = slots.isEmpty() ? 1 : slots.get(0).getCount();
        boolean finished = kind == Kind.SMELT ? slots.get(0).isEmpty()
                : slots.size() > 3 && (slots.get(3).getCount() < brewStartCount || timer > 20 * 22);
        if (kind == Kind.SMELT && timer % 100 == 0 && !slots.get(2).isEmpty()) a.quickMoveContainerSlot(2); // empty the output as it fills
        // 12 s in, nothing smelted and the fuel slot empty: the fuel never went in (the log: "input mutton x2, fuel slot air,
        // output air, its fuel pick oak_log"; the speedrunner lost a minute each time). The whole fuel stack in, shift-click.
        // Every 12 s, not only the first time: an item smelts in 10 s, so no input used up since the last look and the fuel
        // slot empty means the fire is out. It ran out halfway too (4 of 8 iron smelted, then 2 minutes at a cold furnace).
        if (kind == Kind.SMELT && timer == 1) lastInput = slots.get(0).getCount();
        boolean coldNow = false;
        if (kind == Kind.SMELT && timer > 0 && timer % 240 == 0) {
            int in = slots.get(0).getCount();
            coldNow = in > 0 && in >= lastInput && slots.get(1).isEmpty();
            lastInput = in;
        }
        if (coldNow) {
            int f = fuelSlot(a.inventory());
            if (f >= 0) { dev.agentnet.AgentNet.LOG.info("[{}] the furnace has no fuel in it: putting {} in", a.name(), Gear.id(a.inventory().get(f))); putIn(a, f, 1); }
            else { a.quickMoveContainerSlot(0); outOfFuel = true; done(a, "the furnace has no fuel and it carries none: getting some first"); return; }
        }
        // Smelting: ~10 s an item (a whole stack takes over 10 minutes); brewing: 20 s.
        int limit = kind == Kind.SMELT ? 20 * (30 + 11 * Math.max(1, smeltStart)) : 20 * 90;
        if (!finished && timer > limit && kind == Kind.SMELT) {
            // Why it never finished (the speedrunner tried 3 chicken three times, 63 s each, nothing came out).
            String fuel = fuelFrom >= 0 && fuelFrom < a.inventory().size() ? Gear.id(a.inventory().get(fuelFrom)) : "none";
            dev.agentnet.AgentNet.LOG.info("[{}] the furnace never finished {}: input {} x{}, fuel slot {} x{}, output {} x{} (its fuel pick: {})", a.name(), job.item,
                    Gear.id(slots.get(0)), slots.get(0).getCount(), Gear.id(slots.get(1)), slots.get(1).getCount(), Gear.id(slots.get(2)), slots.get(2).getCount(), fuel);
        }
        if (finished || timer > limit) go(State.TAKE);
    }

    // ------------------------------------------------------------------ crafting by hand
    // Recipes the recipe book hasn't unlocked yet (torches unlock only with a stone pickaxe)
    // can still be crafted by putting the items into the 2x2 grid like a player does.

    private record Step(int op, java.util.function.Predicate<String> item, int slot) {}
    private static final int PICK = 0, PUT_ALL = 1, PUT_ONE = 2, RETURN = 3, RESULT = 4, CLEAR = 5;
    private List<Step> handSteps;
    private int handStep, handFrom;

    /** The 2x2 grid (menu slots 1 2 / 3 4) for the few early recipes, or null. */
    private static Map<Integer, java.util.function.Predicate<String>> handPattern(String item) {
        java.util.function.Predicate<String> planks = id -> id.endsWith("_planks");
        return switch (item) {
            case "minecraft:torch" -> Map.of(1, id -> id.equals("minecraft:coal") || id.equals("minecraft:charcoal"), 3, id -> id.equals("minecraft:stick"));
            case "minecraft:stick" -> Map.of(1, planks, 3, planks);
            case "minecraft:crafting_table" -> Map.of(1, planks, 2, planks, 3, planks, 4, planks);
            default -> {
                if (item.endsWith("_planks")) {
                    String log = item.replace("_planks", "_log");
                    yield Map.of(1, id -> id.equals(log));
                }
                yield null;
            }
        };
    }

    /** Plan a hand craft if there's a pattern and every ingredient is there. */
    private boolean planHand(AgentHandle a) {
        var pattern = handPattern(job.item);
        if (pattern == null) return false;
        List<ItemStack> inv = a.inventory();
        // Group grid slots by ingredient: one slot gets the whole stack (craft as many as it
        // makes), several slots sharing it get one each (one craft).
        Map<java.util.function.Predicate<String>, List<Integer>> by = new java.util.LinkedHashMap<>();
        for (int slot = 1; slot <= 4; slot++) if (pattern.containsKey(slot)) by.computeIfAbsent(pattern.get(slot), k -> new ArrayList<>()).add(slot);
        List<Step> steps = new ArrayList<>();
        for (var e : by.entrySet()) {
            int have = 0;
            for (ItemStack s : inv) if (!s.isEmpty() && e.getKey().test(Gear.id(s))) have += s.getCount();
            if (have < e.getValue().size()) return false;
            steps.add(new Step(PICK, e.getKey(), -1));
            for (int slot : e.getValue()) steps.add(new Step(e.getValue().size() == 1 && job.count > 1 ? PUT_ALL : PUT_ONE, null, slot));
            steps.add(new Step(RETURN, null, -1));
        }
        steps.add(new Step(RESULT, null, 0));
        for (int slot = 1; slot <= 4; slot++) steps.add(new Step(CLEAR, null, slot));
        handSteps = steps;
        handStep = 0;
        return true;
    }

    // ---- the same at a crafting table: menu slot 0 the result, 1-9 the grid (rows of three)

    private boolean hand3;

    /** A 3x3 pattern (grid slot -> ingredient) for what has to be made at a table, or null. */
    private static Map<Integer, java.util.function.Predicate<String>> handPattern3(String item) {
        if (item.endsWith("_boat") && !item.endsWith("_chest_boat")) {
            String planks = item.replace("_boat", "_planks");
            java.util.function.Predicate<String> p = id -> id.equals(planks);
            return Map.of(4, p, 6, p, 7, p, 8, p, 9, p); // a U: two at the sides of the middle row, three along the bottom
        }
        // Tools, a furnace, a chest, a shield, a bucket: laid out by hand like a player when the recipe isn't unlocked yet
        // (a distributor "couldn't make minecraft:wooden_axe" with planks and sticks in its pack).
        // Slots 1-9, row by row.
        java.util.function.Predicate<String> stick = id -> id.equals("minecraft:stick");
        java.util.function.Predicate<String> planks = id -> id.endsWith("_planks");
        String[] tools = {"_pickaxe", "_axe", "_shovel", "_sword", "_hoe"};
        for (String t : tools) {
            if (!item.endsWith(t)) continue;
            String mat = item.substring("minecraft:".length(), item.length() - t.length());
            java.util.function.Predicate<String> m = switch (mat) {
                case "wooden" -> planks;
                case "stone" -> id -> id.equals("minecraft:cobblestone") || id.equals("minecraft:cobbled_deepslate") || id.equals("minecraft:blackstone");
                case "iron" -> id -> id.equals("minecraft:iron_ingot");
                case "golden" -> id -> id.equals("minecraft:gold_ingot");
                case "diamond" -> id -> id.equals("minecraft:diamond");
                default -> null;
            };
            if (m == null) return null;
            return switch (t) {
                case "_pickaxe" -> Map.of(1, m, 2, m, 3, m, 5, stick, 8, stick);
                case "_axe" -> Map.of(1, m, 2, m, 4, m, 5, stick, 8, stick);
                case "_shovel" -> Map.of(2, m, 5, stick, 8, stick);
                case "_sword" -> Map.of(2, m, 5, m, 8, stick);
                default -> Map.of(1, m, 2, m, 5, stick, 8, stick); // hoe
            };
        }
        java.util.function.Predicate<String> cobble = id -> id.equals("minecraft:cobblestone") || id.equals("minecraft:cobbled_deepslate") || id.equals("minecraft:blackstone");
        java.util.function.Predicate<String> iron = id -> id.equals("minecraft:iron_ingot");
        java.util.function.Predicate<String> gold = id -> id.equals("minecraft:gold_ingot");
        return switch (item) {
            case "minecraft:furnace" -> Map.of(1, cobble, 2, cobble, 3, cobble, 4, cobble, 6, cobble, 7, cobble, 8, cobble, 9, cobble);
            case "minecraft:chest" -> Map.of(1, planks, 2, planks, 3, planks, 4, planks, 6, planks, 7, planks, 8, planks, 9, planks);
            case "minecraft:shield" -> Map.of(1, planks, 2, iron, 3, planks, 4, planks, 5, planks, 6, planks, 8, planks);
            case "minecraft:bucket" -> Map.of(4, iron, 6, iron, 8, iron);
            // Armor by hand (the recipe book only has it once the advancement fires: a helper with 7 iron ingots stood
            // "making its iron leggings" for 5 minutes, run 1120e). Grid slots 1-9, rows top to bottom.
            case "minecraft:iron_helmet" -> Map.of(1, iron, 2, iron, 3, iron, 4, iron, 6, iron);
            case "minecraft:iron_chestplate" -> Map.of(1, iron, 3, iron, 4, iron, 5, iron, 6, iron, 7, iron, 8, iron, 9, iron);
            case "minecraft:iron_leggings" -> Map.of(1, iron, 2, iron, 3, iron, 4, iron, 6, iron, 7, iron, 9, iron);
            case "minecraft:iron_boots" -> Map.of(4, iron, 6, iron, 7, iron, 9, iron);
            case "minecraft:golden_helmet" -> Map.of(1, gold, 2, gold, 3, gold, 4, gold, 6, gold);
            default -> null;
        };
    }

    /** Plan job.count rounds of a 3x3 hand craft (one item each), if everything is there. */
    private boolean planHand3(AgentHandle a) {
        var pattern = handPattern3(job.item);
        if (pattern == null) return false;
        List<ItemStack> inv = a.inventory();
        Map<java.util.function.Predicate<String>, List<Integer>> by = new java.util.LinkedHashMap<>();
        for (int slot = 1; slot <= 9; slot++) if (pattern.containsKey(slot)) by.computeIfAbsent(pattern.get(slot), k -> new ArrayList<>()).add(slot);
        int rounds = Math.max(1, job.count);
        for (var e : by.entrySet()) {
            int have = 0;
            for (ItemStack s : inv) if (!s.isEmpty() && e.getKey().test(Gear.id(s))) have += s.getCount();
            rounds = Math.min(rounds, have / e.getValue().size());
        }
        if (rounds < 1) return false;
        List<Step> steps = new ArrayList<>();
        for (int r = 0; r < rounds; r++) {
            for (var e : by.entrySet()) {
                steps.add(new Step(PICK, e.getKey(), -1));
                for (int slot : e.getValue()) steps.add(new Step(PUT_ONE, null, slot));
                steps.add(new Step(RETURN, null, -1));
            }
            steps.add(new Step(RESULT, null, 0));
        }
        for (int slot = 1; slot <= 9; slot++) steps.add(new Step(CLEAR, null, slot));
        handSteps = steps;
        handStep = 0;
        return true;
    }

    /** One click every 2 ticks, in the crafting table's window. */
    private void handCraft3(AgentHandle a) {
        if (timer % 2 == 1) return;
        if (handStep >= handSteps.size()) { go(State.TAKE); return; }
        Step s = handSteps.get(handStep++);
        switch (s.op()) {
            case PICK -> {
                List<ItemStack> inv = a.inventory();
                handFrom = -1;
                for (int i = 0; i < inv.size() && i < 36; i++) if (!inv.get(i).isEmpty() && s.item().test(Gear.id(inv.get(i)))) { handFrom = a.containerMenuSlot(i); break; }
                if (handFrom < 0) { done(a, "ran out of ingredients for " + job.item); return; }
                a.clickContainer(handFrom, 0);
            }
            case PUT_ONE -> a.clickContainer(s.slot(), 1);
            case PUT_ALL -> a.clickContainer(s.slot(), 0);
            case RETURN -> { if (handFrom >= 0) a.clickContainer(handFrom, 0); }
            case RESULT -> a.quickMoveContainerSlot(0);
            case CLEAR -> a.quickMoveContainerSlot(s.slot());
        }
    }

    /** One click every 2 ticks (the server resyncs the inventory in between). */
    private void handCraft(AgentHandle a) {
        if (timer % 2 == 1) return;
        if (handStep >= handSteps.size()) { go(State.TAKE); return; }
        Step s = handSteps.get(handStep++);
        switch (s.op()) {
            case PICK -> {
                List<ItemStack> inv = a.inventory();
                handFrom = -1;
                for (int i = 0; i < inv.size() && i < 36; i++) if (!inv.get(i).isEmpty() && s.item().test(Gear.id(inv.get(i)))) { handFrom = a.inventoryMenuSlot(i); break; }
                if (handFrom < 0) { done(a, "ran out of ingredients for " + job.item); return; }
                a.clickInventoryMenu(handFrom, 0);
            }
            case PUT_ALL -> a.clickInventoryMenu(s.slot(), 0);
            case PUT_ONE -> a.clickInventoryMenu(s.slot(), 1);
            case RETURN -> { if (handFrom >= 0) a.clickInventoryMenu(handFrom, 0); }
            case RESULT -> a.quickMoveInventoryMenu(0);
            case CLEAR -> a.quickMoveInventoryMenu(s.slot());
        }
    }

    private void take(AgentHandle a) {
        if (timer == 1 && a.containerOpen()) {
            if (kind == Kind.SMELT) { a.quickMoveContainerSlot(2); a.quickMoveContainerSlot(1); } // the output, and the fuel that's left back
            if (kind == Kind.BREW) { for (int s : BREW_BOTTLES) a.quickMoveContainerSlot(s); a.quickMoveContainerSlot(3); a.quickMoveContainerSlot(4); }
        }
        if (timer < 6) return;
        // Check something actually came out (missing ingredients make a recipe click do nothing).
        if (kind != Kind.BREW && countOf(a, job.item) <= countBefore) { done(a, "couldn't make " + job.item + " (missing ingredients?)"); return; }
        made++;
        done(a, null);
    }

    private void done(AgentHandle a, String problem) {
        if (a.containerOpen()) a.closeContainer();
        if (problem != null) warn(a, problem);
        else { lastWarning = null; if (job != null) a.log("made " + (job.brewIngredient != null ? "potions with " + job.brewIngredient : job.item)); }
        // Done or failed, the job the table was made for is over: a later one may make a table again. Cleared only on
        // success, one failed shield (5 planks after the table) kept every later shield from getting a table (run 1118d).
        if (job != null && job.item.equals(tableFor)) tableFor = null;
        job = null;
        placedTable = false;
        if (jobs.isEmpty() && pickupNext(a)) return; // all done: take its own stations back
        go(State.IDLE);
    }

    // ------------------------------------------------------------------ helpers

    private int fuelFrom = -1, fuelLeft, placeRetries;
    private boolean fuelReturn;

    /** Items one piece of this fuel smelts (furnace: 200 ticks each); 0 = unknown, shift-click it all. */
    private static int burnItems(String id) {
        if (id.equals("minecraft:coal") || id.equals("minecraft:charcoal")) return 8;
        if (id.equals("minecraft:coal_block")) return 80;
        if (id.equals("minecraft:blaze_rod")) return 12;
        if (id.endsWith("_planks") || id.endsWith("_log")) return 1; // 1.5 really (handled where it is used)
        return 0;
    }

    /** How many items are in the furnace's input right now (the job may smelt them in one go). */
    private static int smeltCount(AgentHandle a) {
        List<ItemStack> slots = a.containerSlots();
        return slots.isEmpty() || slots.get(0).isEmpty() ? 1 : slots.get(0).getCount();
    }

    /**
     * The whole stack in this inventory slot into this slot of the open container, by number-key swaps inside it (a
     * shift-click sent logs to the furnace's input, not its fuel: logs smelt into charcoal, so vanilla puts them there
     * first, and the fuel slot stayed empty every time; moving to the hotbar through the inventory menu while a
     * container is open the server ignores). Out of the main inventory: into hotbar slot 7 first, then into place.
     */
    private static void putIn(AgentHandle a, int invIndex, int menuSlot) {
        int hb = invIndex;
        if (invIndex >= 9) { hb = 7; a.swapIntoContainer(a.containerMenuSlot(invIndex), hb); }
        a.swapIntoContainer(menuSlot, hb);
    }

    /** A smelting job ended for want of fuel: the role it works for goes for some (logs) before cooking again. */
    volatile boolean outOfFuel;

    /** Carries something a furnace burns. */
    static boolean hasFuel(AgentHandle a) { return fuelSlot(a.inventory()) >= 0; }

    private static int fuelSlot(List<ItemStack> inv) {
        String[] order = {"minecraft:coal", "minecraft:charcoal", "minecraft:coal_block", "minecraft:blaze_rod", "minecraft:lava_bucket"};
        for (String f : order) { int s = find(inv, f); if (s >= 0) return s; }
        // Planks before logs: a log is worth 4 planks, and both burn the same time.
        for (String end : new String[]{"_planks", "_log"}) {
            for (int i = 0; i < inv.size(); i++) {
                String id = Gear.id(inv.get(i));
                if (id.endsWith(end) && !id.contains("stripped")) return i;
            }
        }
        return -1;
    }

    private int brewStartCount, countBefore, smeltStart;
    private final java.util.Set<Integer> bottleTaken = new java.util.HashSet<>();
    private int lastInput;

    private static int countOf(AgentHandle a, String id) {
        int n = 0;
        for (ItemStack s : a.inventory()) if (!s.isEmpty() && Gear.id(s).equals(id)) n += s.getCount();
        return n;
    }

    private static int find(List<ItemStack> inv, String id) {
        for (int i = 0; i < inv.size(); i++) if (!inv.get(i).isEmpty() && Gear.id(inv.get(i)).equals(id)) return i;
        return -1;
    }

    private static boolean near(AgentHandle a, BlockPos p) {
        double dx = p.getX() + 0.5 - a.x(), dy = p.getY() + 0.5 - (a.y() + 1.62), dz = p.getZ() + 0.5 - a.z();
        return dx * dx + dy * dy + dz * dz <= 4.0 * 4.0;
    }

    private String stationName() {
        if (kind == null) return "station";
        return switch (kind) {
            case CRAFT, CRAFT_2X2, HAND -> "crafting table";
            case SMELT -> BuiltInRegistries.BLOCK.getKey(Blocks.FURNACE).getPath();
            case STONECUT -> "stonecutter";
            case BREW -> "brewing stand";
        };
    }

    private void go(State s) { state = s; timer = 0; }

    private void warn(AgentHandle a, String m) {
        if (m.equals(lastWarning)) return;
        lastWarning = m;
        a.log(m);
    }

    private static int parse(String s) {
        try { return Math.max(1, Integer.parseInt(s)); } catch (NumberFormatException e) { return 1; }
    }

    @Override
    public String status() {
        return (job == null ? "idle" : state.name().toLowerCase() + " " + (job.brewIngredient != null ? "brewing" : job.item))
                + ", " + jobs.size() + " queued, " + made + " made";
    }
}
