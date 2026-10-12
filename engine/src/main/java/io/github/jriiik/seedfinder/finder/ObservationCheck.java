package io.github.jriiik.seedfinder.finder;

import io.github.jriiik.seedfinder.rng.JavaRandom;
import io.github.jriiik.seedfinder.rng.WorldgenSeeds;
import io.github.jriiik.seedfinder.structure.RandomSpreadPlacement;
import io.github.jriiik.seedfinder.structure.SpreadType;

/**
 * One observation turned into fast tests on candidate structure seeds. The tests only look at the structure's
 * position (and, for sets with a candidate in every chunk, its frequency roll); the finder confirms every hit
 * with the full engine rules afterwards.
 */
final class ObservationCheck {

    /** Java's nextInt(n) reads the generator's state from bit 17 upwards. */
    private static final int OUTPUT_SHIFT = 17;

    /** Sets on the same grid with salts this close behave almost the same in the same region (the four temple types). */
    private static final int LINKED_SALT_DISTANCE = 16;

    final StructureObservation observation;
    private final RandomSpreadPlacement placement;
    private final int regionX;
    private final int regionZ;
    /** What the region adds to the structure seed before seeding: regionX * K1 + regionZ * K2 + salt. */
    private final long seedOffset;
    private final int bound;
    private final int offsetX;
    private final int offsetZ;
    private final int liftBits;
    private final int liftMask;
    private final double probability;

    ObservationCheck(StructureObservation observation, RandomSpreadPlacement placement) {
        this.observation = observation;
        this.placement = placement;
        this.regionX = placement.regionOf(observation.chunk().x());
        this.regionZ = placement.regionOf(observation.chunk().z());
        this.seedOffset = WorldgenSeeds.largeFeatureWithSalt(0, regionX, regionZ, placement.salt());
        this.bound = placement.spacing() - placement.separation();
        this.offsetX = observation.chunk().x() - regionX * placement.spacing();
        this.offsetZ = observation.chunk().z() - regionZ * placement.spacing();

        // nextInt(n) returns (31 bits of state) % n. When n = 2^t * odd, the result's lowest t bits are the state's
        // bits 17..17+t-1, which only depend on the lowest 17+t bits of the structure seed. (Powers of two take a
        // different path in nextInt and reveal no low bits; neither does the averaging in triangular spreads.)
        int twos = Integer.numberOfTrailingZeros(bound);
        boolean liftable = placement.spreadType() == SpreadType.LINEAR && twos > 0 && Integer.bitCount(bound) > 1;
        this.liftBits = liftable ? OUTPUT_SHIFT + twos : 0;
        this.liftMask = (1 << twos) - 1;
        this.probability = positionProbability() * (placement.frequency() < 1 ? placement.frequency() : 1);
    }

    /** Whether the observed chunk is one where this set can start at all. */
    boolean isPossibleStartChunk() {
        return offsetX < bound && offsetZ < bound;
    }

    /**
     * Whether this observation's position is nearly decided by the other one's: same grid, same region and salts
     * that differ by only a few (desert pyramids, igloos, jungle temples and swamp huts). Their seeds then differ by
     * the same small number for every world, and measurements show the second one adds only about 4 of its 9 bits.
     */
    boolean isLinkedTo(ObservationCheck other) {
        RandomSpreadPlacement a = placement;
        RandomSpreadPlacement b = other.placement;
        return !observation.set().equals(other.observation.set())
            && a.spacing() == b.spacing() && a.separation() == b.separation() && a.spreadType() == b.spreadType()
            && regionX == other.regionX && regionZ == other.regionZ
            && Math.abs((long) a.salt() - b.salt()) <= LINKED_SALT_DISTANCE;
    }

    /** Whether {@link TopBitsSolver} can use this observation (plain nextInt(n) with n not a power of two). */
    boolean canSolveTopBits() {
        return placement.spreadType() == SpreadType.LINEAR && Integer.bitCount(bound) > 1;
    }

    /** The odd part of the region size: the solver leaves one top-bits candidate in this many. */
    int oddPart() {
        return bound >> Integer.numberOfTrailingZeros(bound);
    }

