package io.github.jriiik.seedfinder.finder;

import static io.github.jriiik.seedfinder.finder.BedrockSurface.A;
import static io.github.jriiik.seedfinder.finder.BedrockSurface.C;
import static io.github.jriiik.seedfinder.finder.BedrockSurface.FULL;
import static io.github.jriiik.seedfinder.finder.BedrockSurface.MASK;

import java.util.Arrays;
import java.util.function.LongConsumer;

/**
 * The exact search over the hidden number X of one {@link BedrockSurface}: finds every 48-bit X that fits all of its
 * blocks, never leaving one out.
 *
 * <p>It goes depth first over the bits of X from bit 47 down. A node at level k has the bits from k up fixed and the k
 * bits below unknown. Every test at a node is a condition that each X below it must meet if it fits the blocks, so a
 * failed test never drops a fitting X; only the leaves (all 48 bits known) are checked against every block exactly.
 * At a node, {@code S ^ X = B + t} for a known B (the bits from k up of {@code S ^ X}) and an unknown t in [0, 2^k).
 * The tests:
 * <ul>
 *   <li><b>One block</b> (levels 0 to 13): {@code u = a * B + c + a * t} lies in {@code a * B + c + [0, a * (2^k - 1)]},
 *       which must meet the block's stretch. From level 14 up that range covers the whole circle.</li>
 *   <li><b>Two blocks</b> i and j: {@code u_i - u_j = a * ((S_i ^ X) - (S_j ^ X))}, and the difference in brackets is
 *       {@code B_i - B_j} up to E either way, E = (S_i ^ S_j) & (2^k - 1). It must meet the difference of the two
 *       stretches. A pair whose S agree on the bits from 12 up to m can prune from level m on, far above level 13. Its
 *       result only changes at levels where S_i ^ S_j has a 1 bit, so it is only tested there.</li>
 *   <li><b>Table step</b>: at a level-18 node, its 64 descendants at level 12 are tested against each of up to 64 strong
 *       blocks with one multiplication and one table lookup (per block, 1024 buckets of the product's top bits, each
 *       holding the descendants that may pass: a superset); the survivors get the exact level-12 tests. Pair tests
 *       that would act at levels 13 to 17 act at level 12 instead.</li>
 * </ul>
 * Each test asks whether an interval of width W (all values the node allows) meets an allowed stretch of length L on
 * the circle mod 2^48: {@code ((start + W) mod 2^48) < L + W}. That is exact for the interval as long as
 * {@code L + W <= 2^48}; tests that would not be are left out.
 *
 * <p>The search is split into {@link #PIECES} independent pieces by the top {@link #SPLIT_BITS} bits of X, so it can run
 * on several threads, each with its own {@link Worker}. The settings below are the best of those measured in the
 * research (pairs among the strongest 1200 blocks whose stretches cover at most 0.8 of the circle together, at most
 * 2000 pair tests and 48 single-block tests per level, the table step on).
 */
final class BedrockSurfaceSearch {

    /** How many top bits of X tell the pieces apart. */
    static final int SPLIT_BITS = 12;
    /** How many pieces the search is split into. */
    static final int PIECES = 1 << SPLIT_BITS;
    /** The level a piece starts at: its bits from here up are fixed. */
    static final int SPLIT_LEVEL = 48 - SPLIT_BITS;
    static final int LEVELS = 48;

    /** Single-block tests only help up to here: a * 2^14 is more than 2^48. */
    private static final int HIGHEST_SINGLE_LEVEL = 13;
    private static final int SINGLES_PER_LEVEL = 48;
    /** Pairs are made among this many of the strongest blocks. */
    private static final int PAIR_BLOCKS = 1200;
    /** Pairs only when their two stretches together cover at most this share of the circle. */
    private static final double MAX_PAIR_SHARE = 0.8;
    private static final int PAIRS_PER_LEVEL = 2000;
    /** Below this level single blocks do the work. */
    private static final int LOWEST_PAIR_LEVEL = 13;
    /** The table step starts at nodes of this level ... */
    static final int TABLE_LEVEL = 18;
    /** ... and tests their descendants at this level. */
    static final int TABLE_TARGET = 12;
    private static final int TABLE_BLOCKS = 64;
    private static final int BUCKET_BITS = 10;
    private static final int BUCKET_SHIFT = 48 - BUCKET_BITS;
    /** Table blocks: those whose stretch at level 12 covers less than this share of the circle. */
    private static final double TABLE_MAX_SHARE = 0.8;

