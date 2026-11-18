package dev.agentnet.role;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.agentnet.AgentFiles;
import dev.agentnet.AgentNet;

/**
 * Chests the distributors and traders know about, shared by all agents and saved to
 * agentnet/chests.txt as "server|dimension|x|y|z|note". Later: what's inside each one.
 */
public final class ChestMemory {
    public record Chest(String server, String dimension, int x, int y, int z, String note) {}

    private static List<Chest> cache;
    /** What each chest held the last time an agent looked into it (not saved). */
    public static final java.util.Map<net.minecraft.core.BlockPos, List<net.minecraft.world.item.ItemStack>> CONTENTS = new java.util.concurrent.ConcurrentHashMap<>();

    private ChestMemory() {}

    private static Path file() { return AgentFiles.dir().resolve("chests.txt"); }

    public static synchronized List<Chest> list(String server, String dimension) {
        load();
        return cache.stream().filter(c -> c.server.equals(server) && c.dimension.equals(dimension)).toList();
    }

    public static synchronized void remember(String server, String dimension, int x, int y, int z, String note) {
        load();
        cache.removeIf(c -> c.server.equals(server) && c.dimension.equals(dimension) && c.x == x && c.y == y && c.z == z);
        cache.add(new Chest(server, dimension, x, y, z, note == null ? "" : note.replace('|', '/')));
        save();
    }

    public static synchronized void forget(String server, String dimension, int x, int y, int z) {
        load();
        if (cache.removeIf(c -> c.server.equals(server) && c.dimension.equals(dimension) && c.x == x && c.y == y && c.z == z)) save();
    }

    private static void load() {
        if (cache != null) return;
        cache = new ArrayList<>();
        try {
            if (!Files.exists(file())) return;
            for (String line : Files.readAllLines(file(), StandardCharsets.UTF_8)) {
                String[] p = line.split("\\|", 6);
                if (p.length < 5 || line.startsWith("#")) continue;
                cache.add(new Chest(p[0], p[1], Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                        Integer.parseInt(p[4]), p.length > 5 ? p[5] : ""));
            }
        } catch (IOException | NumberFormatException e) {
            AgentNet.LOG.warn("Could not read chests.txt", e);
        }
    }

    private static void save() {
        List<String> lines = new ArrayList<>(List.of("# server|dimension|x|y|z|note"));
        for (Chest c : cache) lines.add(String.join("|", c.server, c.dimension, "" + c.x, "" + c.y, "" + c.z, c.note));
        try {
            Files.write(file(), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            AgentNet.LOG.warn("Could not write chests.txt", e);
        }
    }
}
