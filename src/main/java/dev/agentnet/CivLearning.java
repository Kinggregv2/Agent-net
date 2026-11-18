package dev.agentnet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.agentnet.role.Role;
import net.minecraft.world.item.ItemStack;

/**
 * The autopilot learning from its own decisions. Every job it hands out ("Bob becomes a miner,
 * mine iron_ore 32") is checked {@link #CHECK_MS} later: did the thing it was for go up (iron for
 * an iron miner, food for a farmer, fisher or hunter), or, for jobs with nothing to count, did the
 * agent do anything at all (moved, dug, its inventory changed)? Each kind of job keeps a running
 * score (recent results count most), saved per server in agentnet/civ-learning.txt:
 * <ul>
 *   <li>jobs that keep working are tried first when several needs come up at once;</li>
 *   <li>a job that failed again and again here (no trees for miners, no water for fishers...) is
 *       put on hold for a while instead of being handed out every round, and the reason is said;</li>
 *   <li>the lessons ("mine iron_ore works: 4/5", "fisher failed 3 times: idle") go into the summary
 *       the master model reads, so its own answers and plans take them into account too.</li>
 * </ul>
 */
final class CivLearning {
    private CivLearning() {}

    /** How long after a decision its result is judged. */
    static final long CHECK_MS = 5 * 60_000;
    /** A job kind this bad (after enough tries) is put on hold. */
    private static final double HOLD_BELOW = 0.25;
    private static final int MIN_TRIES = 3;
    private static final long HOLD_MS = 20 * 60_000;

    /** A decision waiting to be judged. */
    private record Pending(String key, String agent, String metric, int before, long at, double x, double z, int invSig, int extensions) {}

    /** How long a job of this kind gets before it's judged: diamonds and food take a while (pickaxes first, crops grow). */
    private static long waitFor(String metric) {
        return switch (metric) { case "diamond" -> 15 * 60_000L; case "food" -> 12 * 60_000L; case "iron" -> 10 * 60_000L; default -> CHECK_MS; };
    }

    /** Learned per job kind: a score 0..1 (recent results weigh most), how often tried / worked, the last failure. */
    static final class Score {
        double value = 0.5;
        int tries, worked;
        String lastWhy = "";
        long holdUntil;
    }

    private static final Deque<Pending> pending = new ArrayDeque<>();
    private static final Map<String, Score> scores = new LinkedHashMap<>();
    private static String loadedFor;

    /** "miner:iron_ore", "farmer", "fisher"...: what a need is about, for learning. */
    static String key(Role role, String task) {
        if (role == null) return "none";
        if (task != null && task.startsWith("mine ")) {
            for (String t : task.split("\\s+")) if (!t.equals("mine") && !t.matches("\\d+")) return "miner:" + t;
        }
        return role.id();
    }

    /** What to count to see whether a job of this kind did its job ("" = just check the agent was busy). */
    private static String metricFor(String key) {
        if (key.startsWith("miner:")) {
            String b = key.substring(6);
            if (b.contains("iron")) return "iron";
            if (b.contains("coal")) return "coal";
            if (b.contains("diamond")) return "diamond";
            if (b.contains("log")) return "wood";
            if (b.equals("stone")) return "stone";
            return "";
        }
        return switch (key) {
            case "farmer", "fisher", "hunter" -> "food";
            default -> "";
        };
    }

    private static int measure(List<Agent> crew, String metric) {
        int n = 0;
        java.util.function.Predicate<String> p = switch (metric) {
            case "iron" -> id -> id.equals("minecraft:raw_iron") || id.equals("minecraft:iron_ingot") || id.equals("minecraft:iron_ore") || id.equals("minecraft:deepslate_iron_ore");
            case "coal" -> id -> id.equals("minecraft:coal") || id.equals("minecraft:charcoal");
            case "diamond" -> id -> id.equals("minecraft:diamond");
            case "wood" -> id -> id.endsWith("_log") || id.endsWith("_planks");
            case "stone" -> id -> id.equals("minecraft:cobblestone") || id.equals("minecraft:cobbled_deepslate");
            default -> null;
        };
        for (Agent a : crew) for (ItemStack s : a.inventory()) {
            if (s.isEmpty()) continue;
            String id = dev.agentnet.role.Gear.id(s);
            if (metric.equals("food") ? dev.agentnet.role.Gear.isFood(s) : p != null && p.test(id)) n += s.getCount();
        }
        for (List<ItemStack> chest : dev.agentnet.role.ChestMemory.CONTENTS.values()) for (ItemStack s : chest) {
            if (s.isEmpty()) continue;
            String id = dev.agentnet.role.Gear.id(s);
            if (metric.equals("food") ? dev.agentnet.role.Gear.isFood(s) : p != null && p.test(id)) n += s.getCount();
        }
        return n;
    }

    private static int invSig(Agent a) {
        int h = 1;
        for (ItemStack s : a.inventory()) h = h * 31 + (s.isEmpty() ? 0 : dev.agentnet.role.Gear.id(s).hashCode() * 7 + s.getCount());
        return h;
    }

    // ------------------------------------------------------------------ recording and judging

    /** The autopilot just gave this agent this job (or asked for a new agent: agent = null). */
    static void decided(Role role, String task, Agent agent, List<Agent> crew) {
        load();
        String key = key(role, task), metric = metricFor(key);
        if (agent == null && metric.isEmpty()) return; // a newcomer for a job with nothing to count: nothing to judge it by
        pending.add(new Pending(key, agent == null ? null : agent.name, metric, metric.isEmpty() ? 0 : measure(crew, metric),
                System.currentTimeMillis(), agent == null ? 0 : agent.x(), agent == null ? 0 : agent.z(), agent == null ? 0 : invSig(agent), 0));
        while (pending.size() > 50) pending.pollFirst();
    }

