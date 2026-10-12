package io.github.jriiik.seedfinder;

import java.util.OptionalLong;

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
