package io.github.jriiik.seedfinder.cli;

import io.github.jriiik.seedfinder.Seeds;
import io.github.jriiik.seedfinder.finder.ObservationReader;
import io.github.jriiik.seedfinder.finder.SeedCheck;
import io.github.jriiik.seedfinder.finder.TypedSeedSearch;
import io.github.jriiik.seedfinder.version.VersionData;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "typed-seed",
    mixinStandardHelpOptions = true,
    description = {
        "Find a world's seed from its hashed seed alone, if the seed was typed: as text, or as a number from "
            + "-2147483648 to 2147483647.",
        "Minecraft sends every player a hashed seed (the mod notes it as a 'hashed-seed' line in its notes). All "
            + "4,294,967,296 seeds that can be typed that way are checked, smallest numbers first: that takes minutes, "
            + "or a moment for a small typed number. A seed Minecraft picked by itself can't be found this way; "
            + "use 'seedfinder find' with structures for those.",
        "With a notes file, the seed found is checked against the structures and bedrock in it: a server could send a "
            + "made-up hashed seed.",
    },
    footer = {
        "",
        "Examples:",
        "  seedfinder typed-seed 293737985876514017",
        "  seedfinder typed-seed --file my-notes.txt",
    }
)
final class TypedSeedCommand implements Callable<Integer> {

    /** Rough speed of one thread on a recent CPU (with SHA instructions), for the first estimate. */
    private static final double SEEDS_PER_SECOND_PER_THREAD = 1.5e7;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @CommandLine.Parameters(paramLabel = "<hashed seed>", arity = "0..1", description = "The hashed seed (a number).")
    String hashedSeedText;

    @CommandLine.Option(names = {"-f", "--file"}, paramLabel = "<file>",
        description = "Read the hashed seed from a notes file (a line 'hashed-seed <number>', as the mod writes it), and check the "
            + "seed found against the structures and bedrock in it.")
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
        Optional<ObservationReader.Observations> notes = notes(commandLine, data);
        long hashedSeed = notes.isPresent() ? notes.get().hashedSeed().getAsLong() : parsedHashedSeed(commandLine);
        int threadCount = Threads.resolve(commandLine, threads);

