package io.github.jriiik.seedfinder.mod;

import io.github.jriiik.seedfinder.finder.FullSeedFinder;
import io.github.jriiik.seedfinder.finder.WorldSeedSearch;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
        lines.add("");
        if (runner.running()) {
            lines.add("Searching..." + (runner.percent() >= 0 ? " " + runner.percent() + "%" : ""));
            return lines;
        }
        if (session.resultError() != null) {
            lines.add("The search stopped with an error: " + session.resultError());
            return lines;
        }
        WorldSeedSearch.Result result = session.result();
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
        if (runner.running()) {
            return "Seed Finder: searching" + (runner.percent() >= 0 ? " " + runner.percent() + "%" : "...");
        }
        WorldSeedSearch.Result result = session.result();
        OptionalLong seed = result == null ? OptionalLong.empty() : result.worldSeed();
        if (seed.isPresent()) {
            return "Seed Finder: world seed " + seed.getAsLong();
        }
        String notes = session.structures().size() + " structures, " + session.bedrockChunks("minecraft:overworld") + " bedrock chunks";
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
        WorldSeedSearch.Result result = session.result();
        if (result == null) {
            return OptionalLong.empty();
        }
        if (result.worldSeed().isPresent()) {
            return result.worldSeed();
        }
        return result.structureSeeds().size() == 1 ? OptionalLong.of(result.structureSeeds().get(0)) : OptionalLong.empty();
    }

    private static String chunks(int count, String dimension) {
        return count + " " + dimension + " chunk" + (count == 1 ? "" : "s");
    }
}
