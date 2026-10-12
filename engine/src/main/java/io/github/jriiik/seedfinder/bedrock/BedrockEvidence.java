package io.github.jriiik.seedfinder.bedrock;

import io.github.jriiik.seedfinder.rng.RandomSupport;
import io.github.jriiik.seedfinder.rng.WorldgenSeeds;
import io.github.jriiik.seedfinder.rng.XoroshiroRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bedrock observations prepared for checking many seeds quickly.
 *
 * <p>Observations are sorted into three groups:
 * <ul>
 *   <li>heights where bedrock is not random (always or never bedrock): they carry no information, and one that
 *       says the opposite of the game's rule is reported as a {@link #problems() problem};</li>
 *   <li>random bedrock in dimensions using the old 48-bit generator (the nether): it only depends on the structure
 *       seed, so it can tell look-alike structure seeds apart but not the upper 16 bits;</li>
 *   <li>random bedrock in dimensions using Xoroshiro (the overworld): it depends on the whole 64-bit seed.</li>
 * </ul>
 */
public final class BedrockEvidence {

    private static final int MAX_PROBLEMS_LISTED = 5;

    private final List<BedrockObservation> all;
    private final List<String> problems = new ArrayList<>();
    private final List<BedrockObservation> structureSeedOnly = new ArrayList<>();
    private int uninformative;

    // The whole-seed observations, compiled for speed.
    private final List<BedrockObservation> fullSeed = new ArrayList<>();
    private final long[] hashLo;
    private final long[] hashHi;
    private final long[] positionSeeds;
    private final int[] nameIndex;
    private final int[] thresholds;
    private final boolean[] bedrockWhenBelow;

    public BedrockEvidence(List<BedrockObservation> observations) {
        this.all = List.copyOf(observations);
        Map<String, BedrockObservation> seen = new LinkedHashMap<>();
        int problemCount = 0;
        List<String> names = new ArrayList<>();
        List<BedrockRule> fullSeedRules = new ArrayList<>();
        for (BedrockObservation observation : all) {
            String key = observation.dimension().id() + " " + observation.x() + " " + observation.y() + " " + observation.z();
            BedrockObservation earlier = seen.putIfAbsent(key, observation);
            if (earlier != null) {
                if (earlier.bedrock() != observation.bedrock()) {
                    problemCount = addProblem(problemCount, "the block at (" + observation.x() + ", " + observation.y() + ", "
                        + observation.z() + ") is marked both as bedrock and as not bedrock");
                }
                continue;
            }
            List<BedrockRule> random = new ArrayList<>();
            boolean certainBedrock = false;
            for (BedrockRule rule : observation.dimension().rules()) {
                if (rule.isRandomAt(observation.y())) {
                    random.add(rule);
                } else if (rule.fixedBedrockAt(observation.y())) {
                    certainBedrock = true;
                }
            }
            if (certainBedrock || random.isEmpty()) {
                uninformative++;
                if (certainBedrock != observation.bedrock()) {
                    problemCount = addProblem(problemCount, "the " + observation.dimension().shortName() + (certainBedrock ? " always" : " never")
                        + " has bedrock at y=" + observation.y() + ", but the block at (" + observation.x() + ", " + observation.y() + ", "
                        + observation.z() + ") is marked as " + (observation.bedrock() ? "bedrock" : "not bedrock"));
                }
            } else if (random.size() > 1) {
                throw new IllegalArgumentException("Overlapping bedrock rules at y=" + observation.y() + " are not supported");
            } else if (!observation.dimension().dependsOnFullSeed()) {
                structureSeedOnly.add(observation);
            } else {
                fullSeed.add(observation);
                fullSeedRules.add(random.get(0));
                if (!names.contains(random.get(0).randomName())) {
                    names.add(random.get(0).randomName());
                }
            }
        }
        if (problemCount > MAX_PROBLEMS_LISTED) {
            problems.add("... and " + (problemCount - MAX_PROBLEMS_LISTED) + " more like these");
        }

        hashLo = new long[names.size()];
        hashHi = new long[names.size()];
        for (int i = 0; i < names.size(); i++) {
            RandomSupport.Seed128 hash = RandomSupport.seedFromHashOf(names.get(i));
            hashLo[i] = hash.lo();
            hashHi[i] = hash.hi();
        }
        int n = fullSeed.size();
        positionSeeds = new long[n];
        nameIndex = new int[n];
        thresholds = new int[n];
        bedrockWhenBelow = new boolean[n];
        for (int i = 0; i < n; i++) {
            BedrockObservation observation = fullSeed.get(i);
            BedrockRule rule = fullSeedRules.get(i);
            positionSeeds[i] = WorldgenSeeds.positional(observation.x(), observation.y(), observation.z());
            nameIndex[i] = names.indexOf(rule.randomName());
            thresholds[i] = rule.threshold(observation.y());
            // The observation matches when (number < threshold) == bedrockWhenBelow.
            bedrockWhenBelow[i] = observation.bedrock() != rule.inverted();
        }
    }

