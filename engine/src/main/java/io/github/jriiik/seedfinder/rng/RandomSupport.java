package io.github.jriiik.seedfinder.rng;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Minecraft's helpers for turning seeds and names into the 128-bit state of a {@link XoroshiroRandom}. */
public final class RandomSupport {

    public static final long GOLDEN_RATIO_64 = 0x9E3779B97F4A7C15L;
    public static final long SILVER_RATIO_64 = 0x6A09E667F3BCC909L;

    /** A 128-bit generator state, as two 64-bit halves. */
    public record Seed128(long lo, long hi) {
    }

    private RandomSupport() {
    }

    /** The "Stafford variant 13" bit mixer: spreads every input bit over the whole output. */
    public static long mixStafford13(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Spreads a 64-bit world seed over 128 bits, as {@code new XoroshiroRandomSource(seed)} does. */
    public static Seed128 upgradeSeedTo128bit(long seed) {
        long lo = seed ^ SILVER_RATIO_64;
        long hi = lo + GOLDEN_RATIO_64;
        return new Seed128(mixStafford13(lo), mixStafford13(hi));
    }

    /** The MD5 hash of a name (UTF-8), read as two big-endian 64-bit numbers. */
    public static Seed128 seedFromHashOf(String name) {
        byte[] hash;
        try {
            hash = MessageDigest.getInstance("MD5").digest(name.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime has MD5", e);
        }
        return new Seed128(bigEndianLong(hash, 0), bigEndianLong(hash, 8));
    }

    private static long bigEndianLong(byte[] bytes, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (bytes[offset + i] & 0xFF);
        }
        return value;
    }
}
