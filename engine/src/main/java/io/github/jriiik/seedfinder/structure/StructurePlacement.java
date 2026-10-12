package io.github.jriiik.seedfinder.structure;

import io.github.jriiik.seedfinder.world.BlockPos;
import io.github.jriiik.seedfinder.world.ChunkPos;

/** The rule that decides in which chunks a structure set may start. */
public sealed interface StructurePlacement permits RandomSpreadPlacement, ConcentricRingsPlacement, DimensionOriginPlacement {

    /** The datapack type id, e.g. {@code minecraft:random_spread}. */
    String type();

    /** Offset from the chunk's corner to the position that {@code /locate} reports. */
    default BlockPos locateOffset() {
        return BlockPos.ZERO;
    }

    /** The position {@code /locate} reports for a structure starting in this chunk. */
    default BlockPos locatePos(ChunkPos chunk) {
        return new BlockPos(chunk.minBlockX(), 0, chunk.minBlockZ()).offset(locateOffset());
    }
}
