package io.github.jriiik.seedfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.jriiik.seedfinder.rng.JavaRandom;
import java.util.Arrays;
import java.util.OptionalLong;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class SeedsTest {

    private static final JsonObject RANDOM_SEEDS = Fixtures.load("26.3", "random_seeds.json");

    @Test
    void parsingMatchesTheGame() {
        for (JsonElement element : RANDOM_SEEDS.getAsJsonArray("parse_seed")) {
            String text = element.getAsJsonArray().get(0).getAsString();
            JsonElement expected = element.getAsJsonArray().get(1);
            OptionalLong want = expected.isJsonNull() ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(expected.getAsString()));
            assertEquals(want, Seeds.parse(text), "'" + text + "'");
        }
    }

    @Test
    void randomSeedsFromTheGameAreFoundFromTheirStructureSeed() {
        int checked = 0;
        for (JsonElement element : RANDOM_SEEDS.getAsJsonArray("next_long")) {
            long worldSeed = Long.parseLong(element.getAsJsonArray().get(1).getAsString());
            long[] candidates = Seeds.randomSeedCandidates(Seeds.structureSeed(worldSeed));
            assertTrue(Arrays.stream(candidates).anyMatch(c -> c == worldSeed), worldSeed + " not in " + Arrays.toString(candidates));
            assertTrue(Seeds.couldBeRandomSeed(worldSeed));
            checked++;
        }
        assertEquals(300, checked);
    }

    @Test
    void randomSeedCandidatesAreRealNextLongOutputs() {
        SplittableRandom random = new SplittableRandom(48);
        int[] sizes = new int[3];
        for (int i = 0; i < 3000; i++) {
            long structureSeed = random.nextLong() & Seeds.STRUCTURE_SEED_MASK;
            long[] candidates = Seeds.randomSeedCandidates(structureSeed);
            sizes[candidates.length]++;
            for (long candidate : candidates) {
                assertEquals(structureSeed, Seeds.structureSeed(candidate));
                if (i >= 300) {
                    continue;
                }
                // Rebuild the generator state that would give this seed and run the generator forwards.
                long second = (((long) (int) candidate) & 0xFFFF_FFFFL) << 16;
                boolean genuine = false;
                for (long hidden = 0; hidden < 1 << 16 && !genuine; hidden++) {
                    long before = (((second | hidden) - JavaRandom.ADDEND) * JavaRandom.MULTIPLIER_INVERSE) & JavaRandom.MASK;
                    long start = ((before - JavaRandom.ADDEND) * JavaRandom.MULTIPLIER_INVERSE) & JavaRandom.MASK;
                    genuine = JavaRandom.withState(start).nextLong() == candidate;
                }
                assertTrue(genuine, "candidate " + candidate + " for " + structureSeed);
            }
        }
        // Over all structure seeds: none 11.4%, one 77.3%, two 11.4% (exact fractions 7441, 50654, 7441 of 65536).
        assertTrue(sizes[0] > 250 && sizes[0] < 430, Arrays.toString(sizes));
        assertTrue(sizes[2] > 250 && sizes[2] < 430, Arrays.toString(sizes));
    }

    @Test
    void randomSeedsOfJavaRandomAreAlwaysFound() {
        SplittableRandom random = new SplittableRandom(26);
        for (int i = 0; i < 2000; i++) {
            long worldSeed = new JavaRandom(random.nextLong()).nextLong();
            assertTrue(Seeds.couldBeRandomSeed(worldSeed), Long.toString(worldSeed));
        }
        // The upper half is lowered by one when the lower half's top bit is set; both cases must work.
        assertTrue(Seeds.couldBeRandomSeed(new JavaRandom(42).nextLong()));
        assertFalse(Seeds.couldBeRandomSeed(new JavaRandom(42).nextLong() + (1L << 48)));
    }

    @Test
    void multiplierInverseUndoesOneStep() {
        assertEquals(1L, (JavaRandom.MULTIPLIER * JavaRandom.MULTIPLIER_INVERSE) & JavaRandom.MASK);
    }

    @Test
    void textSeedsAreFoundFromTheirStructureSeed() {
        for (String text : new String[] {"Jrik", "North Carolina", "abc", "Hello World!", "zzzzzzzzzzzzzzzz"}) {
            long worldSeed = Seeds.parse(text).orElseThrow();
            assertEquals(OptionalLong.of(worldSeed), Seeds.textSeedCandidate(Seeds.structureSeed(worldSeed)), text);
        }
        assertEquals(OptionalLong.of(Integer.MIN_VALUE), Seeds.textSeedCandidate(Seeds.structureSeed(Integer.MIN_VALUE)));
        assertEquals(OptionalLong.of(-1L), Seeds.textSeedCandidate(Seeds.structureSeed(-1L)));
        assertEquals(OptionalLong.empty(), Seeds.textSeedCandidate(Seeds.structureSeed(1L << 31)));
        assertEquals(OptionalLong.empty(), Seeds.textSeedCandidate(Seeds.structureSeed(8091867987493326313L)));
    }

    @Test
    void numbersAreUsedAsTheyAre() {
        assertEquals(OptionalLong.of(12345L), Seeds.parse("12345"));
        assertEquals(OptionalLong.of(-8091867987493326313L), Seeds.parse("  -8091867987493326313 "));
        assertEquals(OptionalLong.of(Long.MAX_VALUE), Seeds.parse("9223372036854775807"));
    }

    @Test
    void textBecomesItsHashCode() {
        assertEquals(OptionalLong.of("Jrik".hashCode()), Seeds.parse("Jrik"));
        assertEquals(OptionalLong.of(1772835215L), Seeds.parse("Glacier"));
        // Too big for a long, so Minecraft treats it as text too.
        assertEquals(OptionalLong.of("9223372036854775808".hashCode()), Seeds.parse("9223372036854775808"));
    }

    @Test
    void blankMeansRandom() {
        assertEquals(OptionalLong.empty(), Seeds.parse("   "));
    }

    @Test
    void structureSeedKeepsTheLowest48Bits() {
        assertEquals(12345L, Seeds.structureSeed(12345L));
        assertEquals((1L << 48) - 1, Seeds.structureSeed(-1L));
        assertEquals(Seeds.structureSeed(42L), Seeds.structureSeed(42L + (7L << 48)), "the top 16 bits don't matter");
    }
}
