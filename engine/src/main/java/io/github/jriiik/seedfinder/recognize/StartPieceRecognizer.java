package io.github.jriiik.seedfinder.recognize;

import io.github.jriiik.seedfinder.world.BlockPos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds the start chunk of jigsaw structures (villages, outposts, trial chambers, ancient cities, trail ruins,
 * camps) from their blocks.
 *
 * <p>How the game places a start piece: it picks the piece and a random rotation, puts the piece's reference point
 * (usually the template's origin) on the start chunk's north-west corner, turns the piece around that point, and
 * later only moves it up or down. So: find a block of the piece (an anchor), try each place in the blueprint where
 * that block occurs and each rotation, and keep a placement only when
 * <ol>
 * <li>its reference point lands on a chunk corner (x and z multiples of 16),</li>
 * <li>that chunk is one the structure set can start in at all ({@link StartPiece#couldStartIn}), and</li>
 * <li>at least {@link #MIN_SCORE} of the blueprint's blocks are really there, and of its built blocks alone, and of
 * its built blocks other than the most common one (so that a player's tent roof of wool stairs on grass is not
 * taken for an abandoned camp).</li>
 * </ol>
 * The corner check is done before anything else: for each anchor block, only the blueprint positions and
 * rotations that put the reference point on a chunk corner are tried (about one in 256), looked up by the anchor's
 * position within its chunk.
 */
public final class StartPieceRecognizer {

    /**
     * Both scores must reach this. Measured with the game's own generation code: true start pieces score at least
     * 0.73 on built blocks once their chunks are loaded; wrong placements that pass the corner check reached 0.635
     * overall and 0.455 on built blocks. At 0.6 overall alone, wrong camps were reported.
     */
    public static final double MIN_SCORE = 0.70;

    /**
     * One possible placement of a start piece.
     *
     * @param x, y, z  where the template's origin is in the world
     * @param chunkX, chunkZ the start chunk this placement means
     */
    public record Candidate(StartPiece piece, PieceRotation rotation, int x, int y, int z, int chunkX, int chunkZ) {
    }

    public enum Verdict {
        /** The piece is there: the start chunk is known. */
        MATCH,
        /** The piece isn't there. */
        NO_MATCH,
        /** Some chunk under the piece is not loaded yet; check again later. */
        NOT_LOADED,
    }

    /**
     * The outcome of checking a candidate, with how many of the blueprint's blocks were found: all, built, and
     * built other than the most common built block.
     */
    public record Check(Verdict verdict, int matched, int builtMatched, int distinctMatched) {
    }

    /**
     * One way an anchor block can belong to a piece: a blueprint position of that block and a rotation, as offsets
     * from the anchor block to the template origin and to the reference point (the start chunk's corner).
     */
    private record Placement(StartPiece piece, PieceRotation rotation, int originX, int originY, int originZ, int cornerX, int cornerZ) {
    }

    private final List<StartPiece> pieces;
    /** For each anchor block, the placements by the anchor's position within its chunk ((x & 15) * 16 + (z & 15)). */
    private final Map<String, List<List<Placement>>> byAnchor = new LinkedHashMap<>();

    public StartPieceRecognizer(List<StartPiece> pieces) {
        this.pieces = List.copyOf(pieces);
        for (StartPiece piece : this.pieces) {
            piece.anchors().forEach((block, positions) -> {
                List<List<Placement>> table = byAnchor.computeIfAbsent(block, id -> {
                    List<List<Placement>> empty = new ArrayList<>(256);
                    for (int i = 0; i < 256; i++) {
                        empty.add(new ArrayList<>());
                    }
                    return empty;
                });
                for (BlockPos at : positions) {
                    for (PieceRotation rotation : PieceRotation.values()) {
                        int originX = -rotation.x(at.x(), at.z());
                        int originZ = -rotation.z(at.x(), at.z());
                        for (BlockPos reference : piece.references()) {
                            int cornerX = originX + rotation.x(reference.x(), reference.z());
                            int cornerZ = originZ + rotation.z(reference.x(), reference.z());
                            // The anchor's x within its chunk must make anchor + cornerX a multiple of 16.
                            table.get(((-cornerX) & 15) * 16 + ((-cornerZ) & 15))
                                .add(new Placement(piece, rotation, originX, -at.y(), originZ, cornerX, cornerZ));
                        }
                    }
                }
            });
        }
    }

    public List<StartPiece> pieces() {
        return pieces;
    }

    /** The block ids worth looking for in loaded chunks. */
    public Set<String> anchorBlocks() {
        return byAnchor.keySet();
    }

    /** The placements an anchor block at (x, y, z) could belong to, after the corner and region checks. */
    public List<Candidate> candidates(String block, int x, int y, int z) {
        List<List<Placement>> table = byAnchor.get(block);
        if (table == null) {
            return List.of();
        }
        List<Candidate> candidates = new ArrayList<>();
        for (Placement placement : table.get((x & 15) * 16 + (z & 15))) {
            int chunkX = (x + placement.cornerX()) >> 4;
            int chunkZ = (z + placement.cornerZ()) >> 4;
            if (placement.piece().couldStartIn(chunkX, chunkZ)) {
                candidates.add(new Candidate(placement.piece(), placement.rotation(), x + placement.originX(), y + placement.originY(),
                    z + placement.originZ(), chunkX, chunkZ));
            }
        }
        return candidates;
    }

    /** Compares the candidate's blueprint with the world; only when every chunk under it is loaded. */
    public Check check(Candidate candidate, BlockView view) {
        StartPiece piece = candidate.piece();
        PieceRotation rotation = candidate.rotation();
        int count = piece.blockCount();
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            int x = candidate.x() + rotation.x(piece.x(i), piece.z(i));
            int z = candidate.z() + rotation.z(piece.x(i), piece.z(i));
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                if (!view.isLoaded(chunkX, chunkZ)) {
                    return new Check(Verdict.NOT_LOADED, 0, 0, 0);
                }
            }
        }

        int allowedMisses = count - required(count);
        int allowedBuiltMisses = piece.builtCount() - required(piece.builtCount());
        int allowedDistinctMisses = piece.distinctCount() - required(piece.distinctCount());
        int matched = 0;
        int builtMatched = 0;
        int distinctMatched = 0;
        int misses = 0;
        int builtMisses = 0;
        int distinctMisses = 0;
        for (int i = 0; i < count; i++) {
            String found = view.blockAt(candidate.x() + rotation.x(piece.x(i), piece.z(i)), candidate.y() + piece.y(i),
                candidate.z() + rotation.z(piece.x(i), piece.z(i)));
            boolean same = found != null && BlockKinds.canonical(found).equals(piece.id(i));
            if (same) {
                matched++;
                if (piece.built(i)) {
                    builtMatched++;
                }
                if (piece.distinct(i)) {
                    distinctMatched++;
                }
            } else if (++misses > allowedMisses
                || piece.built(i) && ++builtMisses > allowedBuiltMisses
                || piece.distinct(i) && ++distinctMisses > allowedDistinctMisses) {
                return new Check(Verdict.NO_MATCH, matched, builtMatched, distinctMatched);
            }
        }
        return new Check(Verdict.MATCH, matched, builtMatched, distinctMatched);
    }

    /** The smallest number of blocks that is at least {@link #MIN_SCORE} of {@code count}. */
    static int required(int count) {
        return (int) Math.ceil(MIN_SCORE * count - 1e-9);
    }
}
