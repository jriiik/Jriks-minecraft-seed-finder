package io.github.jriiik.seedfinder.cli;

import io.github.jriiik.seedfinder.bedrock.BedrockDimension;
import io.github.jriiik.seedfinder.bedrock.BedrockEvidence;
import io.github.jriiik.seedfinder.bedrock.BedrockObservation;
import io.github.jriiik.seedfinder.bedrock.BedrockPattern;
import io.github.jriiik.seedfinder.finder.FullSeedFinder;
import io.github.jriiik.seedfinder.finder.FullSeedFinder.Candidate;
import io.github.jriiik.seedfinder.finder.FullSeedFinder.Origin;
import io.github.jriiik.seedfinder.version.VersionData;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Runs the full-seed search for 'find' and 'full-seed' and explains the result in plain words. */
final class FullSeedReport {

    private static final int MAX_MISTAKES_SHOWN = 5;
    private static final int COLUMNS_SUGGESTED = 5;
    /** When the next best seed gets at most this many more blocks wrong, the answer is called close. */
    private static final int CLOSE_MARGIN = 2;

    private FullSeedReport() {
    }

    /** Searches and prints; returns true when at least one world seed could be named. */
    static boolean run(PrintWriter out, VersionData data, List<Long> structureSeeds, BedrockEvidence evidence, int threads) {
        FullSeedFinder.Result result = new FullSeedFinder(structureSeeds, evidence).search(threads);
        reportNetherBedrock(out, structureSeeds, evidence);
        if (!result.checkedAll()) {
            return reportLikelySeeds(out, structureSeeds, result);
        }
        return reportBedrockSearch(out, data, structureSeeds, evidence, result);
    }

    private static void reportNetherBedrock(PrintWriter out, List<Long> structureSeeds, BedrockEvidence evidence) {
        int count = evidence.structureSeedObservations().size();
        if (count == 0) {
            return;
        }
        for (long structureSeed : structureSeeds) {
            int wrong = evidence.structureSeedMismatches(structureSeed);
            out.printf(Locale.ROOT, "Nether bedrock: structure seed %d %s.%n", structureSeed,
                wrong == 0 ? "fits all " + blocks(count) : "gets " + wrong + " of " + blocks(count) + " wrong");
        }
    }

    private static boolean reportLikelySeeds(PrintWriter out, List<Long> structureSeeds, FullSeedFinder.Result result) {
        if (structureSeeds.size() > 1) {
            out.println("Bedrock heights from the overworld can tell these apart and give the full world seed at the same time");
            out.println("(see 'seedfinder full-seed --help').");
            return false;
        }
        List<Long> random = result.candidates().stream().filter(c -> c.origin() == Origin.RANDOM).map(Candidate::worldSeed).toList();
        List<Long> text = result.candidates().stream().filter(c -> c.origin() == Origin.TEXT).map(Candidate::worldSeed).toList();
        out.println("The full world seed (the structure seed is its last 48 bits; 65,536 world seeds share them):");
        if (random.isEmpty()) {
            out.println("  Minecraft never picks a seed with this structure seed by itself, so the seed was typed in.");
        } else {
            out.println("  If nobody typed a seed when the world was created (Minecraft then picks one), it is "
                + random.stream().map(String::valueOf).collect(Collectors.joining(" or ")) + ".");
        }
        if (!text.isEmpty()) {
            out.println("  If the seed was typed as text (like \"My World\") or as a short number, it is " + text.get(0) + ".");
        }
        out.println("  To be sure, or for other typed seeds, add the bedrock floor of one chunk: see 'seedfinder full-seed --help'.");
        return !result.candidates().isEmpty();
    }

