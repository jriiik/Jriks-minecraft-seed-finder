package io.github.jriiik.seedfinder.biome;

import io.github.jriiik.seedfinder.rng.XoroshiroPositionalFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Minecraft's {@code NormalNoise} (26.x): pairs of {@link PerlinNoise} layers, one pair per octave, summed with float
 * weights. Each noise of a world gets its generators from the world's factory and its own name, so a noise can be
 * created on its own, in any order.
 */
final class NormalNoise {

    /** Two layers per octave: the first stack's, then the second's (see {@link NoiseGenerators}). */
    private final PerlinNoise[] layers;
    private final double[] frequencies;
    private final float[] amplitudes;

    private NormalNoise(PerlinNoise[] layers, double[] frequencies, float[] amplitudes) {
        this.layers = layers;
        this.frequencies = frequencies;
        this.amplitudes = amplitudes;
    }

    float get(double x, double y, double z) {
        float value = 0.0F;
        for (int i = 0; i < layers.length; i++) {
            double frequency = frequencies[i];
            value += amplitudes[i] * layers[i].sample(x * frequency, y * frequency, z * frequency);
        }
        return value;
    }

    /**
     * How one noise is built, worked out once: where its octaves' generators come from, their frequencies and their
     * weights. Only the generators depend on the world seed.
     */
    static final class Recipe {

        private final NoiseGenerators generators;
        private final double[] frequencies;
        private final float[] amplitudes;

        /**
         * @param id                 the noise's id, like {@code minecraft:temperature}; its generators come from this name
         * @param normalize          false only for noises written with {@code "normalize": false}
         * @param amplitudeModifiers one per octave (an octave with 0 is left out), or empty for all 1
         */
        Recipe(String id, double baseAmplitude, int baseOctave, int octaveCount, boolean normalize, double[] amplitudeModifiers) {
            if (amplitudeModifiers.length != 0 && amplitudeModifiers.length != octaveCount) {
                throw new IllegalArgumentException(id + ": " + amplitudeModifiers.length + " amplitude modifiers for " + octaveCount + " octaves");
            }
            // NormalNoise.buildOctaves
            double frequency = Math.pow(2.0, baseOctave);
            double amplitude = baseAmplitude;
            if (normalize) {
                amplitude *= Math.pow(0.5, -(octaveCount - 1)) / (Math.pow(0.5, -octaveCount) - 1.0);
            }
            List<double[]> octaves = new ArrayList<>();
            for (int i = 0; i < octaveCount; i++) {
                double modifier = amplitudeModifiers.length == 0 ? 1.0 : amplitudeModifiers[i];
                if (modifier != 0.0) {
                    octaves.add(new double[] {baseOctave + i, frequency, amplitude * modifier});
                }
                frequency *= 2.0;
                amplitude *= 0.5;
            }
            // The weight that makes the noise's spread the same whatever its octaves (computeNormalizationFactor).
            // The game sums the target with DoubleStream.sum, which compensates rounding errors; so do we.
            double targetAmplitude = octaves.stream().mapToDouble(octave -> Math.abs(octave[2])).sum();
            double variance = 0.0;
            for (double[] octave : octaves) {
                double layerDeviation = 0.2702247831245211 * Math.abs(octave[2]);
                variance += layerDeviation * layerDeviation;
            }
            double inputDeviation = Math.sqrt(variance);
            double normalization = inputDeviation == 0.0 ? 0.0 : targetAmplitude * 0.3333333333333333 / (inputDeviation * Math.sqrt(2.0));
            this.generators = new NoiseGenerators(id, octaves.stream().mapToInt(octave -> (int) octave[0]).toArray());
            this.frequencies = new double[octaves.size() * 2];
            this.amplitudes = new float[octaves.size() * 2];
            for (int i = 0; i < octaves.size(); i++) {
                double[] octave = octaves.get(i);
                float weight = (float) (normalization * octave[2]);
                frequencies[2 * i] = octave[1];
                frequencies[2 * i + 1] = octave[1] * NoiseGenerators.SECOND_STACK_FREQUENCY;
                amplitudes[2 * i] = weight;
                amplitudes[2 * i + 1] = weight;
            }
        }

        /** The noise of the world whose generators come from {@code world} ({@code RandomState}'s root factory). */
        NormalNoise create(XoroshiroPositionalFactory world) {
            PerlinNoise[][] stacks = generators.stacks(world);
            PerlinNoise[] layers = new PerlinNoise[frequencies.length];
            for (int i = 0; i < stacks[0].length; i++) {
                layers[2 * i] = stacks[0][i];
                layers[2 * i + 1] = stacks[1][i];
            }
            return new NormalNoise(layers, frequencies, amplitudes);
        }

        @Override
        public String toString() {
            return "NormalNoise.Recipe" + Arrays.toString(frequencies);
        }
    }
}
