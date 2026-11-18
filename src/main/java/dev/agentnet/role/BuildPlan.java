package dev.agentnet.role;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits one schematic between several builders.
 *
 * <p>The build area is cut into vertical slabs along its longest horizontal side, one
 * slab per builder. Slabs are balanced by <b>work</b> (blocks to place), not by size, so
 * a builder with the empty half of a castle courtyard doesn't finish first and idle.
 * Slabs are full-height so builders never stand on or block each other's parts, and
 * each slab is built bottom-up by its own builder.
 */
public final class BuildPlan {
    /** Inclusive box in world coordinates. */
    public record Box(int x1, int y1, int z1, int x2, int y2, int z2) {
        public int sizeX() { return x2 - x1 + 1; }
        public int sizeZ() { return z2 - z1 + 1; }
    }

    private BuildPlan() {}

    /**
     * @param box             the whole schematic
     * @param builders        how many builders share it
     * @param blocksPerSlice  blocks to place in each 1-block-thick slice along the long
     *                        axis (from the parsed schematic); null = assume uniform
     * @return one box per builder (fewer if the schematic is narrower than the crew)
     */
    public static List<Box> split(Box box, int builders, int[] blocksPerSlice) {
        boolean alongX = box.sizeX() >= box.sizeZ();
        int length = alongX ? box.sizeX() : box.sizeZ();
        int parts = Math.max(1, Math.min(builders, length));
        if (blocksPerSlice == null || blocksPerSlice.length != length) {
            blocksPerSlice = new int[length];
            java.util.Arrays.fill(blocksPerSlice, 1);
        }

        long total = 0;
        for (int w : blocksPerSlice) total += w;

        // Greedy: close a slab once it holds its fair share of the remaining work.
        List<int[]> ranges = new ArrayList<>();
        int start = 0;
        long done = 0;
        for (int p = 0; p < parts; p++) {
            int remainingParts = parts - p;
            if (remainingParts == 1) { ranges.add(new int[]{start, length - 1}); break; }
            long target = (total - done) / remainingParts;
            long acc = 0;
            int end = start;
            // Leave at least one slice for every remaining builder.
            int maxEnd = length - remainingParts;
            while (end < maxEnd && acc + blocksPerSlice[end] <= target) acc += blocksPerSlice[end++];
            if (end == start) acc += blocksPerSlice[end++]; // every slab gets at least one slice
            ranges.add(new int[]{start, end - 1});
            done += acc;
            start = end;
        }

        List<Box> out = new ArrayList<>();
        for (int[] r : ranges) {
            out.add(alongX
                    ? new Box(box.x1() + r[0], box.y1(), box.z1(), box.x1() + r[1], box.y2(), box.z2())
                    : new Box(box.x1(), box.y1(), box.z1() + r[0], box.x2(), box.y2(), box.z1() + r[1]));
        }
        return out;
    }
}
