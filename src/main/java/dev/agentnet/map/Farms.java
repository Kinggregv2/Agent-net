package dev.agentnet.map;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import dev.agentnet.AgentFiles;
import dev.agentnet.AgentNet;

/**
 * Farm areas: a box marked with two corners, for crops or trees. A farmer claims a free
 * one (one farmer per farm) and works inside it. Saved to agentnet/farms.txt as
 * "type|server|dimension|x1|y1|z1|x2|y2|z2|name".
 */
public final class Farms {
    public enum Type {
        CROPS, TREES;
        public String id() { return name().toLowerCase(Locale.ROOT); }
        public static Type parse(String s) {
            for (Type t : values()) if (t.id().equalsIgnoreCase(s)) return t;
            return null;
        }
    }

    /** Inclusive box, corners normalized so x1 <= x2 etc. */
    public record Farm(Type type, String server, String dimension, int x1, int y1, int z1, int x2, int y2, int z2, String name) {
        public Farm {
            int t;
            if (x1 > x2) { t = x1; x1 = x2; x2 = t; }
            if (y1 > y2) { t = y1; y1 = y2; y2 = t; }
            if (z1 > z2) { t = z1; z1 = z2; z2 = t; }
        }
        public boolean contains(double x, double y, double z) {
            return x >= x1 && x < x2 + 1 && y >= y1 - 1 && y < y2 + 2 && z >= z1 && z < z2 + 1;
        }
        public int centerX() { return (x1 + x2) / 2; }
        public int centerZ() { return (z1 + z2) / 2; }
        public long blocks() { return (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1); }
        double distSq(double x, double z) {
            double dx = centerX() - x, dz = centerZ() - z;
            return dx * dx + dz * dz;
        }
    }

    private static List<Farm> cache;
    /** farm (lowercase) -> farmers working it. Several farmers may share a field. */
    private static final Map<String, java.util.Set<String>> CLAIMS = new ConcurrentHashMap<>();

    private Farms() {}

    private static Path file() { return AgentFiles.dir().resolve("farms.txt"); }

    public static synchronized List<Farm> list(String server) {
        load();
        return cache.stream().filter(f -> f.server.equals(server)).toList();
    }

    public static synchronized boolean add(Farm farm) {
        load();
        if (find(farm.server, farm.name) != null) return false;
        cache.add(farm);
        save();
        return true;
    }

    public static synchronized boolean remove(String server, String name) {
        load();
        Farm f = find(server, name);
        if (f == null) return false;
        cache.remove(f);
        CLAIMS.remove(name.toLowerCase());
        save();
        return true;
    }

    /**
     * The farm this agent works, or join one: the farm with the fewest farmers, nearest
     * first among equals. Several farmers can share a field; they split the work through
     * reservations (see FarmWork), so they never chase the same crop.
     */
    public static synchronized Optional<Farm> claim(String agent, String server, String dimension, double x, double z) {
        load();
        Optional<Farm> mine = cache.stream().filter(f -> workers(f.name).contains(agent.toLowerCase())).findFirst();
        if (mine.isPresent()) return mine;
        Optional<Farm> pick = cache.stream()
                .filter(f -> f.server.equals(server) && f.dimension.equals(dimension))
                .min(Comparator.<Farm>comparingInt(f -> workers(f.name).size()).thenComparingDouble(f -> f.distSq(x, z)));
        pick.ifPresent(f -> CLAIMS.computeIfAbsent(f.name.toLowerCase(), k -> ConcurrentHashMap.newKeySet()).add(agent.toLowerCase()));
        return pick;
    }

    private static java.util.Set<String> workers(String farm) {
        return CLAIMS.getOrDefault(farm.toLowerCase(), java.util.Set.of());
    }

    /** Forget every claim (the crew moved to another server). */
    public static void clearClaims() { CLAIMS.clear(); }

    public static void release(String agent) {
        for (var set : CLAIMS.values()) set.remove(agent.toLowerCase());
    }

    /** Farmers on this farm, comma-separated, or null if nobody. */
    public static String claimedBy(String farm) {
        var w = workers(farm);
        return w.isEmpty() ? null : String.join(", ", w);
    }

    private static Farm find(String server, String name) {
        for (Farm f : cache) if (f.server.equals(server) && f.name.equalsIgnoreCase(name)) return f;
        return null;
    }

    private static void load() {
        if (cache != null) return;
        cache = new ArrayList<>();
        try {
            if (!Files.exists(file())) return;
            for (String line : Files.readAllLines(file(), StandardCharsets.UTF_8)) {
                if (line.startsWith("#")) continue;
                String[] p = line.split("\\|", 10);
                if (p.length < 10 || Type.parse(p[0]) == null) continue;
                cache.add(new Farm(Type.parse(p[0]), p[1], p[2], Integer.parseInt(p[3]), Integer.parseInt(p[4]),
                        Integer.parseInt(p[5]), Integer.parseInt(p[6]), Integer.parseInt(p[7]), Integer.parseInt(p[8]), p[9]));
            }
        } catch (IOException | NumberFormatException e) {
            AgentNet.LOG.warn("Could not read farms.txt", e);
        }
    }

    private static void save() {
        List<String> lines = new ArrayList<>(List.of("# type|server|dimension|x1|y1|z1|x2|y2|z2|name"));
        for (Farm f : cache) {
            lines.add(String.join("|", f.type.id(), f.server, f.dimension, "" + f.x1, "" + f.y1, "" + f.z1,
                    "" + f.x2, "" + f.y2, "" + f.z2, f.name));
        }
        try {
            Files.write(file(), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            AgentNet.LOG.warn("Could not write farms.txt", e);
        }
    }
}
