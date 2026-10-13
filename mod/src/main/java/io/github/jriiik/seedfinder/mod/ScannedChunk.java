package io.github.jriiik.seedfinder.mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The random bedrock layers of one chunk as the client received them. Each layer has 16 rows (z, north to south) of
 * 16 characters (x, west to east): B = bedrock, . = natural stone (so the bedrock rule said no), ? = something else
 * (air, or a block a mineshaft or player may have put there), which tells nothing.
 *
 * @param dimension the dimension id, like {@code minecraft:overworld}
 */
record ScannedChunk(String dimension, int chunkX, int chunkZ, Map<Integer, List<String>> layers) {

    ScannedChunk {
        layers = new TreeMap<>(layers);
    }

    /** How many blocks say something (B or .). */
    int knownBlocks() {
        return (int) layers.values().stream().flatMap(List::stream).flatMapToInt(String::chars).filter(c -> c != '?').count();
    }

    /** The chunk as notes lines, in the format the finder (and the command-line app) reads. */
    List<String> notesLines() {
        List<String> lines = new ArrayList<>();
        String word = dimension.equals("minecraft:overworld") ? "" : " " + dimension.substring(dimension.indexOf(':') + 1).replaceFirst("^the_", "");
        layers.forEach((y, rows) -> {
            lines.add("bedrock-layer" + word + " " + chunkX * 16 + " " + y + " " + chunkZ * 16);
            lines.addAll(rows);
        });
        return lines;
    }
}
