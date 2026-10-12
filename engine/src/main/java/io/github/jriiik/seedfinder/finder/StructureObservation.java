package io.github.jriiik.seedfinder.finder;

import io.github.jriiik.seedfinder.structure.StructureSet;
import io.github.jriiik.seedfinder.world.ChunkPos;

/** Something seen in a world: a structure from {@code set} starts in {@code chunk}. */
public record StructureObservation(StructureSet set, ChunkPos chunk) {

    /** An observation from block coordinates anywhere inside the structure's start chunk. */
    public static StructureObservation atBlock(StructureSet set, int blockX, int blockZ) {
        return new StructureObservation(set, ChunkPos.ofBlock(blockX, blockZ));
    }

    @Override
    public String toString() {
        return set.shortId() + " at block (" + chunk.minBlockX() + ", " + chunk.minBlockZ() + ")";
    }
}
