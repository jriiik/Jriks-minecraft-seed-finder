package io.github.jriiik.seedfinder.biome;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The overworld's biome list: for each entry, a biome and the box of climate values (temperature, humidity,
 * continentalness, erosion, depth, weirdness, and an offset) where it applies. A climate point gets the biome of the
 * closest box; boxes are compared in the game's units (the parameter x 10000, rounded towards zero).
 */
public final class BiomeParameters {

    private final List<String> biomes;
    private final Map<String, Integer> biomeIndexes = new HashMap<>();
    private final int[] entryBiome;
    private final long[][] entryMin;
    private final long[][] entryMax;
    private final int[][] entriesOfBiome;
    private final BiomeTree tree;

    BiomeParameters(List<String> biomes, int[] entryBiome, long[][] entryMin, long[][] entryMax, int treeChildrenPerNode) {
        this.biomes = Collections.unmodifiableList(new ArrayList<>(biomes));
        for (int i = 0; i < biomes.size(); i++) {
            biomeIndexes.put(biomes.get(i), i);
        }
        this.entryBiome = entryBiome;
        this.entryMin = entryMin;
        this.entryMax = entryMax;
        List<List<Integer>> byBiome = new ArrayList<>();
        biomes.forEach(biome -> byBiome.add(new ArrayList<>()));
        for (int entry = 0; entry < entryBiome.length; entry++) {
            byBiome.get(entryBiome[entry]).add(entry);
        }
        this.entriesOfBiome = byBiome.stream().map(list -> list.stream().mapToInt(Integer::intValue).toArray()).toArray(int[][]::new);
        this.tree = new BiomeTree(entryMin, entryMax, treeChildrenPerNode);
    }

    /**
     * Reads the oracle's export ({@code biome_parameters.json}, one dimension's part).
     *
     * @param treeChildrenPerNode how many children a node of the version's search tree has
     */
    static BiomeParameters parse(JsonObject json, int treeChildrenPerNode) {
        List<String> biomes = new ArrayList<>();
        json.getAsJsonArray("biomes").forEach(biome -> biomes.add(biome.getAsString()));
        String[] names = {"temperature", "humidity", "continentalness", "erosion", "depth", "weirdness"};
        long[][][] intervals = new long[names.length][][];
        JsonObject intervalJson = json.getAsJsonObject("intervals");
        for (int d = 0; d < names.length; d++) {
            JsonArray list = intervalJson.getAsJsonArray(names[d]);
            intervals[d] = new long[list.size()][];
            for (int i = 0; i < list.size(); i++) {
                String[] bounds = list.get(i).getAsString().split(" ");
                intervals[d][i] = new long[] {Long.parseLong(bounds[0]), Long.parseLong(bounds[1])};
            }
        }
        JsonArray entries = json.getAsJsonArray("entries");
        int[] entryBiome = new int[entries.size()];
        long[][] entryMin = new long[entries.size()][BiomeTree.DIMENSIONS];
        long[][] entryMax = new long[entries.size()][BiomeTree.DIMENSIONS];
        int index = 0;
        for (JsonElement element : entries) {
            String[] fields = element.getAsString().split(" ");
            if (fields.length != 8) {
                throw new IllegalArgumentException("biome entry needs 8 fields: " + element);
            }
            entryBiome[index] = Integer.parseInt(fields[0]);
            for (int d = 0; d < names.length; d++) {
                long[] interval = intervals[d][Integer.parseInt(fields[1 + d])];
                entryMin[index][d] = interval[0];
                entryMax[index][d] = interval[1];
            }
            long offset = Long.parseLong(fields[7]);
            entryMin[index][6] = offset;
            entryMax[index][6] = offset;
            index++;
        }
        return new BiomeParameters(biomes, entryBiome, entryMin, entryMax, treeChildrenPerNode);
    }

    /** The biome ids, like {@code minecraft:plains}; a biome's position here is its index. */
    public List<String> biomes() {
        return biomes;
    }

    /** The index of a biome id ({@code minecraft:} may be left out), or -1 if the overworld has no such biome. */
    public int biomeIndex(String biome) {
        Integer index = biomeIndexes.get(biome.contains(":") ? biome : "minecraft:" + biome);
        return index == null ? -1 : index;
    }

    int entryCount() {
        return entryBiome.length;
    }

    int biomeOfEntry(int entry) {
        return entryBiome[entry];
    }

    /**
     * The entry the game picks for a climate point (6 values, then 0 for the offset), as {@code findValue} does on a
     * thread whose last answer was {@code previous} (-1 for none).
     */
    int search(long[] target, int previous) {
        return tree.search(target, previous);
    }

    long distance(int entry, long[] target) {
        return BiomeTree.distance(entryMin[entry], entryMax[entry], target);
    }

    /**
     * True if the game can pick {@code biome} at this climate point: when several entries are equally close, which
     * one the game takes depends on its earlier lookups on the same thread, so any of the closest counts.
     */
    boolean isClosest(int biome, long[] target) {
        long closest = distance(tree.search(target, -1), target);
        for (int entry : entriesOfBiome[biome]) {
            if (distance(entry, target) == closest) {
                return true;
            }
        }
        return false;
    }

    /** All biomes the game can pick at this point (slow: checks every entry; for tests). */
    List<Integer> closestBiomes(long[] target) {
        long best = Long.MAX_VALUE;
        List<Integer> found = new ArrayList<>();
        for (int entry = 0; entry < entryBiome.length; entry++) {
            long distance = distance(entry, target);
            if (distance < best) {
                best = distance;
                found.clear();
            }
            if (distance == best && !found.contains(entryBiome[entry])) {
                found.add(entryBiome[entry]);
            }
        }
        return found;
    }
}
