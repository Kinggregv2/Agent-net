package dev.agentnet.map;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.agentnet.AgentFiles;
import dev.agentnet.AgentNet;

/**
 * Points of interest. Explorers revisit them, longest-unvisited first.
 *
 * <p>Two sources: Xaero waypoints whose name ends in "POI" (kept in sync automatically:
 * rename or delete the waypoint and the POI follows), and /agent poi add.
 * Saved to agentnet/pois.txt as "server|dimension|x|y|z|lastVisitedMillis|source|name".
 */
public final class Pois {
    public static final String MANUAL = "manual", XAERO = "xaero";

    public record Poi(String server, String dimension, int x, int y, int z, long lastVisited, String source, String name) {}

    /** A waypoint as read from Xaero. */
    public record Waypoint(String name, int x, int y, int z) {}

    private static List<Poi> cache;

    private Pois() {}

    private static Path file() { return AgentFiles.dir().resolve("pois.txt"); }

    public static synchronized List<Poi> list(String server, String dimension) {
        load();
        return cache.stream().filter(p -> p.server.equals(server) && p.dimension.equals(dimension)).toList();
    }

    public static synchronized List<Poi> listServer(String server) {
        load();
        return cache.stream().filter(p -> p.server.equals(server)).toList();
    }

    /** Returns false if a POI with that name already exists on this server. */
    public static synchronized boolean add(String server, String dimension, int x, int y, int z, String name) {
        load();
        if (find(server, name) != null) return false;
        cache.add(new Poi(server, dimension, x, y, z, 0L, MANUAL, name));
        save();
        return true;
    }

    public static synchronized boolean remove(String server, String name) {
        load();
        Poi p = find(server, name);
        if (p == null) return false;
        cache.remove(p);
        save();
        return true;
    }

    /** Explorers call this on arrival. */
    public static synchronized void markVisited(String server, String name) {
        load();
        Poi p = find(server, name);
        if (p == null) return;
        cache.set(cache.indexOf(p), new Poi(p.server, p.dimension, p.x, p.y, p.z, System.currentTimeMillis(), p.source, p.name));
        save();
    }

    /**
     * Make the Xaero-sourced POIs of one server + dimension match these waypoints.
     * Returns how many POIs were added or removed (0 = nothing changed).
     */
    public static synchronized int syncFromXaero(String server, String dimension, List<Waypoint> waypoints) {
        load();
        Map<String, Poi> old = new HashMap<>();
        for (Poi p : cache) {
            if (p.server.equals(server) && p.dimension.equals(dimension) && p.source.equals(XAERO)) old.put(p.name.toLowerCase(), p);
        }
        List<Poi> fresh = new ArrayList<>();
        for (Waypoint w : waypoints) {
            Poi prev = old.get(w.name().toLowerCase());
            fresh.add(new Poi(server, dimension, w.x(), w.y(), w.z(), prev == null ? 0L : prev.lastVisited, XAERO, w.name()));
        }
        if (fresh.size() == old.size() && fresh.stream().allMatch(f -> old.containsValue(f))) return 0;

        int changes = Math.abs(fresh.size() - old.size()) + (int) fresh.stream().filter(f -> !old.containsValue(f)).count();
        cache.removeIf(p -> p.server.equals(server) && p.dimension.equals(dimension) && p.source.equals(XAERO));
        cache.addAll(fresh);
        save();
        return Math.max(1, changes);
    }

    private static Poi find(String server, String name) {
        for (Poi p : cache) if (p.server.equals(server) && p.name.equalsIgnoreCase(name)) return p;
        return null;
    }

    private static void load() {
        if (cache != null) return;
        cache = new ArrayList<>();
        try {
            if (!Files.exists(file())) return;
            for (String line : Files.readAllLines(file(), StandardCharsets.UTF_8)) {
                if (line.startsWith("#")) continue;
                String[] p = line.split("\\|", 8);
                if (p.length == 7) { // older format, no source column
                    cache.add(new Poi(p[0], p[1], Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                            Integer.parseInt(p[4]), Long.parseLong(p[5]), MANUAL, p[6]));
                } else if (p.length == 8) {
                    cache.add(new Poi(p[0], p[1], Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                            Integer.parseInt(p[4]), Long.parseLong(p[5]), p[6], p[7]));
                }
            }
        } catch (IOException | NumberFormatException e) {
            AgentNet.LOG.warn("Could not read pois.txt", e);
        }
    }

    private static void save() {
        List<String> lines = new ArrayList<>(List.of("# server|dimension|x|y|z|lastVisitedMillis|source|name"));
        for (Poi p : cache) {
            lines.add(String.join("|", p.server, p.dimension, "" + p.x, "" + p.y, "" + p.z, "" + p.lastVisited, p.source, p.name));
        }
        try {
            Files.write(file(), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            AgentNet.LOG.warn("Could not write pois.txt", e);
        }
    }
}
