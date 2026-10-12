package io.github.jriiik.seedfinder.structure;

import io.github.jriiik.seedfinder.world.BlockPos;
import io.github.jriiik.seedfinder.world.ChunkPos;

/**
 * A chunk where a structure from {@code set} will try to start. Whether it really appears still depends on the
 * biome (and for some structures on the terrain), which the engine does not check yet.
 *
 * @param locatePos the position {@code /locate} would report for it
 */
public record StructureCandidate(StructureSet set, ChunkPos chunk, BlockPos locatePos) {
}
