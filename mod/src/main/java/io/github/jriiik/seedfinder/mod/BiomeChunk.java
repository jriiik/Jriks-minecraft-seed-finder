package io.github.jriiik.seedfinder.mod;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The overworld biomes of one chunk as the client received them: for each height read, the 16 cells of 4 x 4 blocks,
 * row by row from north to south, each row from west to east.
 *
 * @param levels block y -> 16 biome ids
 */
record BiomeChunk(int chunkX, int chunkZ, Map<Integer, List<String>> levels) {

    BiomeChunk {
        TreeMap<Integer, List<String>> copy = new TreeMap<>();
        levels.forEach((y, cells) -> {
            if (cells.size() != 16) {
                throw new IllegalArgumentException("a chunk has 16 cells per height, not " + cells.size());
            }
            copy.put(y, List.copyOf(cells));
        });
        levels = copy;
    }

    /** The biome of the cell in column {@code cellX} (0 to 3, west to east) and row {@code cellZ} (north to south). */
    String cell(int y, int cellX, int cellZ) {
        List<String> cells = levels.get(y);
        return cells == null ? null : cells.get(cellZ * 4 + cellX);
    }
}
