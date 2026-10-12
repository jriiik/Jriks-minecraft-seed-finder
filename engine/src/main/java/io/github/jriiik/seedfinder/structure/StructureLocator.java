package io.github.jriiik.seedfinder.structure;

import io.github.jriiik.seedfinder.version.VersionData;
import io.github.jriiik.seedfinder.world.ChunkPos;
import java.util.ArrayList;
import java.util.List;

/**
 * Answers "where can structures start?" for one world seed, using the same rules as Minecraft: placement,
 * frequency rolls and exclusion zones. Biomes are not checked yet, so a candidate only becomes a real structure
 * if the biome there allows it.
 */
public final class StructureLocator {

    /** Exclusion zones may point at sets with their own zones; real data never nests deeply. */
    private static final int MAX_EXCLUSION_DEPTH = 8;

    private final VersionData data;
    private final long worldSeed;

    public StructureLocator(VersionData data, long worldSeed) {
        this.data = data;
        this.worldSeed = worldSeed;
    }

    public long worldSeed() {
        return worldSeed;
    }

    /** Whether the engine can list candidates for this set (strongholds need biomes, which come later). */
    public static boolean canLocate(StructureSet set) {
        return set.placement() instanceof RandomSpreadPlacement || set.placement() instanceof DimensionOriginPlacement;
    }

    /** Whether a structure from this set may start in the chunk (Minecraft's {@code isStructureChunk}). */
    public boolean isStructureChunk(StructureSet set, int chunkX, int chunkZ) {
        return isStructureChunk(set, chunkX, chunkZ, 0);
    }

    private boolean isStructureChunk(StructureSet set, int chunkX, int chunkZ, int depth) {
        return switch (set.placement()) {
            case RandomSpreadPlacement spread -> spread.isPlacementChunk(worldSeed, chunkX, chunkZ)
                && spread.passesFrequency(worldSeed, chunkX, chunkZ)
                && spread.exclusionZone().map(zone -> !hasStructureChunkInRange(zone, chunkX, chunkZ, depth)).orElse(true);
            case DimensionOriginPlacement origin -> chunkX == DimensionOriginPlacement.ORIGIN.x() && chunkZ == DimensionOriginPlacement.ORIGIN.z();
            case ConcentricRingsPlacement rings -> throw new UnsupportedOperationException(
                set.id() + " uses concentric rings, which need biome generation (not supported yet)");
        };
    }

    private boolean hasStructureChunkInRange(ExclusionZone zone, int chunkX, int chunkZ, int depth) {
        if (depth >= MAX_EXCLUSION_DEPTH) {
            throw new IllegalStateException("Exclusion zones nest too deeply (is there a cycle?) at " + zone.otherSet());
        }
        StructureSet other = data.structureSet(zone.otherSet())
            .orElseThrow(() -> new IllegalStateException("Exclusion zone refers to unknown structure set " + zone.otherSet()));
        int range = zone.chunkCount();
        for (int x = chunkX - range; x <= chunkX + range; x++) {
            for (int z = chunkZ - range; z <= chunkZ + range; z++) {
                if (isStructureChunk(other, x, z, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * All chunks inside the rectangle (inclusive, in chunk coordinates) where a structure from this set may start,
     * sorted by region.
     */
    public List<StructureCandidate> candidates(StructureSet set, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        List<StructureCandidate> found = new ArrayList<>();
        switch (set.placement()) {
            case RandomSpreadPlacement spread -> {
                for (int rx = spread.regionOf(minChunkX); rx <= spread.regionOf(maxChunkX); rx++) {
                    for (int rz = spread.regionOf(minChunkZ); rz <= spread.regionOf(maxChunkZ); rz++) {
                        ChunkPos chunk = spread.potentialChunk(worldSeed, rx, rz);
                        if (chunk.x() >= minChunkX && chunk.x() <= maxChunkX && chunk.z() >= minChunkZ && chunk.z() <= maxChunkZ
                            && isStructureChunk(set, chunk.x(), chunk.z())) {
                            found.add(new StructureCandidate(set, chunk, spread.locatePos(chunk)));
                        }
                    }
                }
            }
            case DimensionOriginPlacement origin -> {
                ChunkPos chunk = DimensionOriginPlacement.ORIGIN;
                if (chunk.x() >= minChunkX && chunk.x() <= maxChunkX && chunk.z() >= minChunkZ && chunk.z() <= maxChunkZ) {
                    found.add(new StructureCandidate(set, chunk, origin.locatePos(chunk)));
                }
            }
            case ConcentricRingsPlacement rings -> throw new UnsupportedOperationException(
                set.id() + " uses concentric rings, which need biome generation (not supported yet)");
        }
        return found;
    }
}
