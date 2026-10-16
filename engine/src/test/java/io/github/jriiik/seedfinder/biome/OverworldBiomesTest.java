package io.github.jriiik.seedfinder.biome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.jriiik.seedfinder.Fixtures;
import io.github.jriiik.seedfinder.rng.XoroshiroRandom;
import io.github.jriiik.seedfinder.version.MinecraftVersion;
import io.github.jriiik.seedfinder.version.VersionDataLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Compares the overworld biome computation with the answers the oracle recorded from each supported Minecraft version. */
class OverworldBiomesTest {

    private static JsonObject fixture(MinecraftVersion version) {
        return Fixtures.load(version, "biomes.json");
    }

    private static OverworldBiomes biomes(MinecraftVersion version, String preset) {
        return OverworldBiomes.of(version, OverworldBiomes.Preset.byName(preset).orElseThrow());
    }

    /**
     * True if the version's game computes its climate in double arithmetic (1.21.x); its fixtures then hold doubles,
     * otherwise floats.
     */
    private static boolean inDouble(OverworldBiomes biomes) {
        return biomes.functions() instanceof ClassicClimateFunctions;
    }

    /** Bit for bit: as a double where the game computes in double, else as a float (and the value must be one). */
    private static void assertSameAsGame(OverworldBiomes biomes, String expected, double actual, String context) {
        if (inDouble(biomes)) {
            assertEquals(Double.doubleToRawLongBits(Double.parseDouble(expected)), Double.doubleToRawLongBits(actual),
                () -> context + ": expected " + expected + " but was " + actual);
        } else {
            assertEquals(actual, (double) (float) actual, () -> context + ": a float version gave " + actual);
            assertEquals(Float.floatToRawIntBits(Float.parseFloat(expected)), Float.floatToRawIntBits((float) actual),
                () -> context + ": expected " + expected + " but was " + (float) actual);
        }
    }

    @ParameterizedTest
    @MethodSource("io.github.jriiik.seedfinder.Fixtures#versions")
    void noisesMatchTheGame(MinecraftVersion version) {
        int checked = 0;
        for (Map.Entry<String, JsonElement> preset : fixture(version).getAsJsonObject("noises").entrySet()) {
            OverworldBiomes biomes = biomes(version, preset.getKey());
            for (JsonElement row : preset.getValue().getAsJsonArray()) {
                String[] f = row.getAsString().split(" ");
                double value = biomes.functions().noiseValue(f[1], new XoroshiroRandom(Long.parseLong(f[0])).forkPositional(),
                    Double.parseDouble(f[2]), Double.parseDouble(f[3]), Double.parseDouble(f[4]));
                assertSameAsGame(biomes, f[5], value, preset.getKey() + ": " + row);
                checked++;
            }
        }
        assertEquals(288, checked);
    }

    @ParameterizedTest
    @MethodSource("io.github.jriiik.seedfinder.Fixtures#versions")
    void climateMatchesTheGameBitForBit(MinecraftVersion version) {
        JsonArray depthY = fixture(version).getAsJsonArray("depth_y");
        int checked = 0;
        for (Map.Entry<String, JsonElement> preset : fixture(version).getAsJsonObject("climate").entrySet()) {
            OverworldBiomes biomes = biomes(version, preset.getKey());
            OverworldBiomes.Sampler sampler = biomes.newSampler();
            long seed = Long.MIN_VALUE;
            int column = 0;
            for (JsonElement row : preset.getValue().getAsJsonArray()) {
                // 24 columns per seed, seeds in the order of the oracle's lists
                if (column % 24 == 0) {
                    seed = seedOf(preset.getKey(), column / 24);
                    sampler.seed(seed);
                }
                column++;
                String[] f = row.getAsString().split(" ");
                int x = Integer.parseInt(f[0]);
                int z = Integer.parseInt(f[1]);
                int[] functions = {ClimateEvaluator.TEMPERATURE, ClimateEvaluator.VEGETATION, ClimateEvaluator.CONTINENTS, ClimateEvaluator.EROSION,
                    ClimateEvaluator.RIDGES};
                for (int i = 0; i < functions.length; i++) {
                    double value = sampler.exactClimate(functions[i], x, 0, z);
                    assertSameAsGame(biomes, f[2 + i], value, preset.getKey() + " seed " + seed + ": " + row);
                    assertEquals(Float.floatToRawIntBits((float) value), Float.floatToRawIntBits(sampler.climate(functions[i], x, 0, z)));
                    checked++;
                }
                for (int i = 0; i < depthY.size(); i++) {
                    double depth = sampler.exactClimate(ClimateEvaluator.DEPTH, x, depthY.get(i).getAsInt(), z);
                    assertSameAsGame(biomes, f[7 + i], depth, preset.getKey() + " seed " + seed + " depth at y " + depthY.get(i) + ": " + row);
                    checked++;
                }
            }
        }
        assertEquals((4 + 2 + 2) * 24 * 16, checked);
    }

