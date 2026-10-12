package io.github.jriiik.seedfinder.bedrock;

import io.github.jriiik.seedfinder.rng.PositionalRandomFactory;

/**
 * One random bedrock layer of a dimension: Minecraft's "vertical gradient" material rule. At and below
 * {@code trueAtAndBelow} the gradient is always true, at and above {@code falseAtAndAbove} always false, and in
 * between it is true with a chance that falls in equal steps (for the overworld floor 80%, 60%, 40%, 20%). Each
 * block draws one number from its own generator ({@link PositionalRandomFactory#at}).
 *
 * <p>A floor places bedrock where the gradient is true; a roof ({@code inverted}) where it is false.
 *
 * @param randomName the name the block generators are derived from, such as {@code minecraft:bedrock_floor}
 */
public record BedrockRule(String randomName, int trueAtAndBelow, int falseAtAndAbove, boolean inverted) {

    /** Number of possible values of the 24-bit number behind {@code nextFloat()}. */
    static final int FLOAT_STEPS = 1 << 24;

    public BedrockRule {
        if (falseAtAndAbove <= trueAtAndBelow) {
            throw new IllegalArgumentException("Bedrock rule " + randomName + " has an empty gradient");
        }
    }

    /** True when the block at this height is random (strictly between the two fixed parts). */
    public boolean isRandomAt(int y) {
        return y > trueAtAndBelow && y < falseAtAndAbove;
    }

    /** For heights that are not random: whether this rule places bedrock there. */
    public boolean fixedBedrockAt(int y) {
        if (isRandomAt(y)) {
            throw new IllegalArgumentException("y=" + y + " is random for " + randomName);
        }
        return (y <= trueAtAndBelow) != inverted;
    }

    /** The chance that the gradient is true at a random height, computed like Minecraft's {@code Mth.map}. */
    public double probability(int y) {
        double delta = ((double) y - trueAtAndBelow) / ((double) falseAtAndAbove - trueAtAndBelow);
        return 1.0 + delta * (0.0 - 1.0);
    }

    /**
     * The gradient is true exactly when the block's 24-bit number (the top 24 bits of the generator's first output)
     * is below this value. This is the game's test {@code nextFloat() < probability(y)} without floating point.
     */
    public int threshold(int y) {
        double probability = probability(y);
        int low = 0;
        int high = FLOAT_STEPS;
        // The smallest k for which nextFloat() = k * 2^-24 is no longer below the probability.
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (mid * 0x1.0p-24f < probability) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /** Whether this rule places bedrock at the block, using the dimension's generator factory for this rule. */
    public boolean isBedrock(PositionalRandomFactory factory, int x, int y, int z) {
        if (!isRandomAt(y)) {
            return fixedBedrockAt(y);
        }
        boolean gradient = factory.at(x, y, z).nextFloat() < probability(y);
        return gradient != inverted;
    }
}
