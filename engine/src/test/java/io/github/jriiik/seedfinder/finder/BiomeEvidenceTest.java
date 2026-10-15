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
     * estimate (LIFT^(G-1) x product of (k + 3) / M, times M). Re-measure if the grouping, spacing or LIFT change.
     */
    @Test
    void theFalseFitEstimateIsNotBelowReality() {
        double estimated = 0;
        long real = 0;
        double independent = 0;
        long[] seeds = {LIKELY, ARBITRARY, 42L, -7_777_777_777L, 0x0F0F_F0F0_1234_5678L, 3_141_592_653_589_793L};
        for (int w = 0; w < seeds.length; w++) {
            OverworldBiomes.Preset preset = w < 4 ? OverworldBiomes.Preset.NORMAL : OverworldBiomes.Preset.LARGE_BIOMES;
            int spacing = BiomeEvidence.groupSpacing(preset);
            long seed = seeds[w];
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
        assertTrue(real <= estimated, "wrong candidates fitting both groups: " + real + ", estimated " + estimated + " (independent: " + independent + ")");
    }
}
