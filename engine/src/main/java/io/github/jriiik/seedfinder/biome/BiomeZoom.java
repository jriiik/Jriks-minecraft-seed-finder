package io.github.jriiik.seedfinder.biome;

/**
 * How the game turns quart biomes into block biomes ({@code BiomeManager.getBiome}), the biome the F3 screen shows:
 * each block takes the biome of one of the 8 quarts around it, picked with a jitter that depends on the hashed seed
 * (the seed's SHA-256 the server sends to clients). The quart y the game finally reads is clamped to the
 * dimension's height; that is left to the caller.
 */
public final class BiomeZoom {

    private BiomeZoom() {
    }

    /** The quart {x, y, z} whose biome the game shows at a block. */
    public static int[] quart(long hashedSeed, int x, int y, int z) {
        int shiftedX = x - 2;
        int shiftedY = y - 2;
        int shiftedZ = z - 2;
        int parentX = shiftedX >> 2;
        int parentY = shiftedY >> 2;
        int parentZ = shiftedZ >> 2;
        double fractionX = (shiftedX & 3) / 4.0;
        double fractionY = (shiftedY & 3) / 4.0;
        double fractionZ = (shiftedZ & 3) / 4.0;
        int closest = 0;
        double closestDistance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 8; i++) {
            boolean xEven = (i & 4) == 0;
            boolean yEven = (i & 2) == 0;
            boolean zEven = (i & 1) == 0;
            double distance = fiddledDistance(hashedSeed, xEven ? parentX : parentX + 1, yEven ? parentY : parentY + 1,
                zEven ? parentZ : parentZ + 1, xEven ? fractionX : fractionX - 1.0, yEven ? fractionY : fractionY - 1.0,
                zEven ? fractionZ : fractionZ - 1.0);
            if (closestDistance > distance) {
                closest = i;
                closestDistance = distance;
            }
        }
        return new int[] {(closest & 4) == 0 ? parentX : parentX + 1, (closest & 2) == 0 ? parentY : parentY + 1,
            (closest & 1) == 0 ? parentZ : parentZ + 1};
    }

    /**
     * The 8 quarts {x, y, z} the game may pick from for a block, whatever the hashed seed: one of them holds the
     * block's biome.
     */
    public static int[][] candidates(int x, int y, int z) {
        int parentX = (x - 2) >> 2;
        int parentY = (y - 2) >> 2;
        int parentZ = (z - 2) >> 2;
        int[][] quarts = new int[8][];
        for (int i = 0; i < 8; i++) {
            quarts[i] = new int[] {parentX + ((i >> 2) & 1), parentY + ((i >> 1) & 1), parentZ + (i & 1)};
        }
        return quarts;
    }

    private static double fiddledDistance(long seed, int x, int y, int z, double distanceX, double distanceY, double distanceZ) {
        long random = next(seed, x);
        random = next(random, y);
        random = next(random, z);
        random = next(random, x);
        random = next(random, y);
        random = next(random, z);
        double fiddleX = fiddle(random);
        random = next(random, seed);
        double fiddleY = fiddle(random);
        random = next(random, seed);
        double fiddleZ = fiddle(random);
        return square(distanceZ + fiddleZ) + square(distanceY + fiddleY) + square(distanceX + fiddleX);
    }

    /** {@code LinearCongruentialGenerator.next}. */
    private static long next(long random, long salt) {
        random *= random * 6364136223846793005L + 1442695040888963407L;
        return random + salt;
    }

    private static double fiddle(long random) {
        double uniform = Math.floorMod(random >> 24, 1024) / 1024.0;
        return (uniform - 0.5) * 0.9;
    }

    private static double square(double value) {
        return value * value;
    }
}
