package dev.agentnet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import dev.agentnet.role.Role;

/**
 * View distance (render distance, in chunks) per role: how much world the server sends each
 * agent. Less = less memory and bandwidth; more = miners see ore further away, explorers map
 * more per trip. The server caps it at its own view distance. Saved in
 * agentnet/viewdistance.txt as "role=chunks" (and "none=" for agents without a role).
 */
public final class RoleView {
    public static final int MIN = 2, MAX = 32;
    private static final Map<Role, Integer> VIEW = new EnumMap<>(Role.class);
    private static int none = 2;
    private static boolean loaded;

    private RoleView() {}

    /** Defaults: idle jobs see little, miners and explorers see more. */
    private static int defaultFor(Role r) {
        return switch (r) {
            case WARRIOR, BUILDER, DISTRIBUTOR -> 3;
            default -> 2;
        };
    }

    public static synchronized int get(Role r) {
        load();
        if (r == null) return none;
        return VIEW.getOrDefault(r, defaultFor(r));
    }

    public static synchronized void set(Role r, int chunks) {
        load();
        chunks = Math.max(MIN, Math.min(MAX, chunks));
        if (r == null) none = chunks; else VIEW.put(r, chunks);
        save();
        // Agents with that role get it right away.
        for (Agent a : AgentNetClient.AGENTS.values()) if (a.role() == r) a.sendClientInfo();
    }

    private static Path file() { return AgentFiles.dir().resolve("viewdistance.txt"); }

    private static void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (!Files.exists(file())) return;
            for (String line : Files.readAllLines(file(), StandardCharsets.UTF_8)) {
                String[] p = line.trim().split("=", 2);
                if (p.length != 2 || line.startsWith("#")) continue;
                int v = Math.max(MIN, Math.min(MAX, Integer.parseInt(p[1].trim())));
                if (p[0].trim().equalsIgnoreCase("none")) none = v;
                else { Role r = Role.parse(p[0].trim()); if (r != null) VIEW.put(r, v); }
            }
        } catch (IOException | NumberFormatException e) {
            AgentNet.LOG.warn("Could not read viewdistance.txt", e);
        }
    }

    private static void save() {
        List<String> lines = new ArrayList<>(List.of("# view distance (chunks, " + MIN + "-" + MAX + ") the server sends agents of each role"));
        for (Role r : Role.values()) lines.add(r.id() + "=" + get(r));
        lines.add("none=" + none);
        try {
            Files.write(file(), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            AgentNet.LOG.warn("Could not write viewdistance.txt", e);
        }
    }
}
