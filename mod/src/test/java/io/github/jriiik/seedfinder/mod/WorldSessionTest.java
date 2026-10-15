package io.github.jriiik.seedfinder.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jriiik.seedfinder.Seeds;
import io.github.jriiik.seedfinder.finder.ObservationReader;
import io.github.jriiik.seedfinder.finder.SeedCheck;
import io.github.jriiik.seedfinder.finder.StructureObservation;
import io.github.jriiik.seedfinder.finder.TypedSeedSearch;
import io.github.jriiik.seedfinder.version.MinecraftVersion;
import io.github.jriiik.seedfinder.version.VersionData;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The notes' bookkeeping for automatically recognized structures (no Minecraft needed). */
class WorldSessionTest {

    private static final String WORLD = WorldIdentity.key("server:example.net", 42L);
    private static final int VILLAGES = 34;

    private static WorldSession.NotedStructure recognized(int chunkX, int chunkZ) {
        return new WorldSession.NotedStructure("villages", chunkX * 16, chunkZ * 16, StructureRecognition.SOURCE);
    }

    @Test
    void aRemovedStructureIsNotNotedAgainEvenAfterLoading(@TempDir Path folder) throws IOException {
        WorldSession session = new WorldSession(WORLD);
        assertEquals(WorldSession.Offer.ADDED, session.addRecognized(recognized(3, 4), VILLAGES));
        assertEquals(WorldSession.Offer.KNOWN, session.addRecognized(recognized(3, 4), VILLAGES));
        session.removeStructure(session.structures().get(0));
        assertEquals(WorldSession.Offer.LEFT_OUT, session.addRecognized(recognized(3, 4), VILLAGES));
        session.save(folder);
        WorldSession loaded = WorldSession.load(folder, WORLD);
        assertEquals(WorldSession.Offer.LEFT_OUT, loaded.addRecognized(recognized(3, 4), VILLAGES));
        assertEquals(List.of(), loaded.structures());
        // "Remove all notes" keeps it left out: noting it again could be wrong, leaving it out only misses it.
        loaded.clearAll();
        assertEquals(WorldSession.Offer.LEFT_OUT, loaded.addRecognized(recognized(3, 4), VILLAGES));
    }

    @Test
    void ruinedPortalsInTheNetherAreOnlyNotedWhenThePlayerSwitchesThemOn(@TempDir Path folder) throws IOException {
        WorldSession session = new WorldSession(WORLD);
        assertFalse(session.netherPortals());
        session.save(folder);
        assertFalse(WorldSession.load(folder, WORLD).netherPortals());
        session.setNetherPortals(true);
        session.save(folder);
        assertTrue(WorldSession.load(folder, WORLD).netherPortals());
    }

    @Test
    void aStructurePlacedByCommandIsLeftOutAlsoWhenCollectingWasOff(@TempDir Path folder) throws IOException {
        WorldSession session = new WorldSession(WORLD);
        // Collecting is off: the spot is remembered for this session only, and nothing changes in the notes.
        int version = session.version();
        session.placed("villages", 3 * 16 + 5, 4 * 16 + 9);
        assertEquals(version, session.version());
        assertTrue(session.wasPlaced("villages", 3, 4));
        assertFalse(session.wasPlaced("villages", 4, 4));
        assertFalse(session.wasPlaced("pillager_outposts", 3, 4));
        session.save(folder);
        assertFalse(WorldSession.load(folder, WORLD).wasPlaced("villages", 3, 4), "not saved while collecting is off");

        // Switched on: left out for good, saved, and kept by "Remove all notes".
        session.setCollecting(true);
        assertEquals(WorldSession.Offer.LEFT_OUT, session.addRecognized(recognized(3, 4), VILLAGES));
        session.save(folder);
        WorldSession loaded = WorldSession.load(folder, WORLD);
        assertTrue(loaded.wasPlaced("villages", 3, 4));
        loaded.clearAll();
        assertEquals(WorldSession.Offer.LEFT_OUT, loaded.addRecognized(recognized(3, 4), VILLAGES));
        assertTrue(loaded.wasPlaced("villages", 3, 4));

        // Placed while collecting: an automatic note there is taken back.
        assertEquals(WorldSession.Offer.ADDED, loaded.addRecognized(recognized(40, 4), VILLAGES));
        loaded.placed("villages", 40 * 16, 4 * 16);
        assertEquals(List.of(), loaded.structures());
        assertTrue(loaded.wasPlaced("villages", 40, 4));
        // A start that wasn't trusted is not one that was placed.
        loaded.distrust("villages", 80 * 16, 4 * 16);
        assertFalse(loaded.wasPlaced("villages", 80, 4));
    }

