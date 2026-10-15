package io.github.jriiik.seedfinder.biome;

import io.github.jriiik.seedfinder.rng.XoroshiroRandom;

/**
 * One layer of Minecraft's gradient noise (26.x {@code GradientNoise} + {@code PerlinNoise}): a shuffled table of
 * 256 entries and a random offset, sampled with float arithmetic exactly as the game does it.
 */
final class PerlinNoise {

    /** The 16 gradient directions (x, y, z), the last four repeating earlier ones as in the game. */
    private static final int[] GRADIENT = {
        1, 1, 0, -1, 1, 0, 1, -1, 0, -1, -1, 0,
        1, 0, 1, -1, 0, 1, 1, 0, -1, -1, 0, -1,
        0, 1, 1, 0, -1, 1, 0, 1, -1, 0, -1, -1,
        1, 1, 0, 0, -1, 1, -1, 1, 0, 0, -1, -1,
    };

    /** Coordinates beyond this are wrapped by multiples of 2^25, so the noise repeats instead of losing precision. */
    private static final double HALF_ROUND_OFF = Math.nextDown(1.6777216E7);

    private final byte[] permutations = new byte[256];
    private final double offsetX;
    private final double offsetY;
    private final double offsetZ;

    PerlinNoise(XoroshiroRandom random) {
        offsetX = random.nextDouble() * 256.0;
        offsetY = random.nextDouble() * 256.0;
        offsetZ = random.nextDouble() * 256.0;
        for (int i = 0; i < 256; i++) {
            permutations[i] = (byte) i;
        }
        for (int i = 0; i < 256; i++) {
            int offset = random.nextInt(256 - i);
            byte swap = permutations[i];
            permutations[i] = permutations[offset + i];
            permutations[offset + i] = swap;
        }
    }

    static double wrap(double x) {
        return x >= -HALF_ROUND_OFF && x < HALF_ROUND_OFF ? x : x - Math.floor(x / 3.3554432E7 + 0.5) * 3.3554432E7;
    }

    float sample(double x, double y, double z) {
        double shiftedX = wrap(x) + offsetX;
        double shiftedY = wrap(y) + offsetY;
        double shiftedZ = wrap(z) + offsetZ;
        int floorX = (int) Math.floor(shiftedX);
        int floorY = (int) Math.floor(shiftedY);
        int floorZ = (int) Math.floor(shiftedZ);
        float relativeX = (float) (shiftedX - floorX);
        float relativeY = (float) (shiftedY - floorY);
        float relativeZ = (float) (shiftedZ - floorZ);
        int x0 = permute(floorX);
        int x1 = permute(floorX + 1);
        int xy00 = permute(x0 + floorY);
        int xy01 = permute(x0 + floorY + 1);
        int xy10 = permute(x1 + floorY);
        int xy11 = permute(x1 + floorY + 1);
        float d000 = gradDot(permute(xy00 + floorZ), relativeX, relativeY, relativeZ);
        float d100 = gradDot(permute(xy10 + floorZ), relativeX - 1.0F, relativeY, relativeZ);
        float d010 = gradDot(permute(xy01 + floorZ), relativeX, relativeY - 1.0F, relativeZ);
        float d110 = gradDot(permute(xy11 + floorZ), relativeX - 1.0F, relativeY - 1.0F, relativeZ);
        float d001 = gradDot(permute(xy00 + floorZ + 1), relativeX, relativeY, relativeZ - 1.0F);
        float d101 = gradDot(permute(xy10 + floorZ + 1), relativeX - 1.0F, relativeY, relativeZ - 1.0F);
        float d011 = gradDot(permute(xy01 + floorZ + 1), relativeX, relativeY - 1.0F, relativeZ - 1.0F);
        float d111 = gradDot(permute(xy11 + floorZ + 1), relativeX - 1.0F, relativeY - 1.0F, relativeZ - 1.0F);
        float alphaX = smoothstep(relativeX);
        float alphaY = smoothstep(relativeY);
        float alphaZ = smoothstep(relativeZ);
        float near = lerp(alphaY, lerp(alphaX, d000, d100), lerp(alphaX, d010, d110));
        float far = lerp(alphaY, lerp(alphaX, d001, d101), lerp(alphaX, d011, d111));
        return lerp(alphaZ, near, far);
    }

    private int permute(int x) {
        return permutations[x & 0xFF] & 0xFF;
    }

    private static float gradDot(int hash, float x, float y, float z) {
        int g = (hash & 15) * 3;
        return GRADIENT[g] * x + GRADIENT[g + 1] * y + GRADIENT[g + 2] * z;
    }

    static float smoothstep(float x) {
        return x * x * x * (x * (x * 6.0F - 15.0F) + 10.0F);
    }

    static float lerp(float alpha, float from, float to) {
        return from + alpha * (to - from);
    }
}
