package io.github.jriiik.seedfinder.finder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jriiik.seedfinder.Seeds;
import io.github.jriiik.seedfinder.bedrock.BedrockObservation;
import io.github.jriiik.seedfinder.bedrock.BedrockPattern;
import io.github.jriiik.seedfinder.bedrock.BedrockText;
import io.github.jriiik.seedfinder.version.MinecraftVersion;
import io.github.jriiik.seedfinder.version.VersionData;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class WorldSeedSearchTest {

    private static final VersionData DATA = MinecraftVersion.V26_3.data();
    /** Structure spots of seed 12345 (the README example). */
    private static final List<String> STRUCTURES_12345 = List.of(
        "desert_pyramid 272 80", "swamp_hut 256 -144", "village 336 80", "shipwreck -128 32", "ruined_portal 16 0", "trial_chamber 0 224");

    private static WorldSeedSearch.Result run(List<String> structures, List<BedrockObservation> bedrock, OptionalLong hashedSeed) {
        List<StructureObservation> observations = ObservationReader.read(DATA, structures).structures();
        return WorldSeedSearch.run(DATA, observations, bedrock, hashedSeed, 2, (done, total) -> { });
    }

    private static List<BedrockObservation> bedrockOf(long worldSeed) {
        List<String> lines = BedrockText.heights(new BedrockPattern(DATA.bedrockDimension("overworld").orElseThrow(), worldSeed), 0, 0, 16, 16);
        return ObservationReader.read(DATA, lines).bedrock();
    }

    @Test
    void theHashedSeedGivesTheWorldSeed() {
        WorldSeedSearch.Result result = run(STRUCTURES_12345, List.of(), OptionalLong.of(Seeds.hashedSeed(12345L)));
        assertEquals(WorldSeedSearch.Stage.WORLD_SEED, result.stage());
        assertEquals(OptionalLong.of(12345L), result.worldSeed());
        assertEquals(Optional.of(WorldSeedSearch.Proof.HASHED_SEED), result.proof());
        assertFalse(result.bedrockDisagrees());
    }

    @Test
    void bedrockGivesTheWorldSeedWhenTheHashedSeedDoesNotFit() {
        WorldSeedSearch.Result result = run(STRUCTURES_12345, bedrockOf(12345L), OptionalLong.of(42L));
        assertTrue(result.hashedSeedFitsNone());
        assertEquals(OptionalLong.of(12345L), result.worldSeed());
        assertEquals(Optional.of(WorldSeedSearch.Proof.BEDROCK), result.proof());
    }

    @Test
    void withoutProofTheLikelySeedsAreNamed() {
        WorldSeedSearch.Result result = run(STRUCTURES_12345, List.of(), OptionalLong.empty());
        assertEquals(WorldSeedSearch.Stage.STRUCTURE_SEED, result.stage());
        assertEquals(List.of(12345L), result.structureSeeds());
        assertEquals(List.of(5794443870565576761L, 12345L), result.likelySeeds());
    }

    @Test
    void bedrockFromAnotherWorldIsFlagged() {
        WorldSeedSearch.Result result = run(STRUCTURES_12345, bedrockOf(999L), OptionalLong.of(Seeds.hashedSeed(12345L)));
        assertEquals(OptionalLong.of(12345L), result.worldSeed());
        assertTrue(result.bedrockDisagrees());
    }

    @Test
    void tooFewStructuresSayWhatIsMissing() {
        WorldSeedSearch.Result result = run(List.of("igloo 48 208"), List.of(), OptionalLong.empty());
        assertEquals(WorldSeedSearch.Stage.NEED_MORE_STRUCTURES, result.stage());
        assertFalse(result.plan().problems().isEmpty());
    }

    @Test
    void theNotesFormatCarriesTheHashedSeed() {
        assertEquals(OptionalLong.of(-6467378160175308932L),
            ObservationReader.read(DATA, List.of("# from the mod", "hashed-seed -6467378160175308932")).hashedSeed());
        assertEquals(OptionalLong.empty(), ObservationReader.read(DATA, List.of("igloo 48 208")).hashedSeed());
    }
}
