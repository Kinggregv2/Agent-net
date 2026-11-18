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
 * Work spots: where a fisher stands (and which way it faces), or where a farm is.
 * An agent that gets the matching role claims the nearest free spot; only one agent
 * per spot. Saved to agentnet/spots.txt as "type|server|dimension|x|y|z|yaw|pitch|name".
 */
public final class Spots {
    public enum Type {
        FISHING;
        public String id() { return name().toLowerCase(Locale.ROOT); }
        public static Type parse(String s) {
            for (Type t : values()) if (t.id().equalsIgnoreCase(s)) return t;
            return null;
        }
    }

    /** x/y/z is the block the agent stands in; yaw/pitch is where it looks. */
    public record Spot(Type type, String server, String dimension, double x, double y, double z,
                       float yaw, float pitch, String name) {
        public double distSq(double px, double py, double pz) {
            double dx = x - px, dy = y - py, dz = z - pz;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    private static List<Spot> cache;
    /** spot name (lowercase) -> agent name. In memory only: claims reset on restart. */
    private static final Map<String, String> CLAIMS = new ConcurrentHashMap<>();

    private Spots() {}

    private static Path file() { return AgentFiles.dir().resolve("spots.txt"); }

    public static synchronized List<Spot> list(String server) {
        load();
        return cache.stream().filter(s -> s.server.equals(server)).toList();
    }

    public static synchronized boolean add(Spot spot) {
        load();
        if (find(spot.server, spot.name) != null) return false;
        cache.add(spot);
        save();
        return true;
    }

    public static synchronized boolean remove(String server, String name) {
        load();
        Spot s = find(server, name);
        if (s == null) return false;
        cache.remove(s);
        CLAIMS.remove(name.toLowerCase());
        save();
        return true;
    }

    /** Claim the nearest unclaimed spot of this type for an agent (or return its existing claim). */
    public static synchronized Optional<Spot> claim(Type type, String agent, String server, String dimension,
                                                    double x, double y, double z) {
        load();
        Optional<Spot> mine = cache.stream()
                .filter(s -> s.type == type && agent.equalsIgnoreCase(CLAIMS.get(s.name.toLowerCase())))
                .findFirst();
        if (mine.isPresent()) return mine;
        Optional<Spot> free = cache.stream()
                .filter(s -> s.type == type && s.server.equals(server) && s.dimension.equals(dimension))
                .filter(s -> !CLAIMS.containsKey(s.name.toLowerCase()))
                .min(Comparator.comparingDouble(s -> s.distSq(x, y, z)));
        free.ifPresent(s -> CLAIMS.put(s.name.toLowerCase(), agent));
        return free;
    }

    /** Free every spot this agent holds (role changed, agent left). */
    /** Forget every claim (the crew moved to another server). */
    public static void clearClaims() { CLAIMS.clear(); }

    public static void release(String agent) {
        CLAIMS.values().removeIf(a -> a.equalsIgnoreCase(agent));
    }

    public static String claimedBy(String spotName) { return CLAIMS.get(spotName.toLowerCase()); }

    private static Spot find(String server, String name) {
        for (Spot s : cache) if (s.server.equals(server) && s.name.equalsIgnoreCase(name)) return s;
        return null;
    }

    private static void load() {
        if (cache != null) return;
        cache = new ArrayList<>();
        try {
            if (!Files.exists(file())) return;
            for (String line : Files.readAllLines(file(), StandardCharsets.UTF_8)) {
                if (line.startsWith("#")) continue;
                String[] p = line.split("\\|", 9);
                if (p.length < 9 || Type.parse(p[0]) == null) continue;
                cache.add(new Spot(Type.parse(p[0]), p[1], p[2], Double.parseDouble(p[3]), Double.parseDouble(p[4]),
                        Double.parseDouble(p[5]), Float.parseFloat(p[6]), Float.parseFloat(p[7]), p[8]));
            }
        } catch (IOException | NumberFormatException e) {
            AgentNet.LOG.warn("Could not read spots.txt", e);
        }
    }

    private static void save() {
        List<String> lines = new ArrayList<>(List.of("# type|server|dimension|x|y|z|yaw|pitch|name"));
        for (Spot s : cache) {
            lines.add(String.join("|", s.type.id(), s.server, s.dimension, "" + s.x, "" + s.y, "" + s.z,
                    "" + s.yaw, "" + s.pitch, s.name));
        }
        try {
            Files.write(file(), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            AgentNet.LOG.warn("Could not write spots.txt", e);
        }
    }
}
