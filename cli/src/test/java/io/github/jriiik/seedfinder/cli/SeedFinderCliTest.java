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
