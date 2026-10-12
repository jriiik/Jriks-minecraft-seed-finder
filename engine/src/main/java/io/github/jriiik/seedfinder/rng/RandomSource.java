package io.github.jriiik.seedfinder.rng;

/**
 * A random number generator as Minecraft's world generation uses it. Two algorithms exist: the old 48-bit
 * {@link JavaRandom} ("legacy") and the newer {@link XoroshiroRandom}. Which one a dimension uses for its blocks is
 * part of the version data.
 */
public interface RandomSource {

    long nextLong();

    int nextInt();

    /** A value in {@code [0, bound)}; each algorithm has its own exact way to draw it. */
    int nextInt(int bound);

    float nextFloat();

    double nextDouble();

    boolean nextBoolean();

    /** Splits off a generator factory for positions and names, seeded from this generator's next outputs. */
    PositionalRandomFactory forkPositional();
}
