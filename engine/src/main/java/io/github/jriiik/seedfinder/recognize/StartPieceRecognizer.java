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
 * <li>at least {@link #MIN_SCORE} of the blueprint's blocks, and of its built blocks alone, are really there.</li>
 * </ol>
 * The first two checks are cheap and throw away all but about one placement in 400, so the block comparison
 * (the expensive part) only runs for a few.
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

    /** The outcome of checking a candidate, with how many of the blueprint's blocks (and built blocks) were found. */
    public record Check(Verdict verdict, int matched, int builtMatched) {
    }

    private record AnchorUse(StartPiece piece, BlockPos at) {
    }

    private final List<StartPiece> pieces;
    private final Map<String, List<AnchorUse>> byAnchor = new LinkedHashMap<>();

    public StartPieceRecognizer(List<StartPiece> pieces) {
        this.pieces = List.copyOf(pieces);
        for (StartPiece piece : this.pieces) {
            piece.anchors().forEach((block, positions) -> positions.forEach(at ->
                byAnchor.computeIfAbsent(block, id -> new ArrayList<>()).add(new AnchorUse(piece, at))));
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
        List<AnchorUse> uses = byAnchor.get(block);
        if (uses == null) {
            return List.of();
        }
        List<Candidate> candidates = new ArrayList<>();
        for (AnchorUse use : uses) {
            for (PieceRotation rotation : PieceRotation.values()) {
                int originX = x - rotation.x(use.at().x(), use.at().z());
                int originY = y - use.at().y();
                int originZ = z - rotation.z(use.at().x(), use.at().z());
                for (BlockPos reference : use.piece().references()) {
                    int cornerX = originX + rotation.x(reference.x(), reference.z());
                    int cornerZ = originZ + rotation.z(reference.x(), reference.z());
                    if ((cornerX & 15) == 0 && (cornerZ & 15) == 0 && use.piece().couldStartIn(cornerX >> 4, cornerZ >> 4)) {
                        candidates.add(new Candidate(use.piece(), rotation, originX, originY, originZ, cornerX >> 4, cornerZ >> 4));
                    }
                }
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
                    return new Check(Verdict.NOT_LOADED, 0, 0);
                }
            }
        }

        int needed = required(count);
        int builtNeeded = required(piece.builtCount());
        int allowedMisses = count - needed;
        int allowedBuiltMisses = piece.builtCount() - builtNeeded;
        int matched = 0;
        int builtMatched = 0;
        int misses = 0;
        int builtMisses = 0;
        for (int i = 0; i < count; i++) {
            String found = view.blockAt(candidate.x() + rotation.x(piece.x(i), piece.z(i)), candidate.y() + piece.y(i),
                candidate.z() + rotation.z(piece.x(i), piece.z(i)));
            boolean same = found != null && BlockKinds.canonical(found).equals(piece.id(i));
            if (same) {
                matched++;
                if (piece.built(i)) {
                    builtMatched++;
                }
            } else {
                if (++misses > allowedMisses) {
                    return new Check(Verdict.NO_MATCH, matched, builtMatched);
                }
                if (piece.built(i) && ++builtMisses > allowedBuiltMisses) {
                    return new Check(Verdict.NO_MATCH, matched, builtMatched);
                }
            }
        }
        return new Check(Verdict.MATCH, matched, builtMatched);
    }

    /** The smallest number of blocks that is at least {@link #MIN_SCORE} of {@code count}. */
    static int required(int count) {
        return (int) Math.ceil(MIN_SCORE * count - 1e-9);
    }
}
