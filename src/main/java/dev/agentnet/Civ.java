package dev.agentnet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import dev.agentnet.map.Servers;
import dev.agentnet.role.Role;
import net.minecraft.world.item.ItemStack;

/**
 * The civ autopilot: "/agent civ on" and the master agent runs the settlement by itself.
 *
 * <p>A fighting crew (2026-11-21): a storage keeper, distributors that hand out what the real players put in the
 * storage, a cleanup crew for what the dead drop, a builder when something is going up, and warriors in every
 * other place up to the population cap ("/agent civ cap &lt;n&gt;"). A hitlist player near one of ours gets a
 * fighter at once, even at the cap. No speedrunner and no gatherers.
 * Every decision, and why, goes to the master chat. War mode is left to "/agent war".
 */
public final class Civ {
    private static final int ROUND_TICKS = 20 * 20; // every 20 s: a crew of 12 is in within about a minute and a half
    private static final int MAX_ACTIONS_PER_ROUND = 3;

    static boolean enabled;
    static int cap = 10;
    /** Also ask the local model for a longer-term plan every 10 minutes (opt-in: /agent civ think on). */
    static boolean think;
    private static final int THINK_TICKS = 20 * 60 * 10;
    private static int ticks;
    private static final Deque<String> builds = new ArrayDeque<>();
    static final Deque<String> decisions = new ArrayDeque<>();

    private Civ() {}

    static void tick() {
        if (!enabled) return;
        if (think && (ticks + 1) % THINK_TICKS == 600 && System.currentTimeMillis() - MasterAgent.lastPlanAt > 5 * 60_000) MasterAgent.plan(summary(), 3, cap); // 30 s after a rules round
        if (ticks % (20 * 60) == 600) autosave();
        // A first round at once (the speedrunner is hired the moment the world is up: the user, "the speedrunner should
        // spawn right in"), and every 3 s until the starting crew of nine is in, then every 20 s.
        int every = AgentNetClient.agentCount() < 9 ? 60 : ROUND_TICKS;
        if (++ticks % every != 0 && ticks != 2) return;
        try {
            round();
        } catch (RuntimeException e) {
            AgentNet.LOG.warn("civ round failed", e);
        }
    }

    static void on() { enabled = true; ticks = 0; decide("autopilot on (cap " + cap + " agents); first look in 2 s"); writeState(); }
    static void off() { enabled = false; decide("autopilot off"); writeState(); }

    // ------------------------------------------------------------------ surviving a restart
    // Every minute while it runs: the crew (names, roles, tasks) to saves/civ.txt and the autopilot's own settings to
    // civ-state.txt. The server keeps each agent's inventory and position by name, the base and chests are in their own
    // files, so the next game start loads the same crew back and carries on. "/agent civ off" stops that.

    private static java.nio.file.Path stateFile() { return AgentFiles.dir().resolve("civ-state.txt"); }

    private static void writeState() {
        try {
            java.nio.file.Files.writeString(stateFile(), "# AgentNet autopilot: resumed at the next game start while on=true\non=" + enabled + "\ncap=" + cap + "\nthink=" + think + "\n");
        } catch (java.io.IOException e) { AgentNet.LOG.warn("could not write {}", stateFile(), e); }
    }

