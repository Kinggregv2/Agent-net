/*
 * Derived from Baritone (https://github.com/cabaletta/baritone), file
 * baritone/pathing/movement/MovementHelper.java, branch 26.3.
 * Baritone is free software under the GNU Lesser General Public License v3.0;
 * this file is distributed under the same license.
 */
package dev.agentnet.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Which blocks an agent may walk through or stand on. Same idea as Baritone's
 * canWalkThrough / canWalkOn / avoidWalkingInto, trimmed to what an agent without
 * block breaking or door opening can do:
 * <ul>
 *   <li>walk through: no collision box, no fluid, not dangerous</li>
 *   <li>stand on: a full-height top surface (blocks, stairs, top slabs, farmland...),
 *       not magma/honey; fences and walls are too tall to step onto</li>
 *   <li>doors, gates and trapdoors count as walls (agents can't open them yet)</li>
 * </ul>
 */
public final class Rules {
    private Rules() {}

    public static boolean canWalkThrough(BlockState s) {
        if (s == null) return false; // unknown chunk
        if (s.isAir()) return true;
        // Flowing or falling water (not a source): players walk right through it (a waterfall, a stream over a ledge).
        // As a wall it boxed agents in where they stood in a falling stream, searching 1 spot and going nowhere.
        if (!s.getFluidState().isEmpty()) return s.getFluidState().is(net.minecraft.tags.FluidTags.WATER) && !s.getFluidState().isSource()
                && !dangerous(s) && shape(s).isEmpty();
        if (dangerous(s)) return false;
        Block b = s.getBlock();
        if (b instanceof DoorBlock || b instanceof FenceGateBlock || b instanceof TrapDoorBlock) return false;
        return shape(s).isEmpty();
    }

    /** A door or fence gate an agent can open by hand (wooden doors, any fence gate; not iron). */
    public static boolean openable(BlockState s) {
        if (s == null) return false;
        Block b = s.getBlock();
        if (b instanceof DoorBlock d) return d.type().canOpenByHand();
        return b instanceof FenceGateBlock;
    }

    /** Walkable once opened: open-by-hand doors/gates count, everything else as canWalkThrough. */
    static boolean canWalkThroughOrOpen(BlockState s) {
        return canWalkThrough(s) || openable(s);
    }

    public static boolean canStandOn(BlockState s) {
        if (s == null || s.isAir()) return false;
        Block b = s.getBlock();
        if (b == Blocks.MAGMA_BLOCK || b == Blocks.HONEY_BLOCK || dangerous(s)) return false;
        if (!s.getFluidState().isEmpty()) return false;
        VoxelShape shape = shape(s);
        if (shape.isEmpty()) return false;
        // Anything with a top up to its own block's top: full blocks, stairs, slabs (top and bottom), farmland, carpets,
        // beds, snow. The path node is the cell over it; the walker puts its feet down on the real top (vanilla gravity),
        // so a bottom slab is a half step like a player's. Not fences or walls (1.5: no stepping onto those).
        // (Bottom slabs used to be unstandable: a trial chamber's slab floors had the server putting agents back.)
        double top = shape.max(Direction.Axis.Y);
        return top > 0 && top <= 1.0;
    }

    /** Never step into or onto these. */
    public static boolean dangerous(BlockState s) {
        Block b = s.getBlock();
        return b instanceof BaseFireBlock || b == Blocks.CACTUS || b == Blocks.SWEET_BERRY_BUSH
                || b == Blocks.COBWEB || b == Blocks.POWDER_SNOW || b == Blocks.END_PORTAL
                || b == Blocks.BUBBLE_COLUMN || b == Blocks.POINTED_DRIPSTONE
                // traps: tripwires (jungle temples), stone plates (desert temple TNT), sculk (ancient cities)
                || b == Blocks.TRIPWIRE || b == Blocks.TRIPWIRE_HOOK || b == Blocks.STONE_PRESSURE_PLATE
                || b == Blocks.SCULK_SENSOR || b == Blocks.CALIBRATED_SCULK_SENSOR || b == Blocks.SCULK_SHRIEKER;
    }

    private static VoxelShape shape(BlockState s) {
        try {
            return s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        } catch (RuntimeException e) {
            return net.minecraft.world.phys.shapes.Shapes.block(); // unknown shape: treat as solid
        }
    }
}
