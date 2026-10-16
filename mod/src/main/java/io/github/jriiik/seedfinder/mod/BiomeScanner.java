package io.github.jriiik.seedfinder.mod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Reads the overworld biomes of a chunk the client has just received: the biome the game stored for each cell of 4 x 4
 * x 4 blocks, at a few heights. These are exactly the biomes the generator picked (the F3 screen shows a blend of the
 * cells around a block instead), so the seed search can check them cell by cell.
 */
final class BiomeScanner {

    /**
     * The block heights read: the surface, and three cave levels where cave biomes (lush caves, dripstone caves, deep
     * dark) add evidence that the surface doesn't give.
     */
    static final int[] HEIGHTS = {64, -48, -16, 16};

    /** What a chunk's biomes were, or why they can't be used. */
    sealed interface Result permits Read, Unknown {
    }

    /** The chunk's cells. */
    record Read(BiomeChunk chunk) implements Result {
    }

    /** The chunk has a biome the vanilla overworld doesn't have (a data pack or a mod changes generation). */
    record Unknown(String biome) implements Result {
    }

    private BiomeScanner() {
    }

    /**
     * @param known the overworld's biome ids for this version
     */
    static Result scan(LevelChunk chunk, Set<String> known) {
        ChunkPos pos = chunk.getPos();
        Map<Integer, List<String>> levels = new LinkedHashMap<>();
        for (int y : HEIGHTS) {
            List<String> cells = new ArrayList<>(16);
            for (int qz = 0; qz < 4; qz++) {
                for (int qx = 0; qx < 4; qx++) {
                    String biome = chunk.getNoiseBiome(Chunks.x(pos) * 4 + qx, y >> 2, Chunks.z(pos) * 4 + qz).unwrapKey()
                        .map(key -> key.identifier().toString()).orElse("?");
                    if (!known.contains(biome)) {
                        return new Unknown(biome);
                    }
                    cells.add(biome);
                }
            }
            levels.put(y, cells);
        }
        return new Read(new BiomeChunk(Chunks.x(pos), Chunks.z(pos), levels));
    }
}
