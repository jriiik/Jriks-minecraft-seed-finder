package io.github.jriiik.seedfinder.finder;

import io.github.jriiik.seedfinder.bedrock.BedrockDimension;
import io.github.jriiik.seedfinder.bedrock.BedrockEvidence;
import io.github.jriiik.seedfinder.bedrock.BedrockObservation;
import io.github.jriiik.seedfinder.bedrock.BedrockPattern;
import io.github.jriiik.seedfinder.structure.RandomSpreadPlacement;
import io.github.jriiik.seedfinder.structure.SpreadType;
import io.github.jriiik.seedfinder.structure.StructureLocator;
import io.github.jriiik.seedfinder.structure.StructureSet;
import io.github.jriiik.seedfinder.version.VersionData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How the notes fit a world seed that was found without them: a typed seed found from the hashed seed alone
 * ({@link TypedSeedSearch}). That seed is the world's if the server sends the real hashed seed. A server that sends a
 * made-up hashed seed could send the hash of some typed seed, so the structures and bedrock noted either confirm the
 * seed or show that it isn't this world's.
 *
 * <p>The seed is confirmed when a wrong seed would fit the notes at least as well with a chance of at most
 * {@link #MAX_FALSE_FIT_CHANCE}:
 * <ul>
 *   <li>Structures: a wrong seed lets a structure start in a given chunk with a small chance, at most
 *       {@link #startChance}. The chance that it fits at least as many of the noted structures as this seed does is
 *       at most the sum, over every group of that many notes, of the product of their chances. Notes in the same
 *       region of sets with nearly the same salt get related random numbers in every world (and two notes of one set
 *       in one region can't both fit), so such notes count once, with the sum of their chances.</li>
 *   <li>Overworld bedrock, when every block fits: the measured false-fit rate ({@link FullSeedFinder#falseFitChance})
 *       times {@link #BEDROCK_SAFETY}, because that rate is an average over worlds and single worlds were measured up
 *       to about 5 times higher for small notes.</li>
 * </ul>
 * Overworld bedrock that doesn't fit contradicts the seed: the right seed fits every valid overworld bedrock note,
 * also in chunks of worlds made before Minecraft 1.18 (their part below y = 0 was made with the seed). Nether bedrock
 * that doesn't fit contradicts it unless the rest confirms it, because nether chunks made by 1.17 or older have
 * bedrock that doesn't follow the seed; the same goes for structures, because a server may place structures
 * differently and chunks made by older versions or commands don't follow the seed. Bedrock notes that break the
 * game's rules whatever the seed, or say both bedrock and not bedrock for one block, are left out and reported.
 * Structures are checked with the game's placement rules without biomes (see {@link StructureLocator}); strongholds
 * can't be checked yet and are skipped.
 *
 * @param worldSeed       the seed checked
 * @param verdict         what the notes say about it
 * @param fitting         the noted structures the seed lets start where they were seen (each once)
 * @param misfits         the noted structures the seed doesn't let start there
 * @param overworld       the overworld bedrock checked (it depends on the whole seed)
 * @param nether          the nether bedrock checked (it depends on the structure seed only)
 * @param bedrockProblems bedrock notes left out because they are wrong whatever the seed
 * @param falseFitChance  at most this chance that a wrong seed fits the notes as well as this one (1 when the
 *                        overworld bedrock doesn't fit)
 */
public record SeedCheck(
    long worldSeed,
    Verdict verdict,
    List<StructureObservation> fitting,
    List<StructureObservation> misfits,
    Bedrock overworld,
    Bedrock nether,
    List<String> bedrockProblems,
    double falseFitChance
) {

    /**
     * Bedrock blocks of one kind that depend on the seed and were checked, how many the seed gets wrong, and the first
     * few of those (at most {@link #EXAMPLES}).
     */
    public record Bedrock(int blocks, int mismatches, List<BedrockObservation> examples) {

        /** How many blocks the seed gets wrong are kept as examples. */
        public static final int EXAMPLES = 5;

        public Bedrock {
            examples = List.copyOf(examples);
        }

        /** True when there is bedrock of this kind and all of it fits. */
        public boolean fits() {
            return blocks > 0 && mismatches == 0;
        }
    }

    /** What the notes say about the seed. */
    public enum Verdict {
        /** A wrong seed would fit the notes this well only by a rare chance (some structures may still disagree). */
        CONFIRMED,
        /** Nothing noted disagrees, but too little is noted to tell a wrong seed apart. */
        UNCONFIRMED,
        /** Overworld bedrock disagrees, or other notes disagree and too little fits to outweigh them. */
        CONTRADICTED,
    }

    /** The seed counts as confirmed when a wrong seed would fit the notes this well with at most this chance. */
    public static final double MAX_FALSE_FIT_CHANCE = 0.001;
    /** How much more often than the measured average a wrong seed may fit the bedrock of one particular world. */
    static final double BEDROCK_SAFETY = 10;

    public SeedCheck {
        fitting = List.copyOf(fitting);
        misfits = List.copyOf(misfits);
        bedrockProblems = List.copyOf(bedrockProblems);
    }

    /**
     * True when the overworld bedrock doesn't fit, but only a few blocks of many (at most a tenth, or 4, of 40 or more):
     * a wrong seed gets about a third of them wrong, so those blocks were probably noted or read wrong. The seed still
     * doesn't count as confirmed.
     */
    public boolean bedrockMostlyFits() {
        return overworld.mismatches() > 0 && overworld.blocks() >= 40 && overworld.mismatches() <= Math.max(4, overworld.blocks() / 10);
    }

    public static SeedCheck of(VersionData data, List<StructureObservation> structures, List<BedrockObservation> bedrock, long worldSeed) {
        StructureLocator locator = new StructureLocator(data, worldSeed);
        List<StructureObservation> checked = new ArrayList<>();
        List<StructureObservation> fitting = new ArrayList<>();
        List<StructureObservation> misfits = new ArrayList<>();
        for (StructureObservation structure : new LinkedHashSet<>(structures)) {
            if (!StructureLocator.canLocate(structure.set())) {
                continue;
            }
            boolean fits;
            try {
                fits = locator.isStructureChunk(structure.set(), structure.chunk().x(), structure.chunk().z());
            } catch (UnsupportedOperationException e) {
                continue; // an exclusion zone around strongholds: can't be checked yet
            }
            checked.add(structure);
            (fits ? fitting : misfits).add(structure);
        }
        double chance = structuresFitChance(checked, new HashSet<>(fitting));

        BedrockEvidence evidence = new BedrockEvidence(bedrock);
        Set<String> conflicting = conflictingBlocks(bedrock);
        Bedrock overworld = check(evidence.fullSeedObservations(), conflicting, worldSeed);
        Bedrock nether = check(evidence.structureSeedObservations(), conflicting, worldSeed);
        if (overworld.mismatches() > 0) {
            chance = 1;
        } else if (overworld.blocks() > 0) {
            chance *= Math.min(1, BEDROCK_SAFETY * FullSeedFinder.falseFitChance(overworld.blocks()));
        }

        Verdict verdict = overworld.mismatches() > 0 ? Verdict.CONTRADICTED
            : chance <= MAX_FALSE_FIT_CHANCE ? Verdict.CONFIRMED
            : !misfits.isEmpty() || nether.mismatches() > 0 ? Verdict.CONTRADICTED
            : Verdict.UNCONFIRMED;
        return new SeedCheck(worldSeed, verdict, fitting, misfits, overworld, nether, evidence.problems(), chance);
    }

    private static Bedrock check(List<BedrockObservation> observations, Set<String> conflicting, long worldSeed) {
        Map<BedrockDimension, BedrockPattern> patterns = new HashMap<>();
        int blocks = 0;
        List<BedrockObservation> wrong = new ArrayList<>();
        int mismatches = 0;
        for (BedrockObservation observation : observations) {
            if (conflicting.contains(key(observation))) {
                continue;
            }
            blocks++;
            BedrockPattern pattern = patterns.computeIfAbsent(observation.dimension(), dimension -> new BedrockPattern(dimension, worldSeed));
            if (pattern.isBedrock(observation.x(), observation.y(), observation.z()) != observation.bedrock()) {
                mismatches++;
                if (wrong.size() < Bedrock.EXAMPLES) {
                    wrong.add(observation);
                }
            }
        }
        return new Bedrock(blocks, mismatches, wrong);
    }

    /**
     * At most this chance that a wrong seed fits at least as many of the checked structures as the {@code fitting}
     * ones. Linked notes form one unit, which fits when any of its notes fits, with at most the sum of their chances;
     * units are independent. For units with chances q, k or more fit with a chance of at most e_k(q), the sum of the
     * products of every k of them, and at most e_j(q) for every j below k as well.
     */
    static double structuresFitChance(List<StructureObservation> checked, Set<StructureObservation> fitting) {
        int count = checked.size();
        int[] unit = new int[count];
        for (int i = 0; i < count; i++) {
            unit[i] = i;
        }
        for (int i = 0; i < count; i++) {
            for (int j = i + 1; j < count; j++) {
                if (linked(checked.get(i), checked.get(j))) {
                    int from = unit[j];
                    int to = unit[i];
                    for (int u = 0; u < count; u++) {
                        if (unit[u] == from) {
                            unit[u] = to;
                        }
                    }
                }
            }
        }
        Map<Integer, Double> unitChance = new HashMap<>();
        Set<Integer> unitFits = new HashSet<>();
        for (int i = 0; i < count; i++) {
            unitChance.merge(unit[i], startChance(checked.get(i).set()), Double::sum);
            if (fitting.contains(checked.get(i))) {
                unitFits.add(unit[i]);
            }
        }
        int k = unitFits.size();
        double[] e = new double[k + 1];
        e[0] = 1;
        for (double q : unitChance.values()) {
            double chance = Math.min(1, q);
            for (int j = k; j >= 1; j--) {
                e[j] += e[j - 1] * chance;
            }
        }
        double bound = 1;
        for (double value : e) {
            bound = Math.min(bound, value);
        }
        return bound;
    }

    /**
     * Whether two notes get related random numbers in every world: the same region (by each set's own grid) of sets
     * whose salts differ by little (the four temples; ocean monuments and end cities even share theirs), or one set
     * twice in one region.
     */
    static boolean linked(StructureObservation a, StructureObservation b) {
        if (!(a.set().placement() instanceof RandomSpreadPlacement pa) || !(b.set().placement() instanceof RandomSpreadPlacement pb)) {
            return false;
        }
        return pa.regionOf(a.chunk().x()) == pb.regionOf(b.chunk().x()) && pa.regionOf(a.chunk().z()) == pb.regionOf(b.chunk().z())
            && Math.abs((long) pa.salt() - pb.salt()) <= ObservationCheck.LINKED_SALT_DISTANCE;
    }

    /**
     * The largest chance that a seed lets this set start in one given chunk: the region's start chunk is picked at
     * random (for triangular spread, the middle is likelier), and frequency rolls and exclusion zones only make it
     * smaller. 1 for sets that start in the same place in every world, or in every chunk.
     */
    static double startChance(StructureSet set) {
        if (!(set.placement() instanceof RandomSpreadPlacement spread)) {
            return 1;
        }
        double limit = spread.spacing() - spread.separation();
        double perAxis = spread.spreadType() == SpreadType.TRIANGULAR ? (2 * limit - 1) / (limit * limit) : 1 / limit;
        return perAxis * perAxis;
    }

    /** Blocks noted both as bedrock and as not bedrock: one of the two notes is wrong, so neither is used. */
    private static Set<String> conflictingBlocks(List<BedrockObservation> bedrock) {
        Map<String, Boolean> seen = new HashMap<>();
        Set<String> conflicting = new HashSet<>();
        for (BedrockObservation observation : bedrock) {
            Boolean earlier = seen.putIfAbsent(key(observation), observation.bedrock());
            if (earlier != null && earlier != observation.bedrock()) {
                conflicting.add(key(observation));
            }
        }
        return conflicting;
    }

    private static String key(BedrockObservation observation) {
        return observation.dimension().id() + " " + observation.x() + " " + observation.y() + " " + observation.z();
    }
}
