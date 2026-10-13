package io.github.jriiik.seedfinder.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.jriiik.seedfinder.finder.SeedCheck;
import io.github.jriiik.seedfinder.finder.StructureObservation;
import io.github.jriiik.seedfinder.structure.StructureSet;
import io.github.jriiik.seedfinder.version.MinecraftVersion;
import io.github.jriiik.seedfinder.world.ChunkPos;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The sentences about a typed seed read right for one or several notes (no Minecraft needed). */
class StatusTextTest {

    private static final StructureSet VILLAGES = MinecraftVersion.V26_3.data().structureSet("villages").orElseThrow();

    private static List<StructureObservation> villages(int count) {
        return Collections.nCopies(count, new StructureObservation(VILLAGES, new ChunkPos(21, 5)));
    }

    private static SeedCheck check(int fitting, int misfits, int bedrockBlocks, int bedrockMismatches) {
        return new SeedCheck(12345L, SeedCheck.Verdict.CONFIRMED, villages(fitting), villages(misfits), bedrockBlocks, bedrockMismatches, 0);
    }

    @Test
    void whatFitsTheSeed() {
        assertEquals("the bedrock seen fits it", StatusText.agreeing(check(0, 0, 1024, 0)));
        assertEquals("the 3 structures noted fit it", StatusText.agreeing(check(3, 0, 0, 0)));
        assertEquals("the bedrock seen and the structure noted fit it", StatusText.agreeing(check(1, 0, 1024, 0)));
        assertEquals("1 of the 2 structures noted fits it", StatusText.agreeing(check(1, 1, 1024, 7)));
        assertEquals("the bedrock seen and 2 of the 3 structures noted fit it", StatusText.agreeing(check(2, 1, 1024, 0)));
    }

    @Test
    void whatDoesntFitTheSeed() {
        assertEquals("the structure noted doesn't fit it", StatusText.disagreeing(check(0, 1, 0, 0)));
        assertEquals("the bedrock seen doesn't fit it", StatusText.disagreeing(check(0, 0, 1024, 400)));
        assertEquals("1 of the 2 structures noted and the bedrock seen don't fit it", StatusText.disagreeing(check(1, 1, 1024, 400)));
        assertEquals("2 of the 3 structures noted don't fit it", StatusText.disagreeing(check(1, 2, 0, 0)));
    }
}
