package dev.agentnet.role;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.agentnet.bus.AgentBus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The crew's nether portal, for the autopilot: a 4x5 obsidian frame (10 blocks, no corners) put up
 * by the builders just outside the base's east wall, then lit with flint and steel. The autopilot
 * calls {@link #tick} every round once the crew has a finished base; it says what it still needs
 * ({@link #needs}): obsidian (a miner with a diamond pickaxe) and flint for the flint and steel.
 */
public final class PortalWork {
    private PortalWork() {}

    static final String FILE = "agentnet_portal.litematic";
    private static BlockPos origin;       // bottom-left of the frame
    private static String server, dimension;
    private static boolean lit;
    private static long warnedAt;
    public static String status = "not started";

    /** Lit already (or there's none to make yet). */
    public static boolean lit() { return lit; }

    /** The lit portal's bottom inside block (where to stand to go through), or null. */
    public static BlockPos inside() { return lit && origin != null ? origin.offset(1, 1, 0) : null; }

    /** What the portal is short of, as miner tasks ("mine obsidian 10", "mine gravel 8"). */
    public static List<String> needs(List<? extends AgentHandle> crew) {
        if (origin == null || lit) return List.of();
        int obsidian = 0, flint = 0, fas = 0;
        for (AgentHandle h : crew) for (ItemStack s : h.inventory()) {
            if (s.getItem() == Items.OBSIDIAN) obsidian += s.getCount();
            if (s.getItem() == Items.FLINT) flint += s.getCount();
            if (s.getItem() == Items.FLINT_AND_STEEL) fas++;
        }
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        int placed = placed(crew);
        if (obsidian + placed < 10) out.add("mine obsidian " + (10 - obsidian - placed));
        if (flint == 0 && fas == 0) out.add("mine gravel 12");
        return out;
    }

    /** One autopilot round: plan the frame, hand it to the builders, light it when it stands. */
    public static void tick(List<? extends AgentHandle> crew) {
        if (lit || crew.isEmpty()) return;
        AgentHandle any = crew.get(0);
        Bases.Base base = null;
        for (AgentHandle h : crew) { base = Bases.builtNear(h.server(), h.dimension(), h.x(), h.z()); if (base != null) { any = h; break; } }
        if (base == null || !base.dimension().endsWith("overworld")) { status = "waiting for a finished base"; return; }
        if (origin == null || !any.server().equals(server)) plan(any, base);
        if (origin == null) return;
        int placed = placed(crew);
        if (placed < 10) {
            status = "frame " + placed + "/10 obsidian";
            String task = "build " + FILE + " " + origin.getX() + " " + origin.getY() + " " + origin.getZ();
            boolean onIt = false;
            for (AgentHandle h : crew) if (h.role() == Role.BUILDER && task.equals(h.task())) onIt = true;
            if (!onIt) for (AgentHandle h : crew) {
                if (h.role() != Role.BUILDER) continue;
                String st = h.moduleStatus();
                if (h.task().startsWith("build agentnet_base") && st != null && !st.startsWith("done")) continue; // still on the base
                h.assignTask(task);
                h.log("§aputting up the nether portal frame at " + origin.toShortString());
                break;
            }
            return;
        }
        // The frame stands: light it. Whoever has flint and steel goes over; else the builder asks for one.
        BlockPos fire = origin.offset(1, 1, 0);
        for (AgentHandle h : crew) {
            var w = h.openWorld();
            BlockState in = w == null ? null : w.get(fire.getX(), fire.getY(), fire.getZ());
            if (in != null && in.getBlock() == Blocks.NETHER_PORTAL) {
                lit = true;
                status = "lit";
                h.log("§athe nether portal at " + origin.toShortString() + " is lit");
                dev.agentnet.map.Pois.add(h.server(), h.dimension(), fire.getX(), fire.getY(), fire.getZ(), "nether_portal");
                return;
            }
        }
        for (AgentHandle h : crew) {
            int slot = -1;
            List<ItemStack> inv = h.inventory();
            for (int i = 0; i < inv.size(); i++) if (inv.get(i).getItem() == Items.FLINT_AND_STEEL) slot = i;
            if (slot < 0) continue;
            double d = Math.sqrt(fire.distToCenterSqr(h.x(), h.y() + 1.6, h.z()));
            if (d > 4) { if (!h.isNavigating()) h.navigate(fire.getX(), fire.getY(), fire.getZ() - 2, 1); status = h.name() + " walking over to light it"; return; }
            if (slot >= 9) { h.moveToHotbar(slot, 8); slot = 8; }
            h.selectHotbar(slot);
            h.lookAt(fire.getX() + 0.5, fire.getY(), fire.getZ() + 0.5);
            h.useItemOn(fire.getX(), fire.getY() - 1, fire.getZ(), Direction.UP); // the top of the bottom obsidian
            status = h.name() + " lighting it";
            return;
        }
        long now = System.currentTimeMillis();
        status = "frame done: needs flint and steel";
        if (now - warnedAt > 3 * 60_000) {
            warnedAt = now;
            for (AgentHandle h : crew) if (h.role() == Role.BUILDER) { BuilderModule.requestMaterial(h, "minecraft:flint_and_steel", 1); break; }
        }
    }

    /** Obsidian of the frame already in place (as far as any agent can see it). */
    private static int placed(List<? extends AgentHandle> crew) {
        if (origin == null) return 0;
        int best = 0;
        for (AgentHandle h : crew) {
            var w = h.openWorld();
            if (w == null) continue;
            int n = 0;
            for (BlockPos p : frame()) { BlockState s = w.get(p.getX(), p.getY(), p.getZ()); if (s != null && s.getBlock() == Blocks.OBSIDIAN) n++; }
            best = Math.max(best, n);
        }
        return best;
    }

    private static List<BlockPos> frame() {
        java.util.ArrayList<BlockPos> out = new java.util.ArrayList<>();
        for (int x = 0; x < 4; x++) for (int y = 0; y < 5; y++) {
            boolean edge = x == 0 || x == 3 || y == 0 || y == 4;
            boolean corner = (x == 0 || x == 3) && (y == 0 || y == 4);
            if (edge && !corner) out.add(origin.offset(x, y, 0));
        }
        return out;
    }

    /** Just outside the base's east wall, the frame's bottom level with the base floor. */
    private static void plan(AgentHandle a, Bases.Base b) {
        server = a.server();
        dimension = a.dimension();
        origin = new BlockPos(b.origin().getX() + 19, b.hub().getY() - 1, b.origin().getZ() + 4);
        Map<BlockPos, BlockState> m = new HashMap<>();
        for (int x = 0; x < 4; x++) for (int y = 0; y < 5; y++) {
            boolean edge = x == 0 || x == 3 || y == 0 || y == 4;
            boolean corner = (x == 0 || x == 3) && (y == 0 || y == 4);
            if (edge && !corner) m.put(new BlockPos(x, y, 0), Blocks.OBSIDIAN.defaultBlockState());
        }
        Path dir = dev.agentnet.AgentFiles.dir().resolve("schematics"), file = dir.resolve(FILE);
        try {
            Files.createDirectories(dir);
            Litematic.save(file, "AgentNet nether portal", "AgentNet", BasePlanner.getter(m), BlockPos.ZERO, new BlockPos(3, 4, 0));
            Litematic.forget(file);
            status = "planned at " + origin.toShortString();
            a.log("§aplanned the crew's nether portal at " + origin.toShortString() + " (10 obsidian, then flint and steel)");
        } catch (java.io.IOException e) {
            dev.agentnet.AgentNet.LOG.warn("couldn't write the portal schematic", e);
            origin = null;
        }
    }
}
