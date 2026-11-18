package dev.agentnet.pathing;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The cheapest possible memory of the world around one agent: the chunk packets exactly
 * as the server sent them (not decoded), plus block changes since. Decoding only happens
 * in {@link BlockView}, and only while pathfinding.
 *
 * <p>{@link #drop()} throws everything away; after that the agent needs a reconnect to
 * see the world again (the server never re-sends chunks it already sent).
 */
public final class ChunkStore {
    record Raw(ClientboundLevelChunkPacketData data, int sections) {}

    private final Map<Long, Raw> chunks = new ConcurrentHashMap<>();
    /** chunk key -> (block pos key -> new state), for updates that arrived after the chunk. */
    private final Map<Long, Map<Long, BlockState>> changes = new ConcurrentHashMap<>();
    private volatile long lastChunkAt;

    public static long key(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }
    static long blockKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public void putChunk(int cx, int cz, ClientboundLevelChunkPacketData data, int sections) {
        long k = key(cx, cz);
        chunks.put(k, new Raw(data, sections));
        changes.remove(k);
        lastChunkAt = System.currentTimeMillis();
    }

    public void forgetChunk(int cx, int cz) {
        long k = key(cx, cz);
        chunks.remove(k);
        changes.remove(k);
    }

    public void blockChanged(int x, int y, int z, BlockState state) {
        long k = key(x >> 4, z >> 4);
        if (!chunks.containsKey(k)) return;
        changes.computeIfAbsent(k, kk -> new ConcurrentHashMap<>()).put(blockKey(x, y, z), state);
    }

    public boolean has(int cx, int cz) { return chunks.containsKey(key(cx, cz)); }
    public boolean isEmpty() { return chunks.isEmpty(); }
    public int size() { return chunks.size(); }
    public long lastChunkAt() { return lastChunkAt; }

    Raw raw(int cx, int cz) { return chunks.get(key(cx, cz)); }
    Map<Long, BlockState> changes(int cx, int cz) { return changes.get(key(cx, cz)); }

    /** Forget the whole world (arrived, idle, dimension change). */
    public void drop() {
        chunks.clear();
        changes.clear();
    }
}
