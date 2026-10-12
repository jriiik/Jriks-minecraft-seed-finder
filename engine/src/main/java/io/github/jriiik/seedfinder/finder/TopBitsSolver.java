package io.github.jriiik.seedfinder.finder;

import io.github.jriiik.seedfinder.rng.JavaRandom;

/**
 * Given the lowest 35 bits of a structure seed, lists the only values of the top 13 bits for which one observed
 * structure gets its x coordinate right, instead of trying all 8192.
 *
 * <p>Why this works: Minecraft xors the seed with a 35-bit constant, so the top 13 bits pass through unchanged and
 * the generator's next state is {@code P + 2^35 * (a * top mod 2^13)}, where P only depends on the low 35 bits.
 * nextInt(n) then sees {@code B + 2^18 * T} with {@code T = (P_high + a * top) mod 2^13}, and matching the observed
 * x mod the odd part of n is a simple congruence for T. (If nextInt had to re-roll, which happens about once in
 * 100 million seeds, that seed is missed.)
 */
final class TopBitsSolver {

    static final int LOW_BITS = 35;
    private static final int TOP_VALUES = 1 << 13;
    private static final int TOP_MASK = TOP_VALUES - 1;
    private static final long LOW_MASK = (1L << LOW_BITS) - 1;
    private static final int INVERSE_MULTIPLIER = inverseModPowerOfTwo((int) (JavaRandom.MULTIPLIER & TOP_MASK));

    private final long seedOffset;
    private final int offsetX;
    private final int odd;
    private final int twosMask;
    private final int inverseShift;

    TopBitsSolver(ObservationCheck check) {
        this.seedOffset = check.seedOffset();
        this.offsetX = check.offsetX();
        int bound = check.bound();
        int twos = Integer.numberOfTrailingZeros(bound);
        this.odd = bound >> twos;
        this.twosMask = (1 << twos) - 1;
        this.inverseShift = inverseMod((int) ((1L << 18) % odd), odd);
    }

    /** Most candidates per call: one for every {@code odd}-th value of T. */
    int maxCandidates() {
        return (TOP_VALUES + odd - 1) / odd;
    }

    /** Writes the full structure seeds with these low 35 bits that fit the x coordinate into {@code out}; returns how many. */
    int candidates(long low35, long[] out) {
        long state = (((low35 + seedOffset) ^ JavaRandom.MULTIPLIER) * JavaRandom.MULTIPLIER + JavaRandom.ADDEND) & JavaRandom.MASK;
        int low = (int) ((state & LOW_MASK) >>> 17);
        int high = (int) (state >>> LOW_BITS);
        if (((low - offsetX) & twosMask) != 0) {
            return 0;
        }
        int count = 0;
        for (int t = (int) Math.floorMod((long) (offsetX - low) * inverseShift, (long) odd); t < TOP_VALUES; t += odd) {
            int top = ((t - high) * INVERSE_MULTIPLIER) & TOP_MASK;
            out[count++] = low35 | (long) top << LOW_BITS;
        }
        return count;
    }

    private static int inverseMod(int value, int modulus) {
        if (modulus == 1) {
            return 0;
        }
        for (int candidate = 1; candidate < modulus; candidate++) {
            if ((long) value * candidate % modulus == 1) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(value + " has no inverse mod " + modulus);
    }

    private static int inverseModPowerOfTwo(int odd) {
        int inverse = odd; // Newton's iteration doubles the number of correct bits each step
        for (int i = 0; i < 4; i++) {
            inverse *= 2 - odd * inverse;
        }
        return inverse & TOP_MASK;
    }
}
