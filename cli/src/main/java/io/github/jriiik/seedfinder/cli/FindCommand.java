package io.github.jriiik.seedfinder.cli;

import io.github.jriiik.seedfinder.bedrock.BedrockEvidence;
import io.github.jriiik.seedfinder.finder.FullSeedFinder;
import io.github.jriiik.seedfinder.finder.NetherBedrockSearch;
import io.github.jriiik.seedfinder.finder.ObservationReader;
import io.github.jriiik.seedfinder.finder.SearchPlan;
import io.github.jriiik.seedfinder.finder.SeedCheck;
import io.github.jriiik.seedfinder.finder.StructureObservation;
import io.github.jriiik.seedfinder.finder.StructureSeedFinder;
import io.github.jriiik.seedfinder.finder.WorldSeedSearch;
import io.github.jriiik.seedfinder.version.MinecraftVersion;
import io.github.jriiik.seedfinder.version.VersionData;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "find",
    mixinStandardHelpOptions = true,
    description = {
        "Work out a world's seed from structures you have seen in it, and optionally its bedrock.",
        "Give each structure's type and the block position of the chunk where it starts: /locate shows exactly that. "
            + "About 6 structures of mixed types are usually enough: at least one temple, pillager outpost, shipwreck, "
            + "ocean ruin or village to start the search, plus a ruined portal, abandoned camp, trial chamber or ancient "
            + "city to rule out look-alike seeds.",
        "The structures give the structure seed (the last 48 bits of the world seed). For the full seed, add the bedrock "
            + "floor of one chunk or the biomes of a place to the file (see 'seedfinder full-seed --help'); without them, the likely "
            + "full seeds are named.",
    },
    footer = {
        "",
        "Examples:",
        "  seedfinder find desert_pyramid:272,80 swamp_hut:256,-144 village:336,80 shipwreck:-128,32 ruined_portal:16,0 trial_chamber:0,224",
        "  seedfinder find --file my-structures.txt",
        "",
        "In a file, write one structure per line (\"igloo 48 208\"). Lines copied from /locate work too.",
        "Bedrock and biome notes can go in the same file.",
    }
)
final class FindCommand implements Callable<Integer> {

    /** Rough search speed of one thread, for the time estimate. */
    private static final double SEEDS_PER_SECOND_PER_THREAD = 1.5e8;
    private static final int MAX_SEEDS_SHOWN = 20;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @CommandLine.Parameters(paramLabel = "<observation>", arity = "0..*",
        description = "A structure and where it starts, like igloo:48,208 (block x,z).")
    List<String> observationTexts = new ArrayList<>();

    @CommandLine.Option(names = {"-f", "--file"}, paramLabel = "<file>",
        description = "Read the structures from a text file, one per line.")
    Path file;

    @CommandLine.Option(names = "--threads", paramLabel = "<count>", description = "How many CPU threads to use (default: all of them).")
    int threads;

    @CommandLine.Mixin
    VersionOption version = new VersionOption();

