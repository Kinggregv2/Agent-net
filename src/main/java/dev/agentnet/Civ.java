package dev.agentnet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import dev.agentnet.map.Farms;
import dev.agentnet.map.Servers;
import dev.agentnet.map.Spots;
import dev.agentnet.role.Role;
import net.minecraft.world.item.ItemStack;

/**
 * The civ autopilot: "/agent civ on" and the master agent runs the settlement by itself.
 *
 * <p>Once a minute it looks at the whole crew (roles, what everyone carries, what the
 * storage chests held last time, farms and fishing spots you set up, threats) and works
 * down a fixed list of needs, most important first:
 * <ol>
 *   <li>a warrior when an enemy (hitlist player) is near any agent and nobody guards;</li>
 *   <li>a distributor as soon as there are gatherers to collect from;</li>
 *   <li>food: one farmer per farm area, one fisher per fishing spot (only with a spare rod);</li>
 *   <li>mining only for requests (the base, the guards' gear, the portal); miners make their own
 *       pickaxes on the way (wood, stone, iron, diamond), and a miner with no request goes "bored"
 *       and mines whatever useful ore is near;</li>
 *   <li>builders for schematics you queued with "/agent civ build &lt;name&gt;".</li>
 * </ol>
 * It fills one or two needs per round: agents without a role get the job first, then new
 * agents join, never more than the population cap ("/agent civ cap &lt;n&gt;", default 10).
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

    /** A job for the speedrunner (top priority until the dragon is dead). */
    private static boolean runnerNeed(Need n) { return n.why().startsWith("the speedrunner needs ") && n.why().contains("iron"); }

    private static final String HUNGRY = "hungry: ";
    /** Agents starving with nobody making food: before the base. */
    private static boolean hungerNeed(Need n) { return n.why().startsWith(HUNGRY); }

    /** The storage keeper and the base itself (its builder and its materials): before guards, gear and the rest. */

    private static final Role[] STARTERS = {Role.SPEEDRUNNER, Role.MINER, Role.BUILDER, Role.DISTRIBUTOR,
            Role.SPEEDRUNNER, Role.MINER, Role.SPEEDRUNNER, Role.MINER, Role.SPEEDRUNNER};

    /** The next of the starting crew still missing, or null once all nine are in (or the crew is past them). */
    private static Need starterNeed(int crewSize, Map<Role, Integer> roles) {
        if (crewSize >= STARTERS.length + 3) return null; // well past the start: by need only
        Map<Role, Integer> want = new EnumMap<>(Role.class);
        for (Role r : STARTERS) {
            want.merge(r, 1, Integer::sum);
            int have = roles.getOrDefault(r, 0) + (r == Role.MINER ? 0 : 0);
            if (have >= want.get(r)) continue;
            return switch (r) {
                case SPEEDRUNNER -> new Need(r, "speedrun", have == 0 ? "a runner to beat the game: its own portal into the nether, then the End"
                        : "the starting crew: a speedrun helper (" + have + " of 4 runners)");
                case MINER -> new Need(r, "mine 64 stone", "the starting crew: miner " + (have + 1) + " of 3");
                case BUILDER -> new Need(r, null, "the starting crew: a builder for the base");
                default -> new Need(r, null, "the starting crew: a distributor");
            };
        }
        return null;
    }
    private static boolean first(Need n) {
        return n.role() == Role.SPEEDRUNNER || n.role() == Role.RESCUER || n.role() == Role.FALLOFF || runnerNeed(n) || hungerNeed(n) || n.role() == Role.KEEPER || n.role() == Role.BUILDER || n.role() == Role.MINER && n.why().startsWith("the base needs")
                || n.role() == Role.DISTRIBUTOR && n.why().startsWith("the base is going up")
                || n.role() == Role.EXPLORER && "scout".equals(n.task()) // the lava scout: for the speedrunners, not learned like the rest
                || n.role() == Role.WARRIOR && n.why().contains("(hitlist)") // a hitlist player near one of ours: a fighter even at the cap (run 1120v test)
                || n.why().startsWith("the starting crew"); // the first nine, in their order
    }

    private static void round() {
        String server = Servers.current();
        if (server == null) return;
        List<Agent> crew = new ArrayList<>(AgentNetClient.AGENTS.values());
        crew.removeIf(a -> !a.isOnline());
        CivLearning.judge(crew); // how did the jobs handed out 5 minutes ago go?
        Map<Role, Integer> roles = new EnumMap<>(Role.class);
        for (Agent a : crew) if (a.role() != null) roles.merge(a.role(), 1, Integer::sum);
        // Stuck on the job: not counted for its role, so the rules below bring in another (the user, 2026-11-19, watching one
        // distributor serve the whole crew while the other stood stuck: "detect when there is not enough of a specific
        // role"). Stuck = it has not moved, dug or had its pack change for two minutes, it is not dug in for the night, and
        // it is not waiting on purpose. Only the roles that supply the crew: more runners or guards are not the answer to
        // a stuck one.
        stalled.clear();
        for (Agent a : crew) {
            Role r = a.role();
            if (r != Role.DISTRIBUTOR && r != Role.MINER && r != Role.HUNTER && r != Role.FARMER && r != Role.FISHER) continue;
            if (!AfkChecker.afk(a.name) || a.dugIn() || a.onlineFor() < 150_000) continue;
            String st = String.valueOf(a.moduleStatus());
            if (st.contains("waiting")) continue;
            if (r == Role.DISTRIBUTOR && st.contains(" 0 pickup(s), 0 deliver(ies)")) continue; // nothing asked of it
            stalled.add(a.name);
            roles.merge(r, -1, Integer::sum);
            if (stalledSaid.add(a.name)) decide(a.name + " (" + r.id() + ") has been stuck for " + AfkChecker.idleMs(a.name) / 60_000 + " min (" + st + "): not counted as a " + r.id() + " until it moves again");
        }
        stalledSaid.retainAll(stalled);
        roles.values().removeIf(v -> v <= 0);
        // Hired but still connecting (up to 2 minutes): their jobs are taken. Counting only the agents already in the game
        // hired a second speedrunner while the first was joining, and one of them was later moved to another job.
        long nowJ = System.currentTimeMillis();
        joining.entrySet().removeIf(e -> nowJ - e.getValue().at() > 120_000 || AgentNetClient.AGENTS.containsKey(e.getKey()) && AgentNetClient.AGENTS.get(e.getKey()).isOnline());
        for (Joining j : joining.values()) roles.merge(j.role(), 1, Integer::sum);
        // Out of the game for a minute because of mobs: still the crew's, its job is taken (no replacement hired).
        Agent.AWAY.values().removeIf(v -> System.currentTimeMillis() > (Long) v[1]);
        for (var e : Agent.AWAY.entrySet()) { Agent aw = AgentNetClient.AGENTS.get(e.getKey()); if ((aw == null || !aw.isOnline()) && e.getValue()[0] != null) roles.merge((Role) e.getValue()[0], 1, Integer::sum); }

        List<Need> needs = new ArrayList<>();
        // 1. Threats.
        if (roles.getOrDefault(Role.WARRIOR, 0) == 0) {
            for (Agent a : crew) {
                var enemy = a.entitiesNear(32).stream().filter(s -> s.player() && a.isEnemy(s.name())).findFirst();
                if (enemy.isPresent()) { needs.add(new Need(Role.WARRIOR, null, enemy.get().name() + " (hitlist) is near " + a.name)); break; }
            }
        }
        // 2. Somebody to collect from gatherers.
        int gatherers = roles.getOrDefault(Role.FARMER, 0) + roles.getOrDefault(Role.FISHER, 0) + roles.getOrDefault(Role.MINER, 0);
        if (roles.getOrDefault(Role.DISTRIBUTOR, 0) == 0 && (gatherers > 0 || crew.size() >= 2)) {
            needs.add(new Need(Role.DISTRIBUTOR, null, gatherers + " gatherer(s) and nobody to collect and hand out supplies"));
        }
        // 3. Food.
        // Farmers make their own field when none is set up, fishers fish any open water: one of each at least.
        int farms = Math.max(crew.size() >= 3 ? 1 : 0, Farms.list(server).size());
        if (roles.getOrDefault(Role.FARMER, 0) < farms) needs.add(new Need(Role.FARMER, null, farms + " farm area(s), only " + roles.getOrDefault(Role.FARMER, 0) + " farmer(s)"));
        long spots = Math.max(1, Spots.list(server).stream().filter(s -> s.type() == Spots.Type.FISHING).count());
        int fishers = roles.getOrDefault(Role.FISHER, 0);
        // A fisher without a rod just stands there: only add one when the crew has a spare rod
        // (fishers already carry theirs, so rods beyond the fisher count are spare).
        int spareRods = count(crew, "minecraft:fishing_rod") - fishers;
        if (fishers < spots && spareRods > 0) needs.add(new Need(Role.FISHER, null, spots + " fishing spot(s), only " + fishers + " fisher(s), and a spare fishing rod"));
        else if (fishers < spots) quiet("fishing spot(s) waiting: no spare fishing rod in the crew or chests");
        // 4./5. Resources. Just what the base needs: each miner makes its own pickaxes on the way
        // (logs -> wooden -> stone -> iron -> diamond), so an empty-handed miner sent for iron is fine.
        int iron = count(crew, "minecraft:iron_ingot") + count(crew, "minecraft:raw_iron") + count(crew, "minecraft:iron_ore");
        int diamonds = count(crew, "minecraft:diamond");
        // Mining is for requests only: the base's materials (the builder), the guards' gear (below), the
        // portal. Miners make their own pickaxes on the way. A miner with no request is "bored" and mines
        // whatever useful ore is near (MinerModule), so nothing here sends one out just in case.
        // 6. Map: no explorer by default. The crew finds what it needs where it works, and an explorer
        //    took a crew slot to give up on far chunks. (A job that really needs one, like finding a
        //    stronghold, asks for it itself; /agent role <name> explorer still works by hand.)
        // 6b. The base going up: what it still needs that no miner is getting (one entry per miner wanted:
        //     a big base gets up to 3 stone miners at once).
        Map<String, Integer> wantMiners = new java.util.HashMap<>();
        for (String t : dev.agentnet.role.Bases.supplyNeeds()) {
            String block = t.split(" ")[1];
            int wanted = wantMiners.merge(block, 1, Integer::sum);
            if (minersOn(crew, block) < wanted) needs.add(new Need(Role.MINER, t, "the base needs " + t.split(" ")[2] + " more " + (block.equals("stone") ? "cobblestone" : block)));
        }
        // 7. Building.
        if (!builds.isEmpty() && crew.stream().noneMatch(a -> a.role() == Role.BUILDER && a.task.startsWith("build"))) {
            needs.add(new Need(Role.BUILDER, null, "schematic '" + builds.peekFirst() + "' is waiting"));
        }

        // 8. The long game: a base, its storage keeper, a nether portal, and an army that grows to the cap,
        //    with the workers it needs growing with it and the gear targets set by how the guards are kitted.
        // The crew's base: one somebody is near (a base left behind far away doesn't count). The keeper
        // and the distributors use the same test, so they all agree on whether there is a base.
        String dim = "minecraft:overworld";
        for (Agent a : crew) if (a.dimension() != null && a.isOnline()) { dim = a.dimension(); break; }
        int warriors = roles.getOrDefault(Role.WARRIOR, 0);
        boolean hasBase = dev.agentnet.role.Bases.crew(server, dim) != null;
        boolean baseBuilt = dev.agentnet.role.Bases.crewBuilt(server, dim) != null;
        if (!hasBase && roles.getOrDefault(Role.BUILDER, 0) == 0) needs.add(new Need(Role.BUILDER, null, "the crew has no base yet"));
        else if (hasBase && !baseBuilt) {
            // One builder per build: extra builders only waited for the same materials.
            int left = dev.agentnet.role.Bases.blocksLeft(), want = 1, have = roles.getOrDefault(Role.BUILDER, 0); // one builder: more never sped it up (materials are the limit)
            if (have < want) needs.add(new Need(Role.BUILDER, null, have == 0 ? "the base isn't finished and nobody is building it" : left + " blocks of the base left: " + want + " builders wanted"));
            // Someone to carry for the base from the start, together with its first miners (not after them).
            if (roles.getOrDefault(Role.DISTRIBUTOR, 0) == 0 && needs.stream().noneMatch(n -> n.role() == Role.DISTRIBUTOR))
                needs.add(new Need(Role.DISTRIBUTOR, null, "the base is going up and nobody carries for it"));
        }
        if (baseBuilt && roles.getOrDefault(Role.KEEPER, 0) == 0 && crew.size() >= 5) needs.add(new Need(Role.KEEPER, null, "the base's storage has no keeper"));
        // Distributors carry everything (tools, food, base materials, gear): one per 4 crew members, up to 4,
        // and more again for a big army.
        // Every target 15% over what is needed (rounded up): the crew aims for an oversupply of everything.
        int wantFarmers = over(1 + warriors / 10), wantDistributors = over(Math.max(Math.min(4, 1 + crew.size() / 4), 1 + warriors / 10)), wantHunters = warriors >= 4 ? over(1) : 0;
        if (roles.getOrDefault(Role.FARMER, 0) < wantFarmers && crew.size() >= 3) needs.add(new Need(Role.FARMER, null, warriors + " guards to feed: " + wantFarmers + " farmer(s) wanted"));
        // One agent beats the game on its own (its own portal, fortress, pearls, the End), from the start.
        // Four on it: one main speedrunner (it carries the rods and pearls and builds the portals) and three helpers that
        // go with it and kill the blazes and endermen for it. The first is hired first of all; the helpers as the crew grows.
        int runners = roles.getOrDefault(Role.SPEEDRUNNER, 0);
        if (runners < 1 || runners < 4 && crew.size() >= 2) needs.add(new Need(Role.SPEEDRUNNER, "speedrun", runners == 0
                ? "a runner to beat the game: its own portal into the nether, then the End"
                : "the speedrunner needs helpers (" + runners + " of 4): blazes and endermen for it"));
        // Falloff speedrunners: two that go with the team and stay at the blaze spawner for two stacks of rods (potions),
        // then come home as brewers. Once the four runners are in, and while the dragon is not dead yet.
        int falloffs = roles.getOrDefault(Role.FALLOFF, 0);
        // Only once the main has its portal up: hired at the start they looked like more helpers (the user counted four)
        // and had nothing to do for 15 minutes.
        if (runners >= 4 && falloffs < 2 && crew.size() >= 6 && dev.agentnet.role.SpeedrunnerModule.priorityRunner() != null && dev.agentnet.role.SpeedrunnerModule.mainAtPortal())
            needs.add(new Need(Role.FALLOFF, "speedrun", "the speedrunners need falloffs (" + falloffs + " of 2): blaze rods for potions, then brewers"));
        // Until the dragon is dead the speedrunner comes first for everyone (the user: "if he happens to need iron, everyone
        // should bend their knees and go get him some iron"): two more on iron for it, taken off other work if need be;
        // the distributors bring it to the main first.
        int runnerIron = dev.agentnet.role.SpeedrunnerModule.crewIronNeed();
        if (runnerIron > 0 && minersOn(crew, "iron_ore") < 2) needs.add(new Need(Role.MINER, "mine iron_ore " + Math.max(4, runnerIron),
                "the speedrunner needs " + runnerIron + " more iron (it comes first until the dragon is dead)"));
        // Structure loot (shipwrecks, ruined portals, villages, temples): iron, gold, obsidian, maps. One looter early on.
        if (crew.size() >= 4 && roles.getOrDefault(Role.LOOTER, 0) < 1) needs.add(new Need(Role.LOOTER, "loot", "structure loot around (shipwrecks, ruined portals, villages): iron, gold, obsidian"));
        // A lava-pool scout (the user, "to add" 2): one explorer from the start whose job is a pool for the speedrunners'
        // portal, so they need not look for one themselves. Only while the main has none yet.
        if (crew.size() >= 5 && roles.getOrDefault(Role.EXPLORER, 0) < 1 && dev.agentnet.role.SpeedrunnerModule.wantsScout())
            needs.add(new Need(Role.EXPLORER, "scout", "the speedrunners have no lava pool yet: a scout to find one"));
        // Drops left lying around (mob loot, tunnel spills, loads nobody fetched): one agent picks them up.
        if (crew.size() >= 12 && roles.getOrDefault(Role.PICKUP, 0) < 1) needs.add(new Need(Role.PICKUP, null, "items left on the ground: one agent to pick them up"));
        // Someone to bring a pickaxe to agents stuck deep underground without one, once the crew is big enough to lose some.
        // One rescuer from the start (the user: "one of the guys that should spawn in right away when autopilot is turned on"):
        // a broken pickaxe at the bottom of a mine strands an agent from the first minutes on.
        // No rescuer any more (the user, 2026-11-19: "get rid of the rescuer, he is no longer needed"): agents without a
        // pickaxe dig themselves out or make their own now. One still in the crew from before goes mining instead.
        for (Agent a : crew) if (a.role() == Role.RESCUER) { decide(a.name + " was the rescuer: that job is gone, it mines now"); AgentNetClient.changeRole(a.name, "miner"); }
        if (roles.getOrDefault(Role.HUNTER, 0) < wantHunters) needs.add(new Need(Role.HUNTER, "hunt", "meat for " + warriors + " guards"));
        // Hungry agents with no food and nobody making any (run 1118d: four runners, two falloffs, a rescuer, a distributor
        // and a builder filled the cap of 10; the farmer waits for the base, so four agents starved and the distributor died
        // asking itself for food): a hunter, before the base and everything after the speedrunners.
        int hungry = 0;
        for (Agent a : crew) if (a.food() < 18 && a.inventory().stream().noneMatch(dev.agentnet.role.Gear::isFood)) hungry++;
        int feeders = roles.getOrDefault(Role.FARMER, 0) + roles.getOrDefault(Role.HUNTER, 0) + roles.getOrDefault(Role.FISHER, 0);
        if (hungry >= 2 && feeders == 0) needs.add(new Need(Role.HUNTER, "hunt", HUNGRY + hungry + " agents with no food and nobody making any"));
        if (roles.getOrDefault(Role.DISTRIBUTOR, 0) > 0 && roles.getOrDefault(Role.DISTRIBUTOR, 0) < wantDistributors) needs.add(new Need(Role.DISTRIBUTOR, null, crew.size() + " agents to supply: " + wantDistributors + " distributors wanted"));
        // One more when those at work cannot keep up: more than six pick-ups and deliveries waiting on each of them.
        int carriers = roles.getOrDefault(Role.DISTRIBUTOR, 0), backlog = 0;
        for (Agent a : crew) {
            if (a.role() != Role.DISTRIBUTOR || stalled.contains(a.name)) continue;
            java.util.regex.Matcher mt = java.util.regex.Pattern.compile("([0-9]+) pickup[(]s[)], ([0-9]+) deliver").matcher(String.valueOf(a.moduleStatus()));
            if (mt.find()) backlog += Integer.parseInt(mt.group(1)) + Integer.parseInt(mt.group(2));
        }
        if (carriers > 0 && backlog > 6 * carriers && needs.stream().noneMatch(n -> n.role() == Role.DISTRIBUTOR))
            needs.add(new Need(Role.DISTRIBUTOR, null, backlog + " pick-ups and deliveries waiting on " + carriers + " distributor(s): too much for them"));
        // Gear: guards without iron armor want iron (24 each: a set and a sword), then diamonds (27 each).
        int noIron = 0, noDiamond = 0;
        for (Agent a : crew) {
            if (a.role() != Role.WARRIOR) continue;
            int ironPieces = 0, diamond = 0;
            for (ItemStack s : a.armor()) { int t = dev.agentnet.role.Gear.tier(s); if (!s.isEmpty() && t >= 4) ironPieces++; if (!s.isEmpty() && t >= 5) diamond++; }
            if (ironPieces < 4) noIron++;
            if (diamond < 4) noDiamond++;
        }
        // Straight to diamond (no iron set first): 27 diamonds per guard without diamond armor (a set and a sword),
        // 2-4 miners on it. Iron only for tools until every guard is in diamond, then for the next guards.
        int ironTarget = over(24 + (noDiamond == 0 ? 24 * noIron : 0)), diamondTarget = over(3 + 27 * noDiamond);
        // Maces need breeze rods and heavy cores: one agent grinds a trial chamber once the guards have their diamonds.
        if (warriors >= 2 && noDiamond == 0 && roles.getOrDefault(Role.TRIAL_GRINDER, 0) < 1) needs.add(new Need(Role.TRIAL_GRINDER, "grind", "breeze rods and heavy cores for maces"));
        int ironMiners = noDiamond == 0 ? Math.min(4, 1 + noIron / 3) : 1, diamondMiners = noDiamond > 0 ? Math.min(6, 3 + noDiamond / 2) : 2;
        // The first guard right after the base: the one to gear up in diamond (the army grows later, with food).
        if (baseBuilt && warriors == 0) needs.add(new Need(Role.WARRIOR, null, "the base is finished: the first guard, to gear up in diamond"));
        if (iron < ironTarget && minersOn(crew, "iron_ore") < ironMiners) needs.add(new Need(Role.MINER, "mine iron_ore 32", iron + " of " + ironTarget + " iron (" + noIron + " guard(s) without iron armor)"));
        if (diamonds < diamondTarget && minersOn(crew, "diamond_ore") < diamondMiners) needs.add(new Need(Role.MINER, "mine diamond_ore 8", diamonds + " of " + diamondTarget + " diamonds (" + noDiamond + " guard(s) without diamond armor)"));
        // Diamonds until the speedrunners beat the game, then netherite (the user, 2026-11-19: "the miners should also go
        // after diamonds until the speedrunners beat the game, then they should go through their portal and start mining
        // netherite"). Up to three miners on each, after the speedrunner's own iron.
        if (dev.agentnet.role.SpeedrunnerModule.gameBeaten()) {
            if (dev.agentnet.role.SpeedrunnerModule.runnersPortal() != null && minersOn(crew, "ancient_debris") < 3)
                needs.add(new Need(Role.MINER, "mine ancient_debris 8", "the game is beaten: through the speedrunners' portal for ancient debris (netherite)"));
        } else if (minersOn(crew, "diamond_ore") < 3) {
            needs.add(new Need(Role.MINER, "mine diamond_ore 8", "diamonds while the speedrunners go for the dragon"));
        }
        // The nether (netherite comes from there): a portal next to the base, once a diamond pickaxe exists.
        if (baseBuilt && countHeld(crew, id -> id.equals("minecraft:diamond_pickaxe") || id.equals("minecraft:netherite_pickaxe")) > 0) {
            dev.agentnet.role.PortalWork.tick(crew);
            for (String t : dev.agentnet.role.PortalWork.needs(crew)) {
                String block = t.split(" ")[1];
                if (!minerOn(crew, block)) needs.add(new Need(Role.MINER, t, "the nether portal needs " + block + " (" + dev.agentnet.role.PortalWork.status + ")"));
            }
            // Through it: netherite starts with ancient debris (diamond pickaxe; the miner goes through the portal itself).
            if (dev.agentnet.role.PortalWork.lit() && minersOn(crew, "ancient_debris") < 2)
                needs.add(new Need(Role.MINER, "mine ancient_debris 4", "the nether portal is lit: ancient debris for netherite"));
        }
        // The army: once the crew can feed and supply itself, every free place up to the cap is a guard.
        boolean economy = roles.getOrDefault(Role.DISTRIBUTOR, 0) > 0 && roles.getOrDefault(Role.MINER, 0) > 0 && roles.getOrDefault(Role.FARMER, 0) + roles.getOrDefault(Role.HUNTER, 0) + roles.getOrDefault(Role.FISHER, 0) > 0;
        int workers = crew.size() - warriors;
        // ...but only guards it can arm: a new one once every guard it has is in diamond (15 bare-handed guards hired
        // in five minutes had the miners digging for 408 diamonds while nothing else moved forward).
        if (economy && noDiamond == 0 && warriors < cap - Math.max(workers, 6)) needs.add(new Need(Role.WARRIOR, null, "growing the army: " + warriors + " guard(s), room for " + (cap - Math.max(workers, 6) - warriors) + " more"));

        // The base comes first: until it's finished, nothing else is hired (no iron, diamonds, guards, farms):
        // only the base's builders and materials, the keeper and the distributors that carry for them.
        // A threat from the hitlist still gets a guard.
        if (!baseBuilt) needs.removeIf(n -> !first(n) && n.role() != Role.DISTRIBUTOR && !n.why().contains("(hitlist)"));

        // The starting crew (the user, 2026-11-20): the main speedrunner among the very first, then a miner, a builder, a
        // distributor, and three helpers and two more miners in turn: 1 + 3 helpers, 3 miners, a distributor and a
        // builder. Until those nine are hired, only they (and a fighter for a hitlist player) are; after that, by need.
        Need starter = starterNeed(crew.size(), roles);
        if (starter != null) { needs.removeIf(x -> !(x.role() == Role.WARRIOR && x.why().contains("(hitlist)"))); needs.add(starter); }
        // Self-improving: jobs that kept failing here are on hold for a while; the rest, best track record
        // first (guards and distributors keep their place in front: they don't produce anything to count).
        needs.removeIf(n -> n.role() != Role.WARRIOR && n.role() != Role.DISTRIBUTOR && !first(n) && !CivLearning.allowed(n.role(), n.task()));
        // The keeper and the base go before everything: without a finished base there is no storage, no keeper
        // and no portal (keeper first: it's one agent and makes the stored materials usable).
        List<Need> keeper = new ArrayList<>();
        List<Need> front = new ArrayList<>(), rest = new ArrayList<>();
        for (Need n : needs) if (first(n)) keeper.add(n);
        needs.removeAll(keeper);
        keeper.sort(java.util.Comparator.comparingInt((Need n) -> n.role() == Role.SPEEDRUNNER ? -4 : n.role() == Role.RESCUER ? -3 : n.role() == Role.FALLOFF ? -2 : runnerNeed(n) || hungerNeed(n) ? -1 : n.role() == Role.KEEPER ? 0 : n.role() == Role.BUILDER ? 1 : n.role() == Role.DISTRIBUTOR ? 2 : 3));
        for (Need n : needs) (n.role() == Role.WARRIOR || n.role() == Role.DISTRIBUTOR ? front : rest).add(n);
        rest.sort(java.util.Comparator.comparingDouble((Need n) -> -CivLearning.score(n.role(), n.task())));
        needs.clear();
        needs.addAll(keeper);
        needs.addAll(front);
        needs.addAll(rest);
        // Idle miners whose job is done: point them at what's short instead of spawning.
        pickedThisRound.clear();
        int actions = 0;
        for (Need n : needs) {
            if (actions >= MAX_ACTIONS_PER_ROUND) break;
            if (fill(n, crew)) actions++;
        }
        if (needs.isEmpty() && ticks % (ROUND_TICKS * 10) == 0) decide("all needs covered (" + crew.size() + " agents)");
    }

    /** Give the job to an agent without a role (or an idle miner for mining jobs), else spawn one. */
    private static final java.util.Set<String> pickedThisRound = new java.util.HashSet<>();

    /** Agents whose role has had nothing to do, since when (name -> ms). Warriors are never moved. */
    private static final Map<String, Long> idleSince = new java.util.HashMap<>();
    private static final long IDLE_MOVE_MS = 5 * 60_000;

    private static boolean idleLong(Agent a) {
        Long t = idleSince.get(a.name);
        return t != null && System.currentTimeMillis() - t > IDLE_MOVE_MS;
    }

    private static boolean fill(Need n, List<Agent> crew) {
        Agent pick = null;
        long now0 = System.currentTimeMillis();
        for (Agent a : crew) {
            if (a.role() != null && !java.util.Set.of(Role.DISTRIBUTOR, Role.WARRIOR, Role.RESCUER, Role.SPEEDRUNNER, Role.FALLOFF, Role.BREWER, Role.PICKUP, Role.FARMER, Role.KEEPER).contains(a.role()) && a.moduleIdle()) idleSince.putIfAbsent(a.name, now0); // waiting is their job
            else idleSince.remove(a.name);
        }
        // The starting crew is hired, never reshuffled: a distributor slot took the miner just hired, and "miner 2 of 3"
        // made the same miner a miner again every 3 s (run 1120z).
        boolean starting = n.why().startsWith("the starting crew") || n.why().startsWith("a runner to beat the game");
        if (!starting) for (Agent a : crew) {
            if (pickedThisRound.contains(a.name)) continue; // already got a job this round
            if (a.role() == null) { pick = a; break; }
            // Nothing to do in its role for 5 minutes (not a warrior): it takes the job instead of a newcomer.
            if (a.role() != n.role() && idleLong(a)) { pick = a; idleSince.remove(a.name); break; }
            if (n.role() == Role.MINER && a.role() == Role.MINER && minerFree(a)) { pick = a; break; }
        }
        // The speedrunner's iron: any miner not already on iron, busy or not, else whoever's work can wait (the
        // builder last; never a guard, a speedrunner, a distributor, the keeper or a rescuer).
        // The lava scout too, for the speedrunners: at the cap it was never hired ("but the crew is at its cap", run 1120p).
        boolean scout = n.role() == Role.EXPLORER && "scout".equals(n.task());
        // A hitlist player about: a fighter from whoever's work can wait (run 1120w: no distributor or guard to spare at the cap).
        boolean threat = n.role() == Role.WARRIOR && n.why().contains("(hitlist)");
        if (pick == null && (runnerNeed(n) || hungerNeed(n) || scout || threat)) {
            List<Role> order = threat ? List.of(Role.LOOTER, Role.PICKUP, Role.HUNTER, Role.FISHER, Role.MINER, Role.EXPLORER)
                    : scout ? List.of(Role.LOOTER, Role.PICKUP, Role.HUNTER, Role.FISHER, Role.MINER)
                    : hungerNeed(n) ? List.of(Role.LOOTER, Role.PICKUP, Role.MINER, Role.BUILDER)
                    : List.of(Role.MINER, Role.LOOTER, Role.HUNTER, Role.FISHER, Role.FARMER, Role.PICKUP, Role.BUILDER);
            for (Role r : order) {
                for (Agent a : crew) {
                    if (pickedThisRound.contains(a.name) || a.role() != r) continue;
                    if (r == Role.MINER && a.task != null && a.task.contains("iron_ore")) continue;
                    pick = a; break;
                }
                if (pick != null) break;
            }
        }
        // No distributor at all: one of the gatherers carries, cap or no cap (the user, 2026-11-19: "no distributors at all,
        // distributors are vital"). Run 1119c: five distributors became miners and hunters one by one, each "idle" between
        // deliveries when the speedrunner wanted iron or someone was hungry, and at the cap none could be hired back.
        if (pick == null && n.role() == Role.DISTRIBUTOR && crew.stream().noneMatch(x -> x.role() == Role.DISTRIBUTOR && !stalled.contains(x.name))) {
            for (Role r : List.of(Role.LOOTER, Role.PICKUP, Role.MINER, Role.FISHER, Role.HUNTER, Role.BUILDER)) {
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
            // At the cap: move someone off a job nobody needs (a farmer with no farm left to work,
            // a fisher with no spot) before giving up.
            Agent spare = surplus(crew, n);
            if (spare != null) {
                decide(n.why() + " -> " + spare.name + " moves from " + spare.role().id() + " (nothing to do there) to " + n.role().id());
                MasterAgent.run("agent role " + spare.name + " " + n.role().id());
                runTask(spare.name, n);
                CivLearning.decided(n.role(), n.task(), spare, crew);
                return true;
            }
            decide(n.why() + ", but the crew is at its cap of " + cap + " (raise it: /agent civ cap <n>)");
            return false;
        }
        // A saved agent first (it comes back with its inventory): one that had this role, else any.
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
        CivLearning.decided(n.role(), n.task(), null, crew); // judged by what it was for (a newcomer has no name yet)
        if (n.task() != null || n.role() == Role.BUILDER) pendingTasks.add(n);
        return true;
    }

    private record Joining(Role role, long at) {}
    /** Agents hired and still connecting (name, or "new#..." for a newcomer without a name yet): their role counts as filled. */
    private static final Map<String, Joining> joining = new java.util.concurrent.ConcurrentHashMap<>();

    /** Saved agents brought back lately (name -> when): not asked again for 2 minutes while they join. */
    private static final Map<String, Long> rejoined = new java.util.HashMap<>();

    /** A saved agent that isn't online: one whose saved role is this one first, else any. Null if none. */
    private static String rejoinCandidate(Role role) {
        try {
            if (!Saves.exists("civ")) return null;
            long now = System.currentTimeMillis();
            String any = null;
            for (Saves.Entry e : Saves.read("civ")) {
                String k = e.name().toLowerCase();
                if (AgentNetClient.AGENTS.containsKey(k) || AgentNetClient.RETIRED.contains(k)) continue; // (a retired scout came back 2 s later, into its lava: run 1120q)
                Long t = rejoined.get(k);
                if (t != null && now - t < 120_000) continue;
                if (e.role() == role) return e.name();
                // Never someone saved in a job that carries its own progress (the speedrunner's iron and buckets, a farm,
                // a building): after every restart they came back in somebody else's job and the run started over.
                if (any == null && !KEEPS_ROLE.contains(e.role())) any = e.name();
            }
            return any;
        } catch (java.io.IOException ex) { return null; }
    }

    private static final java.util.Set<Role> KEEPS_ROLE = java.util.EnumSet.of(Role.SPEEDRUNNER, Role.FALLOFF, Role.BREWER, Role.WARRIOR, Role.FARMER,
            Role.RESCUER, Role.PICKUP, Role.BUILDER, Role.TRIAL_GRINDER);

    /** More farmers than farm areas, more fishers than fishing spots, or a second explorer: one of them. */
    private static Agent surplus(List<Agent> crew, Need need) {
        Role needed = need.role();
        String server = Servers.current();
        if (server == null) return null;
        int farms = Math.max(1, Farms.list(server).size());
        long spots = Math.max(1, Spots.list(server).stream().filter(s -> s.type() == Spots.Type.FISHING).count());
        // Farmers are never "spare": several share a field, and the crew starved after they were moved off it.
        Map<Role, Integer> limit = Map.of(Role.FARMER, Integer.MAX_VALUE, Role.FISHER, (int) spots, Role.EXPLORER, 1);
        Map<Role, Integer> count = new EnumMap<>(Role.class);
        for (Agent a : crew) if (a.role() != null) count.merge(a.role(), 1, Integer::sum);
        if (first(need)) {
            // The base and its storage outrank a second distributor, then one guard.
            for (Agent a : crew) if (a.role() == Role.DISTRIBUTOR && count.getOrDefault(Role.DISTRIBUTOR, 0) > 2) return a; // never below two
            // Never a guard while a hitlist player is about: one was moved to miner in the middle of its fight (run 1120v test).
            if (!dev.agentnet.role.Sightings.anyFresh())
                for (Agent a : crew) if (a.role() == Role.WARRIOR) return a;
        }
        for (Agent a : crew) {
            Role r = a.role();
            if (r == null || r == needed || !limit.containsKey(r)) continue;
            if (count.getOrDefault(r, 0) > limit.get(r)) return a;
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
            if (a.isOnline() && a.onlineFor() > 10_000 && a.role() == n.role() && (a.task.equals("idle")
                    // an explorer starts exploring by itself: the scout job goes to it all the same (run 1120m: it never did)
                    || n.role() == Role.EXPLORER && a.task.startsWith("explore") && !a.task.equals(n.task()))) {
                pendingTasks.pollFirst();
                runTask(a.name, n);
                return;
            }
        }
        if (pendingTicks > 20 * 90) { pendingTasks.pollFirst(); pendingTicks = 0; }
    }

    private static int pendingTicks;

    /** Items of this id in every agent's inventory plus what storage chests held last time. */
    /**
     * Best pickaxe tier the crew has or can make right now: 0 none, 1 wood, 2 stone, 3 iron,
     * 4 diamond, 5 netherite. A tier counts once someone carries (or a chest holds) the pickaxe,
     * or the stuff for it: 3 logs (planks + sticks + table), 3 cobblestone, 3 iron, 3 diamonds;
     * miners craft the next pickaxe themselves when an ore needs it.
     */
    static int pickTier(List<Agent> crew) {
        // Miners count with what they carry (and can craft from it); anyone else's pickaxe, or one
        // in a chest, only counts as something a distributor can hand out.
        int t = spareTier(crew);
        for (Agent a : crew) if (a.role() == Role.MINER) t = Math.max(t, agentTier(a));
        return t;
    }

    private static final String[][] PICKS = {{"wooden_pickaxe", "1"}, {"golden_pickaxe", "1"}, {"stone_pickaxe", "2"}, {"copper_pickaxe", "2"},
            {"iron_pickaxe", "3"}, {"diamond_pickaxe", "4"}, {"netherite_pickaxe", "5"}};

    /** Best pickaxe a distributor could hand out: in a chest or a non-miner's inventory. */
    private static int spareTier(List<Agent> crew) {
        List<Agent> others = crew.stream().filter(a -> a.role() != Role.MINER).toList();
        int t = 0;
        for (String[] p : PICKS) {
            String id = "minecraft:" + p[0];
            int n = countHeld(others, x -> x.equals(id));
            for (List<ItemStack> chest : dev.agentnet.role.ChestMemory.CONTENTS.values())
                for (ItemStack s : chest) if (!s.isEmpty() && dev.agentnet.role.Gear.id(s).equals(id)) n += s.getCount();
            if (n > 0) t = Math.max(t, Integer.parseInt(p[1]));
        }
        return t;
    }

    /** What this agent can mine: its best pickaxe, or the next one up if it carries the stuff to craft it. */
    static int agentTier(Agent a) {
        List<Agent> me = List.of(a);
        int t = 0;
        for (String[] p : PICKS) { String id = "minecraft:" + p[0]; if (countHeld(me, x -> x.equals(id)) > 0) t = Math.max(t, Integer.parseInt(p[1])); }
        int logs = countHeld(me, id -> id.endsWith("_log") && !id.contains("stripped"));
        int planks = countHeld(me, id -> id.endsWith("_planks"));
        int cobble = countHeld(me, id -> id.equals("minecraft:cobblestone") || id.equals("minecraft:cobbled_deepslate") || id.equals("minecraft:blackstone"));
        if (t == 0 && logs * 4 + planks >= 9) t = 1;
        if (t == 1 && cobble >= 3) t = 2;
        if (t == 2 && countHeld(me, id -> id.equals("minecraft:iron_ingot")) >= 3) t = 3;
        if (t == 3 && countHeld(me, id -> id.equals("minecraft:diamond")) >= 3) t = 4;
        return t;
    }

    private static boolean hasPickaxe(Agent a) {
        for (ItemStack s : a.inventory()) if (!s.isEmpty() && dev.agentnet.role.Gear.id(s).endsWith("_pickaxe")) return true;
        return false;
    }

    /** A miner with nothing left to do: no job, done, or nothing of its kind around. */
    private static boolean minerFree(Agent a) {
        String s = a.moduleStatus();
        return a.task.equals("idle") || a.task.contains("useful") || s.startsWith("done") || s.startsWith("waiting for /agent mine") || s.startsWith("no ") && s.contains("found nearby");
    }

    /** The block in "mine iron_ore 32" or "mine 32 iron_ore". */
    private static String blockOf(String task) {
        for (String t : task.split("\\s+")) if (!t.equals("mine") && !t.matches("\\d+")) return t;
        return "";
    }

    /** Jobs on the pickaxe ladder (stone, iron, diamonds): not taken away for coal or wood. */
    private static boolean ladderJob(String task) {
        return task.contains(" stone") || task.contains("iron_ore") || task.contains("diamond_ore");
    }

    /** How many miners are on this block right now. */
    private static int minersOn(List<Agent> crew, String block) {
        return (int) crew.stream().filter(a -> a.role() == Role.MINER && a.task.contains(block) && !minerFree(a)).count()
                + (int) pendingTasks.stream().filter(n -> n.role() == Role.MINER && n.task() != null && n.task().contains(block)).count(); // hired, job not handed over yet
    }

    /** A miner already has this job (its task names the block). */
    private static boolean minerOn(List<Agent> crew, String block) {
        return minersOn(crew, block) > 0 || crew.stream().anyMatch(a -> a.role() == Role.MINER && a.task.contains(block) && !minerFree(a));
    }

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
        if (server != null) {
            sb.append(Farms.list(server).size()).append(" farm(s), ")
              .append(Spots.list(server).stream().filter(s -> s.type() == Spots.Type.FISHING).count()).append(" fishing spot(s). ");
        }
        sb.append("Iron ").append(count(crew, "minecraft:iron_ingot") + count(crew, "minecraft:raw_iron"))
          .append(", coal ").append(count(crew, "minecraft:coal") + count(crew, "minecraft:charcoal"))
          .append(", bread ").append(count(crew, "minecraft:bread")).append(", wheat ").append(count(crew, "minecraft:wheat")).append(".")
          .append(" Best pickaxe: ").append(dev.agentnet.role.Gear.pickTierName(pickTier(crew)))
          .append(" (miners craft their own pickaxes on the way up: wood, stone, iron, diamond). Fishing rods: ").append(count(crew, "minecraft:fishing_rod")).append(".");
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