    /** Judge decisions old enough (every autopilot round). */
    static void judge(List<Agent> crew) {
        load();
        long now = System.currentTimeMillis();
        boolean changed = false;
        List<Pending> due = new ArrayList<>();
        for (var it = pending.iterator(); it.hasNext(); ) { Pending q = it.next(); if (now - q.at() >= waitFor(q.metric())) { due.add(q); it.remove(); } }
        for (Pending p : due) {
            Agent a = p.agent() == null ? null : AgentNetClient.AGENTS.get(p.agent().toLowerCase());
            boolean ok;
            String why;
            if (!p.metric().isEmpty()) {
                int after = measure(crew, p.metric());
                ok = after > p.before();
                why = p.metric() + " " + p.before() + " -> " + after;
                // Nothing to show yet, but the agent is at it (moved, dug, picked things up): give it more time, twice.
                if (!ok && a != null && a.isOnline() && p.extensions() < 2 && (Math.hypot(a.x() - p.x(), a.z() - p.z()) > 4 || invSig(a) != p.invSig())) {
                    pending.add(new Pending(p.key(), p.agent(), p.metric(), p.before(), now, a.x(), a.z(), invSig(a), p.extensions() + 1));
                    AgentNet.LOG.info("[Civ] learning: {} busy but no {} yet, giving it more time", p.key(), p.metric());
                    continue;
                }
            } else if (a == null || !a.isOnline()) {
                ok = false;
                why = "the agent is gone";
            } else {
                boolean moved = Math.hypot(a.x() - p.x(), a.z() - p.z()) > 4;
                ok = moved || invSig(a) != p.invSig();
                why = ok ? "it got busy" : "it didn't move or pick anything up";
            }
            if (a != null && !a.isOnline()) { ok = false; why = "the agent is gone"; }
            Score s = scores.computeIfAbsent(p.key(), k -> new Score());
            s.tries++;
            if (ok) s.worked++;
            s.value = s.value * 0.6 + (ok ? 0.4 : 0);
            if (!ok) s.lastWhy = why;
            if (!ok && s.tries >= MIN_TRIES && s.value < HOLD_BELOW) {
                s.holdUntil = now + HOLD_MS;
                Civ.decideQuiet("learned: " + p.key() + " keeps failing here (" + s.worked + "/" + s.tries + " worked, last: " + why + "): on hold for 20 min");
            } else {
                AgentNet.LOG.info("[Civ] learned: {} {} ({})", p.key(), ok ? "worked" : "failed", why);
            }
            changed = true;
        }
        if (changed) save();
    }

    /** False while this kind of job is on hold (it kept failing here). */
    static boolean allowed(Role role, String task) {
        load();
        Score s = scores.get(key(role, task));
        return s == null || System.currentTimeMillis() >= s.holdUntil;
    }

    /** How well this kind of job has gone here, 0..1 (0.5 when it's new). */
    static double score(Role role, String task) {
        load();
        Score s = scores.get(key(role, task));
        return s == null ? 0.5 : s.value;
    }

    /** One line per job kind, for the summary the master model reads. */
    static String lessons() {
        load();
        if (scores.isEmpty()) return "";
        List<String> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (var e : scores.entrySet()) {
            Score s = e.getValue();
            String line = e.getKey() + " " + s.worked + "/" + s.tries + " worked";
            if (now < s.holdUntil) line += " (ON HOLD: keeps failing, " + s.lastWhy + ")";
            else if (s.tries > s.worked && !s.lastWhy.isEmpty()) line += " (last failure: " + s.lastWhy + ")";
            out.add(line);
        }
        return "Lessons from earlier decisions: " + String.join("; ", out) + ".";
    }

    // ------------------------------------------------------------------ file

    private static Path file() { return AgentFiles.dir().resolve("civ-learning.txt"); }

    private static void load() {
        String server = dev.agentnet.map.Servers.current();
        if (server == null || server.equals(loadedFor)) return;
        loadedFor = server;
        scores.clear();
        pending.clear();
        try {
            if (!Files.exists(file())) return;
            for (String line : Files.readAllLines(file(), StandardCharsets.UTF_8)) {
                String[] p = line.split("\\|", -1);
                if (p.length < 6 || line.startsWith("#") || !p[0].equals(server)) continue;
                Score s = new Score();
                s.value = Double.parseDouble(p[2]);
                s.tries = Integer.parseInt(p[3]);
                s.worked = Integer.parseInt(p[4]);
                s.lastWhy = p[5];
                scores.put(p[1], s);
            }
        } catch (IOException | RuntimeException e) {
            AgentNet.LOG.warn("Could not read civ-learning.txt", e);
        }
    }

    private static void save() {
        String server = loadedFor;
        if (server == null) return;
        List<String> lines = new ArrayList<>(List.of("# server|job|score|tries|worked|last failure (the autopilot's own lessons)"));
        try {
            if (Files.exists(file())) for (String l : Files.readAllLines(file(), StandardCharsets.UTF_8)) if (!l.startsWith("#") && !l.startsWith(server + "|")) lines.add(l);
            for (var e : scores.entrySet()) {
                Score s = e.getValue();
                lines.add(server + "|" + e.getKey() + "|" + String.format(java.util.Locale.ROOT, "%.3f", s.value) + "|" + s.tries + "|" + s.worked + "|" + s.lastWhy.replace('|', '/'));
            }
            Files.write(file(), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            AgentNet.LOG.warn("Could not write civ-learning.txt", e);
        }
    }
}
