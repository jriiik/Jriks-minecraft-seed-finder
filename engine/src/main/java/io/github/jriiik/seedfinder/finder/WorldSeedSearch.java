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
 * Everything from observations to the world seed in one go, for the mod: the structure seed from structures or from
 * nether bedrock, then the full seed from the hashed seed the server sent, from overworld bedrock or biomes, or the
 * likely seeds when none of them settles it.
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
        /** Neither the structures nor the nether bedrock allow a search yet; see {@link Result#plan()}. */
        NEED_MORE_STRUCTURES,
        /** No structure seed fits the structures (and the nether bedrock). */
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
        /** It is the only seed that fits all of the biomes, and they rule out chance (see {@link BiomeEvidence}). */
        BIOMES,
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
     * @param leftOut        a structure that doesn't fit the world seed and was left out (see {@link #withoutMisfit})
     * @param biomes         the biome check, when biomes were noted and checked
     * @param biomesDisagree true when the world seed found another way doesn't fit the biomes noted (under any preset)
     * @param nether         the nether bedrock search, when there was nether bedrock to search
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
        List<Long> likelySeeds,
        Optional<StructureObservation> leftOut,
        Optional<BiomeEvidence.Decision> biomes,
        boolean biomesDisagree,
        Optional<NetherBedrockSearch.Finding> nether
    ) {

        Result withLeftOut(StructureObservation structure) {
            return new Result(stage, plan, structureSeeds, worldSeed, proof, hashedSeedFitsNone, bedrock, bedrockProblems, bedrockDisagrees,
                likelySeeds, Optional.of(structure), biomes, biomesDisagree, nether);
        }
    }

    /** Biomes to check, with what the check needs; {@link #NONE} when there are none. */
    public record BiomeInput(BiomeEvidence evidence, BiomeEvidence.ScanCache cache, java.util.function.BooleanSupplier stop) {

        public static final BiomeInput NONE = new BiomeInput(null, null, () -> false);

        boolean present() {
            return evidence != null && !evidence.isEmpty();
        }
    }

    private WorldSeedSearch() {
    }

    /**
     * The first step on its own: the structure seeds that fit the structures (empty when the plan says the search
     * can't run), and those the nether bedrock allows. It is the slow part, so the mod keeps its result while only
     * overworld bedrock, biomes or the hashed seed change.
     *
     * @param structureSeeds the structure seeds of the structures and the nether bedrock together (see {@link #withNether})
     * @param nether         the nether bedrock search, if there was nether bedrock
     * @param netherDisagrees true when the nether bedrock proved structure seeds that the structures don't allow: then
     *                       the structures' seeds are kept, unless none fits them (a structure may come from older
     *                       chunks, or the nether chunks may)
     */
    public record StructureStep(SearchPlan plan, List<Long> structureSeeds, Optional<NetherBedrockSearch.Finding> nether, boolean netherDisagrees) {

        public StructureStep {
            structureSeeds = List.copyOf(structureSeeds);
        }

        public StructureStep(SearchPlan plan, List<Long> structureSeeds) {
            this(plan, structureSeeds, Optional.empty(), false);
        }

        /** True when there is something to go on: the structures could be searched, or the nether bedrock gave seeds. */
        public boolean searched() {
            return plan.canSearch() || nether.map(finding -> !finding.structureSeeds().isEmpty()).orElse(false);
        }

        /**
         * Adds what the nether bedrock search found. Its seeds and the structures' are combined: both together when
         * they agree; the nether's alone when the structures can't be searched yet or no seed fits them while the
         * nether is proven; the structures' when the two disagree ({@link #netherDisagrees()}). Unproven nether
         * results (one surface, few blocks) only narrow the structures' seeds, or stand in for them when there are
         * none, as candidates that the hashed seed or biomes can still prove.
         */
        public StructureStep withNether(NetherBedrockSearch.Finding finding) {
            List<Long> netherSeeds = finding.structureSeeds();
            if (netherSeeds.isEmpty()) {
                return new StructureStep(plan, structureSeeds, Optional.of(finding), false);
            }
            if (!plan.canSearch()) {
                return new StructureStep(plan, netherSeeds, Optional.of(finding), false);
            }
            List<Long> both = structureSeeds.stream().filter(netherSeeds::contains).toList();
            if (!both.isEmpty()) {
                return new StructureStep(plan, both, Optional.of(finding), false);
            }
            if (structureSeeds.isEmpty() && finding.proven()) {
                return new StructureStep(plan, netherSeeds, Optional.of(finding), true);
            }
            return new StructureStep(plan, structureSeeds, Optional.of(finding), finding.proven());
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

    /** {@link #structureSeeds}, then the nether bedrock search when there are nether notes ({@link StructureStep#withNether}). */
    public static StructureStep structureSeeds(VersionData data, List<StructureObservation> structures, List<BedrockObservation> bedrockNotes,
                                               int threads, java.util.function.BooleanSupplier stop, StructureSeedFinder.ProgressListener progress,
                                               NetherBedrockSearch.Progress netherProgress) {
        StructureStep step = structureSeeds(data, structures, threads, progress);
        if (bedrockNotes.stream().noneMatch(note -> note.dimension().id().equals(NetherBedrockSearch.NETHER))) {
            return step;
        }
        return step.withNether(new NetherBedrockSearch(data).find(bedrockNotes, threads, stop, netherProgress));
    }

    /** Above this many structures {@link #withoutMisfit} doesn't try (one search per structure). */
    public static final int MAX_MISFIT_CHECKS = 16;

    /**
     * When no seed fits all the structures: tries again without each structure in turn. If leaving out exactly one
     * gives a world seed that is proven (by the hashed seed or by bedrock), that structure doesn't follow the seed's
     * placement: it may come from chunks made by an older Minecraft version, a command, a copy, or a wrong note.
     * Returns that proven result, naming the structure left out.
     */
    public static Optional<Result> withoutMisfit(VersionData data, List<StructureObservation> structures, List<BedrockObservation> bedrockNotes,
                                                 OptionalLong hashedSeed, int threads) {
        return withoutMisfit(data, structures, bedrockNotes, BiomeInput.NONE, hashedSeed, threads);
    }

    /** {@link #withoutMisfit} with biomes as a way to prove the seed too. */
    public static Optional<Result> withoutMisfit(VersionData data, List<StructureObservation> structures, List<BedrockObservation> bedrockNotes,
                                                 BiomeInput biomes, OptionalLong hashedSeed, int threads) {
        if (structures.size() > MAX_MISFIT_CHECKS) {
            return Optional.empty();
        }
        Result proven = null;
        for (int i = 0; i < structures.size(); i++) {
            List<StructureObservation> rest = new ArrayList<>(structures);
            StructureObservation left = rest.remove(i);
            StructureStep step = structureSeeds(data, rest, threads, (done, total) -> { });
            if (!step.plan().canSearch() || step.structureSeeds().isEmpty()) {
                continue;
            }
            Result result = fullSeed(step, bedrockNotes, biomes, hashedSeed, threads);
            if (result.stage() == Stage.WORLD_SEED) {
                if (proven != null) {
                    // Two different structures each seem to be the odd one out: no clear answer.
                    return Optional.empty();
                }
                proven = result.withLeftOut(left);
            }
        }
        return Optional.ofNullable(proven);
    }

    /** The second step: the full seed from the hashed seed or bedrock, or the likely seeds. */
    public static Result fullSeed(StructureStep step, List<BedrockObservation> bedrockNotes, OptionalLong hashedSeed, int threads) {
        return fullSeed(step, bedrockNotes, BiomeInput.NONE, hashedSeed, threads);
    }

    /**
     * The second step: the full seed from the hashed seed, bedrock or biomes (in that order: the hashed seed is
     * instant, biomes take a few seconds per structure seed), or the likely seeds.
     */
    public static Result fullSeed(StructureStep step, List<BedrockObservation> bedrockNotes, BiomeInput biomes, OptionalLong hashedSeed,
                                  int threads) {
        SearchPlan plan = step.plan();
        List<Long> structureSeeds = step.structureSeeds();
        BedrockEvidence evidence = new BedrockEvidence(bedrockNotes);
        List<String> bedrockProblems = evidence.problems();
        Optional<NetherBedrockSearch.Finding> nether = step.nether();
        if (!step.searched()) {
            return new Result(Stage.NEED_MORE_STRUCTURES, plan, List.of(), OptionalLong.empty(), Optional.empty(), false,
                Optional.empty(), bedrockProblems, false, List.of(), Optional.empty(), Optional.empty(), false, nether);
        }
        if (structureSeeds.isEmpty()) {
            return new Result(Stage.NO_STRUCTURE_SEED, plan, structureSeeds, OptionalLong.empty(), Optional.empty(), false,
                Optional.empty(), bedrockProblems, false, List.of(), Optional.empty(), Optional.empty(), false, nether);
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
                boolean biomesDisagree = biomes.present() && !biomes.evidence().fitsUnderAnyPreset(worldSeed);
                return new Result(Stage.WORLD_SEED, plan, structureSeeds, OptionalLong.of(worldSeed), Optional.of(Proof.HASHED_SEED),
                    false, Optional.empty(), bedrockProblems, disagrees, List.of(), Optional.empty(), Optional.empty(), biomesDisagree, nether);
            }
            hashedSeedFitsNone = checkedAll && matches.length == 0;
        }

        Optional<FullSeedFinder.Result> bedrock = Optional.empty();
        if (bedrockProblems.isEmpty() && !evidence.fullSeedObservations().isEmpty()
            && structureSeeds.size() <= FullSeedFinder.MAX_STRUCTURE_SEEDS) {
            FullSeedFinder.Result found = new FullSeedFinder(structureSeeds, evidence).search(threads);
            bedrock = Optional.of(found);
            List<FullSeedFinder.Candidate> best = found.best();
            // Each wrong structure seed among the candidates may have a seed that fits by chance.
            boolean certain = best.size() == 1 && best.get(0).mismatches() == 0
                && structureSeeds.size() * FullSeedFinder.expectedFalseFits(evidence.fullSeedObservations().size()) < 0.02;
            if (certain) {
                long worldSeed = best.get(0).worldSeed();
                boolean biomesDisagree = biomes.present() && !biomes.evidence().fitsUnderAnyPreset(worldSeed);
                return new Result(Stage.WORLD_SEED, plan, structureSeeds, OptionalLong.of(worldSeed), Optional.of(Proof.BEDROCK),
                    hashedSeedFitsNone, bedrock, bedrockProblems, false, List.of(), Optional.empty(), Optional.empty(), biomesDisagree, nether);
            }
        }

        Optional<BiomeEvidence.Decision> biomeDecision = Optional.empty();
        if (biomes.present() && structureSeeds.size() <= BiomeEvidence.MAX_STRUCTURE_SEEDS) {
            List<Long> likelyForBiomes = new ArrayList<>();
            for (long structureSeed : structureSeeds) {
                likelyForBiomes.addAll(likelySeeds(structureSeed));
            }
            BiomeEvidence.Decision decision = biomes.evidence().search(structureSeeds, likelyForBiomes, threads, biomes.stop(), biomes.cache());
            biomeDecision = Optional.of(decision);
            if (decision.proven()) {
                return new Result(Stage.WORLD_SEED, plan, structureSeeds, decision.seed(), Optional.of(Proof.BIOMES), hashedSeedFitsNone, bedrock,
                    bedrockProblems, false, List.of(), Optional.empty(), biomeDecision, false, nether);
            }
        }

        List<Long> likely = new ArrayList<>();
        if (structureSeeds.size() == 1) {
            likely.addAll(likelySeeds(structureSeeds.get(0)));
        }
        return new Result(Stage.STRUCTURE_SEED, plan, structureSeeds, OptionalLong.empty(), Optional.empty(), hashedSeedFitsNone,
            bedrock, bedrockProblems, false, likely, Optional.empty(), biomeDecision, false, nether);
    }

    /** The world seeds of a structure seed that Minecraft could have picked itself, or that a text seed would give. */
    public static List<Long> likelySeeds(long structureSeed) {
        List<Long> likely = new ArrayList<>();
        Arrays.stream(Seeds.randomSeedCandidates(structureSeed)).forEach(likely::add);
        Seeds.textSeedCandidate(structureSeed).ifPresent(seed -> {
            if (!likely.contains(seed)) {
                likely.add(seed);
            }
        });
        return likely;
    }
}
