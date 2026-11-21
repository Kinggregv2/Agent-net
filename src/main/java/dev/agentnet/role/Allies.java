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

    /** A real player of ours in trouble: a hostile mob on it, a hitlist player near it, or its health going down. */
    public record Danger(String name, String dim, Vec3 pos, String why, long at) {}
    public static final long DANGER_MS = 15_000;
    private static final Map<String, Danger> DANGER = new ConcurrentHashMap<>();
    private static final Map<String, Float> HEALTH = new ConcurrentHashMap<>();
    private static final java.util.Set<String> MOBS_LEFT_ALONE = java.util.Set.of("zombified_piglin", "enderman", "piglin", "piglin_brute");

    /** What this agent sees right now goes into the list. */
    public static void look(AgentHandle a) {
        long now = System.currentTimeMillis();
        List<Seen> seen = a.entitiesNear(128);
        String dim = String.valueOf(a.dimension());
        for (Seen s : seen) {
            if (!s.player() || s.name() == null || a.isEnemy(s.name()) || s.name().equalsIgnoreCase(Training.partner) || agent(s.name())) continue;
            String k = s.name().toLowerCase();
            SEEN.put(k, new Ally(s.name(), dim, s.pos(), now));
            String why = null;
            for (Seen o : seen) {
                if (o.id() == s.id()) continue;
                double d = o.pos().distanceTo(s.pos());
                if (o.player() && o.name() != null && a.isEnemy(o.name()) && d <= 16) { why = o.name() + " (hitlist) is on them"; break; }
                if (!o.player() && o.hostile() && !MOBS_LEFT_ALONE.contains(o.name()) && d <= 8) { why = "a " + o.name() + " is on them"; break; }
            }
            float hp = a.healthOf(s.id());
            if (!Float.isNaN(hp)) {
                Float before = HEALTH.put(k, hp);
                if (why == null && before != null && hp < before - 0.5f && hp < 14) why = "they are getting hurt (" + Math.round(hp) + " HP)";
            }
            if (why != null) {
                Danger old = DANGER.put(k, new Danger(s.name(), dim, s.pos(), why, now));
                if (old == null || now - old.at() > DANGER_MS) dev.agentnet.AgentNet.LOG.info("[{}] {} needs help: {}", a.name(), s.name(), why);
            }
        }
    }

    /** The real player of ours most recently in trouble in this dimension, or null. */
    public static Danger danger(String dim) {
        long now = System.currentTimeMillis();
        Danger best = null;
        for (Danger d : DANGER.values()) if (now - d.at() <= DANGER_MS && AgentHandle.sameDim(d.dim(), dim) && (best == null || d.at() > best.at())) best = d;
        return best;
    }

    /** One of the two warriors nearest to that player (they go to help; the rest keep their posts). */
    public static boolean oneOfNearest(AgentHandle a, Vec3 to) {
        double mine = to.distanceToSqr(a.x(), a.y(), a.z());
        int closer = 0;
        for (AgentHandle h : AgentBus.agents.get())
            if (h != a && h.isOnline() && h.role() == Role.WARRIOR && AgentHandle.sameDim(h.dimension(), a.dimension()) && to.distanceToSqr(h.x(), h.y(), h.z()) < mine) closer++;
        return closer < 2;
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
