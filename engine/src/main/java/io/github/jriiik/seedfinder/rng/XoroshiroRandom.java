package io.github.jriiik.seedfinder.rng;

/**
 * Minecraft's {@code XoroshiroRandomSource}: the Xoroshiro128++ generator with Minecraft's way of drawing numbers
 * from it. Since 1.18 it makes the per-block randomness of the overworld (bedrock, ores, surface), which depends on
 * all 64 bits of the world seed.
 *
 * <p>Different draws use different bits: {@link #nextInt} uses the low 32 bits of the output, {@link #nextFloat}
 * and {@link #nextDouble} the high ones.
 */
public final class XoroshiroRandom implements RandomSource {

    private long lo;
    private long hi;

    /** Seeded like {@code new XoroshiroRandomSource(seed)}: the seed is spread over 128 bits first. */
    public XoroshiroRandom(long seed) {
        this(RandomSupport.upgradeSeedTo128bit(seed));
    }

    /** Starts from a raw 128-bit state (no mixing). */
    public XoroshiroRandom(RandomSupport.Seed128 state) {
        this(state.lo(), state.hi());
    }

    /** Starts from a raw 128-bit state (no mixing). An all-zero state is replaced, because it would only give zeros. */
    public XoroshiroRandom(long lo, long hi) {
        if ((lo | hi) == 0) {
            lo = RandomSupport.GOLDEN_RATIO_64;
            hi = RandomSupport.SILVER_RATIO_64;
        }
        this.lo = lo;
        this.hi = hi;
    }

    /** The first {@link #nextLong()} of {@code new XoroshiroRandom(lo, hi)}, without creating it. */
    public static long firstLong(long lo, long hi) {
        if ((lo | hi) == 0) {
            lo = RandomSupport.GOLDEN_RATIO_64;
            hi = RandomSupport.SILVER_RATIO_64;
        }
        return Long.rotateLeft(lo + hi, 17) + lo;
    }

    @Override
    public long nextLong() {
        long s0 = lo;
        long s1 = hi;
        long result = Long.rotateLeft(s0 + s1, 17) + s0;
        s1 ^= s0;
        lo = Long.rotateLeft(s0, 49) ^ s1 ^ (s1 << 21);
        hi = Long.rotateLeft(s1, 28);
        return result;
    }

    @Override
    public int nextInt() {
        return (int) nextLong();
    }

    /** Lemire's method on the low 32 bits; unlike {@link JavaRandom}, powers of two get no special case. */
    @Override
    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive, got " + bound);
        }
        long product = Integer.toUnsignedLong(nextInt()) * bound;
        long fraction = product & 0xFFFFFFFFL;
        if (fraction < bound) {
            int threshold = Integer.remainderUnsigned(-bound, bound);
            while (fraction < threshold) {
                product = Integer.toUnsignedLong(nextInt()) * bound;
                fraction = product & 0xFFFFFFFFL;
            }
        }
        return (int) (product >> 32);
    }

    /** The top {@code bits} bits of the next output. */
    public long nextBits(int bits) {
        return nextLong() >>> (64 - bits);
    }

    @Override
    public float nextFloat() {
        return nextBits(24) * 0x1.0p-24f;
    }

    @Override
    public double nextDouble() {
        return nextBits(53) * 0x1.0p-53;
    }

    @Override
    public boolean nextBoolean() {
        return (nextLong() & 1) != 0;
    }

    /** A new generator seeded from the next two outputs. */
    public XoroshiroRandom fork() {
        long forkLo = nextLong();
        long forkHi = nextLong();
        return new XoroshiroRandom(forkLo, forkHi);
    }

    @Override
    public XoroshiroPositionalFactory forkPositional() {
        long seedLo = nextLong();
        long seedHi = nextLong();
        return new XoroshiroPositionalFactory(seedLo, seedHi);
    }
}
