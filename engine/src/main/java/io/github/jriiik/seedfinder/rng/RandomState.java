package io.github.jriiik.seedfinder.rng;

/**
 * The block-level random generators of one dimension of a world (Minecraft's {@code RandomState}). Each named use,
 * such as {@code minecraft:bedrock_floor}, gets its own {@link PositionalRandomFactory} derived from the world seed.
 */
public final class RandomState {

    private final PositionalRandomFactory root;

    /**
     * @param legacyRandomSource true for dimensions that use the old {@link JavaRandom} (the nether and the End in
     *                           26.3); then only the lowest 48 bits of the seed matter
     */
    public RandomState(long worldSeed, boolean legacyRandomSource) {
        RandomSource base = legacyRandomSource ? new JavaRandom(worldSeed) : new XoroshiroRandom(worldSeed);
        this.root = base.forkPositional();
    }

    /** The generator factory for one named use ({@code getOrCreateRandomFactory}). */
    public PositionalRandomFactory factory(String name) {
        return root.fromHashOf(name).forkPositional();
    }
}