    private final BedrockSurface surface;

    // Tests per level: single blocks (S with the low bits cleared, offset, limit) and pairs.
    private final long[][] singleS = new long[LEVELS][];
    private final long[][] singleOffset = new long[LEVELS][];
    private final long[][] singleLimit = new long[LEVELS][];
    private final long[][] pairI = new long[LEVELS][];
    private final long[][] pairJ = new long[LEVELS][];
    private final long[][] pairOffset = new long[LEVELS][];
    private final long[][] pairLimit = new long[LEVELS][];
    private final boolean[] hasTests = new boolean[LEVELS];
    /** For k >= {@link #TABLE_LEVEL}: no level from {@link #TABLE_LEVEL} up to k - 1 has a test. */
    private final boolean[] noTestsDown = new boolean[LEVELS + 1];

    // The table step: per table block, its S above level 18, its offset, and its 1024 masks (flattened).
    private final long[] tableS;
    private final long[] tableOffset;
    private final long[] table;
    /** The surface's index of each table block. */
    private final int[] tableIndex;
    private final boolean useTable;
    private final int pairsUsed;

    BedrockSurfaceSearch(BedrockSurface surface) {
        this.surface = surface;
        int n = surface.size();

        // The table step first: whether it is used decides where the low pair tests go.
        long w12 = A * ((1L << TABLE_TARGET) - 1);
        long tableLimit = (long) (TABLE_MAX_SHARE * FULL);
        int[] use = new int[Math.min(TABLE_BLOCKS, n)];
        int used = 0;
        for (int i = 0; i < n && used < use.length; i++) {
            if (surface.length(i) + w12 < tableLimit) {
                use[used++] = i;
            }
        }
        tableIndex = Arrays.copyOf(use, used);
        tableS = new long[used];
        tableOffset = new long[used];
        table = new long[used << BUCKET_BITS];
        long bucketWidth = 1L << BUCKET_SHIFT;
        int descendants = 1 << (TABLE_LEVEL - TABLE_TARGET);
        long[] offsets = new long[descendants];
        for (int u = 0; u < used; u++) {
            int i = use[u];
            tableS[u] = surface.position(i) & ~((1L << TABLE_LEVEL) - 1) & MASK;
            tableOffset[u] = (C - surface.low(i) + w12) & MASK;
            long limit = surface.length(i) + w12;
            long middle = (surface.position(i) >>> TABLE_TARGET) & (descendants - 1);
            for (int t = 0; t < descendants; t++) {
                offsets[t] = (A * ((middle ^ t) << TABLE_TARGET)) & MASK;
            }
            for (int bucket = 0; bucket < 1 << BUCKET_BITS; bucket++) {
                long mask = 0;
                for (int t = 0; t < descendants; t++) {
                    // The values of this descendant in this bucket: [start, start + bucketWidth) on the circle.
                    long start = (((long) bucket << BUCKET_SHIFT) + offsets[t]) & MASK;
                    if (start < limit || start + bucketWidth > FULL) {
                        mask |= 1L << t;
                    }
                }
                table[(u << BUCKET_BITS) + bucket] = mask;
            }
        }
        useTable = used > 0;

        // Single blocks: the strongest first, and only those whose range still leaves part of the circle out (as
        // the blocks are sorted by length, these are the first ones).
        for (int k = 0; k < LEVELS; k++) {
            long w = A * ((1L << k) - 1);
            int count = 0;
            while (k <= HIGHEST_SINGLE_LEVEL && count < Math.min(n, SINGLES_PER_LEVEL) && surface.length(count) + w < FULL) {
                count++;
            }
            long cleared = ~((1L << k) - 1) & MASK;
            singleS[k] = new long[count];
            singleOffset[k] = new long[count];
            singleLimit[k] = new long[count];
            for (int i = 0; i < count; i++) {
                singleS[k][i] = surface.position(i) & cleared;
                singleOffset[k][i] = (C - surface.low(i) + w) & MASK;
                singleLimit[k][i] = surface.length(i) + w;
            }
        }

        // Pairs.
        PairTests[] pairs = new PairTests[LEVELS];
        for (int k = 0; k < LEVELS; k++) {
            pairs[k] = new PairTests();
        }
        int candidates = Math.min(n, PAIR_BLOCKS);
        long maxWidth = (long) (MAX_PAIR_SHARE * FULL);
        for (int i = 0; i < candidates; i++) {
            for (int j = i + 1; j < candidates; j++) {
                long width = surface.length(i) + surface.length(j) - 1;
                if (width > maxWidth) {
                    continue;
                }
                long d = surface.position(i) ^ surface.position(j);
                long errorLimit = (FULL - 1 - width) / (2 * A); // usable while E <= this
                // The highest usable level: E only grows with the level, and any bit of d at or above the bit length of
                // the limit makes E too large from the level above that bit.
                int limitBits = 64 - Long.numberOfLeadingZeros(errorLimit);
                long high = d & -(1L << limitBits);
                int top = high == 0 ? LEVELS - 1 : Math.min(LEVELS - 1, Long.numberOfTrailingZeros(high));
                while (top >= LOWEST_PAIR_LEVEL && (d & ((1L << top) - 1)) > errorLimit) {
                    top--;
                }
                boolean low = false;
                for (int k = top; k >= LOWEST_PAIR_LEVEL; k--) {
                    if (k != top && ((d >>> k) & 1) == 0) {
                        continue; // same result as the level above
                    }
                    if (useTable && k < TABLE_LEVEL) {
                        low = true; // the table step skips levels 13 to 17: test at level 12 instead
                        continue;
                    }
                    addPair(pairs[k], i, j, k, width, d);
                }
                if (low) {
                    addPair(pairs[TABLE_TARGET], i, j, TABLE_TARGET, width, d);
                }
            }
        }
        int pairCount = 0;
        for (int k = 0; k < LEVELS; k++) {
            pairs[k].keepStrongest();
            pairI[k] = Arrays.copyOf(pairs[k].i, pairs[k].size);
            pairJ[k] = Arrays.copyOf(pairs[k].j, pairs[k].size);
            pairOffset[k] = Arrays.copyOf(pairs[k].offset, pairs[k].size);
            pairLimit[k] = Arrays.copyOf(pairs[k].limit, pairs[k].size);
            pairCount += pairs[k].size;
            hasTests[k] = singleS[k].length + pairI[k].length > 0;
        }
        pairsUsed = pairCount;
        for (int k = TABLE_LEVEL; k <= LEVELS; k++) {
            boolean none = true;
            for (int level = TABLE_LEVEL; level < k; level++) {
                none &= !hasTests[level];
            }
            noTestsDown[k] = none;
        }
    }

