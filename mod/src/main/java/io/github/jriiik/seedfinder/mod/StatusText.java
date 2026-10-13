package io.github.jriiik.seedfinder.mod;

import io.github.jriiik.seedfinder.finder.FullSeedFinder;
import io.github.jriiik.seedfinder.finder.SeedCheck;
import io.github.jriiik.seedfinder.finder.WorldSeedSearch;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Collectors;

/** The mod's status in plain words, for its screen and the one-line display. */
final class StatusText {

    private StatusText() {
    }

    static List<String> lines(WorldSession session, SearchRunner runner) {
        List<String> lines = new ArrayList<>();
        lines.add("World: " + session.displayName());
        if (!session.collecting()) {
            lines.add("Collecting is switched off in this world, so nothing is noted here.");
            lines.add("Switch it on only for your own worlds or where the server allows looking for the seed.");
            lines.add("Each world has its own notes; worlds behind one address (a lobby and a game world, a reset world) are told");
            lines.add("apart by the hashed seed the server sends.");
            return lines;
        }
        List<WorldSession.NotedStructure> structures = session.structures();
        long recognized = structures.stream().filter(s -> s.source().equals(StructureRecognition.SOURCE)).count();
        lines.add("Structures: " + structures.size() + (structures.isEmpty() ? "" : " (" + structures.stream()
            .map(WorldSession.NotedStructure::set).distinct().collect(Collectors.joining(", ")) + ")")
            + (recognized > 0 ? ", " + recognized + " recognized automatically" : ""));
        List<String> missing = StructureRecognition.missingSets();
        if (!missing.isEmpty()) {
            lines.add("Could not prepare recognition of " + String.join(", ", missing) + " (see the game log).");
        }
        lines.addAll(session.disagreements());
        lines.add("Bedrock: " + chunks(session.bedrockChunks("minecraft:overworld"), "overworld") + ", "
            + chunks(session.bedrockChunks("minecraft:the_nether"), "nether"));
        lines.add("Hashed seed from the server: " + (session.hashedSeed().isPresent() ? "received" : "not yet"));
        TypedSeedRunner typedSeeds = SeedFinderClient.typedSeeds();
        if (session.worldSeed().isEmpty() && session.typedSeed().isEmpty()) {
            if (typedSeeds.runningFor(session)) {
                lines.add("Checking whether the seed was typed (as text or a short number): " + typedSeeds.percent() + "%");
            } else if (typedSeeds.failedFor(session)) {
                lines.add("The check whether the seed was typed stopped with an error (see the game log). It starts again when you come back.");
            } else if (session.typedSeedSearchDone()) {
                lines.add("No seed typed as text or a short number has the hashed seed this server sent (all 4,294,967,296 were checked):"
                    + " the seed was picked by Minecraft or typed as a longer number, or the server sends a made-up hashed seed.");
            }
        }
        lines.add("");
        WorldSeedSearch.Result result = session.result();
        OptionalLong typed = session.typedSeed();
        if (typed.isPresent() && (result == null || result.worldSeed().isEmpty()) && typedSeedLines(session, typed.getAsLong(), lines)) {
            return lines;
        }
        if (runner.running()) {
            lines.add("Searching..." + (runner.percent() >= 0 ? " " + runner.percent() + "%" : ""));
            return lines;
        }
        if (session.resultError() != null) {
            lines.add("The search stopped with an error: " + session.resultError());
            return lines;
        }
        if (result == null) {
            lines.add(structures.isEmpty()
                ? "Villages, outposts, trial chambers, ancient cities, trail ruins and camps are recognized when you come near. Pyramids,"
                    + " jungle temples and swamp huts you can add yourself (\"Add a structure here\"). Answers to /locate (if you may use it) are noted too."
                : "About to search...");
            return lines;
        }
        if (!session.resultIsCurrent()) {
            lines.add("(New notes since this result; it will be updated.)");
        }
        switch (result.stage()) {
            case NEED_MORE_STRUCTURES -> {
                lines.add("Not enough to search yet:");
                result.plan().problems().forEach(problem -> lines.add("- " + problem));
            }
            case NO_STRUCTURE_SEED -> {
                lines.add("No seed fits these structures together. Possible reasons: a structure noted in the wrong chunk, structures");
                lines.add("in chunks made by an older Minecraft version, structures placed by a command or copied, or a server that");
                lines.add("places structures differently (then the seed can't be found from structures). Remove the ones you doubt.");
            }
            case STRUCTURE_SEED -> structureSeedLines(result, lines);
            case WORLD_SEED -> {
                lines.add("World seed: " + result.worldSeed().getAsLong());
                lines.add(result.proof().orElseThrow() == WorldSeedSearch.Proof.HASHED_SEED
                    ? "Certain: it is the only seed that fits the structures and the hashed seed the server sent."
                    : "Certain: it is the only seed that fits the structures and the bedrock floor.");
                result.leftOut().ifPresent(misfit -> {
                    lines.add("Left out: " + misfit + " doesn't fit this seed. It may come from an older Minecraft version, a command");
                    lines.add("or a copy (or was noted in the wrong place). Remove it on \"Remove a structure\".");
                });
                if (result.bedrockDisagrees()) {
                    lines.add("Note: some of the bedrock seen doesn't fit this seed (the server may change blocks it sends).");
                }
            }
        }
        return lines;
    }

