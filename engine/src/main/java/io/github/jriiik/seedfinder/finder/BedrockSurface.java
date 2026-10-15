package io.github.jriiik.seedfinder.finder;

import io.github.jriiik.seedfinder.rng.JavaRandom;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.IntStream;

/**
 * The noted blocks of one random bedrock surface of the nether (the floor or the roof), compiled for
 * {@link NetherBedrockSearch}.
 *
 * <p>In a world, each block of the surface draws its number from the old 48-bit generator, started in the state
 * {@code S ^ X}: S is the block's position number ({@code Mth.getSeed}, lowest 48 bits) and X is one hidden 48-bit
 * number shared by the whole surface. The generator's first step gives {@code u = (a * (S ^ X) + c) mod 2^48}, and
 * the block's 24-bit number is {@code u >>> 24}; whether the block is bedrock depends on that number being below the
 * height's threshold. So a noted block allows exactly the values of {@code u} in one stretch of the circle of 48-bit
 * numbers: {@code (u - low) mod 2^48 < length}. Its {@link #bits() information} is {@code log2(2^48 / length)}: a
 * hidden number unrelated to the block passes it with the chance {@code length / 2^48}.
 *
 * <p>The blocks are kept strongest first (shortest stretch), which is the order the search tests them in.
 */
final class BedrockSurface {

    static final long A = JavaRandom.MULTIPLIER;
    static final long C = JavaRandom.ADDEND;
    static final long MASK = JavaRandom.MASK;
    /** 2^48: every value of {@code u}. */
    static final long FULL = 1L << 48;

    private final int hash;
    private final long[] positions;
    private final long[] lows;
    private final long[] lengths;
    private final double bits;
    private final boolean impossible;

    /**
     * @param hash      {@code hashCode()} of the rule's random name: X comes from {@code nextLong()} of the old
     *                  generator set to {@code hash ^ P}
     * @param positions per block: its position number S ({@code Mth.getSeed}, lowest 48 bits)
     * @param lows      per block: where its allowed stretch of {@code u} starts
     * @param lengths   per block: how long the stretch is (1 to 2^48); a block allowing every value carries no
     *                  information and is left out, a block allowing none makes the surface fit nothing
     */
    BedrockSurface(int hash, long[] positions, long[] lows, long[] lengths) {
        if (positions.length != lows.length || positions.length != lengths.length) {
            throw new IllegalArgumentException("one position, low and length per block");
        }
        this.hash = hash;
        boolean none = false;
        for (long length : lengths) {
            if (length < 0 || length > FULL) {
                throw new IllegalArgumentException("length " + length + " is not between 0 and 2^48");
            }
            none |= length == 0;
        }
        this.impossible = none;
        // Strongest first; blocks of equal strength keep their order (the sort is stable).
        int[] order = IntStream.range(0, lengths.length)
            .filter(i -> lengths[i] < FULL)
            .boxed()
            .sorted(Comparator.comparingLong(i -> lengths[i]))
            .mapToInt(Integer::intValue)
            .toArray();
        this.positions = new long[order.length];
        this.lows = new long[order.length];
        this.lengths = new long[order.length];
        double sum = 0;
        for (int k = 0; k < order.length; k++) {
            this.positions[k] = positions[order[k]] & MASK;
            this.lows[k] = lows[order[k]] & MASK;
            this.lengths[k] = lengths[order[k]];
            sum += bitsOf(this.lengths[k]);
        }
        this.bits = sum;
    }

    /** The information of one block: {@code log2(2^48 / length)} (infinite for a block that allows nothing). */
    static double bitsOf(long length) {
        return length == 0 ? Double.POSITIVE_INFINITY : 48 - Math.log((double) length) / Math.log(2);
    }

    int hash() {
        return hash;
    }

    /** How many informative blocks there are. */
    int size() {
        return positions.length;
    }

    long position(int block) {
        return positions[block];
    }

    long low(int block) {
        return lows[block];
    }

    long length(int block) {
        return lengths[block];
    }

    /** The sum of the blocks' information: a hidden number unrelated to them passes all with the chance 2^-bits. */
    double bits() {
        return bits;
    }

    /** True when some block allows no value at all, so that no world fits. */
    boolean impossible() {
        return impossible;
    }

    /** Whether the hidden number X fits every block (exactly, as the game computes it). */
    boolean accepts(long x) {
        if (impossible) {
            return false;
        }
        for (int i = 0; i < positions.length; i++) {
            long u = ((positions[i] ^ x) * A + C) & MASK;
            if (((u - lows[i]) & MASK) >= lengths[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return "BedrockSurface[" + positions.length + " blocks, " + String.format(Locale.ROOT, "%.1f", bits) + " bits"
            + (impossible ? ", fits nothing" : "") + "]";
    }
}
