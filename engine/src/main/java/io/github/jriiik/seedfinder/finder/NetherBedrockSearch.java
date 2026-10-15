package io.github.jriiik.seedfinder.finder;

import static io.github.jriiik.seedfinder.finder.BedrockSurface.A;
import static io.github.jriiik.seedfinder.finder.BedrockSurface.C;
import static io.github.jriiik.seedfinder.finder.BedrockSurface.FULL;
import static io.github.jriiik.seedfinder.finder.BedrockSurface.MASK;

import io.github.jriiik.seedfinder.bedrock.BedrockDimension;
import io.github.jriiik.seedfinder.bedrock.BedrockEvidence;
import io.github.jriiik.seedfinder.bedrock.BedrockObservation;
import io.github.jriiik.seedfinder.bedrock.BedrockPattern;
import io.github.jriiik.seedfinder.bedrock.BedrockRule;
import io.github.jriiik.seedfinder.rng.JavaRandom;
import io.github.jriiik.seedfinder.rng.WorldgenSeeds;
import io.github.jriiik.seedfinder.version.VersionData;
import io.github.jriiik.seedfinder.world.ChunkPos;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/**
 * Finds the structure seed (the lowest 48 bits of the world seed) from the random bedrock of the nether alone: its
 * floor (y 1 to 4) and its roof (y 123 to 126), in chunks made by Minecraft 1.18 or later.
 *
 * <p><b>How the game makes this bedrock.</b> The nether's blocks use the old 48-bit generator (the one of
 * {@code java.util.Random}). From the world seed the game takes one {@code nextLong()}, P. Each bedrock rule (floor,
 * roof) then takes {@code nextLong()} of a generator set to {@code hashCode(rule name) ^ P}: the start number of the
 * rule's block generators. Each block starts its own generator at that number xor its position number, and is bedrock
 * or not depending on whether its first 24-bit number is below the height's threshold ({@link BedrockRule#threshold}).
 * Setting a generator keeps only 48 bits, so the nether's bedrock depends on the world seed only through the lowest 48
 * bits of P: its <em>bedrock key</em> ({@link #bedrockKey}).
 *
 * <p><b>What bedrock can tell.</b>
 * <ul>
 *   <li>The key, never more. About 23% of structure seeds share their key with exactly one other structure seed (the
 *       other 77% have it alone), and two such seeds have the same nether bedrock everywhere. So a key means one or two
 *       structure seeds ({@link #structureSeedsOfKey}); structures, overworld bedrock or the hashed seed tell them
 *       apart.</li>
 *   <li>One surface (only the floor, or only the roof) never pins its hidden number down. Numbers that differ from the
 *       right one in a few bits move every block's 24-bit number only a little, so they agree with it on every block
 *       that is not right at its threshold: a chunk's floor leaves 3 to 16 of these near-misses, a 4 x 4 patch
 *       thousands. Each gives an unrelated key, so one surface gives several keys (2 to about 500), all of which fit
 *       it. The other surface removes them.</li>
 * </ul>
 *
 * <p><b>How the search works.</b> One surface (the one that looks cheaper to search) is searched for every hidden
 * number that fits its blocks ({@code BedrockSurfaceSearch}; {@link #PIECES} independent pieces on several threads).
 * Each one is turned back into keys by stepping the generator backwards, each key is checked against the other
 * surface, turned back into structure seeds, and every structure seed is checked once more from scratch with
 * {@link BedrockPattern} (one that doesn't fit is dropped). Nothing that fits is ever left out, so if the notes are
 * right and the chunks were made by 1.18 or later, the world's structure seed is in the result. Measured on a busy
 * 4-CPU computer: one chunk's floor and roof 0.4 to 1.8 s on one thread (0.25 to 0.8 s on two); 2 x 2 chunks 0.1 to
 * 0.4 s on one thread; one floor and one roof layer 3 to 6 s on two threads; in the research, 50 to 100 scattered
 * blocks of each surface 20 to 60 s on two threads. (19MisterX98's Nether_Bedrock_Cracker also searches one surface
 * and checks the other after stepping back to P; it was read for ideas only.)
 *
 * <p><b>When a result counts</b> ({@link Outcome#proven()}). A wrong key can get into the result in two ways; both
 * must be rare, at most {@link #MAX_FALSE_FIT_CHANCE} wrong keys expected:
 * <ul>
 *   <li>Notes that don't follow the formula at all (a chunk made before 1.18, a server that changes bedrock): a key
 *       unrelated to the notes fits each block with the share of numbers the block allows, so about
 *       2^(48 - bits) keys fit by chance, where bits is the information of both surfaces together ({@link Plan}).
 *       That needs {@link #PROOF_BITS} bits: about 70 blocks on average; bedrock at y 4 or y 123, or a gap at y 1 or
 *       y 126, is worth 2.3 bits, bedrock at y 1 or y 126 only 0.3.</li>
 *   <li>Right notes with a few blocks misread: then the right key fails, but near-misses of the searched surface may
 *       fit the misread blocks. Their keys are unrelated to the other surface, which lets each through with the chance
 *       2^-(its bits); so N fitting numbers of the searched surface let about N * 2^-(other bits) wrong keys through
 *       ({@link Outcome#expectedNearMissKeys()}). One surface alone has no such check: its result is a list of
 *       candidates (the right key and the near-misses' keys, which fit just as well), never proven, but still useful
 *       with the hashed seed.</li>
 * </ul>
 *
 * <p><b>Chunks made before 1.18</b> keep their old bedrock (also all bedrock at y 0 and y 127), as do servers that change
 * bedrock; such chunks fit no seed, and one of them among the notes makes a search of all notes together come back
 * empty. {@link #find} therefore searches one chunk at a time and checks the answer against the other chunks.
 */
