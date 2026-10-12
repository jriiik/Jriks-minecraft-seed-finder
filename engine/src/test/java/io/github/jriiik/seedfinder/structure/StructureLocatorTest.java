package io.github.jriiik.seedfinder.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jriiik.seedfinder.version.MinecraftVersion;
import io.github.jriiik.seedfinder.version.VersionData;
import io.github.jriiik.seedfinder.world.BlockPos;
import io.github.jriiik.seedfinder.world.ChunkPos;
import java.util.List;
import org.junit.jupiter.api.Test;

class StructureLocatorTest {

    private static final VersionData DATA = MinecraftVersion.V26_3.data();

    @Test
    void locatePositionIncludesTheSetsOffset() {
        StructureSet treasure = DATA.structureSet("buried_treasures").orElseThrow();
        StructureSet villages = DATA.structureSet("villages").orElseThrow();
        ChunkPos chunk = new ChunkPos(-3, 7);
        assertEquals(new BlockPos(-48 + 9, 0, 112 + 9), treasure.placement().locatePos(chunk));
        assertEquals(new BlockPos(-48, 0, 112), villages.placement().locatePos(chunk));
    }

    @Test
    void exclusionZoneRemovesOutpostsNearVillages() {
        // Find an outpost candidate that passes its frequency roll but is too close to a village, and check that
        // the locator rejects it while a far-away one is kept.
        StructureSet outposts = DATA.structureSet("pillager_outposts").orElseThrow();
        StructureSet villages = DATA.structureSet("villages").orElseThrow();
        RandomSpreadPlacement placement = (RandomSpreadPlacement) outposts.placement();
        StructureLocator locator = new StructureLocator(DATA, 12345L);
        int excluded = 0;
        int kept = 0;
        for (int rx = -20; rx <= 20; rx++) {
            for (int rz = -20; rz <= 20; rz++) {
                ChunkPos chunk = placement.potentialChunk(12345L, rx, rz);
                if (!placement.passesFrequency(12345L, chunk.x(), chunk.z())) {
                    continue;
                }
                boolean villageNearby = !locator.candidates(villages, chunk.x() - 10, chunk.z() - 10, chunk.x() + 10, chunk.z() + 10).isEmpty();
                assertEquals(!villageNearby, locator.isStructureChunk(outposts, chunk.x(), chunk.z()), chunk.toString());
                if (villageNearby) {
                    excluded++;
                } else {
                    kept++;
                }
            }
        }
        assertTrue(excluded > 0 && kept > 0, "expected both cases, got excluded=" + excluded + " kept=" + kept);
    }

    @Test
    void candidatesStayInsideTheRequestedArea() {
        StructureSet villages = DATA.structureSet("villages").orElseThrow();
        StructureLocator locator = new StructureLocator(DATA, -1L);
        List<StructureCandidate> found = locator.candidates(villages, -100, -50, 37, 80);
        assertFalse(found.isEmpty());
        for (StructureCandidate candidate : found) {
            assertTrue(candidate.chunk().x() >= -100 && candidate.chunk().x() <= 37, candidate.toString());
            assertTrue(candidate.chunk().z() >= -50 && candidate.chunk().z() <= 80, candidate.toString());
        }
    }

    @Test
    void strongholdsAreNotSupportedYet() {
        StructureSet strongholds = DATA.structureSet("strongholds").orElseThrow();
        assertFalse(StructureLocator.canLocate(strongholds));
        StructureLocator locator = new StructureLocator(DATA, 1L);
        assertThrows(UnsupportedOperationException.class, () -> locator.candidates(strongholds, 0, 0, 10, 10));
    }
}
