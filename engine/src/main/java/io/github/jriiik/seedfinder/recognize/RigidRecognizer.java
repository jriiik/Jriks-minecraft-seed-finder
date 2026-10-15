package io.github.jriiik.seedfinder.recognize;

import io.github.jriiik.seedfinder.structure.RandomSpreadPlacement;
import io.github.jriiik.seedfinder.structure.StructureSet;
import io.github.jriiik.seedfinder.version.VersionData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Finds the start chunk of rigid structures from their blocks: desert pyramids, jungle temples, swamp huts, igloos,
 * shipwrecks, ocean ruins and ruined portals. Each is one piece that the game builds from a fixed point: the corner of
 * a code-built piece, or the origin of a template, always lands exactly on the start chunk's north-west corner, and
 * templates are then turned (portals also mirrored) about a fixed pivot. So every variant in every pose is one
 * {@link RigidLayout} at known offsets from that corner, and the start chunk is the chunk of the layout's origin.
 *
 * <p>The steps, as measured with the game's own generation code (see the roadmap):
 * <ol>
 * <li>Vote: each block of a trigger class votes for every placement of a layout that has that class there, but only
 * for placements whose origin lands on a chunk corner (x and z multiples of 16).</li>
 * <li>Verify: placements with at least {@link #MIN_VOTES} votes are compared block by block
 * ({@link #verify}).</li>
 * <li>Accept ({@link #acceptable}): enough blocks match, overall and with every kind of block counting equally; the
 * spots the structure leaves empty are not filled with its own material; ocean ruins touch open water; the biome is
 * one the structure starts in; and a small ocean ruin does not fit better off the chunk grid (the cluster ruins
 * around a big ruin, {@link #isClusterPiece}).</li>
 * <li>Resolve, strongest first ({@link #resolve}): two starts of one structure set closer than the set's separation
 * cannot both exist; a fit sharing many matched blocks with a stronger one is dropped; and when a fit with another
 * start chunk explains mostly the same blocks almost as well, nothing is reported there.</li>
 * </ol>
 * Wrong answers matter far more than misses: keep the thresholds unless a new measurement shows a change is safe.
 */
public final class RigidRecognizer {

    /** Placements need this many votes before they are compared. */
    public static final int MIN_VOTES = 3;
    /** At least this many matched blocks (fewer for layouts with few telling blocks, see {@link #minMatched}). */
    public static final int MIN_MATCHED = 12;
    /** At least this share of the telling blocks must be judged (seen, or covered by a portal's netherrack). */
    public static final double MIN_COVERAGE = 0.6;
    /** The empty-spot test needs this many empty spots seen. */
    public static final int MIN_EMPTY_CHECKED = 10;
    /** Ocean ruins: at least this share of their columns end in open water (and at least one). */
    public static final double MIN_OPEN_TOPS = 0.05;
    /** Rival fits whose scores are within this of the best one make the start chunk uncertain. */
    public static final double AMBIGUITY_MARGIN = 0.15;
    static final double DROP_OVERLAP = 0.3;
    static final double AMBIGUOUS_OVERLAP = 0.5;
    static final int AMBIGUITY_DISTANCE = 64;
    static final int OVERLAP_DISTANCE = 80;
    /** Small ocean ruins: an off-grid fit this much better means the blocks are a cluster ruin. */
    static final double CLUSTER_MARGIN = 0.05;
    /** Ocean-ruin layouts with fewer telling blocks than this are small ruins (the size of cluster ruins). */
    static final int SMALL_RUIN = 150;
    static final int CLUSTER_REACH = 10;
    static final int CLUSTER_HEIGHT = 4;
    /** The biome test looks this far above and below the structure's base. */
    static final int BIOME_REACH = 16;
    static final int SEA_LEVEL = 63;

    /** Where starts of a kind can be: its structure set's grid, and the biomes its structures start in. */
    public record Rules(int spacing, int separation, Set<String> biomes) {

        public Rules {
            if (spacing <= separation || separation < 0) {
                throw new IllegalArgumentException("spacing (" + spacing + ") must be larger than separation (" + separation + ")");
            }
            biomes = Set.copyOf(biomes);
        }

        /**
         * Whether the set can start in this chunk at all: in each region of {@code spacing} x {@code spacing}
         * chunks, starts are never in the last {@code separation} rows and columns.
         */
        public boolean couldStartIn(int chunkX, int chunkZ) {
            int limit = spacing - separation;
            return Math.floorMod(chunkX, spacing) < limit && Math.floorMod(chunkZ, spacing) < limit;
        }
    }

    /** A placement of a layout: its origin (x and z on a chunk corner) and how many blocks voted for it. */
    public record Candidate(RigidLayout layout, int x, int y, int z, int votes) {

        public int chunkX() {
            return x >> 4;
        }

        public int chunkZ() {
            return z >> 4;
        }
    }

    /** The trigger blocks found in loaded chunks, packed with {@link #pack}. */
    public interface Triggers {
        /** The trigger blocks of a chunk; null when the chunk has not been looked at. */
        int[] at(int chunkX, int chunkZ);
    }

    /** A verified placement: how well the blocks there fit the layout. */
    public static final class Detection {
        private final Candidate candidate;
        private final int matched;
        private final int checked;
        private final int skipped;
        private final double balanced;
        private final boolean classesCovered;
        private final boolean spotsLookedAt;
        private final int emptyChecked;
        private final int emptyFilled;
        private final int emptyWater;
        private final int topsChecked;
        private final int topsOpen;
        /** The matched blocks' positions ({@link #position}), sorted. */
        final long[] matchedPositions;

        Detection(Candidate candidate, int matched, int checked, int skipped, double balanced, boolean classesCovered, boolean spotsLookedAt,
                  int emptyChecked, int emptyFilled, int emptyWater, int topsChecked, int topsOpen, long[] matchedPositions) {
            this.candidate = candidate;
            this.matched = matched;
            this.checked = checked;
            this.skipped = skipped;
            this.balanced = balanced;
            this.classesCovered = classesCovered;
            this.spotsLookedAt = spotsLookedAt;
            this.emptyChecked = emptyChecked;
            this.emptyFilled = emptyFilled;
            this.emptyWater = emptyWater;
            this.topsChecked = topsChecked;
            this.topsOpen = topsOpen;
            this.matchedPositions = matchedPositions;
        }

        public Candidate candidate() {
            return candidate;
        }

        public RigidLayout layout() {
            return candidate.layout();
        }

        public RigidKind kind() {
            return candidate.layout().kind();
        }

        public int x() {
            return candidate.x();
        }

        public int y() {
            return candidate.y();
        }

        public int z() {
            return candidate.z();
        }

        public int chunkX() {
            return candidate.chunkX();
        }

        public int chunkZ() {
            return candidate.chunkZ();
        }

        /** Telling blocks that match. */
        public int matched() {
            return matched;
        }

        /** Telling blocks compared (seen, and not covered by a portal's netherrack). */
        public int checked() {
            return checked;
        }

        /** Telling blocks not judged: netherrack under a ruined portal, or lava around one in the nether. */
        public int skipped() {
            return skipped;
        }

        /** The share of matched blocks when every kind of block counts equally. */
        public double balanced() {
            return balanced;
        }

        /** With blocks not judged: whether enough kinds of block are still judged on enough of their blocks. */
        public boolean classesCovered() {
            return classesCovered;
        }

        /**
         * Whether the empty spots and (ocean ruins) column tops were looked at: only for fits that pass the thresholds
         * on their blocks, since for others they can't change anything.
         */
        public boolean spotsLookedAt() {
            return spotsLookedAt;
        }

        /** Empty spots of the layout that were seen, those holding the structure's own material, and water. */
        public int emptyChecked() {
            return emptyChecked;
        }

        public int emptyFilled() {
            return emptyFilled;
        }

        public int emptyWater() {
            return emptyWater;
        }

        /** Ocean ruins: column tops seen, and those that end in open water (or air above sea level). */
        public int topsChecked() {
            return topsChecked;
        }

        public int topsOpen() {
            return topsOpen;
        }

        public double score() {
            return checked == 0 ? 0 : (double) matched / checked;
        }

        /** What ranks fits against each other: matched blocks times score. */
        public double strength() {
            return matched * score();
        }

        @Override
        public String toString() {
            String spots = spotsLookedAt ? String.format(java.util.Locale.ROOT, "empty filled %d/%d, water %d, open tops %d/%d", emptyFilled,
                emptyChecked, emptyWater, topsOpen, topsChecked) : "empty spots not looked at";
            return String.format(java.util.Locale.ROOT, "%s start chunk (%d, %d) origin (%d, %d, %d) %s: matched %d/%d (%.2f) balanced %.2f, skipped %d, %s,"
                    + " votes %d",
                kind().structureSet(), chunkX(), chunkZ(), x(), y(), z(), layout().variant(), matched, checked, score(), balanced, skipped, spots,
                candidate.votes());
        }
    }

    private static final Comparator<Detection> STRONGEST_FIRST = Comparator.comparingDouble(Detection::strength).reversed()
        .thenComparing(detection -> detection.layout().variant())
        .thenComparingInt(Detection::x)
        .thenComparingInt(Detection::z)
        .thenComparingInt(Detection::y);

    private final List<RigidLayout> layouts;
    private final Map<RigidKind, Rules> rules;
    private final Map<String, Integer> triggerClasses = new LinkedHashMap<>();
    /** How many chunks from its origin chunk a layout's blocks reach. */
    private final int span;
    private final int side;
    /** How many chunks from its origin chunk judging a fit on its own reads: blocks, empty spots, biome cells. */
    private final int readSpan;
    /** How many chunks from a fit's origin chunk the cluster test reads. */
    private final int clusterSpan;
    /**
     * For each trigger class, position within the chunk ((x & 15) * 16 + (z & 15)) and chunk offset from the origin
     * chunk: the layout entries that put the origin on a chunk corner, as layout index << 12 | (dy & 0xFFF).
     */
    private final int[][] table;
    private final ClusterIndex clusterIndex;

    /** A recognizer for one dimension: the kinds whose structure sets appear there, with their rules from the data. */
    public static RigidRecognizer create(List<RigidLayout> layouts, VersionData data, String dimension) {
        Map<RigidKind, Rules> rules = new EnumMap<>(RigidKind.class);
        for (RigidKind kind : RigidKind.values()) {
            data.structureSet(kind.structureSet()).ifPresent(set -> {
                if (set.placement() instanceof RandomSpreadPlacement placement && data.dimensionsOf(set).contains(dimension)) {
                    Set<String> biomes = new HashSet<>();
                    for (StructureSet.Entry entry : set.structures()) {
                        data.structure(entry.structure()).ifPresent(info -> biomes.addAll(info.biomes()));
                    }
                    rules.put(kind, new Rules(placement.spacing(), placement.separation(), biomes));
                }
            });
        }
        return new RigidRecognizer(layouts, rules);
    }

    /** Layouts of kinds without rules are left out. (Takes some tens of milliseconds: make it off the game's thread.) */
    public RigidRecognizer(List<RigidLayout> layouts, Map<RigidKind, Rules> rules) {
        this.rules = rules.isEmpty() ? Map.of() : Collections.unmodifiableMap(new EnumMap<>(rules));
        this.layouts = layouts.stream().filter(layout -> rules.containsKey(layout.kind())).toList();
        if (this.layouts.size() >= 1 << 15) {
            throw new IllegalArgumentException("too many layouts: " + this.layouts.size());
        }
        int reach = 0;
        int reads = 0;
        for (RigidLayout layout : this.layouts) {
            for (int e = 0; e < layout.size(); e++) {
                if (layout.dy[e] < -2048 || layout.dy[e] > 2047) {
                    throw new IllegalArgumentException("layout too tall: " + layout);
                }
                for (String c : layout.trigger[e]) {
                    triggerClasses.computeIfAbsent(c, key -> triggerClasses.size());
                }
                reach = Math.max(reach, Math.max(Math.abs(layout.dx[e] >> 4), Math.abs(layout.dz[e] >> 4)));
            }
            for (int e = 0; e < layout.ex.length; e++) {
                reads = Math.max(reads, Math.max(Math.abs(layout.ex[e] >> 4), Math.abs(layout.ez[e] >> 4)));
            }
        }
        for (RigidKind kind : this.rules.keySet()) {
            // The biome cells next to the chunk's middle or corner (biomeFits).
            int at = kind.biomeAtCorner() ? 0 : 8;
            reads = Math.max(reads, Math.max(Math.abs(Math.floorDiv(at - 4, 16)), Math.floorDiv(at + 4, 16)));
        }
        span = reach;
        side = 2 * span + 1;
        readSpan = Math.max(span, reads);
        List<List<Integer>> cells = new ArrayList<>();
        int size = triggerClasses.size() * 256 * side * side;
        for (int i = 0; i < size; i++) {
            cells.add(null);
        }
        for (int li = 0; li < this.layouts.size(); li++) {
            RigidLayout layout = this.layouts.get(li);
            for (int e = 0; e < layout.size(); e++) {
                for (String c : layout.trigger[e]) {
                    int cell = cell(triggerClasses.get(c), layout.dx[e] & 15, layout.dz[e] & 15, layout.dx[e] >> 4, layout.dz[e] >> 4);
                    if (cells.get(cell) == null) {
                        cells.set(cell, new ArrayList<>());
                    }
                    cells.get(cell).add((li << 12) | (layout.dy[e] & 0xFFF));
                }
            }
        }
        table = new int[size][];
        for (int i = 0; i < size; i++) {
            if (cells.get(i) != null) {
                table[i] = cells.get(i).stream().mapToInt(Integer::intValue).toArray();
            }
        }
        clusterIndex = clusterIndex(this.layouts);
        if (clusterIndex.layouts.length == 0) {
            clusterSpan = 0;
        } else {
            clusterSpan = Math.max(
                Math.max(Math.abs(Math.floorDiv(clusterIndex.minDx - CLUSTER_REACH, 16)), Math.floorDiv(clusterIndex.maxDx + CLUSTER_REACH, 16)),
                Math.max(Math.abs(Math.floorDiv(clusterIndex.minDz - CLUSTER_REACH, 16)), Math.floorDiv(clusterIndex.maxDz + CLUSTER_REACH, 16)));
        }
    }

    private int cell(int triggerClass, int localX, int localZ, int chunkOffsetX, int chunkOffsetZ) {
        return ((triggerClass * 256 + localX * 16 + localZ) * side + chunkOffsetX + span) * side + chunkOffsetZ + span;
    }

    public List<RigidLayout> layouts() {
        return layouts;
    }

    /** The kinds this recognizer looks for. */
    public Set<RigidKind> kinds() {
        return rules.keySet();
    }

    public Rules rules(RigidKind kind) {
        return rules.get(kind);
    }

    /** How many chunks from its origin chunk a layout's blocks can be (and so the blocks that vote for it). */
    public int span() {
        return span;
    }

    /**
     * How many chunks from its origin chunk {@link #verify} and {@link #acceptable} read: the layout's blocks, its
     * empty spots and the biome cells.
     */
    public int readSpan() {
        return readSpan;
    }

    /** How many chunks from a fit's origin chunk {@link #isClusterPiece} reads (0 when there are no small ocean ruins). */
    public int clusterSpan() {
        return clusterSpan;
    }

    /** The trigger class of a block id ({@code minecraft:cobblestone}), or -1 when the block does not vote. */
    public int triggerClass(String blockId) {
        Integer index = triggerClasses.get(RigidBlocks.canonical(blockId));
        return index == null ? -1 : index;
    }

    /** A trigger block for {@link Triggers}: its position within the chunk, its height and its trigger class. */
    public static int pack(int localX, int y, int localZ, int triggerClass) {
        if (y < -2048 || y > 2047 || triggerClass < 0 || triggerClass >= 4096) {
            throw new IllegalArgumentException("cannot pack y " + y + " class " + triggerClass);
        }
        return triggerClass << 20 | (y & 0xFFF) << 8 | (localX & 15) << 4 | localZ & 15;
    }

    /** The trigger blocks of a loaded chunk, read block by block (slow; for tests and measurements). */
    public int[] triggersOf(BlockView view, int chunkX, int chunkZ, int minY, int maxY) {
        List<Integer> found = new ArrayList<>();
        for (int y = minY; y <= maxY; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    String id = view.blockAt(chunkX * 16 + x, y, chunkZ * 16 + z);
                    int triggerClass = id == null ? -1 : triggerClass(id);
                    if (triggerClass >= 0) {
                        found.add(pack(x, y, z, triggerClass));
                    }
                }
            }
        }
        return found.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * The placements whose origin is on the corner of this chunk and that got at least {@link #MIN_VOTES} votes from
     * the trigger blocks within {@link #span} chunks; null when some of those chunks have not been looked at.
     * Placements in chunks where their set cannot start are included: they still compete in {@link #resolve}.
     */
    public List<Candidate> candidates(int chunkX, int chunkZ, Triggers triggers) {
        int[][] found = new int[side * side][];
        for (int i = 0; i < found.length; i++) {
            found[i] = triggers.at(chunkX + i / side - span, chunkZ + i % side - span);
            if (found[i] == null) {
                return null;
            }
        }
        Votes votes = votes();
        votes.start(chunkX, chunkZ);
        for (int i = 0; i < found.length; i++) {
            votes.add(chunkX + i / side - span, chunkZ + i % side - span, found[i], 0, found[i].length);
        }
        return votes.candidates();
    }

    /** A vote counter, to be used again for one chunk after another ({@link Votes#start}). */
    public Votes votes() {
        return new Votes();
    }

    /**
     * Counts the votes for the placements whose origin is on one chunk's corner, a slice of trigger blocks at a time:
     * {@link #candidates} in small steps.
     */
    public final class Votes {
        private int chunkX;
        private int chunkZ;
        /** Open addressing: keys (layout index << 12 | origin height & 0xFFF, -1 when free) and their votes. */
        private int[] keys = new int[1 << 12];
        private int[] counts = new int[1 << 12];
        /** The slots in use, in the order they were taken. */
        private int[] used = new int[1 << 11];
        private int size;

        private Votes() {
            Arrays.fill(keys, -1);
        }

        /** Starts counting for the placements whose origin is on this chunk's corner. */
        public void start(int chunkX, int chunkZ) {
            for (int i = 0; i < size; i++) {
                keys[used[i]] = -1;
                counts[used[i]] = 0;
            }
            size = 0;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        /** Counts the votes of {@code triggers[from]} to {@code triggers[to - 1]}, the trigger blocks of a chunk within {@link #span}. */
        public void add(int sourceX, int sourceZ, int[] triggers, int from, int to) {
            int offset = (sourceX - chunkX + span) * side + sourceZ - chunkZ + span;
            if (offset < 0 || offset >= side * side || Math.abs(sourceZ - chunkZ) > span) {
                throw new IllegalArgumentException("chunk " + sourceX + ", " + sourceZ + " is too far from " + chunkX + ", " + chunkZ);
            }
            for (int i = from; i < to; i++) {
                int packed = triggers[i];
                int[] entries = table[((packed >>> 20) * 256 + (packed >> 4 & 15) * 16 + (packed & 15)) * side * side + offset];
                if (entries == null) {
                    continue;
                }
                int y = packed << 12 >> 20;
                for (int entry : entries) {
                    increment((entry & ~0xFFF) | ((y - (entry << 20 >> 20)) & 0xFFF));
                }
            }
        }

        private void increment(int key) {
            int mask = keys.length - 1;
            int slot = slot(key, mask);
            while (true) {
                int found = keys[slot];
                if (found == key) {
                    counts[slot]++;
                    return;
                }
                if (found == -1) {
                    keys[slot] = key;
                    counts[slot] = 1;
                    used[size++] = slot;
                    if (size * 2 >= keys.length) {
                        // At most half full: then probing stays short, and the slots in use fit into used.
                        grow();
                    }
                    return;
                }
                slot = (slot + 1) & mask;
            }
        }

        private static int slot(int key, int mask) {
            int h = key * 0x9E3779B9;
            return (h ^ (h >>> 16)) & mask;
        }

        private void grow() {
            int[] oldKeys = keys;
            int[] oldCounts = counts;
            int[] oldUsed = used;
            int oldSize = size;
            keys = new int[oldKeys.length * 2];
            counts = new int[oldKeys.length * 2];
            used = new int[oldKeys.length];
            Arrays.fill(keys, -1);
            int mask = keys.length - 1;
            size = 0;
            for (int i = 0; i < oldSize; i++) {
                int key = oldKeys[oldUsed[i]];
                int slot = slot(key, mask);
                while (keys[slot] != -1) {
                    slot = (slot + 1) & mask;
                }
                keys[slot] = key;
                counts[slot] = oldCounts[oldUsed[i]];
                used[size++] = slot;
            }
        }

        /** The placements with at least {@link #MIN_VOTES} votes so far, by layout and then height. */
        public List<Candidate> candidates() {
            long[] picked = new long[size];
            int n = 0;
            for (int i = 0; i < size; i++) {
                if (counts[used[i]] >= MIN_VOTES) {
                    picked[n++] = (long) keys[used[i]] << 32 | counts[used[i]];
                }
            }
            Arrays.sort(picked, 0, n);
            List<Candidate> candidates = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int key = (int) (picked[i] >>> 32);
                candidates.add(new Candidate(layouts.get(key >>> 12), chunkX * 16, key << 20 >> 20, chunkZ * 16, (int) picked[i]));
            }
            return candidates;
        }
    }

    /**
     * Compares a placement with the world. Returns null when it cannot matter: its score is sure to stay below
     * what acceptance or a rival needs ({@code minScore - AMBIGUITY_MARGIN}). The candidate's votes must have been
     * counted on the blocks the view shows ({@link #candidates}): a block that matches by a voting class has voted, so
     * at most the votes plus the layout's telling blocks of other classes can match.
     */
    public Detection verify(Candidate candidate, BlockView view, boolean nether) {
        Verification verification = verification(candidate, nether);
        verification.step(view, Integer.MAX_VALUE);
        return verification.result();
    }

    /** {@link #verify} in small steps. */
    public Verification verification(Candidate candidate, boolean nether) {
        return new Verification(candidate, nether);
    }

    /**
     * The comparison of a placement with the world ({@link #verify}), a few blocks at a time. The empty spots and
     * column tops are only looked at for fits that pass the thresholds: for others they can't change anything.
     */
    public static final class Verification {
        private static final int BLOCKS = 0;
        private static final int TOPS = 1;
        private static final int SPOTS = 2;
        private static final int DONE = 3;

        private final Candidate candidate;
        private final RigidLayout layout;
        private final boolean nether;
        private final double floor;
        private final int[] groupChecked;
        private final long[] positions;
        private int stage = BLOCKS;
        private int next;
        private int matched;
        private int misses;
        private int skipped;
        private int remaining;
        private double weightMatched;
        private double weightChecked;
        private boolean classesCovered = true;
        private boolean spotsLookedAt;
        private int topsChecked;
        private int topsOpen;
        private int emptyChecked;
        private int emptyFilled;
        private int emptyWater;
        private Detection result;

        private Verification(Candidate candidate, boolean nether) {
            this.candidate = candidate;
            this.layout = candidate.layout();
            this.nether = nether;
            this.floor = layout.kind().minScore() - AMBIGUITY_MARGIN - 1e-9;
            this.remaining = layout.informativeCount;
            this.groupChecked = new int[layout.groupTotal.length];
            this.positions = new long[layout.informativeCount];
        }

        /** Reads at most {@code reads} blocks (at least one); true once the comparison is done. */
        public boolean step(BlockView view, int reads) {
            int left = Math.max(1, reads);
            while (stage != DONE) {
                if (stage == BLOCKS) {
                    if (next == layout.size()) {
                        blocksDone();
                    } else if (layout.informative[next]) {
                        if (left-- == 0) {
                            return false;
                        }
                        block(view, next++);
                    } else {
                        next++;
                    }
                } else if (stage == TOPS) {
                    if (next == layout.colX.length) {
                        stage = SPOTS;
                        next = 0;
                    } else {
                        if (left-- == 0) {
                            return false;
                        }
                        top(view, next++);
                    }
                } else if (next == layout.ex.length) {
                    finish();
                } else {
                    if (left-- == 0) {
                        return false;
                    }
                    spot(view, next++);
                }
            }
            return true;
        }

        private void block(BlockView view, int e) {
            remaining--;
            int x = candidate.x() + layout.dx[e];
            int y = candidate.y() + layout.dy[e];
            int z = candidate.z() + layout.dz[e];
            String id = view.blockAt(x, y, z);
            if (id == null) {
                return;
            }
            String found = RigidBlocks.canonical(id);
            if (portalSpread(layout, e, found, nether)) {
                skipped++;
                weightChecked += layout.weight[e];
                return;
            }
            groupChecked[layout.group[e]]++;
            weightChecked += layout.weight[e];
            if (accepts(layout.accept[e], found)) {
                positions[matched++] = position(x, y, z);
                weightMatched += layout.weight[e];
            } else {
                misses++;
                // Even if every block still to come matched, the score would stay too low to matter. Blocks that
                // match by a voting class have voted, so at most votes + unvoted blocks can match in all.
                int possible = Math.min(matched + remaining, Math.max(matched, candidate.votes() + layout.unvoted));
                if ((double) possible / (possible + misses) < floor) {
                    stage = DONE;
                }
            }
        }

        private void blocksDone() {
            if (skipped > 0) {
                // With blocks left unjudged, at least two of the layout's kinds of block (of 3 or more blocks each) must
                // still be judged on a quarter of their blocks: an obsidian frame alone is not a ruined portal.
                int big = 0;
                int covered = 0;
                for (int g = 0; g < layout.groupTotal.length; g++) {
                    if (layout.groupTotal[g] >= 3) {
                        big++;
                        if (groupChecked[g] * 4 >= layout.groupTotal[g]) {
                            covered++;
                        }
                    }
                }
                classesCovered = covered >= Math.min(2, big);
            }
            if (passesThresholds(layout, matched, matched + misses, skipped, balanced(), classesCovered)) {
                spotsLookedAt = true;
                stage = layout.kind() == RigidKind.OCEAN_RUIN ? TOPS : SPOTS;
                next = 0;
            } else {
                finish();
            }
        }

        private double balanced() {
            return weightChecked == 0 ? 0 : weightMatched / weightChecked;
        }

        private void top(BlockView view, int c) {
            int y = candidate.y() + layout.colTop[c] + 1;
            String id = view.blockAt(candidate.x() + layout.colX[c], y, candidate.z() + layout.colZ[c]);
            if (id != null) {
                topsChecked++;
                String found = RigidBlocks.canonical(id);
                if (RigidBlocks.WATERY.contains(found) || found.equals("air") && y >= SEA_LEVEL) {
                    topsOpen++;
                }
            }
        }

        private void spot(BlockView view, int e) {
            String id = view.blockAt(candidate.x() + layout.ex[e], candidate.y() + layout.ey[e], candidate.z() + layout.ez[e]);
            if (id != null) {
                emptyChecked++;
                String found = RigidBlocks.canonical(id);
                if (layout.material.contains(found)) {
                    emptyFilled++;
                }
                if (RigidBlocks.WATERY.contains(found)) {
                    emptyWater++;
                }
            }
        }

        private void finish() {
            long[] sorted = Arrays.copyOf(positions, matched);
            Arrays.sort(sorted);
            result = new Detection(candidate, matched, matched + misses, skipped, balanced(), classesCovered, spotsLookedAt, emptyChecked,
                emptyFilled, emptyWater, topsChecked, topsOpen, sorted);
            stage = DONE;
        }

        public Candidate candidate() {
            return candidate;
        }

        /** The fit once {@link #step} has returned true; null when it cannot matter. */
        public Detection result() {
            if (stage != DONE) {
                throw new IllegalStateException("not done yet");
            }
            return result;
        }
    }

    /**
     * Ruined portals spread netherrack (and magma) over their lowest four layers after the template is placed, and in
     * the nether lava replaces their parts that stand in lava: such blocks are neither a match nor a miss.
     */
    private static boolean portalSpread(RigidLayout layout, int entry, String found, boolean nether) {
        if (layout.kind() != RigidKind.RUINED_PORTAL) {
            return false;
        }
        if (found.equals("netherrack") && layout.dy[entry] <= layout.minDy + 3) {
            return true;
        }
        return nether && found.equals("lava");
    }

    private static boolean accepts(String[] accepted, String found) {
        for (String c : accepted) {
            if (c.equals(found)) {
                return true;
            }
        }
        return false;
    }

    /** A block position as one number (for comparing matched blocks). */
    static long position(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | y & 0xFFFL;
    }

    /** Matched blocks needed: {@link #MIN_MATCHED}, or 70% of the telling blocks for layouts with few of them. */
    static int minMatched(RigidLayout layout) {
        return Math.min(MIN_MATCHED, (int) Math.ceil(0.7 * layout.informativeCount()));
    }

    /** The thresholds on a fit's blocks: enough matched, overall and class by class, and enough of them judged. */
    private static boolean passesThresholds(RigidLayout layout, int matched, int checked, int skipped, double balanced, boolean classesCovered) {
        RigidKind kind = layout.kind();
        double score = checked == 0 ? 0 : (double) matched / checked;
        return matched >= minMatched(layout) && score >= kind.minScore() && balanced >= kind.minBalanced() && classesCovered
            && checked + skipped >= MIN_COVERAGE * layout.informativeCount();
    }

    /**
     * Whether a fit passes on its own: the thresholds, the empty spots, open water for ocean ruins, and the biome.
     * (The cluster test and the comparison with other fits are separate.)
     */
    public boolean acceptable(Detection d, BlockView view) {
        RigidKind kind = d.kind();
        if (!passesThresholds(d.layout(), d.matched(), d.checked(), d.skipped(), d.balanced(), d.classesCovered()) || !d.spotsLookedAt()) {
            return false;
        }
        if (d.emptyChecked() >= MIN_EMPTY_CHECKED && d.emptyFilled() > kind.maxFilled() * d.emptyChecked()) {
            return false;
        }
        // Ocean ruins lie on the sea floor: some of their columns end in open water, and water fills some of their
        // empty spots. (A desert village by a warm ocean once fitted a ruin on dry land.)
        if (kind == RigidKind.OCEAN_RUIN && (d.topsOpen() == 0 || d.topsOpen() < MIN_OPEN_TOPS * d.topsChecked() || d.emptyWater() == 0)) {
            return false;
        }
        return biomeFits(d, view);
    }

    /**
     * The game tests the biome at the start: the chunk's middle for most structures, its corner for ruined portals,
     * at a height near the structure's base. Any 4 x 4 x 4 cell next to that point, from 16 blocks below the base to
     * 16 above, may match: the biomes the client gets differ from the ones the test uses in a few cells at borders.
     * Passes when no biome is known.
     */
    boolean biomeFits(Detection d, BlockView view) {
        Set<String> biomes = rules.get(d.kind()).biomes();
        int x = d.x() + (d.kind().biomeAtCorner() ? 0 : 8);
        int z = d.z() + (d.kind().biomeAtCorner() ? 0 : 8);
        boolean known = false;
        for (int dx = -4; dx <= 4; dx += 4) {
            for (int dz = -4; dz <= 4; dz += 4) {
                for (int y = d.y() - BIOME_REACH; y <= d.y() + BIOME_REACH; y += 4) {
                    String biome = view.biomeAt(x + dx, y, z + dz);
                    if (biome != null) {
                        known = true;
                        if (biomes.contains(biome)) {
                            return true;
                        }
                    }
                }
            }
        }
        return !known;
    }

    /** Whether the fit is a small ocean ruin, which must pass {@link #isClusterPiece} before it is accepted. */
    public boolean needsClusterCheck(Detection d) {
        return d.kind() == RigidKind.OCEAN_RUIN && d.layout().informativeCount() < SMALL_RUIN;
    }

    /**
     * A big ocean ruin is surrounded by 4 to 8 small "cluster" ruins whose template origins are never on the chunk
     * grid. A grid fit of a small ruin on such a piece is only a shifted partial fit: some small-ruin layout fits the
     * same blocks clearly better at an origin off the grid, within {@link #CLUSTER_REACH} blocks sideways and
     * {@link #CLUSTER_HEIGHT} up or down. Found by voting over the blocks around the fit.
     */
    public boolean isClusterPiece(Detection d, BlockView view) {
        if (!needsClusterCheck(d)) {
            return false;
        }
        ClusterTest test = clusterTest(d);
        while (!test.step(view, Integer.MAX_VALUE)) {
            // next layout
        }
        return test.isClusterPiece();
    }

    /** {@link #isClusterPiece} in small steps (for a small ocean ruin, {@link #needsClusterCheck}). */
    public ClusterTest clusterTest(Detection d) {
        return clusterTest(d, new ClusterBuffers());
    }

    /** {@link #clusterTest(Detection)} with room for its counts that is used again from one test to the next. */
    public ClusterTest clusterTest(Detection d, ClusterBuffers buffers) {
        if (!needsClusterCheck(d)) {
            throw new IllegalArgumentException("no cluster test for " + d);
        }
        return new ClusterTest(d, buffers);
    }

    /** Room for the counts of cluster tests (about a megabyte), for one test at a time. */
    public static final class ClusterBuffers {
        private short[] votes = new short[0];
        private boolean[] visible = new boolean[0];
    }

    /** The cluster test of one fit: the area around it read a few blocks at a time, then the off-grid fits counted. */
    public final class ClusterTest {
        private static final int COUNT_X = 2 * CLUSTER_REACH + 1;
        private static final int COUNT_Y = 2 * CLUSTER_HEIGHT + 1;
        private static final int COUNT_Z = 2 * CLUSTER_REACH + 1;
        /** Counts cleared per step when the buffers are used again. */
        private static final int CLEAR_SLICE = 1 << 16;
        /** A block read costs 1, plus 1 for every this many small-ruin entries its class votes for. */
        private static final int ENTRIES_PER_READ = 64;

        private final Detection d;
        private final int need;
        private final int fromX;
        private final int fromY;
        private final int fromZ;
        private final int minX;
        private final int minY;
        private final int minZ;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private final ClusterBuffers buffers;
        private final int visibleLength;
        private final int votesLength;
        private boolean[] visible;
        private short[] votes;
        private int cleared;
        private int nextRead;
        private int nextLayout;
        private int nextOriginX;
        private double best;

        private ClusterTest(Detection d, ClusterBuffers buffers) {
            this.d = d;
            this.buffers = buffers;
            ClusterIndex index = clusterIndex;
            need = minMatched(d.layout());
            fromX = d.x() - CLUSTER_REACH;
            fromY = d.y() - CLUSTER_HEIGHT;
            fromZ = d.z() - CLUSTER_REACH;
            minX = fromX + index.minDx;
            minY = fromY + index.minDy;
            minZ = fromZ + index.minDz;
            sizeX = COUNT_X - 1 + index.maxDx - index.minDx + 1;
            sizeY = COUNT_Y - 1 + index.maxDy - index.minDy + 1;
            sizeZ = COUNT_Z - 1 + index.maxDz - index.minDz + 1;
            visibleLength = sizeX * sizeY * sizeZ;
            votesLength = index.layouts.length * COUNT_X * COUNT_Y * COUNT_Z;
        }

        /**
         * Clears a slice of the counts, reads blocks of the area worth {@code reads} block reads (at least one; a
         * block whose class votes for many small-ruin entries costs more), or counts the fits of one layout at one
         * sideways offset; true once done.
         */
        public boolean step(BlockView view, int reads) {
            if (votes == null) {
                if (buffers.votes.length < votesLength || buffers.visible.length < visibleLength) {
                    buffers.votes = new short[votesLength];
                    buffers.visible = new boolean[visibleLength];
                    cleared = votesLength;
                }
                votes = buffers.votes;
                visible = buffers.visible;
                return false;
            }
            if (cleared < votesLength) {
                int to = Math.min(votesLength, cleared + CLEAR_SLICE);
                Arrays.fill(votes, cleared, to, (short) 0);
                if (cleared == 0) {
                    Arrays.fill(visible, 0, visibleLength, false);
                }
                cleared = to;
                return false;
            }
            if (nextRead < visibleLength) {
                int budget = Math.max(1, reads);
                while (budget > 0 && nextRead < visibleLength) {
                    budget -= read(view, nextRead / (sizeY * sizeZ), nextRead / sizeZ % sizeY, nextRead % sizeZ);
                    nextRead++;
                }
                return false;
            }
            if (nextLayout < clusterIndex.layouts.length) {
                count(nextLayout, nextOriginX);
                if (++nextOriginX == COUNT_X) {
                    nextOriginX = 0;
                    nextLayout++;
                }
            }
            return nextLayout == clusterIndex.layouts.length;
        }

        /** Reads one block; returns what it cost. */
        private int read(BlockView view, int x, int y, int z) {
            String id = view.blockAt(minX + x, minY + y, minZ + z);
            if (id == null) {
                return 1;
            }
            visible[(x * sizeY + y) * sizeZ + z] = true;
            int[] entries = clusterIndex.byClass.get(RigidBlocks.canonical(id));
            if (entries == null) {
                return 1;
            }
            for (int entry : entries) {
                RigidLayout layout = clusterIndex.layouts[entry >>> 16];
                int e = entry & 0xFFFF;
                int ox = minX + x - layout.dx[e] - fromX;
                int oy = minY + y - layout.dy[e] - fromY;
                int oz = minZ + z - layout.dz[e] - fromZ;
                if (ox >= 0 && ox < COUNT_X && oy >= 0 && oy < COUNT_Y && oz >= 0 && oz < COUNT_Z) {
                    votes[(((entry >>> 16) * COUNT_X + ox) * COUNT_Y + oy) * COUNT_Z + oz]++;
                }
            }
            return 1 + entries.length / ENTRIES_PER_READ;
        }

        private void count(int li, int ox) {
            RigidLayout layout = clusterIndex.layouts[li];
            for (int oz = 0; oz < COUNT_Z; oz++) {
                if ((fromX + ox & 15) == 0 && (fromZ + oz & 15) == 0) {
                    continue;
                }
                for (int oy = 0; oy < COUNT_Y; oy++) {
                    int matched = votes[((li * COUNT_X + ox) * COUNT_Y + oy) * COUNT_Z + oz];
                    if (matched < need) {
                        continue;
                    }
                    int checked = 0;
                    for (int e = 0; e < layout.size(); e++) {
                        if (layout.informative[e]) {
                            int x = fromX + ox + layout.dx[e] - minX;
                            int y = fromY + oy + layout.dy[e] - minY;
                            int z = fromZ + oz + layout.dz[e] - minZ;
                            if (visible[(x * sizeY + y) * sizeZ + z]) {
                                checked++;
                            }
                        }
                    }
                    if (checked > 0) {
                        best = Math.max(best, (double) matched / checked);
                    }
                }
            }
        }

        public Detection detection() {
            return d;
        }

        /** The answer once {@link #step} has returned true. */
        public boolean isClusterPiece() {
            if (nextLayout < clusterIndex.layouts.length || votes == null || nextRead < visibleLength) {
                throw new IllegalStateException("not done yet");
            }
            return best > d.score() + CLUSTER_MARGIN;
        }
    }

    /** The small-ruin layouts and, for each block class, their telling entries that accept it. */
    private record ClusterIndex(RigidLayout[] layouts, Map<String, int[]> byClass, int minDx, int maxDx, int minDy, int maxDy, int minDz,
                                int maxDz) {
    }

    private static ClusterIndex clusterIndex(List<RigidLayout> layouts) {
        // Layouts with the same telling blocks give the same counts (the cold ruins' brick, cracked and mossy
        // templates are the same classes): one of each is enough for the best fit.
        Map<List<String>, RigidLayout> distinct = new LinkedHashMap<>();
        for (RigidLayout layout : layouts) {
            if (layout.kind() == RigidKind.OCEAN_RUIN && layout.informativeCount() < SMALL_RUIN) {
                List<String> telling = new ArrayList<>();
                for (int e = 0; e < layout.size(); e++) {
                    if (layout.informative[e]) {
                        String[] accepted = layout.accept[e].clone();
                        Arrays.sort(accepted);
                        telling.add(layout.dx[e] + "," + layout.dy[e] + "," + layout.dz[e] + "=" + String.join("|", accepted));
                    }
                }
                Collections.sort(telling);
                distinct.putIfAbsent(telling, layout);
            }
        }
        List<RigidLayout> small = List.copyOf(distinct.values());
        Map<String, List<Integer>> byClass = new HashMap<>();
        int[] bounds = {0, 0, 0, 0, 0, 0};
        boolean first = true;
        for (int li = 0; li < small.size(); li++) {
            RigidLayout layout = small.get(li);
            for (int e = 0; e < layout.size(); e++) {
                if (!layout.informative[e]) {
                    continue;
                }
                for (String c : layout.accept[e]) {
                    byClass.computeIfAbsent(c, key -> new ArrayList<>()).add(li << 16 | e);
                }
                if (first) {
                    bounds = new int[] {layout.dx[e], layout.dx[e], layout.dy[e], layout.dy[e], layout.dz[e], layout.dz[e]};
                    first = false;
                }
                bounds[0] = Math.min(bounds[0], layout.dx[e]);
                bounds[1] = Math.max(bounds[1], layout.dx[e]);
                bounds[2] = Math.min(bounds[2], layout.dy[e]);
                bounds[3] = Math.max(bounds[3], layout.dy[e]);
                bounds[4] = Math.min(bounds[4], layout.dz[e]);
                bounds[5] = Math.max(bounds[5], layout.dz[e]);
            }
        }
        Map<String, int[]> entries = new HashMap<>();
        byClass.forEach((c, list) -> entries.put(c, list.stream().mapToInt(Integer::intValue).toArray()));
        return new ClusterIndex(small.toArray(new RigidLayout[0]), entries, bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
    }

    /**
     * Decides among verified fits, strongest first, and returns those accepted in chunks where their set can start.
     * A fit is accepted when it is {@link #acceptable}, no stronger accepted fit of its set is within the set's
     * separation (two starts that close cannot both exist), it shares less than 30% of its matched blocks with any
     * stronger accepted fit, no rival makes its start chunk uncertain, and (small ocean ruins) it is not a cluster
     * piece.
     *
     * @param clusterPiece the cluster test ({@link #isClusterPiece}), asked only for small ocean ruins that pass
     *                     everything else
     */
    public List<Detection> resolve(List<Detection> detections, BlockView view, Predicate<Detection> clusterPiece) {
        Resolution resolution = resolution(detections);
        while (!resolution.step(view, clusterPiece::test)) {
            // next fit
        }
        return resolution.result();
    }

    /** {@link #resolve} one fit at a time. */
    public Resolution resolution(List<Detection> detections) {
        return new Resolution(detections);
    }

    /** The decision among verified fits ({@link #resolve}), one fit per step. */
    public final class Resolution {
        private final List<Detection> order;
        private final List<Detection> accepted = new ArrayList<>();
        private int next;
        /** The fit that passed everything but the cluster test, which has no answer yet. */
        private Detection waiting;

        private Resolution(List<Detection> detections) {
            order = new ArrayList<>(detections);
            order.sort(STRONGEST_FIRST);
        }

        /**
         * Decides the next fit; true once all are decided. {@code clusterPiece} gives the cluster test's answer for a
         * fit, or null when it is not known yet: then the fit waits ({@link #waitingFor}) and is decided by a later
         * call.
         */
        public boolean step(BlockView view, Function<Detection, Boolean> clusterPiece) {
            if (next < order.size()) {
                Detection d = order.get(next);
                if (waiting != d) {
                    if (!acceptable(d, view) || conflicts(d, accepted) || ambiguous(d, order)) {
                        next++;
                        return next == order.size();
                    }
                    if (!needsClusterCheck(d)) {
                        accepted.add(d);
                        next++;
                        return next == order.size();
                    }
                    waiting = d;
                }
                Boolean piece = clusterPiece.apply(d);
                if (piece == null) {
                    return false;
                }
                waiting = null;
                if (!piece) {
                    accepted.add(d);
                }
                next++;
            }
            return next == order.size();
        }

        /** The fit whose cluster test is needed before the next step can decide it, or null. */
        public Detection waitingFor() {
            return waiting;
        }

        /** The fits accepted, in chunks where their set can start (once all are decided). */
        public List<Detection> result() {
            if (next < order.size()) {
                throw new IllegalStateException("not done yet");
            }
            return accepted.stream().filter(d -> rules.get(d.kind()).couldStartIn(d.chunkX(), d.chunkZ())).toList();
        }
    }

    private boolean conflicts(Detection d, List<Detection> accepted) {
        for (Detection a : accepted) {
            if (a.kind() == d.kind()) {
                int separation = rules.get(d.kind()).separation();
                if (Math.abs(a.chunkX() - d.chunkX()) <= separation && Math.abs(a.chunkZ() - d.chunkZ()) <= separation) {
                    return true;
                }
            }
            if (overlap(a, d) >= DROP_OVERLAP) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a fit with another start chunk explains mostly the same blocks almost as well: then the blocks seen do
     * not pin the start chunk down.
     */
    private static boolean ambiguous(Detection d, List<Detection> all) {
        for (Detection o : all) {
            if (o == d || o.kind() != d.kind() || o.chunkX() == d.chunkX() && o.chunkZ() == d.chunkZ()) {
                continue;
            }
            if (o.matched() < minMatched(o.layout()) || o.matched() < d.matched() / 2 || o.score() < d.score() - AMBIGUITY_MARGIN
                || o.balanced() < d.balanced() - AMBIGUITY_MARGIN || !o.classesCovered()) {
                continue;
            }
            if (Math.abs(o.x() - d.x()) > AMBIGUITY_DISTANCE || Math.abs(o.z() - d.z()) > AMBIGUITY_DISTANCE) {
                continue;
            }
            if (overlap(d, o) >= AMBIGUOUS_OVERLAP) {
                return true;
            }
        }
        return false;
    }

    /** Shared matched blocks, as a share of the matched blocks of the fit with fewer. */
    static double overlap(Detection a, Detection b) {
        if (Math.abs(a.x() - b.x()) > OVERLAP_DISTANCE || Math.abs(a.z() - b.z()) > OVERLAP_DISTANCE) {
            return 0;
        }
        long[] small = a.matchedPositions.length <= b.matchedPositions.length ? a.matchedPositions : b.matchedPositions;
        long[] big = small == a.matchedPositions ? b.matchedPositions : a.matchedPositions;
        if (small.length == 0) {
            return 0;
        }
        int shared = 0;
        int i = 0;
        int j = 0;
        while (i < small.length && j < big.length) {
            if (small[i] == big[j]) {
                shared++;
                i++;
                j++;
            } else if (small[i] < big[j]) {
                i++;
            } else {
                j++;
            }
        }
        return (double) shared / small.length;
    }

    /**
     * Recognizes everything in a window of loaded chunks at once, with votes from the window's blocks only. This is
     * how the recognizer was measured; the mod works chunk by chunk instead ({@link RigidTracker}).
     */
    public List<Detection> scan(BlockView view, int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ, int minY, int maxY, boolean nether) {
        Map<Long, int[]> triggers = new HashMap<>();
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                if (view.isLoaded(x, z)) {
                    triggers.put(chunkKey(x, z), triggersOf(view, x, z, minY, maxY));
                }
            }
        }
        int[] none = new int[0];
        Triggers source = (x, z) -> triggers.getOrDefault(chunkKey(x, z), none);
        List<Detection> verified = new ArrayList<>();
        for (int x = minChunkX - span; x <= maxChunkX + span; x++) {
            for (int z = minChunkZ - span; z <= maxChunkZ + span; z++) {
                for (Candidate candidate : candidates(x, z, source)) {
                    Detection d = verify(candidate, view, nether);
                    if (d != null) {
                        verified.add(d);
                    }
                }
            }
        }
        return resolve(verified, view, d -> isClusterPiece(d, view));
    }

    static long chunkKey(int chunkX, int chunkZ) {
        return (long) chunkX << 32 | chunkZ & 0xFFFFFFFFL;
    }
}
