package dev.agentnet.role;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.world.phys.Vec3;

/**
 * Where players just died (ours, the enemies', the real players on our side): everything they carried is on the ground
 * there for 5 minutes. The cleanup crew goes to these first (the user, 2026-11-21: "there should be a cleanup crew that
 * will just take the items from the dead bodys"). Any agent that sees a player die (entity event 3), or dies itself,
 * adds one.
 */
public final class LootSpots {
    private LootSpots() {}

    public record Spot(String dim, Vec3 pos, String who, long at) {}

    /** Items on the ground despawn after 5 minutes. */
    public static final long LIFE_MS = 5 * 60_000;
    private static final List<Spot> SPOTS = new CopyOnWriteArrayList<>();

    public static void add(String dim, Vec3 pos, String who) {
        long now = System.currentTimeMillis();
        SPOTS.removeIf(s -> now - s.at() > LIFE_MS);
        // The same death seen by several agents: once.
        for (Spot s : SPOTS) if (s.dim().equals(dim) && s.pos().distanceToSqr(pos) < 4 * 4 && now - s.at() < 10_000) return;
        SPOTS.add(new Spot(dim, pos, who, now));
        dev.agentnet.AgentNet.LOG.info("[cleanup] {} died at {} {} {} ({}): its things are on the ground for 5 minutes", who,
                (int) Math.floor(pos.x), (int) Math.floor(pos.y), (int) Math.floor(pos.z), dim);
    }

    /** The freshest spot in this dimension not yet cleared, or null. */
    public static Spot next(String dim, Vec3 from, double range) {
        long now = System.currentTimeMillis();
        Spot best = null;
        for (Spot s : SPOTS) {
            if (now - s.at() > LIFE_MS || !s.dim().equals(dim) || s.pos().distanceToSqr(from) > range * range) continue;
            if (best == null || s.at() > best.at()) best = s;
        }
        return best;
    }

    /** Nothing left there (or it is gone): off the list. */
    public static void cleared(Spot s) { SPOTS.remove(s); }
}
