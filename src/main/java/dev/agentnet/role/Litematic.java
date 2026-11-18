package dev.agentnet.role;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Reads Litematica .litematic schematics: gzip NBT with Regions, each holding a
 * Position and Size (sizes may be negative: the region extends backwards), a
 * BlockStatePalette and BlockStates, a long[] where every block's palette index is packed
 * with max(2, bits needed) bits, values allowed to span two longs (Litematica's
 * LitematicaBitArray). Block index = (y * sizeZ + z) * sizeX + x.
 *
 * <p>Positions come out relative to the schematic's origin (its corner when it was saved).
 */
public final class Litematic {
    public record Placed(BlockPos pos, BlockState state) {}

    public record Schematic(String name, List<Placed> blocks, BlockPos min, BlockPos max) {
        public int count() { return blocks.size(); }
    }

    private static final Map<Path, Schematic> CACHE = new ConcurrentHashMap<>();

    private Litematic() {}

    /** Find a file by name in agentnet/schematics/ or the game's schematics/ folder (Litematica's). */
    public static Path find(Path agentDir, String name) {
        String n = name.endsWith(".litematic") ? name : name + ".litematic";
        for (Path dir : new Path[]{agentDir.resolve("schematics"), agentDir.getParent().resolve("schematics")}) {
            Path p = dir.resolve(n);
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }

    /** Non-air blocks, relative to the schematic origin. Cached per file (until it changes). */
    public static Schematic load(Path file) throws IOException {
        Schematic s = CACHE.get(file);
        if (s != null) return s;
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        CompoundTag regions = root.getCompoundOrEmpty("Regions");
        List<Placed> out = new ArrayList<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (String key : regions.keySet()) {
            CompoundTag r = regions.getCompoundOrEmpty(key);
            CompoundTag pos = r.getCompoundOrEmpty("Position"), size = r.getCompoundOrEmpty("Size");
            int px = pos.getIntOr("x", 0), py = pos.getIntOr("y", 0), pz = pos.getIntOr("z", 0);
            int sx = size.getIntOr("x", 0), sy = size.getIntOr("y", 0), sz = size.getIntOr("z", 0);
            // Negative size: the region goes the other way from its position.
            int ox = sx < 0 ? px + sx + 1 : px, oy = sy < 0 ? py + sy + 1 : py, oz = sz < 0 ? pz + sz + 1 : pz;
            int ax = Math.abs(sx), ay = Math.abs(sy), az = Math.abs(sz);
            if (ax == 0 || ay == 0 || az == 0) continue;

            ListTag paletteTag = r.getListOrEmpty("BlockStatePalette");
            BlockState[] palette = new BlockState[paletteTag.size()];
            for (int i = 0; i < palette.length; i++) {
                palette[i] = NbtUtils.readBlockState(BuiltInRegistries.BLOCK, paletteTag.getCompoundOrEmpty(i));
            }
            long[] data = r.getLongArray("BlockStates").orElse(new long[0]);
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.length - 1)));
            long mask = (1L << bits) - 1;
            long volume = (long) ax * ay * az;
            if (data.length * 64L < volume * bits) throw new IOException("region '" + key + "' has too little block data");

