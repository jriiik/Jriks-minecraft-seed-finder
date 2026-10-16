package io.github.jriiik.seedfinder.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.jriiik.seedfinder.Fixtures;
import io.github.jriiik.seedfinder.version.MinecraftVersion;
import io.github.jriiik.seedfinder.version.VersionData;
import io.github.jriiik.seedfinder.world.ChunkPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The main Phase 1 check: for every structure set and many seeds, our engine must pick exactly the same chunks
 * as each supported Minecraft version (recorded by tools/oracle), including frequency rolls and exclusion zones.
 */
@ParameterizedClass
@MethodSource("io.github.jriiik.seedfinder.Fixtures#versions")
class PlacementFixtureTest {

    private final VersionData data;
    private final JsonObject placements;

    PlacementFixtureTest(MinecraftVersion version) {
        data = version.data();
        placements = Fixtures.load(version, "placements.json");
    }

    @TestFactory
    Stream<DynamicTest> everyRandomSpreadSetMatchesTheGame() {
        JsonObject sets = placements.getAsJsonObject("sets");
        int chunkRadius = placements.get("chunk_radius").getAsInt();
        assertEquals(
            data.structureSets().stream().filter(s -> s.placement() instanceof RandomSpreadPlacement).count(),
            sets.size(),
            "the fixture should cover every random-spread set");
        return sets.entrySet().stream().map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> {
            StructureSet set = data.structureSet(entry.getKey()).orElseThrow();
            RandomSpreadPlacement placement = (RandomSpreadPlacement) set.placement();
            for (Map.Entry<String, JsonElement> bySeed : entry.getValue().getAsJsonObject().getAsJsonObject("seeds").entrySet()) {
                long seed = Long.parseLong(bySeed.getKey());
                StructureLocator locator = new StructureLocator(data, seed);
                JsonArray rows = bySeed.getValue().getAsJsonArray();
                if (placement.spacing() == 1) {
                    assertEveryChunkMatches(set, locator, rows, chunkRadius);
                } else {
                    assertEveryRegionMatches(set, placement, locator, rows);
                }
            }
        }));
    }

    private static void assertEveryRegionMatches(StructureSet set, RandomSpreadPlacement placement, StructureLocator locator, JsonArray rows) {
        long seed = locator.worldSeed();
        for (JsonElement element : rows) {
            JsonArray row = element.getAsJsonArray();
            int rx = row.get(0).getAsInt();
            int rz = row.get(1).getAsInt();
            String context = set.id() + " seed " + seed + " region (" + rx + ", " + rz + ")";
            ChunkPos chunk = placement.potentialChunk(seed, rx, rz);
            assertEquals(new ChunkPos(row.get(2).getAsInt(), row.get(3).getAsInt()), chunk, context);
            assertEquals(row.get(4).getAsBoolean(), placement.passesFrequency(seed, chunk.x(), chunk.z()), context + " frequency");
            assertEquals(row.get(5).getAsBoolean(), locator.isStructureChunk(set, chunk.x(), chunk.z()), context + " structure chunk");
            assertTrue(placement.isPlacementChunk(seed, chunk.x(), chunk.z()), context);
        }
    }

    private static void assertEveryChunkMatches(StructureSet set, StructureLocator locator, JsonArray expectedRows, int radius) {
        Set<String> expected = new TreeSet<>();
        expectedRows.forEach(e -> expected.add(e.getAsJsonArray().get(0).getAsInt() + "," + e.getAsJsonArray().get(1).getAsInt()));
        Set<String> actual = new TreeSet<>();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (locator.isStructureChunk(set, x, z)) {
                    actual.add(x + "," + z);
                }
            }
        }
        assertEquals(expected, actual, set.id() + " seed " + locator.worldSeed());
    }

    @TestFactory
    Stream<DynamicTest> candidatesInAnAreaAreExactlyTheStructureChunks() {
        // Cross-check the area search against the per-region fixture rows: within the fixture's regions,
        // candidates() must return exactly the chunks the game marked as structure chunks.
        int radius = placements.get("region_radius").getAsInt();
        List<DynamicTest> tests = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : placements.getAsJsonObject("sets").entrySet()) {
            StructureSet set = data.structureSet(entry.getKey()).orElseThrow();
            RandomSpreadPlacement placement = (RandomSpreadPlacement) set.placement();
            if (placement.spacing() == 1) {
                continue;
            }
            tests.add(DynamicTest.dynamicTest(entry.getKey(), () -> {
                for (Map.Entry<String, JsonElement> bySeed : entry.getValue().getAsJsonObject().getAsJsonObject("seeds").entrySet()) {
                    StructureLocator locator = new StructureLocator(data, Long.parseLong(bySeed.getKey()));
                    Set<ChunkPos> expected = new java.util.HashSet<>();
                    for (JsonElement element : bySeed.getValue().getAsJsonArray()) {
                        JsonArray row = element.getAsJsonArray();
                        boolean inArea = Math.abs(row.get(0).getAsInt()) <= radius && Math.abs(row.get(1).getAsInt()) <= radius;
                        if (inArea && row.get(5).getAsBoolean()) {
                            expected.add(new ChunkPos(row.get(2).getAsInt(), row.get(3).getAsInt()));
                        }
                    }
                    int min = -radius * placement.spacing();
                    int max = (radius + 1) * placement.spacing() - 1;
                    Set<ChunkPos> actual = new java.util.HashSet<>();
                    locator.candidates(set, min, min, max, max).forEach(c -> actual.add(c.chunk()));
                    assertEquals(expected, actual, set.id() + " seed " + bySeed.getKey());
                }
            }));
        }
        return tests.stream();
    }
}
