package dev.agentnet.role;

import java.util.List;

import dev.agentnet.pathing.BlockView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/**
 * The brewer: blaze rods and nether wart (a falloff speedrunner's haul) into potions, like a player.
 * <ol>
 *   <li>a brewing stand (a blaze rod and 3 cobblestone), put down once and left standing (it keeps its fuel);</li>
 *   <li>blaze powder from the rods (the stand's fuel, and the strength ingredient);</li>
 *   <li>glass bottles: sand smelted into glass, 3 glass into 3 bottles;</li>
 *   <li>the bottles filled at water (looking at it, right-click);</li>
 *   <li>nether wart into the water bottles: awkward potions;</li>
 *   <li>blaze powder into the awkward ones: potions of Strength.</li>
 * </ol>
 * Then again, while it has wart and rods, up to a stock of {@link #STOCK} potions (others can ask a distributor for them).
 */
final class BrewerModule implements RoleModule {
    private static final int STOCK = 12;

    private final MakerModule crafter = new MakerModule();
    private final MinerModule miner = new MinerModule();
    private String minerJob, status = "starting", said;
    private int ticks, askAt = -1000, fillAt = -100;
    private BlockPos water;

    @Override public void onStart(AgentHandle a) { a.keepWorld(true); miner.onStart(a); }
    @Override public void onStop(AgentHandle a) { mine(a, null); miner.onStop(a); crafter.onStop(a); a.keepWorld(false); }
    @Override public void onTask(AgentHandle a, String task) { if (task != null && (task.startsWith("make") || task.startsWith("brew"))) crafter.onTask(a, task); }
    @Override public String status() { return "brewer: " + status; }
    @Override public boolean idle() { return status.startsWith("done"); }

    @Override
    public void tick(AgentHandle a) {
        ticks++;
        if (ticks < 40) return;
        if (crafter.busy()) { crafter.tick(a); return; }
        // A smelt that had nothing to burn: logs first.
        if (crafter.outOfFuel) {
            if (MakerModule.hasFuel(a)) { crafter.outOfFuel = false; mine(a, null); }
            else { step(a, "getting logs: the furnace had no fuel"); mine(a, "mine 4 logs"); miner.tick(a); return; }
        }
        int rods = count(a, "minecraft:blaze_rod"), powder = count(a, "minecraft:blaze_powder"), wart = count(a, "minecraft:nether_wart");
        int waterB = potions(a, "water"), awkward = potions(a, "awkward"), strength = potions(a, "strength");
        int bottles = count(a, "minecraft:glass_bottle");
        boolean stand = count(a, "minecraft:brewing_stand") > 0 || standNear(a);

        if (strength >= STOCK) { mine(a, null); step(a, "done: " + strength + " potions of Strength made"); return; }
        if (wart + awkward == 0 && waterB + awkward == 0 && bottles == 0 && rods + powder == 0) { mine(a, null); step(a, "done: nothing left to brew with (needs blaze rods and nether wart)"); return; }

        // 1. The brewing stand.
        if (!stand) {
            if (rods == 0) { step(a, "no blaze rod for a brewing stand"); return; }
            if (stone(a) < 3) { step(a, "mining cobblestone for a brewing stand"); mine(a, "mine 3 stone"); miner.tick(a); return; }
            mine(a, null);
            ask(a, "make brewing_stand 1", "making a brewing stand");
            return;
        }
        // 2. Blaze powder: 2 at hand (the stand's fuel and a strength brew), a rod at a time.
        if (powder < 2 && rods > 0) { ask(a, "make blaze_powder " + Math.min(rods, 2), "making blaze powder"); return; }
        // 3. Bottles: three to brew at once.
        int want = 3 - waterB - awkward;
        if (want > 0 && bottles < want) {
            int glass = count(a, "minecraft:glass");
            if (glass >= 3) { mine(a, null); ask(a, "make glass_bottle 1", "making glass bottles"); return; }
            int sand = count(a, "minecraft:sand") + count(a, "minecraft:red_sand");
            if (glass + sand >= 3) {
                if (!MakerModule.canHaveFurnace(a)) { step(a, "mining cobblestone for a furnace (glass)"); mine(a, "mine 8 stone"); miner.tick(a); return; }
                mine(a, null);
                ask(a, "make glass " + (3 - glass), "smelting sand into glass");
                return;
            }
            step(a, "digging sand for glass bottles");
            mine(a, "mine " + (3 - glass) + " sand");
            miner.tick(a);
            return;
        }
        // 4. Water in the bottles.
        if (bottles > 0 && waterB + awkward < 3) { fill(a); return; }
        mine(a, null);
        // 5. Nether wart into the water: awkward potions.
        if (waterB > 0 && wart > 0) { ask(a, "brew nether_wart", "brewing awkward potions (nether wart)"); return; }
        // 6. Blaze powder into the awkward ones: Strength.
        if (awkward > 0 && powder > 0) { ask(a, "brew blaze_powder", "brewing potions of Strength (blaze powder)"); return; }
        step(a, strength > 0 ? "done: " + strength + " potions of Strength (out of wart or rods for more)" : "waiting: needs nether wart for awkward potions");
    }

