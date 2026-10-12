package io.github.jriiik.seedfinder.finder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jriiik.seedfinder.structure.StructureSet;
import io.github.jriiik.seedfinder.version.MinecraftVersion;
import io.github.jriiik.seedfinder.version.VersionData;
import io.github.jriiik.seedfinder.world.ChunkPos;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ObservationParserTest {

    private static final VersionData DATA = MinecraftVersion.V26_3.data();

    private static StructureObservation parse(String line) {
        return ObservationParser.parseLine(DATA, line).orElseThrow();
    }

    private static StructureSet set(String id) {
        return DATA.structureSet(id).orElseThrow();
    }

    @Test
    void readsTheSimpleFormats() {
        StructureObservation expected = new StructureObservation(set("igloos"), ChunkPos.ofBlock(48, 208));
        assertEquals(expected, parse("igloo 48 208"));
        assertEquals(expected, parse("  igloos   48   208  "));
        assertEquals(expected, parse("igloo: 48, 208"));
        assertEquals(expected, parse("igloo:48,208"));
        assertEquals(expected, parse("minecraft:igloo:48,208"));
        assertEquals(expected, parse("igloo 48 70 208"));
        assertEquals(expected, parse("igloo [48, ~, 208]"));
    }

    @Test
    void readsLinesPastedFromLocate() {
        assertEquals(
            new StructureObservation(set("villages"), ChunkPos.ofBlock(-1408, -1264)),
            parse("The nearest minecraft:village_plains is at [-1408, ~, -1264] (1892 blocks away)"));
        assertEquals(
            new StructureObservation(set("villages"), ChunkPos.ofBlock(336, 80)),
            parse("[12:01:33] [Server thread/INFO]: The nearest #minecraft:village (minecraft:village_desert) is at [336, ~, 80] (345 blocks away)"));
    }

    @Test
    void negativeBlockCoordinatesUseTheRightChunk() {
        assertEquals(new ChunkPos(-1, -1), parse("village -1 -16").chunk());
        assertEquals(new ChunkPos(-2, 0), parse("village -17 15").chunk());
    }

    @Test
    void skipsCommentsAndBlankLines() {
        assertEquals(Optional.empty(), ObservationParser.parseLine(DATA, "   "));
        assertEquals(Optional.empty(), ObservationParser.parseLine(DATA, "# my notes"));
        List<StructureObservation> parsed = ObservationParser.parseLines(DATA, List.of("# notes", "", "village 1 2", "igloo 3 4"));
        assertEquals(2, parsed.size());
    }

    @Test
    void explainsMistakesWithTheLineNumber() {
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
            () -> ObservationParser.parseLines(DATA, List.of("village 1 2", "castle 5 5")));
        assertEquals("Line 2: unknown structure 'castle'", unknown.getMessage());
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, () -> parse("village 5"));
        assertTrue(missing.getMessage().contains("x and z"), missing.getMessage());
        IllegalArgumentException notANumber = assertThrows(IllegalArgumentException.class, () -> parse("village 5 north"));
        assertTrue(notANumber.getMessage().contains("'north' is not a whole number"), notANumber.getMessage());
    }
}