    private int addProblem(int count, String problem) {
        if (count < MAX_PROBLEMS_LISTED) {
            problems.add(problem);
        }
        return count + 1;
    }

    /** All observations as given. */
    public List<BedrockObservation> observations() {
        return all;
    }

    /** Observations that contradict the game's rules no matter what the seed is (mistakes when noting them down). */
    public List<String> problems() {
        return List.copyOf(problems);
    }

    /** Observations of random bedrock that depend on the whole 64-bit seed (overworld). */
    public List<BedrockObservation> fullSeedObservations() {
        return List.copyOf(fullSeed);
    }

    /** Observations of random bedrock that only depend on the structure seed (nether). */
    public List<BedrockObservation> structureSeedObservations() {
        return List.copyOf(structureSeedOnly);
    }

    /** Observations at heights where bedrock is not random; they don't help find the seed. */
    public int uninformativeCount() {
        return uninformative;
    }

    /** How many observations disagree with a structure seed (only the nether-style observations take part). */
    public int structureSeedMismatches(long structureSeed) {
        int mismatches = 0;
        Map<BedrockDimension, BedrockPattern> patterns = new HashMap<>();
        for (BedrockObservation observation : structureSeedOnly) {
            BedrockPattern pattern = patterns.computeIfAbsent(observation.dimension(), dimension -> new BedrockPattern(dimension, structureSeed));
            if (pattern.isBedrock(observation.x(), observation.y(), observation.z()) != observation.bedrock()) {
                mismatches++;
            }
        }
        return mismatches;
    }

    /**
     * How many whole-seed observations disagree with a world seed. Counting stops early once it is above
     * {@code limit} (the result is then {@code limit + 1}).
     */
    public int fullSeedMismatches(long worldSeed, int limit) {
        if (positionSeeds.length == 0) {
            return 0;
        }
        // The world's block generator root (RandomState), then one factory per bedrock rule name.
        XoroshiroRandom root = new XoroshiroRandom(worldSeed);
        long rootLo = root.nextLong();
        long rootHi = root.nextLong();
        long[] factoryLo = new long[hashLo.length];
        long[] factoryHi = new long[hashLo.length];
        for (int i = 0; i < hashLo.length; i++) {
            XoroshiroRandom named = new XoroshiroRandom(hashLo[i] ^ rootLo, hashHi[i] ^ rootHi);
            factoryLo[i] = named.nextLong();
            factoryHi[i] = named.nextLong();
        }
        int mismatches = 0;
        for (int i = 0; i < positionSeeds.length; i++) {
            int name = nameIndex[i];
            long number = XoroshiroRandom.firstLong(positionSeeds[i] ^ factoryLo[name], factoryHi[name]) >>> 40;
            if ((number < thresholds[i]) != bedrockWhenBelow[i] && ++mismatches > limit) {
                return mismatches;
            }
        }
        return mismatches;
    }

    /** The observations a world seed disagrees with, for showing which notes are probably wrong. */
    public List<BedrockObservation> mismatchesOf(long worldSeed) {
        Map<BedrockDimension, BedrockPattern> patterns = new HashMap<>();
        List<BedrockObservation> wrong = new ArrayList<>();
        for (BedrockObservation observation : all) {
            BedrockPattern pattern = patterns.computeIfAbsent(observation.dimension(), dimension -> new BedrockPattern(dimension, worldSeed));
            if (pattern.isBedrock(observation.x(), observation.y(), observation.z()) != observation.bedrock() && !wrong.contains(observation)) {
                wrong.add(observation);
            }
        }
        return wrong;
    }
}