    private void addPair(PairTests tests, int i, int j, int k, long width, long d) {
        long e = d & ((1L << k) - 1);
        long cleared = ~((1L << k) - 1) & MASK;
        long offset = (surface.low(j) - surface.low(i) + surface.length(j) - 1 + A * e) & MASK;
        tests.add(surface.position(i) & cleared, surface.position(j) & cleared, offset, width + 2 * A * e);
    }

    /** The pair tests of one level while they are collected: only the {@link #PAIRS_PER_LEVEL} strongest are kept. */
    private static final class PairTests {

        /** At most this many are held before the weakest are dropped (the index must fit in 13 bits). */
        private static final int CAPACITY = 1 << 13;

        long[] i = new long[16];
        long[] j = new long[16];
        long[] offset = new long[16];
        long[] limit = new long[16];
        int size;

        void add(long si, long sj, long off, long lim) {
            if (size == CAPACITY) {
                keepStrongest();
            }
            if (size == i.length) {
                int grown = Math.min(CAPACITY, size * 2);
                i = Arrays.copyOf(i, grown);
                j = Arrays.copyOf(j, grown);
                offset = Arrays.copyOf(offset, grown);
                limit = Arrays.copyOf(limit, grown);
            }
            i[size] = si;
            j[size] = sj;
            offset[size] = off;
            limit[size] = lim;
            size++;
        }

