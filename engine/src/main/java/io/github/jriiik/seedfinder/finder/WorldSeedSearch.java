package io.github.jriiik.seedfinder.finder;

import io.github.jriiik.seedfinder.Seeds;
import io.github.jriiik.seedfinder.bedrock.BedrockEvidence;
import io.github.jriiik.seedfinder.bedrock.BedrockObservation;
import io.github.jriiik.seedfinder.version.VersionData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

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
        /**
         * No structure seed fits the structures (and the nether bedrock); or, when the hashed seed is known to be
         * genuine (single player), none of the structure seeds that fit has it ({@link Result#hashedSeedFitsNone()}).
         */
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
     * @param structureSeeds the structure seeds that fit the structures and the nether bedrock (empty before the search
     *                       ran); both sides' seeds when they disagree ({@link StructureStep#netherDisagrees()})
     * @param worldSeed      the world seed, once it is certain
     * @param proof          how the world seed was found
     * @param hashedSeedFitsNone true when a hashed seed was given but no candidate has it (the server may send a
     *                       different value, or the structure seed is wrong)
     * @param bedrock        the bedrock search, when there was overworld bedrock to check
     * @param bedrockProblems notes that contradict the game's bedrock rules whatever the seed
     * @param bedrockDisagrees true when the world seed found gets some bedrock wrong; at {@link Stage#STRUCTURE_SEED}:
     *                       the seed the biomes single out gets some bedrock wrong while another seed fits all of it
     * @param likelySeeds    without proof: the seeds Minecraft could have picked itself or a text seed would give, of
     *                       a single structure seed (only those that fit the biomes noted, if any were checked)
     * @param leftOut        a structure that doesn't fit the world seed and was left out (see {@link #withoutMisfit})
     * @param biomes         the biome check, when biomes were noted and checked
     * @param biomesDisagree true when the world seed found another way doesn't fit the biomes noted (under any preset)
     * @param nether         the nether bedrock search, when there was nether bedrock to search
     * @param netherDisagrees true when the nether bedrock proved structure seeds the structures don't allow (see
     *                       {@link StructureStep#netherDisagrees()})
     * @param misfits        with a world seed: the structures noted that don't fit it (found another way, for example
     *                       from the nether bedrock)
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
        Optional<NetherBedrockSearch.Finding> nether,
        boolean netherDisagrees,
        List<StructureObservation> misfits
    ) {

        public Result {
            structureSeeds = List.copyOf(structureSeeds);
            likelySeeds = List.copyOf(likelySeeds);
            misfits = List.copyOf(misfits);
        }

        Result withLeftOut(StructureObservation structure) {
            return new Result(stage, plan, structureSeeds, worldSeed, proof, hashedSeedFitsNone, bedrock, bedrockProblems, bedrockDisagrees,
                likelySeeds, Optional.of(structure), biomes, biomesDisagree, nether, netherDisagrees, misfits);
        }

        /**
         * True when the structure seeds came from the nether bedrock alone: no structures could be searched, or none
         * fitted them all.
         */
        public boolean fromNetherAlone() {
            return nether.map(finding -> !finding.structureSeeds().isEmpty()).orElse(false)
                && (!plan.canSearch() || netherDisagrees && nether.get().structureSeeds().containsAll(structureSeeds));
        }

        /** With {@link #netherDisagrees()}: the structure seeds the structures fit and the nether bedrock doesn't. */
        public List<Long> structuresOnlySeeds() {
            List<Long> netherSeeds = nether.map(NetherBedrockSearch.Finding::structureSeeds).orElse(List.of());
            return netherDisagrees ? structureSeeds.stream().filter(seed -> !netherSeeds.contains(seed)).toList() : List.of();
        }
    }

    /** Biomes to check, with what the check needs; {@link #NONE} when there are none. */
    public record BiomeInput(BiomeEvidence evidence, BiomeEvidence.ScanCache cache, java.util.function.BooleanSupplier stop) {

        public static final BiomeInput NONE = new BiomeInput(null, null, () -> false);

        boolean present() {
            return evidence != null && !evidence.isEmpty();
        }
    }

    /**
     * The hashed seed the server sent, and whether it is known to be the world's own: in single player the game makes
     * it from the real seed, while a server may send a made-up one.
     */
    public record HashedSeed(OptionalLong value, boolean genuine) {

        public static final HashedSeed NONE = new HashedSeed(OptionalLong.empty(), false);

        public static HashedSeed of(OptionalLong value) {
            return new HashedSeed(value, false);
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
     * @param netherDisagrees true when the nether bedrock and the structures disagree: the nether proved structure
     *                       seeds that the structures don't allow (then the seeds of both are kept, so the hashed
     *                       seed, overworld bedrock or biomes can tell which side is right), or, with an unproven
     *                       complete list, none of the structures' seeds is on it
     */
    public record StructureStep(VersionData data, SearchPlan plan, List<Long> structureSeeds, Optional<NetherBedrockSearch.Finding> nether,
                                boolean netherDisagrees) {

        public StructureStep {
            structureSeeds = List.copyOf(structureSeeds);
        }

        public StructureStep(VersionData data, SearchPlan plan, List<Long> structureSeeds) {
            this(data, plan, structureSeeds, Optional.empty(), false);
        }

        /**
         * True when there is something to go on: the structures could be searched, or the nether bedrock gave seeds
         * (a list cut off because too many seeds fit doesn't count: the right seed may be missing from it).
         */
        public boolean searched() {
            return plan.canSearch() || nether.map(finding -> !finding.capped() && !finding.structureSeeds().isEmpty()).orElse(false);
        }

        /**
         * Adds what the nether bedrock search found. Its seeds and the structures' are combined: both together when
         * they agree; the nether's alone when the structures can't be searched yet or no seed fits them while the
         * nether is proven. When a proven nether result and the structures disagree ({@link #netherDisagrees()}), one
         * side is wrong (a structure noted in the wrong place, from older chunks or a command; or nether chunks of
         * another world with the same hashed seed), so the seeds of both are kept. Unproven nether results (one
         * surface, few blocks) only narrow the structures' seeds, or stand in for them when there are none, as
         * candidates that the hashed seed or biomes can still prove. A list cut off because too many seeds fit
         * ({@link NetherBedrockSearch.Finding#capped()}) is not used: the right seed may be missing from it.
         */
        public StructureStep withNether(NetherBedrockSearch.Finding finding) {
            List<Long> netherSeeds = finding.structureSeeds();
            if (netherSeeds.isEmpty() || finding.capped()) {
                return new StructureStep(data, plan, structureSeeds, Optional.of(finding), false);
            }
            if (!plan.canSearch()) {
                return new StructureStep(data, plan, netherSeeds, Optional.of(finding), false);
            }
            List<Long> both = structureSeeds.stream().filter(netherSeeds::contains).toList();
            if (!both.isEmpty()) {
                return new StructureStep(data, plan, both, Optional.of(finding), false);
            }
            if (finding.proven()) {
                Set<Long> union = new LinkedHashSet<>(netherSeeds);
                union.addAll(structureSeeds);
                return new StructureStep(data, plan, List.copyOf(union), Optional.of(finding), true);
            }
            // An unproven but complete list holds every seed whose nether bedrock fits: the right one too, unless a
            // nether chunk doesn't follow the seed. Not on it: worth a note, but the structures' seeds are kept.
            return new StructureStep(data, plan, structureSeeds, Optional.of(finding), !structureSeeds.isEmpty());
        }
    }

    public static StructureStep structureSeeds(VersionData data, List<StructureObservation> structures, int threads,
                                               StructureSeedFinder.ProgressListener progress) {
        StructureSeedFinder finder = new StructureSeedFinder(data, structures);
        SearchPlan plan = finder.plan();
        return new StructureStep(data, plan, plan.canSearch() ? finder.search(threads, progress) : List.of());
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

    /**
     * The bedrock notes the full-seed step checks: after a nether search, only the overworld's (the nether's were used
     * by that search; a chunk it found unusable, or the nether of one side when the structures and the nether
     * disagree, would otherwise count against every seed).
     */
    public static List<BedrockObservation> bedrockForFullSeed(List<BedrockObservation> bedrockNotes, Optional<NetherBedrockSearch.Finding> nether) {
        if (nether.isEmpty()) {
            return bedrockNotes;
        }
        return bedrockNotes.stream().filter(note -> !note.dimension().id().equals(NetherBedrockSearch.NETHER)).toList();
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
        return withoutMisfit(data, structures, bedrockNotes, Optional.empty(), BiomeInput.NONE, HashedSeed.of(hashedSeed), threads);
    }

    /**
     * {@link #withoutMisfit} with biomes as a way to prove the seed too, and the nether bedrock search already done
     * (combined with each try as in {@link StructureStep#withNether}).
     */
    public static Optional<Result> withoutMisfit(VersionData data, List<StructureObservation> structures, List<BedrockObservation> bedrockNotes,
                                                 Optional<NetherBedrockSearch.Finding> nether, BiomeInput biomes, HashedSeed hashedSeed,
                                                 int threads) {
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
            if (nether.isPresent()) {
                step = step.withNether(nether.get());
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
        return fullSeed(step, bedrockNotes, BiomeInput.NONE, HashedSeed.of(hashedSeed), threads);
    }

    /** {@link #fullSeed(StructureStep, List, BiomeInput, HashedSeed, int)} with a hashed seed that may be made up. */
    public static Result fullSeed(StructureStep step, List<BedrockObservation> bedrockNotes, BiomeInput biomes, OptionalLong hashedSeed,
                                  int threads) {
        return fullSeed(step, bedrockNotes, biomes, HashedSeed.of(hashedSeed), threads);
    }

    /**
     * The second step: the full seed from the hashed seed, bedrock or biomes (in that order: the hashed seed is
     * instant, biomes take a few seconds per structure seed), or the likely seeds. After a nether search only the
     * overworld's bedrock is checked ({@link #bedrockForFullSeed}). A genuine hashed seed that none of the candidates has means the
     * structure seeds are wrong: then nothing else is tried ({@link Stage#NO_STRUCTURE_SEED}), and a seed proven by
     * bedrock or biomes must have it.
     */
    public static Result fullSeed(StructureStep step, List<BedrockObservation> bedrockNotes, BiomeInput biomes, HashedSeed hashedSeed,
                                  int threads) {
        SearchPlan plan = step.plan();
        List<Long> structureSeeds = step.structureSeeds();
        Optional<NetherBedrockSearch.Finding> nether = step.nether();
        BedrockEvidence evidence = new BedrockEvidence(bedrockForFullSeed(bedrockNotes, nether));
        List<String> bedrockProblems = evidence.problems();
        boolean netherDisagrees = step.netherDisagrees();
        if (!step.searched()) {
            return new Result(Stage.NEED_MORE_STRUCTURES, plan, List.of(), OptionalLong.empty(), Optional.empty(), false,
                Optional.empty(), bedrockProblems, false, List.of(), Optional.empty(), Optional.empty(), false, nether, netherDisagrees, List.of());
        }
        if (structureSeeds.isEmpty()) {
            return new Result(Stage.NO_STRUCTURE_SEED, plan, structureSeeds, OptionalLong.empty(), Optional.empty(), false,
                Optional.empty(), bedrockProblems, false, List.of(), Optional.empty(), Optional.empty(), false, nether, netherDisagrees, List.of());
        }

        boolean hashedSeedFitsNone = false;
        OptionalLong hashed = hashedSeed.value();
        if (hashed.isPresent()) {
            long[] matches = Seeds.likelyWithHashedSeed(structureSeeds, hashed.getAsLong());
            boolean checkedAll = matches.length == 0 && structureSeeds.size() <= MAX_HASHED_SEED_CHECKS;
            if (checkedAll) {
                matches = Seeds.withHashedSeed(structureSeeds, hashed.getAsLong());
            }
            if (matches.length == 1) {
                long worldSeed = matches[0];
                boolean disagrees = bedrockProblems.isEmpty() && !evidence.mismatchesOf(worldSeed).isEmpty();
                boolean biomesDisagree = biomes.present() && !biomes.evidence().fitsUnderAnyPreset(worldSeed);
                return new Result(Stage.WORLD_SEED, plan, structureSeeds, OptionalLong.of(worldSeed), Optional.of(Proof.HASHED_SEED),
                    false, Optional.empty(), bedrockProblems, disagrees, List.of(), Optional.empty(), Optional.empty(), biomesDisagree, nether,
                    netherDisagrees, misfits(step, worldSeed));
            }
            hashedSeedFitsNone = checkedAll && matches.length == 0;
            if (hashedSeedFitsNone && hashedSeed.genuine()) {
                // The world's own hashed seed fits none of them: the structure seeds are wrong (a structure that
                // doesn't follow the seed, for example), so nothing found from them could be the world seed.
                return new Result(Stage.NO_STRUCTURE_SEED, plan, structureSeeds, OptionalLong.empty(), Optional.empty(), true,
                    Optional.empty(), bedrockProblems, false, List.of(), Optional.empty(), Optional.empty(), false, nether, netherDisagrees, List.of());
            }
        }

        Optional<FullSeedFinder.Result> bedrock = Optional.empty();
        if (bedrockProblems.isEmpty() && !evidence.fullSeedObservations().isEmpty()
            && structureSeeds.size() <= FullSeedFinder.MAX_STRUCTURE_SEEDS) {
            FullSeedFinder.Result found = new FullSeedFinder(structureSeeds, evidence).search(threads);
            bedrock = Optional.of(found);
            List<FullSeedFinder.Candidate> best = found.best();
            // Each wrong structure seed among the candidates may have a seed that fits by chance.
            boolean certain = best.size() == 1 && best.get(0).mismatches() == 0
                && structureSeeds.size() * FullSeedFinder.expectedFalseFits(evidence.fullSeedObservations().size()) < 0.02
                && hasHashedSeed(best.get(0).worldSeed(), hashedSeed);
            if (certain) {
                long worldSeed = best.get(0).worldSeed();
                boolean biomesDisagree = biomes.present() && !biomes.evidence().fitsUnderAnyPreset(worldSeed);
                return new Result(Stage.WORLD_SEED, plan, structureSeeds, OptionalLong.of(worldSeed), Optional.of(Proof.BEDROCK),
                    hashedSeedFitsNone, bedrock, bedrockProblems, false, List.of(), Optional.empty(), Optional.empty(), biomesDisagree, nether,
                    netherDisagrees, misfits(step, worldSeed));
            }
        }

        Optional<BiomeEvidence.Decision> biomeDecision = Optional.empty();
        boolean bedrockDisagrees = false;
        if (biomes.present() && structureSeeds.size() <= BiomeEvidence.MAX_STRUCTURE_SEEDS) {
            List<Long> likelyForBiomes = new ArrayList<>();
            for (long structureSeed : structureSeeds) {
                likelyForBiomes.addAll(likelySeeds(structureSeed));
            }
            BiomeEvidence.Decision decision = biomes.evidence().search(structureSeeds, likelyForBiomes, threads, biomes.stop(), biomes.cache());
            biomeDecision = Optional.of(decision);
            if (decision.proven() && hasHashedSeed(decision.seed().getAsLong(), hashedSeed)) {
                long worldSeed = decision.seed().getAsLong();
                bedrockDisagrees = bedrockProblems.isEmpty() && !evidence.mismatchesOf(worldSeed).isEmpty();
                // Bedrock that another seed fits perfectly while this one gets some wrong is evidence against it:
                // then neither is presented (notes from two worlds, or changed chunks on one side).
                boolean anotherFitsBedrock = bedrock.map(found -> found.best().stream()
                    .anyMatch(candidate -> candidate.mismatches() == 0 && candidate.worldSeed() != worldSeed)).orElse(false);
                if (!(bedrockDisagrees && anotherFitsBedrock)) {
                    return new Result(Stage.WORLD_SEED, plan, structureSeeds, decision.seed(), Optional.of(Proof.BIOMES), hashedSeedFitsNone, bedrock,
                        bedrockProblems, bedrockDisagrees, List.of(), Optional.empty(), biomeDecision, false, nether, netherDisagrees,
                        misfits(step, worldSeed));
                }
            }
        }

        List<Long> likely = new ArrayList<>();
        if (structureSeeds.size() == 1) {
            likely.addAll(likelySeeds(structureSeeds.get(0)));
            // Likely seeds the biomes noted rule out are not named (the seed was then typed as a number).
            biomeDecision.filter(BiomeEvidence.Decision::complete)
                .ifPresent(decision -> likely.removeIf(seed -> !biomes.evidence().fitsUnderAnyPreset(seed)));
        }
        return new Result(Stage.STRUCTURE_SEED, plan, structureSeeds, OptionalLong.empty(), Optional.empty(), hashedSeedFitsNone,
            bedrock, bedrockProblems, bedrockDisagrees, likely, Optional.empty(), biomeDecision, false, nether, netherDisagrees, List.of());
    }

    /** False only when the hashed seed is genuine and the seed doesn't have it. */
    private static boolean hasHashedSeed(long worldSeed, HashedSeed hashedSeed) {
        return !hashedSeed.genuine() || hashedSeed.value().isEmpty() || Seeds.hashedSeed(worldSeed) == hashedSeed.value().getAsLong();
    }

    /** The structures of the step's plan that don't fit the world seed. */
    private static List<StructureObservation> misfits(StructureStep step, long worldSeed) {
        return SeedCheck.misfits(step.data(), step.plan().observations(), worldSeed);
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
