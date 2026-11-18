package dev.agentnet.role;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.agentnet.pathing.BlockSource;
import dev.agentnet.pathing.BlockView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Looter: empties the loot containers of generated structures, above and below ground.
 *
 * <p>It only touches a container it can tell belongs to a structure, from the blocks around it;
 * anything it can't place is treated as somebody's base and left alone. Every kind of structure
 * has its own way in:
 * <ul>
 *   <li><b>general</b> (dungeons, mineshafts, villages, ruined portals, strongholds, fortresses,
 *       bastions, outposts, igloos): walk there, or dig a staircase when walking can't;
 *       open the container and take everything (a chest under a solid block is broken instead:
 *       its contents drop and get picked up);</li>
 *   <li><b>desert temple</b>: the 4 chests sit around a stone pressure plate on 9 TNT. It never
 *       enters the pit: it digs to the block behind each chest and loots it from there;</li>
 *   <li><b>jungle temple</b>: tripwires, their hooks and the arrow dispensers are never walked
 *       through or dug; the chests are reached around them;</li>
 *   <li><b>trial chambers</b>: chests and barrels, decorated pots (broken: they drop their item),
 *       vaults when it carries a (ominous) trial key; the spawners' mobs make it back off;</li>
 *   <li><b>ancient city</b>: sneaks the whole time (sculk sensors don't hear sneaking steps),
 *       never digs or breaks anything there, and opens at most 3 containers per visit (each
 *       opening is a sound that can wake a shrieker), then leaves the city alone for 15 min;</li>
 *   <li><b>end city</b>: chests, and the ship's elytra (knocked out of its item frame) when it
 *       can get aboard.</li>
 * </ul>
 * With nothing to loot in the loaded land it explores. "/agent golem &lt;agent&gt;" kills a
 * nearby iron golem: hit it, back off a few blocks (close enough that it follows), pillar up
 * 3 blocks (out of its reach) and hit it from the top.
 */
final class LooterModule extends GathererModule {
    enum Kind { GENERAL, DESERT_TEMPLE, JUNGLE_TEMPLE, TRIAL_CHAMBERS, ANCIENT_CITY, END_CITY, SHIPWRECK, BURIED_TREASURE }

    private static final int SEARCH = 64, CITY_MAX_OPENS = 3;
    private static final long CITY_COOLDOWN_MS = 15 * 60_000;
    /** Looted (or given up) containers, shared by all looters: "dimension|x|y|z". */
    static final Set<String> LOOTED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private enum State { SEARCH, GO, OPEN, TAKE, BREAK, VAULT, PICKUP, EXPLORE, GOLEM, TRIAL, IDLE }

    private State state = State.SEARCH;

    /** Trial grinder: only trial chambers, and it stays at its chamber, fighting the spawners again after each cooldown. */
    private final boolean grinder;
    private BlockPos chamber;
    private int grindNav;

    LooterModule() { this(false); }
    LooterModule(boolean grinder) { this.grinder = grinder; }

    /**
     * The grinder with nothing left to loot: back to its chamber, and the spawners again as soon as one is off its
     * cooldown (30 minutes in vanilla). Breeze rods and heavy cores are what the crew needs for maces.
     */
    private boolean grindAgain(AgentHandle a, BlockView view) {
        if (chamber == null) return false;
        double d = Math.hypot(chamber.getX() - a.x(), chamber.getZ() - a.z());
        if (d > 24) {
            status = "going back to its trial chamber at " + chamber.toShortString();
            if (--grindNav <= 0 && !a.isNavigating()) { grindNav = 5; a.navigate(chamber.getX(), chamber.getY(), chamber.getZ(), 4); }
            return true;
        }
        boolean ready = false;
        for (BlockPos p : view.findAll(chamber.getX(), chamber.getZ(), 48, s -> s.getBlock() == Blocks.TRIAL_SPAWNER, 32)) {
            BlockState s = view.get(p.getX(), p.getY(), p.getZ());
            String st = s == null ? "cooldown" : s.toString();
            if (Math.abs(p.getY() - chamber.getY()) <= 24 && !st.contains("cooldown") && !st.contains("ejecting_reward") && !st.contains("waiting_for_reward")) { ready = true; break; }
        }
        if (ready) {
            trial.start(a, view, chamber);
            if (trial.active()) { a.log("grinding the trial chamber again"); state = State.TRIAL; timer = 0; return true; }
        }
        status = "at its trial chamber: waiting for the spawners' cooldown";
        return true;
    }
    private int ticks, timer, wait, opened, navTries;
    private BlockPos target, goal;
    private Kind kind;
    private String status = "";
    private final Tunnel tunnel = new Tunnel();
    private boolean tunnelling;
    private final ExplorerModule explorer = new ExplorerModule();
    private boolean exploring;
    /** Ancient city center -> containers opened there this visit / when we left it. */
    private final Map<BlockPos, Integer> cityOpens = new HashMap<>();
    private final Map<BlockPos, Long> cityLeft = new HashMap<>();
    private BlockPos city;
    private final Golem golem = new Golem();

    @Override
    public void onStart(AgentHandle a) { a.keepWorld(true); }

    @Override
    public void onStop(AgentHandle a) {
        stopExploring(a);
        a.setSneaking(false);
        a.keepWorld(false);
        if (a.isNavigating()) a.stopNavigation();
    }

    @Override
    public void onTask(AgentHandle a, String task) {
        if (task.equals("idle")) { stopExploring(a); state = State.IDLE; status = "stopped"; return; }
        if (task.startsWith("golem") || task.startsWith("kill iron_golem")) {
            stopExploring(a);
            golem.start(a);
            state = State.GOLEM;
            return;
        }
        if (task.startsWith("loot") || task.startsWith("grind")) { state = State.SEARCH; a.log("looking for structures to loot"); }
    }

    @Override
    public String status() { return status; }

    @Override
    protected Set<Integer> keepSlots(AgentHandle a) {
        // Weapon, pickaxe, food, pillar blocks and trial keys stay; the loot goes to the base.
        Set<Integer> keep = new java.util.HashSet<>();
        List<ItemStack> inv = a.inventory();
        for (String t : new String[]{"sword", "axe", "pickaxe", "shovel"}) { int i = Gear.bestTool(inv, t); if (i >= 0) keep.add(i); }
        keepFood(inv, keep, Set.of());
        for (int i = 0; i < inv.size(); i++) {
            String id = Gear.id(inv.get(i));
            if (id.contains("trial_key") || id.equals("minecraft:cobblestone") || id.equals("minecraft:dirt") || id.equals("minecraft:netherrack")) keep.add(i);
        }
        return keep;
    }

    @Override
    protected void work(AgentHandle a) {
        ticks++;
        timer++;
        if (wait > 0) { wait--; return; }
        if (a.isDigging()) return;
        switch (state) {
            case IDLE -> {}
            case SEARCH -> { if (timer % 20 == 1) search(a); }
            case GO -> go(a);
            case OPEN -> open(a);
            case TAKE -> take(a);
            case BREAK -> breakIt(a);
            case VAULT -> vault(a);
            case PICKUP -> pickup(a);
            case GOLEM -> { if (golem.tick(a)) { state = State.PICKUP; timer = 0; target = BlockPos.containing(a.x(), a.y(), a.z()); } status = golem.status; }
            case TRIAL -> { status = trial.status; if (!trial.tick(a)) { trialsDone.add(trialKey); state = State.GO; timer = 0; } }
            case EXPLORE -> {
                if (structGoal != null) { travelToStructure(a); if (timer % 100 == 0) search(a); return; }
                explorer.tick(a);
                status = "nothing to loot around: exploring (" + explorer.status() + ")";
                if (timer % 100 == 0) search(a);
            }
        }
    }

    // ------------------------------------------------------------------ finding loot

    private static boolean container(BlockState s) {
        Block b = s.getBlock();
        return b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST || b == Blocks.BARREL || b == Blocks.DECORATED_POT || b == Blocks.VAULT;
    }

    private String key(AgentHandle a, BlockPos p) { return a.dimension() + "|" + p.getX() + "|" + p.getY() + "|" + p.getZ(); }

    private void search(AgentHandle a) {
        BlockSource w = a.openWorld();
        if (!(w instanceof BlockView view)) { status = "waiting for world data"; return; }
        int fx = (int) Math.floor(a.x()), fz = (int) Math.floor(a.z());
        BlockPos best = null;
        Kind bestKind = null;
        double bd = Double.MAX_VALUE;
        long now = System.currentTimeMillis();
        for (BlockPos p : view.findAll(fx, fz, SEARCH, LooterModule::container, 256)) {
            if (LOOTED.contains(key(a, p)) || ours(a, p)) continue;
            Kind k = classify(a, view, p);
            if (wet(w, p) && k != Kind.SHIPWRECK && k != Kind.BURIED_TREASURE) continue; // flooded caves: no; shipwrecks are dived for
            if (ironOnly && k != null && !ironLikely(view, p, k)) continue;
            // A shipwreck's iron is only in its treasure chest, down in the hold: the supply and map chests sit higher.
            // So for iron, a wreck chest with another of the same wreck 2+ blocks lower is skipped.
            if (ironOnly && k == Kind.SHIPWRECK && lowerWreckChest(view, p)) continue;
            if (k != null) remember(a, k, p);
            if (k == null) continue; // can't tell it's a structure: maybe someone's base
            if (grinder && k != Kind.TRIAL_CHAMBERS) continue;
            BlockState s = w.get(p.getX(), p.getY(), p.getZ());
            if (s != null && s.getBlock() == Blocks.VAULT && !hasKeyFor(a, s)) continue;
            if (s != null && s.getBlock() == Blocks.DECORATED_POT && k != Kind.TRIAL_CHAMBERS) continue; // pots elsewhere: decoration
            if (k == Kind.ANCIENT_CITY) {
                BlockPos c = cityOf(view, p);
                Long left = cityLeft.get(c);
                if (left != null && now - left < CITY_COOLDOWN_MS) continue; // let the warning level cool down
            }
            double d = p.distToCenterSqr(a.x(), a.y(), a.z());
            if (d < bd) { bd = d; best = p; bestKind = k; }
        }
        if (best == null && grinder && grindAgain(a, view)) return;
        if (best == null && ++emptySearches >= 3 && goRemembered(a)) return; // a structure seen earlier still has loot: go back there
        if (best != null) emptySearches = 0;
        if (best == null) {
            if (!exploring) {
                exploring = true;
                explorer.onStart(a);
                explorer.onTask(a, "explore 1024");
                a.log("no structure loot in sight: exploring");
            }
            state = State.EXPLORE;
            return;
        }
        structGoal = null;
        stopExploring(a);
        target = best;
        kind = bestKind;
        city = kind == Kind.ANCIENT_CITY ? cityOf(view, best) : null;
        plate = kind == Kind.DESERT_TEMPLE ? plateNear(a, view, best) : null;
        tntStarted = false; tntDug = 0; tntStep = 0; backToGo = false; tunnelArrived = false; stepTries = 0;
        goal = approachGoal(a, view, best);
        tunnel.reset();
        tunnel.forbid = kind == Kind.DESERT_TEMPLE ? pitOf(plate) : kind == Kind.ANCIENT_CITY ? quietZone(view, best) : p -> false;
        walkOnly = false;
        tunnelling = false;
        navTries = 0;
        state = State.GO;
        timer = 0;
        a.setSneaking(kind == Kind.ANCIENT_CITY);
        a.log(String.format("looting a %s container at %s (%s)", w.get(best.getX(), best.getY(), best.getZ()).getBlock().getName().getString(),
                best.toShortString(), kind.name().toLowerCase().replace('_', ' ')));
    }

    // ------------------------------------------------------------------ remembering structures

    /** Structures seen (one per 64x64 area): kept as map points "loot <kind> x z" for later. */
    private final Set<String> remembered = new java.util.HashSet<>();
    private dev.agentnet.map.Pois.Poi structGoal;
    private int structTries, emptySearches;

    /** A structure recognized on the way: note it on the shared map (once), whether or not we loot it now. */
    private void remember(AgentHandle a, Kind k, BlockPos p) {
        String key = a.dimension() + "|" + (p.getX() >> 6) + "|" + (p.getZ() >> 6);
        if (!remembered.add(key)) return;
        String name = "loot " + k.name().toLowerCase().replace('_', ' ') + " " + p.getX() + " " + p.getZ();
        if (dev.agentnet.map.Pois.add(a.server(), a.dimension(), p.getX(), p.getY(), p.getZ(), name))
            dev.agentnet.AgentNet.LOG.info("[{}] noted a {} at {}", a.name(), k.name().toLowerCase().replace('_', ' '), p.toShortString());
    }

    /** Nothing to loot in sight: the nearest remembered structure (not checked yet this session), if any. */
    private boolean goRemembered(AgentHandle a) {
        dev.agentnet.map.Pois.Poi best = null;
        double bd = Double.MAX_VALUE;
        for (var poi : dev.agentnet.map.Pois.list(a.server(), a.dimension())) {
            if (!poi.name().startsWith(grinder ? "loot trial chambers" : "loot ")) continue;
            double d = Math.hypot(poi.x() - a.x(), poi.z() - a.z());
            if (d < 48 || d > 1500) continue; // right here (we'd see its loot) or too far to be worth the walk
            if (d < bd) { bd = d; best = poi; }
        }
        if (best == null) return false;
        if (structGoal == null || !structGoal.name().equals(best.name())) {
            structGoal = best;
            structTries = 0;
            a.log("going back to a " + best.name().substring(5).replaceAll(" -?\\d+ -?\\d+$", "") + " it saw earlier (" + Math.round(bd) + " blocks)");
        }
        if (!exploring) { exploring = true; explorer.onStart(a); }
        state = State.EXPLORE;
        timer = 0;
        return true;
    }

    private void travelToStructure(AgentHandle a) {
        status = "going back to " + structGoal.name();
        if (Math.hypot(structGoal.x() - a.x(), structGoal.z() - a.z()) < 40) {
            // There: whatever is left shows up in the next search; nothing left = forget it.
            dev.agentnet.map.Pois.remove(a.server(), structGoal.name());
            structGoal = null;
            search(a);
            return;
        }
        if (!a.isNavigating() && timer % 40 == 1) {
            if (a.navStatus().startsWith("failed") && ++structTries > 5) {
                a.log("can't get back to " + structGoal.name() + ": forgetting it");
                dev.agentnet.map.Pois.remove(a.server(), structGoal.name());
                structGoal = null;
                return;
            }
            a.navigate(structGoal.x(), structGoal.y(), structGoal.z(), 16);
        }
    }

    /** Water in the container's block or within 2 blocks of it. */
    /**
     * Buried treasure (the user: "any chest that is under sand on the 9 9 chunk coordinates"): vanilla always puts it at
     * block 9, 9 of its chunk, a plain chest under sand, sandstone or gravel. Its loot: iron ingots (often a lot), gold, a
     * heart of the sea. Nothing else generates a lone chest at that spot under sand.
     */
    static boolean buriedTreasure(BlockView view, BlockPos p) {
        if ((p.getX() & 15) != 9 || (p.getZ() & 15) != 9) return false;
        BlockState s = view.get(p.getX(), p.getY(), p.getZ());
        if (s == null || s.getBlock() != Blocks.CHEST) return false;
        for (int dy = 1; dy <= 2; dy++) {
            BlockState up = view.get(p.getX(), p.getY() + dy, p.getZ());
            if (up == null) return false;
            Block b = up.getBlock();
            if (b == Blocks.SAND || b == Blocks.RED_SAND || b == Blocks.SANDSTONE || b == Blocks.RED_SANDSTONE || b == Blocks.GRAVEL || b == Blocks.SUSPICIOUS_SAND) return true;
            if (dy == 1 && !up.isAir() && up.getFluidState().isEmpty()) continue;
            return false;
        }
        return false;
    }

    private static boolean wet(BlockSource w, BlockPos p) {
        for (int x = -2; x <= 2; x++) for (int y = -1; y <= 2; y++) for (int z = -2; z <= 2; z++) {
            BlockState s = w.get(p.getX() + x, p.getY() + y, p.getZ() + z);
            if (s != null && !s.getFluidState().isEmpty()) return true;
        }
        return false;
    }

    /** Next to our own stored chests or a registered farm: ours, never loot it. */
    private static boolean ours(AgentHandle a, BlockPos p) {
        for (var c : ChestMemory.list(a.server(), a.dimension())) {
            if (Math.abs(c.x() - p.getX()) <= 16 && Math.abs(c.z() - p.getZ()) <= 16 && Math.abs(c.y() - p.getY()) <= 8) return true;
        }
        for (var f : dev.agentnet.map.Farms.list(a.server())) if (dev.agentnet.role.AgentHandle.sameDim(f.dimension(), a.dimension()) && f.contains(p.getX(), p.getY(), p.getZ())) return true;
        return false;
    }

    /** Which structure this container belongs to, from the blocks around it; null = can't tell. */
    static Kind classify(AgentHandle a, BlockView view, BlockPos p) {
        if (buriedTreasure(view, p)) return Kind.BURIED_TREASURE;
        Map<Block, Integer> around = new HashMap<>();
        int r = 10;
        for (int x = -r; x <= r; x++) for (int y = -8; y <= 8; y++) for (int z = -r; z <= r; z++) {
            BlockState s = view.get(p.getX() + x, p.getY() + y, p.getZ() + z);
            if (s != null && !s.isAir()) around.merge(s.getBlock(), 1, Integer::sum);
        }
        java.util.function.Predicate<Block> has = around::containsKey;
        if (a.dimension() != null && a.dimension().endsWith("the_end") && (has.test(Blocks.PURPUR_BLOCK) || has.test(Blocks.PURPUR_PILLAR))) return Kind.END_CITY;
        if (has.test(Blocks.SCULK_SHRIEKER) || has.test(Blocks.REINFORCED_DEEPSLATE) || has.test(Blocks.SCULK_SENSOR) && has.test(Blocks.DEEPSLATE_TILES)) return Kind.ANCIENT_CITY;
        if (has.test(Blocks.TRIAL_SPAWNER) || has.test(Blocks.VAULT) || has.test(Blocks.TUFF_BRICKS) && has.test(Blocks.CHISELED_TUFF)) return Kind.TRIAL_CHAMBERS;
        if (has.test(byId("orange_terracotta")) && (has.test(Blocks.CUT_SANDSTONE) || has.test(Blocks.CHISELED_SANDSTONE)) && (has.test(Blocks.TNT) || has.test(Blocks.STONE_PRESSURE_PLATE))) return Kind.DESERT_TEMPLE;
        // Shipwreck: the container in or by water, in a hull of planks and logs with trapdoors, fences or stairs.
        boolean water = false;
        for (int x = -2; x <= 2 && !water; x++) for (int y = -1; y <= 2 && !water; y++) for (int z = -2; z <= 2 && !water; z++) {
            BlockState s = view.get(p.getX() + x, p.getY() + y, p.getZ() + z);
            if (s != null && s.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) water = true;
        }
        // (On dry land too: wrecks on beaches and buried in hills, as long as nothing around looks player-made.)
        boolean playerMade = has.test(Blocks.CRAFTING_TABLE) || has.test(Blocks.FURNACE) || has.test(Blocks.TORCH) || around.keySet().stream().anyMatch(b -> b instanceof net.minecraft.world.level.block.BedBlock);
        if (water || !playerMade) {
            int wood = 0, fittings = 0;
            for (var e : around.entrySet()) {
                String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(e.getKey()).getPath();
                if (id.endsWith("_planks") || id.endsWith("_log") || id.endsWith("_wood")) wood += e.getValue();
                if (id.endsWith("_trapdoor") || id.endsWith("_fence") || id.endsWith("_stairs") || id.endsWith("_slab")) fittings += e.getValue();
            }
            if (water ? wood >= 20 && fittings >= 4 : wood >= 30 && fittings >= 6) return Kind.SHIPWRECK;
        }
        if (has.test(Blocks.MOSSY_COBBLESTONE) && (has.test(Blocks.TRIPWIRE_HOOK) || has.test(Blocks.DISPENSER) || has.test(Blocks.STICKY_PISTON))) return Kind.JUNGLE_TEMPLE;
        // General structures: a sign of the structure right by the container.
        if (has.test(Blocks.SPAWNER)                                                                // dungeon, fortress
                || has.test(Blocks.RAIL) && has.test(Blocks.COBWEB)                                 // mineshaft
                || has.test(Blocks.BELL)                                                            // village
                || has.test(Blocks.CRYING_OBSIDIAN) && has.test(Blocks.NETHERRACK)                  // ruined portal
                || has.test(Blocks.END_PORTAL_FRAME) || has.test(Blocks.CRACKED_STONE_BRICKS) && has.test(Blocks.IRON_BARS) // stronghold
                || has.test(Blocks.NETHER_BRICK_FENCE)                                              // nether fortress
                || has.test(Blocks.GILDED_BLACKSTONE)                                               // bastion
                || has.test(Blocks.DARK_OAK_LOG) && has.test(Blocks.BIRCH_PLANKS) && has.test(Blocks.COBBLESTONE_WALL) // outpost
                || has.test(Blocks.SNOW_BLOCK) && has.test(byId("white_carpet")))                    // igloo
            return Kind.GENERAL;
        for (Seen s : a.entitiesNear(32)) if (!s.player() && s.name().equals("villager")) return Kind.GENERAL; // village house
        return null;
    }

    private static Block byId(String id) { return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.withDefaultNamespace(id)); }

    /** Ancient cities are big: one key per 64x64 area, so the 3-per-visit limit spans the city. */
    private static BlockPos cityOf(BlockView view, BlockPos p) { return new BlockPos(p.getX() >> 6, 0, p.getZ() >> 6); }

    private boolean walkOnly;

    private final TrialRun trial = new TrialRun();
    /** Trial chambers already fought through (one key per 64x64 area). */
    private final Set<String> trialsDone = new java.util.HashSet<>();
    private String trialKey;

    private static String chamberKey(AgentHandle a, BlockPos p) { return a.dimension() + "|" + (p.getX() >> 6) + "|" + (p.getZ() >> 6); }

    /** Fighting a trial chamber's waves: don't run from its mobs. */
    @Override
    public boolean fighting() { return state == State.TRIAL; }

    /** Ancient city: no digging within 12 blocks of a sculk sensor or shrieker (the vibrations). */
    private static java.util.function.Predicate<BlockPos> quietZone(BlockView view, BlockPos c) {
        List<BlockPos> sculk = view.findAll(c.getX(), c.getZ(), 48, s -> s.getBlock() == Blocks.SCULK_SENSOR
                || s.getBlock() == Blocks.SCULK_SHRIEKER || s.getBlock() == Blocks.CALIBRATED_SCULK_SENSOR, 512);
        return p -> {
            for (BlockPos s : sculk) if (s.distSqr(p) <= 12 * 12) return true;
            return false;
        };
    }

    /** Desert temple plates seen (they stay remembered after it breaks one), by dimension. */
    static final Set<String> PLATES = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private BlockPos plate;

    /** The temple's trap plate near this chest: in the world, or remembered after breaking it. */
    private BlockPos plateNear(AgentHandle a, BlockView view, BlockPos chest) {
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) for (int y = -2; y <= 2; y++) {
            BlockPos q = chest.offset(x, y, z);
            BlockState s = view.get(q.getX(), q.getY(), q.getZ());
            if (s != null && s.getBlock() == Blocks.STONE_PRESSURE_PLATE) { PLATES.add(a.dimension() + "|" + q.asLong()); return q; }
            if (PLATES.contains(a.dimension() + "|" + q.asLong())) return q;
        }
        // Plate already gone (an earlier visit): the TNT under the floor still marks the spot.
        int n = 0, sx = 0, sz = 0;
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) for (int y = -5; y <= -1; y++) {
            BlockState s = view.get(chest.getX() + x, chest.getY() + y, chest.getZ() + z);
            if (s != null && s.getBlock() == Blocks.TNT) { n++; sx += chest.getX() + x; sz += chest.getZ() + z; }
        }
        if (n > 0) return new BlockPos(Math.round((float) sx / n), chest.getY(), Math.round((float) sz / n));
        return null;
    }

    /**
     * Desert temple no-go cells: the plate itself (never step on it), the TNT under the pit floor
     * (never dig it), and the shaft straight above the plate (it would drop us onto it).
     */
    private static java.util.function.Predicate<BlockPos> pitOf(BlockPos pl) {
        if (pl == null) return p -> false;
        return p -> p.equals(pl)
                || Math.abs(p.getX() - pl.getX()) <= 1 && Math.abs(p.getZ() - pl.getZ()) <= 1 && p.getY() < pl.getY()   // floor + TNT
                || p.getX() == pl.getX() && p.getZ() == pl.getZ() && p.getY() > pl.getY();                              // the drop shaft
    }

    /**
     * Where to stand. Desert temple: a corner of the pit floor, diagonal to the plate: the plate
     * and all 4 chests are in reach from there, and a staircase can come down to it without
     * touching the plate. Elsewhere: the container itself (walking gets within reach; a tunnel
     * ends next to it).
     */
    private BlockPos approachGoal(AgentHandle a, BlockView view, BlockPos c) {
        if (kind != Kind.DESERT_TEMPLE || plate == null) return c;
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (int[] d : new int[][]{{1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
            BlockPos corner = plate.offset(d[0], 0, d[1]);
            double dist = corner.distToCenterSqr(a.x(), a.y(), a.z());
            if (dist < bd) { bd = dist; best = corner; }
        }
        return best;
    }

    private boolean tntStarted, backToGo, tunnelArrived;
    private int stepTries;

    /** Standing on the desert pit floor (the corner, or the old plate spot once it's gone): every chest in reach. */
    private boolean inPit(AgentHandle a) {
        return plate != null && Math.abs(Math.floor(a.x()) - plate.getX()) <= 1 && Math.abs(Math.floor(a.z()) - plate.getZ()) <= 1
                && a.y() - plate.getY() < 1.5 && a.y() - plate.getY() > -4.5; // the floor, or down in the dug-out TNT hole
    }
    private int tntDug, tntStep;

    /**
     * Desert temple, plate gone: dig out the TNT under the pit floor (3x3, a couple of blocks down),
     * with the sandstone over it. Never lit: only a plate, redstone or fire lights TNT, and a broken
     * TNT block just drops. Our own column last: by then we stand on the old plate spot (safe now).
     * Then the drops get picked up. True while there's digging left.
     */
    private boolean mineTnt(AgentHandle a) {
        BlockSource w = a.openWorld();
        int fx = (int) Math.floor(a.x()), fy = (int) Math.floor(a.y() + 0.01), fz = (int) Math.floor(a.z());
        BlockPos dig = null;
        boolean ownLeft = false;
        for (int y = plate.getY() - 1; y >= plate.getY() - 4 && dig == null; y--) {
            for (int x = -1; x <= 1 && dig == null; x++) for (int z = -1; z <= 1 && dig == null; z++) {
                BlockPos p = new BlockPos(plate.getX() + x, y, plate.getZ() + z);
                if (!tntBelow(w, p)) continue;   // nothing (more) to dig in this column
                BlockState s = w.get(p.getX(), p.getY(), p.getZ());
                if (s == null || s.isAir() || !s.getFluidState().isEmpty()) continue;
                if (p.getX() == fx && p.getZ() == fz) { ownLeft = true; continue; } // under our own feet: last
                dig = p;
            }
        }
        if (dig == null && ownLeft) {
            // The last column is the one we stand on: dig straight down (mined TNT just drops,
            // and we only ever fall a block at a time; the chests stay in reach from the hole).
            if (a.airborne()) return true;
            BlockPos below = new BlockPos(fx, fy - 1, fz);
            BlockState s = w.get(below.getX(), below.getY(), below.getZ());
            if (s != null && !s.isAir() && tntBelow(w, below)) dig = below;
        }
        if (dig == null) {
            if (tntStarted && tntDug > 0 && tntStep++ == 0) {
                a.log("dug out " + tntDug + " TNT block(s) from the desert temple: collecting them");
                state = State.PICKUP; timer = 0; backToGo = true; // the TNT fell around; then back to this chest
                return true;
            }
            return false;
        }
        tntStarted = true;
        status = "digging out the TNT under the temple floor";
        BlockState s = w.get(dig.getX(), dig.getY(), dig.getZ());
        if (s.getBlock() == Blocks.TNT) tntDug++;
        Tunnel.holdBest(a, s);
        a.lookAt(dig.getX() + 0.5, dig.getY() + 0.5, dig.getZ() + 0.5);
        int t = a.dig(dig.getX(), dig.getY(), dig.getZ());
        if (t < 0) { LOOTED.add(a.dimension() + "|tnt|" + dig.asLong()); return false; }
        wait = t + 2;
        return true;
    }

    /** TNT in this column at or below this block (down to 4 under the plate). */
    private boolean tntBelow(BlockSource w, BlockPos p) {
        for (int y = p.getY(); y >= plate.getY() - 4; y--) {
            BlockState s = w.get(p.getX(), y, p.getZ());
            if (s != null && s.getBlock() == Blocks.TNT) return true;
        }
        return false;
    }

    /** Desert temple: the plate is still there and we're in the pit corner: break it before anything else. */
    private boolean breakPlate(AgentHandle a) {
        if (plate == null) return false;
        BlockState s = a.openWorld().get(plate.getX(), plate.getY(), plate.getZ());
        if (s == null || s.getBlock() != Blocks.STONE_PRESSURE_PLATE) return false;
        status = "breaking the pressure plate over the TNT";
        a.lookAt(plate.getX() + 0.5, plate.getY() + 0.1, plate.getZ() + 0.5);
        int t = a.dig(plate.getX(), plate.getY(), plate.getZ());
        if (t >= 0) { wait = t + 2; if (timer % 40 == 0 || timer < 3) a.log("breaking the desert temple's pressure plate (standing beside it)"); }
        return true;
    }

    // ------------------------------------------------------------------ getting there

    private void go(AgentHandle a) {
        status = "on the way to loot at " + target.toShortString() + (tunnelling ? " (digging)" : "");
        if (gone(a)) { done(a, null); return; }
        if (kind == Kind.SHIPWRECK && a.openWorld() instanceof BlockView sv && wet(sv, target)) { diveFor(a, sv); return; }
        // Desert temple: right next to the corner (the staircase ends within a block): step into it.
        if (kind == Kind.DESERT_TEMPLE && plate != null && !tntStarted && !inPit(a) && near(a, goal, 2.5) && (!tunnelling || tunnelArrived)) {
            if (a.isNavigating() || timer % 20 != 0) return;
            if (stepTries++ < 2) { a.navigate(goal.getX(), goal.getY(), goal.getZ(), 0); return; }
            // Walking in failed (a wall between us and the corner): dig the corner open, feet and head.
            BlockSource w = a.openWorld();
            for (BlockPos c : new BlockPos[]{goal.above(), goal}) {
                BlockState s = w.get(c.getX(), c.getY(), c.getZ());
                if (s != null && !Tunnel.passable(s) && !tunnel.forbid.test(c) && !Tunnel.trap(w, c)) {
                    Tunnel.holdBest(a, s);
                    a.lookAt(c.getX() + 0.5, c.getY() + 0.5, c.getZ() + 0.5);
                    int t = a.dig(c.getX(), c.getY(), c.getZ());
                    if (t >= 0) { wait = t + 2; return; }
                }
            }
            if (stepTries > 6) {
                dev.agentnet.AgentNet.LOG.info("[{}] can't step into the pit corner {} from {} {} {} (plate {}): digging there instead", a.name(), goal.toShortString(),
                        String.format("%.1f", a.x()), String.format("%.1f", a.y()), String.format("%.1f", a.z()), plate.toShortString());
                if (stepTries > 12) { done(a, "can't get into the pit corner"); return; }
                tunnelling = true; tunnelArrived = false; tunnel.reset(); tunnel.forbid = pitOf(plate); // let the staircase digger find a way in
                return;
            }
            a.navigate(goal.getX(), goal.getY(), goal.getZ(), 0);
            return;
        }
        if (kind == Kind.DESERT_TEMPLE && plate != null && (inPit(a) || tntStarted)) {
            if (a.isNavigating()) a.stopNavigation();
            if (breakPlate(a)) return; // the trap first
            if (mineTnt(a)) return;    // then the TNT under the floor; then the chests, all in reach of this corner
        }
        if (canReach(a, target) && kind == Kind.TRIAL_CHAMBERS && !trialsDone.contains(trialKey = chamberKey(a, target))) {
            // In the chamber: beat its spawners (keys for the vaults) before looting.
            if (a.isNavigating()) a.stopNavigation();
            if (a.openWorld() instanceof BlockView v) trial.start(a, v, target);
            if (grinder && chamber == null) { chamber = target; a.log("found its trial chamber at " + target.toShortString() + ": grinding it from now on"); }
            if (trial.active()) { state = State.TRIAL; timer = 0; return; }
            trialsDone.add(trialKey);
        }
        if (canReach(a, target) && (kind != Kind.DESERT_TEMPLE || plate == null || inPit(a))) {
            if (a.isNavigating()) a.stopNavigation();
            state = state(a);
            timer = 0;
            return;
        }
        if (timer > 20 * 240) { done(a, "took too long to get there"); return; }
        if (kind == Kind.ANCIENT_CITY) a.setSneaking(walkOnly || target.distToCenterSqr(a.x(), a.y(), a.z()) < 40 * 40);
        if (tunnelling) {
            // (Ancient city: the tunnel stops 12 blocks short of any sculk; the rest is walked, sneaking.)
            Tunnel.Result r = tunnel.tick(a, goal);
            if (r == Tunnel.Result.BLOCKED && kind == Kind.ANCIENT_CITY && !walkOnly) { tunnelling = false; walkOnly = true; navTries = 0; timer = 0; a.log("at the edge of the ancient city: sneaking the rest of the way"); return; }
            if (r == Tunnel.Result.BLOCKED) done(a, "can't dig there: " + tunnel.problem());
            if (r == Tunnel.Result.ARRIVED && kind == Kind.DESERT_TEMPLE) { tunnelArrived = true; return; }
            if (r == Tunnel.Result.ARRIVED && !canReach(a, target)) done(a, "dug next to it but still can't reach it");
            return;
        }
        if (a.isNavigating()) return;
        if (timer == 1 || timer % 40 == 0) {
            if (navTries > 0 && a.navStatus().startsWith("failed")) {
                if (walkOnly && navTries > 3) { done(a, "can't walk to it, and digging there would wake the warden"); return; }
                if (!walkOnly) {
                    tunnelling = true;
                    a.log("can't walk to it: digging a way in");
                    return;
                }
            }
            navTries++;
            BlockPos g = kind == Kind.DESERT_TEMPLE ? goal : target;
            a.navigate(g.getX(), g.getY(), g.getZ(), kind == Kind.DESERT_TEMPLE ? 0 : 2);
        }
    }

    /** What to do with the container once in reach. */
    private State state(AgentHandle a) {
        BlockSource w = a.openWorld();
        BlockState s = w.get(target.getX(), target.getY(), target.getZ());
        if (s.getBlock() == Blocks.DECORATED_POT) return State.BREAK;
        if (s.getBlock() == Blocks.VAULT) return State.VAULT;
        if (s.getBlock() == Blocks.CHEST || s.getBlock() == Blocks.TRAPPED_CHEST) {
            BlockState above = w.get(target.getX(), target.getY() + 1, target.getZ());
            if (above != null && above.isRedstoneConductor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) && kind != Kind.ANCIENT_CITY) return State.BREAK; // can't open: break it, the loot drops
        }
        return State.OPEN;
    }

    private boolean gone(AgentHandle a) {
        BlockSource w = a.openWorld();
        BlockState s = w == null ? null : w.get(target.getX(), target.getY(), target.getZ());
        return s != null && !container(s);
    }

    private static boolean canReach(AgentHandle a, BlockPos p) {
        double dx = p.getX() + 0.5 - a.x(), dy = p.getY() + 0.5 - (a.y() + 1.62), dz = p.getZ() + 0.5 - a.z();
        return dx * dx + dy * dy + dz * dz <= 4.3 * 4.3;
    }

    private static boolean inCell(AgentHandle a, BlockPos p) {
        return (int) Math.floor(a.x()) == p.getX() && (int) Math.floor(a.z()) == p.getZ() && Math.abs(a.y() - p.getY()) < 0.6;
    }

    private static boolean near(AgentHandle a, BlockPos p, double r) {
        return Math.abs(a.x() - (p.getX() + 0.5)) <= r && Math.abs(a.z() - (p.getZ() + 0.5)) <= r && Math.abs(a.y() - p.getY()) <= 1.5;
    }

    // ------------------------------------------------------------------ taking it

    /**
     * A shipwreck chest under water: to the surface right above it (swimming there), straight down (through water,
     * digging deck planks in the way), open it from within reach. Diving on purpose for 15 s at a time: then up for air.
     */
    /** Going for loot right now (not searching or exploring). */
    /** Only chests that can hold iron (the speedrunner's looting): see ironLikely. */
    boolean ironOnly;

    /**
     * Vanilla loot with a fair chance of iron: shipwrecks (the treasure chest), desert and jungle temples, dungeons,
     * mineshafts, strongholds, ruined portals (iron nuggets, obsidian, flint and steel), and in villages only the smiths'
     * chests (weaponsmith, toolsmith, armorer: next to a grindstone, smithing table or blast furnace).
     */
    /** Another chest or barrel of the same wreck (within 14 across) at least 2 blocks lower than this one. */
    private static boolean lowerWreckChest(BlockView view, BlockPos p) {
        for (int x = -14; x <= 14; x++) for (int z = -14; z <= 14; z++) for (int y = -8; y <= -2; y++) {
            BlockState s = view.get(p.getX() + x, p.getY() + y, p.getZ() + z);
            if (s != null && (s.getBlock() == Blocks.CHEST || s.getBlock() == Blocks.BARREL)) return true;
        }
        return false;
    }

    private static boolean ironLikely(BlockView view, BlockPos p, Kind k) {
        if (k == Kind.SHIPWRECK || k == Kind.DESERT_TEMPLE || k == Kind.JUNGLE_TEMPLE || k == Kind.BURIED_TREASURE) return true;
        if (k != Kind.GENERAL) return false;
        boolean village = false, smith = false, other = false;
        for (int x = -6; x <= 6; x++) for (int y = -4; y <= 4; y++) for (int z = -6; z <= 6; z++) {
            BlockState s = view.get(p.getX() + x, p.getY() + y, p.getZ() + z);
            if (s == null) continue;
            Block b = s.getBlock();
            if (b == Blocks.BELL) village = true;
            if (Math.abs(x) <= 4 && Math.abs(z) <= 4 && (b == Blocks.GRINDSTONE || b == Blocks.SMITHING_TABLE || b == Blocks.BLAST_FURNACE)) smith = true;
            if (b == Blocks.SPAWNER || b == Blocks.COBWEB || b == Blocks.CRYING_OBSIDIAN || b == Blocks.END_PORTAL_FRAME || b == Blocks.MOSSY_COBBLESTONE) other = true;
        }
        if (smith) return true;
        if (village) return false; // the other village chests: food, saplings, beds
        return other;
    }

    BlockPos target() { return target; }

    boolean looting() {
        return state == State.GO || state == State.OPEN || state == State.TAKE || state == State.BREAK || state == State.VAULT || state == State.PICKUP || state == State.TRIAL;
    }

    /** One look for loot in sight (no exploring): goes for it if there is some, else stays idle. */
    void lookOnce(AgentHandle a) {
        search(a);
        if (state == State.EXPLORE || state == State.SEARCH) { onTask(a, "idle"); }
    }

    private void diveFor(AgentHandle a, BlockView w) {
        double d = target.distToCenterSqr(a.x(), a.y() + 1.62, a.z());
        if (d <= 4.0 * 4.0) { a.dive(15); if (a.isNavigating()) a.stopNavigation(); state = State.OPEN; timer = 0; return; }
        if (timer > 20 * 120) { done(a, "couldn't dive down to the shipwreck"); return; }
        boolean over = Math.abs(a.x() - (target.getX() + 0.5)) < 1.5 && Math.abs(a.z() - (target.getZ() + 0.5)) < 1.5;
        if (!over) {
            // The water's surface over the chest: the first air above it.
            int sy = target.getY() + 1;
            while (sy < target.getY() + 40) { BlockState s = w.get(target.getX(), sy, target.getZ()); if (s == null || s.isAir()) break; sy++; }
            status = "swimming over the shipwreck at " + target.toShortString();
            if (!a.isNavigating() && timer % 40 == 1) a.navigate(target.getX(), sy - 1, target.getZ(), 1);
            return;
        }
        status = "diving down to the shipwreck chest at " + target.toShortString();
        a.dive(15);
        if (a.swimToward(target.getX() + 0.5, target.getY() + 1.0, target.getZ() + 0.5)) return;
        // Something in the way down: dig it (deck planks), never the chest itself.
        if (a.isDigging() || timer % 10 != 0) return;
        BlockPos below = BlockPos.containing(a.x(), a.y() - 0.5, a.z());
        BlockState bs = w.get(below.getX(), below.getY(), below.getZ());
        if (bs != null && !bs.isAir() && bs.getFluidState().isEmpty() && !container(bs) && !below.equals(target)) {
            Gear.holdBestFor(a, bs);
            a.dig(below.getX(), below.getY(), below.getZ());
        }
    }

    private void open(AgentHandle a) {
        status = "opening the container at " + target.toShortString();
        if (a.containerOpen()) { state = State.TAKE; timer = 0; return; }
        if (timer == 1 || timer % 30 == 0) {
            a.lookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
            a.useItemOn(target.getX(), target.getY(), target.getZ(), Direction.UP);
        }
        if (timer > 100) {
            if (kind == Kind.ANCIENT_CITY) { done(a, "it wouldn't open"); return; }
            state = State.BREAK; timer = 0; // won't open (blocked lid...): break it instead
        }
    }

    private void take(AgentHandle a) {
        status = "taking the loot";
        if (!a.containerOpen()) { done(a, null); return; }
        if (timer % 2 != 0) return;
        List<ItemStack> slots = a.containerSlots();
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).isEmpty()) continue;
            if (a.usedSlots() >= 36) { a.closeContainer(); done(a, "inventory full"); return; }
            a.quickMoveContainerSlot(i);
            return;
        }
        a.closeContainer();
        opened++;
        if (city != null) {
            int n = cityOpens.merge(city, 1, Integer::sum);
            if (n >= CITY_MAX_OPENS) {
                cityLeft.put(city, System.currentTimeMillis());
                cityOpens.remove(city);
                a.log("opened " + n + " containers in this ancient city: leaving it alone for 15 minutes (the shriekers)");
            }
        }
        done(a, null);
    }

    private void breakIt(AgentHandle a) {
        status = "breaking it open (the loot drops)";
        if (gone(a)) { state = State.PICKUP; timer = 0; opened++; return; }
        if (timer > 20 * 20) { done(a, "couldn't break it"); return; }
        a.lookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        int t = a.dig(target.getX(), target.getY(), target.getZ());
        if (t < 0) { done(a, "couldn't break it"); return; }
        wait = t + 2;
    }

    private void vault(AgentHandle a) {
        status = "unlocking a vault";
        BlockState s = a.openWorld().get(target.getX(), target.getY(), target.getZ());
        if (timer == 1) {
            int k = keySlot(a, s);
            if (k < 0) { done(a, "no key for this vault"); return; }
            if (k >= 9) { a.moveToHotbar(k, 7); k = 7; }
            a.selectHotbar(k);
            a.lookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
            a.useItemOn(target.getX(), target.getY(), target.getZ(), Direction.UP);
        }
        if (timer > 20 * 6) { opened++; state = State.PICKUP; timer = 0; } // it spits the items out over a few seconds
    }

    private static boolean hasKeyFor(AgentHandle a, BlockState vault) { return keySlot(a, vault) >= 0; }

    private static int keySlot(AgentHandle a, BlockState vault) {
        String want = vault.toString().contains("ominous=true") ? "minecraft:ominous_trial_key" : "minecraft:trial_key";
        List<ItemStack> inv = a.inventory();
        for (int i = 0; i < inv.size(); i++) if (Gear.id(inv.get(i)).equals(want)) return i;
        return -1;
    }

    private void pickup(AgentHandle a) {
        status = "picking up the loot";
        if (timer > 20 * 10 || timer > 20 && backToGo && a.itemsNear(6).isEmpty()) { if (backToGo) { backToGo = false; state = State.GO; timer = 0; return; } done(a, null); return; }
        if (a.isNavigating() || timer % 5 != 0) return;
        for (Vec3 v : a.itemsNear(6)) {
            if (GroundDrops.reservedFor(a.name(), v)) continue; // another distributor's pickup
            if (v.distanceToSqr(a.x(), a.y(), a.z()) < 0.8) continue;
            if (target != null && v.distanceToSqr(Vec3.atCenterOf(target)) > 36) continue;
            a.navigate((int) Math.floor(v.x), (int) Math.floor(v.y + 0.2), (int) Math.floor(v.z), timer > 60 ? 1 : 0);
            return;
        }
        if (timer > 20) { if (backToGo) { backToGo = false; state = State.GO; timer = 0; return; } done(a, null); }
    }

    private void done(AgentHandle a, String problem) {
        if (target != null) LOOTED.add(key(a, target));
        if (problem != null) a.log("skipping the container at " + (target == null ? "?" : target.toShortString()) + ": " + problem);
        if (a.isNavigating()) a.stopNavigation();
        if (a.containerOpen()) a.closeContainer();
        a.setSneaking(false);
        state = State.SEARCH;
        timer = 0;
        // End city ships: knock the elytra out of its frame while we're aboard.
        if (kind == Kind.END_CITY) for (Seen s : a.entitiesNear(5)) {
            if (!s.player() && s.name().contains("item_frame")) { a.lookAt(s.pos().x, s.pos().y, s.pos().z); a.attack(s.id()); state = State.PICKUP; target = BlockPos.containing(s.pos()); break; }
        }
    }

    private void stopExploring(AgentHandle a) {
        if (!exploring) return;
        exploring = false;
        explorer.onTask(a, "idle");
        if (a.isNavigating()) a.stopNavigation();
    }

    int opened() { return opened; }

    // ------------------------------------------------------------------ the golem

    /**
     * Iron golem: 100 HP, hits hard, can't reach up 3 blocks. Hit it (it gets angry and comes),
     * back off ~6 blocks so it follows but stays close, pillar up 3 blocks, then hit it from the top
     * until it's dead; come back down and collect the iron.
     */
    static final class Golem {
        private enum G { FIND, HIT, BACK, PILLAR, FIGHT, DOWN, DONE }
        private G g = G.FIND;
        private int id = -1, t, placed, startY, cooldown, downs;
        private double peak;
        String status = "";

        void start(AgentHandle a) { g = G.FIND; t = 0; placed = 0; downs = 0; id = -1; jumps = 0; moves = 0; lastDist = 99; landed = 0; cycles = 0; swings = 0; again = false; a.log("going after an iron golem"); }

        /** True once it's done (dead, or given up). */
        boolean tick(AgentHandle a) {
            t++;
            if (cooldown > 0) cooldown--;
            Seen golem = null;
            for (Seen s : a.entitiesNear(40)) if (!s.player() && s.name().equals("iron_golem") && (id < 0 || s.id() == id)) { golem = s; break; }
            switch (g) {
                case FIND -> {
                    if (golem == null && t < 100) { status = "looking for an iron golem"; return false; }
                    if (golem == null) { status = "no iron golem around"; a.log("no iron golem in sight"); return true; }
                    if (blockSlot(a) < 0) { a.log("needs 3 blocks (cobblestone, dirt...) to pillar up out of the golem's reach"); return true; }
                    id = golem.id();
                    g = G.HIT; t = 0;
                }
                case HIT -> {
                    if (golem == null) { g = G.DONE; return true; }
                    status = String.format("walking up to the golem (%.0f blocks)", golem.distance());
                    if (golem.distance() > 3.0) {
                        if (t % 10 == 1) a.navigate((int) Math.floor(golem.pos().x), (int) Math.floor(golem.pos().y), (int) Math.floor(golem.pos().z), 2);
                        if (t > 20 * 30) { a.log("couldn't get to the golem"); return true; }
                        return false;
                    }
                    if (a.isNavigating()) a.stopNavigation();
                    // Did the last swing land (the server reports the damage)? Then back off.
                    if (a.hitRecently(golem.id(), 1500)) {
                        a.log("hit the golem: backing off to pillar up");
                        backOff(a, golem.pos(), 0);
                        g = G.BACK; t = 0;
                        return false;
                    }
                    if (cooldown > 0) return false;
                    if (++swings > 4) { a.log("its hits on the golem don't land: giving up"); return true; }
                    weapon(a);
                    a.lookAt(golem.pos().x, golem.pos().y + 1.2, golem.pos().z);
                    a.attack(golem.id());
                    cooldown = 13;
                }
                case BACK -> {
                    status = "backing off from the golem";
                    if (a.isNavigating() && t < 40) return false;
                    if (a.isNavigating()) a.stopNavigation();
                    startY = (int) Math.floor(a.y() + 0.01);
                    placed = 0;
                    jumps = 0;
                    if (!openSky(a, 5)) { // leaves or a ledge overhead: a jump would hit its head
                        if (++moves > 4) { a.log("no open sky to pillar up near the golem: giving up"); return true; }
                        backOff(a, golem != null ? golem.pos() : new Vec3(a.x() + 1, a.y(), a.z()), moves);
                        t = 0;
                        return false;
                    }
                    g = G.PILLAR; t = 0;
                }
                case PILLAR -> {
                    status = "pillaring up (" + placed + "/" + PILLAR_HIGH + ")";
                    if (placed >= PILLAR_HIGH && !a.airborne()) {
                        // Really up there? The server may have refused a block (we'd be standing on nothing).
                        int feet = (int) Math.floor(a.y() + 0.01);
                        if (feet < startY + PILLAR_HIGH) { a.log("only " + (feet - startY) + " blocks up (a block didn't take): placing more"); placed = feet - startY; return false; }
                        g = G.FIGHT; t = 0; a.log("on a " + PILLAR_HIGH + "-block pillar: out of the golem's reach"); return false;
                    }
                    if (t > 20 * 20) { a.log("couldn't pillar up"); g = G.FIGHT; return false; }
                    int top = startY + placed; // where the next block goes (our feet now)
                    if (!a.airborne()) {
                        // Jumped last time and never left the ground (something overhead): move and try again.
                        if (++jumps > placed + 4) {
                            if (++moves > 4) { a.log("couldn't jump here to pillar up: giving up"); g = G.DOWN; t = 0; downs = 0; return false; }
                            backOff(a, golem != null ? golem.pos() : new Vec3(a.x() + 1, a.y(), a.z()), moves);
                            g = G.BACK; t = 0;
                            return false;
                        }
                        int slot = blockSlot(a);
                        if (slot < 0) { a.log("ran out of blocks to pillar with"); g = G.FIGHT; return false; }
                        if (slot >= 9) { a.moveToHotbar(slot, 8); slot = 8; }
                        a.selectHotbar(slot);
                        a.lookAt(a.x(), top - 0.5, a.z());
                        peak = a.y();
                        a.jump();
                        return false;
                    }
                    // In the air: once the feet are clear of the block below, put one there.
                    if (a.y() >= top + 1.0 && a.verticalSpeed() > -0.2 && peak < top + 1.0) {
                        int fx = (int) Math.floor(a.x()), fz = (int) Math.floor(a.z());
                        a.placeAgainst(fx, top - 1, fz, Direction.UP);
                        placed++;
                        peak = a.y();
                    }
                }
                case FIGHT -> {
                    if (golem == null) {
                        // Gone from sight: dead only if it was close (else it wandered off or we were moved).
                        a.log(lastDist < 8 && a.y() > startY + 2 ? "the golem is dead" : "lost sight of the golem");
                        g = G.DOWN; t = 0; downs = 0; return false;
                    }
                    lastDist = golem.distance();
                    status = String.format("fighting the golem from the pillar (%.0f blocks)", golem.distance());
                    if (t > 20 * 20 && landed == 0 && cycles < 3) { a.log("the golem isn't coming: climbing down to hit it again"); again = true; g = G.DOWN; t = 0; downs = 0; return false; }
                    if (t > 20 * 120) { a.log("the golem won't come close: giving up"); g = G.DOWN; t = 0; return false; }
                    // Reach is measured to the golem's hitbox (1.4 wide, 2.7 tall), not its middle: from a pillar
                    // its head is close even when its feet are a couple of blocks out.
                    double ex = a.x(), ey = a.y() + 1.62, ez = a.z();
                    double cx = Math.max(golem.pos().x - 0.7, Math.min(ex, golem.pos().x + 0.7));
                    double cy = Math.max(golem.pos().y, Math.min(ey, golem.pos().y + 2.7));
                    double cz = Math.max(golem.pos().z - 0.7, Math.min(ez, golem.pos().z + 0.7));
                    double reach = Math.sqrt((cx - ex) * (cx - ex) + (cy - ey) * (cy - ey) + (cz - ez) * (cz - ez));
                    if (t % 100 == 0) dev.agentnet.AgentNet.LOG.info("[{}] golem {} blocks away, {} to its hitbox", a.name(), String.format("%.1f", golem.distance()), String.format("%.1f", reach));
                    if (cooldown == 0 && reach <= 3.2) { // 4 up: its head is ~2.9 from our eyes when it stands at the pillar
                        weapon(a);
                        a.lookAt(cx, cy, cz);
                        a.attack(golem.id());
                        landed++;
                        cooldown = 13;
                    }
                }
                case DOWN -> {
                    status = "climbing down the pillar";
                    if (downs >= placed || a.airborne() && t < 200) {
                        if (downs >= placed && !a.airborne()) {
                            if (again && cycles < 3) { again = false; cycles++; swings = 0; g = G.HIT; t = 0; a.log("hitting the golem again to make it come"); return false; }
                            g = G.DONE; return true;
                        }
                        return false;
                    }
                    if (a.isDigging()) return false;
                    int fx = (int) Math.floor(a.x()), fz = (int) Math.floor(a.z()), fy = (int) Math.floor(a.y() + 0.01);
                    if (a.dig(fx, fy - 1, fz) >= 0) downs++;
                    else { downs = placed; }
                }
                case DONE -> { return true; }
            }
            return false;
        }

        /** 3 isn't enough in 26.3: tested, the golem hit (and knocked off) an agent 3 blocks up. */
        private static final int PILLAR_HIGH = 4;
        private int jumps, moves, landed, cycles, swings;
        private boolean again;
        private double lastDist = 99;

        /** Walk ~6 blocks away from the golem; each retry turns 60 degrees further. */
        private static void backOff(AgentHandle a, Vec3 from, int turn) {
            double ang = Math.atan2(a.z() - from.z, a.x() - from.x) + turn * Math.PI / 3;
            a.navigate((int) Math.floor(a.x() + Math.cos(ang) * 10), (int) Math.floor(a.y()), (int) Math.floor(a.z() + Math.sin(ang) * 10), 1);
        }

        /** Nothing solid in the h blocks above the head (room to jump and pillar). */
        private static boolean openSky(AgentHandle a, int h) {
            var w = a.openWorld();
            if (w == null) return false;
            int fx = (int) Math.floor(a.x()), fy = (int) Math.floor(a.y() + 0.01), fz = (int) Math.floor(a.z());
            for (int y = fy + 2; y <= fy + 1 + h; y++) {
                BlockState s = w.get(fx, y, fz);
                if (s == null || !s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) return false;
            }
            return true;
        }

        private static int blockSlot(AgentHandle a) {
            List<ItemStack> inv = a.inventory();
            for (String id : new String[]{"minecraft:cobblestone", "minecraft:dirt", "minecraft:netherrack", "minecraft:cobbled_deepslate", "minecraft:stone"}) {
                for (int i = 0; i < inv.size(); i++) if (Gear.id(inv.get(i)).equals(id)) return i;
            }
            return -1;
        }

        private static void weapon(AgentHandle a) {
            List<ItemStack> inv = a.inventory();
            int i = Gear.bestTool(inv, "sword");
            if (i < 0) i = Gear.bestTool(inv, "axe");
            if (i < 0) return;
            if (i >= 9) { a.moveToHotbar(i, 0); i = 0; }
            a.selectHotbar(i);
        }
    }
}
