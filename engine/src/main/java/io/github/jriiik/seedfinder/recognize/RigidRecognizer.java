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
        private final int emptyChecked;
        private final int emptyFilled;
        private final int emptyWater;
        private final int topsChecked;
        private final int topsOpen;
        /** The matched blocks' positions ({@link #position}), sorted. */
        final long[] matchedPositions;

        Detection(Candidate candidate, int matched, int checked, int skipped, double balanced, boolean classesCovered, int emptyChecked,
                  int emptyFilled, int emptyWater, int topsChecked, int topsOpen, long[] matchedPositions) {
            this.candidate = candidate;
            this.matched = matched;
            this.checked = checked;
            this.skipped = skipped;
            this.balanced = balanced;
            this.classesCovered = classesCovered;
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
            return String.format(java.util.Locale.ROOT,
                "%s start chunk (%d, %d) origin (%d, %d, %d) %s: matched %d/%d (%.2f) balanced %.2f, skipped %d, empty filled %d/%d, water %d,"
                    + " open tops %d/%d, votes %d",
                kind().structureSet(), chunkX(), chunkZ(), x(), y(), z(), layout().variant(), matched, checked, score(), balanced, skipped,
                emptyFilled, emptyChecked, emptyWater, topsOpen, topsChecked, candidate.votes());
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
    /**
     * For each trigger class, position within the chunk ((x & 15) * 16 + (z & 15)) and chunk offset from the origin
     * chunk: the layout entries (layout index << 16 | entry index) that put the origin on a chunk corner.
     */
    private final int[][] table;
    private ClusterIndex clusterIndex;

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

    /** Layouts of kinds without rules are left out. */
    public RigidRecognizer(List<RigidLayout> layouts, Map<RigidKind, Rules> rules) {
        this.rules = rules.isEmpty() ? Map.of() : Collections.unmodifiableMap(new EnumMap<>(rules));
        this.layouts = layouts.stream().filter(layout -> rules.containsKey(layout.kind())).toList();
        if (this.layouts.size() >= 1 << 15) {
            throw new IllegalArgumentException("too many layouts: " + this.layouts.size());
        }
        int reach = 0;
        for (RigidLayout layout : this.layouts) {
            if (layout.size() >= 1 << 16) {
                throw new IllegalArgumentException("layout too big: " + layout);
            }
            for (int e = 0; e < layout.size(); e++) {
                for (String c : layout.trigger[e]) {
                    triggerClasses.computeIfAbsent(c, key -> triggerClasses.size());
                }
                reach = Math.max(reach, Math.max(Math.abs(layout.dx[e] >> 4), Math.abs(layout.dz[e] >> 4)));
            }
        }
        span = reach;
        side = 2 * span + 1;
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
                    cells.get(cell).add(li << 16 | e);
                }
            }
        }
        table = new int[size][];
        for (int i = 0; i < size; i++) {
            if (cells.get(i) != null) {
                table[i] = cells.get(i).stream().mapToInt(Integer::intValue).toArray();
            }
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

    /** How many chunks from its origin chunk a layout's blocks can be. */
    public int span() {
        return span;
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
        Map<Integer, int[]> votes = new HashMap<>();
        for (int sourceX = chunkX - span; sourceX <= chunkX + span; sourceX++) {
            for (int sourceZ = chunkZ - span; sourceZ <= chunkZ + span; sourceZ++) {
                int[] found = triggers.at(sourceX, sourceZ);
                if (found == null) {
                    return null;
                }
                int offset = (sourceX - chunkX + span) * side + sourceZ - chunkZ + span;
                for (int packed : found) {
                    int[] entries = table[((packed >>> 20) * 256 + (packed >> 4 & 15) * 16 + (packed & 15)) * side * side + offset];
                    if (entries == null) {
                        continue;
                    }
                    int y = packed << 12 >> 20;
                    for (int entry : entries) {
                        int originY = y - layouts.get(entry >>> 16).dy[entry & 0xFFFF];
                        votes.computeIfAbsent((entry >>> 16) << 12 | originY & 0xFFF, key -> new int[1])[0]++;
                    }
                }
            }
        }
        int[] keys = votes.entrySet().stream().filter(entry -> entry.getValue()[0] >= MIN_VOTES).mapToInt(Map.Entry::getKey).sorted().toArray();
        List<Candidate> candidates = new ArrayList<>(keys.length);
        for (int key : keys) {
            candidates.add(new Candidate(layouts.get(key >>> 12), chunkX * 16, key << 20 >> 20, chunkZ * 16, votes.get(key)[0]));
        }
        return candidates;
    }

    /**
     * Compares a placement with the world. Returns null when it cannot matter: its score is sure to stay below
     * what acceptance or a rival needs ({@code minScore - AMBIGUITY_MARGIN}).
     */
    public Detection verify(Candidate candidate, BlockView view, boolean nether) {
        RigidLayout layout = candidate.layout();
        RigidKind kind = layout.kind();
        double floor = kind.minScore() - AMBIGUITY_MARGIN - 1e-9;
        int originX = candidate.x();
        int originY = candidate.y();
        int originZ = candidate.z();
        int matched = 0;
        int misses = 0;
        int skipped = 0;
        int remaining = layout.informativeCount;
        int[] groupChecked = new int[layout.groupTotal.length];
        double weightMatched = 0;
        double weightChecked = 0;
        long[] positions = new long[layout.informativeCount];
        for (int e = 0; e < layout.size(); e++) {
            if (!layout.informative[e]) {
                continue;
            }
            remaining--;
            int x = originX + layout.dx[e];
            int y = originY + layout.dy[e];
            int z = originZ + layout.dz[e];
            String id = view.blockAt(x, y, z);
            if (id == null) {
                continue;
            }
            String found = RigidBlocks.canonical(id);
            if (portalSpread(layout, e, found, nether)) {
                skipped++;
                weightChecked += layout.weight[e];
                continue;
            }
            groupChecked[layout.group[e]]++;
            weightChecked += layout.weight[e];
            if (accepts(layout.accept[e], found)) {
                positions[matched++] = position(x, y, z);
                weightMatched += layout.weight[e];
            } else {
                misses++;
                // Even if every block still to come matched, the score would stay too low to matter.
                if ((double) (matched + remaining) / (matched + misses + remaining) < floor) {
                    return null;
                }
            }
        }
        int checked = matched + misses;
        boolean classesCovered = true;
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
        int topsChecked = 0;
        int topsOpen = 0;
        if (kind == RigidKind.OCEAN_RUIN) {
            for (int c = 0; c < layout.colX.length; c++) {
                int y = originY + layout.colTop[c] + 1;
                String id = view.blockAt(originX + layout.colX[c], y, originZ + layout.colZ[c]);
                if (id != null) {
                    topsChecked++;
                    String found = RigidBlocks.canonical(id);
                    if (RigidBlocks.WATERY.contains(found) || found.equals("air") && y >= SEA_LEVEL) {
                        topsOpen++;
                    }
                }
            }
        }
        int emptyChecked = 0;
        int emptyFilled = 0;
        int emptyWater = 0;
        for (int e = 0; e < layout.ex.length; e++) {
            String id = view.blockAt(originX + layout.ex[e], originY + layout.ey[e], originZ + layout.ez[e]);
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
        long[] sorted = Arrays.copyOf(positions, matched);
        Arrays.sort(sorted);
        return new Detection(candidate, matched, checked, skipped, weightChecked == 0 ? 0 : weightMatched / weightChecked, classesCovered,
            emptyChecked, emptyFilled, emptyWater, topsChecked, topsOpen, sorted);
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

    /**
     * Whether a fit passes on its own: the thresholds, the empty spots, open water for ocean ruins, and the biome.
     * (The cluster test and the comparison with other fits are separate.)
     */
    public boolean acceptable(Detection d, BlockView view) {
        RigidKind kind = d.kind();
        RigidLayout layout = d.layout();
        if (d.matched() < minMatched(layout) || d.score() < kind.minScore() || d.balanced() < kind.minBalanced() || !d.classesCovered()
            || d.checked() + d.skipped() < MIN_COVERAGE * layout.informativeCount()) {
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
        ClusterIndex index = clusterIndex();
        int need = minMatched(d.layout());
        int fromX = d.x() - CLUSTER_REACH;
        int fromY = d.y() - CLUSTER_HEIGHT;
        int fromZ = d.z() - CLUSTER_REACH;
        int countX = 2 * CLUSTER_REACH + 1;
        int countY = 2 * CLUSTER_HEIGHT + 1;
        int countZ = 2 * CLUSTER_REACH + 1;
        int minX = fromX + index.minDx;
        int minY = fromY + index.minDy;
        int minZ = fromZ + index.minDz;
        int sizeX = countX - 1 + index.maxDx - index.minDx + 1;
        int sizeY = countY - 1 + index.maxDy - index.minDy + 1;
        int sizeZ = countZ - 1 + index.maxDz - index.minDz + 1;
        boolean[] visible = new boolean[sizeX * sizeY * sizeZ];
        int[] votes = new int[index.layouts.length * countX * countY * countZ];
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    String id = view.blockAt(minX + x, minY + y, minZ + z);
                    if (id == null) {
                        continue;
                    }
                    visible[(x * sizeY + y) * sizeZ + z] = true;
                    int[] entries = index.byClass.get(RigidBlocks.canonical(id));
                    if (entries == null) {
                        continue;
                    }
                    for (int entry : entries) {
                        RigidLayout layout = index.layouts[entry >>> 16];
                        int e = entry & 0xFFFF;
                        int ox = minX + x - layout.dx[e] - fromX;
                        int oy = minY + y - layout.dy[e] - fromY;
                        int oz = minZ + z - layout.dz[e] - fromZ;
                        if (ox >= 0 && ox < countX && oy >= 0 && oy < countY && oz >= 0 && oz < countZ) {
                            votes[(((entry >>> 16) * countX + ox) * countY + oy) * countZ + oz]++;
                        }
                    }
                }
            }
        }
        double best = 0;
        for (int li = 0; li < index.layouts.length; li++) {
            RigidLayout layout = index.layouts[li];
            for (int ox = 0; ox < countX; ox++) {
                for (int oz = 0; oz < countZ; oz++) {
                    if ((fromX + ox & 15) == 0 && (fromZ + oz & 15) == 0) {
                        continue;
                    }
                    for (int oy = 0; oy < countY; oy++) {
                        int matched = votes[((li * countX + ox) * countY + oy) * countZ + oz];
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
        }
        return best > d.score() + CLUSTER_MARGIN;
    }

    /** The small-ruin layouts and, for each block class, their telling entries that accept it. */
    private record ClusterIndex(RigidLayout[] layouts, Map<String, int[]> byClass, int minDx, int maxDx, int minDy, int maxDy, int minDz,
                                int maxDz) {
    }

    private synchronized ClusterIndex clusterIndex() {
        if (clusterIndex == null) {
            List<RigidLayout> small = layouts.stream()
                .filter(layout -> layout.kind() == RigidKind.OCEAN_RUIN && layout.informativeCount() < SMALL_RUIN).toList();
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
            clusterIndex = new ClusterIndex(small.toArray(new RigidLayout[0]), entries, bounds[0], bounds[1], bounds[2], bounds[3], bounds[4],
                bounds[5]);
        }
        return clusterIndex;
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
        List<Detection> order = new ArrayList<>(detections);
        order.sort(STRONGEST_FIRST);
        List<Detection> accepted = new ArrayList<>();
        for (Detection d : order) {
            if (acceptable(d, view) && !conflicts(d, accepted) && !ambiguous(d, order) && !(needsClusterCheck(d) && clusterPiece.test(d))) {
                accepted.add(d);
            }
        }
        return accepted.stream().filter(d -> rules.get(d.kind()).couldStartIn(d.chunkX(), d.chunkZ())).toList();
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
