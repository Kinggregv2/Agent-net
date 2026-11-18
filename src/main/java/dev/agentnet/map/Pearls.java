package dev.agentnet.map;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.agentnet.AgentFiles;
import dev.agentnet.AgentNet;

/**
 * Ender pearl stasis chambers: for each agent, the block to right-click (trapdoor, lever,
 * button...) that drops its pearl and brings it home. Saved to agentnet/pearls.txt as
 * "server|agent|dimension|x|y|z".
 */
public final class Pearls {
    public record Pearl(String server, String agent, String dimension, int x, int y, int z) {}

    private static List<Pearl> cache;

    private Pearls() {}

    private static Path file() { return AgentFiles.dir().resolve("pearls.txt"); }

    public static synchronized Optional<Pearl> get(String server, String agent) {
        load();
        return cache.stream().filter(p -> p.server.equals(server) && p.agent.equalsIgnoreCase(agent)).findFirst();
    }

    public static synchronized List<Pearl> list(String server) {
        load();
        return cache.stream().filter(p -> p.server.equals(server)).toList();
    }

    public static synchronized void set(Pearl p) {
        load();
        cache.removeIf(o -> o.server.equals(p.server) && o.agent.equalsIgnoreCase(p.agent));
        cache.add(p);
        save();
    }

    public static synchronized boolean remove(String server, String agent) {
        load();
        boolean r = cache.removeIf(o -> o.server.equals(server) && o.agent.equalsIgnoreCase(agent));
        if (r) save();
        return r;
    }

    private static void load() {
        if (cache != null) return;
        cache = new ArrayList<>();
        try {
            if (!Files.exists(file())) return;
            for (String line : Files.readAllLines(file(), StandardCharsets.UTF_8)) {
                String[] p = line.split("\\|");
                if (line.startsWith("#") || p.length < 6) continue;
                cache.add(new Pearl(p[0], p[1], p[2], Integer.parseInt(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5])));
            }
        } catch (IOException | NumberFormatException e) {
            AgentNet.LOG.warn("Could not read pearls.txt", e);
        }
    }

    private static void save() {
        List<String> lines = new ArrayList<>(List.of("# server|agent|dimension|x|y|z  (block to click to pull the agent's pearl)"));
        for (Pearl p : cache) lines.add(String.join("|", p.server, p.agent, p.dimension, "" + p.x, "" + p.y, "" + p.z));
        try {
            Files.write(file(), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            AgentNet.LOG.warn("Could not write pearls.txt", e);
        }
    }
}