public final class NetherBedrockSearch {

    /** The dimension whose bedrock this searches. */
    public static final String NETHER = "minecraft:the_nether";
    /** How many pieces a search is split into (by the top 12 bits of the searched surface's hidden number). */
    public static final int PIECES = BedrockSurfaceSearch.PIECES;
    /** A result is proven only when fewer wrong keys than this are expected (see the class comment). */
    public static final double MAX_FALSE_FIT_CHANCE = 0.001;
    /** Both surfaces together need this much information for a proof: 2^(48 - 58) is below one in a thousand. */
    public static final double PROOF_BITS = 58;
    /**
     * The searched surface needs this much information. With less, the fitting numbers (about 2^(48 - bits) plus the
     * near-misses) get too many to list, and the search much slower; the research measured down to about 40 bits (50
     * scattered blocks of one surface: 20 to 60 seconds).
     */
    public static final double MIN_SEARCH_BITS = 40;
    /**
     * The searched surface also needs this many rare blocks: bedrock at y 3, 4, 123 or 124, or no bedrock at y 1, 2, 125
     * or 126 (what was seen happens for at most 40% of the numbers). Only those prune the upper part of the search, so
     * its time depends mostly on them. Measured on one thread with 32 x 32 areas of the floor: 30 rare blocks about 30
     * CPU seconds, 15 rare and 35 others 80, 8 and 60 others 3 to 4 minutes, 4 and 100 others about 13 minutes, none
     * and 150 others about 70 minutes. (A chunk's floor has about 300.)
     */
    public static final int MIN_RARE_BLOCKS = 8;
    /** At most this many structure seeds are listed; with more the search stops ({@link Outcome#capped()}). */
    public static final int MAX_STRUCTURE_SEEDS = 500;
    /**
     * At most this many numbers of the searched surface are followed up; with more the search stops
     * ({@link Outcome#capped()}). Each costs about 0.1 ms to turn back into keys.
     */
    public static final int MAX_SURFACE_FITS = 1 << 16;

    /**
     * What the noted nether blocks reveal, worked out before searching. A block's information is
     * {@code log2(1 / share)}, where share is the part of all 24-bit numbers that gives what was noted (the chance
     * that a block made from an unrelated key agrees): bedrock at y 1 or y 126 (share 0.8) 0.32 bits, no bedrock there
     * 2.32; at y 2 or y 125 0.74 and 1.32; at y 3 or y 124 1.32 and 0.74; at y 4 or y 123 2.32 and 0.32.
     *
     * @param floorBlocks     informative blocks of the floor (y 1 to 4); other heights never change and tell nothing
     * @param roofBlocks      informative blocks of the roof (y 123 to 126)
     * @param floorRareBlocks how many of the floor blocks are rare (see {@link #MIN_RARE_BLOCKS})
     * @param roofRareBlocks  how many of the roof blocks are rare
     * @param floorBits       the floor blocks' information, summed
     * @param roofBits        the roof blocks' information, summed
     * @param problems        notes left out: blocks noted both as bedrock and as not bedrock, and notes that break the
     *                        game's rules whatever the seed (see {@link BedrockEvidence#problems()})
     */
    public record Plan(int floorBlocks, int roofBlocks, int floorRareBlocks, int roofRareBlocks, double floorBits, double roofBits,
                       List<String> problems) {

        public Plan {
            problems = List.copyOf(problems);
        }

        public int blocks() {
            return floorBlocks + roofBlocks;
        }

        /** The information of both surfaces together. */
        public double bits() {
            return floorBits + roofBits;
        }

        /** About how many keys would fit notes that don't follow the formula, by pure chance: 2^(48 - bits). */
        public double expectedFalseKeys() {
            return Math.pow(2, 48 - bits());
        }

        /**
         * True when the search can run: one surface has at least {@link #MIN_SEARCH_BITS} and {@link #MIN_RARE_BLOCKS}
         * rare blocks.
         */
        public boolean canSearch() {
            return canSearchFloor() || canSearchRoof();
        }

        boolean canSearchFloor() {
            return floorBits >= MIN_SEARCH_BITS && floorRareBlocks >= MIN_RARE_BLOCKS;
        }

        boolean canSearchRoof() {
            return roofBits >= MIN_SEARCH_BITS && roofRareBlocks >= MIN_RARE_BLOCKS;
        }

        /**
         * True when a result can be proven: both surfaces have blocks and together at least {@link #PROOF_BITS} (the
         * search then still checks the near-misses, see {@link Outcome#proven()}).
         */
        public boolean enough() {
            return floorBlocks > 0 && roofBlocks > 0 && bits() >= PROOF_BITS;
        }
    }

