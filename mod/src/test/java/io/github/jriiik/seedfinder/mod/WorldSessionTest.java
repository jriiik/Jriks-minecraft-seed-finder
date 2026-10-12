package io.github.jriiik.seedfinder.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        loaded.clearAll();
        assertEquals(WorldSession.Offer.ADDED, loaded.addRecognized(recognized(3, 4), VILLAGES));
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
}