    /** The seeds the oracle used, in its order (BiomeOracle.NORMAL_SEEDS and OTHER_SEEDS). */
    private static long seedOf(String preset, int index) {
        long[] normal = {0L, -1L, "Jrik".hashCode(), 8091867987493326313L};
        long[] other = {1L, -6984854390176336655L};
        return preset.equals("minecraft:normal") ? normal[index] : other[index];
    }

    @ParameterizedTest
    @MethodSource("io.github.jriiik.seedfinder.Fixtures#versions")
    void searchMatchesTheGameIncludingTies(MinecraftVersion version) {
        BiomeParameters parameters = biomes(version, "minecraft:normal").parameters();
        assertEquals(0, searchMismatches(version, parameters));
        int ties = 0;
        for (JsonElement row : fixture(version).getAsJsonArray("search")) {
            String[] f = row.getAsString().split(" ");
            long[] target = target(f);
            int expected = Integer.parseInt(f[6]);
            assertTrue(parameters.isClosest(expected, target), row.toString());
            List<Integer> closest = parameters.closestBiomes(target);
            assertTrue(closest.contains(expected), row.toString());
            if (closest.size() > 1) {
                ties++;
            }
        }
        // the edge targets are meant to produce ties between different biomes; make sure they did
        assertTrue(ties > 10, "only " + ties + " ties");
    }

    /**
     * The search fixture tells the versions' tree shapes apart: the same list in a tree with another number of
     * children per node picks other biomes on some ties, so the fixture guards the number each version uses.
     */
    @ParameterizedTest
    @MethodSource("io.github.jriiik.seedfinder.Fixtures#versions")
    void theSearchFixtureCatchesAWrongTreeShape(MinecraftVersion version) {
        JsonObject list = VersionDataLoader.readBundled(version.displayName(), "biome_parameters.json").getAsJsonObject("overworld");
        int own = OverworldBiomes.treeChildrenPerNode(version);
        for (MinecraftVersion other : MinecraftVersion.values()) {
            int children = OverworldBiomes.treeChildrenPerNode(other);
            int mismatches = searchMismatches(version, BiomeParameters.parse(list, children));
            if (children == own) {
                assertEquals(0, mismatches);
            } else {
                assertTrue(mismatches > 0, children + " children per node fit every lookup of " + version.displayName());
            }
        }
    }

    /** The lookups of the search fixture, in order on one thread, whose biome differs from the game's. */
    private static int searchMismatches(MinecraftVersion version, BiomeParameters parameters) {
        int previous = -1;
        int mismatches = 0;
        for (JsonElement row : fixture(version).getAsJsonArray("search")) {
            String[] f = row.getAsString().split(" ");
            previous = parameters.search(target(f), previous);
            if (!parameters.biomes().get(Integer.parseInt(f[6])).equals(parameters.biomes().get(parameters.biomeOfEntry(previous)))) {
                mismatches++;
            }
        }
        return mismatches;
    }

    private static long[] target(String[] f) {
        long[] target = new long[7];
        for (int d = 0; d < 6; d++) {
            target[d] = Long.parseLong(f[d]);
        }
        return target;
    }

    @ParameterizedTest
    @MethodSource("io.github.jriiik.seedfinder.Fixtures#versions")
    void generatedChunksMatchTheGame(MinecraftVersion version) {
        int checked = 0;
        for (Map.Entry<String, JsonElement> preset : fixture(version).getAsJsonObject("chunks").entrySet()) {
            OverworldBiomes biomes = biomes(version, preset.getKey());
            OverworldBiomes.Sampler sampler = biomes.newSampler();
            for (JsonElement element : preset.getValue().getAsJsonArray()) {
                JsonObject chunk = element.getAsJsonObject();
                long seed = chunk.get("seed").getAsLong();
                int chunkX = chunk.get("chunk_x").getAsInt();
                int chunkZ = chunk.get("chunk_z").getAsInt();
                List<Integer> expected = new ArrayList<>();
                for (String run : chunk.get("biomes").getAsString().split(" ")) {
                    String[] parts = run.split("\\*");
                    for (int i = 0; i < Integer.parseInt(parts[1]); i++) {
                        expected.add(Integer.parseInt(parts[0]));
                    }
                }
                int[] actual = sampler.seed(seed).chunkBiomes(chunkX, chunkZ);
                assertEquals(expected.size(), actual.length);
                for (int i = 0; i < actual.length; i++) {
                    assertEquals(expected.get(i).intValue(), actual[i], preset.getKey() + " seed " + seed + " chunk " + chunkX + " " + chunkZ + " quart " + i);
                }
                // every generated biome is one the cold lookup accepts as possible
                int i = 0;
                for (int qy = biomes.minQuartY(); qy <= biomes.maxQuartY(); qy += 4) {
                    for (int x = 0; x < 4; x++) {
                        for (int y = 0; y < 4; y++) {
                            for (int z = 0; z < 4; z++) {
                                assertTrue(sampler.couldBe(actual[i++], chunkX * 4 + x, qy + y, chunkZ * 4 + z));
                            }
                        }
                    }
                }
                checked++;
            }
        }
        assertEquals(12, checked);
    }

