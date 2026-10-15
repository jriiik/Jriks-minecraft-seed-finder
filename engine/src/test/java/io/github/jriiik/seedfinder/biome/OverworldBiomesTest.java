package io.github.jriiik.seedfinder.biome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.jriiik.seedfinder.Fixtures;
import io.github.jriiik.seedfinder.rng.XoroshiroRandom;
import io.github.jriiik.seedfinder.version.MinecraftVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Compares the overworld biome computation with the answers the oracle recorded from Minecraft 26.3. */
class OverworldBiomesTest {

    private static final JsonObject FIXTURE = Fixtures.load("26.3", "biomes.json");

    private static OverworldBiomes biomes(String preset) {
        return OverworldBiomes.of(MinecraftVersion.V26_3, OverworldBiomes.Preset.byName(preset).orElseThrow());
    }

    @Test
    void noisesMatchTheGame() {
        int checked = 0;
        for (Map.Entry<String, JsonElement> preset : FIXTURE.getAsJsonObject("noises").entrySet()) {
            OverworldBiomes biomes = biomes(preset.getKey());
            for (JsonElement row : preset.getValue().getAsJsonArray()) {
                String[] f = row.getAsString().split(" ");
                NormalNoise noise = biomes.functions().noise(f[1]).create(new XoroshiroRandom(Long.parseLong(f[0])).forkPositional());
                float value = noise.get(Double.parseDouble(f[2]), Double.parseDouble(f[3]), Double.parseDouble(f[4]));
                assertEquals(Float.parseFloat(f[5]), value, preset.getKey() + ": " + row);
                checked++;
            }
        }
        assertEquals(288, checked);
    }

    @Test
    void climateMatchesTheGameBitForBit() {
        JsonArray depthY = FIXTURE.getAsJsonArray("depth_y");
        int checked = 0;
        for (Map.Entry<String, JsonElement> preset : FIXTURE.getAsJsonObject("climate").entrySet()) {
            OverworldBiomes biomes = biomes(preset.getKey());
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
                int[] functions = {ClimateFunctions.TEMPERATURE, ClimateFunctions.VEGETATION, ClimateFunctions.CONTINENTS, ClimateFunctions.EROSION,
                    ClimateFunctions.RIDGES};
                for (int i = 0; i < functions.length; i++) {
                    assertBits(Float.parseFloat(f[2 + i]), sampler.climate(functions[i], x, 0, z), preset.getKey() + " seed " + seed + ": " + row);
                    checked++;
                }
                for (int i = 0; i < depthY.size(); i++) {
                    float depth = sampler.climate(ClimateFunctions.DEPTH, x, depthY.get(i).getAsInt(), z);
                    assertBits(Float.parseFloat(f[7 + i]), depth, preset.getKey() + " seed " + seed + " depth at y " + depthY.get(i) + ": " + row);
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

    private static void assertBits(float expected, float actual, String context) {
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual), () -> context + ": expected " + expected + " but was " + actual);
    }

    @Test
    void searchMatchesTheGameIncludingTies() {
        BiomeParameters parameters = biomes("minecraft:normal").parameters();
        int previous = -1;
        int ties = 0;
        for (JsonElement row : FIXTURE.getAsJsonArray("search")) {
            String[] f = row.getAsString().split(" ");
            long[] target = new long[7];
            for (int d = 0; d < 6; d++) {
                target[d] = Long.parseLong(f[d]);
            }
            int expected = Integer.parseInt(f[6]);
            previous = parameters.search(target, previous);
            assertEquals(parameters.biomes().get(expected), parameters.biomes().get(parameters.biomeOfEntry(previous)), row.toString());
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

    @Test
    void generatedChunksMatchTheGame() {
        int checked = 0;
        for (Map.Entry<String, JsonElement> preset : FIXTURE.getAsJsonObject("chunks").entrySet()) {
            OverworldBiomes biomes = biomes(preset.getKey());
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

    @Test
    void zoomMatchesTheGame() {
        int checked = 0;
        for (Map.Entry<String, JsonElement> entry : FIXTURE.getAsJsonObject("zoom").entrySet()) {
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

    @Test
    void overworldHeightAndListAreRead() {
        OverworldBiomes biomes = biomes("normal");
        assertEquals(-16, biomes.minQuartY());
        assertEquals(79, biomes.maxQuartY());
        assertEquals(7594, biomes.parameters().entryCount());
        assertTrue(biomes.parameters().biomeIndex("plains") >= 0);
        assertEquals(biomes.parameters().biomeIndex("minecraft:plains"), biomes.parameters().biomeIndex("plains"));
        assertEquals(-1, biomes.parameters().biomeIndex("minecraft:nether_wastes"));
    }
}
