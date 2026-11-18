package dev.agentnet.pathing;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;

/**
 * Read-only block access for one pathfinding run. Chunks are decoded from the
 * {@link ChunkStore} the first time the search touches them, and the whole view is
 * thrown away when the run ends: decoded blocks never outlive a trip.
 */
public final class BlockView implements BlockSource {
    private final ChunkStore store;
    private final PalettedContainerFactory factory;
    private final int minY;
    private final Map<Long, LevelChunkSection[]> decoded = new HashMap<>();
    private final Map<Long, Boolean> failed = new HashMap<>();
    private static volatile long failSaidAt;

    public BlockView(ChunkStore store, RegistryAccess registries, int minY) {
        this.store = store;
        this.factory = PalettedContainerFactory.create(registries);
        this.minY = minY;
    }

    @Override public boolean isLoaded(int x, int z) { return store.has(x >> 4, z >> 4); }

    /** The block at a position, or null if that chunk isn't known. */
    @Override public BlockState get(int x, int y, int z) {
        LevelChunkSection[] sections = sections(x >> 4, z >> 4);
        if (sections == null) return null;
        int idx = (y - minY) >> 4;
        if (idx < 0 || idx >= sections.length) return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        return sections[idx].getBlockState(x & 15, y & 15, z & 15);
    }

    private LevelChunkSection[] sections(int cx, int cz) {
        long k = ChunkStore.key(cx, cz);
        LevelChunkSection[] s = decoded.get(k);
        if (s != null || failed.containsKey(k)) return s;
        ChunkStore.Raw raw = store.raw(cx, cz);
        if (raw == null) return null;
        try {
            FriendlyByteBuf buf = raw.data().getReadBuffer();
            s = new LevelChunkSection[raw.sections()];
            for (int i = 0; i < s.length; i++) {
                s[i] = new LevelChunkSection(factory);
                s[i].read(buf);
            }
            Map<Long, BlockState> changes = store.changes(cx, cz);
            if (changes != null) {
                for (var e : changes.entrySet()) {
                    long bk = e.getKey();
                    int x = (int) (bk >> 38), z = (int) (bk << 26 >> 38), y = (int) (bk << 52 >> 52);
                    int idx = (y - minY) >> 4;
                    if (idx >= 0 && idx < s.length) s[idx].setBlockState(x & 15, y & 15, z & 15, e.getValue(), false);
                }
            }
            decoded.put(k, s);
            return s;
        } catch (RuntimeException e) {
            failed.put(k, true);
            long now = System.currentTimeMillis();
            if (now - failSaidAt > 5_000) { failSaidAt = now; dev.agentnet.AgentNet.LOG.warn("chunk {} {} could not be read: {}", cx, cz, e.toString()); }
            return null;
        }
    }

    public int decodedChunks() { return decoded.size(); }

    /**
     * Every block matching {@code wanted} within {@code radius} blocks (horizontally) of x/z,
     * in the known chunks. Fast: 16x16x16 sections whose palette can't contain the block
     * are skipped without looking at a single block.
     */
    public java.util.List<net.minecraft.core.BlockPos> findAll(int cx, int cz, int radius,
            java.util.function.Predicate<BlockState> wanted, int limit) {
        return findAll(cx, cz, radius, wanted, limit, null);
    }

    /** Same, leaving out columns where {@code skipXZ} says so (spawn protection...): they don't use up the limit. */
    public java.util.List<net.minecraft.core.BlockPos> findAll(int cx, int cz, int radius,
            java.util.function.Predicate<BlockState> wanted, int limit, java.util.function.BiPredicate<Integer, Integer> skipXZ) {
        java.util.List<net.minecraft.core.BlockPos> out = new java.util.ArrayList<>();
        // Nearest chunks first, so a common block (stone) stops the search close by.
        java.util.List<int[]> chunks = new java.util.ArrayList<>();
        for (int chx = (cx - radius) >> 4; chx <= (cx + radius) >> 4; chx++)
            for (int chz = (cz - radius) >> 4; chz <= (cz + radius) >> 4; chz++) chunks.add(new int[]{chx, chz});
        int ox = cx >> 4, oz = cz >> 4;
        chunks.sort(java.util.Comparator.comparingInt(c -> Math.max(Math.abs(c[0] - ox), Math.abs(c[1] - oz))));
        for (int[] c : chunks) {
            if (out.size() >= limit) break;
            int chx = c[0], chz = c[1];
            LevelChunkSection[] s = sections(chx, chz);
            if (s == null) continue;
            for (int i = 0; i < s.length; i++) {
                if (s[i].hasOnlyAir() || !s[i].maybeHas(wanted)) continue;
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    if (!wanted.test(s[i].getBlockState(x, y, z))) continue;
                    if (skipXZ != null && skipXZ.test((chx << 4) + x, (chz << 4) + z)) continue;
                    out.add(new net.minecraft.core.BlockPos((chx << 4) + x, minY + (i << 4) + y, (chz << 4) + z));
                }
            }
        }
        return out;
    }
}
