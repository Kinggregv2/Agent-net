package dev.agentnet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.agentnet.role.Role;
import net.minecraft.world.item.ItemStack;

/**
 * Notices agents that stand around doing nothing and tells the master agent:
 * "&lt;name&gt; is not working, if you believe he should work assign him a task".
 *
 * <p>"Doing nothing" = for {@link #AFK_MS} it hasn't moved more than a couple of blocks, dug
 * anything or had its inventory change. Roles that are expected to wait now and then (warriors
 * on guard, distributors between deliveries, the storage keeper) are left out. One report per
 * idle stretch: it's reported again only after it worked in between.
 */
final class AfkChecker {
    static long AFK_MS = 5 * 60_000;
    private static final Set<Role> EXEMPT = Set.of(Role.WARRIOR, Role.DISTRIBUTOR, Role.KEEPER);

    private record Seen(double x, double y, double z, int inv, long since, boolean reported) {}

    private static final Map<String, Seen> SEEN = new HashMap<>();
    private static int ticks;
    static boolean enabled = true;

    private AfkChecker() {}

    /** The AFK detector: every online agent, every role, always on (the report to the master above is separate and
     *  can be switched off). Idle = hasn't moved 2.5 blocks, dug, or had its inventory change. */
    private static final Map<String, Seen> IDLE = new HashMap<>();
    /** Idle this long = AFK: red on the map, and said once in chat. */
    static long DETECT_MS = 2 * 60_000;

    /** How long this agent has stood idle, in ms (0 while it's working). */
    static long idleMs(String name) {
        Seen s = IDLE.get(name.toLowerCase());
        return s == null ? 0 : System.currentTimeMillis() - s.since();
    }

    static boolean afk(String name) { return idleMs(name) >= DETECT_MS; }

    private static void detect(long now) {
        IDLE.keySet().removeIf(n -> !AgentNetClient.AGENTS.containsKey(n));
        for (Agent a : AgentNetClient.AGENTS.values()) {
            String key = a.name.toLowerCase();
            // Dug in for the night: still on purpose, not idle (it showed as AFK and looked stuck).
            if (!a.isOnline() || a.dugIn()) { IDLE.remove(key); continue; }
            int inv = signature(a);
            Seen s = IDLE.get(key);
            if (s == null || a.isDigging() || inv != s.inv() || sq(a.x() - s.x()) + sq(a.y() - s.y()) + sq(a.z() - s.z()) > 2.5 * 2.5) {
                IDLE.put(key, new Seen(a.x(), a.y(), a.z(), inv, now, false));
                continue;
            }
            if (s.reported() || now - s.since() < DETECT_MS) continue;
            IDLE.put(key, new Seen(s.x(), s.y(), s.z(), s.inv(), s.since(), true));
            // Guards and the keeper stand still by design: on the map, but not announced.
            if (a.role() == Role.WARRIOR || a.role() == Role.KEEPER) continue;
            String doing = a.moduleStatus();
            AgentNetClient.notify("§e[" + a.name + "] AFK: hasn't moved for " + DETECT_MS / 60_000 + " min ("
                    + (a.role() == null ? "no role" : a.role().id()) + (doing == null || doing.isBlank() ? "" : "; " + doing) + ")");
            a.afk(); // and its job told: counts as stuck, something else (a helper stood 8 minutes "mining iron for its leggings", run 1120zh)
        }
    }

    /** Main thread, every client tick. */
    static void tick() {
        if (++ticks % 20 != 0) return;
        long now = System.currentTimeMillis();
        MasterAgent.drainAfk();
        detect(now);
        SEEN.keySet().removeIf(n -> !AgentNetClient.AGENTS.containsKey(n));
        for (Agent a : AgentNetClient.AGENTS.values()) {
            String key = a.name.toLowerCase();
            if (!enabled || !a.isOnline() || a.onlineFor() < 60_000 || (a.role() != null && EXEMPT.contains(a.role()))) { SEEN.remove(key); continue; }
            String st = a.moduleStatus();
            if (a.role() == Role.BUILDER && st != null && st.startsWith("waiting for materials")) { SEEN.remove(key); continue; } // its job is on, the supply is coming
            int inv = signature(a);
            Seen s = SEEN.get(key);
            boolean busy = a.isDigging();
            if (s == null || busy || inv != s.inv() || sq(a.x() - s.x()) + sq(a.y() - s.y()) + sq(a.z() - s.z()) > 2.5 * 2.5) {
                SEEN.put(key, new Seen(a.x(), a.y(), a.z(), inv, now, false));
                continue;
            }
            if (s.reported() || now - s.since() < AFK_MS) continue;
            SEEN.put(key, new Seen(s.x(), s.y(), s.z(), s.inv(), s.since(), true));
            String what = a.role() == null ? "no role" : a.role().id() + (a.task.equals("idle") ? "" : ", task: " + a.task);
            String doing = a.moduleStatus();
            AgentNet.LOG.info("[AFK] {} idle for {} min ({}; {})", a.name, (now - s.since()) / 60_000, what, doing);
            // Only an agent with no job goes to the master model: for one with a job, "idle" is that job waiting on
            // something (crops growing, a delivery, a stuck spot), and the model kept reassigning farmers, the speedrunner
            // and the picker-upper to mining iron.
            if (a.role() != null) continue;
            MasterAgent.afk(a.name + " is not working, if you believe he should work assign him a task"
                    + " (" + what + (doing == null || doing.isBlank() ? "" : "; it says: " + doing) + "). " + hints(a));
        }
    }

    /**
     * What would be useful for it to do, so the model has something concrete to pick from: the base's
     * missing materials first, then jobs nobody does yet. Re-setting the role it already has does nothing.
     */
    private static String hints(Agent a) {
        List<String> ideas = new ArrayList<>();
        Map<Role, Integer> roles = new java.util.EnumMap<>(Role.class);
        for (Agent o : AgentNetClient.AGENTS.values()) if (o.isOnline() && o.role() != null) roles.merge(o.role(), 1, Integer::sum);
        // The fighting crew (2026-11-21): no gathering jobs to suggest; fighters first, then the base crew.
        ideas.add("/agent role " + a.name + " warrior (guard the base and the players)");
        if (roles.getOrDefault(Role.PICKUP, 0) == 0) ideas.add("/agent role " + a.name + " pickup (clean up what the dead drop)");
        if (roles.getOrDefault(Role.KEEPER, 0) == 0) ideas.add("/agent role " + a.name + " keeper (keep the base storage)");
        if (roles.getOrDefault(Role.DISTRIBUTOR, 0) == 0) ideas.add("/agent role " + a.name + " distributor (hand out gear from storage)");
        return "Useful right now: " + String.join("; ", ideas.subList(0, Math.min(4, ideas.size())))
                + ". Pick one of these commands (or a better one); don't set the role it already has, and there is no role \"none\".";
    }

    private static int signature(Agent a) {
        int h = 1;
        for (ItemStack s : a.inventory()) h = h * 31 + (s.isEmpty() ? 0 : dev.agentnet.role.Gear.id(s).hashCode() * 7 + s.getCount());
        return h;
    }

    private static double sq(double v) { return v * v; }
}
