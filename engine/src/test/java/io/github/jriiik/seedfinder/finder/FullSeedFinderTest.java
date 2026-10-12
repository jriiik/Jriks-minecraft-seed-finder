package io.github.jriiik.seedfinder.finder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jriiik.seedfinder.Seeds;
import io.github.jriiik.seedfinder.bedrock.BedrockDimension;
import io.github.jriiik.seedfinder.bedrock.BedrockEvidence;
import io.github.jriiik.seedfinder.bedrock.BedrockObservation;
import io.github.jriiik.seedfinder.bedrock.BedrockPattern;
import io.github.jriiik.seedfinder.bedrock.BedrockText;
import io.github.jriiik.seedfinder.rng.JavaRandom;
import io.github.jriiik.seedfinder.version.MinecraftVersion;
import io.github.jriiik.seedfinder.version.VersionData;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class FullSeedFinderTest {

    private static final VersionData DATA = MinecraftVersion.V26_3.data();
    private static final BedrockDimension OVERWORLD = DATA.bedrockDimension("overworld").orElseThrow();
    private static final BedrockDimension NETHER = DATA.bedrockDimension("nether").orElseThrow();

    /** What a player notes after digging out a square of the overworld floor. */
    private static List<BedrockObservation> heights(long worldSeed, int minX, int minZ, int size) {
        List<String> text = BedrockText.heights(new BedrockPattern(OVERWORLD, worldSeed), minX, minZ, size, size);
        return ObservationReader.read(DATA, text).bedrock();
    }

    private static FullSeedFinder.Result search(long structureSeed, List<BedrockObservation> observations) {
        return new FullSeedFinder(List.of(structureSeed), new BedrockEvidence(observations)).search(4);
    }

    @Test
    void oneChunkOfBedrockHeightsFindsTheSeed() {
        long[] seeds = {12345L, 8091867987493326313L, new JavaRandom(2026).nextLong(), "Jrik".hashCode(), Long.MIN_VALUE};
        for (long seed : seeds) {
            FullSeedFinder.Result result = search(Seeds.structureSeed(seed), heights(seed, 0, 0, 16));
            assertTrue(result.checkedAll());
            assertEquals(65_536, result.seedsChecked());
            assertEquals(1, result.best().size(), () -> "seed " + seed + ": " + result.candidates());
            assertEquals(seed, result.best().get(0).worldSeed());
            assertEquals(0, result.best().get(0).mismatches());
            assertEquals(1, result.perfectFits());
            assertEquals(FullSeedFinder.originOf(seed), result.best().get(0).origin());
        }
    }

    @Test
    void originsAreRecognised() {
        assertEquals(FullSeedFinder.Origin.RANDOM, FullSeedFinder.originOf(new JavaRandom(7).nextLong()));
        assertEquals(FullSeedFinder.Origin.TEXT, FullSeedFinder.originOf("Jrik".hashCode()));
        assertEquals(FullSeedFinder.Origin.TYPED_NUMBER, FullSeedFinder.originOf(1234567890123456789L));
        // Some long numbers could also have been picked by Minecraft: about 1 in 65,536.
        assertEquals(FullSeedFinder.Origin.RANDOM, FullSeedFinder.originOf(8091867987493326313L));
    }

    @Test
    void mistakesInTheNotesDontHideTheSeed() {
        long seed = -6984854390176336655L;
        List<BedrockObservation> observations = new ArrayList<>(heights(seed, -500, 1200, 16));
        for (int i : new int[] {5, 77, 300}) {
            BedrockObservation o = observations.get(i);
            observations.set(i, new BedrockObservation(o.dimension(), o.x(), o.y(), o.z(), !o.bedrock()));
        }
        FullSeedFinder.Result result = search(Seeds.structureSeed(seed), observations);
        assertEquals(seed, result.candidates().get(0).worldSeed());
        assertEquals(3, result.candidates().get(0).mismatches());
        assertTrue(result.candidates().get(1).mismatches() > 3);
        assertEquals(3, new BedrockEvidence(observations).mismatchesOf(seed).size());
    }

    @Test
    void withoutBedrockTheLikelySeedsAreListed() {
        long random = new JavaRandom(99).nextLong();
        FullSeedFinder.Result result = search(Seeds.structureSeed(random), List.of());
        assertFalse(result.checkedAll());
        assertTrue(result.candidates().stream().anyMatch(c -> c.worldSeed() == random && c.origin() == FullSeedFinder.Origin.RANDOM));

        long text = "My server".hashCode();
        result = search(Seeds.structureSeed(text), List.of());
        assertTrue(result.candidates().stream().anyMatch(c -> c.worldSeed() == text && c.origin() == FullSeedFinder.Origin.TEXT));
    }

    @Test
    void bedrockTellsLookAlikeStructureSeedsApart() {
        long seed = 3141592653589793238L;
        long other = Seeds.structureSeed(seed + 77549457968617L);
        FullSeedFinder finder = new FullSeedFinder(List.of(other, Seeds.structureSeed(seed)), new BedrockEvidence(heights(seed, 64, 64, 16)));
        FullSeedFinder.Result result = finder.search(2);
        assertEquals(1, result.best().size());
        assertEquals(seed, result.best().get(0).worldSeed());
        assertEquals(2 * 65_536, result.seedsChecked());
    }

    @Test
    void netherBedrockOnlyChecksTheStructureSeed() {
        long seed = 8091867987493326313L;
        List<String> text = BedrockText.heights(new BedrockPattern(NETHER, seed), 0, 0, 16, 16);
        List<BedrockObservation> nether = ObservationReader.read(DATA, text).bedrock();
        BedrockEvidence evidence = new BedrockEvidence(nether);
        assertTrue(evidence.fullSeedObservations().isEmpty());
        assertEquals(0, evidence.structureSeedMismatches(Seeds.structureSeed(seed)));
        assertTrue(evidence.structureSeedMismatches(Seeds.structureSeed(seed) ^ 1) > 50);
    }

    @Test
    void aSmallPatchCanLeaveSeveralSeedsAndSaysWhereToLook() {
        SplittableRandom random = new SplittableRandom(5);
        for (int attempt = 0; attempt < 50; attempt++) {
            long seed = random.nextLong();
            List<BedrockObservation> observations = heights(seed, 0, 0, 3);
            FullSeedFinder.Result result = search(Seeds.structureSeed(seed), observations);
            List<FullSeedFinder.Candidate> best = result.best();
            assertEquals(Math.min(result.perfectFits(), FullSeedFinder.MAX_CANDIDATES), best.size());
            if (result.perfectFits() > FullSeedFinder.MAX_CANDIDATES) {
                continue; // too many to list them all
            }
            assertTrue(best.stream().anyMatch(c -> c.worldSeed() == seed));
            if (best.size() < 2) {
                continue;
            }
            List<Long> seeds = best.stream().map(FullSeedFinder.Candidate::worldSeed).limit(4).toList();
            List<int[]> columns = FullSeedFinder.distinguishingColumns(OVERWORLD, seeds, observations, 5);
            assertFalse(columns.isEmpty());
            for (int[] column : columns) {
                assertFalse(column[0] >= 0 && column[0] < 3 && column[1] >= 0 && column[1] < 3, "already observed");
                long distinct = seeds.stream().mapToInt(s -> new BedrockPattern(OVERWORLD, s).floorTop(column[0], column[1])).distinct().count();
                assertTrue(distinct > 1);
            }
            return;
        }
        throw new AssertionError("expected a 3x3 patch to leave several seeds at least once");
    }

    @Test
    void theResultDoesNotDependOnTheNumberOfThreads() {
        long seed = 12345L;
        BedrockEvidence evidence = new BedrockEvidence(heights(seed, 0, 0, 5));
        FullSeedFinder.Result one = new FullSeedFinder(List.of(Seeds.structureSeed(seed)), evidence).search(1);
        FullSeedFinder.Result four = new FullSeedFinder(List.of(Seeds.structureSeed(seed)), evidence).search(4);
        assertEquals(one, four);
        assertEquals(FullSeedFinder.MAX_CANDIDATES, one.candidates().size());
        for (int i = 1; i < one.candidates().size(); i++) {
            assertTrue(one.candidates().get(i - 1).mismatches() <= one.candidates().get(i).mismatches());
        }
    }

    /** Guards the estimate the report relies on: it must not be lower than reality (and not wildly higher). */
    @Test
    void theFalseFitEstimateMatchesReality() {
        SplittableRandom random = new SplittableRandom(2027);
        int trials = 200;
        long falseFits = 0;
        long blocks = 0;
        for (int i = 0; i < trials; i++) {
            long seed = random.nextLong();
            BedrockEvidence evidence = new BedrockEvidence(heights(seed, random.nextInt(-9999, 9999), random.nextInt(-9999, 9999), 8));
            blocks += evidence.fullSeedObservations().size();
            long wrongStructureSeed = random.nextLong() & Seeds.STRUCTURE_SEED_MASK;
            falseFits += new FullSeedFinder(List.of(wrongStructureSeed), evidence).search(4).perfectFits();
        }
        double measured = falseFits / (double) trials; // about 0.22 for 8 x 8 bedrock heights
        double estimate = FullSeedFinder.expectedFalseFits((int) (blocks / trials));
        assertTrue(estimate >= measured && estimate < 6 * measured, "estimate " + estimate + ", measured " + measured);
        assertTrue(FullSeedFinder.expectedFalseFits(633) < 0.02, "a whole chunk of heights must count as certain");
    }

    @Test
    void impossibleNotesAreReported() {
        List<BedrockObservation> observations = List.of(
            new BedrockObservation(OVERWORLD, 0, -64, 0, false),
            new BedrockObservation(OVERWORLD, 0, -50, 0, true),
            new BedrockObservation(OVERWORLD, 1, -61, 1, true),
            new BedrockObservation(OVERWORLD, 1, -61, 1, false),
            new BedrockObservation(OVERWORLD, 2, -64, 2, true));
        BedrockEvidence evidence = new BedrockEvidence(observations);
        assertEquals(3, evidence.problems().size(), evidence.problems().toString());
        assertEquals(1, evidence.fullSeedObservations().size());
        assertEquals(3, evidence.uninformativeCount());
        assertNotEquals(List.of(), evidence.mismatchesOf(12345L));
    }
}
