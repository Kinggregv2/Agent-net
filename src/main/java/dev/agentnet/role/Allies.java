package dev.agentnet.role;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.agentnet.bus.AgentBus;
import net.minecraft.world.phys.Vec3;

/**
 * The real players on our side: every player an agent sees that is not one of the agents and not on the hitlist. The
 * warriors guard them (the user, 2026-11-21: "Protect real players"): mobs near one get fought, and some of the
 * warriors go with them as bodyguards while the rest guard the base.
 */
public final class Allies {
    private Allies() {}

    public record Ally(String name, String dim, Vec3 pos, long at) {}

    /** A player not seen for this long is not counted (gone, or out of every agent's sight). */
    public static final long FRESH_MS = 20_000;
    private static final Map<String, Ally> SEEN = new ConcurrentHashMap<>();

    private static boolean agent(String name) {
        for (AgentHandle h : AgentBus.agents.get()) if (h.name().equalsIgnoreCase(name)) return true;
        return false;
    }

    /** What this agent sees right now goes into the list. */
    public static void look(AgentHandle a) {
        long now = System.currentTimeMillis();
        for (Seen s : a.entitiesNear(128)) {
            if (!s.player() || s.name() == null || a.isEnemy(s.name()) || s.name().equalsIgnoreCase(Training.partner) || agent(s.name())) continue;
            SEEN.put(s.name().toLowerCase(), new Ally(s.name(), String.valueOf(a.dimension()), s.pos(), now));
        }
    }

    /** The real players seen lately in this dimension, by name (the same order for every warrior). */
    public static List<Ally> list(String dim) {
        long now = System.currentTimeMillis();
        List<Ally> out = new ArrayList<>();
        for (Ally x : SEEN.values()) if (now - x.at() <= FRESH_MS && AgentHandle.sameDim(x.dim(), dim)) out.add(x);
        out.sort(Comparator.comparing(x -> x.name().toLowerCase()));
        return out;
    }

    /** A real player of ours within r blocks of p. */
    public static boolean near(String dim, Vec3 p, double r) {
        for (Ally x : list(dim)) if (x.pos().distanceToSqr(p) <= r * r) return true;
        return false;
    }

    /**
     * The real player this warrior guards, or null to guard the base. Up to two warriors per player, round robin by
     * name; with two or more warriors at least one always stays at the base.
     */
    public static Ally guarding(AgentHandle a) {
        List<Ally> allies = list(String.valueOf(a.dimension()));
        if (allies.isEmpty()) return null;
        List<String> warriors = new ArrayList<>();
        for (AgentHandle h : AgentBus.agents.get())
            if (h.isOnline() && h.role() == Role.WARRIOR && AgentHandle.sameDim(h.dimension(), a.dimension())) warriors.add(h.name().toLowerCase());
        warriors.sort(null);
        int i = warriors.indexOf(a.name().toLowerCase());
        if (i < 0) return null;
        int guards = Math.min(2 * allies.size(), warriors.size() >= 2 ? warriors.size() - 1 : warriors.size());
        return i < guards ? allies.get(i % allies.size()) : null;
    }
}
