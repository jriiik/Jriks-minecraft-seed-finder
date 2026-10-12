package io.github.jriiik.seedfinder.bedrock;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes bedrock in the same text format the finder reads ({@code ObservationReader}), so a predicted pattern can be
 * compared with what a player noted down.
 */
public final class BedrockText {

    private BedrockText() {
    }

    /**
     * A {@code bedrock-heights} block: the top bedrock block of each column of the floor, as the last digit of its
     * height, one row per z (north to south) and one character per x (west to east).
     */
    public static List<String> heights(BedrockPattern pattern, int minX, int minZ, int sizeX, int sizeZ) {
        List<String> lines = new ArrayList<>();
        lines.add("bedrock-heights" + dimensionWord(pattern.dimension()) + " " + minX + " " + minZ);
        for (int dz = 0; dz < sizeZ; dz++) {
            StringBuilder row = new StringBuilder();
            for (int dx = 0; dx < sizeX; dx++) {
                row.append(Math.abs(pattern.floorTop(minX + dx, minZ + dz)) % 10);
            }
            lines.add(row.toString());
        }
        return lines;
    }

    /** A {@code bedrock-layer} block: B for bedrock, . for anything else. */
    public static List<String> layer(BedrockPattern pattern, int minX, int y, int minZ, int sizeX, int sizeZ) {
        List<String> lines = new ArrayList<>();
        lines.add("bedrock-layer" + dimensionWord(pattern.dimension()) + " " + minX + " " + y + " " + minZ);
        for (int dz = 0; dz < sizeZ; dz++) {
            StringBuilder row = new StringBuilder();
            for (int dx = 0; dx < sizeX; dx++) {
                row.append(pattern.isBedrock(minX + dx, y, minZ + dz) ? 'B' : '.');
            }
            lines.add(row.toString());
        }
        return lines;
    }

    private static String dimensionWord(BedrockDimension dimension) {
        return dimension.id().equals("minecraft:overworld") ? "" : " " + dimension.displayName();
    }
}
