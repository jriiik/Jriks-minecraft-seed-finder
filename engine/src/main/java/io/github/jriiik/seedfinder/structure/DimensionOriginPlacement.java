package io.github.jriiik.seedfinder.structure;

import io.github.jriiik.seedfinder.world.ChunkPos;

/**
 * A single structure in the dimension's origin chunk. Vanilla worlds don't use it, but datapacks can (new in 26.x).
 * For normal world generators the origin is chunk (0, 0).
 */
public record DimensionOriginPlacement() implements StructurePlacement {

    public static final String TYPE = "minecraft:dimension_origin";
    public static final ChunkPos ORIGIN = new ChunkPos(0, 0);

    @Override
    public String type() {
        return TYPE;
    }
}
