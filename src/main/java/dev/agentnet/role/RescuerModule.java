package dev.agentnet.role;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.agentnet.bus.AgentBus;
import net.minecraft.world.item.ItemStack;

/**
 * Rescuer: brings a pickaxe to agents stranded deep underground without one (a broken pickaxe at the bottom of a
 * mine, a fall into a cave). It keeps a few spare pickaxes (crafts stone ones from its own cobblestone and sticks,
 * or asks a distributor), goes down to the stranded agent (digging its way when there is no path: a trip that keeps
 * failing makes it tunnel toward the goal), and drops a pickaxe at its feet. The stranded agent then digs itself out.
 * Between rescues it waits at the base.
 */
final class RescuerModule implements RoleModule {
    private static final int SPARE = 3, UNDERGROUND_Y = 50;
    private final MakerModule crafter = new MakerModule();
    private final Map<String, Long> helped = new HashMap<>();
    private String patient;
    private int ticks, navTicks, rescueTicks;
    private long askedAt, craftAt;
    private String status = "waiting for someone to rescue";

    @Override public void onStart(AgentHandle a) { a.keepWorld(true); }
    @Override public void onStop(AgentHandle a) { mine(a, null); a.keepWorld(false); if (a.isNavigating()) a.stopNavigation(); }
    @Override public String status() { return status; }
    @Override public boolean idle() { return patient == null; }

    private final MinerModule miner = new MinerModule();
    private String minerJob;
    private int jobAt;

    private void mine(AgentHandle a, String job) {
        if (job == null ? minerJob == null : job.equals(minerJob)) return;
        minerJob = job;
        jobAt = ticks;
        miner.onTask(a, job == null ? "idle" : job);
    }

    /**
     * Short of spare pickaxes with nobody to rescue: it gets the wood and the cobblestone itself and makes them. Asking
     * alone left it standing at the base with one dirt and "0 spare pickaxes" (run 1118d: no distributor had any), and
     * with fewer than two it rescues nobody. True while it is busy with that.
     */
    private boolean stock(AgentHandle a) {
        int have = pickaxes(a);
        if (have >= SPARE) { mine(a, null); return false; }
        int wood = 0, stone = 0;
        for (ItemStack s : a.inventory()) {
            String id = Gear.id(s);
            if (id.endsWith("_planks")) wood += s.getCount();
            else if ((id.endsWith("_log") || id.endsWith("_stem")) && !id.contains("stripped")) wood += 4 * s.getCount();
            else if (id.equals("minecraft:stick")) wood += s.getCount() / 2;
            else if (id.equals("minecraft:cobblestone") || id.equals("minecraft:cobbled_deepslate")) stone += s.getCount();
        }
        // A table (4 planks), sticks, and a wooden pickaxe to get at the stone: 3 logs; then 3 cobblestone a pickaxe.
        String job = wood < 6 ? "mine 3 logs" : stone < 3 ? "mine " + 3 * (SPARE - have) + " stone" : null;
        if (job == null) {
            mine(a, null);
            if (ticks % 60 == 0 && crafter.queueTool(a, "minecraft:stone_pickaxe")) a.log("making a spare stone pickaxe (" + have + " of " + SPARE + ")");
            status = "making spare pickaxes (" + have + " of " + SPARE + ")";
            return true;
        }
        // Again only once the miner says it is done: "idle" it also is while it waits for a distributor or makes its own
        // pickaxe, and starting over every 5 s kept it asking for one, 42 times in five minutes.
        if (minerJob != null && miner.idle() && miner.status().startsWith("done") && ticks - jobAt > 100) minerJob = null;
        if (minerJob == null) a.log("§7" + have + " of " + SPARE + " spare pickaxes: getting " + (job.contains("logs") ? "wood" : "cobblestone") + " for more");
        mine(a, job);
        miner.tick(a);
        status = "getting " + (job.contains("logs") ? "wood" : "cobblestone") + " for spare pickaxes (" + have + " of " + SPARE + ")";
        return true;
    }

    private static int pickaxes(AgentHandle a) {
        int n = 0;
        for (ItemStack s : a.inventory()) if (Gear.id(s).endsWith("_pickaxe")) n++;
        return n;
    }

    /** Deep underground with no pickaxe: it can't dig itself out (a fist takes 7 s a stone block, with no drops). */
    private static boolean stranded(AgentHandle h) {
        if (!h.isOnline() || h.role() == Role.RESCUER || h.airborne()) return false;
        if (h.dimension() == null || !h.dimension().endsWith("overworld")) return false;
        if (pickaxes(h) > 0) return false;
        // A speedrunner without one, anywhere, until the dragon is dead: it comes first (run 1118d: the main died, lost its
        // pickaxe, and spent its time hunting logs in treeless snowy mountains while the rescuer sat on three spares).
        if (runner(h)) return true;
        // Deep down, or under rock at any height (a tunnel into a mountain: run 1118d's spawn is mountains at y 90-110).
        return h.y() <= UNDERGROUND_Y || roofed(h);
    }