    @ParameterizedTest
    @MethodSource("io.github.jriiik.seedfinder.Fixtures#versions")
    void zoomMatchesTheGame(MinecraftVersion version) {
        int checked = 0;
        for (Map.Entry<String, JsonElement> entry : fixture(version).getAsJsonObject("zoom").entrySet()) {
            JsonObject zoom = entry.getValue().getAsJsonObject();
            long hashedSeed = zoom.get("hashed_seed").getAsLong();
            assertEquals(io.github.jriiik.seedfinder.Seeds.hashedSeed(Long.parseLong(entry.getKey())), hashedSeed);
            for (JsonElement row : zoom.getAsJsonArray("blocks")) {
                String[] f = row.getAsString().split(" ");
                int x = Integer.parseInt(f[0]);
                int y = Integer.parseInt(f[1]);
                int z = Integer.parseInt(f[2]);
                int[] quart = BiomeZoom.quart(hashedSeed, x, y, z);
                assertEquals(Integer.parseInt(f[3]), Math.floorMod(quart[0] + 2 * quart[1] + 4 * quart[2], 8), row.toString());
                boolean among = false;
                for (int[] candidate : BiomeZoom.candidates(x, y, z)) {
                    among |= candidate[0] == quart[0] && candidate[1] == quart[1] && candidate[2] == quart[2];
                }
                assertTrue(among, row.toString());
                checked++;
            }
        }
        assertEquals(450, checked);
    }

    @ParameterizedTest
    @MethodSource("io.github.jriiik.seedfinder.Fixtures#versions")
    void overworldHeightAndListAreRead(MinecraftVersion version) {
        OverworldBiomes biomes = biomes(version, "normal");
        assertEquals(-16, biomes.minQuartY());
        assertEquals(79, biomes.maxQuartY());
        assertEquals(version == MinecraftVersion.V1_21_11 ? 7593 : 7594, biomes.parameters().entryCount());
        assertTrue(biomes.parameters().biomeIndex("plains") >= 0);
        assertEquals(biomes.parameters().biomeIndex("minecraft:plains"), biomes.parameters().biomeIndex("plains"));
        assertEquals(-1, biomes.parameters().biomeIndex("minecraft:nether_wastes"));
        // biomes new in 26.x
        assertEquals(version == MinecraftVersion.V1_21_11, biomes.parameters().biomeIndex("dappled_forest") < 0);
        assertEquals(version == MinecraftVersion.V1_21_11, biomes.parameters().biomeIndex("sulfur_caves") < 0);
    }

    @Test
    void eachVersionReadsOnlyItsOwnClimateFormat() {
        // 1.21.x data in the 26.x reader and the other way round fail when read, instead of computing something else
        JsonObject classic = VersionDataLoader.readBundled(MinecraftVersion.V1_21_11.displayName(), "climate.json");
        JsonObject compiled = VersionDataLoader.readBundled(MinecraftVersion.V26_3.displayName(), "climate.json");
        for (OverworldBiomes.Preset preset : OverworldBiomes.Preset.values()) {
            assertThrows(IllegalArgumentException.class, () -> ClimateFunctions.parse(classic.getAsJsonObject("presets").getAsJsonObject(preset.id()),
                classic.getAsJsonObject("density_functions"), classic.getAsJsonObject("noises")));
            assertThrows(IllegalArgumentException.class, () -> ClassicClimateFunctions.parse(
                compiled.getAsJsonObject("presets").getAsJsonObject(preset.id()), compiled.getAsJsonObject("density_functions"),
                compiled.getAsJsonObject("noises")));
        }
        assertTrue(inDouble(biomes(MinecraftVersion.V1_21_11, "normal")));
        assertTrue(!inDouble(biomes(MinecraftVersion.V26_3, "normal")));
    }
}
