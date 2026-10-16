package io.github.jriiik.seedfinder.biome;

import io.github.jriiik.seedfinder.rng.RandomSupport;
import io.github.jriiik.seedfinder.rng.XoroshiroPositionalFactory;
import io.github.jriiik.seedfinder.rng.XoroshiroRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Minecraft 1.21.x's {@code NormalNoise}, in double arithmetic: two stacks of octaves (the game's {@code PerlinNoise}),
 * the second sampled slightly faster so the two do not line up, added and scaled. The layers are the same as 26.x
 * makes from the same names; only the arithmetic and the weights differ.
 */
final class ClassicNormalNoise {

    private static final double SECOND_STACK_FREQUENCY = 1.0181268882175227;

    private final Recipe recipe;
    private final PerlinNoise[] first;
    private final PerlinNoise[] second;

    private ClassicNormalNoise(Recipe recipe, PerlinNoise[] first, PerlinNoise[] second) {
        this.recipe = recipe;
        this.first = first;
        this.second = second;
    }

    double get(double x, double y, double z) {
        double secondX = x * SECOND_STACK_FREQUENCY;
        double secondY = y * SECOND_STACK_FREQUENCY;
        double secondZ = z * SECOND_STACK_FREQUENCY;
        return (stack(first, x, y, z) + stack(second, secondX, secondY, secondZ)) * recipe.valueFactor;
    }

    /** {@code PerlinNoise.getValue}: the octaves from the lowest frequency up, each wrapped, weighted and added in order. */
    private double stack(PerlinNoise[] layers, double x, double y, double z) {
        double value = 0.0;
        for (int i = 0; i < layers.length; i++) {
            double frequency = recipe.inputFactors[i];
            double noise = layers[i].sampleDouble(PerlinNoise.wrap(x * frequency), PerlinNoise.wrap(y * frequency), PerlinNoise.wrap(z * frequency));
            value += recipe.amplitudes[i] * noise * recipe.valueFactors[i];
        }
        return value;
    }

    /**
     * How one noise is built from its parameters ({@code firstOctave} and {@code amplitudes}, the 1.21.x data format),
     * worked out once. Only the generators depend on the world seed.
     */
    static final class Recipe {

        private final RandomSupport.Seed128 idHash;
        /** Per octave with a non-zero amplitude, from the lowest frequency up. */
        private final RandomSupport.Seed128[] octaveHashes;
        private final double[] inputFactors;
        private final double[] valueFactors;
        private final double[] amplitudes;
        private final double valueFactor;

        /**
         * @param id          the noise's id, like {@code minecraft:temperature}; its generators come from this name
         * @param firstOctave the octave of the first amplitude (-10 means a wave length of 1024 blocks)
         * @param amplitudes  one per octave; an octave with 0 is left out
         */
        Recipe(String id, int firstOctave, double[] amplitudes) {
            this.idHash = RandomSupport.seedFromHashOf(id);
            int lowest = Integer.MAX_VALUE;
            int highest = Integer.MIN_VALUE;
            for (int i = 0; i < amplitudes.length; i++) {
                if (amplitudes[i] != 0.0) {
                    lowest = Math.min(lowest, i);
                    highest = Math.max(highest, i);
                }
            }
            if (lowest > highest) {
                throw new IllegalArgumentException(id + ": every amplitude is 0");
            }
            // NormalNoise: 1/6 over the expected deviation of the octaves between the first and last non-zero one
            this.valueFactor = 0.16666666666666666 / (0.1 * (1.0 + 1.0 / (highest - lowest + 1)));
            // PerlinNoise: the input factor doubles and the value factor halves from one octave to the next
            double inputFactor = Math.pow(2.0, firstOctave);
            double valueFactor = Math.pow(2.0, amplitudes.length - 1) / (Math.pow(2.0, amplitudes.length) - 1.0);
            List<double[]> octaves = new ArrayList<>();
            for (int i = 0; i < amplitudes.length; i++) {
                if (amplitudes[i] != 0.0) {
                    octaves.add(new double[] {firstOctave + i, inputFactor, valueFactor, amplitudes[i]});
                }
                inputFactor *= 2.0;
                valueFactor /= 2.0;
            }
            this.octaveHashes = new RandomSupport.Seed128[octaves.size()];
            this.inputFactors = new double[octaves.size()];
            this.valueFactors = new double[octaves.size()];
            this.amplitudes = new double[octaves.size()];
            for (int i = 0; i < octaves.size(); i++) {
                double[] octave = octaves.get(i);
                octaveHashes[i] = RandomSupport.seedFromHashOf("octave_" + (int) octave[0]);
                inputFactors[i] = octave[1];
                valueFactors[i] = octave[2];
                this.amplitudes[i] = octave[3];
            }
        }

        /** The noise of the world whose generators come from {@code world} ({@code RandomState}'s root factory). */
        ClassicNormalNoise create(XoroshiroPositionalFactory world) {
            XoroshiroRandom random = world.fromHash(idHash);
            // each stack forks its own factory from the noise's random, the first stack first
            XoroshiroPositionalFactory firstFactory = random.forkPositional();
            XoroshiroPositionalFactory secondFactory = random.forkPositional();
            PerlinNoise[] first = new PerlinNoise[octaveHashes.length];
            PerlinNoise[] second = new PerlinNoise[octaveHashes.length];
            for (int i = 0; i < octaveHashes.length; i++) {
                first[i] = new PerlinNoise(firstFactory.fromHash(octaveHashes[i]));
                second[i] = new PerlinNoise(secondFactory.fromHash(octaveHashes[i]));
            }
            return new ClassicNormalNoise(this, first, second);
        }

        @Override
        public String toString() {
            return "ClassicNormalNoise.Recipe" + Arrays.toString(inputFactors);
        }
    }
}