    /**
     * The result of one search.
     *
     * @param plan           what the blocks searched reveal
     * @param structureSeeds every structure seed that fits all the blocks, smallest first (at most
     *                       {@link #MAX_STRUCTURE_SEEDS}); all of them when {@link #complete()}
     * @param bedrockKeys    the bedrock keys of those seeds, smallest first: one or two seeds per key
     * @param searchedRoof   true when the roof was searched and the floor used as the check, false the other way round
     * @param surfaceFits    how many hidden numbers of the searched surface fit its blocks (the right one and its
     *                       near-misses, or numbers that fit by chance)
     * @param piecesDone     how many of the {@link #PIECES} pieces were searched
     * @param capped         true when too many seeds or numbers fit ({@link #MAX_STRUCTURE_SEEDS},
     *                       {@link #MAX_SURFACE_FITS}): the search stopped there and the lists are incomplete
     */
    public record Outcome(Plan plan, List<Long> structureSeeds, List<Long> bedrockKeys, boolean searchedRoof, long surfaceFits,
                          int piecesDone, boolean capped) {

        public Outcome {
            structureSeeds = List.copyOf(structureSeeds);
            bedrockKeys = List.copyOf(bedrockKeys);
        }

        /** True when every piece was searched and nothing was cut off: the lists hold everything that fits. */
        public boolean complete() {
            return piecesDone >= PIECES && !capped;
        }

        /**
         * About how many wrong keys near-misses of the searched surface would let through if some blocks were misread:
         * the number of fitting hidden numbers times 2^-(the other surface's bits).
         */
        public double expectedNearMissKeys() {
            return surfaceFits * Math.pow(2, -(searchedRoof ? plan.floorBits() : plan.roofBits()));
        }

        /**
         * True when the world's structure seed is one of {@link #structureSeeds()} unless the notes are wrong, and wrong
         * notes would leave a wrong key here only by a rare chance: the search is complete and found something, both
         * surfaces have blocks with at least {@link #PROOF_BITS} together, and {@link #expectedNearMissKeys()} is at
         * most {@link #MAX_FALSE_FIT_CHANCE}. (Then there is nearly always exactly one key: one or two structure
         * seeds.)
         */
        public boolean proven() {
            return complete() && !structureSeeds.isEmpty() && plan.enough() && expectedNearMissKeys() <= MAX_FALSE_FIT_CHANCE;
        }
    }

    /** Hears how far {@link #find} is. Called from the search threads, one call at a time. */
    @FunctionalInterface
    public interface Progress {

        /**
         * @param search     which search is running, counted from 0: one per chunk tried, then perhaps one of several
         *                   chunks together
         * @param piecesDone how many of its {@link #PIECES} pieces are done
         */
        void update(int search, int piecesDone);
    }

    /** What {@link #find} learned about one chunk with nether notes. */
    public enum ChunkStatus {
        /** Its blocks were in the search whose result is reported, and fit it. */
        USED,
        /** Not searched, but its blocks fit the structure seeds found. */
        FITS,
        /** Not searched, and its blocks fit none of the structure seeds found: made before 1.18, or misread. */
        CONTRADICTS,
        /** Searched on its own, and no seed fits it: made before 1.18 (or by a server that changes bedrock), or misread. */
        FITS_NO_SEED,
        /** Not searched and nothing to check it against (the search was stopped, or nothing was found). */
        NOT_CHECKED,
    }

    /** One chunk with nether notes, what they reveal, and what was learned about it. */
    public record ChunkReport(ChunkPos chunk, Plan plan, ChunkStatus status) {
    }

