package io.github.jriiik.seedfinder.version;

import io.github.jriiik.seedfinder.structure.StructureInfo;
import io.github.jriiik.seedfinder.structure.StructureSet;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Everything the engine knows about how one Minecraft version generates its worlds. */
public final class VersionData {

    private final String version;
    private final Map<String, StructureSet> structureSets;
    private final Map<String, StructureInfo> structures;
    private final Map<String, Set<String>> dimensionBiomes;

    /**
     * @param dimensionBiomes for each dimension id (e.g. {@code minecraft:the_nether}), the biomes it can contain
     */
    public VersionData(
        String version, Map<String, StructureSet> structureSets, Map<String, StructureInfo> structures,
        Map<String, Set<String>> dimensionBiomes
    ) {
        this.version = version;
        this.structureSets = Collections.unmodifiableMap(new LinkedHashMap<>(structureSets));
        this.structures = Collections.unmodifiableMap(new LinkedHashMap<>(structures));
        Map<String, Set<String>> dimensions = new LinkedHashMap<>();
        dimensionBiomes.forEach((dimension, biomes) -> dimensions.put(dimension, Set.copyOf(biomes)));
        this.dimensionBiomes = Collections.unmodifiableMap(dimensions);
    }

    /**
     * The dimensions in which this set's structures can appear: those containing at least one biome that one of
     * its structures may start in (the same rule Minecraft uses). Ruined portals, for example, appear in two.
     */
    public List<String> dimensionsOf(StructureSet set) {
        return dimensionBiomes.entrySet().stream()
            .filter(dimension -> set.structures().stream()
                .map(entry -> structures.get(entry.structure()))
                .anyMatch(info -> info != null && info.biomes().stream().anyMatch(dimension.getValue()::contains)))
            .map(Map.Entry::getKey)
            .toList();
    }

    public String version() {
        return version;
    }

    public Collection<StructureSet> structureSets() {
        return structureSets.values();
    }

    /** Finds a structure set by id; the {@code minecraft:} prefix may be left out. */
    public Optional<StructureSet> structureSet(String id) {
        return Optional.ofNullable(structureSets.get(VersionDataLoader.normalizeId(id)));
    }

    public Optional<StructureInfo> structure(String id) {
        return Optional.ofNullable(structures.get(VersionDataLoader.normalizeId(id)));
    }
}
