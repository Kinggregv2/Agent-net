package dev.agentnet.role;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is waiting on whom, for the supply-chain map (/agent map): a builder waiting for the miners getting its
 * materials, a miner bringing a load to the builder, a distributor collecting from a miner, an agent waiting
 * for a distributor's delivery. Roles mark their links while they hold (every second or so); a link not
 * marked again for a few seconds is gone.
 */
public final class Links {
    /** What kind of link: drawn in different colors. */
    public enum Kind { WAITING, BRINGING, COLLECTING }

    public record Link(String from, String to, Kind kind, String label, long at) {}

    private static final long STALE_MS = 5_000;
    private static final Map<String, Link> LINKS = new ConcurrentHashMap<>();

    private Links() {}

    /** from waits on / brings to / collects from `to` (names), with a short label shown on hover. */
    public static void mark(String from, String to, Kind kind, String label) {
        if (from == null || to == null || from.equalsIgnoreCase(to)) return;
        LINKS.put(from.toLowerCase() + ">" + to.toLowerCase() + ">" + kind, new Link(from, to, kind, label, System.currentTimeMillis()));
    }

    /** The links that still hold. */
    public static List<Link> current() {
        long now = System.currentTimeMillis();
        LINKS.values().removeIf(l -> now - l.at() > STALE_MS);
        return new ArrayList<>(LINKS.values());
    }
}
