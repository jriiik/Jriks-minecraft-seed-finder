package io.github.jriiik.seedfinder.cli;

import io.github.jriiik.seedfinder.Seeds;
import io.github.jriiik.seedfinder.bedrock.BedrockDimension;
import io.github.jriiik.seedfinder.bedrock.BedrockEvidence;
import io.github.jriiik.seedfinder.bedrock.BedrockObservation;
import io.github.jriiik.seedfinder.bedrock.BedrockPattern;
import io.github.jriiik.seedfinder.finder.FullSeedFinder;
import io.github.jriiik.seedfinder.finder.FullSeedFinder.Candidate;
import io.github.jriiik.seedfinder.finder.FullSeedFinder.Origin;
import io.github.jriiik.seedfinder.finder.WorldSeedSearch;
import io.github.jriiik.seedfinder.version.VersionData;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Runs the full-seed search for 'find' and 'full-seed' and explains the result in plain words. */
final class FullSeedReport {

    private static final int MAX_MISTAKES_SHOWN = 5;
    private static final int COLUMNS_SUGGESTED = 5;
    /** When the next best seed gets at most this many more blocks wrong, the answer is called close. */
    private static final int CLOSE_MARGIN = 2;
    /** With more structure seeds than this, say that the bedrock check can take a while. */
    private static final int MANY_STRUCTURE_SEEDS = 20;

    private FullSeedReport() {
    }

    /** Searches and prints; returns true when at least one world seed could be named. */
    static boolean run(PrintWriter out, VersionData data, List<Long> foundSeeds, BedrockEvidence evidence, OptionalLong hashedSeed,
                       int threads) {
        List<Long> structureSeeds = new ArrayList<>(new LinkedHashSet<>(foundSeeds));
        if (hashedSeed.isPresent() && structureSeeds.size() <= WorldSeedSearch.MAX_HASHED_SEED_CHECKS) {
            long[] matches = Seeds.withHashedSeed(structureSeeds, hashedSeed.getAsLong());
            if (matches.length == 1) {
                out.println("Found the full seed: " + matches[0]);
                out.println("It is the only seed with these structures whose hash is the hashed seed in your notes.");
                List<BedrockObservation> wrong = evidence.problems().isEmpty() ? evidence.mismatchesOf(matches[0]) : List.of();
                if (!wrong.isEmpty()) {
                    out.printf("But it gets %d of your bedrock blocks wrong, for example:%n", wrong.size());
                    wrong.stream().limit(MAX_MISTAKES_SHOWN).forEach(observation -> out.println("  " + mistake(observation)));
                }
                return true;
            }
            out.println("None of the possible seeds has the hashed seed in your notes (the server may send a different value),");
            out.println("so it is not used.");
        }
        if (!evidence.structureSeedObservations().isEmpty()) {
            structureSeeds = narrowByNether(out, structureSeeds, evidence);
            if (structureSeeds.isEmpty()) {
                return false;
            }
        }
        if (evidence.fullSeedObservations().isEmpty()) {
            return reportLikelySeeds(out, structureSeeds);
        }
        if (structureSeeds.size() > MANY_STRUCTURE_SEEDS) {
            out.printf("Checking the bedrock against %d structure seeds, this can take a while...%n", structureSeeds.size());
            out.flush();
        }
        FullSeedFinder.Result result = new FullSeedFinder(structureSeeds, evidence).search(threads);
        return reportBedrockSearch(out, data, structureSeeds, evidence, result);
    }

    /** Nether bedrock only depends on the structure seed: keep the structure seeds that fit it. */
    private static List<Long> narrowByNether(PrintWriter out, List<Long> structureSeeds, BedrockEvidence evidence) {
        int count = evidence.structureSeedObservations().size();
        int[] wrong = structureSeeds.stream().mapToInt(evidence::structureSeedMismatches).toArray();
        int best = 0;
        List<Long> fitting = new ArrayList<>();
        for (int i = 0; i < structureSeeds.size(); i++) {
            best = wrong[i] < wrong[best] ? i : best;
            if (wrong[i] == 0) {
                fitting.add(structureSeeds.get(i));
            }
        }
        int fewest = wrong[best];
        if (fewest > 0) {
            long structureSeed = structureSeeds.get(best);
            out.printf("Your nether bedrock doesn't fit: %s gets %d of %s wrong:%n",
                structureSeeds.size() == 1 ? "the structure seed" : "the closest structure seed, " + structureSeed + ",", fewest, blocks(count));
            evidence.mismatchesOf(structureSeed).stream()
                .filter(observation -> !observation.dimension().dependsOnFullSeed())
                .limit(MAX_MISTAKES_SHOWN)
                .forEach(observation -> out.println("  " + mistake(observation)));
            out.println("Please check these blocks in the game. If they are right, the structure seed is wrong.");
            return List.of();
        }
        if (structureSeeds.size() == 1) {
            out.printf("Nether bedrock: the structure seed fits all %s.%n", blocks(count));
        } else if (fitting.size() == 1) {
            out.printf("Nether bedrock: of the %d structure seeds, only %d fits all %s.%n", structureSeeds.size(), fitting.get(0), blocks(count));
        } else {
            out.printf("Nether bedrock: %d of the %d structure seeds fit all %s.%n", fitting.size(), structureSeeds.size(), blocks(count));
        }
        return fitting;
    }

