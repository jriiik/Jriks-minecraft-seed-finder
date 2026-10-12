package io.github.jriiik.seedfinder.rng;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.jriiik.seedfinder.Fixtures;
import io.github.jriiik.seedfinder.bedrock.BedrockDimension;
import io.github.jriiik.seedfinder.bedrock.BedrockRule;
import io.github.jriiik.seedfinder.version.MinecraftVersion;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Compares our Xoroshiro128++ generator and the generators built from it with values recorded from Minecraft 26.3. */
class XoroshiroRandomTest {

    private static final JsonObject FIXTURE = Fixtures.load("26.3", "xoroshiro.json");
    private static final int[] BOUNDS = {1, 3, 24, 100, 1 << 30, 0x55555556, Integer.MAX_VALUE};

    @Test
    void seedMixingMatchesTheGame() {
        for (JsonElement element : FIXTURE.getAsJsonArray("mix_stafford13")) {
            JsonArray row = element.getAsJsonArray();
            assertEquals(asLong(row, 1), RandomSupport.mixStafford13(asLong(row, 0)));
        }
        for (JsonElement element : FIXTURE.getAsJsonArray("upgrade_seed_to_128bit")) {
            JsonArray row = element.getAsJsonArray();
            assertEquals(new RandomSupport.Seed128(asLong(row, 1), asLong(row, 2)), RandomSupport.upgradeSeedTo128bit(asLong(row, 0)));
        }
        for (JsonElement element : FIXTURE.getAsJsonArray("seed_from_hash_of")) {
            JsonArray row = element.getAsJsonArray();
            assertEquals(new RandomSupport.Seed128(asLong(row, 1), asLong(row, 2)), RandomSupport.seedFromHashOf(row.get(0).getAsString()));
        }
    }

    @Test
    void positionalSeedMatchesTheGame() {
        for (JsonElement element : FIXTURE.getAsJsonArray("positional_seed")) {
            JsonArray row = element.getAsJsonArray();
            assertEquals(asLong(row, 3), WorldgenSeeds.positional(row.get(0).getAsInt(), row.get(1).getAsInt(), row.get(2).getAsInt()), row.toString());
        }
    }

    @Test
    void drawsMatchTheGame() {
        for (Map.Entry<String, JsonElement> entry : FIXTURE.getAsJsonObject("seeds").entrySet()) {
            long seed = Long.parseLong(entry.getKey());
            JsonObject s = entry.getValue().getAsJsonObject().getAsJsonObject("samples");
            XoroshiroRandom random = new XoroshiroRandom(seed);
            String context = "seed " + seed;
            assertEquals(Long.parseLong(s.get("next_long").getAsString()), random.nextLong(), context);
            assertEquals(s.get("next_int").getAsInt(), random.nextInt(), context);
            JsonArray bounded = s.getAsJsonArray("next_int_bounded");
            int i = 0;
            for (int bound : BOUNDS) {
                assertEquals(bounded.get(i++).getAsInt(), random.nextInt(bound), context + " bound " + bound);
            }
            while (i < bounded.size()) {
                assertEquals(bounded.get(i++).getAsInt(), random.nextInt(0x55555556), context);
            }
            assertEquals(s.get("next_float").getAsFloat(), random.nextFloat(), context);
            assertEquals(s.get("next_double").getAsDouble(), random.nextDouble(), context);
            assertEquals(s.get("next_boolean").getAsBoolean(), random.nextBoolean(), context);
            assertEquals(Long.parseLong(s.get("next_long_after").getAsString()), random.nextLong(), context);
        }
    }

    @Test
    void forksMatchTheGame() {
        JsonArray positions = FIXTURE.getAsJsonArray("positional_seed");
        JsonArray names = FIXTURE.getAsJsonArray("seed_from_hash_of");
        for (Map.Entry<String, JsonElement> entry : FIXTURE.getAsJsonObject("seeds").entrySet()) {
            long seed = Long.parseLong(entry.getKey());
            JsonObject s = entry.getValue().getAsJsonObject();
            assertEquals(Long.parseLong(s.get("fork_next_long").getAsString()), new XoroshiroRandom(seed).fork().nextLong());
            XoroshiroPositionalFactory factory = new XoroshiroRandom(seed).forkPositional();
            JsonArray at = s.getAsJsonArray("fork_positional_at_next_long");
            for (int i = 0; i < positions.size(); i++) {
                JsonArray p = positions.get(i).getAsJsonArray();
                assertEquals(Long.parseLong(at.get(i).getAsString()),
                    factory.at(p.get(0).getAsInt(), p.get(1).getAsInt(), p.get(2).getAsInt()).nextLong(), "seed " + seed + " at " + p);
                long state = WorldgenSeeds.positional(p.get(0).getAsInt(), p.get(1).getAsInt(), p.get(2).getAsInt()) ^ factory.seedLo();
                assertEquals(Long.parseLong(at.get(i).getAsString()), XoroshiroRandom.firstLong(state, factory.seedHi()));
            }
            JsonArray fromHash = s.getAsJsonArray("fork_positional_from_hash_of_next_long");
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i).getAsJsonArray().get(0).getAsString();
                assertEquals(Long.parseLong(fromHash.get(i).getAsString()), factory.fromHashOf(name).nextLong(), "seed " + seed + " " + name);
            }
        }
    }

    /** The world's own generators for each bedrock rule (Xoroshiro in the overworld, the old generator in the nether). */
    @Test
    void worldBedrockGeneratorsMatchTheGame() {
        Map<String, BedrockDimension> dimensions = MinecraftVersion.V26_3.data().bedrock();
        JsonArray positions = FIXTURE.getAsJsonArray("positional_seed");
        int checked = 0;
        for (Map.Entry<String, JsonElement> entry : FIXTURE.getAsJsonObject("seeds").entrySet()) {
            long seed = Long.parseLong(entry.getKey());
            JsonObject byDimension = entry.getValue().getAsJsonObject().getAsJsonObject("world_bedrock_factories");
            for (Map.Entry<String, JsonElement> dimension : byDimension.entrySet()) {
                BedrockDimension bedrock = dimensions.get(dimension.getKey());
                RandomState state = new RandomState(seed, bedrock.legacyRandomSource());
                JsonObject byName = dimension.getValue().getAsJsonObject();
                assertEquals(bedrock.rules().stream().map(BedrockRule::randomName).toList(), byName.keySet().stream().toList());
                for (Map.Entry<String, JsonElement> name : byName.entrySet()) {
                    PositionalRandomFactory factory = state.factory(name.getKey());
                    JsonArray values = name.getValue().getAsJsonArray();
                    for (int i = 0; i < positions.size(); i++) {
                        JsonArray p = positions.get(i).getAsJsonArray();
                        assertEquals(Long.parseLong(values.get(i).getAsString()),
                            factory.at(p.get(0).getAsInt(), p.get(1).getAsInt(), p.get(2).getAsInt()).nextLong(),
                            "seed " + seed + " " + dimension.getKey() + " " + name.getKey() + " at " + p);
                        checked++;
                    }
                }
            }
        }
        assertEquals(10 * 3 * 11, checked);
    }

    @Test
    void anAllZeroStateIsReplaced() {
        long expected = new XoroshiroRandom(RandomSupport.GOLDEN_RATIO_64, RandomSupport.SILVER_RATIO_64).nextLong();
        assertEquals(expected, new XoroshiroRandom(0, 0).nextLong());
        assertEquals(expected, XoroshiroRandom.firstLong(0, 0));
    }

    private static long asLong(JsonArray row, int index) {
        return Long.parseLong(row.get(index).getAsString());
    }
}
