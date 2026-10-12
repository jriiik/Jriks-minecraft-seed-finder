package io.github.jriiik.seedfinder.rng;

/**
 * The seed formulas Minecraft uses to start a fresh random sequence for one piece of world generation.
 * Each method returns the value Minecraft passes to {@code setSeed}; use it with {@code new JavaRandom(seed)}.
 *
 * <p>The names follow Minecraft's {@code WorldgenRandom} methods so they are easy to compare.
 */
public final class WorldgenSeeds {

    private WorldgenSeeds() {
    }

    /** Seed for region-based structure placement ({@code setLargeFeatureWithSalt}). */
    public static long largeFeatureWithSalt(long worldSeed, int regionX, int regionZ, int salt) {
        return regionX * 341873128712L + regionZ * 132897987541L + worldSeed + salt;
    }

    /** Seed used by some structures and the mineshaft frequency check ({@code setLargeFeatureSeed}). */
    public static long largeFeature(long worldSeed, int chunkX, int chunkZ) {
        JavaRandom random = new JavaRandom(worldSeed);
        long xScale = random.nextLong();
        long zScale = random.nextLong();
        return chunkX * xScale ^ chunkZ * zScale ^ worldSeed;
    }

    /** Per-chunk decoration seed ({@code setDecorationSeed}); takes the block coordinates of the chunk's corner. */
    public static long decoration(long worldSeed, int blockX, int blockZ) {
        JavaRandom random = new JavaRandom(worldSeed);
        long xScale = random.nextLong() | 1L;
        long zScale = random.nextLong() | 1L;
        return blockX * xScale + blockZ * zScale ^ worldSeed;
    }

    /** Seed for one feature in a chunk ({@code setFeatureSeed}), derived from the decoration seed. */
    public static long feature(long decorationSeed, int index, int step) {
        return decorationSeed + index + 10000 * step;
    }
}
