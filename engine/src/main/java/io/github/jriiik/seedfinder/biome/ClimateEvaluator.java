package io.github.jriiik.seedfinder.biome;

import io.github.jriiik.seedfinder.rng.XoroshiroPositionalFactory;

/**
 * The six climate functions of one world preset (temperature, vegetation, continents, erosion, depth, ridges),
 * evaluated as one Minecraft version's code does it: 26.x in float arithmetic ({@link ClimateFunctions}), 1.21.x in
 * double arithmetic ({@link ClassicClimateFunctions}). Which one a version uses is decided by the version, see
 * {@link OverworldBiomes}.
 */
interface ClimateEvaluator {

    /** The router fields, in the order of a climate target point (the game calls vegetation "humidity", ridges "weirdness"). */
    String[] FIELDS = {"temperature", "vegetation", "continents", "erosion", "depth", "ridges"};

    int TEMPERATURE = 0;
    int VEGETATION = 1;
    int CONTINENTS = 2;
    int EROSION = 3;
    int DEPTH = 4;
    int RIDGES = 5;

    /** A fresh state for one thread; set its world seed with {@link State#reset}. */
    State newState();

    /** True if a climate function depends on y (only depth does, in vanilla). */
    boolean dependsOnY(int function);

    /**
     * The value of one of the climate's noises (by id, like {@code minecraft:temperature}) for the world whose
     * generators come from {@code world}, exactly as the game computes it (a float widened to double where the game
     * computes in float). For checking against the game; throws if the climate does not use the noise.
     */
    double noiseValue(String id, XoroshiroPositionalFactory world, double x, double y, double z);

    /**
     * The noises and caches of one world seed. Noises are made when first sampled, since a check that reads only some
     * climate functions then pays only for the noises those use. Not thread-safe; use one state per thread.
     */
    interface State {

        /** Switches to another world seed, forgetting the noises and cached values of the previous one. */
        void reset(XoroshiroPositionalFactory world);

        /**
         * One climate function at a block position, as the float the game turns into a climate target
         * ({@code Climate.Sampler} casts each value to float before quantizing it).
         */
        float climate(int function, int x, int y, int z);

        /** One climate function exactly as the game's density function computes it (for checking against the game). */
        double exact(int function, int x, int y, int z);
    }
}
