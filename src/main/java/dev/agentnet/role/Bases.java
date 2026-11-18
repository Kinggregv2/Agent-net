package dev.agentnet.role;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * The crew's bases, one per server and dimension: where they are, where their hub, door,
 * chests and furnaces are, and whether they're finished. Saved in agentnet/bases.txt.
 *
 * <p>A base is the crew's home: storage keepers live there, distributors bring everything there,
 * makers smelt at its furnace array, and an agent with nothing to do waits at the hub (so the crew
 * stays together instead of being spread over the map).
 */
public final class Bases {
    private Bases() {}

    public record Base(String server, String dimension, BlockPos origin, String file, BlockPos hub, BlockPos door,
                       List<BlockPos> chests, List<BlockPos> furnaces, BlockPos table, boolean built) {
        public Base withBuilt(boolean b) { return new Base(server, dimension, origin, file, hub, door, chests, furnaces, table, b); }
    }

    private static List<Base> cache;

    private static Path file() { return dev.agentnet.AgentFiles.dir().resolve("bases.txt"); }

    /** The base in this dimension (built or still going up), or null. */
    public static synchronized Base get(String server, String dimension) {
        load();
        for (Base b : cache) if (b.server().equals(server) && dev.agentnet.role.AgentHandle.sameDim(b.dimension(), dimension)) return b;
        return null;
    }

    /** A finished base in this dimension, or null. */
    public static Base built(String server, String dimension) {
        Base b = get(server, dimension);
        return b != null && b.built() ? b : null;
    }

    /** Blocks the crew's base still needs (last check), -1 = unknown. */
    public static int blocksLeft() { return BaseWork.blocksLeft; }

    /** Raw materials the base going up still needs, as miner tasks ("mine stone 64"): for the civ autopilot. */
    public static java.util.List<String> supplyNeeds() { return BaseWork.needs(); }

    /** How far from the crew a base still counts as theirs (a base left behind elsewhere doesn't). */
    public static final int RANGE = 256;

    /** A finished base within {@link #RANGE} blocks of this spot, or null: until then, no hub and no keeper. */
    /** The crew's base (built or going up): one that some online agent is within {@link #RANGE} blocks of.
     *  A base everyone has left behind is not the crew's any more. Used by the autopilot, the keeper and
     *  distributors alike, so they all agree on whether there is a base. */
    public static Base crew(String server, String dimension) {
        Base b = get(server, dimension);
        return b != null && !BaseWork.abandoned(b) ? b : null;
    }

    /** The crew's base, once it's finished; else null. */
    public static Base crewBuilt(String server, String dimension) {
        Base b = crew(server, dimension);
        return b != null && b.built() ? b : null;
    }

    public static Base builtNear(String server, String dimension, double x, double z) {
        Base b = built(server, dimension);
        if (b == null) return null;
        double dx = b.origin().getX() - x, dz = b.origin().getZ() - z;
        return dx * dx + dz * dz <= (double) RANGE * RANGE ? b : null;
    }

    public static synchronized void put(Base b) {
        load();
        cache.removeIf(x -> x.server().equals(b.server()) && dev.agentnet.role.AgentHandle.sameDim(x.dimension(), b.dimension()));
        cache.add(b);
        save();
    }

    public static synchronized void remove(String server, String dimension) {
        load();
        cache.removeIf(x -> x.server().equals(server) && dev.agentnet.role.AgentHandle.sameDim(x.dimension(), dimension));
        save();
    }

    // "server|dim|ox oy oz|file|hx hy hz|dx dy dz|x y z;x y z...|x y z;...|tx ty tz|built"
    private static void load() {
        if (cache != null) return;
        cache = new ArrayList<>();
        try {
            if (!Files.exists(file())) return;
            for (String line : Files.readAllLines(file(), StandardCharsets.UTF_8)) {
                String[] p = line.split("\\|", -1);
                if (p.length < 10 || line.startsWith("#")) continue;
                try {
                    cache.add(new Base(p[0], p[1], pos(p[2]), p[3], pos(p[4]), pos(p[5]), list(p[6]), list(p[7]), pos(p[8]), Boolean.parseBoolean(p[9])));
                } catch (RuntimeException ignored) {}
            }
        } catch (IOException e) {
            dev.agentnet.AgentNet.LOG.warn("couldn't read bases.txt", e);
        }
    }

    private static void save() {
        List<String> out = new ArrayList<>();
        out.add("# AgentNet bases: server|dimension|origin|schematic|hub|door|chests|furnaces|crafting table|built");
        for (Base b : cache) {
            out.add(String.join("|", b.server(), b.dimension(), str(b.origin()), b.file(), str(b.hub()), str(b.door()),
                    strs(b.chests()), strs(b.furnaces()), str(b.table()), String.valueOf(b.built())));
        }
        try { Files.write(file(), out, StandardCharsets.UTF_8); }
        catch (IOException e) { dev.agentnet.AgentNet.LOG.warn("couldn't write bases.txt", e); }
    }

    private static String str(BlockPos p) { return p == null ? "" : p.getX() + " " + p.getY() + " " + p.getZ(); }
    private static String strs(List<BlockPos> l) { return String.join(";", l.stream().map(Bases::str).toList()); }
    private static BlockPos pos(String s) {
        if (s.isBlank()) return null;
        String[] q = s.trim().split(" ");
        return new BlockPos(Integer.parseInt(q[0]), Integer.parseInt(q[1]), Integer.parseInt(q[2]));
    }
    private static List<BlockPos> list(String s) {
        List<BlockPos> out = new ArrayList<>();
        if (!s.isBlank()) for (String q : s.split(";")) out.add(pos(q));
        return out;
    }
}