    /** One job for the crafter, at most every 10 s (it says itself why one didn't work). */
    private void ask(AgentHandle a, String task, String what) {
        step(a, what);
        if (ticks - askAt < 200) return;
        askAt = ticks;
        crafter.onTask(a, task);
    }

    private void step(AgentHandle a, String s) {
        status = s;
        if (!s.equals(said)) { said = s; a.log("[brewer] " + s); }
    }

    /** Glass bottles filled at the nearest water: up to it, looking at it, right-click (one bottle each time). */
    private void fill(AgentHandle a) {
        if (!(a.openWorld() instanceof BlockView w)) return;
        int fx = (int) Math.floor(a.x()), fz = (int) Math.floor(a.z());
        if (water == null || ticks % 200 == 0) {
            water = null;
            double bd = Double.MAX_VALUE;
            for (BlockPos p : w.findAll(fx, fz, 48, s -> s.getFluidState().isSource() && s.getFluidState().is(net.minecraft.tags.FluidTags.WATER), 64)) {
                var over = w.get(p.getX(), p.getY() + 1, p.getZ());
                if (over == null || !over.isAir()) continue; // open water, so it can look down at it
                double d = p.distToCenterSqr(a.x(), a.y(), a.z());
                if (d < bd) { bd = d; water = p; }
            }
            if (water == null) { step(a, "looking for water to fill its bottles"); if (!a.isNavigating() && ticks % 100 == 0) a.navigate(fx + 40, (int) a.y(), fz, 8); return; }
        }
        double d = Math.sqrt(water.distToCenterSqr(a.x(), a.y() + 1.62, a.z()));
        step(a, "filling glass bottles with water");
        if (d > 3.5) { if (!a.isNavigating() || ticks % 20 == 0) a.navigate(water.getX(), water.getY() + 1, water.getZ(), 2); return; }
        if (a.isNavigating()) a.stopNavigation();
        if (ticks - fillAt < 6) return;
        fillAt = ticks;
        List<ItemStack> inv = a.inventory();
        int s = -1;
        for (int i = 0; i < inv.size() && s < 0; i++) if (Gear.id(inv.get(i)).equals("minecraft:glass_bottle")) s = i;
        if (s < 0) return;
        int hb = s < 9 ? s : 7;
        if (s >= 9) a.moveToHotbar(s, hb);
        a.selectHotbar(hb);
        a.lookAt(water.getX() + 0.5, water.getY() + 0.9, water.getZ() + 0.5);
        a.useItem();
    }

    private boolean standNear(AgentHandle a) {
        if (!(a.openWorld() instanceof BlockView w)) return false;
        return !w.findAll((int) Math.floor(a.x()), (int) Math.floor(a.z()), 24, s -> s.getBlock() == Blocks.BREWING_STAND, 1).isEmpty();
    }

    private void mine(AgentHandle a, String job) {
        if (job == null ? minerJob == null : job.equals(minerJob)) return;
        minerJob = job;
        miner.onTask(a, job == null ? "idle" : job);
    }

    private static int stone(AgentHandle a) {
        return count(a, "minecraft:cobblestone") + count(a, "minecraft:cobbled_deepslate") + count(a, "minecraft:blackstone");
    }

    private static int count(AgentHandle a, String id) {
        int n = 0;
        for (ItemStack s : a.inventory()) if (!s.isEmpty() && Gear.id(s).equals(id)) n += s.getCount();
        return n;
    }

    /** Drinkable potions of this kind it carries ("water", "awkward", "strength" or "long_strength"/"strong_strength"). */
    static int potions(AgentHandle a, String kind) {
        int n = 0;
        for (ItemStack s : a.inventory()) {
            if (s.isEmpty() || !Gear.id(s).equals("minecraft:potion")) continue;
            var c = s.get(DataComponents.POTION_CONTENTS);
            if (c == null || c.potion().isEmpty()) continue;
            String id = c.potion().get().unwrapKey().map(k -> k.identifier().getPath()).orElse("");
            if (id.equals(kind) || kind.equals("strength") && id.endsWith("strength")) n += s.getCount();
        }
        return n;
    }
}
