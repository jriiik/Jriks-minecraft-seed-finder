package io.github.jriiik.seedfinder.structure;

import io.github.jriiik.seedfinder.rng.JavaRandom;
import io.github.jriiik.seedfinder.rng.WorldgenSeeds;
import io.github.jriiik.seedfinder.world.BlockPos;
import io.github.jriiik.seedfinder.world.ChunkPos;
import java.util.Optional;

/**
 * The most common placement: the world is cut into square regions of {@code spacing} x {@code spacing} chunks,
 * and each region gets exactly one candidate chunk, picked at random but at least {@code separation} chunks
 * away from the region's far edges. Most structures (villages, temples, outposts...) use it.
 *
 * <p>Only the lowest 48 bits of the world seed influence where the candidate chunks are, because the random
 * generator only keeps 48 bits. That is what makes structure-based seed finding possible.
 */
public record RandomSpreadPlacement(
    int spacing,
    int separation,
    SpreadType spreadType,
    int salt,
    float frequency,
    FrequencyReduction frequencyReduction,
    Optional<ExclusionZone> exclusionZone,
    BlockPos locateOffset
) implements StructurePlacement {

    public static final String TYPE = "minecraft:random_spread";

    public RandomSpreadPlacement {
        if (spacing <= separation || separation < 0) {
            throw new IllegalArgumentException("spacing (" + spacing + ") must be larger than separation (" + separation + ")");
        }
        if (!(frequency >= 0 && frequency <= 1)) {
            throw new IllegalArgumentException("frequency must be between 0 and 1, got " + frequency);
        }
    }

    @Override
    public String type() {
        return TYPE;
    }

    /** The region a chunk coordinate belongs to. */
    public int regionOf(int chunkCoordinate) {
        return Math.floorDiv(chunkCoordinate, spacing);
    }

    /** The one chunk in region (regionX, regionZ) where this structure set may start. */
    public ChunkPos potentialChunk(long worldSeed, int regionX, int regionZ) {
        JavaRandom random = new JavaRandom(WorldgenSeeds.largeFeatureWithSalt(worldSeed, regionX, regionZ, salt));
        int limit = spacing - separation;
        int offsetX = spreadType.evaluate(random, limit);
        int offsetZ = spreadType.evaluate(random, limit);
        return new ChunkPos(regionX * spacing + offsetX, regionZ * spacing + offsetZ);
    }

    /** Whether this chunk is its region's candidate chunk. */
    public boolean isPlacementChunk(long worldSeed, int chunkX, int chunkZ) {
        ChunkPos candidate = potentialChunk(worldSeed, regionOf(chunkX), regionOf(chunkZ));
        return candidate.x() == chunkX && candidate.z() == chunkZ;
    }

    /** Whether the candidate survives the set's frequency roll (always true when the frequency is 1). */
    public boolean passesFrequency(long worldSeed, int chunkX, int chunkZ) {
        return frequency >= 1.0f || frequencyReduction.shouldGenerate(worldSeed, salt, chunkX, chunkZ, frequency);
    }
}
