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

        // find points to it when the notes have a hashed seed but no structures (with the file, so the notes check the seed).
        result = run(find("--file", notes.toString()));
        assertEquals(1, result.exitCode(), result.err());
        assertTrue(result.out().contains("seedfinder typed-seed --file " + notes), result.out());
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
        assertTrue(result.out().contains("the notes don't fit that seed (overworld bedrock blocks that don't fit: "), result.out());
    }

    /** A notes file with the hashed seed of 12345 and the given lines. */
    private static java.nio.file.Path notes12345(java.nio.file.Path folder, List<String> lines) throws java.io.IOException {
        java.nio.file.Path file = folder.resolve("notes-" + Integer.toHexString(lines.hashCode()) + ".txt");
        List<String> all = new java.util.ArrayList<>();
        all.add("hashed-seed " + io.github.jriiik.seedfinder.Seeds.hashedSeed(12345L));
        all.addAll(lines);
        java.nio.file.Files.write(file, all);
        return file;
    }

    private static List<String> bedrockLines(String dimension, long worldSeed) {
        var data = io.github.jriiik.seedfinder.version.MinecraftVersion.V26_3.data();
        return io.github.jriiik.seedfinder.bedrock.BedrockText.heights(
            new io.github.jriiik.seedfinder.bedrock.BedrockPattern(data.bedrockDimension(dimension).orElseThrow(), worldSeed), 0, 0, 16, 16);
    }

    private static Result typedSeed(java.nio.file.Path notes) {
        return run("typed-seed", "--threads", "2", "--file", notes.toString());
    }

    @Test
    void typedSeedExplainsEachVerdict(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws java.io.IOException {
        // One structure: not enough to confirm.
        Result result = typedSeed(notes12345(folder, List.of("village 336 80")));
        assertEquals(0, result.exitCode(), result.err());
        assertTrue(result.out().contains("This is right unless the server sends a made-up hashed seed: the notes don't have enough structures"
            + " or overworld\nbedrock yet to confirm it."), result.out());

        // A structure noted one chunk off: the seed is not presented, and the likeliest causes are named.
        result = typedSeed(notes12345(folder, List.of("village 336 96")));
        assertEquals(1, result.exitCode(), result.err());
        assertFalse(result.out().contains("Found the world seed"), result.out());
        assertTrue(result.out().contains("the notes don't fit that seed (structures that don't fit: 1 of 1).\nEither the server sends a made-up"
            + " hashed seed, or the structure was noted in the wrong place or doesn't follow the seed (older chunks, a command, a copy, or a"
            + " server that places structures differently). Bedrock notes from the overworld would tell which.\nStructures that don't fit:"
            + " villages at block (336, 96). If one was noted in the wrong place, fix it and run this again."), result.out());

        // Two structures confirm it; the third is listed.
        result = typedSeed(notes12345(folder, List.of("village 336 96", "desert_pyramid 272 80", "trial_chamber 0 224")));
        assertEquals(0, result.exitCode(), result.err());
        assertTrue(result.out().contains("Found the world seed: 12345\n"), result.out());
        assertTrue(result.out().contains("The notes confirm it: 2 structures fit it.\nThis structure in the notes doesn't fit it: villages at"
            + " block (336, 96). The server may place structures differently, or it comes from an older Minecraft version, a command or a"
            + " copy (or was noted in the wrong place)."), result.out());

        // Overworld bedrock confirms it; nether bedrock from chunks made by Minecraft 1.17 or older doesn't follow the seed.
        List<String> lines = new java.util.ArrayList<>(bedrockLines("overworld", 12345L));
        lines.add("");
        lines.addAll(bedrockLines("nether", 999L));
        result = typedSeed(notes12345(folder, lines));
        assertEquals(0, result.exitCode(), result.err());
        assertTrue(result.out().matches("(?s).*The notes confirm it: \\d+ overworld bedrock blocks fit it\\.\n\\d+ of the \\d+ nether bedrock"
            + " blocks in the notes don't fit it: nether chunks made by Minecraft 1\\.17 or older don't follow the seed \\(or they were"
            + " noted wrong\\):\n  your notes say .* in the nether.*"), result.out());

        // A note that is wrong whatever the seed is left out; the rest still confirms it.
        lines = new java.util.ArrayList<>(bedrockLines("overworld", 12345L));
        lines.add("");
        lines.add("not-bedrock 5 -64 5");
        result = typedSeed(notes12345(folder, lines));
        assertEquals(0, result.exitCode(), result.err());
        assertTrue(result.out().contains("Left out of the check, because it is wrong whatever the seed: "), result.out());
        assertTrue(result.out().contains("The notes confirm it: "), result.out());
    }

    @Test
    void typedSeedPointsAtMistypedBedrock(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws java.io.IOException {
        // One digit of the heights changed: two blocks are wrong, which a wrong seed (about a third wrong) can't explain.
        List<String> lines = new java.util.ArrayList<>(bedrockLines("overworld", 12345L));
        String row = lines.get(1);
        char first = row.charAt(0);
        lines.set(1, (first == '3' ? '1' : '3') + row.substring(1));
        Result result = typedSeed(notes12345(folder, lines));
        assertEquals(1, result.exitCode(), result.err());
        assertFalse(result.out().contains("Found the world seed"), result.out());
        assertTrue(result.out().contains("Most of the overworld bedrock fits it, though (a wrong seed gets about a third of it wrong), so these"
            + " blocks were\nprobably noted wrong:\n  your notes say "), result.out());
        assertTrue(result.out().contains("Check them in the game, fix them and run this again."), result.out());
        assertFalse(result.out().contains("made-up"), result.out());
    }

    @Test
    void typedSeedTellsANotingMistakeFromAMadeUpHashedSeed(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws java.io.IOException {
        List<String> structures = List.of("desert_pyramid 272 80", "swamp_hut 256 -144", "village 336 80", "shipwreck -128 32",
            "ruined_portal 16 0", "trial_chamber 0 224");

        // Bedrock of another world: probably a made-up hashed seed.
        Result result = typedSeed(notes12345(folder, bedrockLines("overworld", 999L)));
        assertEquals(1, result.exitCode(), result.err());
        assertTrue(result.out().contains("\nThe server probably sends a made-up hashed seed, so this is not the world's seed. Use 'seedfinder"
            + " find' with the\nstructures and bedrock instead.\n"), result.out());

        // The world's own bedrock written under the wrong corner, while six structures confirm the seed: a noting mistake.
        List<String> lines = new java.util.ArrayList<>(structures);
        List<String> shifted = new java.util.ArrayList<>(bedrockLines("overworld", 12345L));
        shifted.set(0, "bedrock-heights 16 0");
        lines.addAll(shifted);
        result = typedSeed(notes12345(folder, lines));
        assertEquals(1, result.exitCode(), result.err());
        assertFalse(result.out().contains("Found the world seed") || result.out().contains("made-up"), result.out());
        assertTrue(result.out().contains("The structures in the notes confirm it, though, so the overworld bedrock was probably noted in the"
            + " wrong place:\n"), result.out());

        // One nether digit mistyped, nothing else noted: the block is shown so it can be checked.
        List<String> nether = new java.util.ArrayList<>(bedrockLines("nether", 12345L));
        String row = nether.get(1);
        nether.set(1, (row.charAt(0) == '2' ? '1' : '2') + row.substring(1));
        result = typedSeed(notes12345(folder, nether));
        assertEquals(1, result.exitCode(), result.err());
        assertTrue(result.out().contains("Either the server sends a made-up hashed seed, or the nether bedrock was noted wrong or comes from"
            + " chunks made by Minecraft 1.17 or older. Bedrock notes from the overworld would tell which.\nNether bedrock that doesn't fit:\n"
            + "  your notes say "), result.out());
        assertTrue(result.out().contains("If it was noted wrong, fix it and run this again."), result.out());
    }

    @Test
    void typedSeedNeedsAHashedSeedInTheNotes(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws java.io.IOException {
        java.nio.file.Path notes = folder.resolve("no-hash.txt");
        java.nio.file.Files.writeString(notes, "village 336 80\n");
        Result result = typedSeed(notes);
        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("has no 'hashed-seed' line"), result.err());
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

    @Test
    void biomesFromOneAreaGiveTheFullSeedOfTheExample(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws java.io.IOException {
        Result biomes = run("biomes", "--seed", "12345", "--at", "-96,-96", "--size", "48");
        assertEquals(0, biomes.exitCode(), biomes.err());
        assertTrue(biomes.out().contains("biome-grid -96 64 -96 "), biomes.out());
        java.nio.file.Path file = folder.resolve("notes.txt");
        java.nio.file.Files.writeString(file, String.join("\n", EXAMPLE).replace(':', ' ').replace(',', ' ') + "\n\n" + biomes.out());
        Result result = run(find("--file", file.toString()));
        assertEquals(0, result.exitCode(), result.out() + result.err());
        assertTrue(result.out().contains("And 2,304 biome notes in 1 place that can help find the full seed."), result.out());
        assertTrue(result.out().contains("Found the full seed: 12345\n"), result.out());
    }

    @Test
    void biomesOfAnotherWorldDontFit(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws java.io.IOException {
        java.nio.file.Path file = folder.resolve("notes.txt");
        java.nio.file.Files.writeString(file, run("biomes", "--seed", "999", "--size", "24").out());
        Result result = run("full-seed", "12345", "--file", file.toString(), "--threads", "2");
        assertEquals(1, result.exitCode(), result.out());
        assertTrue(result.out().contains("fits your biome notes under any world preset"), result.out());
    }

    @Test
    void netherBedrockAloneGivesTheStructureSeedAndTheHashedSeedTheRest(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder)
        throws java.io.IOException {
        Result nether = run("bedrock", "--seed", "12345", "--dimension", "nether", "--layers", "--at", "64,-32");
        assertEquals(0, nether.exitCode(), nether.err());
        java.nio.file.Path file = folder.resolve("notes.txt");
        java.nio.file.Files.writeString(file, nether.out() + "\nhashed-seed " + io.github.jriiik.seedfinder.Seeds.hashedSeed(12345L) + "\n");
        Result result = run(find("--file", file.toString()));
        assertEquals(0, result.exitCode(), result.out() + result.err());
        assertTrue(result.out().contains("Minecraft 26.3, no structures.\n"), result.out());
        assertTrue(result.out().contains("Nether bedrock: 1,024 floor and 1,024 roof blocks"), result.out());
        assertTrue(result.out().contains("structure seed") && result.out().contains("12345"), result.out());
        assertTrue(result.out().contains("Found the full seed: 12345\n"), result.out());
    }

    @Test
    void netherBedrockIsUsedWithStructuresToo(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws java.io.IOException {
        String nether = run("bedrock", "--seed", "12345", "--dimension", "nether", "--layers", "--at", "64,-32").out();
        String hashed = "\nhashed-seed " + io.github.jriiik.seedfinder.Seeds.hashedSeed(12345L) + "\n";
        java.nio.file.Path file = folder.resolve("notes.txt");

        // one structure: too few to search, so the nether bedrock gives the structure seed
        java.nio.file.Files.writeString(file, "village 336 80\n" + nether + hashed);
        Result oneStructure = run(find("--file", file.toString()));
        assertEquals(0, oneStructure.exitCode(), oneStructure.out() + oneStructure.err());
        assertTrue(oneStructure.out().contains("The nether bedrock is searched instead."), oneStructure.out());
        assertTrue(oneStructure.out().contains("Found the full seed: 12345\n"), oneStructure.out());

        // a village noted a chunk off: the nether bedrock and the hashed seed give the seed, and the village is named
        String structures = String.join("\n", EXAMPLE).replace(':', ' ').replace(',', ' ').replace("village 336 80", "village 352 80");
        java.nio.file.Files.writeString(file, structures + "\n" + nether + hashed);
        Result misfit = run(find("--file", file.toString()));
        assertEquals(0, misfit.exitCode(), misfit.out() + misfit.err());
        assertTrue(misfit.out().contains("Found the full seed: 12345\n"), misfit.out());
        assertTrue(misfit.out().contains("villages at block (352, 80) doesn't fit this seed"), misfit.out());
    }

    @Test
    void littleBedrockWithManyStructureSeedsIsNotCalledFound(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws java.io.IOException {
        java.nio.file.Path file = folder.resolve("bedrock.txt");
        // a whole chunk: certain for one structure seed (a wrong one has a fitting seed about once in 100), not for 41
        java.nio.file.Files.writeString(file, run("bedrock", "--seed", "12345").out());
        assertTrue(run("full-seed", "12345", "--threads", "2", "--file", file.toString()).out().contains("Found the full seed: 12345"));
        List<String> args = new java.util.ArrayList<>(List.of("full-seed", "--threads", "2", "--file", file.toString(), "12345"));
        for (int i = 1; i <= 40; i++) {
            args.add(Long.toString(12345L + 1000L * i));
        }
        Result result = run(args.toArray(String[]::new));
        assertFalse(result.out().contains("Found the full seed"), result.out());
        assertTrue(result.out().contains("One world seed fits all of your bedrock: ") || result.out().contains("world seeds fit all of your bedrock"),
            result.out());
    }

    @Test
    void biomeNotesWithMistakesAreReadOrLeftOut(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws java.io.IOException {
        Result biomes = run("biomes", "--seed", "12345", "--at", "-96,-96", "--size", "48");
        java.nio.file.Path file = folder.resolve("notes.txt");
        java.nio.file.Files.writeString(file, biomes.out() + "\nbiome dark forrest 120 64 -35\npreset normal\n");
        Result result = run("full-seed", "12345", "--file", file.toString(), "--threads", "2");
        assertEquals(0, result.exitCode(), result.out() + result.err());
        assertTrue(result.out().contains("Found the full seed: 12345"), result.out());
        assertTrue(result.out().contains("'minecraft:dark_forrest' is not an overworld biome"), result.out());
        assertFalse(result.err().contains("Exception"), result.err());

        java.nio.file.Files.writeString(file, "preset large biomes\nbiome: sparse jungle 0 64 0\n");
        Result largeBiomes = run("full-seed", "12345", "--file", file.toString(), "--threads", "2");
        assertTrue(largeBiomes.exitCode() != 2 && largeBiomes.out().contains("against 1 biome note"), largeBiomes.out() + largeBiomes.err());

        java.nio.file.Files.writeString(file, "preset tiny\n");
        Result unknownPreset = run("full-seed", "12345", "--file", file.toString());
        assertEquals(2, unknownPreset.exitCode());
        assertTrue(unknownPreset.err().contains("unknown world preset 'tiny'"), unknownPreset.err());
        assertFalse(unknownPreset.err().contains("Write each structure"), unknownPreset.err());
    }

    @Test
    void aFewNetherBlocksNarrowTheStructureSeeds(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws java.io.IOException {
        List<String> layers = run("bedrock", "--seed", "12345", "--dimension", "nether", "--layers").out().lines().toList();
        int header = layers.indexOf("bedrock-layer nether 0 4 0");
        assertTrue(header >= 0, String.join("\n", layers));
        List<String> lines = new java.util.ArrayList<>(List.of("desert_pyramid 272 80", "swamp_hut 256 -144", "village 336 80",
            "shipwreck -128 32", "ruined_portal 16 0"));
        lines.addAll(layers.subList(header, header + 3)); // the header and 2 rows: 32 blocks, too few to search on their own
        java.nio.file.Path file = folder.resolve("notes.txt");
        java.nio.file.Files.write(file, lines);
        Result result = run(find("--file", file.toString()));
        assertTrue(result.out().contains("only 12345 fits all 32 bedrock blocks"), result.out());
        assertTrue(result.out().contains("it is 5794443870565576761."), result.out());
    }

    @Test
    void findLeavesOutAStructureThatDoesntFit(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws java.io.IOException {
        java.nio.file.Path file = folder.resolve("notes.txt");
        String structures = String.join("\n", EXAMPLE).replace(':', ' ').replace(',', ' ').replace("village 336 80", "village 352 80");
        java.nio.file.Files.writeString(file, structures + "\nhashed-seed " + io.github.jriiik.seedfinder.Seeds.hashedSeed(12345L) + "\n");
        Result result = run(find("--file", file.toString()));
        assertEquals(0, result.exitCode(), result.out() + result.err());
        assertTrue(result.out().contains("Found the full seed: 12345\n"), result.out());
        assertTrue(result.out().contains("villages at block (352, 80) doesn't fit it"), result.out());
    }

    /**
     * World 8071994503331311257 (structure seed 136596199829145) with its ruined portal noted a chunk off: these six
     * structures fit only the wrong structure seed 7421670452889, one of whose world seeds also fits this overworld chunk.
     */
    private static final long MISNOTED_WORLD = 8071994503331311257L;
    private static final List<String> MISNOTED_STRUCTURES = List.of("desert_pyramid -176 112", "swamp_hut 80 -208", "village 288 80",
        "shipwreck 48 80", "ruined_portal 256 256", "ocean_ruin 112 128");

    private static String netherNote(long seed, int x, int y, int z) {
        io.github.jriiik.seedfinder.version.VersionData data = io.github.jriiik.seedfinder.version.MinecraftVersion.V26_3.data();
        boolean bedrock = new io.github.jriiik.seedfinder.bedrock.BedrockPattern(data.bedrockDimension("nether").orElseThrow(), seed).isBedrock(x, y, z);
        return (bedrock ? "bedrock" : "not-bedrock") + " nether " + x + " " + y + " " + z;
    }

    private static List<String> layers(String dimension, long seed, int minX, int minZ, int... ys) {
        io.github.jriiik.seedfinder.version.VersionData data = io.github.jriiik.seedfinder.version.MinecraftVersion.V26_3.data();
        io.github.jriiik.seedfinder.bedrock.BedrockPattern pattern = new io.github.jriiik.seedfinder.bedrock.BedrockPattern(
            data.bedrockDimension(dimension).orElseThrow(), seed);
        List<String> lines = new java.util.ArrayList<>();
        for (int y : ys) {
            lines.addAll(io.github.jriiik.seedfinder.bedrock.BedrockText.layer(pattern, minX, y, minZ, 16, 16));
        }
        return lines;
    }

    @Test
    void netherNotesAcrossAChunkBorderStillRuleOutAWrongStructureSeed(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder)
        throws java.io.IOException {
        List<String> nether = new java.util.ArrayList<>();
        int[][] spots = {{48, 4, -28}, {48, 1, -26}, {48, 4, -25}, {48, 1, -21}, {48, 1, -19}, {48, 1, -18}, {49, 4, -28}, {49, 1, -25},
            {49, 1, -21}, {49, 1, -19}, {50, 1, -32}, {50, 1, -30}, {47, 4, -28}};
        for (int[] spot : spots) {
            nether.add(netherNote(MISNOTED_WORLD, spot[0], spot[1], spot[2]));
        }
        List<String> overworld = layers("overworld", MISNOTED_WORLD, -3792, 320, -63, -62, -61, -60);
        java.nio.file.Path file = folder.resolve("notes.txt");
        for (List<String> notes : List.of(nether, new java.util.ArrayList<>(layers("nether", MISNOTED_WORLD, 48, -32, 1, 2, 3, 4)))) {
            if (notes != nether) {
                notes.add(netherNote(MISNOTED_WORLD, 47, 4, -28)); // a whole floor chunk, plus one block in the next chunk
            }
            List<String> lines = new java.util.ArrayList<>(MISNOTED_STRUCTURES);
            lines.addAll(notes);
            lines.addAll(overworld);
            java.nio.file.Files.write(file, lines);
            Result result = run(find("--file", file.toString()));
            assertFalse(result.out().contains("Found the full seed"), result.out());
            assertTrue(result.out().contains("Your nether bedrock doesn't fit"), result.out());
            assertEquals(1, result.exitCode());
            Result fullSeed = run("full-seed", "7421670452889", "--threads", "2", "--file", file.toString());
            assertFalse(fullSeed.out().contains("Found the full seed"), fullSeed.out());
            assertEquals(1, fullSeed.exitCode());
        }
    }

    @Test
    void oneMisreadRoofBlockDoesntPickTheWrongFloorTwin(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws java.io.IOException {
        // 136596199829145 (the world's) and 109940606698184 have the same nether floor everywhere: only the roof tells
        // them apart. One roof block noted wrong fits the other one.
        long right = 136596199829145L;
        long twin = 109940606698184L;
        List<String> lines = new java.util.ArrayList<>(layers("nether", right, 48, -32, 1, 2, 3, 4));
        lines.add(netherNote(right, 48, 125, -32));
        String misread = null;
        for (int x = 80; x < 96 && misread == null; x++) {
            if (netherNote(right, x, 123, -32).startsWith("not") && !netherNote(twin, x, 123, -32).startsWith("not")) {
                misread = "bedrock nether " + x + " 123 -32";
            }
        }
        assertTrue(misread != null);
        lines.add(misread);
        java.nio.file.Path file = folder.resolve("notes.txt");
        java.nio.file.Files.write(file, lines);
        Result result = run("full-seed", Long.toString(twin), Long.toString(right), "--threads", "2", "--file", file.toString());
        assertFalse(result.out().contains("only " + twin + " fits"), result.out());
        assertTrue(result.out().contains("so all of them are kept"), result.out());
        assertFalse(result.out().contains("it is "), "no likely seed of either may be named: " + result.out());
    }

    // ------------------------------------------------------------------------------------- Minecraft 1.21.11 worlds

    @Test
    void versionsListsEverySupportedVersion() {
        Result result = run("versions");
        assertEquals(0, result.exitCode(), result.err());
        assertTrue(result.out().contains("  1.21.11\n"), result.out());
        assertTrue(result.out().contains("  26.3  (newest)\n"), result.out());
        assertTrue(result.out().contains("'minecraft 1.21.11'"), result.out());
    }

    /** The lines of an output from its first line that starts with the given text. */
    private static List<String> from(String out, String start) {
        return out.lines().dropWhile(line -> !line.startsWith(start)).toList();
    }

    @Test
    void structuresLocateAndBedrockWorkFor1_21_11() {
        Result structures = run("structures", "--minecraft", "1.21.11");
        assertEquals(0, structures.exitCode(), structures.err());
        assertTrue(structures.out().contains("Structure types in Minecraft 1.21.11"), structures.out());
        assertTrue(structures.out().contains("villages"), structures.out());
        assertFalse(structures.out().contains("abandoned_camp"), "abandoned camps are new in 26.x: " + structures.out());

        // Structures shared by both versions are placed the same way.
        Result locate = run("locate", "--seed", "12345", "-m", "1.21.11");
        assertEquals(0, locate.exitCode(), locate.err());
        assertTrue(locate.out().startsWith("Minecraft 1.21.11, seed 12345, overworld"), locate.out());
        assertFalse(locate.out().contains("abandoned_camp"), locate.out());
        List<String> newer = from(run("locate", "--seed", "12345", "villages", "desert_pyramids").out(), "  Structure");
        assertEquals(newer, from(run("locate", "--seed", "12345", "-m", "1.21.11", "villages", "desert_pyramids").out(), "  Structure"));
        Result camps = run("locate", "--seed", "12345", "-m", "1.21.11", "abandoned_camp");
        assertEquals(2, camps.exitCode());
        assertTrue(camps.err().contains("Minecraft 1.21.11 has no abandoned_camp (only Minecraft 26.3 has them)"), camps.err());
        assertEquals(0, run("locate", "--seed", "12345", "-m", "1.21.11", "--dimension", "nether").exitCode());

        // The bedrock is the same too, and the printed notes name their version.
        Result bedrock = run("bedrock", "--seed", "12345", "--minecraft", "1.21.11");
        assertEquals(0, bedrock.exitCode(), bedrock.err());
        assertTrue(bedrock.out().contains("(Minecraft 1.21.11)"), bedrock.out());
        assertTrue(bedrock.out().contains("\nminecraft 1.21.11\nbedrock-heights 0 0\n"), bedrock.out());
        assertEquals(from(run("bedrock", "--seed", "12345").out(), "bedrock-heights"), from(bedrock.out(), "bedrock-heights"));
        assertTrue(run("bedrock", "--seed", "12345").out().contains("\nminecraft 26.3\nbedrock-heights 0 0\n"));
    }

    @Test
    void biomesOf1_21_11GiveTheFullSeedWithTheirVersionLine(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws java.io.IOException {
        Result biomes = run("biomes", "--seed", "12345", "--at", "-96,-96", "--size", "48", "--minecraft", "1.21.11");
        assertEquals(0, biomes.exitCode(), biomes.err());
        assertTrue(biomes.out().contains("(Minecraft 1.21.11, normal preset)"), biomes.out());
        assertTrue(biomes.out().contains("\nminecraft 1.21.11\n"), biomes.out());
        java.nio.file.Path file = folder.resolve("notes.txt");
        java.nio.file.Files.writeString(file, String.join("\n", EXAMPLE).replace(':', ' ').replace(',', ' ') + "\n\n" + biomes.out());

        // The notes' own line picks the version, without --minecraft.
        Result result = run(find("--file", file.toString()));
        assertEquals(0, result.exitCode(), result.out() + result.err());
        assertTrue(result.out().contains("Minecraft 1.21.11, 6 structures:"), result.out());
        assertTrue(result.out().contains("Found the full seed: 12345\n"), result.out());

        Result fullSeed = run("full-seed", "12345", "--threads", "2", "--file", file.toString());
        assertEquals(0, fullSeed.exitCode(), fullSeed.out() + fullSeed.err());
        assertTrue(fullSeed.out().startsWith("Minecraft 1.21.11.\n"), fullSeed.out());
        assertTrue(fullSeed.out().contains("Found the full seed: 12345\n"), fullSeed.out());

        // --minecraft that agrees is fine; one that contradicts the notes is refused with the reason.
        assertEquals(0, run(find("--file", file.toString(), "--minecraft", "1.21.11")).exitCode());
        Result contradiction = run(find("--file", file.toString(), "--minecraft", "26.3"));
        assertEquals(2, contradiction.exitCode());
        assertTrue(contradiction.err().contains("The notes say they are from Minecraft 1.21.11 (their 'minecraft 1.21.11' line), but --minecraft"
            + " says 26.3."), contradiction.err());
        assertEquals(2, run("full-seed", "12345", "--file", file.toString(), "-m", "26.3").exitCode());
        assertEquals(2, run("typed-seed", "--file", file.toString(), "-m", "26.3").exitCode());
    }

    @Test
    void notesWithoutAVersionLineTakeTheOption(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws java.io.IOException {
        Result result = run(find(java.util.stream.Stream.concat(java.util.stream.Stream.of("-m", "1.21.11"), java.util.Arrays.stream(EXAMPLE))
            .toArray(String[]::new)));
        assertEquals(0, result.exitCode(), result.err());
        assertTrue(result.out().contains("Minecraft 1.21.11, 6 structures:"), result.out());
        assertTrue(result.out().contains("Found the structure seed: 12345\n"), result.out());

        // A version line among the command-line structures counts as well.
        result = run(find(java.util.stream.Stream.concat(java.util.Arrays.stream(EXAMPLE), java.util.stream.Stream.of("minecraft 1.21.11"))
            .toArray(String[]::new)));
        assertEquals(0, result.exitCode(), result.err());
        assertTrue(result.out().contains("Minecraft 1.21.11, 6 structures:"), result.out());
    }

    @Test
    void badVersionLinesAreReportedWithTheirLine(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws java.io.IOException {
        java.nio.file.Path file = folder.resolve("notes.txt");
        java.nio.file.Files.writeString(file, "village 336 80\nminecraft 1.20.1\n");
        Result unknown = run(find("--file", file.toString()));
        assertEquals(2, unknown.exitCode());
        assertTrue(unknown.err().contains("line 2 of " + file + ": Minecraft 1.20.1 is not supported (supported: 1.21.11, 26.3)."), unknown.err());
        assertFalse(unknown.err().contains("igloo:48,208"), "not a structure line: " + unknown.err());

        java.nio.file.Files.writeString(file, "minecraft 1.21.11\nvillage 336 80\nminecraft 26.3\n");
        Result twice = run("full-seed", "12345", "--file", file.toString());
        assertEquals(2, twice.exitCode());
        assertTrue(twice.err().contains("line 3 of " + file + ": this line says Minecraft 26.3, but an earlier line says 1.21.11"), twice.err());

        // A 1.21.11 world has no abandoned camps.
        java.nio.file.Files.writeString(file, "minecraft 1.21.11\nabandoned_camp 80 144\n");
        Result camp = run(find("--file", file.toString()));
        assertEquals(2, camp.exitCode());
        assertTrue(camp.err().contains("line 2 of " + file + ": Minecraft 1.21.11 has no structure 'abandoned_camp' (only Minecraft 26.3 has it)"),
            camp.err());
        assertEquals(2, run("find", "abandoned_camp:80,144", "-m", "1.21.11").exitCode());
        assertTrue(run(find("abandoned_camp:80,144", "village:336,80")).out().contains("Minecraft 26.3"), "26.3 has them");
    }

    @Test
    void typedSeedReadsA1_21_11NotesFile(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws java.io.IOException {
        java.nio.file.Path notes = notes12345(folder, List.of("minecraft 1.21.11", "village 336 80", "desert_pyramid 272 80", "swamp_hut 256 -144",
            "shipwreck -128 32", "ruined_portal 16 0", "trial_chamber 0 224"));
        Result result = typedSeed(notes);
        assertEquals(0, result.exitCode(), result.out() + result.err());
        assertTrue(result.out().contains("Found the world seed: 12345\n"), result.out());
        assertTrue(result.out().contains("The notes confirm it: "), result.out());
    }
}
