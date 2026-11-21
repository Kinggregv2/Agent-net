package dev.agentnet.role;

/**
 * Any agent that is attacked fights back (the user, 2026-11-21: "when any agent is attacked they load in the fight engine
 * and fight until the one that attacked them isn't dead or they die, after that they unload the fight engine"). The fight
 * engine is made when the first hit lands and dropped when the attacker is dead, gone (out of sight, over 32 blocks off),
 * or the agent itself died. Never a friend (you, the whitelist, our agents), and never a creeper (that one is run from).
 */
public final class SelfDefense {
    private Fight engine;
    private String against;

    /** In a fight right now. */
    public boolean active() { return engine != null; }

    /** One tick against the entity that hit it (-1: nobody). True while fighting: nothing else runs. */
    public boolean tick(AgentHandle a, int attackerId) {
        Seen t = null;
        if (attackerId >= 0) for (Seen s : a.entitiesNear(32)) if (s.id() == attackerId) { t = s; break; }
        boolean fight = t != null && !t.name().equals("creeper") && (!t.player() || a.isEnemy(t.name()));
        if (!fight) {
            if (engine != null) {
                engine.idle(a);
                engine = null;
                a.log(t == null ? "§athe fight with " + against + " is over (dead or gone): back to work" : "§estopped fighting " + against);
                against = null;
            }
            return false;
        }
        if (engine == null) { engine = new Fight(); against = t.name(); a.log("§cattacked by " + t.name() + ": fighting back"); }
        if (a.isNavigating() && t.distance() < 4) a.stopNavigation();
        engine.tick(a, t);
        return true;
    }

    /** It died: the fight is over. */
    public void reset(AgentHandle a) { if (engine != null) { engine.idle(a); engine = null; against = null; } }
}
