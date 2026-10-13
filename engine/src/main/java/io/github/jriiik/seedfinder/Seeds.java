package io.github.jriiik.seedfinder;

import io.github.jriiik.seedfinder.rng.JavaRandom;
import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;

/** Helpers for world seeds as players see them. */
public final class Seeds {

    /** Structure positions only depend on this many low bits of the world seed. */
    public static final int STRUCTURE_SEED_BITS = 48;
    public static final long STRUCTURE_SEED_MASK = (1L << STRUCTURE_SEED_BITS) - 1;

    private Seeds() {
    }

    /**
     * The structure seed of a world seed: its lowest 48 bits, which alone decide where structures can start.
     * 65,536 world seeds share each structure seed.
     */
    public static long structureSeed(long worldSeed) {
        return worldSeed & STRUCTURE_SEED_MASK;
    }

    /**
     * The world seeds with this structure seed that Minecraft can pick by itself, when nobody types a seed. Such
     * seeds are one {@code nextLong()} of Java's 48-bit random generator, so only 2^48 of the 2^64 possible seeds
     * can come out of it: usually exactly one per structure seed, sometimes two or none.
     *
     * <p>How it works: {@code nextLong()} glues two 32-bit outputs together. The structure seed fixes the second
     * output completely and 16 bits of the first; stepping the generator back from each of the 65,536 possible
     * hidden low bits of the second state shows which ones fit.
     */
    public static long[] randomSeedCandidates(long structureSeed) {
        long seed = structureSeed & STRUCTURE_SEED_MASK;
        long low32 = seed & 0xFFFF_FFFFL;
        // The second half is added as a signed int, which takes 1 away from the upper half when its top bit is set.
        long borrow = low32 >>> 31;
        long wanted = ((seed >>> 32) + borrow) & 0xFFFF;
        long[] found = new long[2];
        int count = 0;
        for (long hidden = 0; hidden < 1 << 16; hidden++) {
            long second = (low32 << 16) | hidden;
            long first = ((second - JavaRandom.ADDEND) * JavaRandom.MULTIPLIER_INVERSE) & JavaRandom.MASK;
            if (((first >>> 16) & 0xFFFF) == wanted) {
                if (count == found.length) {
                    found = Arrays.copyOf(found, count * 2);
                }
                found[count++] = ((long) (int) (first >>> 16) << 32) + (int) low32;
            }
        }
        return Arrays.copyOf(found, count);
    }

    /** True when Minecraft could have picked this world seed by itself (see {@link #randomSeedCandidates}). */
    public static boolean couldBeRandomSeed(long worldSeed) {
        return Arrays.stream(randomSeedCandidates(structureSeed(worldSeed))).anyMatch(candidate -> candidate == worldSeed);
    }

    /**
     * The world seed with this structure seed that a player could have typed as text (any text that is not a number
     * becomes its 32-bit {@code hashCode()}) or as a number from -2147483648 to 2147483647. At most one such seed
     * exists for each structure seed.
     */
    public static OptionalLong textSeedCandidate(long structureSeed) {
        long candidate = (structureSeed << 16) >> 16;
        return candidate == (int) candidate ? OptionalLong.of(candidate) : OptionalLong.empty();
    }

    /**
     * The "hashed seed" Minecraft sends to every player when they join or change dimension
     * ({@code BiomeManager.obfuscateSeed}): the first 8 bytes of the SHA-256 hash of the world seed, both read in
     * little-endian byte order. The game uses it to blend biomes smoothly; the seed can't be computed back from it,
     * but each candidate seed can be checked against it.
     */
    public static long hashedSeed(long worldSeed) {
        return new Hasher().hashedSeed(worldSeed);
    }

    /** Works out many hashed seeds quickly, reusing its buffers; one per thread. */
    public static final class Hasher {
        private final MessageDigest digest = sha256();
        private final byte[] input = new byte[8];
        private final byte[] output = new byte[32];

        /** The hashed seed of a world seed, see {@link Seeds#hashedSeed(long)}. */
        public long hashedSeed(long worldSeed) {
            for (int i = 0; i < 8; i++) {
                input[i] = (byte) (worldSeed >>> (8 * i));
            }
            digest.update(input, 0, 8);
            try {
                digest.digest(output, 0, output.length);
            } catch (DigestException e) {
                throw new IllegalStateException("SHA-256 output doesn't fit in 32 bytes", e);
            }
            long value = 0;
            for (int i = 7; i >= 0; i--) {
                value = (value << 8) | (output[i] & 0xFF);
            }
            return value;
        }
    }

    /**
     * The world seeds with one of these structure seeds whose hashed seed is the given one: normally exactly one, or
     * none when the server sent a different value (or the structure seeds are wrong). Tries the seeds people
     * normally end up with first ({@link #likelyWithHashedSeed}), then all 65,536 seeds of each structure seed.
     */
    public static long[] withHashedSeed(Collection<Long> structureSeeds, long hashedSeed) {
        long[] likely = likelyWithHashedSeed(structureSeeds, hashedSeed);
        if (likely.length > 0) {
            return likely;
        }
        Hasher hasher = new Hasher();
        List<Long> found = new ArrayList<>();
        for (long structureSeed : distinct(structureSeeds)) {
            for (long upper = 0; upper < 1 << 16; upper++) {
                long worldSeed = upper << STRUCTURE_SEED_BITS | structureSeed;
                if (hasher.hashedSeed(worldSeed) == hashedSeed) {
                    found.add(worldSeed);
                }
            }
        }
        return found.stream().mapToLong(Long::longValue).toArray();
    }

    /**
     * The quick part of {@link #withHashedSeed}: only the one to three seeds per structure seed that Minecraft picks
     * by itself ({@link #randomSeedCandidates}) or that typed text or a short number gives ({@link #textSeedCandidate}).
     */
    public static long[] likelyWithHashedSeed(Collection<Long> structureSeeds, long hashedSeed) {
        Hasher hasher = new Hasher();
        List<Long> found = new ArrayList<>();
        for (long structureSeed : distinct(structureSeeds)) {
            Set<Long> likely = new LinkedHashSet<>();
            Arrays.stream(randomSeedCandidates(structureSeed)).forEach(likely::add);
            textSeedCandidate(structureSeed).ifPresent(likely::add);
            for (long worldSeed : likely) {
                if (hasher.hashedSeed(worldSeed) == hashedSeed) {
                    found.add(worldSeed);
                }
            }
        }
        return found.stream().mapToLong(Long::longValue).toArray();
    }

    /** The structure seeds (lowest 48 bits) without repeats, even when the same one is given in two forms. */
    private static Set<Long> distinct(Collection<Long> structureSeeds) {
        Set<Long> seeds = new LinkedHashSet<>();
        structureSeeds.forEach(seed -> seeds.add(structureSeed(seed)));
        return seeds;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime has SHA-256", e);
        }
    }

    /**
     * Turns what a player types into the "Seed for the World Generator" box into the actual world seed, exactly
     * like Minecraft does: numbers are used as they are, any other text becomes its Java {@code hashCode()}.
     * Returns empty for blank input (Minecraft would then pick a random seed).
     */
    public static OptionalLong parse(String typed) {
        String text = typed.trim();
        if (text.isEmpty()) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(text));
        } catch (NumberFormatException notANumber) {
            return OptionalLong.of(text.hashCode());
        }
    }
}
