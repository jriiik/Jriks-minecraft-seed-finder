package io.github.jriiik.seedfinder.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class SeedFinderCliTest {

    private record Result(int exitCode, String out, String err) {
    }

    private static Result run(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine commandLine = SeedFinderCli.commandLine();
        commandLine.setOut(new PrintWriter(out, true));
        commandLine.setErr(new PrintWriter(err, true));
        int exitCode = commandLine.execute(args);
        return new Result(exitCode, out.toString(), err.toString());
    }

    /** Structure spots of seed 12345 (from 'seedfinder locate --seed 12345'), the example in the help text. */
    private static final String[] EXAMPLE = {
        "desert_pyramid:272,80", "swamp_hut:256,-144", "village:336,80", "shipwreck:-128,32", "ruined_portal:16,0", "trial_chamber:0,224",
    };

    private static String[] find(String... args) {
        String[] all = new String[args.length + 3];
        all[0] = "find";
        all[1] = "--threads";
        all[2] = "2";
        System.arraycopy(args, 0, all, 3, args.length);
        return all;
    }

    @Test
    void findWorksOutTheStructureSeedOfTheExample() {
        Result result = run(find(EXAMPLE));
        assertEquals(0, result.exitCode(), result.err());
        assertTrue(result.out().contains("Minecraft 26.3, 6 structures:"), result.out());
        assertTrue(result.out().contains("Found the structure seed: 12345\n"), result.out());
    }

    @Test
    void findListsEverySeedThatFitsAndSuggestsWhatToAdd() {
        Result result = run(find(java.util.Arrays.copyOf(EXAMPLE, 5)));
        assertEquals(0, result.exitCode(), result.err());
        assertTrue(result.out().contains("structure seeds that fit everything you entered"), result.out());
        assertTrue(result.out().contains("  12345\n"), result.out());
        assertTrue(result.out().contains("add a structure of one of these types: abandoned_camp, trial_chambers, ancient_cities"), result.out());
    }

    @Test
    void findReadsAFileIncludingLinesPastedFromLocate(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws java.io.IOException {
        java.nio.file.Path file = folder.resolve("structures.txt");
        java.nio.file.Files.writeString(file, String.join("\n",
            "# My world",
            "desert_pyramid 272 80",
            "swamp_hut: 256, -144",
            "The nearest minecraft:village_plains is at [336, ~, 80] (345 blocks away)",
            "shipwreck -128 63 32",
            "",
            "ruined_portal 16 0",
            "trial_chamber 0 224"));
        Result result = run(find("--file", file.toString()));
        assertEquals(0, result.exitCode(), result.err());
        assertTrue(result.out().contains("Found the structure seed: 12345\n"), result.out());
    }

    @Test
    void findExplainsWhatIsMissing() {
        Result tooFew = run(find("igloo:48,208", "village:336,80"));
        assertEquals(1, tooFew.exitCode());
        assertTrue(tooFew.out().contains("Can't search yet: With this information about"), tooFew.out());

        Result noStart = run(find("ruined_portal:16,0", "ruined_portal:700,700", "abandoned_camp:80,144"));
        assertEquals(1, noStart.exitCode());
        assertTrue(noStart.out().contains("add at least one of these structures: desert_pyramids"), noStart.out());

        Result impossible = run(find("village:500,80", "igloo:48,208"));
        assertEquals(1, impossible.exitCode());
        assertTrue(impossible.out().contains("never start in that chunk"), impossible.out());
    }

    @Test
    void findRejectsBadInputWithAShortMessage() {
        Result unknown = run(find("castle:1,2"));
        assertEquals(2, unknown.exitCode());
        assertTrue(unknown.err().contains("'castle:1,2': unknown structure 'castle'"), unknown.err());
        assertFalse(unknown.err().contains("Usage:"));

        assertEquals(2, run(find("village:1")).exitCode());
        assertEquals(2, run(find()).exitCode());
        assertEquals(2, run(find("--file", "does-not-exist.txt")).exitCode());
    }

    @Test
    void structureSeedShowsTheLowest48Bits() {
        assertEquals("World seed 12345 has structure seed 12345.\n", run("structure-seed", "12345").out());
        assertEquals("World seed -1 has structure seed 281474976710655.\n", run("structure-seed", "-1").out());
        assertTrue(run("structure-seed", "Jrik").out().contains("(text, which Minecraft turns into 2317450) has structure seed 2317450."));
    }

    @Test
    void locateListsAVillageTheGameAlsoPutsThere() {
        // Minecraft 26.3 picks chunk (-88, -79) for villages in region (-3, -3) of seed 12345 (see the oracle
        // fixtures), which /locate reports as block (-1408, -1264), 1892 blocks from the origin.
        Result result = run("locate", "--seed", "12345", "--radius", "2000", "villages");
        assertEquals(0, result.exitCode(), result.err());
        assertTrue(result.out().matches("(?s).*villages\\s+-1408\\s+-1264\\s+1892\\n.*"), result.out());
    }

    @Test
    void locateExplainsTextSeeds() {
        Result result = run("locate", "--seed", "Jrik", "villages");
        assertEquals(0, result.exitCode(), result.err());
        assertTrue(result.out().contains("seed \"Jrik\" (text, which Minecraft turns into 2317450)"), result.out());
    }

    @Test
    void locateSortsSpotsByDistance() {
        Result result = run("locate", "--seed", "-1", "--at", "300,-700", "--radius", "1500");
        assertEquals(0, result.exitCode(), result.err());
        long previous = -1;
        for (String line : result.out().lines().toList()) {
            String[] columns = line.trim().split("\\s+");
            if (columns.length == 4 && columns[3].matches("\\d+")) {
                long distance = Long.parseLong(columns[3]);
                assertTrue(distance >= previous, "not sorted at: " + line);
                previous = distance;
            }
        }
        assertTrue(previous > 0, "expected some spots in:\n" + result.out());
    }

    @Test
    void locateRejectsUnknownStructuresWithAShortMessage() {
        Result result = run("locate", "--seed", "1", "castles");
        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("Unknown structure 'castles'"), result.err());
        assertTrue(result.err().contains("seedfinder locate --help"), result.err());
        assertFalse(result.err().contains("Usage:"), "should not dump the whole help page");
    }

    @Test
    void locatePointsToTheRightDimension() {
        Result result = run("locate", "--seed", "1", "end_cities");
        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("Add --dimension end"), result.err());
        assertEquals(0, run("locate", "--seed", "1", "--dimension", "end", "end_cities").exitCode());
    }

    @Test
    void locateRejectsBadInput() {
        assertEquals(2, run("locate", "--seed", "1", "--at", "5").exitCode());
        assertEquals(2, run("locate", "--seed", "1", "--at", "2147483647,0").exitCode());
        assertEquals(2, run("locate", "--seed", "1", "--radius", "0").exitCode());
        assertEquals(2, run("locate", "--seed", "   ").exitCode());
        assertEquals(2, run("locate", "--seed", "1", "-m", "1.8").exitCode());
        assertEquals(2, run("locate", "--seed", "1", "strongholds").exitCode());
        assertEquals(2, run("locate", "--seed", "1", "--radius", "5000", "mineshafts").exitCode());
        assertEquals(2, run("locate").exitCode());
    }

    @Test
    void structuresListsTheNewAbandonedCamps() {
        Result result = run("structures");
        assertEquals(0, result.exitCode(), result.err());
        assertTrue(result.out().contains("abandoned_camp"), result.out());
        assertTrue(result.out().contains("overworld + nether"), "ruined portals appear in two dimensions");
    }

    @Test
    void versionsListsTheNewestVersion() {
        Result result = run("versions");
        assertEquals(0, result.exitCode(), result.err());
        assertTrue(result.out().contains("26.3  (newest)"), result.out());
    }
}