    @Override
    public Integer call() {
        CommandLine commandLine = spec.commandLine();
        PrintWriter out = commandLine.getOut();
        MinecraftVersion minecraft = version.resolve(commandLine);
        VersionData data = minecraft.data();
        ObservationInput input = ObservationInput.of(commandLine, file, observationTexts);
        if (input.isEmpty()) {
            throw new CommandLine.ParameterException(commandLine, "Tell me which structures you saw, for example: seedfinder find igloo:48,208 village:336,80");
        }
        ObservationReader.Observations read = input.read(commandLine, data);
        List<StructureObservation> observations = read.structures();
        BedrockEvidence bedrock = new BedrockEvidence(read.bedrock());
        int threadCount = Threads.resolve(commandLine, threads);
        boolean nether = read.bedrock().stream().anyMatch(note -> note.dimension().id().equals(NetherBedrockSearch.NETHER));
        if (observations.isEmpty() && read.bedrock().isEmpty() && read.hashedSeed().isPresent()) {
            out.println("There is a hashed seed but no structures yet. If the world's seed was typed (as text or a short number),");
            out.println("the hashed seed alone gives it: " + typedSeedCommand(read.hashedSeed().getAsLong()));
            return 1;
        }
        if (observations.isEmpty() && read.bedrock().isEmpty() && !read.biomes().isEmpty()) {
            out.println("There are biome notes but no structures. Biomes can give the full seed once the structure seed is known: add");
            out.println("about 6 structures, or if you know the structure seed, use 'seedfinder full-seed <structure seed> --file <notes>'.");
            return 1;
        }
        if (observations.isEmpty() && read.bedrock().isEmpty()) {
            throw new CommandLine.ParameterException(commandLine, "Tell me which structures you saw, for example: seedfinder find igloo:48,208 village:336,80");
        }
        if (observations.isEmpty() && !nether) {
            out.println("There are bedrock notes but no structures. Overworld bedrock alone can't find the structure seed: add about 6");
            out.println("structures, or the nether's bedrock floor and roof of one chunk, or if you know the structure seed, use");
            out.println("'seedfinder full-seed <structure seed>'.");
            read.hashedSeed().ifPresent(hashed -> out.println("If the world's seed was typed (as text or a short number), the hashed seed gives it"
                + " and the bedrock checks it: " + typedSeedCommand(hashed)));
            return 1;
        }

        StructureSeedFinder finder = new StructureSeedFinder(data, observations);
        SearchPlan plan = finder.plan();
        if (observations.isEmpty()) {
            out.printf("Minecraft %s, no structures.%n", data.version());
        } else {
            out.printf("Minecraft %s, %d structure%s:%n", data.version(), plan.observations().size(), plan.observations().size() == 1 ? "" : "s");
            for (StructureObservation observation : plan.observations()) {
                out.printf("  %-20s starts in the chunk at block (%d, %d)%n",
                    observation.set().shortId(), observation.chunk().minBlockX(), observation.chunk().minBlockZ());
            }
        }
        out.println();
        int bedrockBlocks = bedrock.fullSeedObservations().size() + bedrock.structureSeedObservations().size();
        if (!read.bedrock().isEmpty()) {
            out.printf(Locale.ROOT, "And %,d bedrock block%s that can help find the %s.%n%n", bedrockBlocks, bedrockBlocks == 1 ? "" : "s",
                nether ? "structure seed and the full seed" : "full seed");
        }
        FullSeedReport.Biomes biomes = FullSeedReport.Biomes.of(out, read, minecraft);
        if (biomes != null) {
            int notes = biomes.evidence().noted();
            out.printf(Locale.ROOT, "And %,d biome note%s in %d place%s that can help find the full seed.%n%n", notes, notes == 1 ? "" : "s",
                biomes.evidence().places(), biomes.evidence().places() == 1 ? "" : "s");
        }
        plan.warnings().forEach(warning -> out.println("Note: " + warning));
        bedrock.problems().forEach(problem -> out.println("Can't search yet: " + problem + "."));
        if (!bedrock.problems().isEmpty()) {
            return 1;
        }
        if (!observations.isEmpty() && !plan.canSearch()) {
            plan.problems().forEach(problem -> out.println("Can't search yet: " + problem));
            if (nether) {
                out.println("The nether bedrock is searched instead.");
            } else {
                read.hashedSeed().ifPresent(hashed -> out.println("If the world's seed was typed (as text or a short number), the hashed seed alone "
                    + "gives it: " + typedSeedCommand(hashed)));
                return 1;
            }
            out.println();
        }

        List<Long> found = List.of();
        if (plan.canSearch()) {
            double estimate = plan.seedsToTest() / (SEEDS_PER_SECOND_PER_THREAD * threadCount);
            out.printf(Locale.ROOT, "Searching %,d candidate seeds on %d thread%s%s...%n", plan.seedsToTest(), threadCount,
                threadCount == 1 ? "" : "s", estimate >= 3 ? " (about " + duration(estimate) + ")" : "");
            out.flush();
            long start = System.nanoTime();
            found = finder.search(threadCount, new ProgressPrinter(commandLine.getErr()));
            double seconds = (System.nanoTime() - start) / 1e9;
            out.printf("Done in %s.%n%n", duration(seconds));
            report(out, found, plan.suggestedStructures());
            out.println();
        }
        WorldSeedSearch.StructureStep step = new WorldSeedSearch.StructureStep(data, plan, found);
        if (nether) {
            step = step.withNether(searchNether(out, commandLine.getErr(), data, read, threadCount));
            reportCombined(out, step, found);
        }
        if (!step.searched() || step.structureSeeds().isEmpty()) {
            return 1;
        }
        List<Long> seeds = step.structureSeeds();
        FullSeedReport.Outcome outcome = FullSeedReport.Outcome.NOTHING;
        if (seeds.size() <= FullSeedFinder.MAX_STRUCTURE_SEEDS) {
            // After the nether search only the overworld bedrock is left to check (the nether's was used).
            BedrockEvidence forFullSeed = new BedrockEvidence(WorldSeedSearch.bedrockForFullSeed(read.bedrock(), step.nether()));
            outcome = FullSeedReport.run(out, data, seeds, forFullSeed, biomes, read.hashedSeed(), threadCount);
            if (outcome.worldSeed().isPresent()) {
                List<StructureObservation> misfits = SeedCheck.misfits(data, plan.observations(), outcome.worldSeed().getAsLong());
                for (StructureObservation misfit : misfits) {
                    out.printf("%s at block (%d, %d) doesn't fit this seed: noted in the wrong place, from chunks made by an older Minecraft%n",
                        misfit.set().shortId(), misfit.chunk().minBlockX(), misfit.chunk().minBlockZ());
                    out.println("version, or placed by a command or copied.");
                }
            }
        } else if (!read.bedrock().isEmpty() || biomes != null) {
            out.println("Too many structure seeds to check the bedrock or biomes against; add more structures first.");
        }
        out.println();
        out.println("To compare with a world whose seed you know: seedfinder structure-seed <that world's seed>");
        // Without overworld bedrock or biomes, finding the structure seed is the result; with them, the full seed has to
        // come out too.
        boolean fullSeedNotes = !WorldSeedSearch.bedrockForFullSeed(read.bedrock(), step.nether()).isEmpty() || biomes != null;
        boolean structureSeedFound = !found.isEmpty() && !step.netherDisagrees() || step.nether().map(NetherBedrockSearch.Finding::proven).orElse(false);
        return outcome.named() || !fullSeedNotes && structureSeedFound ? 0 : 1;
    }

