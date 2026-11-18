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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Hunter-gatherer: food from the land around it, in this order:
 * <ol>
 *   <li>passive animals (cows, pigs, sheep, chickens, rabbits, mooshrooms), always leaving
 *       two of each kind in sight so they can breed back;</li>
 *   <li>ripe sweet berry bushes (picked, the bush stays);</li>
 *   <li>in villages only (a bell or a villager close by): hay bales and ripe crops, which it
 *       takes without replanting. Our own registered farms are never touched;</li>
 *   <li>nothing of that within reach: it explores, which loads new land to search.</li>
 * </ol>
 * The drops are picked up; at half a full inventory a distributor collects (as for any gatherer).
 */
final class HunterModule extends GathererModule {
    private static final Set<String> GAME = Set.of("cow", "pig", "sheep", "chicken", "rabbit", "mooshroom");
    private static final int SEARCH = 40, KEEP_PER_KIND = 2;
    private static final double HIT = 3.0;

    private enum Job { NONE, HUNT, BERRIES, HAY, CROP, PICKUP, EXPLORE, STORE }

    /** Carries its meat to the base chests itself (waiting for a distributor took far too long). */
    @Override
    protected boolean carriesHome(AgentHandle a) {
        Bases.Base b = Bases.crew(a.server(), a.dimension());
        return b != null && b.built() && !b.chests().isEmpty() && b.hub().distToCenterSqr(a.x(), a.y(), a.z()) < 400 * 400;
    }

    private BlockPos chest;
    private final java.util.Set<BlockPos> fullChests = new java.util.HashSet<>();
    private int storeFailedAt = -100_000;

    private boolean wantStore(AgentHandle a) {
        if (!carriesHome(a) || ticks - storeFailedAt < 20 * 120) return false;
        int meat = 0;
        for (ItemStack s : a.inventory()) if (Gear.isFood(s)) meat += s.getCount();
        return a.inventoryFill() > 0.4 || meat >= 48;
    }

    private void store(AgentHandle a) {
        Bases.Base b = Bases.crew(a.server(), a.dimension());
        if (b == null) { job = Job.NONE; return; }
        if (chest == null) {
            for (BlockPos c : b.chests()) {
                if (fullChests.contains(c)) continue;
                List<ItemStack> l = ChestMemory.CONTENTS.get(c);
                if (l == null || l.stream().anyMatch(ItemStack::isEmpty)) { chest = c; break; }
            }
            if (chest == null) { fullChests.clear(); storeFailedAt = ticks; a.log("every base chest is full: keeping its meat"); job = Job.NONE; return; }
        }
        status = "bringing its meat to the base (" + chest.toShortString() + ")";
        if (jobTicks > 20 * 120) {
            if (a.containerOpen()) a.closeContainer();
            storeFailedAt = ticks; chest = null; job = Job.NONE;
            a.log("couldn't get its meat into the base chests: back to hunting");
            return;
        }
        double d = chest.distToCenterSqr(a.x(), a.y() + 1.6, a.z());
        if (d > 4.0 * 4.0) {
            if (--navTicks <= 0 && !a.isNavigating()) { navTicks = 40; a.navigate(chest.getX(), chest.getY(), chest.getZ(), 2); }
            return;
        }
        if (a.isNavigating()) a.stopNavigation();
        if (!a.containerOpen()) {
            if (jobTicks % 20 == 0) { a.lookAt(chest.getX() + 0.5, chest.getY() + 0.5, chest.getZ() + 0.5); a.useItemOn(chest.getX(), chest.getY(), chest.getZ(), Direction.UP); }
            return;
        }
        if (jobTicks % 2 != 0) return;
        Set<Integer> keep = keepSlots(a);
        List<ItemStack> inv = a.inventory();
        boolean full = a.containerSlots().stream().noneMatch(ItemStack::isEmpty);
        if (!full) for (int i = 0; i < inv.size(); i++) {
            if (inv.get(i).isEmpty() || keep.contains(i)) continue;
            a.quickMoveToContainer(i);
            return;
        }
        ChestMemory.CONTENTS.put(chest, a.containerSlots().stream().map(ItemStack::copy).toList());
        a.closeContainer();
        if (full) { fullChests.add(chest); chest = null; jobTicks = 0; return; } // the next chest
        a.log("put its meat in the base chest at " + chest.toShortString());
        chest = null;
        job = Job.NONE;
    }

    /** No sword or axe: one from the crew (kills in 2-3 hits instead of 5-10 with a fist). */
    private void askWeapon(AgentHandle a) {
        List<ItemStack> inv = a.inventory();
        if (Gear.bestTool(inv, "sword") < 0 && Gear.bestTool(inv, "axe") < 0) requestTool(a, "weapon:sword:0");
    }