            for (int y = 0; y < ay; y++) for (int z = 0; z < az; z++) for (int x = 0; x < ax; x++) {
                long index = ((long) y * az + z) * ax + x;
                int id = (int) get(data, index, bits, mask);
                if (id >= palette.length) continue;
                BlockState st = palette[id];
                if (st == null || st.isAir()) continue;
                BlockPos p = new BlockPos(ox + x, oy + y, oz + z);
                out.add(new Placed(p, st));
                minX = Math.min(minX, p.getX()); minY = Math.min(minY, p.getY()); minZ = Math.min(minZ, p.getZ());
                maxX = Math.max(maxX, p.getX()); maxY = Math.max(maxY, p.getY()); maxZ = Math.max(maxZ, p.getZ());
            }
        }
        if (out.isEmpty()) throw new IOException("no blocks in " + file.getFileName());
        s = new Schematic(file.getFileName().toString(), List.copyOf(out),
                new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
        CACHE.put(file, s);
        return s;
    }

    /** LitematicaBitArray.getAt: values may straddle two longs. */
    static long get(long[] arr, long index, int bits, long mask) {
        long start = index * bits;
        int a = (int) (start >> 6);
        int b = (int) (((index + 1) * bits - 1) >> 6);
        int off = (int) (start & 63);
        if (a == b) return (arr[a] >>> off) & mask;
        return ((arr[a] >>> off) | (arr[b] << (64 - off))) & mask;
    }

    /**
     * Save a box of the world (as your client sees it) as a one-region .litematic that
     * Litematica can open too. Returns the number of non-air blocks.
     */
    public static int save(Path file, String name, String author, net.minecraft.world.level.BlockGetter level,
                           BlockPos a, BlockPos b) throws IOException {
        BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        int sx = max.getX() - min.getX() + 1, sy = max.getY() - min.getY() + 1, sz = max.getZ() - min.getZ() + 1;
        long volume = (long) sx * sy * sz;
        if (volume > 4_000_000) throw new IOException("too big (" + volume + " blocks, max 4 million)");

        List<BlockState> palette = new ArrayList<>();
        Map<BlockState, Integer> index = new HashMap<>();
        BlockState air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        palette.add(air);
        index.put(air, 0);
        int[] ids = new int[(int) volume];
        int nonAir = 0;
        for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++) for (int x = 0; x < sx; x++) {
            BlockState s = level.getBlockState(min.offset(x, y, z));
            if (s.isAir()) s = air;
            else nonAir++;
            Integer id = index.get(s);
            if (id == null) { id = palette.size(); palette.add(s); index.put(s, id); }
            ids[(y * sz + z) * sx + x] = id;
        }
        int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1)));
        long[] data = new long[(int) ((volume * bits + 63) / 64)];
        for (long i = 0; i < volume; i++) {
            long v = ids[(int) i];
            long start = i * bits;
            int ai = (int) (start >> 6), bi = (int) (((i + 1) * bits - 1) >> 6), off = (int) (start & 63);
            data[ai] |= v << off;
            if (ai != bi) data[bi] |= v >>> (64 - off);
        }

        ListTag paletteTag = new ListTag();
        for (BlockState s : palette) paletteTag.add(NbtUtils.writeBlockState(s));
        CompoundTag size = new CompoundTag();
        size.putInt("x", sx); size.putInt("y", sy); size.putInt("z", sz);
        CompoundTag zero = new CompoundTag();
        zero.putInt("x", 0); zero.putInt("y", 0); zero.putInt("z", 0);
        CompoundTag region = new CompoundTag();
        region.put("Position", zero);
        region.put("Size", size.copy());
        region.put("BlockStatePalette", paletteTag);
        region.putLongArray("BlockStates", data);
        region.put("TileEntities", new ListTag());
        region.put("Entities", new ListTag());
        region.put("PendingBlockTicks", new ListTag());
        region.put("PendingFluidTicks", new ListTag());
        CompoundTag regions = new CompoundTag();
        regions.put(name, region);

        long now = System.currentTimeMillis();
        CompoundTag meta = new CompoundTag();
        meta.putString("Name", name);
        meta.putString("Author", author);
        meta.putString("Description", "Saved by AgentNet");
        meta.put("EnclosingSize", size);
        meta.putInt("RegionCount", 1);
        meta.putInt("TotalBlocks", nonAir);
        meta.putInt("TotalVolume", (int) volume);
        meta.putLong("TimeCreated", now);
        meta.putLong("TimeModified", now);

        CompoundTag root = new CompoundTag();
        root.putInt("MinecraftDataVersion", net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version());
        root.putInt("Version", 6);
        root.put("Metadata", meta);
        root.put("Regions", regions);
        Files.createDirectories(file.getParent());
        NbtIo.writeCompressed(root, file);
        CACHE.remove(file);
        return nonAir;
    }

    /** Forget a cached file (it was edited). */
    public static void forget(Path file) { CACHE.remove(file); }
}
