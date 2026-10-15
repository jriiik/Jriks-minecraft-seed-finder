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

    @Test
    void aStructureThatDoesntFitTheSeedIsFoundAndLeftOut() {
        // A village one chunk off the spot seed 12345 uses in its region: like a village from an older game version,
        // placed by a command, or copied.
        var villages = DATA.structureSet("villages").orElseThrow();
        var placement = (io.github.jriiik.seedfinder.structure.RandomSpreadPlacement) villages.placement();
        var spot = placement.potentialChunk(12345L, 3, 3);
        String misfit = "village " + (spot.minBlockX() + 16) + " " + spot.minBlockZ();
        List<String> structures = new java.util.ArrayList<>(STRUCTURES_12345);
        structures.add(2, misfit);
        List<StructureObservation> observations = ObservationReader.read(DATA, structures).structures();
        WorldSeedSearch.Result plain = WorldSeedSearch.run(DATA, observations, List.of(), OptionalLong.of(Seeds.hashedSeed(12345L)), 2, (done, total) -> { });
        assertEquals(WorldSeedSearch.Stage.NO_STRUCTURE_SEED, plain.stage());

        WorldSeedSearch.Result fixed = WorldSeedSearch.withoutMisfit(DATA, observations, List.of(), OptionalLong.of(Seeds.hashedSeed(12345L)), 2).orElseThrow();
        assertEquals(OptionalLong.of(12345L), fixed.worldSeed());
        assertEquals(Optional.of(observations.get(2)), fixed.leftOut());

        // Without a proof (no hashed seed, no bedrock) nothing is claimed.
        assertTrue(WorldSeedSearch.withoutMisfit(DATA, observations, List.of(), OptionalLong.empty(), 2).isEmpty());
    }

    @Test
    void biomesGiveTheWorldSeedWithoutHashedSeedOrBedrock() {
        List<StructureObservation> structures = ObservationReader.read(DATA, STRUCTURES_12345).structures();
        WorldSeedSearch.StructureStep step = WorldSeedSearch.structureSeeds(DATA, structures, 2, (done, total) -> { });
        List<BiomeObservation> biomes = BiomeEvidenceTest.area(12345L, io.github.jriiik.seedfinder.biome.OverworldBiomes.Preset.NORMAL, 0, 0, 6, 64);
        WorldSeedSearch.BiomeInput input = new WorldSeedSearch.BiomeInput(new BiomeEvidence(MinecraftVersion.V26_3, biomes, OptionalLong.empty()),
            new BiomeEvidence.ScanCache(16), () -> false);
        WorldSeedSearch.Result result = WorldSeedSearch.fullSeed(step, List.of(), input, OptionalLong.empty(), 4);
        assertEquals(OptionalLong.of(12345L), result.worldSeed());
        assertEquals(Optional.of(WorldSeedSearch.Proof.BIOMES), result.proof());

        // found from the hashed seed, the biomes of another world are flagged
        List<BiomeObservation> otherWorld = BiomeEvidenceTest.area(999L, io.github.jriiik.seedfinder.biome.OverworldBiomes.Preset.NORMAL, 0, 0, 3, 64);
        WorldSeedSearch.Result flagged = WorldSeedSearch.fullSeed(step, List.of(),
            new WorldSeedSearch.BiomeInput(new BiomeEvidence(MinecraftVersion.V26_3, otherWorld, OptionalLong.empty()), null, () -> false),
            OptionalLong.of(Seeds.hashedSeed(12345L)), 4);
        assertEquals(Optional.of(WorldSeedSearch.Proof.HASHED_SEED), flagged.proof());
        assertTrue(flagged.biomesDisagree());
    }

    /** One nether chunk's floor and roof, as BedrockText writes them (what the mod's notes hold). */
    private static List<BedrockObservation> netherChunkOf(long worldSeed, int chunkX, int chunkZ) {
        io.github.jriiik.seedfinder.bedrock.BedrockDimension nether = DATA.bedrockDimension("nether").orElseThrow();
        BedrockPattern pattern = new BedrockPattern(nether, worldSeed);
        List<String> lines = new java.util.ArrayList<>();
        for (int y : new int[] {1, 2, 3, 4, 123, 124, 125, 126}) {
            lines.addAll(BedrockText.layer(pattern, chunkX * 16, y, chunkZ * 16, 16, 16));
        }
        return ObservationReader.read(DATA, lines).bedrock();
    }

    @Test
    void netherBedrockAloneGivesTheStructureSeedAndTheHashedSeedTheWorldSeed() {
        List<BedrockObservation> nether = netherChunkOf(12345L, 7, -3);
        WorldSeedSearch.StructureStep step = WorldSeedSearch.structureSeeds(DATA, List.of(), nether, 2, () -> false, (done, total) -> { },
            (search, pieces) -> { });
        assertTrue(step.searched());
        assertTrue(step.nether().orElseThrow().proven());
        assertTrue(step.structureSeeds().contains(12345L));
        WorldSeedSearch.Result result = WorldSeedSearch.fullSeed(step, nether, OptionalLong.of(Seeds.hashedSeed(12345L)), 2);
        assertEquals(OptionalLong.of(12345L), result.worldSeed());
        assertEquals(Optional.of(WorldSeedSearch.Proof.HASHED_SEED), result.proof());

        // without the hashed seed, the biomes of one area prove it (12345 can be typed)
        List<BiomeObservation> biomes = BiomeEvidenceTest.area(12345L, io.github.jriiik.seedfinder.biome.OverworldBiomes.Preset.NORMAL, 0, 0, 6, 64);
        WorldSeedSearch.Result fromBiomes = WorldSeedSearch.fullSeed(step, nether, new WorldSeedSearch.BiomeInput(
            new BiomeEvidence(MinecraftVersion.V26_3, biomes, OptionalLong.empty()), null, () -> false), OptionalLong.empty(), 4);
        assertEquals(OptionalLong.of(12345L), fromBiomes.worldSeed());
        assertEquals(Optional.of(WorldSeedSearch.Proof.BIOMES), fromBiomes.proof());
    }

    @Test
    void netherBedrockAndStructuresAreCombined() {
        List<StructureObservation> structures = ObservationReader.read(DATA, STRUCTURES_12345).structures();
        WorldSeedSearch.StructureStep agreeing = WorldSeedSearch.structureSeeds(DATA, structures, netherChunkOf(12345L, 0, 0), 2, () -> false,
            (done, total) -> { }, (search, pieces) -> { });
        assertEquals(List.of(12345L), agreeing.structureSeeds());
        assertFalse(agreeing.netherDisagrees());
        // nether bedrock of another world: the structures' seed is kept, and the disagreement shown
        WorldSeedSearch.StructureStep disagreeing = WorldSeedSearch.structureSeeds(DATA, structures, netherChunkOf(999L, 0, 0), 2, () -> false,
            (done, total) -> { }, (search, pieces) -> { });
        assertEquals(List.of(12345L), disagreeing.structureSeeds());
        assertTrue(disagreeing.netherDisagrees());
        // too few structures to search: the nether alone
        List<StructureObservation> one = ObservationReader.read(DATA, List.of("igloo 48 208")).structures();
        WorldSeedSearch.StructureStep netherOnly = WorldSeedSearch.structureSeeds(DATA, one, netherChunkOf(12345L, 0, 0), 2, () -> false,
            (done, total) -> { }, (search, pieces) -> { });
        assertFalse(netherOnly.plan().canSearch());
        assertTrue(netherOnly.searched() && netherOnly.structureSeeds().contains(12345L));
    }
}
