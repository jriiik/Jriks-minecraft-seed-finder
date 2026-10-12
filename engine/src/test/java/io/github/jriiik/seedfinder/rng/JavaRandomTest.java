package io.github.jriiik.seedfinder.rng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** java.util.Random uses the same algorithm, so it is a perfect reference. */
class JavaRandomTest {

    private static final long[] SEEDS = {0L, 1L, -1L, 42L, Long.MIN_VALUE, Long.MAX_VALUE, 0x5DEECE66DL, -7261648964369397258L};
    private static final int[] BOUNDS = {1, 2, 3, 5, 7, 16, 24, 26, 32, 61, 100, 1 << 30, (1 << 30) + 1, Integer.MAX_VALUE};

    @Test
    void matchesJavaUtilRandomForEveryMethod() {
        for (long seed : SEEDS) {
            Random expected = new Random(seed);
            JavaRandom actual = new JavaRandom(seed);
            for (int i = 0; i < 200; i++) {
                assertEquals(expected.nextInt(), actual.nextInt(), "nextInt, seed " + seed);
                assertEquals(expected.nextLong(), actual.nextLong(), "nextLong, seed " + seed);
                assertEquals(expected.nextFloat(), actual.nextFloat(), "nextFloat, seed " + seed);
                assertEquals(expected.nextDouble(), actual.nextDouble(), "nextDouble, seed " + seed);
                assertEquals(expected.nextBoolean(), actual.nextBoolean(), "nextBoolean, seed " + seed);
                for (int bound : BOUNDS) {
                    assertEquals(expected.nextInt(bound), actual.nextInt(bound), "nextInt(" + bound + "), seed " + seed);
                }
            }
        }
    }

    @Test
    void setSeedRestartsTheSequence() {
        JavaRandom random = new JavaRandom(5);
        long first = random.nextLong();
        random.nextLong();
        random.setSeed(5);
        assertEquals(first, random.nextLong());
    }

    @Test
    void skipMatchesCallingNextRepeatedly() {
        for (long steps : new long[] {0, 1, 2, 3, 17, 1000}) {
            JavaRandom stepped = new JavaRandom(123);
            for (long i = 0; i < steps; i++) {
                stepped.next(32);
            }
            JavaRandom skipped = new JavaRandom(123);
            skipped.skip(steps);
            assertEquals(stepped.state(), skipped.state(), "skip(" + steps + ")");
        }
    }

    @Test
    void negativeSkipGoesBackwards() {
        JavaRandom random = new JavaRandom(99);
        long start = random.state();
        random.skip(12345);
        random.skip(-12345);
        assertEquals(start, random.state());
        random.skip(-1);
        random.next(32);
        assertEquals(start, random.state());
    }

    @Test
    void withStateUsesTheRawState() {
        JavaRandom seeded = new JavaRandom(77);
        JavaRandom copy = JavaRandom.withState(seeded.state());
        assertEquals(seeded.nextLong(), copy.nextLong());
    }

    @Test
    void rejectsNonPositiveBounds() {
        JavaRandom random = new JavaRandom(1);
        assertThrows(IllegalArgumentException.class, () -> random.nextInt(0));
        assertThrows(IllegalArgumentException.class, () -> random.nextInt(-5));
    }
}
