package io.github.jriiik.seedfinder.structure;

import java.util.List;

/**
 * A group of structures that share one placement rule, e.g. {@code minecraft:villages} contains the five
 * village variants; which variant appears depends on the biome.
 */
public record StructureSet(String id, List<Entry> structures, StructurePlacement placement) {

    public StructureSet {
        structures = List.copyOf(structures);
    }

    /** One structure in the set and its weight when the game picks between them. */
    public record Entry(String structure, int weight) {
    }

    /** The id without the {@code minecraft:} prefix, which is nicer to show to people. */
    public String shortId() {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }
}
