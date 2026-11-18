package dev.agentnet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.agentnet.map.Servers;
import dev.agentnet.role.Role;

/**
 * Every agent you've had, per server, with its role and importance. Saved automatically
 * whenever an agent joins or its role/importance changes, kept when it leaves, so
 * /load can bring the crew back later. agentnet/roster.txt: "server|name|role|importance".
 */
final class Roster {
    record Entry(String server, String name, Role role, int importance) {}

    /** server|name(lowercase) -> entry, in join order. Main thread only. */
    private static Map<String, Entry> entries;

    private Roster() {}

    private static Path file() { return AgentFiles.dir().resolve("roster.txt"); }

    static void remember(Agent a) {
        load();
        String server = Servers.key(a.address);
        Role role = a.role();
        Entry prev = entries.get(server + "|" + a.name.toLowerCase());
        if (dev.agentnet.role.War.active() && prev != null) role = prev.role(); // war roles are temporary
        Entry e = new Entry(server, a.name, role, a.importance);
        Entry old = entries.put(server + "|" + a.name.toLowerCase(), e);
        if (!e.equals(old)) save();
    }

    static boolean forget(String server, String name) {
        load();
        boolean removed = entries.remove(server + "|" + name.toLowerCase()) != null;
        if (removed) save();
        return removed;
    }

    static List<Entry> list(String server) {
        load();
        return entries.values().stream().filter(e -> e.server.equals(server)).toList();
    }

    private static void load() {
        if (entries != null) return;
        entries = new LinkedHashMap<>();
        try {
            if (!Files.exists(file())) return;
            for (String line : Files.readAllLines(file(), StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] p = line.split("\\|");
                if (p.length < 4) continue;
                Role role = p[2].equals("none") ? null : Role.parse(p[2]);
                int imp;
                try { imp = Integer.parseInt(p[3].trim()); } catch (NumberFormatException e) { imp = 5; }
                entries.put(p[0] + "|" + p[1].toLowerCase(), new Entry(p[0], p[1], role, imp));
            }
        } catch (IOException e) {
            AgentNet.LOG.warn("Could not read roster.txt", e);
        }
    }

    private static void save() {
        List<String> lines = new ArrayList<>(List.of("# server|name|role|importance  (saved automatically; /load brings them back)"));
        for (Entry e : entries.values()) {
            lines.add(e.server + "|" + e.name + "|" + (e.role == null ? "none" : e.role.id()) + "|" + e.importance);
        }
        try {
            Files.write(file(), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            AgentNet.LOG.warn("Could not write roster.txt", e);
        }
    }
}