    long seedOffset() {
        return seedOffset;
    }

    int bound() {
        return bound;
    }

    int offsetX() {
        return offsetX;
    }

    /** How many low bits of the structure seed {@link #lowBitsMatch} needs, or 0 if this check can't filter low bits. */
    int liftBits() {
        return liftBits;
    }

    /** Tests only the lowest {@link #liftBits()} bits of a structure seed (the other bits are ignored). */
    boolean lowBitsMatch(long lowSeed) {
        long mask = (1L << liftBits) - 1;
        long state = ((lowSeed + seedOffset) ^ JavaRandom.MULTIPLIER) & mask;
        state = (state * JavaRandom.MULTIPLIER + JavaRandom.ADDEND) & mask;
        if ((int) (state >>> OUTPUT_SHIFT & liftMask) != (offsetX & liftMask)) {
            return false;
        }
        state = (state * JavaRandom.MULTIPLIER + JavaRandom.ADDEND) & mask;
        return (int) (state >>> OUTPUT_SHIFT & liftMask) == (offsetZ & liftMask);
    }

    /** Fast test of a full 48-bit structure seed. */
    boolean matches(long structureSeed, JavaRandom random) {
        if (bound == 1) {
            // Every region's candidate is fixed, so only a frequency roll can tell seeds apart.
            return placement.passesFrequency(structureSeed, observation.chunk().x(), observation.chunk().z());
        }
        random.setSeed(structureSeed + seedOffset);
        SpreadType spread = placement.spreadType();
        return spread.evaluate(random, bound) == offsetX && spread.evaluate(random, bound) == offsetZ;
    }

    /** The chance that a random seed passes this observation (for estimates). */
    double probability() {
        return probability;
    }

    /** The chance of passing once the low bits are already known to match. */
    double remainingProbability() {
        return liftBits > 0 ? probability * (liftMask + 1) * (liftMask + 1) : probability;
    }

    /**
     * The chance that a "twin" of a matching seed matches too. A twin differs only in bits 35..47; that moves the
     * 31-bit value behind nextInt by the same 2^18 * shift (mod 2^31) for every structure, so the position is kept
     * whenever the shift is a multiple of the odd part of the region size (or becomes one after wrapping around).
     *
     * @param shiftX how far (in units of 2^18) the value behind the x coordinate moves, from 0 to 8191
     * @param shiftZ the same for the z coordinate
     */
    double twinProbability(int shiftX, int shiftZ) {
        if (bound == 1 || placement.spreadType() != SpreadType.LINEAR || Integer.bitCount(bound) == 1) {
            return probability; // no simple pattern: treat the twin like any other seed
        }
        int odd = bound >> Integer.numberOfTrailingZeros(bound);
        double frequency = placement.frequency() < 1 ? placement.frequency() : 1;
        return keepsPosition(shiftX, odd) * keepsPosition(shiftZ, odd) * frequency;
    }

    private static double keepsPosition(int shift, int odd) {
        double wrapChance = shift / (double) TWIN_SHIFTS;
        return (shift % odd == 0 ? 1 - wrapChance : 0) + (Math.floorMod(shift - TWIN_SHIFTS, odd) == 0 ? wrapChance : 0);
    }

    /** Number of different twin shifts: bits 35..47 of the seed give 2^13 possibilities. */
    static final int TWIN_SHIFTS = 1 << 13;

    private double positionProbability() {
        if (bound == 1) {
            return 1;
        }
        return switch (placement.spreadType()) {
            case LINEAR -> 1.0 / bound / bound;
            case TRIANGULAR -> triangular(offsetX) * triangular(offsetZ);
        };
    }

    /** Chance that (nextInt(n) + nextInt(n)) / 2 equals the offset. */
    private double triangular(int offset) {
        int ways = 0;
        for (int a = 0; a < bound; a++) {
            int b1 = 2 * offset - a;
            int b2 = 2 * offset + 1 - a;
            ways += (b1 >= 0 && b1 < bound ? 1 : 0) + (b2 >= 0 && b2 < bound ? 1 : 0);
        }
        return (double) ways / bound / bound;
    }
}
