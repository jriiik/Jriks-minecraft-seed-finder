package io.github.jriiik.seedfinder.recognize;

import io.github.jriiik.seedfinder.world.BlockPos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The blueprint of a structure's start piece, cut down to what recognition compares: the blocks that are sure to be
 * there after the game builds the piece (no air, no plants, nothing a processor may swap for something else), and
 * the point the game puts exactly on the start chunk's north-west corner.
 *
 * <p>Blueprints come from the game's own files at runtime (the mod reads them from the game jar); they are never
 * stored in this project.
 */
public final class StartPiece {

    /** A block of the blueprint, at its template-local position. */
    public record Block(int x, int y, int z, String id) {
    }

    private final String name;
    private final String structureSet;
    private final int spacing;
    private final int separation;
    private final List<BlockPos> references;
    private final int[] xs;
    private final int[] ys;
    private final int[] zs;
    private final String[] ids;
    private final boolean[] built;
    private final int builtCount;
    private final Map<String, List<BlockPos>> anchors;

    /**
     * @param name         the template, for messages
     * @param structureSet the structure set's short id, e.g. {@code villages}
     * @param spacing      the set's spacing in chunks
     * @param separation   the set's separation in chunks
     * @param blocks       the blocks to compare, template-local
     * @param references   template-local points that the game puts on the start chunk's north-west corner: the
     *                     template origin, a named jigsaw block, or (for a piece found through the start piece it is
     *                     attached to) the start piece's origin in this piece's coordinates
     */
    public StartPiece(String name, String structureSet, int spacing, int separation, List<Block> blocks, List<BlockPos> references) {
        if (spacing <= separation || separation < 0) {
            throw new IllegalArgumentException("spacing (" + spacing + ") must be larger than separation (" + separation + ")");
        }
        if (references.isEmpty()) {
            throw new IllegalArgumentException(name + " has no reference point");
        }
        this.name = name;
        this.structureSet = structureSet;
        this.spacing = spacing;
        this.separation = separation;
        this.references = List.copyOf(references);

        // Compare in a scattered order, so a wrong placement runs out of allowed misses quickly.
        int count = blocks.size();
        int step = count % 7919 == 0 ? 1 : 7919;
        xs = new int[count];
        ys = new int[count];
        zs = new int[count];
        ids = new String[count];
        built = new boolean[count];
        int builtBlocks = 0;
        for (int i = 0; i < count; i++) {
            Block block = blocks.get((int) ((long) i * step % count));
            xs[i] = block.x();
            ys[i] = block.y();
            zs[i] = block.z();
            ids[i] = BlockKinds.canonical(block.id());
            built[i] = !BlockKinds.TERRAIN.contains(ids[i]);
            if (built[i]) {
                builtBlocks++;
            }
        }
        builtCount = builtBlocks;
        anchors = chooseAnchors();
    }

    /**
     * The blocks searched for in loaded chunks. A bell or reinforced deepslate alone when the piece has one (only
     * village centres and ancient city centres do); otherwise up to three of the piece's rarest built blocks that
     * don't also generate in nature.
     */
    private Map<String, List<BlockPos>> chooseAnchors() {
        Map<String, List<BlockPos>> positions = new LinkedHashMap<>();
        for (int i = 0; i < ids.length; i++) {
            if (built[i]) {
                positions.computeIfAbsent(ids[i], id -> new ArrayList<>()).add(new BlockPos(xs[i], ys[i], zs[i]));
            }
        }
        for (String unique : List.of("minecraft:bell", "minecraft:reinforced_deepslate")) {
            if (positions.containsKey(unique)) {
                return Map.of(unique, List.copyOf(positions.get(unique)));
            }
        }
        Map<String, List<BlockPos>> chosen = new LinkedHashMap<>();
        positions.entrySet().stream()
            .filter(entry -> !BlockKinds.NOT_ANCHORS.contains(entry.getKey()))
            .sorted(Comparator.comparingInt((Map.Entry<String, List<BlockPos>> entry) -> entry.getValue().size()).thenComparing(Map.Entry::getKey))
            .limit(3)
            .forEach(entry -> chosen.put(entry.getKey(), List.copyOf(entry.getValue())));
        return chosen;
    }

    public String name() {
        return name;
    }

    public String structureSet() {
        return structureSet;
    }

    public int spacing() {
        return spacing;
    }

    public int separation() {
        return separation;
    }

    public List<BlockPos> references() {
        return references;
    }

    public int blockCount() {
        return ids.length;
    }

    public int builtCount() {
        return builtCount;
    }

    /** The anchor block ids and where they are in the piece; empty when the piece has nothing to search for. */
    public Map<String, List<BlockPos>> anchors() {
        return anchors;
    }

    /** The anchor block ids. */
    public Set<String> anchorBlocks() {
        return anchors.keySet();
    }

    /**
     * Whether the structure set can start in this chunk at all: in each region of {@code spacing} x {@code spacing}
     * chunks, starts are never in the last {@code separation} rows and columns. This holds for every seed.
     */
    public boolean couldStartIn(int chunkX, int chunkZ) {
        int limit = spacing - separation;
        return Math.floorMod(chunkX, spacing) < limit && Math.floorMod(chunkZ, spacing) < limit;
    }

    int x(int index) {
        return xs[index];
    }

    int y(int index) {
        return ys[index];
    }

    int z(int index) {
        return zs[index];
    }

    String id(int index) {
        return ids[index];
    }

    boolean built(int index) {
        return built[index];
    }

    @Override
    public String toString() {
        return name + " (" + structureSet + ", " + ids.length + " blocks, " + builtCount + " built, anchors " + anchors.keySet() + ")";
    }
}
