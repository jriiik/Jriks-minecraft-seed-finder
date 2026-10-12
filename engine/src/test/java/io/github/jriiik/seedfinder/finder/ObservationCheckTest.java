package io.github.jriiik.seedfinder.finder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jriiik.seedfinder.Seeds;
import io.github.jriiik.seedfinder.rng.JavaRandom;
import io.github.jriiik.seedfinder.structure.RandomSpreadPlacement;
import io.github.jriiik.seedfinder.structure.StructureSet;
import io.github.jriiik.seedfinder.version.MinecraftVersion;
import io.github.jriiik.seedfinder.version.VersionData;
import io.github.jriiik.seedfinder.world.ChunkPos;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ObservationCheckTest {

    private static final VersionData DATA = MinecraftVersion.V26_3.data();

    private static ObservationCheck check(String set, ChunkPos chunk) {
        StructureSet structureSet = DATA.structureSet(set).orElseThrow();
        return new ObservationCheck(new StructureObservation(structureSet, chunk), (RandomSpreadPlacement) structureSet.placement());
    }

    @Test
    void liftBitsDependOnTheRegionSize() {
        assertEquals(18, check("villages", new ChunkPos(0, 0)).liftBits(), "26 = 2 * 13");
        assertEquals(20, check("desert_pyramids", new ChunkPos(0, 0)).liftBits(), "24 = 8 * 3");
        assertEquals(19, check("shipwrecks", new ChunkPos(0, 0)).liftBits(), "20 = 4 * 5");
        assertEquals(0, check("ancient_cities", new ChunkPos(0, 0)).liftBits(), "16 is a power of two");
        assertEquals(0, check("ruined_portals", new ChunkPos(0, 0)).liftBits(), "25 is odd");
        assertEquals(0, check("ocean_monuments", new ChunkPos(0, 0)).liftBits(), "triangular spread");
    }

    @Test
    void theTrueSeedAlwaysPassesEveryCheck() {
        Random random = new Random(1);
        JavaRandom scratch = new JavaRandom(0);
        for (int i = 0; i < 300; i++) {
            long structureSeed = Seeds.structureSeed(random.nextLong());
            for (StructureSet set : DATA.structureSets()) {
                if (!(set.placement() instanceof RandomSpreadPlacement spread)) {
                    continue;
                }
                ChunkPos chunk = spread.potentialChunk(structureSeed, random.nextInt(200) - 100, random.nextInt(200) - 100);
                ObservationCheck check = new ObservationCheck(new StructureObservation(set, chunk), spread);
                assertTrue(check.isPossibleStartChunk(), set.id());
                if (check.liftBits() > 0) {
                    assertTrue(check.lowBitsMatch(structureSeed & ((1L << check.liftBits()) - 1)), set.id() + " seed " + structureSeed);
                }
                if (spread.spacing() > 1) {
                    assertTrue(check.matches(structureSeed, scratch), set.id() + " seed " + structureSeed);
                }
            }
        }
    }

    @Test
    void lowBitsFilterOutTheExpectedShare() {
        // Desert pyramids: 3 low bits per coordinate are revealed, so 1 in 64 random low values pass.
        ObservationCheck check = check("desert_pyramids", new ChunkPos(13, -50));
        int passed = 0;
        int tries = 1 << 20;
        for (long low = 0; low < tries; low++) {
            if (check.lowBitsMatch(low)) {
                passed++;
            }
        }
        assertEquals(tries / 64.0, passed, tries / 64.0 * 0.05);
    }

    @Test
    void rejectsChunksOutsideTheStartArea() {
        // Villages start in the first 26 chunks of each 34-chunk region.
        assertTrue(check("villages", new ChunkPos(25, 3)).isPossibleStartChunk());
        assertTrue(!check("villages", new ChunkPos(26, 3)).isPossibleStartChunk());
        assertTrue(!check("villages", new ChunkPos(-1, 3)).isPossibleStartChunk(), "-1 is offset 33 in region -1");
    }

    @Test
    void probabilitiesMatchThePlacement() {
        assertEquals(1.0 / 26 / 26, check("villages", new ChunkPos(0, 0)).probability(), 1e-12);
        assertEquals(0.2 / 24 / 24, check("pillager_outposts", new ChunkPos(0, 0)).probability(), 1e-9);
        assertEquals(0.01, check("buried_treasures", new ChunkPos(5, 5)).probability(), 1e-9);
        // Triangular: offset 13 of 27 is the most likely value of (a + b) / 2.
        assertTrue(check("ocean_monuments", new ChunkPos(13, 13)).probability() > check("ocean_monuments", new ChunkPos(0, 0)).probability());
    }
}
