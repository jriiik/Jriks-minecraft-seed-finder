package io.github.jriiik.seedfinder.structure;

import java.util.List;

/** Facts about a single structure: its generation step and the biomes it is allowed to start in. */
public record StructureInfo(String id, String step, List<String> biomes) {

    public StructureInfo {
        biomes = List.copyOf(biomes);
    }
}
