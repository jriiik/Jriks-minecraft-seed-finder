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
        // nether bedrock of another world: the seeds of both are kept, the disagreement shown, and the hashed seed
        // tells which side is right
        List<BedrockObservation> otherNether = netherChunkOf(999L, 0, 0);
        WorldSeedSearch.StructureStep disagreeing = WorldSeedSearch.structureSeeds(DATA, structures, otherNether, 2, () -> false,
            (done, total) -> { }, (search, pieces) -> { });
        assertTrue(disagreeing.structureSeeds().contains(12345L) && disagreeing.structureSeeds().contains(999L));
        assertTrue(disagreeing.netherDisagrees());
        WorldSeedSearch.Result decided = WorldSeedSearch.fullSeed(disagreeing, otherNether, OptionalLong.of(Seeds.hashedSeed(12345L)), 2);
        assertEquals(OptionalLong.of(12345L), decided.worldSeed());
        assertTrue(decided.netherDisagrees());
        assertTrue(decided.misfits().isEmpty());
        WorldSeedSearch.Result undecided = WorldSeedSearch.fullSeed(disagreeing, otherNether, OptionalLong.empty(), 2);
        assertEquals(WorldSeedSearch.Stage.STRUCTURE_SEED, undecided.stage());
        assertEquals(List.of(12345L), undecided.structuresOnlySeeds());
        assertTrue(undecided.likelySeeds().isEmpty(), "no likely seeds while it isn't clear which structure seed is right");
        // too few structures to search: the nether alone
        List<StructureObservation> one = ObservationReader.read(DATA, List.of("igloo 48 208")).structures();
        WorldSeedSearch.StructureStep netherOnly = WorldSeedSearch.structureSeeds(DATA, one, netherChunkOf(12345L, 0, 0), 2, () -> false,
            (done, total) -> { }, (search, pieces) -> { });
        assertFalse(netherOnly.plan().canSearch());
        assertTrue(netherOnly.searched() && netherOnly.structureSeeds().contains(12345L));
    }

    @Test
    void aStructureNotedInTheWrongPlaceGivesWayToProvenNetherBedrock() {
        // Six structures of 12345 with one village a chunk off: alone they fit no seed (or a wrong one); the nether
        // bedrock proves the structure seed, the hashed seed the world seed, and the village is named.
        var villages = DATA.structureSet("villages").orElseThrow();
        var placement = (io.github.jriiik.seedfinder.structure.RandomSpreadPlacement) villages.placement();
        var spot = placement.potentialChunk(12345L, 3, 3);
        List<String> notes = new java.util.ArrayList<>(STRUCTURES_12345);
        notes.add(2, "village " + (spot.minBlockX() + 16) + " " + spot.minBlockZ());
        List<StructureObservation> structures = ObservationReader.read(DATA, notes).structures();
        List<BedrockObservation> nether = netherChunkOf(12345L, 3, 5);
        WorldSeedSearch.StructureStep step = WorldSeedSearch.structureSeeds(DATA, structures, nether, 2, () -> false, (done, total) -> { },
            (search, pieces) -> { });
        WorldSeedSearch.Result result = WorldSeedSearch.fullSeed(step, nether, OptionalLong.of(Seeds.hashedSeed(12345L)), 2);
        assertEquals(OptionalLong.of(12345L), result.worldSeed());
        assertTrue(result.netherDisagrees());
        assertEquals(List.of(structures.get(2)), result.misfits());
    }

    @Test
    void aGenuineHashedSeedThatFitsNoneMeansTheStructureSeedIsWrong() {
        List<StructureObservation> structures = ObservationReader.read(DATA, STRUCTURES_12345).structures();
        WorldSeedSearch.StructureStep step = WorldSeedSearch.structureSeeds(DATA, structures, 2, (done, total) -> { });
        WorldSeedSearch.Result result = WorldSeedSearch.fullSeed(step, bedrockOf(12345L), WorldSeedSearch.BiomeInput.NONE,
            new WorldSeedSearch.HashedSeed(OptionalLong.of(42L), true), 2);
        assertEquals(WorldSeedSearch.Stage.NO_STRUCTURE_SEED, result.stage());
        assertTrue(result.hashedSeedFitsNone());
        assertTrue(result.worldSeed().isEmpty());
    }

    @Test
    void biomesDontProveASeedTheBedrockRulesOut() {
        // Bedrock of 12345 (too little to be certain on its own) and the biomes of another world with the same
        // structure seed: notes from two worlds. Neither seed is presented as found.
        List<StructureObservation> structures = ObservationReader.read(DATA, STRUCTURES_12345).structures();
        WorldSeedSearch.StructureStep step = WorldSeedSearch.structureSeeds(DATA, structures, 2, (done, total) -> { });
        List<String> lines = BedrockText.heights(new BedrockPattern(DATA.bedrockDimension("overworld").orElseThrow(), 12345L), 0, 0, 6, 6);
        List<BedrockObservation> bedrock = ObservationReader.read(DATA, lines).bedrock();
        long twin = 5794443870565576761L;
        List<BiomeObservation> biomes = BiomeEvidenceTest.area(twin, io.github.jriiik.seedfinder.biome.OverworldBiomes.Preset.NORMAL, 0, 0, 6, 64);
        WorldSeedSearch.BiomeInput input = new WorldSeedSearch.BiomeInput(new BiomeEvidence(MinecraftVersion.V26_3, biomes, OptionalLong.empty()),
            null, () -> false);
        assertEquals(OptionalLong.of(twin), WorldSeedSearch.fullSeed(step, List.of(), input, OptionalLong.empty(), 4).worldSeed(),
            "the biomes alone prove their world");
        WorldSeedSearch.Result result = WorldSeedSearch.fullSeed(step, bedrock, input, OptionalLong.empty(), 4);
        assertEquals(WorldSeedSearch.Stage.STRUCTURE_SEED, result.stage());
        assertTrue(result.bedrockDisagrees());
        assertTrue(result.worldSeed().isEmpty());
        assertTrue(result.likelySeeds().isEmpty(), "nor offered as likely: " + result.likelySeeds());
    }

    @Test
    void likelySeedsTheBiomesRuleOutAreNotNamed() {
        // A seed typed as a long number: one area of its biomes singles it out but can't prove it; the seeds
        // Minecraft picks itself or text gives don't fit those biomes, so they are not offered.
        long typed = (0x5A5AL << 48) | 12345L;
        List<StructureObservation> structures = ObservationReader.read(DATA, STRUCTURES_12345).structures();
        WorldSeedSearch.StructureStep step = WorldSeedSearch.structureSeeds(DATA, structures, 2, (done, total) -> { });
        List<BiomeObservation> biomes = BiomeEvidenceTest.area(typed, io.github.jriiik.seedfinder.biome.OverworldBiomes.Preset.NORMAL, 0, 0, 6, 64);
        WorldSeedSearch.Result result = WorldSeedSearch.fullSeed(step, List.of(), new WorldSeedSearch.BiomeInput(
            new BiomeEvidence(MinecraftVersion.V26_3, biomes, OptionalLong.empty()), null, () -> false), OptionalLong.empty(), 4);
        assertEquals(WorldSeedSearch.Stage.STRUCTURE_SEED, result.stage());
        assertTrue(result.biomes().orElseThrow().fitting().contains(typed));
        assertTrue(result.likelySeeds().isEmpty());
    }

    /**
     * A nether chunk as Minecraft 1.17 and older made it: bedrock at y 0 to 4 and 127 down to 123 by a random draw
     * from a generator seeded with the chunk position, not with the world seed.
     */
    private static List<BedrockObservation> oldNetherChunk(int chunkX, int chunkZ) {
        java.util.Random random = new java.util.Random(chunkX * 341873128712L + chunkZ * 132897987541L);
        boolean[][][] bedrock = new boolean[16][128][16];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 127; y >= 0; y--) {
                    bedrock[x][y][z] = y >= 127 - random.nextInt(5) || y <= random.nextInt(5);
                }
            }
        }
        List<String> lines = new java.util.ArrayList<>();
        for (int y : new int[] {1, 2, 3, 4, 123, 124, 125, 126}) {
            lines.add("bedrock-layer nether " + chunkX * 16 + " " + y + " " + chunkZ * 16);
            for (int z = 0; z < 16; z++) {
                StringBuilder row = new StringBuilder();
                for (int x = 0; x < 16; x++) {
                    row.append(bedrock[x][y][z] ? 'B' : '.');
                }
                lines.add(row.toString());
            }
        }
        return ObservationReader.read(DATA, lines).bedrock();
    }

    @Test
    void netherChunksMadeByOldVersionsAreLeftOutOfTheBedrockCheck() {
        List<BedrockObservation> notes = new java.util.ArrayList<>(netherChunkOf(12345L, 2, 2));
        notes.addAll(oldNetherChunk(0, 0));
        notes.addAll(bedrockOf(12345L));
        WorldSeedSearch.StructureStep step = WorldSeedSearch.structureSeeds(DATA, List.of(), notes, 2, () -> false, (done, total) -> { },
            (search, pieces) -> { });
        assertEquals(List.of(new io.github.jriiik.seedfinder.world.ChunkPos(0, 0)), step.nether().orElseThrow().unusableChunks());
        WorldSeedSearch.Result result = WorldSeedSearch.fullSeed(step, notes, OptionalLong.empty(), 2);
        assertEquals(OptionalLong.of(12345L), result.worldSeed());
        assertEquals(Optional.of(WorldSeedSearch.Proof.BEDROCK), result.proof());
    }

    @Test
    void netherNotesTooFewToSearchStillRuleOutSeeds() {
        // A few nether floor blocks of another world (where its bedrock differs from 12345's): too few to search, but
        // they still count against 12345, so the overworld bedrock alone doesn't make it certain.
        io.github.jriiik.seedfinder.bedrock.BedrockDimension netherRules = DATA.bedrockDimension("nether").orElseThrow();
        BedrockPattern right = new BedrockPattern(netherRules, 12345L);
        BedrockPattern other = new BedrockPattern(netherRules, 999L);
        List<BedrockObservation> notes = new java.util.ArrayList<>();
        for (int i = 0; i < 256 && notes.size() < 12; i++) {
            int x = i & 15;
            int z = i >> 4;
            if (right.isBedrock(x, 4, z) != other.isBedrock(x, 4, z)) {
                notes.add(new BedrockObservation(netherRules, x, 4, z, other.isBedrock(x, 4, z)));
            }
        }
        notes.addAll(bedrockOf(12345L));
        List<StructureObservation> structures = ObservationReader.read(DATA, STRUCTURES_12345).structures();
        WorldSeedSearch.StructureStep step = WorldSeedSearch.structureSeeds(DATA, structures, notes, 2, () -> false, (done, total) -> { },
            (search, pieces) -> { });
        assertTrue(step.nether().orElseThrow().structureSeeds().isEmpty());
        assertEquals(notes.size(), WorldSeedSearch.bedrockForFullSeed(notes, step).size());
        WorldSeedSearch.Result result = WorldSeedSearch.fullSeed(step, notes, OptionalLong.empty(), 2);
        assertEquals(WorldSeedSearch.Stage.STRUCTURE_SEED, result.stage());
        assertTrue(result.worldSeed().isEmpty());
    }

    @Test
    void likelySeedsAreKeptWhenNoSeedFitsTheBiomes() {
        // Biomes of another world: no seed of 12345 fits them, so they say nothing about how its seed was picked.
        List<StructureObservation> structures = ObservationReader.read(DATA, STRUCTURES_12345).structures();
        WorldSeedSearch.StructureStep step = WorldSeedSearch.structureSeeds(DATA, structures, 2, (done, total) -> { });
        List<BiomeObservation> biomes = new java.util.ArrayList<>(
            BiomeEvidenceTest.area(987_654_321_987L, io.github.jriiik.seedfinder.biome.OverworldBiomes.Preset.NORMAL, 100, -300, 4, 64));
        biomes.addAll(BiomeEvidenceTest.area(987_654_321_987L, io.github.jriiik.seedfinder.biome.OverworldBiomes.Preset.NORMAL, 4500, -300, 4, 64));
        WorldSeedSearch.Result result = WorldSeedSearch.fullSeed(step, List.of(), new WorldSeedSearch.BiomeInput(
            new BiomeEvidence(MinecraftVersion.V26_3, biomes, OptionalLong.empty()), null, () -> false), OptionalLong.empty(), 4);
        assertTrue(result.biomes().orElseThrow().fitting().isEmpty());
        assertEquals(List.of(5794443870565576761L, 12345L), result.likelySeeds());
    }

    @Test
    void likelySeedsTheBedrockRulesOutAreNotNamed() {
        // A small bedrock patch of 12345: too little to be certain, but 5794443870565576761 doesn't fit it.
        List<StructureObservation> structures = ObservationReader.read(DATA, STRUCTURES_12345).structures();
        WorldSeedSearch.StructureStep step = WorldSeedSearch.structureSeeds(DATA, structures, 2, (done, total) -> { });
        List<String> lines = BedrockText.heights(new BedrockPattern(DATA.bedrockDimension("overworld").orElseThrow(), 12345L), 0, 0, 6, 6);
        WorldSeedSearch.Result result = WorldSeedSearch.fullSeed(step, ObservationReader.read(DATA, lines).bedrock(), OptionalLong.empty(), 2);
        assertEquals(WorldSeedSearch.Stage.STRUCTURE_SEED, result.stage());
        assertEquals(List.of(12345L), result.likelySeeds());
    }
}
