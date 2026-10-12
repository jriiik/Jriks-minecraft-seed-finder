package io.github.jriiik.seedfinder.structure;

import io.github.jriiik.seedfinder.world.BlockPos;
import java.util.List;

/**
 * Strongholds: a fixed number of structures arranged in rings around the world origin. Their exact positions
 * depend on biomes, so they can only be computed once the engine can generate biomes (a later phase).
 *
 * @param preferredBiomes biome ids or tags (starting with '#') that the ring positions move towards
 */
public record ConcentricRingsPlacement(
    int distance,
    int spread,
    int count,
    List<String> preferredBiomes,
    int salt,
    BlockPos locateOffset
) implements StructurePlacement {

    public static final String TYPE = "minecraft:concentric_rings";

    public ConcentricRingsPlacement {
        preferredBiomes = List.copyOf(preferredBiomes);
    }

    @Override
    public String type() {
        return TYPE;
    }
}