    @Test
    void twoRecognizedStartsInOneRegionAreBothLeftOut() {
        WorldSession session = new WorldSession(WORLD);
        assertEquals(WorldSession.Offer.ADDED, session.addRecognized(recognized(3, 4), VILLAGES));
        // Chunks 3 and 20 are in the same region (0) of 34 x 34 chunks; 40 is in the next one.
        assertEquals(WorldSession.Offer.LEFT_OUT, session.addRecognized(recognized(20, 4), VILLAGES));
        assertEquals(List.of(), session.structures());
        assertEquals(WorldSession.Offer.LEFT_OUT, session.addRecognized(recognized(3, 4), VILLAGES));
        assertEquals(WorldSession.Offer.ADDED, session.addRecognized(recognized(40, 4), VILLAGES));
        // The same on the negative side: chunks -34 and -9 are both in region -1.
        assertEquals(WorldSession.Offer.ADDED, session.addRecognized(recognized(-34, -9), VILLAGES));
        assertEquals(WorldSession.Offer.LEFT_OUT, session.addRecognized(recognized(-9, -9), VILLAGES));
    }

    @Test
    void aNoteFromLocateOrThePlayerIsKeptAndTheDisagreementShown() {
        WorldSession session = new WorldSession(WORLD);
        session.addStructure(new WorldSession.NotedStructure("villages", 50, 70, "from /locate"));
        assertEquals(WorldSession.Offer.DISAGREES, session.addRecognized(recognized(20, 4), VILLAGES));
        assertEquals(1, session.structures().size());
        assertEquals("from /locate", session.structures().get(0).source());
        assertEquals(1, session.disagreements().size());
        assertTrue(session.disagreements().get(0).contains("/locate is always right"), session.disagreements().get(0));

        // Once the player removes the other note, the recognized start fits.
        int removals = session.removals();
        session.removeStructure(session.structures().get(0));
        assertTrue(session.removals() > removals);
        assertEquals(List.of(), session.disagreements());
        assertEquals(WorldSession.Offer.ADDED, session.addRecognized(recognized(20, 4), VILLAGES));
    }

    @Test
    void removingANoteThatIsAlreadyGoneDoesNothing() {
        WorldSession session = new WorldSession(WORLD);
        session.addRecognized(recognized(3, 4), VILLAGES);
        WorldSession.NotedStructure noted = session.structures().get(0);
        session.removeStructure(noted);
        int version = session.version();
        session.removeStructure(noted);
        assertEquals(version, session.version());
    }

    @Test
    void notesSavedByTheEarlierVersionUnderTheAddressAreFound(@TempDir Path folder) throws IOException {
        String identity = "server:example.net";
        // The earlier version's file: no hashed seed in the key, no removed_structures.
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(WorldSession.fileName(identity) + ".json"), """
            {"world": "server:example.net", "collecting": true, "show_status": true, "hashed_seed": "42",
             "structures": [{"set": "villages", "x": 48, "z": 64, "source": "from /locate"}], "bedrock": []}
            """, StandardCharsets.UTF_8);
        WorldSession same = WorldSession.load(folder, WorldIdentity.key(identity, 42L), identity, 42L);
        assertTrue(same.collecting());
        assertEquals(1, same.structures().size());
        assertEquals(OptionalLong.of(42L), same.hashedSeed());
        assertEquals("server:example.net", same.displayName());
        assertTrue(same.needsSaving(), "saved again under the new key");