    private Job job = Job.NONE;
    private int ticks, jobTicks, cooldown, navTicks, killed, picked;
    private int prey = -1;
    private BlockPos spot;
    private Vec3 dropsAt;
    private String status = "";
    private final ExplorerModule explorer = new ExplorerModule();
    private boolean exploring;
    private final Map<BlockPos, Integer> gaveUp = new HashMap<>();

    @Override
    public void onStart(AgentHandle a) { a.keepWorld(true); }

    @Override
    public void onStop(AgentHandle a) {
        if (exploring) explorer.onStop(a);
        exploring = false;
        a.keepWorld(false);
        if (a.isNavigating()) a.stopNavigation();
    }

    @Override
    public void onTask(AgentHandle a, String task) {
        if (task.equals("idle")) { stopExploring(a); job = Job.NONE; }
    }

    @Override
    public String status() { return status; }

    @Override
    protected Set<Integer> keepSlots(AgentHandle a) {
        // Keep the weapon and one food stack; everything else (meat, berries, wheat...) goes to the base.
        Set<Integer> keep = new java.util.HashSet<>();
        List<ItemStack> inv = a.inventory();
        for (String t : new String[]{"sword", "axe"}) { int i = Gear.bestTool(inv, t); if (i >= 0) keep.add(i); }
        keepFood(inv, keep, Set.of());
        return keep;
    }

    @Override
    protected void work(AgentHandle a) {
        ticks++;
        jobTicks++;
        if (cooldown > 0) cooldown--;
        if (a.isDigging()) return;
        switch (job) {
            case NONE -> { if (ticks % 10 == 0) { if (ticks % 200 == 0) askWeapon(a); if (wantStore(a)) { start(a, Job.STORE); return; } choose(a); } }
            case STORE -> store(a);
            case HUNT -> hunt(a);
            case BERRIES, HAY, CROP -> harvest(a);
            case PICKUP -> pickup(a);
            case EXPLORE -> {
                if (explorer.status().contains("is explored")) {
                    // All mapped already: animals still wander in and out of it. A walk ~50 blocks some way, then look again.
                    if (!a.isNavigating()) {
                        double ang = Math.random() * Math.PI * 2;
                        Bases.Base home = Bases.crew(a.server(), a.dimension());
                        if (home != null && Math.hypot(home.origin().getX() - a.x(), home.origin().getZ() - a.z()) > 100)
                            ang = Math.atan2(home.origin().getZ() - a.z(), home.origin().getX() - a.x()) + (Math.random() - 0.5); // drifting off: back toward home
                        a.navigate((int) (a.x() + Math.cos(ang) * 50), (int) a.y(), (int) (a.z() + Math.sin(ang) * 50), 10);
                    }
                    status = "nothing to eat around: walking about for animals";
                } else {
                    explorer.tick(a);
                    status = "nothing to eat around: exploring (" + explorer.status() + ")";
                }
                if (ticks % 100 == 0) { // new land loaded: anything there?
                    job = Job.NONE;
                    choose(a);
                    if (job == Job.NONE) job = Job.EXPLORE;
                }
            }
        }
    }

    // ------------------------------------------------------------------ choosing