    /** Searches the nether bedrock and says what came out. */
    private static NetherBedrockSearch.Finding searchNether(PrintWriter out, PrintWriter err, VersionData data, ObservationReader.Observations read,
                                                            int threads) {
        NetherBedrockSearch search = new NetherBedrockSearch(data);
        NetherBedrockSearch.Plan plan = search.plan(read.bedrock());
        out.printf(Locale.ROOT, "Nether bedrock: %,d floor and %,d roof blocks that tell something.%n", plan.floorBlocks(), plan.roofBlocks());
        plan.problems().forEach(problem -> out.println("Left out: " + problem + "."));
        if (!plan.canSearch()) {
            out.println("That is not enough to search yet. Note the nether's bedrock floor (y 1 to 4) and roof (y 123 to 126) of one chunk:");
            out.println("'bedrock-layer nether x y z' followed by rows of B (bedrock) and . (netherrack), see 'seedfinder full-seed --help'.");
            out.println("Bedrock at y 4 or y 123, and gaps at y 1 or y 126, tell the most.");
            out.println();
        } else {
            out.printf("Searching the nether bedrock on %d thread%s...%n", threads, threads == 1 ? "" : "s");
            out.flush();
        }
        long start = System.nanoTime();
        long[] lastPrint = {System.nanoTime()};
        NetherBedrockSearch.Finding finding = search.find(read.bedrock(), threads, () -> false, (index, pieces) -> {
            long now = System.nanoTime();
            if (now - lastPrint[0] > 500_000_000L) {
                lastPrint[0] = now;
                err.print("\rSearching" + (index > 0 ? " (try " + (index + 1) + ")" : "") + "... " + pieces * 100 / NetherBedrockSearch.PIECES + "%   ");
                err.flush();
            }
        });
        if (!plan.canSearch()) {
            return finding;
        }
        err.println();
        out.printf("Done in %s.%n%n", duration((System.nanoTime() - start) / 1e9));
        for (io.github.jriiik.seedfinder.world.ChunkPos chunk : finding.unusableChunks()) {
            out.printf("The nether bedrock of the chunk at block (%d, %d) fits no seed that fits the rest: that chunk was made by Minecraft%n",
                chunk.minBlockX(), chunk.minBlockZ());
            out.println("1.17 or older (its bedrock doesn't follow the seed), or something was noted wrong there. It is left out.");
        }
        List<Long> seeds = finding.structureSeeds();
        if (seeds.isEmpty()) {
            out.println("No structure seed fits the nether bedrock. Check the notes (rows go from north to south, each row from west");
            out.println("to east, below a line with the north-west block), and that the chunks were made by Minecraft 1.18 or newer.");
        } else if (finding.capped()) {
            out.printf(Locale.ROOT, "More than %,d structure seeds fit the nether bedrock, too many to go on with. Note both the floor%n", seeds.size());
            out.println("(y 1 to 4) and the roof (y 123 to 126) of a whole chunk.");
        } else if (finding.proven()) {
            out.println(seeds.size() == 1 ? "The nether bedrock gives the structure seed: " + seeds.get(0)
                : "The nether bedrock gives the structure seed " + seeds.get(0) + " or " + seeds.get(1)
                    + ": these two have the same nether bedrock everywhere.");
        } else {
            out.printf(Locale.ROOT, "%,d structure seeds fit the nether bedrock, so it is not certain yet:%n", seeds.size());
            seeds.stream().limit(MAX_SEEDS_SHOWN).forEach(seed -> out.println("  " + seed));
            if (seeds.size() > MAX_SEEDS_SHOWN) {
                out.printf("  ... and %d more%n", seeds.size() - MAX_SEEDS_SHOWN);
            }
            out.println("Note both the floor (y 1 to 4) and the roof (y 123 to 126) of a chunk to be sure, or add structures.");
        }
        out.println();
        return finding;
    }

