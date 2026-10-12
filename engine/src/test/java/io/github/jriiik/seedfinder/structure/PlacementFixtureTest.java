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

/**
 * The main Phase 1 check: for every structure set and many seeds, our engine must pick exactly the same chunks
 * as Minecraft 26.3 (recorded by tools/oracle), including frequency rolls and exclusion zones.
 */
class PlacementFixtureTest {

    private static final VersionData DATA = MinecraftVersion.V26_3.data();
    private static final JsonObject PLACEMENTS = Fixtures.load("26.3", "placements.json");

    @TestFactory
    Stream<DynamicTest> everyRandomSpreadSetMatchesTheGame() {
        JsonObject sets = PLACEMENTS.getAsJsonObject("sets");
        int chunkRadius = PLACEMENTS.get("chunk_radius").getAsInt();
        assertEquals(
            DATA.structureSets().stream().filter(s -> s.placement() instanceof RandomSpreadPlacement).count(),
            sets.size(),
            "the fixture should cover every random-spread set");
        return sets.entrySet().stream().map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> {
            StructureSet set = DATA.structureSet(entry.getKey()).orElseThrow();
            RandomSpreadPlacement placement = (RandomSpreadPlacement) set.placement();
            for (Map.Entry<String, JsonElement> bySeed : entry.getValue().getAsJsonObject().getAsJsonObject("seeds").entrySet()) {
                long seed = Long.parseLong(bySeed.getKey());
                StructureLocator locator = new StructureLocator(DATA, seed);
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
        int radius = PLACEMENTS.get("region_radius").getAsInt();
        List<DynamicTest> tests = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : PLACEMENTS.getAsJsonObject("sets").entrySet()) {
            StructureSet set = DATA.structureSet(entry.getKey()).orElseThrow();
            RandomSpreadPlacement placement = (RandomSpreadPlacement) set.placement();
            if (placement.spacing() == 1) {
                continue;
            }
            tests.add(DynamicTest.dynamicTest(entry.getKey(), () -> {
                for (Map.Entry<String, JsonElement> bySeed : entry.getValue().getAsJsonObject().getAsJsonObject("seeds").entrySet()) {
                    StructureLocator locator = new StructureLocator(DATA, Long.parseLong(bySeed.getKey()));
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