    private static void autosave() {
        List<Agent> crew = new ArrayList<>();
        for (Agent a : AgentNetClient.AGENTS.values()) if (a.isOnline()) crew.add(a);
        if (crew.isEmpty()) return;
        // Merged with the last save, never just overwritten: agents knocked offline for a moment (a connection reset
        // took 16 of 25 at once) keep their place and come back with the next load.
        try {
            java.util.Map<String, String> lines = new java.util.LinkedHashMap<>();
            if (Saves.exists("civ")) for (Saves.Entry e : Saves.read("civ"))
                lines.put(e.name().toLowerCase(), e.name() + "|" + (e.role() == null ? "none" : e.role().id()) + "|" + e.importance() + "|" + e.task());
            for (Agent a : crew) {
                String task = a.task == null ? "idle" : a.task.replace('|', ' ').replace('\n', ' ');
                lines.put(a.name.toLowerCase(), a.name + "|" + (a.role() == null ? "none" : a.role().id()) + "|" + a.importance + "|" + task);
            }
            List<String> out = new ArrayList<>(List.of("# AgentNet crew save: name|role|importance|task"));
            out.addAll(lines.values());
            java.nio.file.Files.write(Saves.file("civ"), out, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) { AgentNet.LOG.warn("civ autosave failed", e); return; }
        writeState();
        AgentNet.LOG.info("[Civ] autosaved {} agents", crew.size());
    }

    /** Lines for the start-up script: the saved crew back and the autopilot on again. Empty unless it was running. */
    static List<String> resumeScript() {
        try {
            if (!java.nio.file.Files.exists(stateFile()) || !Saves.exists("civ")) return List.of();
            java.util.Map<String, String> kv = new java.util.HashMap<>();
            for (String l : java.nio.file.Files.readAllLines(stateFile())) { int i = l.indexOf('='); if (i > 0 && !l.startsWith("#")) kv.put(l.substring(0, i).trim(), l.substring(i + 1).trim()); }
            if (!"true".equals(kv.get("on"))) return List.of();
            AgentNet.LOG.info("[Civ] resuming the saved crew (cap {})", kv.getOrDefault("cap", "?"));
            // The cap straight away, not by a command: a reconnect while the start-up script runs loses commands.
            try { cap = Integer.parseInt(kv.getOrDefault("cap", "" + cap)); } catch (NumberFormatException ignored) {}
            // Just the autopilot: the saved crew comes back one agent at a time, as jobs need them (see rejoin).
            return List.of("log ===== autopilot back on (the saved crew rejoins as jobs need them) =====",
                    "/agent civ cap " + kv.getOrDefault("cap", "30"), "/agent civ on");
        } catch (java.io.IOException e) { return List.of(); }
    }
    static void queueBuild(String schematic) { builds.add(schematic); decide("queued schematic '" + schematic + "' for builders"); }

    // ------------------------------------------------------------------ one round

    /** The crew aims for a 15% oversupply of everything: a target n becomes n + 15%, rounded up. */
    public static final double OVERSUPPLY = 1.15;
    public static int over(int n) { return n <= 0 ? n : (int) Math.ceil(n * OVERSUPPLY - 1e-9); }

    private record Need(Role role, String task, String why) {}

    /** Agents stuck on the job right now (not counted for their role), and those it has said so about. */
    private static final java.util.Set<String> stalled = new java.util.HashSet<>(), stalledSaid = new java.util.HashSet<>();

    // The fighting crew (the user, 2026-11-21: "let's just focus on fighting"). No speedrunner and no gatherers: the real
    // players bring the materials, the storage keeper keeps them, the distributors hand them out (gear and food for the
    // warriors first), a cleanup crew takes what the dead drop, and every other place up to the cap is a warrior. A
    // builder only for a base or a schematic that is going up.
    private static final Role[] STARTERS = {Role.KEEPER, Role.DISTRIBUTOR, Role.WARRIOR, Role.WARRIOR, Role.PICKUP, Role.WARRIOR};

    /** The next of the starting crew still missing, or null once they are all in (or the crew is past them). */
    private static Need starterNeed(int crewSize, Map<Role, Integer> roles) {
        if (crewSize >= STARTERS.length + 3) return null; // well past the start: by need only
        Map<Role, Integer> want = new EnumMap<>(Role.class);
        for (Role r : STARTERS) {
            int w = want.merge(r, 1, Integer::sum);
            int have = roles.getOrDefault(r, 0);
            if (have >= w) continue;
            return switch (r) {
                case KEEPER -> new Need(r, null, "the starting crew: a storage keeper for the base");
                case WARRIOR -> new Need(r, null, "the starting crew: warrior " + (have + 1) + " of 3");
                case PICKUP -> new Need(r, null, "the starting crew: a cleanup crew for what the dead drop");
                default -> new Need(r, null, "the starting crew: a distributor to hand out the gear");
            };
        }
        return null;
    }

    /** Needs that go before the rest: a hitlist player about, the starting crew, the storage. */
    private static boolean first(Need n) {
        return n.role() == Role.WARRIOR && n.why().contains("(hitlist)") || n.why().startsWith("the starting crew") || n.role() == Role.KEEPER;
    }

    private static void round() {
        String server = Servers.current();
        if (server == null) return;
        List<Agent> crew = new ArrayList<>(AgentNetClient.AGENTS.values());
        crew.removeIf(a -> !a.isOnline());
        CivLearning.judge(crew);
        Map<Role, Integer> roles = new EnumMap<>(Role.class);
        for (Agent a : crew) if (a.role() != null) roles.merge(a.role(), 1, Integer::sum);
        // A distributor or the cleanup crew stuck for two minutes (not moved, dug or had its pack change, not waiting on
        // purpose): not counted, so another is brought in.
        stalled.clear();
        for (Agent a : crew) {
            Role r = a.role();
            if (r != Role.DISTRIBUTOR && r != Role.PICKUP) continue;
            if (!AfkChecker.afk(a.name) || a.dugIn() || a.onlineFor() < 150_000) continue;
            String st = String.valueOf(a.moduleStatus());
            if (st.contains("waiting") || st.contains("nothing")) continue;
            if (r == Role.DISTRIBUTOR && st.contains(" 0 pickup(s), 0 deliver(ies)")) continue; // nothing asked of it
            stalled.add(a.name);
            roles.merge(r, -1, Integer::sum);
            if (stalledSaid.add(a.name)) decide(a.name + " (" + r.id() + ") has been stuck for " + AfkChecker.idleMs(a.name) / 60_000 + " min (" + st + "): not counted as a " + r.id() + " until it moves again");
        }
        stalledSaid.retainAll(stalled);
        roles.values().removeIf(v -> v <= 0);
        // Hired but still connecting (up to 2 minutes), or out for a minute because of mobs: their jobs are taken.
        long nowJ = System.currentTimeMillis();
        joining.entrySet().removeIf(e -> nowJ - e.getValue().at() > 120_000 || AgentNetClient.AGENTS.containsKey(e.getKey()) && AgentNetClient.AGENTS.get(e.getKey()).isOnline());
        for (Joining j : joining.values()) roles.merge(j.role(), 1, Integer::sum);
        Agent.AWAY.values().removeIf(v -> System.currentTimeMillis() > (Long) v[1]);
        for (var e : Agent.AWAY.entrySet()) { Agent aw = AgentNetClient.AGENTS.get(e.getKey()); if ((aw == null || !aw.isOnline()) && e.getValue()[0] != null) roles.merge((Role) e.getValue()[0], 1, Integer::sum); }

        List<Need> needs = new ArrayList<>();
        int warriors = roles.getOrDefault(Role.WARRIOR, 0);
        // 1. A hitlist player near one of ours and too few fighters to meet it: one more, even at the cap.
        for (Agent a : crew) {
            var enemy = a.entitiesNear(32).stream().filter(s -> s.player() && a.isEnemy(s.name())).findFirst();
            if (enemy.isPresent() && warriors < Math.max(1, crew.size() / 2)) { needs.add(new Need(Role.WARRIOR, null, enemy.get().name() + " (hitlist) is near " + a.name)); break; }
        }
        // 2. The storage: one keeper.
        if (roles.getOrDefault(Role.KEEPER, 0) == 0 && crew.size() >= 2) needs.add(new Need(Role.KEEPER, null, "the base's storage has no keeper"));
        // 3. Distributors hand out what the players put in storage: one per 6 of the crew, 1 to 4.
        int carriers = roles.getOrDefault(Role.DISTRIBUTOR, 0);
        int wantDistributors = Math.max(1, Math.min(4, crew.size() / 6 + 1));
        if (carriers < wantDistributors) needs.add(new Need(Role.DISTRIBUTOR, null, crew.size() + " agents to supply from the storage: " + wantDistributors + " distributor(s) wanted"));
        int backlog = 0;
        for (Agent a : crew) {
            if (a.role() != Role.DISTRIBUTOR || stalled.contains(a.name)) continue;
            java.util.regex.Matcher mt = java.util.regex.Pattern.compile("([0-9]+) pickup[(]s[)], ([0-9]+) deliver").matcher(String.valueOf(a.moduleStatus()));
            if (mt.find()) backlog += Integer.parseInt(mt.group(1)) + Integer.parseInt(mt.group(2));
        }
        if (carriers > 0 && carriers >= wantDistributors && backlog > 6 * carriers)
            needs.add(new Need(Role.DISTRIBUTOR, null, backlog + " pick-ups and deliveries waiting on " + carriers + " distributor(s): too much for them"));
        // 4. The cleanup crew: what the dead drop (ours, the enemies', mobs'), one per 8 warriors.
        int wantCleanup = Math.max(1, (warriors + 7) / 8);
        if (crew.size() >= 3 && roles.getOrDefault(Role.PICKUP, 0) < wantCleanup) needs.add(new Need(Role.PICKUP, null, warriors + " warriors fighting: " + wantCleanup + " on cleanup wanted"));
        // 5. Building: a schematic queued, or a base going up (from what the players bring).
        String dim = "minecraft:overworld";
        for (Agent a : crew) if (a.dimension() != null && a.isOnline()) { dim = a.dimension(); break; }
        boolean hasBase = dev.agentnet.role.Bases.crew(server, dim) != null;
        boolean baseBuilt = dev.agentnet.role.Bases.crewBuilt(server, dim) != null;
        if (!builds.isEmpty() && crew.stream().noneMatch(a -> a.role() == Role.BUILDER && a.task.startsWith("build")))
            needs.add(new Need(Role.BUILDER, null, "schematic '" + builds.peekFirst() + "' is waiting"));
        else if (hasBase && !baseBuilt && roles.getOrDefault(Role.BUILDER, 0) == 0)
            needs.add(new Need(Role.BUILDER, null, "the base is not finished and nobody is building it"));
        // 6. The army: every other place up to the cap.
        int support = crew.size() - warriors;
        if (warriors < cap - support) needs.add(new Need(Role.WARRIOR, null, "the army: " + warriors + " warrior(s), room for " + (cap - support - warriors) + " more"));

        Need starter = starterNeed(crew.size(), roles);
        if (starter != null) { needs.removeIf(x -> !(x.role() == Role.WARRIOR && x.why().contains("(hitlist)"))); needs.add(starter); }
        List<Need> front = new ArrayList<>(), rest = new ArrayList<>();
        for (Need n : needs) (first(n) ? front : rest).add(n);
        front.sort(java.util.Comparator.comparingInt((Need n) -> n.why().contains("(hitlist)") ? 0 : 1));
        needs.clear();
        needs.addAll(front);
        needs.addAll(rest);
        pickedThisRound.clear();
        int actions = 0;
        for (Need n : needs) {
            if (actions >= MAX_ACTIONS_PER_ROUND) break;
            if (fill(n, crew)) actions++;
        }
        if (needs.isEmpty() && ticks % (ROUND_TICKS * 10) == 0) decide("all needs covered (" + crew.size() + " agents)");
    }

    private static final java.util.Set<String> pickedThisRound = new java.util.HashSet<>();

    /** Give the job to an agent without a role, else bring a saved one back, else a new one joins (up to the cap). */
    private static boolean fill(Need n, List<Agent> crew) {
        Agent pick = null;
        for (Agent a : crew) {
            if (pickedThisRound.contains(a.name)) continue;
            if (a.role() == null) { pick = a; break; }
        }
        // A hitlist player about: the cleanup crew or a builder fights first, then a spare distributor (never the last one).
        if (pick == null && n.role() == Role.WARRIOR && n.why().contains("(hitlist)")) {
            long dists = crew.stream().filter(x -> x.role() == Role.DISTRIBUTOR).count();
            for (Role r : List.of(Role.PICKUP, Role.BUILDER, Role.MAKER, Role.DISTRIBUTOR)) {
                if (r == Role.DISTRIBUTOR && dists <= 1) continue;
                for (Agent a : crew) { if (pickedThisRound.contains(a.name) || a.role() != r) continue; pick = a; break; }
                if (pick != null) break;
            }
        }
        if (pick != null) {
            pickedThisRound.add(pick.name);
            decide(n.why() + " -> " + pick.name + " becomes a " + n.role().id() + (n.task() == null ? "" : " (" + n.task() + ")"));
            if (pick.role() != n.role()) MasterAgent.run("agent role " + pick.name + " " + n.role().id());
            runTask(pick.name, n);
            CivLearning.decided(n.role(), n.task(), pick, crew);
            return true;
        }
        if (AgentNetClient.AGENTS.size() >= cap) { // joining agents count too
            Agent spare = surplus(crew, n);
            if (spare != null) {
                decide(n.why() + " -> " + spare.name + " moves from " + spare.role().id() + " to " + n.role().id());
                MasterAgent.run("agent role " + spare.name + " " + n.role().id());
                runTask(spare.name, n);
                CivLearning.decided(n.role(), n.task(), spare, crew);
                return true;
            }
            quiet(n.why() + ", but the crew is at its cap of " + cap + " (raise it: /agent civ cap <n>)");
            return false;
        }
        String back = rejoinCandidate(n.role());
        if (back != null) {
            rejoined.put(back.toLowerCase(), System.currentTimeMillis());
            joining.put(back.toLowerCase(), new Joining(n.role(), System.currentTimeMillis()));
            decide(n.why() + " -> " + back + " (saved) comes back as a " + n.role().id());
            MasterAgent.run("agent join " + back);
            MasterAgent.run("agent role " + back + " " + n.role().id());
            CivLearning.decided(n.role(), n.task(), null, crew);
            if (n.task() != null || n.role() == Role.BUILDER) pendingTasks.add(n);
            return true;
        }
        decide(n.why() + " -> a new " + n.role().id() + " joins");
        joining.put("new#" + System.nanoTime(), new Joining(n.role(), System.currentTimeMillis()));
        MasterAgent.run("agent spawn 1 " + n.role().id());
        CivLearning.decided(n.role(), n.task(), null, crew);
        if (n.task() != null || n.role() == Role.BUILDER) pendingTasks.add(n);
        return true;
    }

    private record Joining(Role role, long at) {}
    /** Agents hired and still connecting (name, or "new#..." for a newcomer without a name yet): their role counts as filled. */
    private static final Map<String, Joining> joining = new java.util.concurrent.ConcurrentHashMap<>();

    /** Saved agents brought back lately (name -> when): not asked again for 2 minutes while they join. */
    private static final Map<String, Long> rejoined = new java.util.HashMap<>();

    /** A saved agent that is not online: one whose saved role is this one first, else any (a saved role that is gone counts as none). */
    private static String rejoinCandidate(Role role) {
        try {
            if (!Saves.exists("civ")) return null;
            long now = System.currentTimeMillis();
            String any = null;
            for (Saves.Entry e : Saves.read("civ")) {
                String k = e.name().toLowerCase();
                if (AgentNetClient.AGENTS.containsKey(k) || AgentNetClient.RETIRED.contains(k)) continue;
                Long t = rejoined.get(k);
                if (t != null && now - t < 120_000) continue;
                if (e.role() == role) return e.name();
                if (any == null && (e.role() == null || !KEEPS_ROLE.contains(e.role()))) any = e.name();
            }
            return any;
        } catch (java.io.IOException ex) { return null; }
    }

    private static final java.util.Set<Role> KEEPS_ROLE = java.util.EnumSet.of(Role.WARRIOR, Role.BUILDER, Role.KEEPER);

    /** At the cap: a third distributor, cleanup crew beyond what the warriors need, or a builder with nothing to build. */
    private static Agent surplus(List<Agent> crew, Need need) {
        Map<Role, Integer> count = new EnumMap<>(Role.class);
        for (Agent a : crew) if (a.role() != null) count.merge(a.role(), 1, Integer::sum);
        int warriors = count.getOrDefault(Role.WARRIOR, 0);
        for (Agent a : crew) {
            Role r = a.role();
            if (r == null || r == need.role()) continue;
            if (r == Role.DISTRIBUTOR && count.getOrDefault(r, 0) > 2) return a;
            if (r == Role.PICKUP && count.getOrDefault(r, 0) > Math.max(1, (warriors + 7) / 8)) return a;
            if (r == Role.BUILDER && builds.isEmpty() && a.moduleIdle()) return a;
        }
        return null;
    }

    private static final Deque<Need> pendingTasks = new ArrayDeque<>();

    private static void runTask(String agent, Need n) {
        if (n.role() == Role.BUILDER) {
            String s = builds.pollFirst();
            if (s != null) MasterAgent.run("agent build " + agent + " " + s);
        } else if (n.task() != null) {
            String[] p = n.task().split(" ", 2);
            MasterAgent.run("agent " + p[0] + " " + agent + (p.length > 1 ? " " + p[1] : ""));
        }
    }

    /** New agents get their job once they're online with their role (checked every second). */
    static void tickPending() {
        if (pendingTasks.isEmpty() || ++pendingTicks % 20 != 0) return;
        Need n = pendingTasks.peekFirst();
        for (Agent a : AgentNetClient.AGENTS.values()) {
            if (a.isOnline() && a.onlineFor() > 10_000 && a.role() == n.role() && a.task.equals("idle")) {
                pendingTasks.pollFirst();
                runTask(a.name, n);
                return;
            }
        }
        if (pendingTicks > 20 * 90) { pendingTasks.pollFirst(); pendingTicks = 0; }
    }

    private static int pendingTicks;

    private static final Map<String, Integer> quietAt = new java.util.HashMap<>();

    /** A decision line that would repeat every round: say it at most every 10 minutes. */
    /** A learning note, said at most every 10 minutes. */
    static void decideQuiet(String line) { quiet(line); }

    private static void quiet(String line) {
        Integer at = quietAt.get(line);
        if (at != null && ticks - at < 20 * 60 * 10) return; // 10 minutes
        quietAt.put(line, ticks);
        decide(line);
    }

    private static int countHeld(List<Agent> agents, java.util.function.Predicate<String> id) {
        int n = 0;
        for (Agent a : agents) for (ItemStack s : a.inventory()) if (!s.isEmpty() && id.test(dev.agentnet.role.Gear.id(s))) n += s.getCount();
        return n;
    }

    private static int countMatching(List<Agent> crew, java.util.function.Predicate<String> id) {
        int n = 0;
        for (Agent a : crew) for (ItemStack s : a.inventory()) if (!s.isEmpty() && id.test(dev.agentnet.role.Gear.id(s))) n += s.getCount();
        for (List<ItemStack> chest : dev.agentnet.role.ChestMemory.CONTENTS.values()) {
            for (ItemStack s : chest) if (!s.isEmpty() && id.test(dev.agentnet.role.Gear.id(s))) n += s.getCount();
        }
        return n;
    }

    static int count(List<Agent> crew, String id) {
        int n = 0;
        for (Agent a : crew) for (ItemStack s : a.inventory()) if (!s.isEmpty() && dev.agentnet.role.Gear.id(s).equals(id)) n += s.getCount();
        for (List<ItemStack> chest : dev.agentnet.role.ChestMemory.CONTENTS.values()) {
            for (ItemStack s : chest) if (!s.isEmpty() && dev.agentnet.role.Gear.id(s).equals(id)) n += s.getCount();
        }
        return n;
    }

    private static void decide(String line) {
        decisions.addLast(line);
        while (decisions.size() > 30) decisions.pollFirst();
        MasterAgent.say("§bCiv: §f" + line);
        AgentNet.LOG.info("[Civ] {}", line);
    }

    /** One paragraph for "/agent civ status" (and for the master agent to reason about). */
    static String summary() {
        String server = Servers.current();
        List<Agent> crew = new ArrayList<>(AgentNetClient.AGENTS.values());
        Map<Role, Integer> roles = new EnumMap<>(Role.class);
        int noRole = 0;
        for (Agent a : crew) { if (a.role() == null) noRole++; else roles.merge(a.role(), 1, Integer::sum); }
        StringBuilder sb = new StringBuilder();
        sb.append("Autopilot ").append(enabled ? "ON" : "off").append(", cap ").append(cap).append(". Crew ").append(crew.size()).append(": ");
        roles.forEach((r, c) -> sb.append(c).append(" ").append(r.id()).append(", "));
        if (noRole > 0) sb.append(noRole).append(" without a role, ");
        sb.append("Iron ").append(count(crew, "minecraft:iron_ingot")).append(", diamonds ").append(count(crew, "minecraft:diamond"))
          .append(", food ").append(countMatching(crew, id -> id.contains("bread") || id.contains("cooked") || id.contains("golden_apple") || id.contains("steak"))).append(" (in the crew and the storage; the players bring the materials).");
        if (!builds.isEmpty()) sb.append(" Queued builds: ").append(String.join(", ", builds)).append(".");
        sb.append(" Agents: ");
        sb.append(String.join(", ", crew.stream().map(a -> a.name + " (" + (a.role() == null ? "no role" : a.role().id())
                + (a.task.equals("idle") ? "" : ", " + a.task) + ")").toList()));
        sb.append(".");
        String lessons = CivLearning.lessons();
        if (!lessons.isEmpty()) sb.append(" ").append(lessons);
        return sb.toString();
    }
}