        /** Keeps the strongest tests (smallest limit; equal ones in the order added), strongest first. */
        void keepStrongest() {
            long[] order = new long[size];
            for (int k = 0; k < size; k++) {
                order[k] = limit[k] << 13 | k; // limit < 2^48, so this stays positive
            }
            Arrays.sort(order);
            int kept = Math.min(size, PAIRS_PER_LEVEL);
            long[] ni = new long[Math.max(16, kept)];
            long[] nj = new long[ni.length];
            long[] no = new long[ni.length];
            long[] nl = new long[ni.length];
            for (int k = 0; k < kept; k++) {
                int from = (int) (order[k] & (CAPACITY - 1));
                ni[k] = i[from];
                nj[k] = j[from];
                no[k] = offset[from];
                nl[k] = limit[from];
            }
            i = ni;
            j = nj;
            offset = no;
            limit = nl;
            size = kept;
        }
    }

    BedrockSurface surface() {
        return surface;
    }

    /** How many pair tests are used, over all levels. */
    int pairsUsed() {
        return pairsUsed;
    }

    /** How many blocks the table step uses (0: no table step). */
    int tableBlocks() {
        return tableS.length;
    }

    boolean hasTests(int level) {
        return hasTests[level];
    }

    /**
     * Whether a node at this level passes the level's tests: {@code v} holds the fixed bits (from {@code level} up), its
     * lower bits are 0.
     */
    boolean passes(long v, int level) {
        long[] s = singleS[level];
        long[] offsets = singleOffset[level];
        long[] limits = singleLimit[level];
        for (int k = 0; k < s.length; k++) {
            if ((((s[k] ^ v) * A + offsets[k]) & MASK) >= limits[k]) {
                return false;
            }
        }
        long[] pi = pairI[level];
        long[] pj = pairJ[level];
        long[] po = pairOffset[level];
        long[] pl = pairLimit[level];
        for (int k = 0; k < pi.length; k++) {
            if (((((pi[k] ^ v) - (pj[k] ^ v)) * A + po[k]) & MASK) >= pl[k]) {
                return false;
            }
        }
        return true;
    }

    /** The surface's index of table block {@code u} (0 to {@link #tableBlocks()} - 1). */
    int tableBlock(int u) {
        return tableIndex[u];
    }

    /** {@link #tableMask(long)} for table block {@code u} alone. */
    long tableMask(int u, long v) {
        return table[(u << BUCKET_BITS) + (int) ((((tableS[u] ^ v) * A + tableOffset[u]) & MASK) >>> BUCKET_SHIFT)];
    }

    /**
     * The table step's answer for a node at level 18 ({@code v}'s bits below 18 are 0): bit t is set when the
     * descendant {@code v | t << 12} may pass the table blocks. All bits when there is no table step.
     */
    long tableMask(long v) {
        long mask = -1L;
        for (int u = 0; u < tableS.length; u++) {
            mask &= table[(u << BUCKET_BITS) + (int) ((((tableS[u] ^ v) * A + tableOffset[u]) & MASK) >>> BUCKET_SHIFT)];
        }
        return mask;
    }

