package io.github.jriiik.seedfinder.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jriiik.seedfinder.Seeds;
import io.github.jriiik.seedfinder.bedrock.BedrockPattern;
import io.github.jriiik.seedfinder.bedrock.BedrockText;
import io.github.jriiik.seedfinder.finder.ObservationReader;
import io.github.jriiik.seedfinder.finder.SeedCheck;
import io.github.jriiik.seedfinder.finder.StructureObservation;
import io.github.jriiik.seedfinder.finder.TypedSeedSearch;
import io.github.jriiik.seedfinder.finder.WorldSeedSearch;
import io.github.jriiik.seedfinder.structure.StructureSet;
import io.github.jriiik.seedfinder.version.MinecraftVersion;
import io.github.jriiik.seedfinder.version.VersionData;
import io.github.jriiik.seedfinder.world.ChunkPos;
import java.util.Collections;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** What the screen and the status line say about a typed seed (no Minecraft needed). */
class StatusTextTest {

    private static final VersionData DATA = MinecraftVersion.V26_3.data();
    private static final StructureSet VILLAGES = DATA.structureSet("villages").orElseThrow();
    private static final String SERVER = WorldIdentity.key("server:example.net", Seeds.hashedSeed(12345L));

    private static List<StructureObservation> villages(int count) {
        return Collections.nCopies(count, new StructureObservation(VILLAGES, new ChunkPos(21, 5)));
    }

    private static SeedCheck check(int fitting, int misfits, SeedCheck.Bedrock overworld, SeedCheck.Bedrock nether) {
        return new SeedCheck(12345L, SeedCheck.Verdict.CONFIRMED, villages(fitting), villages(misfits), overworld, nether, List.of(), 0, 1, SeedCheck.BiomeCheck.NONE);
    }

    private static SeedCheck.Bedrock bedrock(int blocks, int mismatches) {
        return new SeedCheck.Bedrock(blocks, mismatches, List.of());
    }

    private static final SeedCheck.Bedrock NONE = bedrock(0, 0);

    @Test
    void whatFitsTheSeed() {
        assertEquals("the overworld bedrock seen fits it", StatusText.agreeing(check(0, 0, bedrock(1024, 0), NONE)));
        assertEquals("the overworld bedrock seen fits it", StatusText.agreeing(check(0, 0, bedrock(1024, 0), bedrock(2048, 7))));
        assertEquals("the 3 structures noted fit it", StatusText.agreeing(check(3, 0, NONE, NONE)));
        assertEquals("the overworld bedrock seen and the structure noted fit it", StatusText.agreeing(check(1, 0, bedrock(1024, 0), NONE)));
        assertEquals("the overworld bedrock seen and 2 of the 3 structures noted fit it", StatusText.agreeing(check(2, 1, bedrock(1024, 0), NONE)));
    }

    @Test
    void whatDoesntFitTheSeedAndWhy() {
        assertEquals("the structure noted doesn't fit it", StatusText.disagreeing(check(0, 1, NONE, NONE)));
        assertEquals("the overworld bedrock seen doesn't fit it", StatusText.disagreeing(check(0, 0, bedrock(1024, 400), NONE)));
        assertEquals("1 of the 2 structures noted and the nether bedrock seen don't fit it", StatusText.disagreeing(check(1, 1, NONE, bedrock(900, 300))));
        assertEquals("2 of the 3 structures noted don't fit it", StatusText.disagreeing(check(1, 2, NONE, NONE)));

        assertTrue(StatusText.contradictionReason(check(0, 0, bedrock(1024, 400), NONE)).startsWith("The server probably sends a made-up hashed seed"));
        assertTrue(StatusText.contradictionReason(check(0, 0, bedrock(1024, 3), NONE)).startsWith("Most of the overworld bedrock fits it, though"));
        SeedCheck structuresConfirm = new SeedCheck(12345L, SeedCheck.Verdict.CONTRADICTED, villages(6), villages(0), bedrock(1024, 400), NONE,
            List.of(), 1, 1e-15, SeedCheck.BiomeCheck.NONE);
        assertTrue(StatusText.contradictionReason(structuresConfirm).startsWith("The structures noted confirm it, though"),
            StatusText.contradictionReason(structuresConfirm));
        assertEquals("Either the server sends a made-up hashed seed, or those nether chunks were made by Minecraft 1.17 or older and that"
            + " structure was noted in the wrong place or doesn't follow the seed (older chunks, a command, a copy, or a server that places"
            + " structures differently). Bedrock from the overworld would tell which.",
            StatusText.contradictionReason(check(0, 1, NONE, bedrock(900, 300))));
    }

