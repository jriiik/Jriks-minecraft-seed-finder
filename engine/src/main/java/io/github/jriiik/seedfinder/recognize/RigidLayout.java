package io.github.jriiik.seedfinder.recognize;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * One rigid structure variant in one pose (rotation and mirror), as recognition compares it: block classes at offsets
 * from the start chunk's north-west corner and the piece's base height, plus the spots the piece leaves empty.
 *
 * <p>Layouts are made from the game's own files and code at runtime (the mod does that); they are never stored in
 * this project.
 */
public final class RigidLayout {

    /** Blocks the layout ignores: they are never part of a built structure. */
    private static final Set<String> SKIPPED = Set.of("air", "structure_block", "jigsaw", "structure_void");
    /** Portal blocks the game removes or replaces at random, or that are fluid. */
    private static final Set<String> PORTAL_DROPPED = Set.of("gold_block", "lava", "chest");

    /**
     * A block of a structure: its offset from the start chunk's north-west corner (x, z) and from the piece's base
     * (y), and the block ids that can be there (several when templates are stacked, or a block may be replaced).
     */
    public record Entry(int dx, int dy, int dz, Set<String> blocks) {
    }

    private final RigidKind kind;
    private final String variant;
    final int[] dx;
    final int[] dy;
    final int[] dz;
    /** The classes accepted at each entry. */
    final String[][] accept;
    /** The classes of each entry that vote. */
    final String[][] trigger;
    /** Whether an entry counts for the score: every class it accepts is one of the kind's informative ones. */
    final boolean[] informative;
    /** Class-balanced weight: 1 / (informative entries accepting the same classes). */
    final double[] weight;
    /** The class group of each informative entry, and how many informative entries each group has. */
    final int[] group;
    final int[] groupTotal;
    final int informativeCount;
    /** Spots the structure leaves empty. */
    final int[] ex;
    final int[] ey;
    final int[] ez;
    /** The informative classes of this layout (what may not fill its empty spots). */
    final Set<String> material;
    final int minDy;
    /** Per column of the layout, its highest block (for the open-water test of ocean ruins). */
    final int[] colX;
    final int[] colZ;
    final int[] colTop;