    private static boolean reportBedrockSearch(PrintWriter out, VersionData data, List<Long> structureSeeds, BedrockEvidence evidence,
                                               FullSeedFinder.Result result) {
        int informative = evidence.fullSeedObservations().size();
        int observed = informative + evidence.structureSeedObservations().size();
        out.printf(Locale.ROOT, "Checked all %,d world seeds with %s against %s.%n", result.seedsChecked(),
            structureSeeds.size() == 1 ? "this structure seed" : "these structure seeds", blocks(observed));
        List<Candidate> candidates = result.candidates();
        List<Candidate> best = result.best();
        Candidate top = candidates.get(0);
        Candidate runnerUp = candidates.size() > best.size() ? candidates.get(best.size()) : null;
        BedrockDimension overworld = data.bedrockDimension("overworld").orElseThrow();

        if (top.mismatches() > 0) {
            // Even a wrong structure seed has full seeds that get only a few blocks wrong, so this is no answer yet.
            if (top.mismatches() > Math.max(4, observed / 10)) {
                out.printf("No world seed fits your bedrock: the closest one gets %d of %s wrong.%n", top.mismatches(), blocks(observed));
                out.println("Check that the heights are noted correctly, and the x and z of the north-west corner. Bedrock inside");
                out.println("mineshafts and strongholds can differ. If the structure seed is wrong, no full seed fits.");
                return false;
            }
            out.printf("No world seed fits all of your bedrock. The closest, %d, gets %d of %s wrong:%n",
                top.worldSeed(), top.mismatches(), blocks(observed));
            evidence.mismatchesOf(top.worldSeed()).stream().limit(MAX_MISTAKES_SHOWN)
                .forEach(observation -> out.println("  " + mistake(observation)));
            out.println("Please check these blocks in the game. If they were noted down wrong, fix them and run this again.");
            out.println("If they are right, the structure seed is probably wrong, or the world's generation was changed (mods,");
            out.println("datapacks or an older Minecraft version).");
            return false;
        }
        if (best.size() > 1) {
            if (result.perfectFits() > FullSeedFinder.MAX_CANDIDATES) {
                out.printf(Locale.ROOT, "%,d world seeds fit all %s. Note a bigger area: the bedrock floor of a whole chunk%n",
                    result.perfectFits(), blocks(observed));
                out.println("(16 x 16 columns) is nearly always enough.");
                return false;
            }
            out.printf("%d world seeds fit all of your bedrock:%n", best.size());
            for (int i = 0; i < best.size(); i++) {
                out.println("  " + label(i) + "  " + best.get(i).worldSeed() + marker(best.get(i).origin()));
            }
            out.println("To tell them apart, add the top bedrock height of these columns:");
            printColumns(out, overworld, best.stream().map(Candidate::worldSeed).toList(), evidence);
            return true;
        }

        out.println("Found the full seed: " + top.worldSeed());
        out.println("It fits all " + blocks(observed) + ", and no other seed with " + (structureSeeds.size() == 1 ? "this structure seed" : "these structure seeds")
            + " does" + (runnerUp == null ? "." : " (the next best gets " + runnerUp.mismatches() + " wrong)."));
        out.println(originNote(top.origin()));
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
        String at = "(" + observation.x() + ", " + observation.y() + ", " + observation.z() + ")";
        return observation.bedrock() ? "your notes say bedrock at " + at + ", this seed says no bedrock"
            : "your notes say no bedrock at " + at + ", this seed says bedrock";
    }

    private static String originNote(Origin origin) {
        return switch (origin) {
            case RANDOM -> "Minecraft can pick this seed by itself, which fits a world created without typing a seed.";
            case TEXT -> "This seed can come from typing text (or this number) as the seed.";
            case TYPED_NUMBER -> "Minecraft never picks this seed by itself, so it was typed in as a number (or chosen by a server host).";
        };
    }

    private static String marker(Origin origin) {
        return switch (origin) {
            case RANDOM -> "  (Minecraft can pick this one by itself)";
            case TEXT -> "  (can come from typing text)";
            case TYPED_NUMBER -> "";
        };
    }

    private static String blocks(int count) {
        return String.format(Locale.ROOT, "%,d bedrock block%s", count, count == 1 ? "" : "s");
    }
}
