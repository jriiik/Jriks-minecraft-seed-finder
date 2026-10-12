package io.github.jriiik.seedfinder.structure;

/**
 * Keeps a structure away from another structure set: no start is allowed if the other set has a start chunk
 * within {@code chunkCount} chunks (for example, pillager outposts stay 10 chunks away from villages).
 */
public record ExclusionZone(String otherSet, int chunkCount) {
}
