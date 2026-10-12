package io.github.jriiik.seedfinder.rng;

/** Minecraft's {@code LegacyPositionalRandomFactory}: positional generators for dimensions using {@link JavaRandom}. */
public record LegacyPositionalFactory(long seed) implements PositionalRandomFactory {

    @Override
    public JavaRandom at(int x, int y, int z) {
        return new JavaRandom(WorldgenSeeds.positional(x, y, z) ^ seed);
    }

    @Override
    public JavaRandom fromHashOf(String name) {
        return new JavaRandom((long) name.hashCode() ^ seed);
    }
}
