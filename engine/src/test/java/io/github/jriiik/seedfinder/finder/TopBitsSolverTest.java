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
import java.util.Arrays;
import java.util.Random;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class TopBitsSolverTest {

    private static final VersionData DATA = MinecraftVersion.V26_3.data();
    private static final long LOW_35 = (1L << 35) - 1;

    @Test
    void givesExactlyTheTopBitsThatTryingAllWouldFind() {
        Random random = new Random(3);
        JavaRandom scratch = new JavaRandom(0);
        int compared = 0;
        for (StructureSet set : DATA.structureSets()) {
            if (!(set.placement() instanceof RandomSpreadPlacement spread)) {
                continue;
            }
            for (int i = 0; i < 20; i++) {
                long structureSeed = Seeds.structureSeed(random.nextLong());
                ChunkPos chunk = spread.potentialChunk(structureSeed, random.nextInt(100) - 50, random.nextInt(100) - 50);
                ObservationCheck check = new ObservationCheck(new StructureObservation(set, chunk), spread);
                if (!check.canSolveTopBits()) {
                    continue;
                }
                TopBitsSolver solver = new TopBitsSolver(check);
                // Any low 35 bits will do; take the true seed's so that at least one answer exists.
                long low35 = structureSeed & LOW_35;
                long[] out = new long[solver.maxCandidates()];
                int count = solver.candidates(low35, out);
                TreeSet<Long> solved = new TreeSet<>();
                Arrays.stream(out, 0, count).forEach(solved::add);

                TreeSet<Long> bruteForce = new TreeSet<>();
                for (long top = 0; top < 8192; top++) {
                    long seed = low35 | top << 35;
                    scratch.setSeed(seed + check.seedOffset());
                    if (scratch.nextInt(check.bound()) == check.offsetX()) {
                        bruteForce.add(seed);
                    }
                }
                assertEquals(bruteForce, solved, set.id() + " seed " + structureSeed);
                assertTrue(solved.contains(structureSeed), set.id());
                compared++;
            }
        }
        assertTrue(compared > 100, "compared " + compared);
    }

    @Test
    void leavesOneCandidateInEveryOddPart() {
        StructureSet villages = DATA.structureSet("villages").orElseThrow();
        ObservationCheck check = new ObservationCheck(new StructureObservation(villages, new ChunkPos(5, 9)),
            (RandomSpreadPlacement) villages.placement());
        assertEquals(13, check.oddPart());
        assertEquals(631, new TopBitsSolver(check).maxCandidates(), "8192 / 13, rounded up");
    }
}
