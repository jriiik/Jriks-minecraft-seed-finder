package io.github.jriiik.seedfinder.recognize;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns matched start pieces into structure starts. Each region of {@code spacing} x {@code spacing} chunks holds at
 * most one start of a structure set, so matches are grouped by set and region: several matches naming the same
 * chunk are one start (for example a zombie village centre and its normal twin), while matches naming different
 * chunks of one region contradict each other, and that region reports nothing.
 */
public final class RecognizedStarts {

    /** A structure start found from blocks. */
    public record Start(String structureSet, int chunkX, int chunkZ) {
    }

    private record Region(String structureSet, int x, int z) {
    }

    private final Map<Region, Set<Start>> regions = new LinkedHashMap<>();

    /** Notes a match; returns whether {@link #starts()} or {@link #conflicting()} changed. */
    public boolean add(StartPieceRecognizer.Candidate match) {
        StartPiece piece = match.piece();
        Region region = new Region(piece.structureSet(), Math.floorDiv(match.chunkX(), piece.spacing()), Math.floorDiv(match.chunkZ(), piece.spacing()));
        return regions.computeIfAbsent(region, key -> new LinkedHashSet<>()).add(new Start(piece.structureSet(), match.chunkX(), match.chunkZ()));
    }

    /** The starts that are certain: the only one found in their region. */
    public List<Start> starts() {
        List<Start> starts = new ArrayList<>();
        regions.values().stream().filter(found -> found.size() == 1).forEach(starts::addAll);
        return starts;
    }

    /** Starts in regions where matches disagree; none of them is reported. */
    public List<Start> conflicting() {
        List<Start> starts = new ArrayList<>();
        regions.values().stream().filter(found -> found.size() > 1).forEach(starts::addAll);
        return starts;
    }
}
