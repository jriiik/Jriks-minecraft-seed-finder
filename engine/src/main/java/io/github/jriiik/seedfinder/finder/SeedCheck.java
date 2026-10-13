package io.github.jriiik.seedfinder.finder;

import io.github.jriiik.seedfinder.Seeds;
import io.github.jriiik.seedfinder.bedrock.BedrockEvidence;
import io.github.jriiik.seedfinder.bedrock.BedrockObservation;
import io.github.jriiik.seedfinder.structure.RandomSpreadPlacement;
import io.github.jriiik.seedfinder.structure.SpreadType;
import io.github.jriiik.seedfinder.structure.StructureLocator;
import io.github.jriiik.seedfinder.structure.StructureSet;
import io.github.jriiik.seedfinder.version.VersionData;
import java.util.ArrayList;
import java.util.List;

/**
 * How the notes fit a world seed that was found without them: a typed seed found from the hashed seed alone
 * ({@link TypedSeedSearch}). That seed is the world's if the server sends the real hashed seed. A server that sends a
 * made-up hashed seed could send the hash of some typed seed, so the structures and bedrock noted either confirm the
 * seed or show that it isn't this world's.
 *
 * <p>Structures are checked with the game's placement rules (whether the seed lets a structure of that set start in
 * that chunk; biomes aren't checked, see {@link StructureLocator}); strongholds can't be checked yet and are skipped.
 * Bedrock is checked block by block, unless the bedrock notes break the game's rules whatever the seed: then it isn't
 * used.
 *
 * @param worldSeed         the seed checked
 * @param verdict           what the notes say about it
 * @param fitting           the noted structures the seed lets start where they were seen
 * @param misfits           the noted structures the seed doesn't let start there
 * @param bedrockBlocks     the noted bedrock blocks that depend on the seed (0 when bedrock isn't used)
 * @param bedrockMismatches how many of them the seed gets wrong
 * @param falseFitChance    the chance that a wrong seed would fit the structures and the bedrock that this one fits
 *                          (overworld bedrock only: it depends on the whole seed, and its chance is measured)
 */
public record SeedCheck(
    long worldSeed,
    Verdict verdict,
    List<StructureObservation> fitting,
    List<StructureObservation> misfits,
    int bedrockBlocks,
    int bedrockMismatches,
    double falseFitChance
) {

    /** What the notes say about the seed. */
    public enum Verdict {
        /** So much of the notes fits that a wrong seed would not (some notes may still disagree, see the lists). */
        CONFIRMED,
        /** Nothing noted disagrees, but too little is noted to tell a wrong seed apart. */
        UNCONFIRMED,
        /** Notes disagree with it, and too little fits to outweigh them. */
        CONTRADICTED,
    }

    /** The seed counts as confirmed when a wrong seed would fit the notes with at most this chance. */
    public static final double MAX_FALSE_FIT_CHANCE = 0.001;

    public SeedCheck {
        fitting = List.copyOf(fitting);
        misfits = List.copyOf(misfits);
    }

    public static SeedCheck of(VersionData data, List<StructureObservation> structures, List<BedrockObservation> bedrock, long worldSeed) {
        StructureLocator locator = new StructureLocator(data, worldSeed);
        List<StructureObservation> fitting = new ArrayList<>();
        List<StructureObservation> misfits = new ArrayList<>();
        double chance = 1;
        for (StructureObservation structure : structures) {
            if (!StructureLocator.canLocate(structure.set())) {
                continue;
            }
            boolean fits;
            try {
                fits = locator.isStructureChunk(structure.set(), structure.chunk().x(), structure.chunk().z());
            } catch (UnsupportedOperationException e) {
                continue; // an exclusion zone around strongholds: can't be checked yet
            }
            if (fits) {
                fitting.add(structure);
                chance *= startChance(structure.set());
            } else {
                misfits.add(structure);
            }
        }

        BedrockEvidence evidence = new BedrockEvidence(bedrock);
        int bedrockBlocks = 0;
        int mismatches = 0;
        if (evidence.problems().isEmpty()) {
            int overworldBlocks = evidence.fullSeedObservations().size();
            int overworldMismatches = evidence.fullSeedMismatches(worldSeed, Integer.MAX_VALUE - 1);
            bedrockBlocks = overworldBlocks + evidence.structureSeedObservations().size();
            mismatches = overworldMismatches + evidence.structureSeedMismatches(Seeds.structureSeed(worldSeed));
            if (overworldMismatches == 0 && overworldBlocks > 0) {
                chance *= FullSeedFinder.falseFitChance(overworldBlocks);
            }
        }

        Verdict verdict = chance <= MAX_FALSE_FIT_CHANCE ? Verdict.CONFIRMED
            : !misfits.isEmpty() || mismatches > 0 ? Verdict.CONTRADICTED
            : Verdict.UNCONFIRMED;
        return new SeedCheck(worldSeed, verdict, fitting, misfits, bedrockBlocks, mismatches, chance);
    }

    /**
     * The largest chance that a seed lets this set start in one given chunk: the region's start chunk is picked at
     * random (for triangular spread, the middle is likelier), and frequency rolls and exclusion zones only make it
     * smaller. 1 for sets that start in the same place in every world.
     */
    static double startChance(StructureSet set) {
        if (!(set.placement() instanceof RandomSpreadPlacement spread)) {
            return 1;
        }
        double limit = spread.spacing() - spread.separation();
        double perAxis = spread.spreadType() == SpreadType.TRIANGULAR ? (2 * limit - 1) / (limit * limit) : 1 / limit;
        return perAxis * perAxis;
    }
}
