package io.github.jriiik.seedfinder.finder;

import io.github.jriiik.seedfinder.biome.OverworldBiomes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes biome notes in the format {@link ObservationReader} reads ({@code biome-grid}). */
public final class BiomeText {

    private static final String LETTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private BiomeText() {
    }

    /**
     * A {@code biome-grid} note: the header with the north-west cell's block and the legend, then one row per cell
     * southwards, one letter per cell eastwards.
     *
     * @param biomes [row][column] biome ids of 4 x 4 x 4 block cells, null where not read
     */
    public static List<String> grid(int x, int y, int z, String[][] biomes) {
        Map<String, Character> legend = new LinkedHashMap<>();
        List<String> rows = new ArrayList<>();
        for (String[] row : biomes) {
            StringBuilder line = new StringBuilder();
            for (String biome : row) {
                if (biome == null) {
                    line.append('?');
                    continue;
                }
                Character letter = legend.get(biome);
                if (letter == null) {
                    if (legend.size() == LETTERS.length()) {
                        throw new IllegalArgumentException("more than " + LETTERS.length() + " biomes in one grid");
                    }
                    letter = LETTERS.charAt(legend.size());
                    legend.put(biome, letter);
                }
                line.append(letter);
            }
            rows.add(line.toString());
        }
        StringBuilder header = new StringBuilder("biome-grid " + (x & ~3) + " " + y + " " + (z & ~3));
        legend.forEach((biome, letter) -> header.append(' ').append(letter).append('=')
            .append(biome.startsWith("minecraft:") ? biome.substring("minecraft:".length()) : biome));
        List<String> lines = new ArrayList<>();
        lines.add(header.toString());
        lines.addAll(rows);
        return lines;
    }

    /** The biomes the game stores for a world seed, as a grid of cells from the block (x, y, z) south-east. */
    public static List<String> grid(OverworldBiomes biomes, long worldSeed, int x, int y, int z, int columns, int rows) {
        OverworldBiomes.Sampler sampler = biomes.newSampler().seed(worldSeed);
        int quartY = Math.max(biomes.minQuartY(), Math.min(biomes.maxQuartY(), y >> 2));
        String[][] cells = new String[rows][columns];
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                cells[row][column] = biomes.parameters().biomes().get(sampler.biome((x >> 2) + column, quartY, (z >> 2) + row));
            }
        }
        return grid(x, y, z, cells);
    }
}
