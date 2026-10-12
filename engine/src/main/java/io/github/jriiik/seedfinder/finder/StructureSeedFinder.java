package io.github.jriiik.seedfinder.finder;

import io.github.jriiik.seedfinder.Seeds;
import io.github.jriiik.seedfinder.rng.JavaRandom;
import io.github.jriiik.seedfinder.structure.ConcentricRingsPlacement;
import io.github.jriiik.seedfinder.structure.DimensionOriginPlacement;
import io.github.jriiik.seedfinder.structure.RandomSpreadPlacement;
import io.github.jriiik.seedfinder.structure.SpreadType;
import io.github.jriiik.seedfinder.structure.StructureLocator;
import io.github.jriiik.seedfinder.structure.StructureSet;
import io.github.jriiik.seedfinder.version.VersionData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

/**
 * Finds the structure seeds (the lowest 48 bits of the world seed) that put structures exactly where they were
 * observed.
 *
 * <p>How, in three steps:
 * <ol>
 *   <li>Structures whose region size is even reveal a few of the lowest seed bits directly, so the lowest 18 to 20
 *       bits are pinned down first by trying every combination (at most about a million).</li>
 *   <li>The bits up to bit 34 are tried exhaustively. For each combination, {@link TopBitsSolver} computes the few
 *       values of the top 13 bits that fit one observation, instead of trying all 8192.</li>
 *   <li>Each resulting seed gets fast position checks for all observations, and every hit is confirmed with the
 *       engine's full rules (frequency rolls, exclusion zones). Work is spread over all CPU cores.</li>
 * </ol>
 */
public final class StructureSeedFinder {

    /** Searches testing more seeds than this would take hours; the plan asks for more observations instead. */
    public static final long MAX_SEEDS_TO_TEST = 1L << 40;
    /** More expected wrong answers than this makes the result useless; the plan asks for more observations. */
    public static final double MAX_EXPECTED_FALSE_MATCHES = 1000;
    /** At most this many seeds are returned. */
    public static final int MAX_RESULTS = 10_000;
    /** How many low-35-bit combinations one parallel work item covers. */
    private static final int BLOCK_BITS = 12;

