package dev.agentnet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.Minecraft;

/**
 * Plain-text files shared by you and the agents, in <instance>/agentnet/.
 * Nothing here is ever sent to the server.
 */
public final class AgentFiles {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static Path dir() {
        Path d = Minecraft.getInstance().gameDirectory.toPath().resolve("agentnet");
        try { Files.createDirectories(d); } catch (IOException ignored) {}
        return d;
    }

    /** Players the agents never treat as enemies. Edit with /agent whitelist, or by hand. */
    static final NameList WHITELIST = new NameList("whitelist.txt",
            "# Players agents will never treat as enemies. One name per line.");
    /** Players who hurt an agent badly. Warriors will use this to pick targets. */
    static final NameList HITLIST = new NameList("hitlist.txt",
            "# Enemies: players not on the whitelist who dealt over 1/3 of an agent's max health.\n"
            + "# Format: name  # when, who they hit, how much");

    /**
     * A name-per-line file. Re-read from disk whenever it changes, so hand edits
     * work. Anything after '#' on a line is a comment and is kept when saving.
     */
    static final class NameList {
        private final String fileName;
        private final String header;
        private final Map<String, String> entries = new LinkedHashMap<>(); // lowercase name -> full line
        private long loadedStamp = -1;

        NameList(String fileName, String header) {
            this.fileName = fileName;
            this.header = header;
        }

        Path path() { return dir().resolve(fileName); }

        synchronized boolean contains(String name) {
            reload();
            return entries.containsKey(name.toLowerCase(Locale.ROOT));
        }

        synchronized List<String> names() {
            reload();
            List<String> out = new ArrayList<>();
            for (String line : entries.values()) out.add(nameOf(line));
            return out;
        }

        /** Returns false if already present. */
        synchronized boolean add(String name, String comment) {
            reload();
            String key = name.toLowerCase(Locale.ROOT);
            if (entries.containsKey(key)) return false;
            entries.put(key, comment == null || comment.isBlank() ? name : name + "  # " + comment);
            save();
            return true;
        }

        synchronized boolean remove(String name) {
            reload();
            boolean removed = entries.remove(name.toLowerCase(Locale.ROOT)) != null;
            if (removed) save();
            return removed;
        }

        private void reload() {
            Path p = path();
            try {
                if (!Files.exists(p)) {
                    entries.clear();
                    save();
                    return;
                }
                long stamp = Files.getLastModifiedTime(p).toMillis();
                if (stamp == loadedStamp) return;
                entries.clear();
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    String name = nameOf(line);
                    if (!name.isEmpty()) entries.put(name.toLowerCase(Locale.ROOT), line.strip());
                }
                loadedStamp = stamp;
            } catch (IOException e) {
                AgentNet.LOG.warn("Could not read {}", p, e);
            }
        }

        private void save() {
            Path p = path();
            List<String> lines = new ArrayList<>(List.of(header.split("\n")));
            lines.addAll(entries.values());
            try {
                Files.write(p, lines, StandardCharsets.UTF_8);
                loadedStamp = Files.getLastModifiedTime(p).toMillis();
            } catch (IOException e) {
                AgentNet.LOG.warn("Could not write {}", p, e);
            }
        }

        private static String nameOf(String line) {
            int hash = line.indexOf('#');
            String s = (hash >= 0 ? line.substring(0, hash) : line).strip();
            int space = s.indexOf(' ');
            return space >= 0 ? s.substring(0, space) : s;
        }
    }

    static String now() { return LocalDateTime.now().format(STAMP); }

    /**
     * Private channel between you and the agents: botchat.txt, one message per line,
     * "[HH:mm:ss] from -> to: message". Agents will read and write it once they have brains.
     */
    static synchronized void botChat(String from, String to, String message) {
        String line = "[" + LocalTime.now().format(CLOCK) + "] " + from + " -> " + to + ": "
                + message.replace('\n', ' ') + System.lineSeparator();
        try {
            Files.writeString(dir().resolve("botchat.txt"), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            AgentNet.LOG.warn("Could not write botchat.txt", e);
        }
    }
}