    private void choose(AgentHandle a) {
        long now = ticks;
        gaveUp.values().removeIf(t -> now - t > 20 * 120);
        // 1. Animals: the nearest one of a kind there are more than two of.
        List<Seen> seen = a.entitiesNear(SEARCH);
        Map<String, Integer> kinds = new HashMap<>();
        for (Seen s : seen) if (!s.player() && GAME.contains(s.name())) kinds.merge(s.name(), 1, Integer::sum);
        Seen best = null;
        for (Seen s : seen) {
            if (s.player() || !GAME.contains(s.name()) || kinds.get(s.name()) <= KEEP_PER_KIND) continue;
            if (best == null || s.distance() < best.distance()) best = s;
        }
        if (best != null) {
            prey = best.id();
            start(a, Job.HUNT);
            a.log("hunting a " + best.name() + String.format(" (%.0f blocks)", best.distance()));
            return;
        }
        BlockSource w = a.openWorld();
        if (false && w instanceof BlockView view) { // animals only: gathering plants wasn't worth the trips
            int fx = (int) Math.floor(a.x()), fz = (int) Math.floor(a.z());
            // 2. Ripe berries (age 2 or 3).
            BlockPos b = nearest(a, view.findAll(fx, fz, SEARCH, s -> s.getBlock() == Blocks.SWEET_BERRY_BUSH && s.getValue(SweetBerryBushBlock.AGE) >= 2, 64));
            if (b != null) { spot = b; start(a, Job.BERRIES); a.log("picking sweet berries at " + b.toShortString()); return; }
            // 3./4. Villages: hay bales, then ripe crops (never our own farms).
            if (inVillage(a, view, fx, fz)) {
                BlockPos h = nearest(a, view.findAll(fx, fz, SEARCH, s -> s.getBlock() == Blocks.HAY_BLOCK, 64));
                if (h != null) { spot = h; start(a, Job.HAY); a.log("taking a hay bale from the village at " + h.toShortString()); return; }
                BlockPos c = nearest(a, view.findAll(fx, fz, SEARCH, HunterModule::ripeCrop, 128));
                if (c != null) { spot = c; start(a, Job.CROP); a.log("taking village crops at " + c.toShortString()); return; }
            }
        }
        // 5. Nothing: explore (loads new land), looking again every few seconds.
        if (!exploring) {
            exploring = true;
            explorer.onStart(a);
            explorer.onTask(a, "explore 128"); // near home: game is all around, running 500 blocks off wasted the day
            a.log("nothing to eat in sight: exploring");
        }
        job = Job.EXPLORE;
    }

    private void start(AgentHandle a, Job j) {
        stopExploring(a);
        job = j;
        jobTicks = 0;
        navTicks = 0;
    }

    private void stopExploring(AgentHandle a) {
        if (!exploring) return;
        exploring = false;
        explorer.onTask(a, "idle");
        if (a.isNavigating()) a.stopNavigation();
    }

