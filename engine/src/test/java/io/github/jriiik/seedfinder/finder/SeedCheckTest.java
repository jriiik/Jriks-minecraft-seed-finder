package io.github.jriiik.seedfinder.finder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jriiik.seedfinder.bedrock.BedrockObservation;
import io.github.jriiik.seedfinder.bedrock.BedrockPattern;
import io.github.jriiik.seedfinder.bedrock.BedrockText;
import io.github.jriiik.seedfinder.structure.RandomSpreadPlacement;
import io.github.jriiik.seedfinder.structure.SpreadType;
import io.github.jriiik.seedfinder.structure.StructureSet;
import io.github.jriiik.seedfinder.version.MinecraftVersion;
import io.github.jriiik.seedfinder.version.VersionData;
import io.github.jriiik.seedfinder.world.ChunkPos;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SeedCheckTest {

    private static final VersionData DATA = MinecraftVersion.V26_3.data();
    /** Structure spots of seed 12345 (the README example). */
    private static final List<String> STRUCTURES_12345 = List.of(
        "desert_pyramid 272 80", "swamp_hut 256 -144", "village 336 80", "shipwreck -128 32", "ruined_portal 16 0", "trial_chamber 0 224");

    private static List<StructureObservation> structures(List<String> lines) {
        return ObservationReader.read(DATA, lines).structures();
    }

    private static List<BedrockObservation> bedrockOf(long worldSeed) {
        List<String> lines = BedrockText.heights(new BedrockPattern(DATA.bedrockDimension("overworld").orElseThrow(), worldSeed), 0, 0, 16, 16);
        return ObservationReader.read(DATA, lines).bedrock();
    }

    /** A village one chunk off the spot seed 12345 uses in a region: like one from an older version, a command or a copy. */
    private static StructureObservation misfitVillage() {
        StructureSet villages = DATA.structureSet("villages").orElseThrow();
        ChunkPos spot = ((RandomSpreadPlacement) villages.placement()).potentialChunk(12345L, 3, 3);
        return new StructureObservation(villages, new ChunkPos(spot.x() + 1, spot.z()));
    }

    @Test
    void theNotesOfTheWorldConfirmItsSeed() {
        SeedCheck check = SeedCheck.of(DATA, structures(STRUCTURES_12345), bedrockOf(12345L), 12345L);
        assertEquals(SeedCheck.Verdict.CONFIRMED, check.verdict());
        assertEquals(6, check.fitting().size());
        assertEquals(List.of(), check.misfits());
        assertTrue(check.bedrockBlocks() > 0);
        assertEquals(0, check.bedrockMismatches());
        assertTrue(check.falseFitChance() < 1e-12, "chance " + check.falseFitChance());
    }

    @Test
    void bedrockAloneOrTwoStructuresConfirmIt() {
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
    }

    @Test
    void theNotesOfAnotherWorldContradictIt() {
        SeedCheck check = SeedCheck.of(DATA, structures(STRUCTURES_12345), bedrockOf(12345L), 999L);
        assertEquals(SeedCheck.Verdict.CONTRADICTED, check.verdict());
        assertTrue(check.misfits().size() >= 5, check.misfits().toString());
        assertTrue(check.bedrockMismatches() > 0);

        assertEquals(SeedCheck.Verdict.CONTRADICTED, SeedCheck.of(DATA, List.of(), bedrockOf(12345L), 999L).verdict());
        assertEquals(SeedCheck.Verdict.CONTRADICTED, SeedCheck.of(DATA, List.of(misfitVillage()), List.of(), 12345L).verdict());
    }

    @Test
    void bedrockOutweighsAStructureThatDoesntFit() {
        // The server may place structures differently, or the village comes from older chunks: the bedrock decides.
        SeedCheck check = SeedCheck.of(DATA, List.of(misfitVillage()), bedrockOf(12345L), 12345L);
        assertEquals(SeedCheck.Verdict.CONFIRMED, check.verdict());
        assertEquals(List.of(misfitVillage()), check.misfits());
    }

    @Test
    void structuresOutweighBedrockThatDoesntFit() {
        // Six structures in the right chunks can't be chance; the server may change blocks it sends.
        SeedCheck check = SeedCheck.of(DATA, structures(STRUCTURES_12345), bedrockOf(999L), 12345L);
        assertEquals(SeedCheck.Verdict.CONFIRMED, check.verdict());
        assertTrue(check.bedrockMismatches() > 0);
    }

    @Test
    void bedrockThatBreaksTheGamesRulesIsNotUsed() {
        // y = -64 is always bedrock, so this note is a mistake whatever the seed.
        List<BedrockObservation> bedrock = new ArrayList<>(bedrockOf(999L));
        bedrock.addAll(ObservationReader.read(DATA, List.of("not-bedrock 3 -64 3")).bedrock());
        SeedCheck check = SeedCheck.of(DATA, structures(List.of("village 336 80")), bedrock, 12345L);
        assertEquals(0, check.bedrockBlocks());
        assertEquals(0, check.bedrockMismatches());
        assertEquals(SeedCheck.Verdict.UNCONFIRMED, check.verdict());
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
                java.util.Arrays.fill(ways, 1);
            }
            double perAxis = (double) java.util.Arrays.stream(ways).max().orElseThrow() / all;
            assertEquals(perAxis * perAxis, SeedCheck.startChance(set), 1e-15, set.id());
            checked++;
        }
        assertTrue(checked > 10, "random-spread sets checked: " + checked);
    }
}