        long estimate = Math.round((1L << 32) / (SEEDS_PER_SECOND_PER_THREAD * threadCount));
        out.printf(Locale.ROOT, "Checking the 4,294,967,296 seeds that can be typed against hashed seed %d on %d thread%s. That takes about %s "
            + "on a recent CPU, several times longer on older ones.%n", hashedSeed, threadCount, threadCount == 1 ? "" : "s", duration(estimate));
        out.flush();
        PrintWriter err = commandLine.getErr();
        long startNanos = System.nanoTime();
        // The engine reports one piece at a time, so these need no lock of their own.
        int[] lastPercent = {-1};
        TypedSeedSearch.Outcome outcome = TypedSeedSearch.search(hashedSeed, 0, threadCount, () -> false, (checked, resumeFrom) -> {
            int percent = checked * 100 / TypedSeedSearch.PIECES;
            if (percent > lastPercent[0]) {
                lastPercent[0] = percent;
                double seconds = (System.nanoTime() - startNanos) / 1e9;
                long left = Math.round(seconds / checked * (TypedSeedSearch.PIECES - checked));
                // The first pieces run slower (Java is still warming up), so the time left is shown from 1% on.
                boolean showLeft = percent >= 1 && checked < TypedSeedSearch.PIECES;
                err.print("\rChecking... " + percent + "%" + (showLeft ? ", about " + duration(left) + " left   " : "                         "));
                err.flush();
            }
        });
        if (lastPercent[0] >= 0) {
            err.println();
        }
        if (!outcome.complete()) {
            out.println("The search stopped before every typed seed was checked, so there is no answer.");
            return 1;
        }
        if (outcome.seed().isEmpty()) {
            out.println("No seed that can be typed as text or a short number has this hashed seed. The world's seed was picked");
            out.println("by Minecraft (or typed as a longer number), or the server sends a made-up hashed seed. Use 'seedfinder find'");
            out.println("with structures instead.");
            return 1;
        }
        long worldSeed = outcome.seed().getAsLong();
        Optional<SeedCheck> check = notes.map(read -> SeedCheck.of(data, read.structures(), read.bedrock(), worldSeed));
        if (check.isPresent() && check.get().verdict() == SeedCheck.Verdict.CONTRADICTED) {
            out.println("The hashed seed is the one of the typed seed " + worldSeed + ", but the notes don't fit that seed (" + misfits(check.get()) + ").");
            if (check.get().bedrockMismatches() > 0) {
                out.println("The server probably sends a made-up hashed seed, so this is not the world's seed. Use 'seedfinder find' with the");
                out.println("structures and bedrock instead.");
            } else {
                out.println("The server may send a made-up hashed seed, or place structures differently. Bedrock notes from the overworld");
                out.println("would tell which.");
            }
            return 1;
        }
        out.println("Found the world seed: " + worldSeed);
        out.println("It was typed, either as the number " + worldSeed + " or as text: Minecraft turns text into a number like this,");
        out.println("and every text that gives the same number makes the same world. Its structure seed is " + Seeds.structureSeed(worldSeed) + ".");
        if (check.isEmpty()) {
            out.println("This is right unless the server sends a made-up hashed seed. To check it, give the notes with --file.");
        } else if (check.get().verdict() == SeedCheck.Verdict.UNCONFIRMED) {
            out.println("This is right unless the server sends a made-up hashed seed: the notes don't have enough structures or bedrock");
            out.println("yet to confirm it.");
        } else {
            SeedCheck confirmed = check.get();
            out.println("The notes confirm it: " + fits(confirmed) + " fit it.");
            if (!confirmed.misfits().isEmpty()) {
                out.println("These structures in the notes don't fit it: " + String.join(", ", confirmed.misfits().stream().map(Object::toString).toList())
                    + ". The server may place structures differently, or they come from an older Minecraft version, a command or a copy.");
            }
            if (confirmed.bedrockMismatches() > 0) {
                out.println(confirmed.bedrockMismatches() + " of the " + confirmed.bedrockBlocks() + " bedrock blocks in the notes don't fit it "
                    + "(the server may change blocks it sends).");
            }
        }
        return 0;
    }

    private Optional<ObservationReader.Observations> notes(CommandLine commandLine, VersionData data) {
        if ((hashedSeedText == null) == (file == null)) {
            throw new CommandLine.ParameterException(commandLine, hashedSeedText == null
                ? "Give the hashed seed, or a notes file with a 'hashed-seed' line (--file), for example: seedfinder typed-seed 293737985876514017"
                : "Give the hashed seed or a notes file (--file), not both");
        }
        if (file == null) {
            return Optional.empty();
        }
        ObservationReader.Observations read = ObservationInput.of(commandLine, file, List.of()).read(commandLine, data);
        if (read.hashedSeed().isEmpty()) {
            throw new CommandLine.ParameterException(commandLine, file + " has no 'hashed-seed' line");
        }
        return Optional.of(read);
    }

    private long parsedHashedSeed(CommandLine commandLine) {
        try {
            return Long.parseLong(hashedSeedText.trim());
        } catch (NumberFormatException e) {
            throw new CommandLine.ParameterException(commandLine, "The hashed seed must be a whole number, not '" + hashedSeedText + "'");
        }
    }

    /** What fits, e.g. "3 structures and 630 bedrock blocks". */
    private static String fits(SeedCheck check) {
        List<String> parts = new ArrayList<>();
        if (!check.fitting().isEmpty()) {
            parts.add(check.fitting().size() + " structure" + (check.fitting().size() == 1 ? "" : "s"));
        }
        if (check.bedrockBlocks() > 0 && check.bedrockMismatches() == 0) {
            parts.add(check.bedrockBlocks() + " bedrock blocks");
        }
        return String.join(" and ", parts);
    }

    /** What doesn't fit, e.g. "structures that don't fit: 2 of 5, bedrock blocks that don't fit: 410 of 630". */
    private static String misfits(SeedCheck check) {
        List<String> parts = new ArrayList<>();
        if (!check.misfits().isEmpty()) {
            parts.add("structures that don't fit: " + check.misfits().size() + " of " + (check.fitting().size() + check.misfits().size()));
        }
        if (check.bedrockMismatches() > 0) {
            parts.add("bedrock blocks that don't fit: " + check.bedrockMismatches() + " of " + check.bedrockBlocks());
        }
        return String.join(", ", parts);
    }

    /** "about 40 seconds", "about 1 minute", "about 5 minutes" without the "about". */
    static String duration(long seconds) {
        if (seconds < 90) {
            long rounded = Math.max(10, Math.round(seconds / 10.0) * 10);
            return rounded + " seconds";
        }
        long minutes = Math.round(seconds / 60.0);
        return minutes + " minute" + (minutes == 1 ? "" : "s");
    }
}