    private RigidLayout(RigidKind kind, String variant, List<int[]> positions, List<Set<String>> classes, List<int[]> empty) {
        this.kind = kind;
        this.variant = variant;
        int n = positions.size();
        dx = new int[n];
        dy = new int[n];
        dz = new int[n];
        accept = new String[n][];
        trigger = new String[n][];
        informative = new boolean[n];
        Map<String, Integer> perGroup = new LinkedHashMap<>();
        String[] groupKey = new String[n];
        Set<String> materials = new HashSet<>();
        int lowest = Integer.MAX_VALUE;
        int informatives = 0;
        for (int i = 0; i < n; i++) {
            dx[i] = positions.get(i)[0];
            dy[i] = positions.get(i)[1];
            dz[i] = positions.get(i)[2];
            accept[i] = classes.get(i).toArray(new String[0]);
            trigger[i] = classes.get(i).stream().filter(kind.triggers()::contains).toArray(String[]::new);
            informative[i] = kind.informative().containsAll(classes.get(i));
            lowest = Math.min(lowest, dy[i]);
            if (informative[i]) {
                informatives++;
                groupKey[i] = String.join("|", classes.get(i));
                perGroup.merge(groupKey[i], 1, Integer::sum);
                materials.addAll(classes.get(i));
            }
        }
        informativeCount = informatives;
        minDy = lowest;
        material = Set.copyOf(materials);
        weight = new double[n];
        group = new int[n];
        Map<String, Integer> groupIndex = new HashMap<>();
        for (int i = 0; i < n; i++) {
            if (informative[i]) {
                weight[i] = 1.0 / perGroup.get(groupKey[i]);
                group[i] = groupIndex.computeIfAbsent(groupKey[i], key -> groupIndex.size());
            }
        }
        groupTotal = new int[groupIndex.size()];
        for (int i = 0; i < n; i++) {
            if (informative[i]) {
                groupTotal[group[i]]++;
            }
        }
        ex = new int[empty.size()];
        ey = new int[empty.size()];
        ez = new int[empty.size()];
        for (int i = 0; i < empty.size(); i++) {
            ex[i] = empty.get(i)[0];
            ey[i] = empty.get(i)[1];
            ez[i] = empty.get(i)[2];
        }
        // Every block counts for the column tops: the cold ruins carry their own gravel on top of the bricks.
        Map<Long, Integer> tops = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            tops.merge(((long) dx[i] << 32) ^ (dz[i] & 0xFFFFFFFFL), dy[i], Math::max);
        }
        colX = new int[tops.size()];
        colZ = new int[tops.size()];
        colTop = new int[tops.size()];
        int column = 0;
        for (Map.Entry<Long, Integer> top : tops.entrySet()) {
            colX[column] = (int) (top.getKey() >> 32);
            colZ[column] = (int) (long) top.getKey();
            colTop[column] = top.getValue();
            column++;
        }
    }

    /**
     * Makes a layout from a piece's blocks (block ids such as {@code minecraft:oak_planks}; air included or not) and
     * the spots where the piece has air. Blocks at the same spot are merged (stacked templates), and a spot is only
     * empty when no stacked template has a block there. The kind's own rules are applied: ruined portals accept any
     * made stone of the family where the template has one (ageing and blackstone swap them), and their gold blocks,
     * lava and chests are left out (removed at random, or fluid).
     */
    public static RigidLayout of(RigidKind kind, String variant, List<Entry> entries, List<int[]> empty) {
        Map<List<Integer>, Set<String>> merged = new LinkedHashMap<>();
        Set<List<Integer>> blocked = new HashSet<>();
        for (Entry entry : entries) {
            Set<String> classes = new TreeSet<>();
            for (String id : entry.blocks()) {
                String c = RigidBlocks.canonical(id);
                if (!c.equals("air")) {
                    blocked.add(List.of(entry.dx(), entry.dy(), entry.dz()));
                }
                if (SKIPPED.contains(c)) {
                    continue;
                }
                if (kind == RigidKind.RUINED_PORTAL) {
                    if (PORTAL_DROPPED.contains(c)) {
                        continue;
                    }
                    if (RigidBlocks.STONE_FAMILY.contains(c)) {
                        classes.addAll(RigidBlocks.STONE_FAMILY_ARTIFICIAL);
                        continue;
                    }
                }
                classes.add(c);
            }
            if (!classes.isEmpty()) {
                merged.computeIfAbsent(List.of(entry.dx(), entry.dy(), entry.dz()), key -> new TreeSet<>()).addAll(classes);
            }
        }
        List<int[]> positions = new ArrayList<>();
        List<Set<String>> classes = new ArrayList<>();
        merged.forEach((position, accepted) -> {
            positions.add(new int[] {position.get(0), position.get(1), position.get(2)});
            classes.add(accepted);
        });
        List<int[]> open = new ArrayList<>();
        Set<List<Integer>> seen = new HashSet<>();
        for (int[] spot : empty) {
            List<Integer> key = List.of(spot[0], spot[1], spot[2]);
            if (!blocked.contains(key) && seen.add(key)) {
                open.add(spot.clone());
            }
        }
        return new RigidLayout(kind, variant, positions, classes, open);
    }

    public RigidKind kind() {
        return kind;
    }

    /** For messages, e.g. {@code ruined_portal/portal_3 CLOCKWISE_90 FRONT_BACK}. */
    public String variant() {
        return variant;
    }

    /** How many blocks the layout compares. */
    public int size() {
        return dx.length;
    }

    /** How many of them count for the score. */
    public int informativeCount() {
        return informativeCount;
    }

    /** The offset ranges of the layout's blocks: {minX, maxX, minY, maxY, minZ, maxZ}. */
    int[] bounds() {
        int[] bounds = {Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (int i = 0; i < size(); i++) {
            bounds[0] = Math.min(bounds[0], dx[i]);
            bounds[1] = Math.max(bounds[1], dx[i]);
            bounds[2] = Math.min(bounds[2], dy[i]);
            bounds[3] = Math.max(bounds[3], dy[i]);
            bounds[4] = Math.min(bounds[4], dz[i]);
            bounds[5] = Math.max(bounds[5], dz[i]);
        }
        return bounds;
    }

    @Override
    public String toString() {
        return kind + " " + variant + " (" + size() + " blocks, " + informativeCount + " informative, " + ex.length + " empty)";
    }
}