    @Test
    void aTypedSeedOnAServerIsShownOnlyAsTheNotesAllow() {
        SearchRunner runner = new SearchRunner(DATA);
        WorldSession session = new WorldSession(SERVER);
        session.setCollecting(true);
        session.setHashedSeed(Seeds.hashedSeed(12345L));
        session.setTypedSeedSearch(new TypedSeedSearch.Outcome(OptionalLong.of(12345L), 0));

        // Not checked yet: not shown as the world seed.
        List<String> lines = StatusText.lines(session, runner);
        assertTrue(lines.stream().anyMatch(line -> line.endsWith("Checking it against the notes...")), lines.toString());
        assertTrue(lines.stream().noneMatch(line -> line.startsWith("World seed:")), lines.toString());
        assertFalse(StatusText.statusLine(session, runner).contains("12345"));
        assertEquals(OptionalLong.empty(), StatusText.seedToCopy(session));

        // Nothing noted to check it against: shown, but not confirmed.
        session.setTypedSeedCheck(session.version(), SeedCheck.of(DATA, List.of(), List.of(), 12345L));
        lines = StatusText.lines(session, runner);
        assertTrue(lines.contains("World seed: 12345"), lines.toString());
        assertTrue(lines.stream().anyMatch(line -> line.contains("unless the server sends a made-up hashed seed")), lines.toString());
        assertEquals("Seed Finder: world seed 12345 (not confirmed yet)", StatusText.statusLine(session, runner));
        assertEquals(OptionalLong.of(12345L), StatusText.seedToCopy(session));

        // A village where seed 12345 has none: not shown as the world seed.
        List<StructureObservation> misfit = ObservationReader.read(DATA, List.of("village 352 80")).structures();
        session.setTypedSeedCheck(session.version(), SeedCheck.of(DATA, misfit, List.of(), 12345L));
        lines = StatusText.lines(session, runner);
        assertTrue(lines.stream().noneMatch(line -> line.startsWith("World seed:")), lines.toString());
        assertTrue(lines.contains("The hashed seed the server sent is the one of the typed seed 12345, but the structure noted doesn't fit it."
            + " Either the server sends a made-up hashed seed, or that structure was noted in the wrong place or doesn't follow the seed"
            + " (older chunks, a command, a copy, or a server that places structures differently). Bedrock from the overworld would tell"
            + " which."), lines.toString());
        assertFalse(StatusText.statusLine(session, runner).contains("12345"));
        assertEquals(OptionalLong.empty(), StatusText.seedToCopy(session));

        // The overworld bedrock fits: certain.
        List<String> bedrockLines = BedrockText.heights(new BedrockPattern(DATA.bedrockDimension("overworld").orElseThrow(), 12345L), 0, 0, 16, 16);
        session.setTypedSeedCheck(session.version(), SeedCheck.of(DATA, misfit, ObservationReader.read(DATA, bedrockLines).bedrock(), 12345L));
        lines = StatusText.lines(session, runner);
        assertTrue(lines.contains("World seed: 12345"), lines.toString());
        assertTrue(lines.contains("Certain: it was typed (as text or as this number), its hash is the hashed seed the server sent, and the"
            + " overworld bedrock seen fits it."), lines.toString());
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("Note: villages at block (352, 80) doesn't fit this seed.")), lines.toString());
        assertEquals("Seed Finder: world seed 12345", StatusText.statusLine(session, runner));
    }

    @Test
    void aSeedProvedByTheStructureSearchComesFirst() {
        SearchRunner runner = new SearchRunner(DATA);
        WorldSession session = new WorldSession(SERVER);
        session.setCollecting(true);
        session.setHashedSeed(Seeds.hashedSeed(12345L));
        session.setTypedSeedSearch(new TypedSeedSearch.Outcome(OptionalLong.of(12345L), 0));
        List<StructureObservation> structures = ObservationReader.read(DATA, List.of("desert_pyramid 272 80", "swamp_hut 256 -144",
            "village 336 80", "shipwreck -128 32", "ruined_portal 16 0", "trial_chamber 0 224")).structures();
        session.setTypedSeedCheck(session.version(), SeedCheck.of(DATA, List.of(structures.get(2)), List.of(), 12345L));
        WorldSeedSearch.Result result = WorldSeedSearch.run(DATA, structures, List.of(), session.hashedSeed(), 2, (done, total) -> { });
        session.setResult(session.version(), result, null);
        List<String> lines = StatusText.lines(session, runner);
        assertTrue(lines.contains("Certain: it is the only seed that fits the structures and the hashed seed the server sent."), lines.toString());
        assertEquals("Seed Finder: world seed 12345", StatusText.statusLine(session, runner));
    }
}