        // Another world behind the same address (another hashed seed) starts with its own, empty notes.
        WorldSession other = WorldSession.load(folder, WorldIdentity.key(identity, 7L), identity, 7L);
        assertEquals(List.of(), other.structures());
        assertTrue(!other.collecting());
    }

    @Test
    void aDistrustedStartIsTakenBackAlsoFromAnEarlierSessionAndNotNotedAgain(@TempDir Path folder) throws IOException {
        WorldSession session = new WorldSession(WORLD);
        assertEquals(WorldSession.Offer.ADDED, session.addRecognized(recognized(3, 4), VILLAGES));
        session.addStructure(new WorldSession.NotedStructure("pillager_outposts", 48, 64, "from /locate"));
        session.save(folder);

        // The next day: the old note is seen again (KNOWN), then a contradicting match turns up in its region.
        WorldSession later = WorldSession.load(folder, WORLD);
        assertEquals(WorldSession.Offer.KNOWN, later.addRecognized(recognized(3, 4), VILLAGES));
        later.distrust("villages", 48, 64);
        later.distrust("villages", 20 * 16, 4 * 16);
        assertEquals(List.of("pillager_outposts"), later.structures().stream().map(WorldSession.NotedStructure::set).toList());
        later.save(folder);
        WorldSession third = WorldSession.load(folder, WORLD);
        assertEquals(WorldSession.Offer.LEFT_OUT, third.addRecognized(recognized(3, 4), VILLAGES));
        assertEquals(WorldSession.Offer.LEFT_OUT, third.addRecognized(recognized(20, 4), VILLAGES));
    }

    @Test
    void theTypedSeedSearchIsSavedAndContinuedLater(@TempDir Path folder) throws IOException {
        WorldSession session = new WorldSession(WORLD);
        session.setHashedSeed(Seeds.hashedSeed(12345L));
        int version = session.version();
        session.setTypedSeedSearch(new TypedSeedSearch.Outcome(OptionalLong.empty(), 37));
        assertEquals(version, session.version(), "progress alone doesn't count as new notes (no new search)");
        assertTrue(session.needsSaving());
        session.save(folder);
        WorldSession later = WorldSession.load(folder, WORLD);
        assertEquals(37, later.typedSeedPieces());
        assertFalse(later.typedSeedSearchDone());

        later.setTypedSeedSearch(new TypedSeedSearch.Outcome(OptionalLong.of(12345L), 37));
        assertTrue(later.typedSeedSearchDone());
        assertEquals(OptionalLong.empty(), later.worldSeed(), "not presented before the notes are checked");
        assertTrue(later.notesLines().contains("# The hashed seed is the one of the typed seed 12345 (not checked against these notes yet)."));
        later.save(folder);
        assertEquals(OptionalLong.of(12345L), WorldSession.load(folder, WORLD).typedSeed());

        // "Remove all notes" keeps it: it depends only on the hashed seed, which is part of these notes' key.
        later.clearAll();
        assertEquals(OptionalLong.of(12345L), later.typedSeed());
        assertEquals(37, later.typedSeedPieces());
    }

    @Test
    void theTypedSeedIsTheWorldSeedUnlessTheNotesContradictIt() {
        VersionData data = MinecraftVersion.V26_3.data();
        WorldSession session = new WorldSession(WORLD);
        session.setHashedSeed(Seeds.hashedSeed(12345L));
        session.setTypedSeedSearch(new TypedSeedSearch.Outcome(OptionalLong.of(12345L), 0));

        // Nothing noted yet: shown, but not confirmed.
        session.setTypedSeedCheck(session.version(), SeedCheck.of(data, List.of(), List.of(), 12345L));
        assertEquals(OptionalLong.of(12345L), session.worldSeed());
        assertTrue(session.notesLines().stream().anyMatch(line -> line.startsWith("# The world seed is 12345 (it was typed")));

        // A village where seed 12345 has none (its village of that region is one chunk west): not the world seed.
        List<StructureObservation> misfit = ObservationReader.read(data, List.of("village 352 80")).structures();
        session.setTypedSeedCheck(session.version(), SeedCheck.of(data, misfit, List.of(), 12345L));
        assertEquals(OptionalLong.empty(), session.worldSeed());
        assertTrue(session.notesLines().stream().anyMatch(line -> line.contains("these notes don't fit that seed")));

        // A check of another seed changes nothing.
        session.setTypedSeedCheck(session.version(), SeedCheck.of(data, List.of(), List.of(), 999L));
        assertEquals(OptionalLong.empty(), session.worldSeed());

        // "Remove all notes" drops the check (it was about the notes), not the typed seed or the hashed seed; a check of
        // the notes from before it is ignored.
        int before = session.version();
        session.setTypedSeedCheck(before, SeedCheck.of(data, List.of(), List.of(), 12345L));
        session.clearAll();
        assertEquals(java.util.Optional.empty(), session.typedSeedCheck());
        assertEquals(OptionalLong.of(12345L), session.typedSeed());
        assertEquals(OptionalLong.of(Seeds.hashedSeed(12345L)), session.hashedSeed());
        session.setTypedSeedCheck(before, SeedCheck.of(data, List.of(), List.of(), 12345L));
        assertEquals(java.util.Optional.empty(), session.typedSeedCheck());
        assertEquals(OptionalLong.empty(), session.worldSeed(), "shown again once the remaining notes are checked");
        session.setTypedSeedCheck(session.version(), SeedCheck.of(data, List.of(), List.of(), 12345L));
        assertEquals(OptionalLong.of(12345L), session.worldSeed());
    }

    @Test
    void removeAllNotesKeepsTheTypedSeedAcrossSaving(@TempDir Path folder) throws IOException {
        WorldSession session = new WorldSession(WORLD);
        session.setHashedSeed(Seeds.hashedSeed(12345L));
        session.setTypedSeedSearch(new TypedSeedSearch.Outcome(OptionalLong.of(12345L), 3));
        session.clearAll();
        session.save(folder);
        WorldSession loaded = WorldSession.load(folder, WORLD);
        assertEquals(OptionalLong.of(12345L), loaded.typedSeed());
        assertEquals(OptionalLong.of(Seeds.hashedSeed(12345L)), loaded.hashedSeed());
    }

    @Test
    void inASinglePlayerWorldTheTypedSeedIsTheWorldSeedEvenWhenNotesDisagree() {
        // The game itself makes the hashed seed there, so disagreeing notes are the ones that are wrong.
        VersionData data = MinecraftVersion.V26_3.data();
        WorldSession session = new WorldSession(WorldIdentity.key("singleplayer:New World", Seeds.hashedSeed(12345L)));
        assertTrue(session.hashedSeedIsGenuine());
        session.setHashedSeed(Seeds.hashedSeed(12345L));
        session.setTypedSeedSearch(new TypedSeedSearch.Outcome(OptionalLong.of(12345L), 0));
        assertEquals(OptionalLong.empty(), session.worldSeed(), "shown once checked");
        List<StructureObservation> misfit = ObservationReader.read(data, List.of("village 352 80")).structures();
        session.setTypedSeedCheck(session.version(), SeedCheck.of(data, misfit, List.of(), 12345L));
        assertEquals(OptionalLong.of(12345L), session.worldSeed());
        assertFalse(new WorldSession(WORLD).hashedSeedIsGenuine());
    }

    @Test
    void aDoubtfulSavedTypedSeedSearchStartsOverButKeepsTheNotes(@TempDir Path folder) throws IOException {
        WorldSession session = new WorldSession(WORLD);
        session.setHashedSeed(Seeds.hashedSeed(12345L));
        session.addStructure(new WorldSession.NotedStructure("villages", 336, 80, "marked by the player"));
        session.setTypedSeedSearch(new TypedSeedSearch.Outcome(OptionalLong.of(12345L), 5));
        session.save(folder);
        Path file = folder.resolve(WorldSession.fileName(WORLD) + ".json");
        String saved = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(saved.contains("\"typed_seed\": \"12345\"") && saved.contains("\"typed_seed_pieces\": 5"), saved);

        // A typed seed whose hash isn't the hashed seed (edited or damaged).
        Files.writeString(file, saved.replace("\"typed_seed\": \"12345\"", "\"typed_seed\": \"12346\""), StandardCharsets.UTF_8);
        WorldSession edited = WorldSession.load(folder, WORLD);
        assertEquals(OptionalLong.empty(), edited.typedSeed());
        assertEquals(0, edited.typedSeedPieces());
        assertEquals(1, edited.structures().size());

        // Progress that doesn't read.
        Files.writeString(file, saved.replace("\"typed_seed_pieces\": 5", "\"typed_seed_pieces\": \"lots\""), StandardCharsets.UTF_8);
        WorldSession damaged = WorldSession.load(folder, WORLD);
        assertEquals(0, damaged.typedSeedPieces());
        assertEquals(1, damaged.structures().size());

        // Progress saved for another layout of the pieces.
        Files.writeString(file, saved.replace("\"typed_seed_layout\": " + TypedSeedSearch.LAYOUT, "\"typed_seed_layout\": 0"), StandardCharsets.UTF_8);
        assertEquals(0, WorldSession.load(folder, WORLD).typedSeedPieces());
    }

    /** A chunk whose cells are all one biome at every height read, but plains in its north-west cell at y 64. */
    private static BiomeChunk biomeChunk(int chunkX, int chunkZ, String biome) {
        java.util.Map<Integer, List<String>> levels = new java.util.LinkedHashMap<>();
        for (int y : BiomeScanner.HEIGHTS) {
            List<String> cells = new java.util.ArrayList<>(java.util.Collections.nCopies(16, biome));
            if (y == 64) {
                cells.set(0, "minecraft:plains");
            }
            levels.put(y, cells);
        }
        return new BiomeChunk(chunkX, chunkZ, levels);
    }

    @Test
    void biomesAreNotedInAFewAreasFarApart(@TempDir Path folder) throws IOException {
        WorldSession session = new WorldSession(WORLD);
        assertTrue(session.offerBiomes(biomeChunk(0, 0, "minecraft:forest")));
        assertFalse(session.offerBiomes(biomeChunk(0, 0, "minecraft:forest")));
        assertTrue(session.offerBiomes(biomeChunk(5, 5, "minecraft:forest")));
        // too far for the first area, too near for a second one
        assertFalse(session.wantsBiomes(100, 0));
        // the notes of both areas must be the spacing apart, not only their first chunks
        assertFalse(session.wantsBiomes(WorldSession.BIOME_AREA_SPACING / 16 + 1, 0));
        int far = WorldSession.biomeAreaDistance(WorldSession.BIOME_AREA_SPACING) / 16 + 1;
        assertTrue(session.offerBiomes(biomeChunk(far, 0, "minecraft:desert")));
        assertEquals(2, session.biomeAreas());
        assertEquals(3, session.biomeChunks());
        // an area takes at most so many chunks
        for (int i = 0; i < 2 * WorldSession.MAX_BIOME_CHUNKS_PER_AREA; i++) {
            session.offerBiomes(biomeChunk(i % 20 - 10, i / 20 - 5, "minecraft:forest"));
        }
        assertTrue(session.biomeChunks() <= 1 + WorldSession.MAX_BIOME_CHUNKS_PER_AREA + 1);

        session.save(folder);
        WorldSession loaded = WorldSession.load(folder, WORLD);
        assertEquals(session.biomes(), loaded.biomes());
        assertEquals(2, loaded.biomeAreas());
        assertFalse(loaded.wantsBiomes(100, 0));

        // the notes file gives the same biomes to the command-line app
        VersionData data = MinecraftVersion.V26_3.data();
        List<io.github.jriiik.seedfinder.finder.BiomeObservation> read = ObservationReader.read(data, loaded.notesLines()).biomes();
        assertEquals(loaded.biomeChunks() * 16 * BiomeScanner.HEIGHTS.length, read.size());
        assertTrue(read.contains(new io.github.jriiik.seedfinder.finder.BiomeObservation("minecraft:desert", far * 16 + 4, 64, 0, true)));
        assertTrue(read.contains(new io.github.jriiik.seedfinder.finder.BiomeObservation("minecraft:plains", far * 16, 64, 0, true)));
    }

    @Test
    void aBiomeFromADataPackStopsBiomeNotes(@TempDir Path folder) throws IOException {
        WorldSession session = new WorldSession(WORLD);
        session.offerBiomes(biomeChunk(0, 0, "minecraft:forest"));
        session.foreignBiomeSeen("terralith:moonlight_grove");
        assertEquals(0, session.biomeChunks());
        assertFalse(session.wantsBiomes(1, 1));
        session.save(folder);
        assertFalse(WorldSession.load(folder, WORLD).wantsBiomes(1, 1));
        session.clearAll();
        assertTrue(session.wantsBiomes(1, 1));
    }

    /** A player walking east with collecting on: the chunks around them, nearest first, as the mod reads them. */
    private static void walk(WorldSession session, int toBlockX) {
        for (int x = 0; x <= toBlockX; x += 64) {
            int centerX = x >> 4;
            for (int radius = 0; radius <= 8; radius++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) == radius && session.wantsBiomes(centerX + dx, dz)) {
                            session.offerBiomes(biomeChunk(centerX + dx, dz, "minecraft:forest"));
                        }
                    }
                }
            }
        }
    }

    private static io.github.jriiik.seedfinder.finder.BiomeEvidence evidence(WorldSession session) {
        VersionData data = MinecraftVersion.V26_3.data();
        return new io.github.jriiik.seedfinder.finder.BiomeEvidence(MinecraftVersion.V26_3, ObservationReader.read(data, session.notesLines()).biomes(),
            OptionalLong.empty());
    }

    @Test
    void theAreasOfAWalkCountSeparately() {
        WorldSession normal = new WorldSession(WORLD);
        walk(normal, 6000);
        assertEquals(2, normal.biomeAreas());
        assertEquals(2, evidence(normal).groups(io.github.jriiik.seedfinder.biome.OverworldBiomes.Preset.NORMAL).size());

        // a large-biome world needs areas 16,384 blocks apart: one more area is noted for that
        WorldSession large = new WorldSession(WORLD);
        walk(large, 18_000);
        assertEquals(WorldSession.MAX_BIOME_AREAS + 1, large.biomeAreas());
        assertEquals(2, evidence(large).groups(io.github.jriiik.seedfinder.biome.OverworldBiomes.Preset.LARGE_BIOMES).size());
        assertEquals(WorldSession.MAX_BIOME_AREAS + 1, evidence(large).groups(io.github.jriiik.seedfinder.biome.OverworldBiomes.Preset.NORMAL).size());
    }

    private static ScannedChunk netherChunk(int chunkX, int chunkZ) {
        java.util.Map<Integer, List<String>> layers = new java.util.TreeMap<>();
        layers.put(4, java.util.Collections.nCopies(16, "B..............."));
        return new ScannedChunk("minecraft:the_nether", chunkX, chunkZ, layers);
    }

    @Test
    void netherChunksFoundUnusableMakeRoomForOthers(@TempDir Path folder) throws IOException {
        WorldSession session = new WorldSession(WORLD);
        for (int i = 0; i < WorldSession.MAX_BEDROCK_CHUNKS; i++) {
            assertTrue(session.offerBedrock(netherChunk(i, 0)));
        }
        assertFalse(session.wantsBedrock("minecraft:the_nether"));
        session.leaveOutBedrock("minecraft:the_nether", List.of(new io.github.jriiik.seedfinder.world.ChunkPos(0, 0),
            new io.github.jriiik.seedfinder.world.ChunkPos(1, 0)));
        assertEquals(2, session.bedrockChunks("minecraft:the_nether"));
        assertEquals(2, session.leftOutBedrockChunks("minecraft:the_nether"));
        assertTrue(session.wantsBedrock("minecraft:the_nether"));
        assertFalse(session.offerBedrock(netherChunk(0, 0)), "a chunk left out is not read again");
        assertTrue(session.offerBedrock(netherChunk(7, 7)));

        session.save(folder);
        WorldSession loaded = WorldSession.load(folder, WORLD);
        assertEquals(2, loaded.leftOutBedrockChunks("minecraft:the_nether"));
        assertFalse(loaded.offerBedrock(netherChunk(1, 0)));
        loaded.clearAll();
        assertEquals(0, loaded.leftOutBedrockChunks("minecraft:the_nether"));
    }

    @Test
    void manyChunksLeftOutKeepOnlyTheirSurroundingsFromBeingRead() {
        WorldSession session = new WorldSession(WORLD);
        // an old portal hub: every chunk around it is left out
        for (int round = 0; round < WorldSession.LEFT_OUT_BEFORE_SPACING / WorldSession.MAX_BEDROCK_CHUNKS; round++) {
            List<io.github.jriiik.seedfinder.world.ChunkPos> old = new java.util.ArrayList<>();
            for (int i = 0; i < WorldSession.MAX_BEDROCK_CHUNKS; i++) {
                int x = round * WorldSession.MAX_BEDROCK_CHUNKS + i;
                assertTrue(session.offerBedrock(netherChunk(x, 0)));
                old.add(new io.github.jriiik.seedfinder.world.ChunkPos(x, 0));
            }
            session.leaveOutBedrock("minecraft:the_nether", old);
        }
        assertTrue(session.leftOutBedrockChunks("minecraft:the_nether") >= WorldSession.LEFT_OUT_BEFORE_SPACING);
        assertFalse(session.wantsBedrock("minecraft:the_nether", 20, 5), "next to the old land");
        assertTrue(session.wantsBedrock("minecraft:the_nether", 60, 60), "new land farther away");
        assertTrue(session.offerBedrock(netherChunk(60, 60)));
    }
}
