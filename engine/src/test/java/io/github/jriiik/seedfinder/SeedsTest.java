package io.github.jriiik.seedfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class SeedsTest {

    @Test
    void numbersAreUsedAsTheyAre() {
        assertEquals(OptionalLong.of(12345L), Seeds.parse("12345"));
        assertEquals(OptionalLong.of(-8091867987493326313L), Seeds.parse("  -8091867987493326313 "));
        assertEquals(OptionalLong.of(Long.MAX_VALUE), Seeds.parse("9223372036854775807"));
    }

    @Test
    void textBecomesItsHashCode() {
        assertEquals(OptionalLong.of("Jrik".hashCode()), Seeds.parse("Jrik"));
        assertEquals(OptionalLong.of(1772835215L), Seeds.parse("Glacier"));
        // Too big for a long, so Minecraft treats it as text too.
        assertEquals(OptionalLong.of("9223372036854775808".hashCode()), Seeds.parse("9223372036854775808"));
    }

    @Test
    void blankMeansRandom() {
        assertEquals(OptionalLong.empty(), Seeds.parse("   "));
    }
}
