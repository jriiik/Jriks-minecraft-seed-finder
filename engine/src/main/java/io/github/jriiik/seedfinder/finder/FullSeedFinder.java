package io.github.jriiik.seedfinder.finder;

import io.github.jriiik.seedfinder.Seeds;
import io.github.jriiik.seedfinder.bedrock.BedrockDimension;
import io.github.jriiik.seedfinder.bedrock.BedrockEvidence;
import io.github.jriiik.seedfinder.bedrock.BedrockObservation;
import io.github.jriiik.seedfinder.bedrock.BedrockPattern;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Finds the full 64-bit world seed once the structure seed (its lowest 48 bits) is known.
 *
 * <p>65,536 world seeds share a structure seed. Two kinds of help narrow them down:
 * <ul>
 *   <li><b>How the seed was made.</b> When nobody types a seed, Minecraft picks one with Java's random generator, and
 *       only one or two of the 65,536 can come out of it ({@link Seeds#randomSeedCandidates}). Text seeds have at
 *       most one ({@link Seeds#textSeedCandidate}). These are good guesses, but only guesses.</li>
 *   <li><b>Bedrock.</b> The random bedrock floor of the overworld depends on all 64 bits. With bedrock observations,
 *       every one of the 65,536 seeds is checked, so the answer does not rely on how the seed was made.</li>
 * </ul>
 * Seeds are ranked by how many observations they get wrong, so a few mistakes in the notes don't hide the answer.
 */
public final class FullSeedFinder {

    /**
     * Checking all 65,536 seeds of one structure seed against a chunk of bedrock takes about 0.05 to 0.15 seconds of
     * CPU time (less when the nether notes rule the structure seed out); more structure seeds than this is too slow.
     */
    public static final int MAX_STRUCTURE_SEEDS = 1000;
    /** ForkJoinPool can't use more threads than this. */
    private static final int MAX_THREADS = 32_767;
    /** How many of the best seeds are kept. */
    public static final int MAX_CANDIDATES = 20;
    private static final int UPPER_VALUES = 1 << 16;
    /** Work item size: this many upper-bit values. */
    private static final int BLOCK_SIZE = 1 << 12;

    /** How a world seed could have been made. */
    public enum Origin {
        /** Minecraft could have picked it by itself (nobody typed a seed). */
        RANDOM,
        /** It could have been typed as text, or as a number of up to about 2 billion. */
        TEXT,
        /** Only by typing this exact (long) number. */
        TYPED_NUMBER
    }

    /** A possible world seed and how many observations it gets wrong. */
    public record Candidate(long worldSeed, int mismatches, Origin origin) {

        /**
         * True when typing text or a short number can give this seed. That can be true for a {@link Origin#RANDOM} seed
         * too: a few short numbers, 0 among them, are also seeds Minecraft can pick by itself.
         */
        public boolean couldBeTyped() {
            return worldSeed == (int) worldSeed;
        }
    }

    /**
     * @param candidates  the best seeds, fewest mistakes first
     * @param checkedAll  true when every upper-bit value of every structure seed was checked against bedrock; false
     *                    when there were no whole-seed observations and only the likely seeds were listed
     * @param seedsChecked how many world seeds were checked
     * @param perfectFits  how many of them fit every observation (more than {@link #MAX_CANDIDATES} are not listed)
     */
    public record Result(List<Candidate> candidates, boolean checkedAll, long seedsChecked, long perfectFits) {

        /** The candidates that get the fewest observations wrong. */
        public List<Candidate> best() {
            if (candidates.isEmpty()) {
                return List.of();
            }
            int fewest = candidates.get(0).mismatches();
            return candidates.stream().filter(candidate -> candidate.mismatches() == fewest).toList();
        }
    }

    private static final Comparator<Candidate> RANKING = Comparator.comparingInt(Candidate::mismatches)
        .thenComparing(Candidate::origin)
        .thenComparingLong(Candidate::worldSeed);

    private final List<Long> structureSeeds;
    private final BedrockEvidence evidence;

    public FullSeedFinder(Collection<Long> structureSeeds, BedrockEvidence evidence) {
        this.structureSeeds = new ArrayList<>(new LinkedHashSet<>(structureSeeds.stream().map(Seeds::structureSeed).toList()));
        this.evidence = evidence;
        if (this.structureSeeds.isEmpty()) {
            throw new IllegalArgumentException("No structure seed given");
        }
        if (this.structureSeeds.size() > MAX_STRUCTURE_SEEDS) {
            throw new IllegalArgumentException("Too many structure seeds (" + this.structureSeeds.size() + "); at most "
                + MAX_STRUCTURE_SEEDS + " can be checked");
        }
    }

    public BedrockEvidence evidence() {
        return evidence;
    }

    /**
     * Measured: with n informative bedrock blocks, the 65,536 seeds of a wrong structure seed contain on average about
     * 65,536 * (c / n)^3 seeds that fit every block, with c between 2.3 and 3.2 (bedrock heights or single layers,
     * patches of 8 x 8 to 24 x 24 blocks). The cube comes from the bedrock pattern of a whole world depending on only
     * a few hidden numbers. This constant is a little above the measurements, so the estimate errs on the careful side.
     */
    static final double FALSE_FIT_CONSTANT = 3.5;

    /**
     * About how many of the 65,536 world seeds of a <em>wrong</em> structure seed would fit this many informative
     * bedrock blocks perfectly. A whole chunk of bedrock heights (about 630 blocks) gives about 0.01; an 8 x 8 patch
     * about 0.7. Seeds of the right structure seed are all checked, so this only matters if the structure seed
     * might be wrong.
     */
    public static double expectedFalseFits(int informativeBlocks) {
        if (informativeBlocks <= 0) {
            return UPPER_VALUES;
        }
        return Math.min(UPPER_VALUES, UPPER_VALUES * Math.pow(FALSE_FIT_CONSTANT / informativeBlocks, 3));
    }

    /** The chance that one wrong world seed fits this many informative bedrock blocks perfectly (same measurement). */
    public static double falseFitChance(int informativeBlocks) {
        return expectedFalseFits(informativeBlocks) / UPPER_VALUES;
    }

    /** How a world seed could have been made, judged by the seed alone. */
    public static Origin originOf(long worldSeed) {
        if (Seeds.couldBeRandomSeed(worldSeed)) {
            return Origin.RANDOM;
        }
        return worldSeed == (int) worldSeed ? Origin.TEXT : Origin.TYPED_NUMBER;
    }

    public Result search(int threads) {
        if (evidence.fullSeedObservations().isEmpty()) {
            return likelySeeds();
        }
        int blocksPerSeed = UPPER_VALUES / BLOCK_SIZE;
        int[] structureMismatches = structureSeeds.stream().mapToInt(evidence::structureSeedMismatches).toArray();
        // Structure seeds the nether notes fit best go first, so the shared limit below gets tight early.
        int[] order = IntStream.range(0, structureSeeds.size()).boxed()
            .sorted(Comparator.comparingInt(index -> structureMismatches[index]))
            .mapToInt(Integer::intValue).toArray();
        // Any block whose list of best candidates is full proves that the overall best list can't contain a candidate
        // with more mismatches than its worst one, so all blocks may skip such candidates.
        AtomicInteger sharedLimit = new AtomicInteger(Integer.MAX_VALUE);
        List<Set<Long>> randomSeeds = structureSeeds.stream()
            .map(seed -> {
                Set<Long> set = new HashSet<>();
                for (long candidate : Seeds.randomSeedCandidates(seed)) {
                    set.add(candidate);
                }
                return set;
            })
            .toList();
        ForkJoinPool pool = new ForkJoinPool(Math.clamp(threads, 1, MAX_THREADS));
        try {
            List<BlockResult> blocks = pool.submit(() -> IntStream.range(0, structureSeeds.size() * blocksPerSeed).parallel()
                .mapToObj(block -> {
                    int seedIndex = order[block / blocksPerSeed];
                    long structureSeed = structureSeeds.get(seedIndex);
                    int firstUpper = (block % blocksPerSeed) * BLOCK_SIZE;
                    return searchBlock(structureSeed, structureMismatches[seedIndex], randomSeeds.get(seedIndex), firstUpper, sharedLimit);
                })
                .toList()).join();
            List<Candidate> merged = blocks.stream()
                .flatMap(block -> block.best().stream())
                .sorted(RANKING)
                .limit(MAX_CANDIDATES)
                .toList();
            long perfectFits = blocks.stream().mapToLong(BlockResult::perfectFits).sum();
            return new Result(merged, true, (long) structureSeeds.size() * UPPER_VALUES, perfectFits);
        } finally {
            pool.shutdown();
        }
    }

    private record BlockResult(List<Candidate> best, long perfectFits) {
    }

    /** The best {@link #MAX_CANDIDATES} seeds among one block of upper-bit values. */
    private BlockResult searchBlock(long structureSeed, int structureMismatches, Set<Long> randomSeeds, int firstUpper,
                                    AtomicInteger sharedLimit) {
        OptionalLong text = Seeds.textSeedCandidate(structureSeed);
        List<Candidate> best = new ArrayList<>(MAX_CANDIDATES + 1);
        long perfectFits = 0;
        for (int upper = firstUpper; upper < firstUpper + BLOCK_SIZE; upper++) {
            long worldSeed = (long) upper << 48 | structureSeed;
            int ownLimit = best.size() < MAX_CANDIDATES ? Integer.MAX_VALUE : best.get(best.size() - 1).mismatches();
            int limit = Math.min(ownLimit, sharedLimit.get());
            if (structureMismatches > limit) {
                break;
            }
            int remaining = limit == Integer.MAX_VALUE ? limit : limit - structureMismatches;
            int mismatches = structureMismatches + evidence.fullSeedMismatches(worldSeed, remaining);
            if (mismatches == 0) {
                perfectFits++;
            }
            if (mismatches > limit) {
                continue;
            }
            Origin origin = randomSeeds.contains(worldSeed) ? Origin.RANDOM
                : text.isPresent() && text.getAsLong() == worldSeed ? Origin.TEXT : Origin.TYPED_NUMBER;
            Candidate candidate = new Candidate(worldSeed, mismatches, origin);
            int position = 0;
            while (position < best.size() && RANKING.compare(best.get(position), candidate) < 0) {
                position++;
            }
            if (position < MAX_CANDIDATES) {
                best.add(position, candidate);
                if (best.size() > MAX_CANDIDATES) {
                    best.remove(best.size() - 1);
                }
                if (best.size() == MAX_CANDIDATES) {
                    sharedLimit.accumulateAndGet(best.get(MAX_CANDIDATES - 1).mismatches(), Math::min);
                }
            }
        }
        return new BlockResult(best, perfectFits);
    }

    /** Without whole-seed observations: the seeds Minecraft or a text seed could have produced. */
    private Result likelySeeds() {
        List<Candidate> candidates = new ArrayList<>();
        for (long structureSeed : structureSeeds) {
            int mismatches = evidence.structureSeedMismatches(structureSeed);
            for (long candidate : Seeds.randomSeedCandidates(structureSeed)) {
                candidates.add(new Candidate(candidate, mismatches, Origin.RANDOM));
            }
            OptionalLong text = Seeds.textSeedCandidate(structureSeed);
            if (text.isPresent() && candidates.stream().noneMatch(c -> c.worldSeed() == text.getAsLong())) {
                candidates.add(new Candidate(text.getAsLong(), mismatches, Origin.TEXT));
            }
        }
        candidates.sort(RANKING);
        long perfectFits = candidates.stream().filter(candidate -> candidate.mismatches() == 0).count();
        return new Result(candidates.stream().limit(MAX_CANDIDATES).toList(), false, candidates.size(), perfectFits);
    }

    /**
     * Columns of the overworld floor near the observations where these seeds predict different bedrock heights,
     * nearest first: digging there tells the seeds apart. Columns that were already observed are skipped.
     *
     * @return {x, z} pairs
     */
    public static List<int[]> distinguishingColumns(BedrockDimension dimension, List<Long> worldSeeds,
                                                    List<BedrockObservation> observed, int limit) {
        if (worldSeeds.size() < 2 || dimension.floor().isEmpty()) {
            return List.of();
        }
        List<BedrockPattern> patterns = worldSeeds.stream().map(seed -> new BedrockPattern(dimension, seed)).toList();
        Set<Long> seen = new HashSet<>();
        long sumX = 0;
        long sumZ = 0;
        int count = 0;
        for (BedrockObservation observation : observed) {
            if (observation.dimension().equals(dimension)) {
                seen.add((long) observation.x() << 32 | (observation.z() & 0xFFFF_FFFFL));
                sumX += observation.x();
                sumZ += observation.z();
                count++;
            }
        }
        int centerX = count == 0 ? 0 : (int) Math.floorDiv(sumX, count);
        int centerZ = count == 0 ? 0 : (int) Math.floorDiv(sumZ, count);
        List<int[]> columns = new ArrayList<>();
        for (int radius = 0; radius <= 64 && columns.size() < limit; radius++) {
            for (int dx = -radius; dx <= radius && columns.size() < limit; dx++) {
                for (int dz = -radius; dz <= radius && columns.size() < limit; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    int x = centerX + dx;
                    int z = centerZ + dz;
                    if (seen.contains((long) x << 32 | (z & 0xFFFF_FFFFL))) {
                        continue;
                    }
                    int first = patterns.get(0).floorTop(x, z);
                    if (patterns.stream().anyMatch(pattern -> pattern.floorTop(x, z) != first)) {
                        columns.add(new int[] {x, z});
                    }
                }
            }
        }
        return columns;
    }
}
