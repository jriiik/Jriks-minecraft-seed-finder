package io.github.jriiik.seedfinder.structure;

import io.github.jriiik.seedfinder.rng.JavaRandom;
import io.github.jriiik.seedfinder.rng.WorldgenSeeds;

/**
 * The extra random roll that thins out a structure set whose frequency is below 1. Minecraft keeps several
 * historical variants so that old worlds keep generating the same way; each one is reproduced exactly.
 */
public enum FrequencyReduction {
    DEFAULT("default") {
        @Override
        public boolean shouldGenerate(long worldSeed, int salt, int chunkX, int chunkZ, float frequency) {
            // Minecraft passes the arguments in an unusual order here (the salt takes the place of the
            // region x coordinate). This is reproduced on purpose.
            JavaRandom random = new JavaRandom(WorldgenSeeds.largeFeatureWithSalt(worldSeed, salt, chunkX, chunkZ));
            return random.nextFloat() < frequency;
        }
    },
    /** Used by pillager outposts. */
    LEGACY_TYPE_1("legacy_type_1") {
        @Override
        public boolean shouldGenerate(long worldSeed, int salt, int chunkX, int chunkZ, float frequency) {
            int cx = chunkX >> 4;
            int cz = chunkZ >> 4;
            JavaRandom random = new JavaRandom(cx ^ cz << 4 ^ worldSeed);
            random.nextInt();
            return random.nextInt((int) (1.0f / frequency)) == 0;
        }
    },
    /** Used by buried treasure. */
    LEGACY_TYPE_2("legacy_type_2") {
        @Override
        public boolean shouldGenerate(long worldSeed, int salt, int chunkX, int chunkZ, float frequency) {
            JavaRandom random = new JavaRandom(WorldgenSeeds.largeFeatureWithSalt(worldSeed, chunkX, chunkZ, 10387320));
            return random.nextFloat() < frequency;
        }
    },
    /** Used by mineshafts. */
    LEGACY_TYPE_3("legacy_type_3") {
        @Override
        public boolean shouldGenerate(long worldSeed, int salt, int chunkX, int chunkZ, float frequency) {
            JavaRandom random = new JavaRandom(WorldgenSeeds.largeFeature(worldSeed, chunkX, chunkZ));
            return random.nextDouble() < frequency;
        }
    };

    private final String id;

    FrequencyReduction(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public abstract boolean shouldGenerate(long worldSeed, int salt, int chunkX, int chunkZ, float frequency);

    public static FrequencyReduction byId(String id) {
        for (FrequencyReduction method : values()) {
            if (method.id.equals(id)) {
                return method;
            }
        }
        throw new IllegalArgumentException("Unknown frequency reduction method: " + id);
    }
}
