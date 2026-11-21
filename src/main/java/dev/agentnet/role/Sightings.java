package dev.agentnet.role;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.agentnet.bus.AgentBus;
import dev.agentnet.bus.Message;
import net.minecraft.world.phys.Vec3;

/**
 * Hitlist sightings ("to add" 1, the user): any agent, even a farmer in the middle of nowhere, that sees a player on the
 * hitlist reports it; the fighters go after that player (by elytra when they have one: navigate() flies long trips); and
 * the agent that saw it starts following it, keeping it in sight (20-48 blocks off) and reporting where it is, until it
 * is lost for a minute. Speedrunners and fighters never follow (the run and the fight come first).
 */
public final class Sightings {
    private Sightings() {}

    public record Sighting(String enemy, String dim, Vec3 pos, long at, String by) {}

    /** Enemy (lower case) -> where it was last seen. */
    private static final Map<String, Sighting> LAST = new ConcurrentHashMap<>();
    /** Enemy (lower case) -> the agent following it. */
    private static final Map<String, String> FOLLOWER = new ConcurrentHashMap<>();
    private static final Map<String, Long> LOOKED = new ConcurrentHashMap<>();
    /** A sighting this fresh sends the fighters; one this old ends the following. */
    public static final long FRESH_MS = 30_000, LOST_MS = 60_000;
    private static final double SEE = 128, NEAR = 20, FAR = 48;

    /** Every agent, once a second: hitlist players it can see. */
    public static void look(AgentHandle a) {
        if (!a.isOnline() || a.dimension() == null) return;
        long now = System.currentTimeMillis();
        Long last = LOOKED.get(a.name());
        if (last != null && now - last < 1_000) return;
        LOOKED.put(a.name(), now);
        for (Seen s : a.entitiesNear(SEE)) {
            if (!s.player() || s.pos() == null || !a.isEnemy(s.name())) continue;
            String k = s.name().toLowerCase();
            Sighting old = LAST.get(k);
            LAST.put(k, new Sighting(s.name(), a.dimension(), s.pos(), now, a.name()));
            if (old == null || now - old.at() > LOST_MS) {
                a.log(String.format("§c[hitlist] spotted %s at %.0f %.0f %.0f: told the fighters", s.name(), s.pos().x, s.pos().y, s.pos().z));
                for (AgentHandle h : AgentBus.agents.get()) {
                    if (h.role() != Role.WARRIOR || h == a) continue;
                    AgentBus.send(Message.of(a.name(), h.name(), Message.Type.ENEMY_SPOTTED, s.name() + " spotted",
                            Map.of("player", s.name(), "x", String.valueOf((int) Math.floor(s.pos().x)), "y", String.valueOf((int) Math.floor(s.pos().y)), "z", String.valueOf((int) Math.floor(s.pos().z)))));
                }
            }
            if (!FOLLOWER.containsKey(k) && mayFollow(a)) {
                FOLLOWER.put(k, a.name());
                a.log("§e[hitlist] following " + s.name() + " now: keeping it in sight for the fighters");
            }
        }
    }

    private static boolean mayFollow(AgentHandle a) {
        Role r = a.role();
        return r != Role.WARRIOR;
    }

    /** A hitlist player seen anywhere within FRESH_MS. */
    public static boolean anyFresh() { long now = System.currentTimeMillis(); for (Sighting s : LAST.values()) if (now - s.at() <= FRESH_MS) return true; return false; }

    /** The freshest sighting in this dimension (within FRESH_MS), or null. */
    public static Sighting latest(String dim) {
        long now = System.currentTimeMillis();
        Sighting best = null;
        for (Sighting s : LAST.values()) {
            if (now - s.at() > FRESH_MS || !AgentHandle.sameDim(s.dim(), dim)) continue;
            if (best == null || s.at() > best.at()) best = s;
        }
        return best;
    }

    private static final Map<String, Integer> NAV = new ConcurrentHashMap<>();

    /** True while this agent is following a hitlist player (instead of its job). */
    public static boolean follow(AgentHandle a) {
        String enemy = null;
        for (var e : FOLLOWER.entrySet()) if (e.getValue().equals(a.name())) { enemy = e.getKey(); break; }
        if (enemy == null) return false;
        Sighting s = LAST.get(enemy);
        long now = System.currentTimeMillis();
        if (s == null || now - s.at() > LOST_MS || !AgentHandle.sameDim(s.dim(), a.dimension()) || !mayFollow(a)) {
            FOLLOWER.remove(enemy);
            if (a.isNavigating()) a.stopNavigation();
            a.log("§7[hitlist] lost " + (s == null ? enemy : s.enemy()) + ": back to its job");
            return false;
        }
        double d = Math.sqrt(s.pos().distanceToSqr(a.x(), a.y(), a.z()));
        int t = NAV.merge(a.name(), 1, Integer::sum);
        if (d > FAR) {
            if (!a.isNavigating() && t % 40 == 1) a.navigate((int) Math.floor(s.pos().x), (int) Math.floor(s.pos().y), (int) Math.floor(s.pos().z), (int) NEAR);
        } else {
            if (d >= NEAR && a.isNavigating()) a.stopNavigation();
            a.lookAt(s.pos().x, s.pos().y + 1.5, s.pos().z);
        }
        return true;
    }

    /** For status lines: who this agent follows, or null. */
    public static String following(String agent) {
        for (var e : FOLLOWER.entrySet()) if (e.getValue().equals(agent)) { Sighting s = LAST.get(e.getKey()); return s == null ? e.getKey() : s.enemy(); }
        return null;
    }
}
