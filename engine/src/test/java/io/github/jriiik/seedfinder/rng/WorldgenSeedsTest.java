package io.github.jriiik.seedfinder.rng;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.jriiik.seedfinder.Fixtures;
import org.junit.jupiter.api.Test;

/** Compares our seeding formulas with values recorded from Minecraft 26.3 itself. */
class WorldgenSeedsTest {

    private static final JsonObject RNG = Fixtures.load("26.3", "rng.json");

    @Test
    void largeFeatureWithSaltMatchesTheGame() {
        JsonArray rows = RNG.getAsJsonArray("large_feature_with_salt");
        assertEquals(200, rows.size());
        for (JsonElement element : rows) {
            JsonObject row = element.getAsJsonObject();
            long seed = WorldgenSeeds.largeFeatureWithSalt(seed(row), row.get("x").getAsInt(), row.get("z").getAsInt(), row.get("salt").getAsInt());
            assertSamples(row, new JavaRandom(seed));
        }
    }

    @Test
    void largeFeatureMatchesTheGame() {
        for (JsonElement element : RNG.getAsJsonArray("large_feature")) {
            JsonObject row = element.getAsJsonObject();
            long seed = WorldgenSeeds.largeFeature(seed(row), row.get("x").getAsInt(), row.get("z").getAsInt());
            assertSamples(row, new JavaRandom(seed));
        }
    }

    @Test
    void decorationAndFeatureSeedsMatchTheGame() {
        for (JsonElement element : RNG.getAsJsonArray("decoration")) {
            JsonObject row = element.getAsJsonObject();
            long decorationSeed = WorldgenSeeds.decoration(seed(row), row.get("x").getAsInt(), row.get("z").getAsInt());
            assertEquals(Long.parseLong(row.get("decoration_seed").getAsString()), decorationSeed, row.toString());
            assertSamples(row, new JavaRandom(decorationSeed));

            JsonArray features = row.getAsJsonArray("feature_next_long");
            int i = 0;
            for (int step = 0; step < 3; step++) {
                for (int index = 0; index < 3; index++) {
                    JavaRandom feature = new JavaRandom(WorldgenSeeds.feature(decorationSeed, index, step));
                    assertEquals(Long.parseLong(features.get(i++).getAsString()), feature.nextLong(), "feature " + index + "/" + step);
                }
            }
        }
    }

    private static long seed(JsonObject row) {
        return Long.parseLong(row.get("seed").getAsString());
    }

    /** The oracle drew these values in exactly this order. */
    private static void assertSamples(JsonObject row, JavaRandom random) {
        JsonObject s = row.getAsJsonObject("samples");
        String context = row.toString();
        assertEquals(s.get("next_int_24").getAsInt(), random.nextInt(24), context);
        assertEquals(s.get("next_int_32").getAsInt(), random.nextInt(32), context);
        assertEquals(s.get("next_int").getAsInt(), random.nextInt(), context);
        assertEquals(Long.parseLong(s.get("next_long").getAsString()), random.nextLong(), context);
        assertEquals(s.get("next_float").getAsFloat(), random.nextFloat(), context);
        assertEquals(s.get("next_double").getAsDouble(), random.nextDouble(), context);
        assertEquals(s.get("next_boolean").getAsBoolean(), random.nextBoolean(), context);
    }
}