    private BlockPos nearest(AgentHandle a, List<BlockPos> list) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos p : list) {
            if (gaveUp.containsKey(p) || ownFarm(a, p)) continue;
            double d = p.distToCenterSqr(a.x(), a.y(), a.z());
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    private static boolean ripeCrop(BlockState s) {
        Block b = s.getBlock();
        return b instanceof CropBlock crop && crop.isMaxAge(s);
    }

    private static boolean ownFarm(AgentHandle a, BlockPos p) {
        for (var f : dev.agentnet.map.Farms.list(a.server())) {
            if (dev.agentnet.role.AgentHandle.sameDim(f.dimension(), a.dimension()) && f.contains(p.getX() + 0.5, p.getY(), p.getZ() + 0.5)) return true;
        }
        return false;
    }

    /** A bell or a villager within 48 blocks. */
    private static boolean inVillage(AgentHandle a, BlockView view, int fx, int fz) {
        for (Seen s : a.entitiesNear(48)) if (!s.player() && s.name().equals("villager")) return true;
        return !view.findAll(fx, fz, 48, s -> s.getBlock() == Blocks.BELL, 1).isEmpty();
    }

    // ------------------------------------------------------------------ hunting

    private void hunt(AgentHandle a) {
        Seen target = null;
        for (Seen s : a.entitiesNear(SEARCH + 8)) if (s.id() == prey) { target = s; break; }
        if (target == null) {
            // Gone: killed (drops on the ground) or wandered off.
            if (dropsAt != null) { start(a, Job.PICKUP); jobTicks = 0; return; }
            if (wantStore(a)) { start(a, Job.STORE); return; }
            job = Job.NONE;
            return;
        }
        if (jobTicks > 20 * 60) { a.log("couldn't catch the " + target.name()); job = Job.NONE; if (a.isNavigating()) a.stopNavigation(); return; }
        dropsAt = target.pos();
        status = String.format("hunting a %s (%.0f blocks), %d killed", target.name(), target.distance(), killed);
        if (target.distance() > HIT) {
            // Close and level with it: straight at it (re-planning every half second never caught a wandering pig).
            BlockSource sw = a.openWorld();
            if (target.distance() < 7 && Math.abs(target.pos().y - a.y()) < 0.6 && sw != null
                    && Sight.clear(sw, a.x(), a.y() + 0.5, a.z(), target.pos().x, a.y() + 0.5, target.pos().z)
                    && Sight.clear(sw, a.x(), a.y() + 1.5, a.z(), target.pos().x, a.y() + 1.5, target.pos().z)) {
                if (a.isNavigating()) a.stopNavigation();
                a.stepToward(target.pos().x, target.pos().z);
                return;
            }
            if (--navTicks <= 0 || !a.isNavigating()) {
                navTicks = 30;
                a.navigate((int) Math.floor(target.pos().x), (int) Math.floor(target.pos().y), (int) Math.floor(target.pos().z), 1);
            }
            return;
        }
        if (a.isNavigating()) a.stopNavigation();
        if (cooldown > 0) return;
        holdWeapon(a);
        a.lookAt(target.pos().x, target.pos().y + 0.5, target.pos().z);
        a.attack(target.id());
        cooldown = 13; // a sword's full swing
        // Killed? It disappears from the list next tick; the hunt() above then picks up the drops.
        killedCheck = target.id();
    }

    private int killedCheck = -1;

    private void pickup(AgentHandle a) {
        if (killedCheck >= 0) { killed++; killedCheck = -1; }
        status = "picking up the drops";
        if (jobTicks == 1 && dropsAt != null) a.navigate((int) Math.floor(dropsAt.x), (int) Math.floor(dropsAt.y), (int) Math.floor(dropsAt.z), 0);
        if (jobTicks > 20 * 6 || jobTicks > 20 && !a.isNavigating()) {
            // Anything left right around? One more step onto it.
            for (Vec3 v : a.itemsNear(4)) {
                if (GroundDrops.reservedFor(a.name(), v)) continue; // another distributor's pickup
                if (v.distanceToSqr(a.x(), a.y(), a.z()) > 0.8 && jobTicks < 20 * 8) {
                    if (!a.isNavigating()) a.navigate((int) Math.floor(v.x), (int) Math.floor(v.y + 0.2), (int) Math.floor(v.z), 0);
                    return;
                }
            }
            dropsAt = null;
            job = Job.NONE;
        }
    }

    private static void holdWeapon(AgentHandle a) {
        List<ItemStack> inv = a.inventory();
        int i = Gear.bestTool(inv, "sword");
        if (i < 0) i = Gear.bestTool(inv, "axe");
        if (i < 0) return;
        if (i >= 9) { a.moveToHotbar(i, 0); i = 0; }
        if (a.selectedSlot() != i) a.selectHotbar(i);
    }

    // ------------------------------------------------------------------ berries, hay, crops

    private void harvest(AgentHandle a) {
        BlockSource w = a.openWorld();
        BlockState s = w == null ? null : w.get(spot.getX(), spot.getY(), spot.getZ());
        boolean still = s != null && switch (job) {
            case BERRIES -> s.getBlock() == Blocks.SWEET_BERRY_BUSH && s.getValue(SweetBerryBushBlock.AGE) >= 2;
            case HAY -> s.getBlock() == Blocks.HAY_BLOCK;
            default -> ripeCrop(s);
        };
        if (!still) { // done (or someone else got it): pick up what fell, then the next one
            if (job != Job.BERRIES) picked++;
            dropsAt = Vec3.atCenterOf(spot);
            start(a, Job.PICKUP);
            return;
        }
        status = (job == Job.BERRIES ? "picking berries" : job == Job.HAY ? "taking hay" : "taking crops") + " at " + spot.toShortString();
        double dx = spot.getX() + 0.5 - a.x(), dy = spot.getY() + 0.5 - (a.y() + 1.62), dz = spot.getZ() + 0.5 - a.z();
        if (dx * dx + dy * dy + dz * dz > 4.4 * 4.4) {
            if (jobTicks > 20 * 40) { gaveUp.put(spot, ticks); a.log("couldn't get to " + spot.toShortString()); job = Job.NONE; return; }
            if (--navTicks <= 0 && !a.isNavigating()) {
                navTicks = 40;
                a.navigate(spot.getX(), spot.getY(), spot.getZ(), job == Job.BERRIES ? 2 : 3); // not into the bush (it hurts); crops: anywhere in reach (water channels)
            }
            return;
        }
        if (a.isNavigating()) a.stopNavigation();
        if (cooldown > 0) return;
        a.lookAt(spot.getX() + 0.5, spot.getY() + 0.5, spot.getZ() + 0.5);
        if (job == Job.BERRIES) {
            a.useItemOn(spot.getX(), spot.getY(), spot.getZ(), Direction.UP); // right-click picks them, the bush stays
            picked++;
            cooldown = 10;
        } else {
            if (job == Job.HAY) holdHoe(a);
            int t = a.dig(spot.getX(), spot.getY(), spot.getZ());
            if (t < 0) { gaveUp.put(spot, ticks); job = Job.NONE; return; }
            cooldown = t + 2;
        }
    }

    private static void holdHoe(AgentHandle a) {
        int i = Gear.bestTool(a.inventory(), "hoe");
        if (i < 0) return;
        if (i >= 9) { a.moveToHotbar(i, 1); i = 1; }
        a.selectHotbar(i);
    }
}
