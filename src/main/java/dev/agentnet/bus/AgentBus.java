package dev.agentnet.bus;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import dev.agentnet.role.AgentHandle;
import dev.agentnet.role.Role;

/**
 * Private agent-to-agent messaging. Nothing goes to the server: messages are delivered
 * in memory to the receiving agent's role module and written to botchat.txt.
 * Messages queue up from any thread and are delivered on the main thread each tick.
 */
public final class AgentBus {
    /** Wired up by AgentNetClient: all known agents, and how to hand a message to one. */
    public static Supplier<Collection<? extends AgentHandle>> agents = List::of;
    public static BiConsumer<String, Message> deliver = (to, m) -> {};
    /** Wired up by AgentNetClient: append a line to botchat.txt. */
    public static Logger log = (from, to, line) -> {};

    public interface Logger { void write(String from, String to, String line); }

    private static final ConcurrentLinkedQueue<Message> QUEUE = new ConcurrentLinkedQueue<>();

    private AgentBus() {}

    public static void send(Message m) {
        if (!allowed(m)) return;
        QUEUE.add(m);
        log.write(m.from(), m.to(), m.type() == Message.Type.TEXT ? m.text()
                : m.type() + (m.text() == null || m.text().isEmpty() ? "" : " " + m.text())
                  + (m.data().isEmpty() ? "" : " " + m.data()));
    }

    /** Deliver queued messages. Main thread only. */
    public static void flush() {
        Message m;
        while ((m = QUEUE.poll()) != null) {
            if ("all".equalsIgnoreCase(m.to())) {
                for (AgentHandle a : agents.get()) if (!a.name().equalsIgnoreCase(m.from())) deliver.accept(a.name(), m);
            } else {
                deliver.accept(m.to(), m);
            }
        }
    }

    /**
     * Who may talk to whom. Everything is allowed for now; this is where the master
     * agent's permissions ("the distributor may ask everyone who is hungry") will go.
     */
    static boolean allowed(Message m) {
        return true;
    }

    /** The nearest online agent with this role on the same server and dimension. */
    public static Optional<AgentHandle> closest(Role role, AgentHandle from) {
        return agents.get().stream()
                .filter(a -> a.isOnline() && a.role() == role && a != from)
                .filter(a -> a.server().equals(from.server()) && a.dimension() != null
                        && dev.agentnet.role.AgentHandle.sameDim(a.dimension(), from.dimension()))
                .map(a -> (AgentHandle) a)
                .min(Comparator.comparingDouble(a -> distSq(a, from)));
    }

    public static double distSq(AgentHandle a, AgentHandle b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }
}
