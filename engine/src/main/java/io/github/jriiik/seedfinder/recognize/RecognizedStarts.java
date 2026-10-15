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
        return add(piece.structureSet(), piece.spacing(), match.chunkX(), match.chunkZ());
    }

    /**
     * Notes a start found some other way (a rigid structure); returns whether {@link #starts()} or
     * {@link #conflicting()} changed.
     *
     * @param spacing the set's spacing: the size of its placement regions, in chunks
     */
    public boolean add(String structureSet, int spacing, int chunkX, int chunkZ) {
        Region region = new Region(structureSet, Math.floorDiv(chunkX, spacing), Math.floorDiv(chunkZ, spacing));
        return regions.computeIfAbsent(region, key -> new LinkedHashSet<>()).add(new Start(structureSet, chunkX, chunkZ));
    }

    /**
     * Takes back a start that is known not to be one (placed by a command); returns whether {@link #starts()} or
     * {@link #conflicting()} changed.
     */
    public boolean remove(String structureSet, int spacing, int chunkX, int chunkZ) {
        Region region = new Region(structureSet, Math.floorDiv(chunkX, spacing), Math.floorDiv(chunkZ, spacing));
        Set<Start> found = regions.get(region);
        if (found == null || !found.remove(new Start(structureSet, chunkX, chunkZ))) {
            return false;
        }
        if (found.isEmpty()) {
            regions.remove(region);
        }
        return true;
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
