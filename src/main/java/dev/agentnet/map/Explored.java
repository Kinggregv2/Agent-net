package dev.agentnet.map;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.agentnet.AgentFiles;
import dev.agentnet.AgentNet;

/**
 * Which chunks have been seen, per server and dimension: every chunk any agent received
 * from the server, and the chunks around you. Explorers head for the edge of this set.
 * Saved to agentnet/explored/&lt;server&gt;_&lt;dimension&gt;.txt (one "x z" chunk per line).
 *
 * <p>Thread-safe: agents add chunks from their network threads.
 */
public final class Explored {
    private static final Map<String, Set<Long>> SEEN = new ConcurrentHashMap<>();
    private static final Set<String> DIRTY = ConcurrentHashMap.newKeySet();
    /** chunk key -> explorer heading there, so two explorers don't chase the same chunk. */
    private static final Map<String, Map<Long, String>> CLAIMS = new ConcurrentHashMap<>();

    private Explored() {}

    public static long key(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }
    public static int cx(long k) { return (int) (k >> 32); }
    public static int cz(long k) { return (int) k; }

    private static String id(String server, String dimension) {
        return (server + "_" + dimension).replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static Set<Long> set(String server, String dimension) {
        return SEEN.computeIfAbsent(id(server, dimension), Explored::load);
    }

    public static void add(String server, String dimension, int cx, int cz) {
        if (server == null || dimension == null) return;
        if (set(server, dimension).add(key(cx, cz))) DIRTY.add(id(server, dimension));
    }

    public static boolean seen(String server, String dimension, int cx, int cz) {
        return set(server, dimension).contains(key(cx, cz));
    }

    public static int count(String server, String dimension) { return set(server, dimension).size(); }

    /**
     * The nearest chunk nobody has seen, within radius chunks of the center, that no other
     * explorer is heading for and that isn't in {@code avoid}; claims it for this explorer.
     * Searches ring by ring outward from the explorer, so the first hit is the closest one.
     */
    public static Long claimFrontier(String server, String dimension, String explorer, int fromCx, int fromCz,
                                     int centerCx, int centerCz, int radius, Set<Long> avoid) {
        Set<Long> seen = set(server, dimension);
        Map<Long, String> claims = CLAIMS.computeIfAbsent(id(server, dimension), k -> new ConcurrentHashMap<>());
        release(explorer);
        int maxRing = radius * 2 + 2;
        for (int r = 0; r <= maxRing; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue; // ring only
                    int x = fromCx + dx, z = fromCz + dz;
                    if (Math.max(Math.abs(x - centerCx), Math.abs(z - centerCz)) > radius) continue;
                    long k = key(x, z);
                    if (seen.contains(k) || avoid.contains(k)) continue;
                    String other = claims.get(k);
                    if (other != null && !other.equals(explorer)) continue;
                    // Spread explorers out: skip chunks right next to another explorer's target.
                    if (nearOtherClaim(claims, x, z, explorer)) continue;
                    claims.put(k, explorer);
                    return k;
                }
            }
        }
        return null;
    }

    private static boolean nearOtherClaim(Map<Long, String> claims, int x, int z, String explorer) {
        for (var e : claims.entrySet()) {
            if (e.getValue().equals(explorer)) continue;
            if (Math.max(Math.abs(cx(e.getKey()) - x), Math.abs(cz(e.getKey()) - z)) <= 3) return true;
        }
        return false;
    }

    public static void release(String explorer) {
        for (var m : CLAIMS.values()) m.values().removeIf(explorer::equals);
    }

    // ------------------------------------------------------------------ files

    private static Path dir() { return AgentFiles.dir().resolve("explored"); }

    private static Set<Long> load(String id) {
        Set<Long> s = ConcurrentHashMap.newKeySet();
        Path f = dir().resolve(id + ".txt");
        try {
            if (Files.exists(f)) {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    String[] p = line.trim().split("\\s+");
                    if (p.length == 2) s.add(key(Integer.parseInt(p[0]), Integer.parseInt(p[1])));
                }
            }
        } catch (IOException | NumberFormatException e) {
            AgentNet.LOG.warn("Could not read {}", f, e);
        }
        return s;
    }

    /** Write every changed dimension to disk. Cheap when nothing changed. */
    public static void save() {
        for (String id : new ArrayList<>(DIRTY)) {
            DIRTY.remove(id);
            Set<Long> s = SEEN.get(id);
            if (s == null) continue;
            List<String> lines = new ArrayList<>(s.size());
            for (long k : s) lines.add(cx(k) + " " + cz(k));
            try {
                Files.createDirectories(dir());
                Files.write(dir().resolve(id + ".txt"), lines, StandardCharsets.UTF_8);
            } catch (IOException e) {
                AgentNet.LOG.warn("Could not write explored chunks for {}", id, e);
            }
        }
    }
}
