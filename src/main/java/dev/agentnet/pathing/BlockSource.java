package dev.agentnet.pathing;

import net.minecraft.world.level.block.state.BlockState;

/** Where the pathfinder reads blocks from: {@link BlockView} in game, a fake world in tests. */
public interface BlockSource {
    boolean isLoaded(int x, int z);

    /** The block at a position, or null if unknown. */
    BlockState get(int x, int y, int z);
}
