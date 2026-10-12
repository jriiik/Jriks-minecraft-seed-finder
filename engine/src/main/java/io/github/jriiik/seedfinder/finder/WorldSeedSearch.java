package io.github.jriiik.seedfinder.finder;

import io.github.jriiik.seedfinder.Seeds;
import io.github.jriiik.seedfinder.bedrock.BedrockEvidence;
import io.github.jriiik.seedfinder.bedrock.BedrockObservation;
import io.github.jriiik.seedfinder.version.VersionData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Everything from observations to the world seed in one go, for the mod: the structure seed from structures, then
 * the full seed from the hashed seed the server sent, from bedrock, or the likely seeds when neither settles it.
 *
 * <p>Steps that can't run yet (too few structures, too little bedrock) are reported, not treated as errors.
 */
public final class WorldSeedSearch {

    /**
     * Checking all 65,536 seeds of a structure seed against the hashed seed takes 5 to 30 ms; above this many
     * structure seeds only the likely seeds (random or typed) are checked.
     */
    public static final int MAX_HASHED_SEED_CHECKS = 500;

    /** How far the search got. */
    public enum Stage {
        /** The structures don't allow a search yet; see {@link Result#plan()}. */
        NEED_MORE_STRUCTURES,
        /** No structure seed fits the structures. */
        NO_STRUCTURE_SEED,
        /** The structure seed (or several) is known, the full seed is not certain yet. */
        STRUCTURE_SEED,
        /** The full world seed is known. */
        WORLD_SEED,
    }

    /** How the full seed was found. */
    public enum Proof {
        /** It is the only seed whose hash is the hashed seed the server sent. */
        HASHED_SEED,
        /** It is the only seed that fits all of the bedrock, and enough bedrock was seen to rule out chance. */
        BEDROCK,
    }

    /**
     * @param plan           the structure search plan (why it can't run, suggestions)
     * @param structureSeeds the structure seeds that fit the structures (empty before the search ran)
     * @param worldSeed      the world seed, once it is certain
     * @param proof          how the world seed was found
     * @param hashedSeedFitsNone true when a hashed seed was given but no candidate has it (the server may send a
     *                       different value, or the structure seed is wrong)
     * @param bedrock        the bedrock search, when there was overworld bedrock to check
     * @param bedrockProblems notes that contradict the game's bedrock rules whatever the seed
     * @param bedrockDisagrees true when the world seed found from the hashed seed gets some bedrock wrong
     * @param likelySeeds    without proof: the seeds Minecraft could have picked itself or a text seed would give
     */
    public record Result(
        Stage stage,
        SearchPlan plan,
        List<Long> structureSeeds,
        OptionalLong worldSeed,
        Optional<Proof> proof,
        boolean hashedSeedFitsNone,
        Optional<FullSeedFinder.Result> bedrock,
        List<String> bedrockProblems,
        boolean bedrockDisagrees,
        List<Long> likelySeeds
    ) {
    }

    private WorldSeedSearch() {
    }

    /**
     * The first step on its own: the structure seeds that fit the structures (empty when the plan says the search
     * can't run). It is the slow part, so the mod keeps its result while only bedrock or the hashed seed change.
     */
    public record StructureStep(SearchPlan plan, List<Long> structureSeeds) {

        public StructureStep {
            structureSeeds = List.copyOf(structureSeeds);
        }
    }

    public static StructureStep structureSeeds(VersionData data, List<StructureObservation> structures, int threads,
                                               StructureSeedFinder.ProgressListener progress) {
        StructureSeedFinder finder = new StructureSeedFinder(data, structures);
        SearchPlan plan = finder.plan();
        return new StructureStep(plan, plan.canSearch() ? finder.search(threads, progress) : List.of());
    }

    public static Result run(VersionData data, List<StructureObservation> structures, List<BedrockObservation> bedrockNotes,
                             OptionalLong hashedSeed, int threads, StructureSeedFinder.ProgressListener progress) {
        return fullSeed(structureSeeds(data, structures, threads, progress), bedrockNotes, hashedSeed, threads);
    }

    /** The second step: the full seed from the hashed seed or bedrock, or the likely seeds. */
    public static Result fullSeed(StructureStep step, List<BedrockObservation> bedrockNotes, OptionalLong hashedSeed, int threads) {
        SearchPlan plan = step.plan();
        List<Long> structureSeeds = step.structureSeeds();
        BedrockEvidence evidence = new BedrockEvidence(bedrockNotes);
        List<String> bedrockProblems = evidence.problems();
        if (!plan.canSearch()) {
            return new Result(Stage.NEED_MORE_STRUCTURES, plan, List.of(), OptionalLong.empty(), Optional.empty(), false,
                Optional.empty(), bedrockProblems, false, List.of());
        }
        if (structureSeeds.isEmpty()) {
            return new Result(Stage.NO_STRUCTURE_SEED, plan, structureSeeds, OptionalLong.empty(), Optional.empty(), false,
                Optional.empty(), bedrockProblems, false, List.of());
        }

        boolean hashedSeedFitsNone = false;
        if (hashedSeed.isPresent()) {
            long[] matches = Seeds.likelyWithHashedSeed(structureSeeds, hashedSeed.getAsLong());
            boolean checkedAll = matches.length == 0 && structureSeeds.size() <= MAX_HASHED_SEED_CHECKS;
            if (checkedAll) {
                matches = Seeds.withHashedSeed(structureSeeds, hashedSeed.getAsLong());
            }
            if (matches.length == 1) {
                long worldSeed = matches[0];
                boolean disagrees = bedrockProblems.isEmpty() && !evidence.mismatchesOf(worldSeed).isEmpty();
                return new Result(Stage.WORLD_SEED, plan, structureSeeds, OptionalLong.of(worldSeed), Optional.of(Proof.HASHED_SEED),
                    false, Optional.empty(), bedrockProblems, disagrees, List.of());
            }
            hashedSeedFitsNone = checkedAll && matches.length == 0;
        }

        Optional<FullSeedFinder.Result> bedrock = Optional.empty();
        if (bedrockProblems.isEmpty() && !evidence.fullSeedObservations().isEmpty()
            && structureSeeds.size() <= FullSeedFinder.MAX_STRUCTURE_SEEDS) {
            FullSeedFinder.Result found = new FullSeedFinder(structureSeeds, evidence).search(threads);
            bedrock = Optional.of(found);
            List<FullSeedFinder.Candidate> best = found.best();
            boolean certain = best.size() == 1 && best.get(0).mismatches() == 0
                && FullSeedFinder.expectedFalseFits(evidence.fullSeedObservations().size()) < 0.02;
            if (certain) {
                return new Result(Stage.WORLD_SEED, plan, structureSeeds, OptionalLong.of(best.get(0).worldSeed()), Optional.of(Proof.BEDROCK),
                    hashedSeedFitsNone, bedrock, bedrockProblems, false, List.of());
            }
        }

        List<Long> likely = new ArrayList<>();
        if (structureSeeds.size() == 1) {
            Arrays.stream(Seeds.randomSeedCandidates(structureSeeds.get(0))).forEach(likely::add);
            Seeds.textSeedCandidate(structureSeeds.get(0)).ifPresent(seed -> {
                if (!likely.contains(seed)) {
                    likely.add(seed);
                }
            });
        }
        return new Result(Stage.STRUCTURE_SEED, plan, structureSeeds, OptionalLong.empty(), Optional.empty(), hashedSeedFitsNone,
            bedrock, bedrockProblems, false, likely);
    }
}