    private static boolean reportLikelySeeds(PrintWriter out, List<Long> structureSeeds) {
        if (structureSeeds.size() > 1) {
            out.println("Bedrock heights from the overworld can tell these structure seeds apart and give the full world seed at");
            out.println("the same time (see 'seedfinder full-seed --help').");
            return false;
        }
        long structureSeed = structureSeeds.get(0);
        long[] random = Seeds.randomSeedCandidates(structureSeed);
        OptionalLong text = Seeds.textSeedCandidate(structureSeed);
        out.println("The full world seed (the structure seed is its last 48 bits; 65,536 world seeds share them):");
        if (random.length == 0) {
            out.println("  Minecraft never picks a seed with this structure seed by itself, so the seed was typed in.");
        } else {
            out.println("  If nobody typed a seed when the world was created (Minecraft then picks one), it is "
                + Arrays.stream(random).mapToObj(String::valueOf).collect(Collectors.joining(" or ")) + ".");
        }
        if (text.isPresent()) {
            out.println("  If the seed was typed as text (like \"My World\") or as a short number, it is " + text.getAsLong() + ".");
        }
        out.println("  To be sure, or for other typed seeds, add the bedrock floor of one chunk: see 'seedfinder full-seed --help'.");
        return random.length > 0 || text.isPresent();
    }

    private static boolean reportBedrockSearch(PrintWriter out, VersionData data, List<Long> structureSeeds, BedrockEvidence evidence,
                                               FullSeedFinder.Result result) {
        int informative = evidence.fullSeedObservations().size();
        String theseSeeds = structureSeeds.size() == 1 ? "this structure seed" : "these structure seeds";
        out.printf(Locale.ROOT, "Checked all %,d world seeds with %s against %s.%n", result.seedsChecked(), theseSeeds, blocks(informative));
        List<Candidate> candidates = result.candidates();
        List<Candidate> best = result.best();
        Candidate top = candidates.get(0);
        Candidate runnerUp = candidates.size() > best.size() ? candidates.get(best.size()) : null;
        BedrockDimension overworld = data.bedrockDimension("overworld").orElseThrow();

        if (top.mismatches() > 0) {
            // Even a wrong structure seed has full seeds that get only a few blocks wrong, so this is no answer yet.
            if (top.mismatches() > Math.max(4, informative / 10)) {
                out.printf("No world seed fits your bedrock: the closest one gets %d of %s wrong.%n", top.mismatches(), blocks(informative));
                out.println("Check that the heights are noted correctly, and the x and z of the north-west corner. Bedrock inside");
                out.println("mineshafts and strongholds can differ. If the structure seed is wrong, no full seed fits.");
                return false;
            }
            List<BedrockObservation> blamed = evidence.mismatchesOf(top.worldSeed());
            if (best.size() == 1) {
                out.printf("No world seed fits all of your bedrock. The closest, %d, gets %d of %s wrong:%n",
                    top.worldSeed(), top.mismatches(), blocks(informative));
            } else {
                for (Candidate tied : best.subList(1, best.size())) {
                    blamed.retainAll(evidence.mismatchesOf(tied.worldSeed()));
                }
                out.printf("No world seed fits all of your bedrock. %s world seeds each get %d of %s wrong%s%n",
                    best.size() == FullSeedFinder.MAX_CANDIDATES ? "At least " + best.size() : best.size(), top.mismatches(),
                    blocks(informative), blamed.isEmpty() ? ", each different ones." : ". All of them disagree with these:");
            }
            blamed.stream().limit(MAX_MISTAKES_SHOWN).forEach(observation -> out.println("  " + mistake(observation)));
            out.println("Please check " + (blamed.isEmpty() ? "your notes" : "these blocks") + " in the game. If something was noted down wrong,"
                + " fix it and run this again.");
            out.println("If the notes are right, the structure seed is probably wrong, or the world's generation was changed (mods,");
            out.println("datapacks or an older Minecraft version).");
            return false;
        }
        if (best.size() > 1) {
            if (result.perfectFits() > FullSeedFinder.MAX_CANDIDATES) {
                out.printf(Locale.ROOT, "%,d world seeds fit all %s. Note a bigger area: the bedrock floor of a whole chunk%n",
                    result.perfectFits(), blocks(informative));
                out.println("(16 x 16 columns) is nearly always enough.");
                return false;
            }
            out.printf("%d world seeds fit all of your bedrock:%n", best.size());
            for (int i = 0; i < best.size(); i++) {
                out.println("  " + label(i) + "  " + best.get(i).worldSeed() + marker(best.get(i)));
            }
            out.println("To tell them apart, add the top bedrock height of these columns:");
            printColumns(out, overworld, best.stream().map(Candidate::worldSeed).toList(), evidence);
            return true;
        }

        out.println("Found the full seed: " + top.worldSeed());
        out.println("It fits all " + blocks(informative) + ", and no other seed with " + theseSeeds + " does"
            + (runnerUp == null ? "." : " (the next best gets " + runnerUp.mismatches() + " wrong)."));
        out.println(originNote(top));
        double falseFits = FullSeedFinder.expectedFalseFits(informative);
        if (falseFits >= 0.5) {
            out.println("Careful: with this little bedrock, a wrong structure seed would often also have a seed that fits, so");
            out.println("this is only right if the structure seed is. Note more columns (a whole chunk) to be sure.");
        } else if (falseFits >= 0.02) {
            out.printf(Locale.ROOT, "With this much bedrock, a wrong structure seed would also have a fitting seed about once in %d tries,%n",
                Math.max(2, Math.round(1 / falseFits)));
            out.println("so this relies on the structure seed being right. A whole chunk of bedrock heights makes it certain.");
        }
        if (runnerUp != null && runnerUp.mismatches() <= CLOSE_MARGIN) {
            out.println("Another seed is close: B = " + runnerUp.worldSeed() + " (A = the seed found). To be sure, add the top");
            out.println("bedrock height of these columns:");
            printColumns(out, overworld, List.of(top.worldSeed(), runnerUp.worldSeed()), evidence);
        }
        return true;
    }