    /** Says how the structures' and the nether's seeds go together, when both were searched. */
    private static void reportCombined(PrintWriter out, WorldSeedSearch.StructureStep step, List<Long> fromStructures) {
        if (!step.plan().canSearch() || step.nether().isEmpty() || step.nether().get().structureSeeds().isEmpty() || step.nether().get().capped()) {
            return;
        }
        List<Long> seeds = step.structureSeeds();
        if (step.netherDisagrees() && step.nether().get().proven()) {
            if (fromStructures.isEmpty()) {
                out.println("No seed fits all the structures, so a structure doesn't follow the seed (noted in the wrong place, from older");
                out.println("chunks, or placed by a command); the structure seed comes from the nether bedrock.");
            } else {
                out.println("The structures and the nether bedrock disagree: one side is wrong (a structure noted in the wrong place or from");
                out.println("older chunks, or nether chunks of another world). The seeds of both are checked below.");
            }
        } else if (step.netherDisagrees()) {
            out.println("Note: none of the structure seeds the structures fit is among those the nether bedrock fits (older nether chunks,");
            out.println("or a block noted wrong?). The structures' seeds are used.");
        } else if (seeds.size() < fromStructures.size()) {
            out.println(seeds.size() == 1 ? "Of these, only " + seeds.get(0) + " fits the nether bedrock too."
                : seeds.size() + " of these fit the nether bedrock too.");
        } else {
            out.println("The nether bedrock fits " + (seeds.size() == 1 ? "it" : "them") + " too.");
        }
        out.println();
    }

    private static void report(PrintWriter out, List<Long> found, List<String> suggestions) {
        if (found.isEmpty()) {
            out.println("No structure seed fits all of these structures. Check that each position is the chunk where the");
            out.println("structure starts, that the structure types are right, and that the world uses normal world");
            out.println("generation for this Minecraft version (no datapacks or mods that change it).");
            return;
        }
        if (found.size() == 1) {
            out.println("Found the structure seed: " + found.get(0));
        } else {
            out.printf(Locale.ROOT, "Found %d structure seeds that fit everything you entered:%n", found.size());
            found.stream().limit(MAX_SEEDS_SHOWN).forEach(seed -> out.println("  " + seed));
            if (found.size() > MAX_SEEDS_SHOWN) {
                out.printf("  ... and %d more%n", found.size() - MAX_SEEDS_SHOWN);
            }
            out.println(suggestions.isEmpty()
                ? "To tell them apart, add a few more structures, ideally of types you haven't used yet."
                : "To tell them apart, add a structure of one of these types: " + String.join(", ", suggestions) + ".");
        }
    }

    private static String duration(double seconds) {
        if (seconds < 1) {
            return "less than a second";
        }
        if (seconds < 120) {
            return Math.round(seconds) + " seconds";
        }
        return Math.round(seconds / 60) + " minutes";
    }

    /** Prints "Searching... 42%" on one line, at most every half second. */
    private static final class ProgressPrinter implements StructureSeedFinder.ProgressListener {
        private final PrintWriter err;
        private long lastPrint = System.nanoTime();
        private int lastPercent = -1;

        ProgressPrinter(PrintWriter err) {
            this.err = err;
        }

        @Override
        public synchronized void onProgress(long done, long total) {
            int percent = (int) (100 * done / total);
            long now = System.nanoTime();
            if (percent != lastPercent && (now - lastPrint > 500_000_000L || done == total)) {
                err.print("\rSearching... " + percent + "%");
                if (done == total) {
                    err.println();
                }
                err.flush();
                lastPercent = percent;
                lastPrint = now;
            }
        }
    }

    /** The typed-seed command for these notes: with the file, so the seed found is checked against them too. */
    private String typedSeedCommand(long hashedSeed) {
        return file != null ? "seedfinder typed-seed --file " + file : "seedfinder typed-seed " + hashedSeed;
    }
}
