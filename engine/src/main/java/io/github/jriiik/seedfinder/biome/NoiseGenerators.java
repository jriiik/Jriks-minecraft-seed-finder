package io.github.jriiik.seedfinder.biome;

import io.github.jriiik.seedfinder.rng.RandomSupport;
import io.github.jriiik.seedfinder.rng.XoroshiroPositionalFactory;
import io.github.jriiik.seedfinder.rng.XoroshiroRandom;

/**
 * Where the layers of one noise get their random numbers, the same in every supported version ({@link NormalNoise} for
 * 26.x, {@link ClassicNormalNoise} for 1.21.x): the hash of the noise's id picks a random from the world's factory,
 * which forks two factories, the first stack's and then the second's. In each stack, an octave's layer comes from the
 * hash of the octave's name ({@code octave_-7}). Only the arithmetic that combines the layers differs between versions.
 */
final class NoiseGenerators {

    /** The second stack is sampled this much faster, so the two do not line up. */
    static final double SECOND_STACK_FREQUENCY = 1.0181268882175227;

    private final RandomSupport.Seed128 idHash;
    /** One per octave, in the order given. */
    private final RandomSupport.Seed128[] octaveHashes;

    /**
     * @param id      the noise's id, like {@code minecraft:temperature}
     * @param octaves the octaves that have a layer (those with a non-zero amplitude), like -10 for a wave length of
     *                1024 blocks, from the lowest frequency up
     */
    NoiseGenerators(String id, int[] octaves) {
        this.idHash = RandomSupport.seedFromHashOf(id);
        this.octaveHashes = new RandomSupport.Seed128[octaves.length];
        for (int i = 0; i < octaves.length; i++) {
            octaveHashes[i] = RandomSupport.seedFromHashOf("octave_" + octaves[i]);
        }
    }

    /**
     * The layers of the world whose generators come from {@code world} ({@code RandomState}'s root factory):
     * {@code [0]} the first stack, {@code [1]} the second, each with one layer per octave in the order given.
     */
    PerlinNoise[][] stacks(XoroshiroPositionalFactory world) {
        XoroshiroRandom random = world.fromHash(idHash);
        XoroshiroPositionalFactory first = random.forkPositional();
        XoroshiroPositionalFactory second = random.forkPositional();
        PerlinNoise[][] stacks = new PerlinNoise[2][octaveHashes.length];
        for (int i = 0; i < octaveHashes.length; i++) {
            stacks[0][i] = new PerlinNoise(first.fromHash(octaveHashes[i]));
            stacks[1][i] = new PerlinNoise(second.fromHash(octaveHashes[i]));
        }
        return stacks;
    }
}
