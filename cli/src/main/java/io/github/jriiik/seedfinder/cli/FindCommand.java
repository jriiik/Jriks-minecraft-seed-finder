package io.github.jriiik.seedfinder.cli;

import io.github.jriiik.seedfinder.bedrock.BedrockEvidence;
import io.github.jriiik.seedfinder.finder.FullSeedFinder;
import io.github.jriiik.seedfinder.finder.ObservationReader;
import io.github.jriiik.seedfinder.finder.SearchPlan;
import io.github.jriiik.seedfinder.finder.StructureObservation;
import io.github.jriiik.seedfinder.finder.StructureSeedFinder;
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
            + "floor of one chunk to the file (see 'seedfinder full-seed --help'); without it, the likely full seeds are named.",
    },
    footer = {
        "",
        "Examples:",
        "  seedfinder find desert_pyramid:272,80 swamp_hut:256,-144 village:336,80 shipwreck:-128,32 ruined_portal:16,0 trial_chamber:0,224",
        "  seedfinder find --file my-structures.txt",
        "",
        "In a file, write one structure per line (\"igloo 48 208\"). Lines copied from /locate work too.",
        "Bedrock notes can go in the same file.",
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
        VersionData data = version.resolve(commandLine).data();
        ObservationInput input = ObservationInput.of(commandLine, file, observationTexts);
        if (input.isEmpty()) {
            throw new CommandLine.ParameterException(commandLine, "Tell me which structures you saw, for example: seedfinder find igloo:48,208 village:336,80");
        }
        ObservationReader.Observations read = input.read(commandLine, data);
        List<StructureObservation> observations = read.structures();
        BedrockEvidence bedrock = new BedrockEvidence(read.bedrock());
        int threadCount = Threads.resolve(commandLine, threads);
        if (observations.isEmpty() && read.bedrock().isEmpty() && read.hashedSeed().isPresent()) {
            out.println("There is a hashed seed but no structures yet. If the world's seed was typed (as text or a short number),");
            out.println("the hashed seed alone gives it: seedfinder typed-seed " + read.hashedSeed().getAsLong());
            return 1;
        }
        if (observations.isEmpty() && read.bedrock().isEmpty()) {
            throw new CommandLine.ParameterException(commandLine, "Tell me which structures you saw, for example: seedfinder find igloo:48,208 village:336,80");
        }
        if (observations.isEmpty()) {
            out.println("There are bedrock notes but no structures. Bedrock alone can't find the structure seed yet:");
            out.println("add about 6 structures, or if you know the structure seed, use 'seedfinder full-seed <structure seed>'.");
            return 1;
        }

        StructureSeedFinder finder = new StructureSeedFinder(data, observations);
        SearchPlan plan = finder.plan();
        out.printf("Minecraft %s, %d structure%s:%n", data.version(), plan.observations().size(), plan.observations().size() == 1 ? "" : "s");
        for (StructureObservation observation : plan.observations()) {
            out.printf("  %-20s starts in the chunk at block (%d, %d)%n",
                observation.set().shortId(), observation.chunk().minBlockX(), observation.chunk().minBlockZ());
        }
        out.println();
        int bedrockBlocks = bedrock.fullSeedObservations().size() + bedrock.structureSeedObservations().size();
        if (!read.bedrock().isEmpty()) {
            out.printf(Locale.ROOT, "And %,d bedrock block%s that can help find the full seed.%n%n", bedrockBlocks, bedrockBlocks == 1 ? "" : "s");
        }
        plan.warnings().forEach(warning -> out.println("Note: " + warning));
        bedrock.problems().forEach(problem -> out.println("Can't search yet: " + problem + "."));
        if (!plan.canSearch()) {
            plan.problems().forEach(problem -> out.println("Can't search yet: " + problem));
            read.hashedSeed().ifPresent(hashed -> out.println("If the world's seed was typed (as text or a short number), the hashed seed alone "
                + "gives it: seedfinder typed-seed " + hashed));
        }
        if (!plan.canSearch() || !bedrock.problems().isEmpty()) {
            return 1;
        }

        double estimate = plan.seedsToTest() / (SEEDS_PER_SECOND_PER_THREAD * threadCount);
        out.printf(Locale.ROOT, "Searching %,d candidate seeds on %d thread%s%s...%n", plan.seedsToTest(), threadCount,
            threadCount == 1 ? "" : "s", estimate >= 3 ? " (about " + duration(estimate) + ")" : "");
        out.flush();
        long start = System.nanoTime();
        List<Long> found = finder.search(threadCount, new ProgressPrinter(commandLine.getErr()));
        double seconds = (System.nanoTime() - start) / 1e9;
        out.printf("Done in %s.%n%n", duration(seconds));
        report(out, found, plan.suggestedStructures());
        if (found.isEmpty()) {
            return 1;
        }
        out.println();
        boolean named = false;
        if (found.size() <= FullSeedFinder.MAX_STRUCTURE_SEEDS) {
            named = FullSeedReport.run(out, data, found, bedrock, read.hashedSeed(), threadCount);
        } else if (!read.bedrock().isEmpty()) {
            out.println("Too many structure seeds to check the bedrock against; add more structures first.");
        }
        out.println();
        out.println("To compare with a world whose seed you know: seedfinder structure-seed <that world's seed>");
        // Without bedrock, finding the structure seed is the result; with bedrock, the full seed has to come out too.
        return read.bedrock().isEmpty() || named ? 0 : 1;
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
}
