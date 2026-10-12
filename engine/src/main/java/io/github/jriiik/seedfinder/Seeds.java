package io.github.jriiik.seedfinder;

import java.util.OptionalLong;

/** Helpers for world seeds as players see them. */
public final class Seeds {

    private Seeds() {
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