    /**
     * What {@link #find} found.
     *
     * @param outcome        the search whose result is reported: a chunk with a proven result, several chunks searched
     *                       together, or else the first chunk that gave candidates; empty when there was none of these
     * @param structureSeeds the structure seeds found, smallest first: those of {@code outcome}, without keys that a
     *                       chunk checked afterwards rules out (when some key fits every such chunk)
     * @param bedrockKeys    their bedrock keys
     * @param chunks         every chunk with informative nether notes, the most informative first
     * @param together       true when {@code outcome} is a search of several chunks together (or of the one chunk that
     *                       didn't have enough on its own)
     * @param complete       false when the search was stopped before it had an answer
     * @param plan           what all the nether notes reveal together, with the notes left out
     */
    public record Finding(Optional<Outcome> outcome, List<Long> structureSeeds, List<Long> bedrockKeys, List<ChunkReport> chunks,
                          boolean together, boolean complete, Plan plan) {

        public Finding {
            structureSeeds = List.copyOf(structureSeeds);
            bedrockKeys = List.copyOf(bedrockKeys);
            chunks = List.copyOf(chunks);
        }

        /** True when {@link #outcome()} is proven (see {@link Outcome#proven()}). */
        public boolean proven() {
            return outcome.isPresent() && outcome.get().proven() && !structureSeeds.isEmpty();
        }

        /** The chunks that fit no seed or contradict the one found: made before 1.18, or misread. */
        public List<ChunkPos> unusableChunks() {
            return chunks.stream()
                .filter(report -> report.status() == ChunkStatus.FITS_NO_SEED || report.status() == ChunkStatus.CONTRADICTS)
                .map(ChunkReport::chunk)
                .toList();
        }
    }

    private final BedrockDimension dimension;
    private final BedrockRule floor;
    private final BedrockRule roof;

    /**
     * Uses the nether's bedrock rules of this version.
     *
     * @throws IllegalArgumentException when the version's nether has no floor and roof of random bedrock made with the
     *                                  old 48-bit generator
     */
    public NetherBedrockSearch(VersionData data) {
        BedrockDimension nether = data.bedrock().get(NETHER);
        if (nether == null) {
            throw new IllegalArgumentException("Minecraft " + data.version() + " has no bedrock rules for " + NETHER);
        }
        if (!nether.legacyRandomSource()) {
            throw new IllegalArgumentException("The nether of Minecraft " + data.version()
                + " doesn't use the old 48-bit generator, which this search needs");
        }
        List<BedrockRule> floors = nether.rules().stream().filter(rule -> !rule.inverted()).toList();
        List<BedrockRule> roofs = nether.rules().stream().filter(BedrockRule::inverted).toList();
        if (floors.size() != 1 || roofs.size() != 1) {
            throw new IllegalArgumentException("The nether of Minecraft " + data.version() + " has " + floors.size() + " floor and "
                + roofs.size() + " roof bedrock rules; this search needs one of each");
        }
        BedrockRule floorRule = floors.get(0);
        BedrockRule roofRule = roofs.get(0);
        for (int y = floorRule.trueAtAndBelow() + 1; y < floorRule.falseAtAndAbove(); y++) {
            if (roofRule.isRandomAt(y)) {
                throw new IllegalArgumentException("The nether's floor and roof bedrock overlap at y=" + y);
            }
        }
        this.dimension = nether;
        this.floor = floorRule;
        this.roof = roofRule;
    }

    /** The nether these rules belong to. */
    public BedrockDimension dimension() {
        return dimension;
    }

    // ------------------------------------------------------------------------------------------------ the model

    /** {@code nextLong()} of the old generator in this 48-bit state (before its first step). */
    static long nextLong(long state) {
        long first = (state * A + C) & MASK;
        long second = (first * A + C) & MASK;
        return ((long) (int) (first >>> 16) << 32) + (int) (second >>> 16);
    }

    /**
     * The bedrock key of a structure seed: the lowest 48 bits of the first {@code nextLong()} of its generator (only the
     * seed's lowest 48 bits count, so a world seed gives the same).
     */
    public static long bedrockKey(long structureSeed) {
        return nextLong((structureSeed ^ A) & MASK) & MASK;
    }

    /** The hidden number of a rule (whose name has this {@code hashCode()}) for a bedrock key. */
    static long hiddenNumber(long key, int hash) {
        return (nextLong(((long) hash ^ key ^ A) & MASK) ^ A) & MASK;
    }

    /** The structure seeds with this bedrock key: usually one, sometimes two or none. */
    public static long[] structureSeedsOfKey(long key) {
        long[] states = statesBeforeNextLong(key & MASK);
        for (int i = 0; i < states.length; i++) {
            states[i] = (states[i] ^ A) & MASK;
        }
        Arrays.sort(states);
        return states;
    }