    /**
     * A rough guess of how much searching this surface costs: log2 of the most nodes on one level, from the share of
     * nodes each level's tests let through. Used to choose which surface to search.
     */
    double costEstimate() {
        if (surface.size() == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double log2Nodes = 0;
        double peak = 0;
        for (int k = LEVELS - 1; k >= 0; k--) {
            log2Nodes += 1;
            double prune = 0;
            for (long limit : pairLimit[k]) {
                prune += Math.log((double) limit / FULL) / Math.log(2);
            }
            for (long limit : singleLimit[k]) {
                prune += Math.log((double) limit / FULL) / Math.log(2);
            }
            log2Nodes = Math.max(0, log2Nodes + prune);
            peak = Math.max(peak, log2Nodes);
        }
        return peak;
    }

    /** A searcher for one thread, handing every fitting X to {@code fits} (from that thread). */
    Worker worker(LongConsumer fits) {
        return new Worker(fits);
    }

    /** Searches pieces or parts of the tree on one thread. */
    final class Worker {

        private final LongConsumer fits;

        private Worker(LongConsumer fits) {
            this.fits = fits;
        }

        /** Searches one piece: the X whose top {@link #SPLIT_BITS} bits are the bits of {@code piece}. */
        void searchPiece(int piece) {
            if (piece < 0 || piece >= PIECES) {
                throw new IllegalArgumentException("piece " + piece);
            }
            if (surface.impossible()) {
                return;
            }
            long v = 0;
            for (int k = LEVELS - 1; k >= SPLIT_LEVEL; k--) {
                if (((piece >>> (k - SPLIT_LEVEL)) & 1) != 0) {
                    v |= 1L << k;
                }
                if (hasTests[k] && !passes(v, k)) {
                    return;
                }
            }
            searchBelow(v, SPLIT_LEVEL);
        }

        /**
         * Searches every X below a node: {@code v} holds the bits from {@code level} up (its lower bits are 0), and the
         * node itself has already passed its tests.
         */
        void searchBelow(long v, int level) {
            if (level == 0) {
                if (surface.accepts(v)) {
                    fits.accept(v);
                }
                return;
            }
            if (useTable && level >= TABLE_LEVEL && noTestsDown[level]) {
                // Nothing to test between here and the table level: run through its nodes in a flat loop.
                long count = 1L << (level - TABLE_LEVEL);
                for (long below = 0; below < count; below++) {
                    tableStep(v | (below << TABLE_LEVEL));
                }
                return;
            }
            int child = level - 1;
            long bit = 1L << child;
            for (int b = 0; b < 2; b++) {
                long node = b == 0 ? v : v | bit;
                if (hasTests[child] && !passes(node, child)) {
                    continue;
                }
                if (child == 0) {
                    if (surface.accepts(node)) {
                        fits.accept(node);
                    }
                } else {
                    searchBelow(node, child);
                }
            }
        }

        /** From a level-18 node to its level-12 descendants that pass every table block, then on down. */
        private void tableStep(long v) {
            long[] s = tableS;
            long[] offsets = tableOffset;
            long[] masks = table;
            int n = s.length;
            long mask = -1L;
            int u = 0;
            // Four lookups per test of the mask: fewer unpredictable branches.
            for (; u + 4 <= n; u += 4) {
                mask &= masks[(u << BUCKET_BITS) + (int) ((((s[u] ^ v) * A + offsets[u]) & MASK) >>> BUCKET_SHIFT)]
                    & masks[((u + 1) << BUCKET_BITS) + (int) ((((s[u + 1] ^ v) * A + offsets[u + 1]) & MASK) >>> BUCKET_SHIFT)]
                    & masks[((u + 2) << BUCKET_BITS) + (int) ((((s[u + 2] ^ v) * A + offsets[u + 2]) & MASK) >>> BUCKET_SHIFT)]
                    & masks[((u + 3) << BUCKET_BITS) + (int) ((((s[u + 3] ^ v) * A + offsets[u + 3]) & MASK) >>> BUCKET_SHIFT)];
                if (mask == 0) {
                    return;
                }
            }
            for (; u < n; u++) {
                mask &= masks[(u << BUCKET_BITS) + (int) ((((s[u] ^ v) * A + offsets[u]) & MASK) >>> BUCKET_SHIFT)];
                if (mask == 0) {
                    return;
                }
            }
            while (mask != 0) {
                long t = Long.numberOfTrailingZeros(mask);
                mask &= mask - 1;
                long node = v | (t << TABLE_TARGET);
                if (passes(node, TABLE_TARGET)) {
                    searchBelow(node, TABLE_TARGET);
                }
            }
        }
    }
}
