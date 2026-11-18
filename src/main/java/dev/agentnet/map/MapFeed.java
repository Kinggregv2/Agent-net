package dev.agentnet.map;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Chunks agents have seen, waiting to be drawn on Xaero's World Map. Agents offer every
 * chunk packet they receive (cheap: the raw packet is shared, not copied); the map writer
 * (see XaeroMapFeed) asks for them through the normal client chunk lookup, which a mixin
 * redirects here while {@link #serving} is on. A chunk leaves the queue once it was drawn.
 */
public final class MapFeed {
    /** Turned on when Xaero's World Map is installed. */
    public static volatile boolean enabled;
    private static final int MAX_QUEUED = 1536; // ~20-60 MB of raw packets at most

    public record Pending(String dimension, int cx, int cz, ClientboundLevelChunkPacketData data) {}

    private static final LinkedHashMap<Long, Pending> QUEUE = new LinkedHashMap<>();

    /** Render thread only: true while the agent map writer runs. */
    public static boolean serving;
    private static String servingDimension;
    private static final Map<Long, LevelChunk> BUILT = new HashMap<>();
    private static long servedCount;

    private MapFeed() {}

    static long key(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }

    /** Netty thread: an agent received a chunk. */
    public static void offer(String dimension, int cx, int cz, ClientboundLevelChunkPacketData data) {
        if (!enabled || dimension == null) return;
        offered++;
        synchronized (QUEUE) {
            long k = key(cx, cz);
            QUEUE.remove(k); // re-insert at the back: newest data
            QUEUE.put(k, new Pending(dimension, cx, cz, data));
            // A cap for each dimension: agents in the nether while you're in the overworld wait their turn (drawn when you
            // go there) without pushing out what agents see around you.
            int inDim = 0;
            for (Pending p : QUEUE.values()) if (p.dimension.equals(dimension)) inDim++;
            if (inDim > MAX_QUEUED) {
                Iterator<Pending> it = QUEUE.values().iterator();
                while (it.hasNext()) if (it.next().dimension.equals(dimension)) { it.remove(); dropped++; break; }
            }
        }
    }

    public static int queued() { synchronized (QUEUE) { return QUEUE.size(); } }
    public static long served() { return servedCount; }
    /** Chunks that fell off the full queue before they were drawn. */
    public static volatile long dropped;
    /** Chunks that came in from agents, all told. */
    public static volatile long offered;

    /**
     * Oldest queued chunk in this dimension that this client doesn't have itself, or null. Chunks it has are dropped on
     * the way: Xaero draws those on its own (and never asks for them), so they only filled the queue. With agents
     * starting where you are, the queue was all of those and nothing far away was ever drawn.
     */
    public static Pending next(ClientLevel level, String dimension) {
        synchronized (QUEUE) {
            Iterator<Pending> it = QUEUE.values().iterator();
            while (it.hasNext()) {
                Pending p = it.next();
                if (!p.dimension.equals(dimension)) continue;
                if (level.getChunkSource().hasChunk(p.cx, p.cz)) { it.remove(); skippedLoaded++; continue; }
                return p;
            }
            return null;
        }
    }
    /** Chunks dropped from the queue because this client had them loaded (Xaero draws those itself). */
    public static volatile long skippedLoaded;

    /** Queued chunks within radius of a center that the client itself has loaded. */
    public static int aroundLoaded(ClientLevel level, String dimension, int cx, int cz, int radius) {
        int n = 0;
        synchronized (QUEUE) {
            for (Pending p : QUEUE.values()) {
                if (p.dimension.equals(dimension) && Math.abs(p.cx - cx) <= radius && Math.abs(p.cz - cz) <= radius
                        && level.getChunkSource().hasChunk(p.cx, p.cz)) n++;
            }
        }
        return n;
    }

    /** Queued chunks within radius (chunks) of a center, in this dimension. */
    public static int around(String dimension, int cx, int cz, int radius) {
        int n = 0;
        synchronized (QUEUE) {
            for (Pending p : QUEUE.values()) {
                if (p.dimension.equals(dimension) && Math.abs(p.cx - cx) <= radius && Math.abs(p.cz - cz) <= radius) n++;
            }
        }
        return n;
    }

    /** Start a writing pass for this dimension. Render thread. */
    public static void begin(String dimension) {
        serving = true;
        servingDimension = dimension;
    }

    public static void end() { serving = false; }

    /** A pass over one area is done: drop what was drawn and the decoded chunks. */
    public static void finishArea(int centerX, int centerZ) {
        synchronized (QUEUE) {
            for (Long k : BUILT.keySet()) QUEUE.remove(k);
            QUEUE.remove(key(centerX, centerZ)); // always progress, even if the client had it itself
        }
        BUILT.clear();
    }

    /**
     * Called by the chunk-cache mixin when the client itself doesn't have the chunk.
     * Decodes the agent's raw packet into a chunk of the client's level (kept until
     * {@link #finishArea}), or returns null.
     */
    /** Diagnostics: lookups while serving, and why they came back empty. */
    public static volatile long asked, notQueued, otherDim, busy;
    public static volatile int lastAskX, lastAskZ;

    public static LevelChunk serve(ClientLevel level, int cx, int cz) {
        if (!serving) return null;
        asked++; lastAskX = cx; lastAskZ = cz;
        long k = key(cx, cz);
        LevelChunk built = BUILT.get(k);
        if (built != null) return built;
        Pending p;
        synchronized (QUEUE) { p = QUEUE.get(k); }
        if (p == null) { notQueued++; return null; }
        if (!p.dimension.equals(servingDimension)) { otherDim++; return null; }
        if (building) { busy++; return null; }
        building = true;
        try {
            // Blocks and heightmaps only, like LevelChunk.replaceWithPacketData minus block
            // entities: loading those would register tickers in your real world, and some
            // (spawners) read the world while loading, which led straight back here.
            LevelChunk c = new LevelChunk(level, new ChunkPos(cx, cz));
            var buf = p.data.getReadBuffer();
            for (var section : c.getSections()) section.read(buf);
            p.data.getHeightmaps().forEach(c::setHeightmap);
            BUILT.put(k, c);
            servedCount++;
            return c;
        } catch (RuntimeException e) {
            synchronized (QUEUE) { QUEUE.remove(k); }
            return null;
        } finally {
            building = false;
        }
    }

    private static boolean building;

    /** Forget everything (left the server). */
    public static void clear() {
        synchronized (QUEUE) { QUEUE.clear(); }
        BUILT.clear();
    }

    static List<Pending> snapshot() { synchronized (QUEUE) { return new ArrayList<>(QUEUE.values()); } }
}