    /**
     * Every 48-bit state of the old generator whose {@code nextLong()} has these lowest 48 bits: none, one or two
     * (rarely more). Bits 0 to 31 of {@code nextLong()} are the second 32-bit output (bits 16 to 47 of the second
     * state), so only that state's 16 lowest bits are unknown; bits 32 to 47 are the first output's lowest 16 bits,
     * minus 1 when the second output is negative (it is added as a signed int). Each of the 65,536 guesses is stepped
     * back once and kept when the first output fits.
     */
    static long[] statesBeforeNextLong(long low48) {
        long low32 = low48 & 0xFFFF_FFFFL;
        long borrow = low32 >>> 31;
        long wanted = ((low48 >>> 32) + borrow) & 0xFFFF;
        long base = low32 << 16;
        long[] found = new long[2];
        int count = 0;
        for (long hidden = 0; hidden < 1 << 16; hidden++) {
            long second = base | hidden;
            long first = ((second - C) * JavaRandom.MULTIPLIER_INVERSE) & MASK;
            if (((first >>> 16) & 0xFFFF) == wanted) {
                if (count == found.length) {
                    found = Arrays.copyOf(found, count * 2);
                }
                found[count++] = ((first - C) * JavaRandom.MULTIPLIER_INVERSE) & MASK;
            }
        }
        return Arrays.copyOf(found, count);
    }

    /**
     * The values of the block's generator state after its first step (whose top 24 bits are the block's number) that
     * give what was noted, as {low, length}: the stretch [low, low + length) of the circle of 48-bit numbers.
     */
    private long[] allowed(BedrockObservation block) {
        BedrockRule rule = ruleAt(block.y());
        long edge = (long) rule.threshold(block.y()) << 24;
        // The gradient is true when the 24-bit number is below the threshold; the roof has bedrock where it is false.
        boolean gradient = block.bedrock() != rule.inverted();
        return gradient ? new long[] {0, edge} : new long[] {edge, FULL - edge};
    }

    private BedrockRule ruleAt(int y) {
        return floor.isRandomAt(y) ? floor : roof;
    }

    private BedrockSurface surface(BedrockRule rule, List<BedrockObservation> blocks) {
        List<BedrockObservation> own = blocks.stream().filter(block -> rule.isRandomAt(block.y())).toList();
        long[] positions = new long[own.size()];
        long[] lows = new long[own.size()];
        long[] lengths = new long[own.size()];
        for (int i = 0; i < own.size(); i++) {
            BedrockObservation block = own.get(i);
            long[] stretch = allowed(block);
            positions[i] = WorldgenSeeds.positional(block.x(), block.y(), block.z()) & MASK;
            lows[i] = stretch[0];
            lengths[i] = stretch[1];
        }
        return new BedrockSurface(rule.randomName().hashCode(), positions, lows, lengths);
    }

    /** The informative blocks of the floor or the roof among these notes, compiled. */
    BedrockSurface surfaceOf(boolean ofRoof, List<BedrockObservation> observations) {
        return surface(ofRoof ? roof : floor, prepare(observations).blocks());
    }

