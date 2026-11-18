package dev.agentnet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import dev.agentnet.role.Role;

/**
 * Named crew snapshots: "/agent save base1" writes every agent's role, task and importance to
 * agentnet/saves/base1.txt; "/agent load base1" brings that crew back (on whatever server you're
 * on now): agents that are online get the role and task, the others join with them.
 * Line format: "name|role|importance|task".
 */
final class Saves {
    record Entry(String name, Role role, int importance, String task) {}

    private Saves() {}

    private static Path dir() { return AgentFiles.dir().resolve("saves"); }

    /** Letters, digits, - and _ only (it's a file name). */
    static boolean validName(String s) { return s.matches("[A-Za-z0-9_-]{1,40}"); }

    static Path file(String name) { return dir().resolve(name.toLowerCase() + ".txt"); }

    static boolean exists(String name) { return validName(name) && Files.exists(file(name)); }

    static void save(String name, List<Agent> agents) throws IOException {
        Files.createDirectories(dir());
        List<String> lines = new ArrayList<>(List.of("# AgentNet crew save: name|role|importance|task"));
        for (Agent a : agents) {
            String task = a.task == null ? "idle" : a.task.replace('|', ' ').replace('\n', ' ');
            lines.add(a.name + "|" + (a.role() == null ? "none" : a.role().id()) + "|" + a.importance + "|" + task);
        }
        Files.write(file(name), lines, StandardCharsets.UTF_8);
    }

    static List<Entry> read(String name) throws IOException {
        List<Entry> out = new ArrayList<>();
        for (String line : Files.readAllLines(file(name), StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] p = line.split("\\|", 4);
            if (p.length < 4) continue;
            Role role = p[1].equals("none") ? null : Role.parse(p[1]);
            int imp;
            try { imp = Integer.parseInt(p[2].trim()); } catch (NumberFormatException e) { imp = 5; }
            out.add(new Entry(p[0], role, imp, p[3].isBlank() ? "idle" : p[3].strip()));
        }
        return out;
    }

    static List<String> names() {
        if (!Files.isDirectory(dir())) return List.of();
        try (Stream<Path> s = Files.list(dir())) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".txt")).map(n -> n.substring(0, n.length() - 4)).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