    private static void printColumns(PrintWriter out, BedrockDimension overworld, List<Long> seeds, BedrockEvidence evidence) {
        List<int[]> columns = FullSeedFinder.distinguishingColumns(overworld, seeds, evidence.observations(), COLUMNS_SUGGESTED);
        List<BedrockPattern> patterns = seeds.stream().map(seed -> new BedrockPattern(overworld, seed)).toList();
        for (int[] column : columns) {
            Map<Integer, List<String>> seedsByHeight = new TreeMap<>(Comparator.reverseOrder());
            for (int i = 0; i < seeds.size(); i++) {
                seedsByHeight.computeIfAbsent(patterns.get(i).floorTop(column[0], column[1]), y -> new ArrayList<>()).add(label(i));
            }
            List<String> predictions = new ArrayList<>();
            seedsByHeight.forEach((y, labels) -> predictions.add("y=" + y + " for " + String.join(", ", labels)));
            out.println("  x=" + column[0] + ", z=" + column[1] + ": " + String.join("; ", predictions));
        }
    }

    /** A, B, C... for listing seeds. */
    private static String label(int index) {
        return index < 26 ? String.valueOf((char) ('A' + index)) : "#" + (index + 1);
    }

    private static String mistake(BedrockObservation observation) {
        String at = "(" + observation.x() + ", " + observation.y() + ", " + observation.z() + ")"
            + (observation.dimension().id().equals("minecraft:overworld") ? "" : " in the " + observation.dimension().displayName());
        return observation.bedrock() ? "your notes say bedrock at " + at + ", this seed says no bedrock"
            : "your notes say no bedrock at " + at + ", this seed says bedrock";
    }

    private static String originNote(Candidate candidate) {
        return switch (candidate.origin()) {
            case RANDOM -> candidate.couldBeTyped()
                ? "Minecraft can pick this seed by itself, and typing this number (or some text) gives it too."
                : "Minecraft can pick this seed by itself, which fits a world created without typing a seed.";
            case TEXT -> "This seed can come from typing text (or this number) as the seed.";
            case TYPED_NUMBER -> "Minecraft never picks this seed by itself, so it was typed in as a number (or chosen by a server host).";
        };
    }

    private static String marker(Candidate candidate) {
        if (candidate.origin() == Origin.RANDOM) {
            return candidate.couldBeTyped() ? "  (Minecraft can pick this one by itself; typing it works too)" : "  (Minecraft can pick this one by itself)";
        }
        return candidate.origin() == Origin.TEXT ? "  (can come from typing text)" : "";
    }

    private static String blocks(int count) {
        return String.format(Locale.ROOT, "%,d bedrock block%s", count, count == 1 ? "" : "s");
    }
}
