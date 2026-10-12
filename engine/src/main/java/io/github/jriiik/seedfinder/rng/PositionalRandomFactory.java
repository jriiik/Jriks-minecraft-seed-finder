package io.github.jriiik.seedfinder.rng;

/**
 * Makes a fresh, independent generator for a block position or a name, so the result does not depend on the order
 * in which the game generates things. Bedrock, for example, uses one fresh generator per block.
 */
public interface PositionalRandomFactory {

    RandomSource at(int x, int y, int z);

    /** A generator for a name such as {@code minecraft:bedrock_floor} (the full id, namespace included). */
    RandomSource fromHashOf(String name);
}