    /** Receives progress updates while searching (from several threads). */
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(long done, long total);
    }

    private final VersionData data;
    private final List<ObservationCheck> checks = new ArrayList<>();
    private final SearchPlan plan;
    private long[] lowCandidates = new long[0];
    private int liftedBits;
    private ObservationCheck solverCheck;

    public StructureSeedFinder(VersionData data, List<StructureObservation> observations) {
        this.data = data;
        this.plan = makePlan(observations);
    }

    public SearchPlan plan() {
        return plan;
    }

    /** Runs the search with the given number of threads and returns the matching structure seeds, smallest first. */
    public List<Long> search(int threads, ProgressListener progress) {
        if (!plan.canSearch()) {
            throw new IllegalStateException("The search can't run: " + String.join(" ", plan.problems()));
        }
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be at least 1, got " + threads);
        }
        int middleBits = TopBitsSolver.LOW_BITS - liftedBits;
        int blockBits = Math.min(BLOCK_BITS, middleBits);
        long blocksPerLowCandidate = 1L << (middleBits - blockBits);
        long totalBlocks = lowCandidates.length * blocksPerLowCandidate;
        ObservationCheck[] ordered = checks.stream()
            .sorted(Comparator.comparingDouble(ObservationCheck::remainingProbability))
            .toArray(ObservationCheck[]::new);
        TopBitsSolver solver = new TopBitsSolver(solverCheck);

        Queue<Long> found = new ConcurrentLinkedQueue<>();
        AtomicInteger foundCount = new AtomicInteger();
        AtomicLong blocksDone = new AtomicLong();
        ForkJoinPool pool = new ForkJoinPool(threads);
        try {
            pool.submit(() -> LongStream.range(0, totalBlocks).parallel().forEach(block -> {
                long low = lowCandidates[(int) (block / blocksPerLowCandidate)];
                long firstMiddle = (block % blocksPerLowCandidate) << blockBits;
                JavaRandom random = new JavaRandom(0);
                long[] seeds = new long[solver.maxCandidates()];
                for (long middle = firstMiddle; middle < firstMiddle + (1L << blockBits); middle++) {
                    int count = solver.candidates(middle << liftedBits | low, seeds);
                    for (int i = 0; i < count; i++) {
                        long seed = seeds[i];
                        if (matchesAll(ordered, seed, random) && confirm(seed) && foundCount.incrementAndGet() <= MAX_RESULTS) {
                            found.add(seed);
                        }
                    }
                }
                if (progress != null) {
                    progress.onProgress(blocksDone.incrementAndGet(), totalBlocks);
                }
            })).join();
        } finally {
            pool.shutdownNow();
        }
        return found.stream().sorted().toList();
    }

    private static boolean matchesAll(ObservationCheck[] ordered, long seed, JavaRandom random) {
        for (ObservationCheck check : ordered) {
            if (!check.matches(seed, random)) {
                return false;
            }
        }
        return true;
    }

    /** Checks a candidate with the engine's complete rules, exactly as the game decides structure chunks. */
    private boolean confirm(long structureSeed) {
        StructureLocator locator = new StructureLocator(data, structureSeed);
        for (ObservationCheck check : checks) {
            StructureObservation o = check.observation;
            if (!locator.isStructureChunk(o.set(), o.chunk().x(), o.chunk().z())) {
                return false;
            }
        }
        return true;
    }

    private SearchPlan makePlan(List<StructureObservation> requested) {
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<StructureObservation> usable = new ArrayList<>();
        Map<String, StructureObservation> byRegion = new HashMap<>();

        for (StructureObservation observation : new LinkedHashSet<>(requested)) {
            StructureSet set = observation.set();
            switch (set.placement()) {
                case ConcentricRingsPlacement rings -> {
                    problems.add(set.shortId() + " can't be used yet: their positions depend on biomes.");
                    continue;
                }
                case DimensionOriginPlacement origin -> {
                    warnings.add(set.shortId() + " always start at the world's center, so they reveal nothing about the seed.");
                    continue;
                }
                case RandomSpreadPlacement spread -> {
                    ObservationCheck check = new ObservationCheck(observation, spread);
                    if (!check.isPossibleStartChunk()) {
                        problems.add(observation + " can't be right: " + set.shortId() + " never start in that chunk. "
                            + "Use the chunk where the structure starts (/locate shows it).");
                        continue;
                    }
                    if (check.probability() >= 1) {
                        warnings.add(observation + " reveals nothing about the seed (" + set.shortId()
                            + " can start in that many places), so it is ignored.");
                        continue;
                    }
                    String region = set.id() + "@" + spread.regionOf(observation.chunk().x()) + "," + spread.regionOf(observation.chunk().z());
                    StructureObservation other = byRegion.putIfAbsent(region, observation);
                    if (other != null && spread.spacing() > 1) {
                        problems.add(other + " and " + observation + " can't both be right: only one of them can start in each "
                            + spread.spacing() + "x" + spread.spacing() + " chunk region.");
                        continue;
                    }
                    checks.add(check);
                    usable.add(observation);
                }
            }
        }
        if (checks.isEmpty() && problems.isEmpty()) {
            problems.add("Add some structure observations first.");
        }

        // Temple types in the same region are linked: the second one barely narrows things down. They are kept as
        // checks, but count as (almost) no information in the estimates below.
        Set<ObservationCheck> linked = new HashSet<>();
        for (int j = 0; j < checks.size(); j++) {
            for (int i = 0; i < j; i++) {
                if (checks.get(j).isLinkedTo(checks.get(i)) && linked.add(checks.get(j))) {
                    warnings.add(checks.get(j).observation + " is in the same area as " + checks.get(i).observation
                        + ", and their positions are linked, so it adds little information. Structures farther apart help more.");
                }
            }
        }
        double informationBits = checks.stream()
            .mapToDouble(c -> linked.contains(c) ? LINKED_INFORMATION_BITS : -Math.log(c.probability()) / Math.log(2)).sum();
        List<String> suggestions = twinBreakers();
        // Without a search plan, all we can do is the rough count: 2^48 seeds, each fitting with the combined chance.
        double expectedFalseMatches = Math.pow(2, Seeds.STRUCTURE_SEED_BITS - informationBits) + expectedTwins(linked);
        long seedsToTest = 0;
        if (problems.isEmpty()) {
            List<ObservationCheck> liftable = checks.stream().filter(c -> c.liftBits() > 0).toList();
            if (liftable.isEmpty()) {
                problems.add("To start the search, add at least one of these structures: " + String.join(", ", liftableSetNames()) + ".");
            } else {
                lift(liftable);
                // Any observation with a liftable region size can drive the solver; the largest odd part helps most.
                solverCheck = checks.stream().filter(ObservationCheck::canSolveTopBits)
                    .max(Comparator.comparingInt(ObservationCheck::oddPart)).orElseThrow();
                seedsToTest = ((long) lowCandidates.length << (TopBitsSolver.LOW_BITS - liftedBits))
                    * new TopBitsSolver(solverCheck).maxCandidates();
                if (seedsToTest > MAX_SEEDS_TO_TEST) {
                    problems.add("The search would take too long with this information. Add more structures, ideally some of: "
                        + String.join(", ", liftableSetNames()) + ".");
                }
                // Better: the low bits are already narrowed down, and the bits revealed beyond what lifting pins down
                // don't rule anything else out, so count the seeds left after lifting times their chance to fit.
                // Seeds sharing the true seed's low bits fit more often than pure chance says, so the count is
                // multiplied by a factor measured on typical observation sets (see theWrongSeedEstimateMatchesReality).
                double remainingChance = checks.stream()
                    .mapToDouble(c -> linked.contains(c) ? LINKED_REMAINING_CHANCE : c.remainingProbability())
                    .reduce(1, (a, b) -> a * b);
                double seedsLeft = Math.max(0, lowCandidates.length * Math.pow(2, Seeds.STRUCTURE_SEED_BITS - liftedBits) - 1);
                expectedFalseMatches = SHARED_LOW_BITS_FACTOR * seedsLeft * remainingChance + expectedTwins(linked);
            }
            String advice = suggestions.isEmpty() ? "more structures, ideally of types you haven't used yet, narrow it down."
                : "adding structures of these types narrows it down best: " + String.join(", ", suggestions) + ".";
            if (expectedFalseMatches > MAX_EXPECTED_FALSE_MATCHES) {
                problems.add(String.format(Locale.ROOT, "With this information about %,.0f structure seeds would fit, "
                    + "too many to be useful. Add more structures.", expectedFalseMatches));
            } else if (expectedFalseMatches >= 1.5) {
                warnings.add(String.format(Locale.ROOT, "About %,.0f other structure seeds will probably fit these structures too. "
                    + "The search lists them all; " + advice, expectedFalseMatches));
            } else if (expectedFalseMatches >= 0.1) {
                warnings.add("Other structure seeds might fit these structures too. The search lists them all; " + advice);
            }
        }
        return new SearchPlan(usable, informationBits, expectedFalseMatches, liftedBits, lowCandidates.length, seedsToTest,
            suggestions, problems, warnings);
    }

    /** Measured: a linked temple adds about 3 to 4.4 bits instead of 9.2. */
    private static final double LINKED_INFORMATION_BITS = 4;
    /** ...and about 2 bits beyond the low bits that lifting already pins down. */
    private static final double LINKED_REMAINING_CHANCE = 0.25;
    /** Measured on typical observation sets: wrong seeds sharing the true low bits fit 2 to 5 times more often than chance. */
    private static final double SHARED_LOW_BITS_FACTOR = 4;

    /**
     * Structure types that would make the answer much more certain: wrong seeds mostly survive because structures
     * whose region sizes share odd factors (3 for temples, 13 for villages, 5 for shipwrecks) react the same way to
     * the same change in the seed. A structure with a new odd factor, or one using a power of two, breaks that.
     * Lists unused overworld types like that, biggest odd factor first.
     */
    private List<String> twinBreakers() {
        long usedFactors = 1;
        for (ObservationCheck check : checks) {
            if (check.canSolveTopBits()) {
                usedFactors = lcm(usedFactors, check.oddPart());
            }
        }
        long factors = usedFactors;
        Set<StructureSet> observed = new HashSet<>();
        checks.forEach(check -> observed.add(check.observation.set()));
        return data.structureSets().stream()
            .filter(set -> !observed.contains(set) && data.dimensionsOf(set).contains("minecraft:overworld"))
            .filter(set -> set.placement() instanceof RandomSpreadPlacement spread && spread.spreadType() == SpreadType.LINEAR
                && spread.spacing() > 1)
            .filter(set -> {
                RandomSpreadPlacement spread = (RandomSpreadPlacement) set.placement();
                int bound = spread.spacing() - spread.separation();
                int odd = bound >> Integer.numberOfTrailingZeros(bound);
                return odd == 1 || factors % odd != 0;
            })
            .sorted(Comparator.comparingInt((StructureSet set) -> {
                RandomSpreadPlacement spread = (RandomSpreadPlacement) set.placement();
                int bound = spread.spacing() - spread.separation();
                return -(bound >> Integer.numberOfTrailingZeros(bound));
            }).thenComparing(StructureSet::shortId))
            .map(StructureSet::shortId)
            .toList();
    }

    private static long lcm(long a, long b) {
        long x = a;
        long y = b;
        while (y != 0) {
            long t = x % y;
            x = y;
            y = t;
        }
        return a / x * b;
    }

    /**
     * Seeds that differ from the true one only in their top 13 bits ("twins") shift every structure's random value
     * in the same way, so they fit far more often than unrelated seeds. Adds up their chances over all 8191 shifts.
     */
    private double expectedTwins(Set<ObservationCheck> linked) {
        if (checks.isEmpty()) {
            return 0;
        }
        int multiplier = (int) (JavaRandom.MULTIPLIER & (ObservationCheck.TWIN_SHIFTS - 1));
        double expected = 0;
        for (int difference = 1; difference < ObservationCheck.TWIN_SHIFTS; difference++) {
            // A difference of d * 2^35 in the seed moves the generator's state by a * d * 2^35 after one step and by
            // a^2 * d * 2^35 after two; seen through nextInt that is a shift of (a * d) or (a^2 * d) mod 2^13.
            int shiftX = (multiplier * difference) & (ObservationCheck.TWIN_SHIFTS - 1);
            int shiftZ = (multiplier * shiftX) & (ObservationCheck.TWIN_SHIFTS - 1);
            double chance = 1;
            for (ObservationCheck check : checks) {
                if (!linked.contains(check)) {
                    chance *= check.twinProbability(shiftX, shiftZ);
                }
            }
            expected += chance;
        }
        return expected;
    }

    /** Pins down the low bits, level by level (each check needs a certain number of low bits). */
    private void lift(List<ObservationCheck> liftable) {
        int[] levels = liftable.stream().mapToInt(ObservationCheck::liftBits).distinct().sorted().toArray();
        long[] candidates = {0};
        int bits = 0;
        for (int level : levels) {
            ObservationCheck[] atLevel = liftable.stream().filter(c -> c.liftBits() == level).toArray(ObservationCheck[]::new);
            long extensions = 1L << (level - bits);
            long[] next = new long[16];
            int count = 0;
            for (long candidate : candidates) {
                for (long extension = 0; extension < extensions; extension++) {
                    long value = candidate | extension << bits;
                    if (lowBitsMatchAll(atLevel, value)) {
                        if (count == next.length) {
                            next = Arrays.copyOf(next, count * 2);
                        }
                        next[count++] = value;
                    }
                }
            }
            candidates = Arrays.copyOf(next, count);
            bits = level;
        }
        lowCandidates = candidates;
        liftedBits = bits;
    }

    private static boolean lowBitsMatchAll(ObservationCheck[] checks, long lowSeed) {
        for (ObservationCheck check : checks) {
            if (!check.lowBitsMatch(lowSeed)) {
                return false;
            }
        }
        return true;
    }

    /** The structure sets that can start a search, most helpful first. */
    private List<String> liftableSetNames() {
        return data.structureSets().stream()
            .filter(set -> set.placement() instanceof RandomSpreadPlacement spread && spread.spreadType() == SpreadType.LINEAR
                && Integer.numberOfTrailingZeros(spread.spacing() - spread.separation()) > 0
                && Integer.bitCount(spread.spacing() - spread.separation()) > 1)
            .sorted(Comparator.comparingInt((StructureSet set) -> {
                RandomSpreadPlacement spread = (RandomSpreadPlacement) set.placement();
                return -Integer.numberOfTrailingZeros(spread.spacing() - spread.separation());
            }).thenComparing(StructureSet::shortId))
            .map(StructureSet::shortId)
            .collect(Collectors.toList());
    }
}