    /**
     * What the typed seed is, judged by the notes. Returns false when the notes contradict it: then the world seed has
     * to come from the seed search, whose status follows.
     */
    private static boolean typedSeedLines(WorldSession session, long seed, List<String> lines) {
        Optional<SeedCheck> found = session.typedSeedCheck();
        if (found.isEmpty()) {
            lines.add("The hashed seed the server sent is the one of the typed seed " + seed + ". Checking it against the notes...");
            return true;
        }
        SeedCheck check = found.get();
        if (session.hashedSeedIsGenuine()) {
            lines.add("World seed: " + seed);
            lines.add("Certain: it was typed (as text or as this number), and its hash is the hashed seed of this single-player world.");
            if (!check.misfits().isEmpty()) {
                boolean one = check.misfits().size() == 1;
                lines.add("Note: " + misfits(check) + " fit this seed: " + (one ? "it" : "they") + " may come from an older Minecraft version,"
                    + " a command or a copy (or " + (one ? "was" : "were") + " noted in the wrong place).");
            }
            if (check.bedrockMismatches() > 0) {
                lines.add("Note: some of the bedrock seen doesn't fit this seed (it may have been changed).");
            }
            return true;
        }
        switch (check.verdict()) {
            case CONFIRMED -> {
                lines.add("World seed: " + seed);
                lines.add("Certain: it was typed (as text or as this number), its hash is the hashed seed the server sent, and "
                    + agreeing(check) + ".");
                if (!check.misfits().isEmpty()) {
                    boolean one = check.misfits().size() == 1;
                    lines.add("Note: " + misfits(check) + " fit this seed. The server may place structures differently, or "
                        + (one ? "it comes" : "they come") + " from an older Minecraft version, a command or a copy (or "
                        + (one ? "was" : "were") + " noted in the wrong place).");
                }
                if (check.bedrockMismatches() > 0) {
                    lines.add("Note: some of the bedrock seen doesn't fit this seed (the server may change blocks it sends).");
                }
                return true;
            }
            case UNCONFIRMED -> {
                lines.add("World seed: " + seed);
                lines.add("It was typed (as text or as this number), and its hash is the hashed seed the server sent. That is certain unless"
                    + " the server sends a made-up hashed seed; bedrock and structures noted later will confirm it.");
                return true;
            }
            case CONTRADICTED -> {
                lines.add("The hashed seed the server sent is the one of the typed seed " + seed + ", but " + disagreeing(check) + ": "
                    + (check.bedrockMismatches() > 0
                        ? "the server probably sends a made-up hashed seed, so the world seed has to come from structures and bedrock instead."
                        : "the server may send a made-up hashed seed, or place structures differently. Bedrock from the overworld would tell which."));
                return false;
            }
        }
        return false;
    }

    /** What fits the seed: "the bedrock seen fits it", "the bedrock seen and 2 of the 3 structures noted fit it", ... */
    static String agreeing(SeedCheck check) {
        List<String> parts = new ArrayList<>();
        if (check.bedrockBlocks() > 0 && check.bedrockMismatches() == 0) {
            parts.add("the bedrock seen");
        }
        if (!check.fitting().isEmpty()) {
            parts.add(structures(check.fitting().size(), check.fitting().size() + check.misfits().size()));
        }
        return String.join(" and ", parts) + (parts.size() > 1 || check.fitting().size() > 1 ? " fit it" : " fits it");
    }

    /** What doesn't fit the seed: "the structure noted doesn't fit it", "1 of the 2 structures noted and the bedrock seen don't fit it", ... */
    static String disagreeing(SeedCheck check) {
        List<String> parts = new ArrayList<>();
        if (!check.misfits().isEmpty()) {
            parts.add(structures(check.misfits().size(), check.fitting().size() + check.misfits().size()));
        }
        if (check.bedrockMismatches() > 0) {
            parts.add("the bedrock seen");
        }
        return String.join(" and ", parts) + (parts.size() > 1 || check.misfits().size() > 1 ? " don't fit it" : " doesn't fit it");
    }

