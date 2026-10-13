package io.github.jriiik.seedfinder.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
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
    void findNamesTheLikelyFullSeedsWithoutBedrock() {
        Result result = run(find(EXAMPLE));
        assertTrue(result.out().contains("(Minecraft then picks one), it is 5794443870565576761.\n"), result.out());
        assertTrue(result.out().contains("or as a short number, it is 12345.\n"), result.out());
    }

    /** The help text's example plus the bedrock floor of chunk (0, 0), as 'seedfinder bedrock' prints it. */
    @Test
    void findWithBedrockFindsTheFullSeed(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws java.io.IOException {
        Result bedrock = run("bedrock", "--seed", "12345");
        assertEquals(0, bedrock.exitCode(), bedrock.err());
        List<String> lines = bedrock.out().lines().dropWhile(line -> !line.startsWith("bedrock-heights")).toList();
        assertEquals(17, lines.size(), bedrock.out());
        assertEquals("bedrock-heights 0 0", lines.get(0));
        java.nio.file.Path file = folder.resolve("world.txt");
        java.nio.file.Files.writeString(file, bedrock.out()); // the whole output is a valid notes file

        Result result = run(find(java.util.stream.Stream.concat(java.util.stream.Stream.of("--file", file.toString()),
            java.util.Arrays.stream(EXAMPLE)).toArray(String[]::new)));
        assertEquals(0, result.exitCode(), result.err());
        assertTrue(result.out().contains("Found the structure seed: 12345\n"), result.out());
        assertTrue(result.out().contains("Found the full seed: 12345\n"), result.out());
        assertTrue(result.out().contains("can come from typing text (or this number)"), result.out());

        Result fullSeed = run("full-seed", "12345", "--threads", "2", "--file", file.toString());
        assertEquals(0, fullSeed.exitCode(), fullSeed.err());
        assertTrue(fullSeed.out().contains("Found the full seed: 12345\n"), fullSeed.out());
        assertFalse(fullSeed.out().contains("Careful"), "a whole chunk is plenty");
    }

    @Test
    void fullSeedPointsAtBlocksThatDontFit(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws java.io.IOException {
        List<String> lines = new java.util.ArrayList<>(run("bedrock", "--seed", "-4964420948893066024").out().lines()
            .dropWhile(line -> !line.startsWith("bedrock-heights")).toList());
        char[] row = lines.get(5).toCharArray(); // row z=4
        row[3] = row[3] == '0' ? '1' : '0';
        lines.set(5, new String(row));
        java.nio.file.Path file = folder.resolve("bedrock.txt");
        java.nio.file.Files.write(file, lines);
        Result result = run("full-seed", Long.toString(-4964420948893066024L & 0xFFFF_FFFF_FFFFL), "--file", file.toString());
        assertEquals(1, result.exitCode(), result.out());
        assertTrue(result.out().contains("No world seed fits all of your bedrock. The closest, -4964420948893066024, gets"), result.out());
        assertTrue(result.out().contains("at (3, "), result.out());
    }

    @Test
    void fullSeedWithASmallPatchSaysWhereToDig(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws java.io.IOException {
        java.nio.file.Path file = folder.resolve("bedrock.txt");
        java.nio.file.Files.writeString(file, run("bedrock", "--seed", "-5090009733490601899", "--at", "1000,-2000", "--size", "4").out());
        Result result = run("full-seed", "183745344901205", "--file", file.toString());
        assertEquals(0, result.exitCode(), result.err());
        assertTrue(result.out().contains("8 world seeds fit all of your bedrock:"), result.out());
        assertTrue(result.out().contains("  A  -5090009733490601899  (Minecraft can pick this one by itself)"), result.out());
        assertTrue(result.out().contains("To tell them apart, add the top bedrock height of these columns:\n  x="), result.out());
    }

    @Test
    void fullSeedRejectsBadInput(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws java.io.IOException {
        assertEquals(2, run("full-seed", "281474976710656").exitCode());
        assertEquals(2, run("full-seed", "-1").exitCode());
        assertEquals(2, run("full-seed").exitCode());
        java.nio.file.Path file = folder.resolve("bedrock.txt");
        java.nio.file.Files.write(file, List.of("bedrock-heights 0 0", "0129"));
        Result digit = run("full-seed", "12345", "--file", file.toString());
        assertEquals(2, digit.exitCode());
        assertTrue(digit.err().contains("line 2 of " + file + ": the height digit 9 is impossible here"), digit.err());
        java.nio.file.Files.write(file, List.of("not-bedrock 0 -64 0"));
        Result impossible = run("full-seed", "12345", "--file", file.toString());
        assertEquals(1, impossible.exitCode());
        assertTrue(impossible.out().contains("the overworld always has bedrock at y=-64"), impossible.out());
    }

    @Test
    void notesFilesInOtherEncodingsWork(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws java.io.IOException {
        String notes = run("bedrock", "--seed", "12345").out().replace("\n", "\r\n");
        java.nio.file.Path bom = folder.resolve("bom.txt");
        java.nio.file.Files.write(bom, ("\uFEFF" + notes).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.nio.file.Path utf16 = folder.resolve("utf16.txt");
        java.nio.file.Files.write(utf16, ("\uFEFF" + notes).getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
        java.nio.file.Path ansi = folder.resolve("ansi.txt");
        java.nio.file.Files.write(ansi, ("# B\u00e4ume\r\n" + notes).getBytes(java.nio.charset.Charset.forName("windows-1252")));
        for (java.nio.file.Path file : List.of(bom, utf16, ansi)) {
            Result result = run("full-seed", "12345", "--threads", "2", "--file", file.toString());
            assertTrue(result.out().contains("Found the full seed: 12345\n"), file + ": " + result.out() + result.err());
        }
    }

    @Test
    void netherBedrockPicksTheStructureSeed(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws java.io.IOException {
        java.nio.file.Path file = folder.resolve("nether.txt");
        java.nio.file.Files.writeString(file, run("bedrock", "--seed", "12345", "--dimension", "nether").out());
        Result result = run("full-seed", "25357015387625", "12345", "--file", file.toString());
        assertEquals(0, result.exitCode(), result.out() + result.err());
        assertTrue(result.out().contains("Nether bedrock: of the 2 structure seeds, only 12345 fits all"), result.out());
        assertTrue(result.out().contains("it is 5794443870565576761."), result.out());

        java.nio.file.Files.write(file, List.of("bedrock-heights nether 0 0", "01234"));
        Result wrong = run("full-seed", "12345", "--file", file.toString());
        assertEquals(1, wrong.exitCode(), wrong.out());
        assertTrue(wrong.out().contains("Your nether bedrock doesn't fit"), wrong.out());
        assertTrue(wrong.out().contains(" in the nether, this seed says"), wrong.out());
        assertFalse(wrong.out().contains("it is 5794443870565576761"), "no seed may be named when the nether notes don't fit");
    }

    @Test
    void likelySeedsForSpecialCases() {
        Result twice = run("full-seed", "12345", "12345");
        assertEquals(0, twice.exitCode(), twice.out());
        assertTrue(twice.out().contains("it is 5794443870565576761."), twice.out());
        // 0 can be typed and is also a seed Minecraft can pick by itself.
        Result zero = run("full-seed", "0");
        assertTrue(zero.out().contains("(Minecraft then picks one), it is 0"), zero.out());
        assertTrue(zero.out().contains("or as a short number, it is 0."), zero.out());
    }

    @Test
    void findNeedsSomethingToWorkWith(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws java.io.IOException {
        java.nio.file.Path comments = folder.resolve("comments.txt");
        java.nio.file.Files.write(comments, List.of("# my world", "# nothing yet"));
        assertEquals(2, run(find("--file", comments.toString())).exitCode());
        assertEquals(2, run(find("# hi")).exitCode());
        assertEquals(2, run(find("--threads", "40000", "igloo:48,208")).exitCode());
        assertEquals(2, run("full-seed", "12345", "--threads", "40000").exitCode());
    }

    @Test
    void findFailsWhenTheBedrockDoesNotFit(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws java.io.IOException {
        java.nio.file.Path file = folder.resolve("other-world.txt");
        java.nio.file.Files.writeString(file, run("bedrock", "--seed", "999").out());
        Result result = run(find(java.util.stream.Stream.concat(java.util.stream.Stream.of("--file", file.toString()),
            java.util.Arrays.stream(EXAMPLE)).toArray(String[]::new)));
        assertEquals(1, result.exitCode(), result.out());
        assertTrue(result.out().contains("Found the structure seed: 12345\n"), result.out());
        assertTrue(result.out().contains("No world seed fits"), result.out());
    }

    @Test
    void bedrockShowsTheNetherAndRejectsTheEnd() {
        Result nether = run("bedrock", "--seed", "12345", "--dimension", "nether", "--size", "4", "--layers");
        assertEquals(0, nether.exitCode(), nether.err());
        assertTrue(nether.out().contains("bedrock-heights nether 0 0\n"), nether.out());
        assertTrue(nether.out().contains("bedrock-layer nether 0 126 0\n"), nether.out());
        assertTrue(nether.out().contains("only depends on the structure seed"), nether.out());
        assertEquals(2, run("bedrock", "--seed", "12345", "--dimension", "end").exitCode());
        assertEquals(2, run("bedrock", "--seed", "12345", "--size", "0").exitCode());
        assertEquals(2, run("bedrock", "--seed", "12345", "--at", "1").exitCode());
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

    @Test
    void typedSeedFindsATypedSeedFromItsHashedSeed(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws java.io.IOException {
        Result result = run("typed-seed", "--threads", "2", Long.toString(io.github.jriiik.seedfinder.Seeds.hashedSeed(12345L)));
        assertEquals(0, result.exitCode(), result.err());
        assertTrue(result.out().contains("Found the world seed: 12345\n"), result.out());

        // From the mod's notes file.
        java.nio.file.Path notes = folder.resolve("notes.txt");
        java.nio.file.Files.writeString(notes, "# Jrik's Seed Finder notes\nhashed-seed " + io.github.jriiik.seedfinder.Seeds.hashedSeed(-7L) + "\n");
        result = run("typed-seed", "--threads", "2", "--file", notes.toString());
        assertEquals(0, result.exitCode(), result.err());
        assertTrue(result.out().contains("Found the world seed: -7\n"), result.out());

        // find points to it when the notes have a hashed seed but no structures.
        result = run(find("--file", notes.toString()));
        assertEquals(1, result.exitCode(), result.err());
        assertTrue(result.out().contains("seedfinder typed-seed " + io.github.jriiik.seedfinder.Seeds.hashedSeed(-7L)), result.out());
    }

    @Test
    void typedSeedTakesANegativeHashedSeed() {
        // Half of all hashed seeds are negative; on the command line they must not be taken for an option.
        Result result = run("typed-seed", "--threads", "2", Long.toString(io.github.jriiik.seedfinder.Seeds.hashedSeed(1L)));
        assertEquals(0, result.exitCode(), result.err());
        assertTrue(result.out().contains("Found the world seed: 1\n"), result.out());
        assertTrue(result.out().contains("unless the server sends a made-up hashed seed"), result.out());
    }

    @Test
    void typedSeedChecksTheSeedAgainstTheNotes(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws java.io.IOException {
        var data = io.github.jriiik.seedfinder.version.MinecraftVersion.V26_3.data();
        var overworld = data.bedrockDimension("overworld").orElseThrow();
        String hashedSeed = "hashed-seed " + io.github.jriiik.seedfinder.Seeds.hashedSeed(12345L) + "\n";

        // The bedrock of the world fits the typed seed: confirmed.
        java.nio.file.Path notes = folder.resolve("notes.txt");
        java.nio.file.Files.writeString(notes, hashedSeed + String.join("\n", io.github.jriiik.seedfinder.bedrock.BedrockText.heights(
            new io.github.jriiik.seedfinder.bedrock.BedrockPattern(overworld, 12345L), 0, 0, 16, 16)) + "\n");
        Result result = run("typed-seed", "--threads", "2", "--file", notes.toString());
        assertEquals(0, result.exitCode(), result.err());
        assertTrue(result.out().contains("Found the world seed: 12345\n"), result.out());
        assertTrue(result.out().contains("The notes confirm it: "), result.out());

        // Bedrock of another world: the server sends a made-up hashed seed, and the typed seed is not the world's.
        java.nio.file.Files.writeString(notes, hashedSeed + String.join("\n", io.github.jriiik.seedfinder.bedrock.BedrockText.heights(
            new io.github.jriiik.seedfinder.bedrock.BedrockPattern(overworld, 999L), 0, 0, 16, 16)) + "\n");
        result = run("typed-seed", "--threads", "2", "--file", notes.toString());
        assertEquals(1, result.exitCode(), result.err());
        assertFalse(result.out().contains("Found the world seed"), result.out());
        assertTrue(result.out().contains("the notes don't fit that seed (bedrock blocks that don't fit: "), result.out());
    }

    @Test
    void typedSeedNeedsExactlyOneHashedSeed() {
        assertEquals(2, run("typed-seed").exitCode());
        Result result = run("typed-seed", "not-a-number");
        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("must be a whole number"), result.err());
        result = run("typed-seed", "123", "--file", "notes.txt");
        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("not both"), result.err());
    }

    @Test
    void durationsReadNaturally() {
        assertEquals("10 seconds", TypedSeedCommand.duration(3));
        assertEquals("40 seconds", TypedSeedCommand.duration(41));
        assertEquals("80 seconds", TypedSeedCommand.duration(84));
        assertEquals("2 minutes", TypedSeedCommand.duration(95));
        assertEquals("21 minutes", TypedSeedCommand.duration(1245));
    }
}
