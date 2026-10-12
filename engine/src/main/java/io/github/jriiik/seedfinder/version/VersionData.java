package io.github.jriiik.seedfinder.version;

import io.github.jriiik.seedfinder.bedrock.BedrockDimension;
import io.github.jriiik.seedfinder.structure.StructureInfo;
import io.github.jriiik.seedfinder.structure.StructureSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Everything the engine knows about how one Minecraft version generates its worlds. */
public final class VersionData {

    private final String version;
    private final Map<String, StructureSet> structureSets;
    private final Map<String, StructureInfo> structures;
    private final Map<String, Set<String>> dimensionBiomes;
    private final Map<String, BedrockDimension> bedrock;

    /**
     * @param dimensionBiomes for each dimension id (e.g. {@code minecraft:the_nether}), the biomes it can contain
     * @param bedrock         for each dimension id, how it places its random bedrock
     */
    public VersionData(
        String version, Map<String, StructureSet> structureSets, Map<String, StructureInfo> structures,
        Map<String, Set<String>> dimensionBiomes, Map<String, BedrockDimension> bedrock
    ) {
        this.version = version;
        this.structureSets = Collections.unmodifiableMap(new LinkedHashMap<>(structureSets));
        this.structures = Collections.unmodifiableMap(new LinkedHashMap<>(structures));
        Map<String, Set<String>> dimensions = new LinkedHashMap<>();
        dimensionBiomes.forEach((dimension, biomes) -> dimensions.put(dimension, Set.copyOf(biomes)));
        this.dimensionBiomes = Collections.unmodifiableMap(dimensions);
        this.bedrock = Collections.unmodifiableMap(new LinkedHashMap<>(bedrock));
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

    /**
     * Finds the structure set for a name people are likely to type: a set id ({@code villages}), the id of one
     * structure in it ({@code village_desert}, {@code desert_pyramid}) or a singular form ({@code village},
     * {@code ancient_city}). The {@code minecraft:} prefix is optional and case does not matter.
     */
    public Optional<StructureSet> findStructureSet(String name) {
        String id = VersionDataLoader.normalizeId(name.trim().toLowerCase(Locale.ROOT));
        List<String> candidates = new ArrayList<>(List.of(id, id + "s"));
        if (id.endsWith("y")) {
            candidates.add(id.substring(0, id.length() - 1) + "ies");
        }
        for (String candidate : candidates) {
            if (structureSets.containsKey(candidate)) {
                return Optional.of(structureSets.get(candidate));
            }
        }
        return structureSets.values().stream()
            .filter(set -> set.structures().stream().anyMatch(entry -> entry.structure().equals(id)))
            .findFirst();
    }

    public Optional<StructureInfo> structure(String id) {
        return Optional.ofNullable(structures.get(VersionDataLoader.normalizeId(id)));
    }

    /** How each dimension places its random bedrock, by dimension id. */
    public Map<String, BedrockDimension> bedrock() {
        return bedrock;
    }

    /**
     * Finds a dimension's bedrock rules by id or by a short name people type: {@code overworld}, {@code nether} or
     * {@code end} (with or without {@code the_} and the {@code minecraft:} prefix).
     */
    public Optional<BedrockDimension> bedrockDimension(String name) {
        String id = VersionDataLoader.normalizeId(name.trim().toLowerCase(Locale.ROOT));
        if (bedrock.containsKey(id)) {
            return Optional.of(bedrock.get(id));
        }
        String path = id.substring(id.indexOf(':') + 1);
        return Optional.ofNullable(bedrock.get(id.substring(0, id.indexOf(':') + 1) + "the_" + path));
    }
}
