package io.github.jriiik.seedfinder.finder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jriiik.seedfinder.bedrock.BedrockDimension;
import io.github.jriiik.seedfinder.bedrock.BedrockObservation;
import io.github.jriiik.seedfinder.bedrock.BedrockPattern;
import io.github.jriiik.seedfinder.bedrock.BedrockText;
import io.github.jriiik.seedfinder.structure.RandomSpreadPlacement;
import io.github.jriiik.seedfinder.structure.SpreadType;
import io.github.jriiik.seedfinder.structure.StructureCandidate;
import io.github.jriiik.seedfinder.structure.StructureLocator;
import io.github.jriiik.seedfinder.structure.StructureSet;
import io.github.jriiik.seedfinder.version.MinecraftVersion;
import io.github.jriiik.seedfinder.version.VersionData;
import io.github.jriiik.seedfinder.world.ChunkPos;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SeedCheckTest {

    private static final VersionData DATA = MinecraftVersion.V26_3.data();
    private static final BedrockDimension OVERWORLD = DATA.bedrockDimension("overworld").orElseThrow();
    private static final BedrockDimension NETHER = DATA.bedrockDimension("nether").orElseThrow();
    /** Structure spots of seed 12345 (the README example). */
    private static final List<String> STRUCTURES_12345 = List.of(
        "desert_pyramid 272 80", "swamp_hut 256 -144", "village 336 80", "shipwreck -128 32", "ruined_portal 16 0", "trial_chamber 0 224");
    /** A seed Minecraft picked itself, for a world with many notes. */
    private static final long PICKED = 8091867987493326313L;

    private static List<StructureObservation> structures(List<String> lines) {
        return ObservationReader.read(DATA, lines).structures();
    }

    private static List<BedrockObservation> bedrock(List<String> lines) {
        return ObservationReader.read(DATA, lines).bedrock();
    }

    /** A chunk of floor heights (about 630 blocks that depend on the seed). */
    private static List<BedrockObservation> bedrockOf(BedrockDimension dimension, long worldSeed) {
        return bedrock(BedrockText.heights(new BedrockPattern(dimension, worldSeed), 0, 0, 16, 16));
    }

    private static List<BedrockObservation> bedrockOf(long worldSeed) {
        return bedrockOf(OVERWORLD, worldSeed);
    }

    private static List<BedrockObservation> together(List<BedrockObservation> a, List<BedrockObservation> b) {
        List<BedrockObservation> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    /** A village one chunk off the spot seed 12345 uses in a region: like one from an older version, a command or a copy. */
    private static StructureObservation misfitVillage() {
        StructureSet villages = DATA.structureSet("villages").orElseThrow();
        ChunkPos spot = ((RandomSpreadPlacement) villages.placement()).potentialChunk(12345L, 3, 3);
        return new StructureObservation(villages, new ChunkPos(spot.x() + 1, spot.z()));
    }

    /** The 60 starts nearest to the middle of a world, of the kinds the mod notes by itself (biomes not checked). */
    private static List<StructureObservation> manyNotes(long worldSeed) {
        StructureLocator locator = new StructureLocator(DATA, worldSeed);
        List<StructureObservation> all = new ArrayList<>();
        for (String id : List.of("villages", "pillager_outposts", "trial_chambers", "ancient_cities", "trail_ruins", "abandoned_camp")) {
            StructureSet set = DATA.structureSet(id).orElseThrow();
            for (StructureCandidate candidate : locator.candidates(set, -150, -150, 150, 150)) {
                all.add(new StructureObservation(set, candidate.chunk()));
            }
        }
        all.sort(Comparator.comparingLong(note -> (long) note.chunk().x() * note.chunk().x() + (long) note.chunk().z() * note.chunk().z()));
        return all.subList(0, 60);
    }

    @Test
    void theNotesOfTheWorldConfirmItsSeed() {
        SeedCheck check = SeedCheck.of(DATA, structures(STRUCTURES_12345), bedrockOf(12345L), 12345L);
        assertEquals(SeedCheck.Verdict.CONFIRMED, check.verdict());
        assertEquals(6, check.fitting().size());
        assertEquals(List.of(), check.misfits());
        assertTrue(check.overworld().fits() && check.overworld().blocks() > 500, check.overworld().toString());
        assertTrue(check.falseFitChance() < 1e-12, "chance " + check.falseFitChance());
    }

    @Test
    void overworldBedrockAloneOrTwoStructuresConfirmIt() {
        assertEquals(SeedCheck.Verdict.CONFIRMED, SeedCheck.of(DATA, List.of(), bedrockOf(12345L), 12345L).verdict());
        assertEquals(SeedCheck.Verdict.CONFIRMED,
            SeedCheck.of(DATA, structures(List.of("village 336 80", "trial_chamber 0 224")), List.of(), 12345L).verdict());
    }

    @Test
    void withTooLittleNotedItIsNotConfirmedYet() {
        SeedCheck nothing = SeedCheck.of(DATA, List.of(), List.of(), 12345L);
        assertEquals(SeedCheck.Verdict.UNCONFIRMED, nothing.verdict());
        assertEquals(1, nothing.falseFitChance());

        // One village: a wrong seed would put a village there about once in 676 times.
        SeedCheck oneVillage = SeedCheck.of(DATA, structures(List.of("village 336 80")), List.of(), 12345L);
        assertEquals(SeedCheck.Verdict.UNCONFIRMED, oneVillage.verdict());
        assertEquals(1, oneVillage.fitting().size());

        // A 6 x 6 patch of one bedrock layer: in some worlds a wrong seed fits that many blocks a few times in 1,000.
        List<BedrockObservation> patch = bedrock(BedrockText.layer(new BedrockPattern(OVERWORLD, 12345L), 0, -61, 0, 6, 6));
        SeedCheck small = SeedCheck.of(DATA, List.of(), patch, 12345L);
        assertTrue(small.overworld().fits());
        assertEquals(SeedCheck.Verdict.UNCONFIRMED, small.verdict());
    }

    @Test
    void bedrockAloneConfirmsOnlyFromAboutThreeHundredBlocksEvenNextToTheMiddleOfTheWorld() {
        // Next to x = 0 and z = 0 the game's random numbers for neighbouring blocks are alike, so wrong seeds fit small
        // notes there far more often: a 9 x 9 layer around the middle doesn't confirm a seed, an 18 x 18 one does.
        BedrockPattern pattern = new BedrockPattern(OVERWORLD, 12345L);
        SeedCheck nine = SeedCheck.of(DATA, List.of(), bedrock(BedrockText.layer(pattern, -4, -61, -4, 9, 9)), 12345L);
        assertTrue(nine.overworld().fits());
        assertEquals(SeedCheck.Verdict.UNCONFIRMED, nine.verdict());
        assertEquals(SeedCheck.Verdict.CONFIRMED, SeedCheck.of(DATA, List.of(), bedrock(BedrockText.layer(pattern, -9, -61, -9, 18, 18)), 12345L).verdict());

        // Measured: how often random wrong seeds fit such an 18 x 18 layer perfectly, in 20 worlds.
        Random worlds = new Random(13);
        int samples = 20_000;
        for (int w = 0; w < 20; w++) {
            long world = worlds.nextLong();
            List<BedrockObservation> notes = bedrock(BedrockText.layer(new BedrockPattern(OVERWORLD, world), -9, -61, -9, 18, 18));
            io.github.jriiik.seedfinder.bedrock.BedrockEvidence evidence = new io.github.jriiik.seedfinder.bedrock.BedrockEvidence(notes);
            assertTrue(evidence.fullSeedObservations().size() >= SeedCheck.BEDROCK_ALONE);
            Random seeds = new Random(world);
            int fits = 0;
            for (int i = 0; i < samples; i++) {
                fits += evidence.fullSeedMismatches(seeds.nextLong(), 0) == 0 ? 1 : 0;
            }
            assertTrue(fits <= samples * SeedCheck.MAX_FALSE_FIT_CHANCE, "world " + world + ": " + fits + " of " + samples + " wrong seeds fit");
        }
    }

    @Test
    void theNotesOfAnotherWorldContradictIt() {
        SeedCheck check = SeedCheck.of(DATA, structures(STRUCTURES_12345), bedrockOf(12345L), 999L);
        assertEquals(SeedCheck.Verdict.CONTRADICTED, check.verdict());
        assertTrue(check.misfits().size() >= 5, check.misfits().toString());
        assertTrue(check.overworld().mismatches() > 0);

        assertEquals(SeedCheck.Verdict.CONTRADICTED, SeedCheck.of(DATA, List.of(), bedrockOf(12345L), 999L).verdict());
        assertEquals(SeedCheck.Verdict.CONTRADICTED, SeedCheck.of(DATA, List.of(misfitVillage()), List.of(), 12345L).verdict());
    }

    @Test
    void overworldBedrockOutweighsAStructureThatDoesntFit() {
        // The server may place structures differently, or the village comes from older chunks: the bedrock decides.
        SeedCheck check = SeedCheck.of(DATA, List.of(misfitVillage()), bedrockOf(12345L), 12345L);
        assertEquals(SeedCheck.Verdict.CONFIRMED, check.verdict());
        assertEquals(List.of(misfitVillage()), check.misfits());
    }

    @Test
    void overworldBedrockThatDoesntFitIsNeverOutweighed() {
        // Six structures fit, but the right seed fits every valid overworld bedrock note.
        SeedCheck check = SeedCheck.of(DATA, structures(STRUCTURES_12345), bedrockOf(999L), 12345L);
        assertEquals(6, check.fitting().size());
        assertTrue(check.overworld().mismatches() > 0);
        assertEquals(SeedCheck.Verdict.CONTRADICTED, check.verdict());
    }

    @Test
    void netherBedrockFromOlderChunksDoesntOutweighTheOverworld() {
        // Nether chunks made by Minecraft 1.17 or older have bedrock that doesn't follow the seed.
        SeedCheck oldNether = SeedCheck.of(DATA, List.of(), together(bedrockOf(12345L), bedrockOf(NETHER, 999L)), 12345L);
        assertEquals(SeedCheck.Verdict.CONFIRMED, oldNether.verdict());
        assertTrue(oldNether.overworld().fits() && oldNether.nether().mismatches() > 0, oldNether.toString());

        // Nether bedrock alone: it contradicts a seed it doesn't fit, but never confirms one (it only depends on 48 bits).
        assertEquals(SeedCheck.Verdict.CONTRADICTED, SeedCheck.of(DATA, List.of(), bedrockOf(NETHER, 999L), 12345L).verdict());
        SeedCheck netherOnly = SeedCheck.of(DATA, List.of(), bedrockOf(NETHER, 12345L), 12345L);
        assertTrue(netherOnly.nether().fits());
        assertEquals(SeedCheck.Verdict.UNCONFIRMED, netherOnly.verdict());
    }

    @Test
    void bedrockNotesThatBreakTheRulesAreLeftOutAndTheRestStillCounts() {
        // y = -64 is always bedrock, so this note is a mistake whatever the seed.
        List<BedrockObservation> mistake = bedrock(List.of("not-bedrock 3 -64 3"));
        SeedCheck other = SeedCheck.of(DATA, List.of(), together(bedrockOf(999L), mistake), 12345L);
        assertFalse(other.bedrockProblems().isEmpty());
        assertEquals(SeedCheck.Verdict.CONTRADICTED, other.verdict());
        SeedCheck right = SeedCheck.of(DATA, List.of(), together(bedrockOf(12345L), mistake), 12345L);
        assertFalse(right.bedrockProblems().isEmpty());
        assertEquals(SeedCheck.Verdict.CONFIRMED, right.verdict());
    }

    @Test
    void aBlockNotedBothWaysIsLeftOut() {
        BedrockPattern pattern = new BedrockPattern(OVERWORLD, 12345L);
        int top = pattern.floorTop(5, 5);
        assertTrue(top > -64 && top < -59, "a random height");
        List<BedrockObservation> notes = together(bedrockOf(12345L), bedrock(List.of("not-bedrock 5 " + top + " 5")));
        SeedCheck check = SeedCheck.of(DATA, List.of(), notes, 12345L);
        assertEquals(0, check.overworld().mismatches());
        assertEquals(SeedCheck.Verdict.CONFIRMED, check.verdict());
        assertFalse(check.bedrockProblems().isEmpty());
    }

    @Test
    void aStructureNotedTwiceOrLinkedStructuresCountOnce() {
        // The same village written twice (blocks in the same chunk): still one village.
        SeedCheck twice = SeedCheck.of(DATA, structures(List.of("village 336 80", "village 340 90")), List.of(), 12345L);
        assertEquals(1, twice.fitting().size());
        assertEquals(SeedCheck.Verdict.UNCONFIRMED, twice.verdict());

        // Ocean monuments and end cities share their salt: in the same region the end city follows from the monument.
        long world = -4172144997902289642L;
        StructureSet monuments = DATA.structureSet("ocean_monuments").orElseThrow();
        StructureSet endCities = DATA.structureSet("end_cities").orElseThrow();
        StructureObservation monument = new StructureObservation(monuments, ((RandomSpreadPlacement) monuments.placement()).potentialChunk(world, 3, 2));
        StructureObservation endCity = new StructureObservation(endCities, ((RandomSpreadPlacement) endCities.placement()).potentialChunk(world, 3, 2));
        assertTrue(SeedCheck.linked(monument, endCity));
        SeedCheck linked = SeedCheck.of(DATA, List.of(monument, endCity), List.of(), world);
        assertEquals(2, linked.fitting().size());
        assertEquals(SeedCheck.Verdict.UNCONFIRMED, linked.verdict(), "counted as independent they would wrongly confirm it");
    }

    @Test
    void aWrongSeedFittingAFewOfManyNotesByChanceIsNotConfirmed() {
        // With 60 notes, a wrong seed often fits two of them by chance; it must not count as confirmed. Measured: random
        // typed seeds against 60 notes of one world are confirmed at most about once in 1,000 times.
        List<StructureObservation> notes = manyNotes(PICKED);
        assertEquals(SeedCheck.Verdict.CONFIRMED, SeedCheck.of(DATA, notes, List.of(), PICKED).verdict());
        Random random = new Random(20261013L);
        int trials = 5_000;
        int confirmed = 0;
        int someFit = 0;
        for (int i = 0; i < trials; i++) {
            SeedCheck check = SeedCheck.of(DATA, notes, List.of(), random.nextInt());
            confirmed += check.verdict() == SeedCheck.Verdict.CONFIRMED ? 1 : 0;
            someFit += check.fitting().size() >= 2 ? 1 : 0;
        }
        assertTrue(someFit > 10, "two or more notes fit by chance in " + someFit + " of " + trials);
        assertTrue(confirmed <= trials / 1000, confirmed + " of " + trials + " wrong seeds confirmed");

        // The case the review found: the hashed seed of the typed seed 91, with the notes and bedrock of another world.
        SeedCheck typed91 = SeedCheck.of(DATA, notes, bedrockOf(PICKED), 91L);
        assertEquals(SeedCheck.Verdict.CONTRADICTED, typed91.verdict());
    }

    @Test
    void theFitChanceOfIndependentNotesIsTheChanceOfFittingAtLeastAsMany() {
        List<StructureObservation> notes = structures(STRUCTURES_12345);
        double all = notes.stream().mapToDouble(note -> SeedCheck.startChance(note.set())).reduce(1, (a, b) -> a * b);
        assertEquals(all, SeedCheck.structuresFitChance(notes, new java.util.HashSet<>(notes)), all * 1e-9);
        // None fit: no evidence.
        assertEquals(1, SeedCheck.structuresFitChance(notes, java.util.Set.of()));
        // One of six: the chance that any one fits, at most the sum.
        double sum = notes.stream().mapToDouble(note -> SeedCheck.startChance(note.set())).sum();
        assertEquals(sum, SeedCheck.structuresFitChance(notes, java.util.Set.of(notes.get(0))), sum * 1e-9);
    }

    @Test
    void theStartChanceIsTheLargestChanceOfAnyChunkInItsRegion() {
        int checked = 0;
        for (StructureSet set : DATA.structureSets()) {
            if (!(set.placement() instanceof RandomSpreadPlacement spread)) {
                assertEquals(1, SeedCheck.startChance(set));
                continue;
            }
            // Every pair of rolls the spread can make, counted per offset (each roll is one of `limit` values).
            int limit = spread.spacing() - spread.separation();
            long[] ways = new long[limit];
            long all = spread.spreadType() == SpreadType.TRIANGULAR ? (long) limit * limit : limit;
            if (spread.spreadType() == SpreadType.TRIANGULAR) {
                for (int a = 0; a < limit; a++) {
                    for (int b = 0; b < limit; b++) {
                        ways[(a + b) / 2]++;
                    }
                }
            } else {
                Arrays.fill(ways, 1);
            }
            double perAxis = (double) Arrays.stream(ways).max().orElseThrow() / all;
            assertEquals(perAxis * perAxis, SeedCheck.startChance(set), 1e-15, set.id());
            checked++;
        }
        assertTrue(checked > 10, "random-spread sets checked: " + checked);
    }
}