    /** "the structure noted", "the 3 structures noted", "2 of the 3 structures noted". */
    private static String structures(int some, int all) {
        return some < all ? some + " of the " + all + " structures noted" : all == 1 ? "the structure noted" : "the " + all + " structures noted";
    }

    /** The structures that don't fit, with the verb: "villages at block (352, 80) doesn't", "2 of the structures noted don't". */
    private static String misfits(SeedCheck check) {
        return check.misfits().size() == 1 ? check.misfits().get(0) + " doesn't" : check.misfits().size() + " of the structures noted don't";
    }

    private static void structureSeedLines(WorldSeedSearch.Result result, List<String> lines) {
        List<Long> seeds = result.structureSeeds();
        if (seeds.size() == 1) {
            lines.add("Structure seed: " + seeds.get(0) + " (the last 48 bits of the world seed)");
        } else {
            lines.add(seeds.size() + " structure seeds fit. More structures tell them apart"
                + (result.plan().suggestedStructures().isEmpty() ? "." : ", best: " + String.join(", ", result.plan().suggestedStructures()) + "."));
        }
        if (result.hashedSeedFitsNone()) {
            lines.add("The hashed seed from the server fits none of them: this server may send a different value.");
        }
        if (!result.bedrockProblems().isEmpty()) {
            lines.add("Some bedrock seen breaks the game's rules, so bedrock isn't used: " + result.bedrockProblems().get(0));
        } else if (result.bedrock().isPresent()) {
            FullSeedFinder.Result bedrock = result.bedrock().get();
            List<FullSeedFinder.Candidate> best = bedrock.best();
            if (best.isEmpty() || best.get(0).mismatches() > 0) {
                lines.add("No world seed fits all of the bedrock seen.");
            } else if (bedrock.perfectFits() > 1) {
                lines.add(String.format(Locale.ROOT, "%,d world seeds fit the bedrock so far; more bedrock is noted as you explore.", bedrock.perfectFits()));
            } else {
                lines.add("One world seed fits the bedrock, " + best.get(0).worldSeed() + ", but more bedrock is needed to be sure.");
            }
        } else {
            lines.add("The world seed follows from bedrock: it is noted when you are near the bottom of the world.");
        }
        List<Long> likely = result.likelySeeds();
        if (!likely.isEmpty()) {
            lines.add("If nobody typed a seed, it is probably " + likely.stream().map(String::valueOf).collect(Collectors.joining(" or ")) + ".");
        }
    }

    /** One short line for the corner of the screen. */
    static String statusLine(WorldSession session, SearchRunner runner) {
        OptionalLong seed = session.worldSeed();
        if (seed.isPresent()) {
            boolean unconfirmed = (session.result() == null || session.result().worldSeed().isEmpty()) && !session.hashedSeedIsGenuine()
                && session.typedSeedCheck().map(check -> check.verdict() == SeedCheck.Verdict.UNCONFIRMED).orElse(false);
            return "Seed Finder: world seed " + seed.getAsLong() + (unconfirmed ? " (not confirmed yet)" : "");
        }
        if (runner.running()) {
            return "Seed Finder: searching" + (runner.percent() >= 0 ? " " + runner.percent() + "%" : "...");
        }
        WorldSeedSearch.Result result = session.result();
        String notes = session.structures().size() + " structures, " + session.bedrockChunks("minecraft:overworld") + " bedrock chunks"
            + (SeedFinderClient.typedSeeds().runningFor(session) ? ", checking typed seeds " + SeedFinderClient.typedSeeds().percent() + "%" : "");
        if (result == null) {
            return "Seed Finder: " + notes;
        }
        return switch (result.stage()) {
            case NEED_MORE_STRUCTURES -> "Seed Finder: " + notes + ", need more structures";
            case NO_STRUCTURE_SEED -> "Seed Finder: no seed fits the structures";
            case STRUCTURE_SEED -> "Seed Finder: " + (result.structureSeeds().size() == 1
                ? "structure seed " + result.structureSeeds().get(0) : result.structureSeeds().size() + " structure seeds") + ", " + notes;
            case WORLD_SEED -> "Seed Finder: world seed " + result.worldSeed().getAsLong();
        };
    }

    /** The best seed to copy: the world seed, else a single structure seed. */
    static OptionalLong seedToCopy(WorldSession session) {
        if (session.worldSeed().isPresent()) {
            return session.worldSeed();
        }
        WorldSeedSearch.Result result = session.result();
        return result != null && result.structureSeeds().size() == 1 ? OptionalLong.of(result.structureSeeds().get(0)) : OptionalLong.empty();
    }

    private static String chunks(int count, String dimension) {
        return count + " " + dimension + " chunk" + (count == 1 ? "" : "s");
    }
}
