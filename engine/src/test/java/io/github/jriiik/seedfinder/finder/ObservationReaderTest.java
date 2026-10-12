package io.github.jriiik.seedfinder.finder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jriiik.seedfinder.bedrock.BedrockDimension;
import io.github.jriiik.seedfinder.bedrock.BedrockObservation;
import io.github.jriiik.seedfinder.bedrock.BedrockPattern;
import io.github.jriiik.seedfinder.bedrock.BedrockText;
import io.github.jriiik.seedfinder.version.MinecraftVersion;
import io.github.jriiik.seedfinder.version.VersionData;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ObservationReaderTest {

    private static final VersionData DATA = MinecraftVersion.V26_3.data();
    private static final BedrockDimension OVERWORLD = DATA.bedrockDimension("overworld").orElseThrow();
    private static final BedrockDimension NETHER = DATA.bedrockDimension("nether").orElseThrow();

    private static ObservationReader.Observations read(String... lines) {
        return ObservationReader.read(DATA, List.of(lines));
    }

    private static BedrockObservation block(BedrockDimension dimension, int x, int y, int z, boolean bedrock) {
        return new BedrockObservation(dimension, x, y, z, bedrock);
    }

    @Test
    void heightsBecomeOneBedrockBlockAndTheAirAboveIt() {
        ObservationReader.Observations read = read("bedrock-heights 10 -20", "02", "?4");
        assertEquals(List.of(
            block(OVERWORLD, 10, -60, -20, true),
            block(OVERWORLD, 11, -62, -20, true), block(OVERWORLD, 11, -61, -20, false), block(OVERWORLD, 11, -60, -20, false),
            block(OVERWORLD, 11, -64, -19, true), block(OVERWORLD, 11, -63, -19, false), block(OVERWORLD, 11, -62, -19, false),
            block(OVERWORLD, 11, -61, -19, false), block(OVERWORLD, 11, -60, -19, false)), read.bedrock());
        assertTrue(read.structures().isEmpty());
    }

    @Test
    void heightsCanBeWrittenInSeveralWays() {
        List<BedrockObservation> expected = read("bedrock-heights 0 0", "0123").bedrock();
        assertEquals(expected, read("bedrock-heights 0 0", "0 1 2 3").bedrock());
        assertEquals(expected, read("bedrock-heights 0 0", "0,1,2,3").bedrock());
        assertEquals(expected, read("bedrock-heights 0 0", "-60 -61 -62 -63").bedrock());
        assertEquals(expected, read("bedrock-heights 0 0", "-60, 1, -62, 3").bedrock());
        assertEquals(expected, read("bedrock-heights: 0, 0", "0123").bedrock());
        assertEquals(expected, read("bedrock-heights 0 -59 0", "0123").bedrock(), "x y z from F3 works too");
    }

    @Test
    void forgivingHeadersAndRows() {
        List<BedrockObservation> expected = read("bedrock-heights 0 0", "0123").bedrock();
        assertEquals(expected, read("bedrock heights 0 0", "0123").bedrock());
        assertEquals(expected, read("bedrock-heights 0 0   # my base", "0123   # first row").bedrock());
        assertEquals(expected, read("bedrock-heights 0 0", "-60, -61, -62, -63,").bedrock());
        assertEquals(read("bedrock-heights nether 0 0", "3").bedrock(), read("bedrock-heights minecraft:the_nether 0 0", "3").bedrock());
        assertEquals(read("not-bedrock 1 -61 2").bedrock(), read("Not Bedrock 1 -61 2").bedrock());
    }

    @Test
    void clearMessagesForCommonMistakes() {
        assertTrue(message("bedrock-heights 12.300 -59.000 45.700", "0").contains("not a whole block coordinate"));
        assertTrue(message("bedrock 30000001 -61 0").contains("outside the world"));
        assertTrue(message("bedrock 99999999999 -61 0").contains("outside the world"));
        ObservationReader.LineException rowAfterBlank = assertThrows(ObservationReader.LineException.class,
            () -> read("bedrock-heights 0 0", "0123", "", "1230"));
        assertEquals(3, rowAfterBlank.lineIndex());
        assertTrue(rowAfterBlank.getMessage().contains("right below a bedrock-heights"), rowAfterBlank.getMessage());
        ObservationReader.LineException badFirstRow = assertThrows(ObservationReader.LineException.class,
            () -> read("bedrock-heights 0 0", "01x3"));
        assertEquals(1, badFirstRow.lineIndex());
        ObservationReader.LineException badLaterRow = assertThrows(ObservationReader.LineException.class,
            () -> read("bedrock-layer 0 -61 0", "B..B", "B.,B"));
        assertEquals(2, badLaterRow.lineIndex());
    }

    private static String message(String... lines) {
        return assertThrows(ObservationReader.LineException.class, () -> read(lines)).getMessage();
    }

    @Test
    void netherHeightsUseTheNetherFloor() {
        assertEquals(List.of(block(NETHER, 5, 3, 7, true), block(NETHER, 5, 4, 7, false)), read("bedrock-heights nether 5 7", "3").bedrock());
    }

    @Test
    void layersAndSingleBlocks() {
        assertEquals(List.of(block(OVERWORLD, 1, -61, 2, true), block(OVERWORLD, 2, -61, 2, false), block(OVERWORLD, 2, -61, 3, true)),
            read("bedrock-layer 1 -61 2", "B.", "?B").bedrock());
        assertEquals(List.of(block(OVERWORLD, 1, -61, 2, true), block(OVERWORLD, 3, -62, 4, false), block(NETHER, 5, 124, 6, true)),
            read("bedrock 1 -61 2", "not-bedrock: 3, -62, 4", "bedrock the_nether 5 124 6").bedrock());
    }

    @Test
    void gridsEndAtABlankLineOrTheNextObservation() {
        ObservationReader.Observations read = read(
            "# my world", "igloo 48 208", "bedrock-heights 0 0", "01", "# a comment inside", "10", "village 336 80",
            "bedrock-layer 0 -62 0", "B", "", "swamp_hut 256 -144");
        assertEquals(3, read.structures().size());
        assertEquals(2 + 1 + 1 + 2 + 1, read.bedrock().size());
    }

    @Test
    void mistakesNameTheLine() {
        ObservationReader.LineException e = assertThrows(ObservationReader.LineException.class,
            () -> read("igloo 48 208", "bedrock-heights 0 0", "0127"));
        assertEquals(2, e.lineIndex());
        assertTrue(e.getMessage().contains("digit 7"), e.getMessage());
        assertEquals(1, assertThrows(ObservationReader.LineException.class, () -> read("igloo 48 208", "bedrock-heights 0 0")).lineIndex());
        assertEquals(1, assertThrows(ObservationReader.LineException.class, () -> read("", "bedrock-heights 0 0", "-58")).lineIndex() - 1);
        assertThrows(ObservationReader.LineException.class, () -> read("bedrock 1 2"));
        assertThrows(ObservationReader.LineException.class, () -> read("bedrock-heights end 0 0", "0"));
        assertThrows(ObservationReader.LineException.class, () -> read("bedrock moon 1 2 3"));
        assertThrows(ObservationReader.LineException.class, () -> read("bedrock-layer 0 0", "B"));
    }

    @Test
    void predictedTextReadsBackAsThePattern() {
        BedrockPattern pattern = new BedrockPattern(OVERWORLD, 8091867987493326313L);
        List<String> lines = new ArrayList<>(BedrockText.heights(pattern, -40, 70, 7, 5));
        lines.addAll(BedrockText.layer(pattern, 3, -61, -9, 6, 4));
        for (BedrockObservation observation : read(lines.toArray(String[]::new)).bedrock()) {
            assertEquals(observation.bedrock(), pattern.isBedrock(observation.x(), observation.y(), observation.z()), observation.toString());
        }
        BedrockPattern nether = new BedrockPattern(NETHER, 42L);
        for (BedrockObservation observation : read(BedrockText.heights(nether, 0, 0, 4, 4).toArray(String[]::new)).bedrock()) {
            assertEquals(NETHER, observation.dimension());
            assertEquals(observation.bedrock(), nether.isBedrock(observation.x(), observation.y(), observation.z()));
        }
    }
}
