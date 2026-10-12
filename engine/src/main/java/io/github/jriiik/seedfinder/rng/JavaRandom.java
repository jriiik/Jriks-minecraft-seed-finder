package io.github.jriiik.seedfinder.rng;

/**
 * Minecraft's "legacy" random number generator. It is the same algorithm as {@code java.util.Random}:
 * a 48-bit linear congruential generator (LCG).
 *
 * <p>We keep our own copy instead of using {@code java.util.Random} because seed finding needs direct access
 * to the internal 48-bit state, and because this class has to behave exactly like Minecraft's
 * {@code LegacyRandomSource}, including the order in which values are drawn.
 */
public final class JavaRandom {

    public static final long MULTIPLIER = 0x5DEECE66DL;
    public static final long ADDEND = 0xBL;
    /** The generator only keeps 48 bits of state. */
    public static final long MASK = (1L << 48) - 1;

    private long state;

    /** Creates a generator seeded like {@code new java.util.Random(seed)}. */
    public JavaRandom(long seed) {
        setSeed(seed);
    }

    /** Creates a generator from a raw internal state (no scrambling; only the lowest 48 bits are kept). */
    public static JavaRandom withState(long state) {
        JavaRandom random = new JavaRandom(0);
        random.state = state & MASK;
        return random;
    }

    /** Re-seeds like {@code java.util.Random#setSeed}: the seed is scrambled and cut down to 48 bits. */
    public void setSeed(long seed) {
        state = (seed ^ MULTIPLIER) & MASK;
    }

    /** The current internal 48-bit state. */
    public long state() {
        return state;
    }

    /** Advances the state once and returns its top {@code bits} bits. */
    public int next(int bits) {
        state = (state * MULTIPLIER + ADDEND) & MASK;
        return (int) (state >>> (48 - bits));
    }

    public int nextInt() {
        return next(32);
    }

    /** A value in {@code [0, bound)}, drawn exactly like {@code java.util.Random#nextInt(int)}. */
    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive, got " + bound);
        }
        if ((bound & -bound) == bound) {
            return (int) ((bound * (long) next(31)) >> 31);
        }
        int bits;
        int value;
        do {
            bits = next(31);
            value = bits % bound;
        } while (bits - value + (bound - 1) < 0);
        return value;
    }

    public long nextLong() {
        return ((long) next(32) << 32) + next(32);
    }

    public boolean nextBoolean() {
        return next(1) != 0;
    }

    public float nextFloat() {
        return next(24) * 0x1.0p-24f;
    }

    public double nextDouble() {
        return (((long) next(26) << 27) + next(27)) * 0x1.0p-53;
    }

    /**
     * Moves the generator as if {@link #next(int)} had been called {@code steps} times. Negative values step
     * backwards, which works because the generator repeats itself every 2^48 steps.
     */
    public void skip(long steps) {
        long remaining = steps & MASK;
        long multiplier = 1;
        long addend = 0;
        long stepMultiplier = MULTIPLIER;
        long stepAddend = ADDEND;
        while (remaining != 0) {
            if ((remaining & 1) != 0) {
                multiplier = (multiplier * stepMultiplier) & MASK;
                addend = (addend * stepMultiplier + stepAddend) & MASK;
            }
            stepAddend = ((stepMultiplier + 1) * stepAddend) & MASK;
            stepMultiplier = (stepMultiplier * stepMultiplier) & MASK;
            remaining >>>= 1;
        }
        state = (state * multiplier + addend) & MASK;
    }
}
