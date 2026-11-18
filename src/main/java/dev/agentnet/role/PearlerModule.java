package dev.agentnet.role;

import java.util.ArrayDeque;
import java.util.Deque;

import dev.agentnet.bus.Message;
import dev.agentnet.map.Pearls;
import net.minecraft.core.Direction;

/**
 * Pearl puller: guards the stasis chambers. When an agent drops under half health, it
 * gets a PEARL_PULL; it walks within reach of that agent's trigger block (trapdoor,
 * lever, button) and right-clicks it, so the pearl lands and the agent is pulled home.
 * A pearl can only be pulled once: the chamber is forgotten afterwards.
 * (Agents without a registered pearl are handed to you instead; see Takeover.)
 */
final class PearlerModule implements RoleModule {
    private static final double REACH = 4.0;
    private static final int TIMEOUT = 20 * 60;

    private final Deque<Message> queue = new ArrayDeque<>();
    private Pearls.Pearl current;
    private int timer, pulled;

    @Override
    public void onStart(AgentHandle a) { a.keepWorld(true); }

    @Override
    public void onStop(AgentHandle a) { a.keepWorld(false); if (a.isNavigating()) a.stopNavigation(); }

    @Override
    public void onMessage(AgentHandle a, Message m) {
        if (m.type() != Message.Type.PEARL_PULL) return;
        queue.removeIf(o -> o.get("agent").equalsIgnoreCase(m.get("agent")));
        queue.addFirst(m); // most urgent: newest
        a.log("pulling " + m.get("agent") + "'s pearl");
    }

    @Override
    public void tick(AgentHandle a) {
        timer++;
        if (current == null) {
            Message m = queue.pollFirst();
            if (m == null) return;
            current = Pearls.get(a.server(), m.get("agent")).orElse(null);
            if (current == null || !dev.agentnet.role.AgentHandle.sameDim(current.dimension(), a.dimension())) {
                a.log("can't pull " + m.get("agent") + "'s pearl" + (current == null ? ": none set" : ": it's in another dimension"));
                current = null;
                return;
            }
            timer = 0;
        }
        if (timer > TIMEOUT) { a.log("couldn't get to " + current.agent() + "'s pearl in time"); current = null; return; }
        double dx = current.x() + 0.5 - a.x(), dy = current.y() + 0.5 - (a.y() + 1.62), dz = current.z() + 0.5 - a.z();
        if (dx * dx + dy * dy + dz * dz <= REACH * REACH) {
            if (a.isNavigating()) a.stopNavigation();
            a.lookAt(current.x() + 0.5, current.y() + 0.5, current.z() + 0.5);
            a.useItemOn(current.x(), current.y(), current.z(), Direction.UP);
            pulled++;
            a.log("pulled " + current.agent() + "'s pearl");
            Pearls.remove(current.server(), current.agent()); // used up
            current = null;
            return;
        }
        if (!a.isNavigating() && timer % 40 == 1) a.navigate(current.x(), current.y(), current.z(), 3);
    }

    @Override
    public String status() { return (current == null ? "waiting" : "pulling " + current.agent() + "'s pearl") + ", " + pulled + " pulled"; }
}