    /** Whether a structure seed gives every block, checked from scratch with the engine's bedrock model. */
    private boolean fitsAll(long structureSeed, List<BedrockObservation> blocks) {
        BedrockPattern pattern = new BedrockPattern(dimension, structureSeed);
        for (BedrockObservation block : blocks) {
            if (pattern.isBedrock(block.x(), block.y(), block.z()) != block.bedrock()) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------------ preparing

    private record Spot(int x, int y, int z) {

        static Spot of(BedrockObservation block) {
            return new Spot(block.x(), block.y(), block.z());
        }
    }

    /** The informative nether blocks, each once, and the notes left out. */
    private record Prepared(List<BedrockObservation> blocks, List<String> problems) {
    }

    /**
     * Keeps the nether blocks at random heights, each once. Blocks noted both as bedrock and as not bedrock are left out
     * (one of the notes is wrong, and leaving a block out keeps the search exact); they, and notes that break the
     * game's rules whatever the seed, are reported by {@link BedrockEvidence}.
     */
    private Prepared prepare(List<BedrockObservation> observations) {
        List<BedrockObservation> nether = new ArrayList<>();
        Map<Spot, Boolean> seen = new HashMap<>();
        Set<Spot> conflicting = new HashSet<>();
        for (BedrockObservation observation : observations) {
            if (!observation.dimension().id().equals(dimension.id())) {
                continue;
            }
            BedrockObservation block = observation.dimension().equals(dimension) ? observation
                : new BedrockObservation(dimension, observation.x(), observation.y(), observation.z(), observation.bedrock());
            nether.add(block);
            Boolean earlier = seen.putIfAbsent(Spot.of(block), block.bedrock());
            if (earlier != null && earlier != block.bedrock()) {
                conflicting.add(Spot.of(block));
            }
        }
        BedrockEvidence evidence = new BedrockEvidence(nether);
        List<BedrockObservation> blocks = evidence.structureSeedObservations().stream()
            .filter(block -> !conflicting.contains(Spot.of(block)))
            .toList();
        return new Prepared(blocks, evidence.problems());
    }

    private Plan planOf(List<BedrockObservation> blocks, List<String> problems) {
        int floorBlocks = 0;
        int roofBlocks = 0;
        int floorRare = 0;
        int roofRare = 0;
        double floorBits = 0;
        double roofBits = 0;
        for (BedrockObservation block : blocks) {
            long length = allowed(block)[1];
            double bits = BedrockSurface.bitsOf(length);
            // Rare: what was seen happens for at most 40% of the numbers (the shares are 0.2, 0.4, 0.6 or 0.8).
            int rare = length < FULL / 2 ? 1 : 0;
            if (floor.isRandomAt(block.y())) {
                floorBlocks++;
                floorRare += rare;
                floorBits += bits;
            } else {
                roofBlocks++;
                roofRare += rare;
                roofBits += bits;
            }
        }
        return new Plan(floorBlocks, roofBlocks, floorRare, roofRare, floorBits, roofBits, problems);
    }

    /** What these notes reveal (only nether blocks at random heights count). */
    public Plan plan(List<BedrockObservation> observations) {
        Prepared prepared = prepare(observations);
        return planOf(prepared.blocks(), prepared.problems());
    }

    // ------------------------------------------------------------------------------------------------ searching

    /**
     * Finds every structure seed that fits all the nether notes (other dimensions' notes are ignored), on
     * {@code threads} threads of its own, until every piece is searched, too many fit, or {@code stop} says so (it is
     * asked before each piece; a piece takes 0.1 to 25 ms). When the plan says the search can't run, nothing is
     * searched ({@code piecesDone} 0). If a search thread fails (or {@code stop} or {@code progress} throws), the others
     * stop and the failure is thrown from here.
     *
     * @param progress hears how many pieces are done, from the search threads, one call at a time
     */
    public Outcome search(List<BedrockObservation> observations, int threads, BooleanSupplier stop, IntConsumer progress) {
        Prepared prepared = prepare(observations);
        return run(prepared.blocks(), planOf(prepared.blocks(), prepared.problems()), threads, stop, progress);
    }

    private Outcome run(List<BedrockObservation> blocks, Plan plan, int threads, BooleanSupplier stop, IntConsumer progress) {
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be at least 1, got " + threads);
        }
        if (!plan.canSearch()) {
            return new Outcome(plan, List.of(), List.of(), false, 0, 0, false);
        }
        BedrockSurface floorSurface = surface(floor, blocks);
        BedrockSurface roofSurface = surface(roof, blocks);
        if (floorSurface.impossible() || roofSurface.impossible()) {
            return new Outcome(plan, List.of(), List.of(), false, 0, PIECES, false);
        }
        // Search the surface that looks cheaper, among those that can be searched.
        BedrockSurfaceSearch floorSearch = plan.canSearchFloor() ? new BedrockSurfaceSearch(floorSurface) : null;
        BedrockSurfaceSearch roofSearch = plan.canSearchRoof() ? new BedrockSurfaceSearch(roofSurface) : null;
        boolean searchRoof = floorSearch == null
            || roofSearch != null && roofSearch.costEstimate() < floorSearch.costEstimate();
        BedrockSurfaceSearch search = searchRoof ? roofSearch : floorSearch;
        BedrockSurface other = searchRoof ? floorSurface : roofSurface;

        Collector collector = new Collector(search.surface(), other, blocks);
        AtomicInteger next = new AtomicInteger();
        Object lock = new Object();
        int[] piecesDone = {0};
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        BooleanSupplier quit = () -> cancelled.get() || collector.full() || stop.getAsBoolean();
        Runnable work = () -> {
            try {
                BedrockSurfaceSearch.Worker worker = search.worker(collector::accept);
                int piece;
                while (!quit.getAsBoolean() && (piece = next.getAndIncrement()) < PIECES) {
                    worker.searchPiece(piece);
                    synchronized (lock) {
                        piecesDone[0]++;
                        progress.accept(piecesDone[0]);
                    }
                }
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
                cancelled.set(true);
            }
        };
        // The workers take this thread's priority (low in the mod).
        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(work, "nether-bedrock-search-" + i);
            workers[i].setDaemon(true);
            workers[i].start();
        }
        boolean interrupted = false;
        for (Thread worker : workers) {
            while (worker.isAlive()) {
                try {
                    worker.join();
                } catch (InterruptedException e) {
                    // Stop the workers (they notice within a piece), wait for them, then pass the interrupt on.
                    interrupted = true;
                    cancelled.set(true);
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (failure.get() != null) {
            throw new IllegalStateException("The nether bedrock search failed", failure.get());
        }
        synchronized (lock) {
            return collector.outcome(plan, searchRoof, piecesDone[0]);
        }
    }

    /** Follows up the fitting numbers of the searched surface, from the search threads. */
    private final class Collector {

        private final BedrockSurface searched;
        private final BedrockSurface other;
        private final List<BedrockObservation> blocks;
        private final AtomicLong fits = new AtomicLong();
        private final AtomicBoolean full = new AtomicBoolean();
        /** Structure seeds by key (guarded by this). */
        private final TreeMap<Long, long[]> seedsByKey = new TreeMap<>();
        private int seedCount;

        Collector(BedrockSurface searched, BedrockSurface other, List<BedrockObservation> blocks) {
            this.searched = searched;
            this.other = other;
            this.blocks = blocks;
        }

        boolean full() {
            return full.get();
        }

        /** One hidden number of the searched surface that fits it: its keys, checked by the other surface. */
        void accept(long x) {
            if (full.get()) {
                return;
            }
            if (fits.incrementAndGet() > MAX_SURFACE_FITS) {
                full.set(true);
                return;
            }
            for (long state : statesBeforeNextLong((x ^ A) & MASK)) {
                // The rule's generator was set to hash ^ P: its first state is that xor a.
                long key = (state ^ A ^ (long) searched.hash()) & MASK;
                if (other.size() > 0 && !other.accepts(hiddenNumber(key, other.hash()))) {
                    continue;
                }
                long[] seeds = Arrays.stream(structureSeedsOfKey(key)).filter(seed -> fitsAll(seed, blocks)).toArray();
                if (seeds.length == 0) {
                    continue;
                }
                synchronized (this) {
                    if (seedsByKey.put(key, seeds) == null) {
                        seedCount += seeds.length;
                    }
                    if (seedCount > MAX_STRUCTURE_SEEDS) {
                        full.set(true);
                    }
                }
            }
        }

        synchronized Outcome outcome(Plan plan, boolean searchedRoof, int piecesDone) {
            List<Long> seeds = seedsByKey.values().stream()
                .flatMap(list -> Arrays.stream(list).boxed())
                .sorted()
                .limit(MAX_STRUCTURE_SEEDS)
                .toList();
            Set<Long> kept = new HashSet<>(seeds);
            List<Long> keys = seedsByKey.entrySet().stream()
                .filter(entry -> Arrays.stream(entry.getValue()).anyMatch(kept::contains))
                .map(Map.Entry::getKey)
                .toList();
            return new Outcome(plan, seeds, keys, searchedRoof, fits.get(), piecesDone, full.get());
        }
    }

    // ------------------------------------------------------------------------------------------------ chunk by chunk

    /**
     * Finds the structure seed from nether notes that may include chunks not made by 1.18 or later (or misread ones),
     * one chunk at a time:
     * <ol>
     *   <li>Chunks with {@link Plan#enough() enough} on their own (both surfaces, {@link #PROOF_BITS}) are searched one
     *       by one, the most informative first. A chunk no seed fits is reported ({@link ChunkStatus#FITS_NO_SEED}) and
     *       the next one is tried; the first {@link Outcome#proven() proven} result ends this step.</li>
     *   <li>Without one, the chunks not found to fit no seed are searched together, when that adds something (this is
     *       how scattered notes, chunks with only one surface known, or a chunk whose result was only a list of
     *       candidates get combined).</li>
     *   <li>Without a result from that either, the first chunk that gave candidates (not proven) is reported.</li>
     * </ol>
     * A result of one chunk is then checked against every other chunk's notes with the engine's bedrock model
     * ({@link ChunkStatus#FITS} or {@link ChunkStatus#CONTRADICTS}); when it has several keys, those that some fitting
     * chunk rules out are dropped (unless that would drop them all). Notes from other dimensions are ignored.
     *
     * @param progress hears which search runs and how many of its pieces are done
     */
    public Finding find(List<BedrockObservation> observations, int threads, BooleanSupplier stop, Progress progress) {
        Prepared prepared = prepare(observations);
        Plan all = planOf(prepared.blocks(), prepared.problems());
        Map<ChunkPos, List<BedrockObservation>> byChunk = new LinkedHashMap<>();
        for (BedrockObservation block : prepared.blocks()) {
            byChunk.computeIfAbsent(ChunkPos.ofBlock(block.x(), block.z()), chunk -> new ArrayList<>()).add(block);
        }
        Map<ChunkPos, Plan> plans = new HashMap<>();
        byChunk.forEach((chunk, blocks) -> plans.put(chunk, planOf(blocks, List.of())));
        List<ChunkPos> order = new ArrayList<>(byChunk.keySet());
        order.sort(Comparator.comparingDouble((ChunkPos chunk) -> -plans.get(chunk).bits())
            .thenComparingInt(ChunkPos::x)
            .thenComparingInt(ChunkPos::z));
        Map<ChunkPos, ChunkStatus> status = new HashMap<>();
        order.forEach(chunk -> status.put(chunk, ChunkStatus.NOT_CHECKED));

        int searches = 0;
        Outcome candidates = null;
        ChunkPos candidatesChunk = null;
        for (ChunkPos chunk : order) {
            Plan plan = plans.get(chunk);
            if (!plan.enough() || !plan.canSearch()) {
                continue;
            }
            int search = searches++;
            Outcome outcome = run(byChunk.get(chunk), plan, threads, stop, pieces -> progress.update(search, pieces));
            if (!outcome.complete() && !outcome.capped()) {
                return finding(Optional.of(outcome), List.of(), order, plans, status, false, false, all); // stopped
            }
            if (outcome.structureSeeds().isEmpty()) {
                if (outcome.complete()) {
                    status.put(chunk, ChunkStatus.FITS_NO_SEED);
                }
                continue; // otherwise too many numbers fit (rare with enough information): no answer from this chunk
            }
            if (outcome.proven()) {
                status.put(chunk, ChunkStatus.USED);
                return checkOthers(outcome, chunk, byChunk, order, plans, status, all);
            }
            if (candidates == null) {
                candidates = outcome;
                candidatesChunk = chunk;
            }
        }

        List<ChunkPos> rest = order.stream().filter(chunk -> status.get(chunk) == ChunkStatus.NOT_CHECKED).toList();
        List<BedrockObservation> blocks = rest.stream().flatMap(chunk -> byChunk.get(chunk).stream()).toList();
        Plan plan = planOf(blocks, List.of());
        boolean addsSomething = rest.size() > 1 || candidatesChunk == null;
        Outcome together = null;
        if (!rest.isEmpty() && addsSomething && plan.canSearch()) {
            int search = searches;
            together = run(blocks, plan, threads, stop, pieces -> progress.update(search, pieces));
            if (!together.complete() && !together.capped()) {
                return finding(Optional.of(together), List.of(), order, plans, status, true, false, all); // stopped
            }
            if (!together.structureSeeds().isEmpty()) {
                rest.forEach(chunk -> status.put(chunk, ChunkStatus.USED));
                return finding(Optional.of(together), together.structureSeeds(), order, plans, status, true, true, all);
            }
        }
        if (candidates != null) {
            status.put(candidatesChunk, ChunkStatus.USED);
            return checkOthers(candidates, candidatesChunk, byChunk, order, plans, status, all);
        }
        return finding(Optional.ofNullable(together), List.of(), order, plans, status, together != null, true, all);
    }

    /** Checks the result of one chunk against every other chunk, and drops keys that a fitting chunk rules out. */
    private Finding checkOthers(Outcome outcome, ChunkPos searched, Map<ChunkPos, List<BedrockObservation>> byChunk, List<ChunkPos> order,
                                Map<ChunkPos, Plan> plans, Map<ChunkPos, ChunkStatus> status, Plan all) {
        // Seeds with one key have the same nether bedrock, so one seed per key is enough.
        Map<Long, Long> seedOfKey = new LinkedHashMap<>();
        for (long seed : outcome.structureSeeds()) {
            seedOfKey.putIfAbsent(bedrockKey(seed), seed);
        }
        Set<Long> keys = new LinkedHashSet<>(seedOfKey.keySet());
        for (ChunkPos chunk : order) {
            if (chunk.equals(searched) || status.get(chunk) != ChunkStatus.NOT_CHECKED) {
                continue; // searched on its own already: nothing fits it
            }
            List<BedrockObservation> blocks = byChunk.get(chunk);
            Set<Long> fitting = new HashSet<>();
            seedOfKey.forEach((key, seed) -> {
                if (fitsAll(seed, blocks)) {
                    fitting.add(key);
                }
            });
            status.put(chunk, fitting.isEmpty() ? ChunkStatus.CONTRADICTS : ChunkStatus.FITS);
            if (!fitting.isEmpty()) {
                Set<Long> narrowed = new LinkedHashSet<>(keys);
                narrowed.retainAll(fitting);
                if (!narrowed.isEmpty()) {
                    keys = narrowed;
                }
            }
        }
        Set<Long> kept = keys;
        List<Long> seeds = outcome.structureSeeds().stream().filter(seed -> kept.contains(bedrockKey(seed))).toList();
        return finding(Optional.of(outcome), seeds, order, plans, status, false, true, all);
    }

    private static Finding finding(Optional<Outcome> outcome, List<Long> seeds, List<ChunkPos> order, Map<ChunkPos, Plan> plans,
                                   Map<ChunkPos, ChunkStatus> status, boolean together, boolean complete, Plan all) {
        List<Long> keys = seeds.stream().map(NetherBedrockSearch::bedrockKey).distinct().sorted().toList();
        List<ChunkReport> chunks = order.stream().map(chunk -> new ChunkReport(chunk, plans.get(chunk), status.get(chunk))).toList();
        return new Finding(outcome, seeds, keys, chunks, together, complete, all);
    }
}
