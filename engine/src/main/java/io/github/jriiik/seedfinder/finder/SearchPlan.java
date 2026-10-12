package io.github.jriiik.seedfinder.finder;

import java.util.List;

/**
 * What a search for these observations will look like, worked out before searching.
 *
 * @param observations the usable observations (duplicates removed)
 * @param informationBits how much the observations reveal about the seed; 48 bits are needed for one answer
 * @param expectedFalseMatches how many wrong structure seeds are expected to fit by pure chance
 * @param liftedBits how many low bits of the seed were pinned down before the main search
 * @param lowCandidates how many combinations of those low bits are still possible
 * @param seedsToTest how many full structure seeds the main search will test
 * @param suggestedStructures structure types that would best tell several fitting seeds apart
 * @param problems reasons the search can't run (empty if it can)
 * @param warnings things the user should know, but which don't block the search
 */
public record SearchPlan(
    List<StructureObservation> observations,
    double informationBits,
    double expectedFalseMatches,
    int liftedBits,
    long lowCandidates,
    long seedsToTest,
    List<String> suggestedStructures,
    List<String> problems,
    List<String> warnings
) {

    public SearchPlan {
        observations = List.copyOf(observations);
        suggestedStructures = List.copyOf(suggestedStructures);
        problems = List.copyOf(problems);
        warnings = List.copyOf(warnings);
    }

    public boolean canSearch() {
        return problems.isEmpty();
    }
}
