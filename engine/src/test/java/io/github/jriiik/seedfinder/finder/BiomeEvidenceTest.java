package io.github.jriiik.seedfinder.finder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jriiik.seedfinder.Seeds;
import io.github.jriiik.seedfinder.biome.BiomeZoom;
import io.github.jriiik.seedfinder.biome.OverworldBiomes;
import io.github.jriiik.seedfinder.version.MinecraftVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * The biome evidence rule: when biomes prove a seed, and a guard that its estimate of how often wrong seeds fit is
 * not below what really happens. Biomes of a world are made with the engine's own biome code, which is checked
 * against the game in {@code OverworldBiomesTest}.
 */
class BiomeEvidenceTest {

    private static final MinecraftVersion VERSION = MinecraftVersion.V26_3;
    private static final int THREADS = 4;
    private static final long MASK = 0xFFFF_FFFF_FFFFL;

    /** A seed Minecraft could have picked itself (the first of seed 12345's likely seeds). */
    private static final long LIKELY = 5794443870565576761L;
    /** A seed nobody would pick at random: not one of its structure seed's likely seeds. */
    private static final long ARBITRARY = 0x1234_0000_0000_0000L | (LIKELY & MASK);

    /** The stored biomes of every 4 x 4 cell at height y of the chunks within a radius around a block. */
    static List<BiomeObservation> area(long seed, OverworldBiomes.Preset preset, int blockX, int blockZ, int radiusChunks, int y) {
        OverworldBiomes biomes = OverworldBiomes.of(VERSION, preset);
        OverworldBiomes.Sampler sampler = biomes.newSampler().seed(seed);
        List<BiomeObservation> notes = new ArrayList<>();
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                if (dx * dx + dz * dz > radiusChunks * radiusChunks) {
                    continue;
                }
                int chunkX = (blockX >> 4) + dx;
                int chunkZ = (blockZ >> 4) + dz;
                for (int qx = 0; qx < 4; qx++) {
                    for (int qz = 0; qz < 4; qz++) {
                        int quartX = chunkX * 4 + qx;
                        int quartZ = chunkZ * 4 + qz;
                        String biome = biomes.parameters().biomes().get(sampler.biome(quartX, y >> 2, quartZ));
                        notes.add(new BiomeObservation(biome, quartX * 4, y, quartZ * 4, true));
                    }
                }
            }
        }
        return notes;
    }

    private static BiomeEvidence evidence(List<BiomeObservation> notes) {
        return new BiomeEvidence(VERSION, notes, OptionalLong.empty());
    }

    @Test
    void oneLoadedAreaProvesALikelySeed() {
        List<Long> likely = WorldSeedSearch.likelySeeds(LIKELY & MASK);
        assertTrue(likely.contains(LIKELY));
        BiomeEvidence evidence = evidence(area(LIKELY, OverworldBiomes.Preset.NORMAL, 100, -300, 6, 64));
        BiomeEvidence.Decision decision = evidence.search(List.of(LIKELY & MASK), likely, THREADS, () -> false, null);
        assertEquals(List.of(LIKELY), decision.fitting());
        assertTrue(decision.likely());
        assertEquals(OptionalLong.of(LIKELY), decision.seed(), "chance " + decision.falseFitChance());
        assertEquals(1, decision.groups());
    }

    @Test
    void oneAreaNeverProvesASeedAmongAllCandidates() {
        BiomeEvidence evidence = evidence(area(ARBITRARY, OverworldBiomes.Preset.NORMAL, 100, -300, 6, 64));
        BiomeEvidence.Decision decision = evidence.search(List.of(ARBITRARY & MASK), WorldSeedSearch.likelySeeds(ARBITRARY & MASK), THREADS,
            () -> false, null);
        assertEquals(List.of(ARBITRARY), decision.fitting());
        assertFalse(decision.proven());
        // even when no other candidate fits, the estimate is 3 in 65,536 per candidate, times 65,536 candidates
        assertTrue(decision.falseFitChance() >= 1, "chance " + decision.falseFitChance());
    }

    @Test
    void twoFarAreasProveAnySeedButNearOnesCountOnce() {
        List<BiomeObservation> near = new ArrayList<>(area(ARBITRARY, OverworldBiomes.Preset.NORMAL, 100, -300, 4, 64));
        near.addAll(area(ARBITRARY, OverworldBiomes.Preset.NORMAL, 1600, -300, 4, 64));
        BiomeEvidence nearEvidence = evidence(near);
        assertEquals(2, nearEvidence.places());
        assertEquals(1, nearEvidence.groups(OverworldBiomes.Preset.NORMAL).size());

        List<BiomeObservation> far = new ArrayList<>(area(ARBITRARY, OverworldBiomes.Preset.NORMAL, 100, -300, 4, 64));
        far.addAll(area(ARBITRARY, OverworldBiomes.Preset.NORMAL, 100 + 4400, -300, 4, 64));
        BiomeEvidence farEvidence = evidence(far);
        assertEquals(2, farEvidence.groups(OverworldBiomes.Preset.NORMAL).size());
        // large-biome worlds need their groups much farther apart
        assertEquals(1, farEvidence.groups(OverworldBiomes.Preset.LARGE_BIOMES).size());
        BiomeEvidence.Decision decision = farEvidence.search(List.of(ARBITRARY & MASK), List.of(), THREADS, () -> false, null);
        assertEquals(OptionalLong.of(ARBITRARY), decision.seed(), "chance " + decision.falseFitChance());
        assertEquals(2, decision.groups());
    }

    @Test
    void biomesOfAnotherWorldProveNothing() {
        List<BiomeObservation> notes = new ArrayList<>(area(ARBITRARY, OverworldBiomes.Preset.NORMAL, 100, -300, 4, 64));
        notes.addAll(area(ARBITRARY, OverworldBiomes.Preset.NORMAL, 100 + 4400, -300, 4, 64));
        long other = 987654321987L;
        BiomeEvidence.Decision decision = evidence(notes).search(List.of(other), WorldSeedSearch.likelySeeds(other), THREADS, () -> false, null);
        assertFalse(decision.proven());
        assertTrue(decision.fitting().isEmpty(), decision.fitting().toString());
    }

    @Test
    void aLargeBiomesWorldIsFoundUnderItsPreset() {
        long seed = LIKELY;
        // a place with a few biomes (large-biome areas are often all one biome, which fits many seeds)
        List<BiomeObservation> notes = List.of();
        for (int x = -2000; notes.stream().map(BiomeObservation::biome).distinct().count() < 3; x += 1000) {
            notes = area(seed, OverworldBiomes.Preset.LARGE_BIOMES, x, 700, 6, 64);
        }
        BiomeEvidence evidence = evidence(notes);
        assertFalse(evidence.fits(seed, OverworldBiomes.Preset.NORMAL));
        assertTrue(evidence.fits(seed, OverworldBiomes.Preset.LARGE_BIOMES));
        BiomeEvidence.Decision decision = evidence.search(List.of(seed & MASK), WorldSeedSearch.likelySeeds(seed & MASK), THREADS, () -> false, null);
        assertEquals(List.of(seed), decision.fitting());
        assertEquals(OverworldBiomes.Preset.LARGE_BIOMES, decision.preset().orElseThrow());
    }

    @Test
    void f3NotesFitWithAndWithoutTheHashedSeed() {
        OverworldBiomes biomes = OverworldBiomes.of(VERSION, OverworldBiomes.Preset.NORMAL);
        OverworldBiomes.Sampler sampler = biomes.newSampler().seed(ARBITRARY);
        long hashed = Seeds.hashedSeed(ARBITRARY);
        List<BiomeObservation> notes = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            int x = i * 37 - 700;
            int y = 70 - i;
            int z = i * i * 3 - 500;
            int[] quart = BiomeZoom.quart(hashed, x, y, z);
            notes.add(new BiomeObservation(biomes.parameters().biomes().get(sampler.biome(quart[0], quart[1], quart[2])), x, y, z, false));
        }
        assertTrue(new BiomeEvidence(VERSION, notes, OptionalLong.of(hashed)).fits(ARBITRARY, OverworldBiomes.Preset.NORMAL));
        assertTrue(new BiomeEvidence(VERSION, notes, OptionalLong.empty()).fits(ARBITRARY, OverworldBiomes.Preset.NORMAL));
    }

    @Test
    void unknownBiomesAreReportedAndLeftOut() {
        BiomeEvidence evidence = evidence(List.of(new BiomeObservation("minecraft:nether_wastes", 0, 64, 0, true),
            new BiomeObservation("plains", 0, 64, 0, true)));
        assertEquals(1, evidence.notes().size());
        assertEquals(1, evidence.problems().size());

        // the notes left out are not checked either: a misspelled name doesn't spoil the others
        List<BiomeObservation> notes = new ArrayList<>(area(LIKELY, OverworldBiomes.Preset.NORMAL, 100, -300, 6, 64));
        notes.add(new BiomeObservation("dark forrest", 120, 64, -35, false));
        BiomeEvidence withTypo = evidence(notes);
        assertEquals(1, withTypo.problems().size());
        assertTrue(withTypo.fits(LIKELY, OverworldBiomes.Preset.NORMAL));
        assertTrue(withTypo.fitsUnderAnyPreset(LIKELY));
        BiomeEvidence.Decision decision = withTypo.search(List.of(LIKELY & MASK), WorldSeedSearch.likelySeeds(LIKELY & MASK), 1, () -> false, null);
        assertEquals(OptionalLong.of(LIKELY), decision.seed());
        assertTrue(evidence(List.of(new BiomeObservation("mountains", 0, 64, 0, false))).isEmpty());
    }

    @Test
    void storedCellsAreThinnedToBordersAndOnePerChunk() {
        List<BiomeObservation> notes = area(LIKELY, OverworldBiomes.Preset.NORMAL, 100, -300, 6, 64);
        BiomeEvidence evidence = evidence(notes);
        assertTrue(evidence.notes().size() < notes.size());
        assertEquals(notes.stream().map(BiomeObservation::biome).distinct().count(),
            evidence.notes().stream().map(BiomeObservation::biome).distinct().count());
    }

    @Test
    void theCountedPlacesAreChosenFromTheNotesAlone() {
        // a place with one biome noted first and a richer one 1,500 blocks away: the richer one counts
        List<BiomeObservation> rich = area(ARBITRARY, OverworldBiomes.Preset.NORMAL, 100, -300, 4, 64);
        assertTrue(rich.stream().map(BiomeObservation::biome).distinct().count() >= 2);
        List<BiomeObservation> notes = new ArrayList<>(List.of(new BiomeObservation(rich.get(0).biome(), 1600, 64, -300, false)));
        notes.addAll(rich);
        BiomeEvidence evidence = evidence(notes);
        assertEquals(2, evidence.places());
        assertEquals(List.of(1), evidence.groups(OverworldBiomes.Preset.NORMAL));

        // trips from spawn in three directions: the three far places count, though each is near the first
        List<BiomeObservation> star = new ArrayList<>(area(ARBITRARY, OverworldBiomes.Preset.NORMAL, 0, 0, 1, 64));
        star.addAll(area(ARBITRARY, OverworldBiomes.Preset.NORMAL, 3950, 0, 1, 64));
        star.addAll(area(ARBITRARY, OverworldBiomes.Preset.NORMAL, -3950, 0, 1, 64));
        star.addAll(area(ARBITRARY, OverworldBiomes.Preset.NORMAL, 0, 3950, 1, 64));
        assertEquals(3, evidence(star).groups(OverworldBiomes.Preset.NORMAL).size());
    }

    @Test
    void aTypedSeedIsConfirmedByOneArea() {
        BiomeEvidence evidence = evidence(area(ARBITRARY, OverworldBiomes.Preset.NORMAL, 100, -300, 6, 64));
        OptionalDouble chance = evidence.falseFitChance(ARBITRARY, THREADS, () -> false, null);
        assertTrue(chance.isPresent() && chance.getAsDouble() <= BiomeEvidence.MAX_FALSE_FIT_CHANCE, chance.toString());
        assertTrue(evidence.falseFitChance(ARBITRARY ^ (1L << 50), THREADS, () -> false, null).isEmpty());
    }

    /**
     * The guard: in worlds where weak groups (one chunk each, fitted by a few percent of the candidates) lie exactly
     * the spacing apart, the number of wrong candidates that really fit all groups must not exceed the rule's
     * estimate (LIFT^(G-1) x product of (k + 3) / M, times M), for each preset on its own; and the worlds must expect
     * enough wrong fits for the comparison to mean something. At the spacing, groups fit together about as often as
     * independent ones (measured 1.00 to 1.04 times here; at most 1.9 in single worlds of a wider measurement), so more
     * than {@link #MAX_GUARD_LIFT} times means the spacing no longer works (large-biome places only 4,096 apart give
     * 1.76 here). Re-measure if the grouping, spacing or LIFT change.
     */
    @Test
    void theFalseFitEstimateIsNotBelowReality() {
        long[] seeds = {LIKELY, ARBITRARY, 42L, -7_777_777_777L, 0x0F0F_F0F0_1234_5678L, 3_141_592_653_589_793L, 0x7A7A_0000_0BAD_F00DL,
            -0x1234_5678_9ABC_DEFL};
        StringBuilder report = new StringBuilder();
        for (OverworldBiomes.Preset preset : BiomeEvidence.PRESET_ORDER) {
            double estimated = 0;
            long real = 0;
            double independent = 0;
            int spacing = BiomeEvidence.groupSpacing(preset);
            for (long seed : seeds) {
                List<BiomeObservation> notes = new ArrayList<>(area(seed, preset, 40, 40, 0, 64));
                notes.addAll(area(seed, preset, 40 + spacing + 16, 40, 0, 64));
                BiomeEvidence evidence = evidence(notes);
                assertEquals(2, evidence.groups(preset).size());
                BiomeEvidence.Scan scan = evidence.scan(seed & MASK, preset, THREADS, () -> false, null);
                List<Long> fitting = scan.fittingAll();
                assertTrue(fitting.contains(seed));
                real += fitting.size() - 1;
                double m = BiomeEvidence.CANDIDATES;
                double product = 1;
                double plain = 1;
                for (int place : evidence.groups(preset)) {
                    int others = scan.count(place) - 1;
                    product *= (others + BiomeEvidence.PSEUDO_COUNT) / m;
                    plain *= others / m;
                }
                estimated += m * BiomeEvidence.LIFT * product;
                independent += m * plain;
            }
            report.append(String.format(java.util.Locale.ROOT, "%s: real %d, estimated %.1f, independent %.1f; ", preset, real, estimated, independent));
            assertTrue(real <= estimated, "wrong candidates fitting both groups: " + report);
            assertTrue(real <= MAX_GUARD_LIFT * independent, "groups at the spacing fit together too often: " + report);
            assertTrue(estimated >= MIN_GUARD_ESTIMATE, "too few expected wrong fits for the guard to tell anything: " + report);
        }
    }

    /** The guard's worlds must expect at least this many wrong fits per preset, or it couldn't catch an estimate too low. */
    private static final double MIN_GUARD_ESTIMATE = 500;
    /** How much more often than independent groups the guard's groups may fit together (the rule allows LIFT = 2). */
    private static final double MAX_GUARD_LIFT = 1.5;
}