    private static boolean runner(AgentHandle h) {
        return (h.role() == Role.SPEEDRUNNER || h.role() == Role.FALLOFF) && SpeedrunnerModule.priorityRunner() != null;
    }

    /** Solid rock over its head (not a tree): it is in a tunnel or a cave, whatever the height. */
    private static boolean roofed(AgentHandle h) {
        var w = h.openWorld();
        if (w == null) return false;
        int x = (int) Math.floor(h.x()), y = (int) Math.floor(h.y() + 0.01), z = (int) Math.floor(h.z());
        for (int dy = 2; dy <= 32; dy++) {
            var s = w.get(x, y + dy, z);
            if (s == null) return false;
            if (s.isAir() || !s.getFluidState().isEmpty() || s.is(net.minecraft.tags.BlockTags.LEAVES) || s.is(net.minecraft.tags.BlockTags.LOGS)) continue;
            return s.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD) || s.is(net.minecraft.tags.BlockTags.DIRT);
        }
        return false;
    }

    @Override
    public void tick(AgentHandle a) {
        ticks++;
        if (crafter.busy()) { crafter.tick(a); return; }
        if (patient == null) {
            if (ticks % 100 == 0) restock(a);
            if (ticks % 40 == 0) pick(a);
            if (patient != null) { mine(a, null); return; }
            if (stock(a)) return;
            status = "waiting for someone to rescue (" + pickaxes(a) + " spare pickaxes)"; HomeBase.goHome(a, ticks);
            return;
        }
        AgentHandle p = GathererModule.find(patient);
        if (p == null || !stranded(p) || ++rescueTicks > 20 * 600) {
            a.log(p != null && pickaxes(p) > 0 ? "§a" + patient + " has a pickaxe now" : "gave up on rescuing " + patient);
            helped.put(patient, System.currentTimeMillis());
            patient = null;
            if (a.isNavigating()) a.stopNavigation();
            return;
        }
        double d = Math.sqrt(AgentBus.distSq(a, p));
        status = String.format("rescuing %s (%.0f blocks away, y=%.0f)", patient, d, p.y());
        if (d > 2.5) {
            if (--navTicks <= 0 && !a.isNavigating()) { navTicks = 20; a.navigate((int) Math.floor(p.x()), (int) Math.floor(p.y()), (int) Math.floor(p.z()), 1); }
            return;
        }
        if (a.isNavigating()) a.stopNavigation();
        List<ItemStack> inv = a.inventory();
        for (int i = 0; i < inv.size(); i++) {
            if (!Gear.id(inv.get(i)).endsWith("_pickaxe")) continue;
            a.lookAt(p.x(), p.y() + 0.5, p.z());
            a.dropStack(i);
            a.log("§adropped a pickaxe for " + patient + " at y=" + (int) p.y());
            helped.put(patient, System.currentTimeMillis() - 4 * 60_000); // check again in a minute: did it pick it up?
            patient = null;
            return;
        }
        a.log("§eno pickaxe left to give " + patient);
        patient = null;
    }

    private void pick(AgentHandle a) {
        if (pickaxes(a) < 2) return; // its own and one to give
        long now = System.currentTimeMillis();
        AgentHandle best = null;
        double bd = 600 * 600;
        for (AgentHandle h : AgentBus.agents.get()) {
            if (h == a || !stranded(h) || !AgentHandle.sameDim(h.dimension(), a.dimension())) continue;
            Long t = helped.get(h.name());
            if (t != null && now - t < (runner(h) ? 60_000 : 5 * 60_000)) continue;
            double d = AgentBus.distSq(a, h);
            // The main speedrunner first, then its helpers, then everyone else (nearest first within each).
            if (runner(h)) d -= h.name().equalsIgnoreCase(SpeedrunnerModule.priorityRunner()) ? 2e6 : 1e6;
            if (d < bd) { bd = d; best = h; }
        }
        if (best == null) return;
        patient = best.name();
        rescueTicks = 0;
        navTicks = 0;
        a.log(runner(best) ? "§e" + patient + " (speedrun) has no pickaxe: bringing it one first" : "§e" + patient + " is stuck at y=" + (int) best.y() + " without a pickaxe: going to bring it one");
    }

    /** Spare pickaxes: crafted from its own cobblestone and sticks, or asked for. */
    private void restock(AgentHandle a) {
        if (pickaxes(a) >= SPARE) return;
        long now = System.currentTimeMillis();
        if (now - craftAt > 60_000) { craftAt = now; if (crafter.queueTool(a, "minecraft:stone_pickaxe")) { a.log("making a spare stone pickaxe"); return; } }
        if (now - askedAt > 3 * 60_000) {
            askedAt = now;
            BuilderModule.requestMaterial(a, "minecraft:stone_pickaxe", SPARE - pickaxes(a));
            BuilderModule.requestMaterial(a, "minecraft:cobblestone", 16);
        }
    }
}
