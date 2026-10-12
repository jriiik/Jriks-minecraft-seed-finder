package io.github.jriiik.seedfinder.version;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.jriiik.seedfinder.bedrock.BedrockDimension;
import io.github.jriiik.seedfinder.bedrock.BedrockRule;
import io.github.jriiik.seedfinder.structure.ConcentricRingsPlacement;
import io.github.jriiik.seedfinder.structure.DimensionOriginPlacement;
import io.github.jriiik.seedfinder.structure.ExclusionZone;
import io.github.jriiik.seedfinder.structure.FrequencyReduction;
import io.github.jriiik.seedfinder.structure.RandomSpreadPlacement;
import io.github.jriiik.seedfinder.structure.SpreadType;
import io.github.jriiik.seedfinder.structure.StructureInfo;
import io.github.jriiik.seedfinder.structure.StructurePlacement;
import io.github.jriiik.seedfinder.structure.StructureSet;
import io.github.jriiik.seedfinder.world.BlockPos;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the per-version data files written by the oracle tool. Structure sets use the same JSON format as
 * Minecraft datapacks, with the same default values, so datapack files can be read the same way.
 */
public final class VersionDataLoader {

    private VersionDataLoader() {
    }

    static VersionData loadBundled(String version) {
        String base = "/io/github/jriiik/seedfinder/versions/" + version + "/";
        JsonObject structureSets = readResource(base + "structure_sets.json").getAsJsonObject("structure_sets");
        JsonObject structures = readResource(base + "structures.json").getAsJsonObject("structures");
        JsonObject dimensions = readResource(base + "dimensions.json").getAsJsonObject("dimensions");
        JsonObject bedrock = readResource(base + "bedrock.json").getAsJsonObject("dimensions");
        return new VersionData(version, parseStructureSets(structureSets), parseStructures(structures), parseDimensions(dimensions),
            parseBedrock(bedrock));
    }

    static Map<String, BedrockDimension> parseBedrock(JsonObject byId) {
        Map<String, BedrockDimension> dimensions = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : byId.entrySet()) {
            String id = normalizeId(entry.getKey());
            JsonObject json = entry.getValue().getAsJsonObject();
            List<BedrockRule> rules = new ArrayList<>();
            for (JsonElement element : json.getAsJsonArray("bedrock_rules")) {
                JsonObject rule = element.getAsJsonObject();
                rules.add(new BedrockRule(normalizeId(rule.get("random_name").getAsString()), rule.get("true_at_and_below").getAsInt(),
                    rule.get("false_at_and_above").getAsInt(), rule.get("inverted").getAsBoolean()));
            }
            dimensions.put(id, new BedrockDimension(id, json.get("legacy_random_source").getAsBoolean(), json.get("min_y").getAsInt(),
                json.get("height").getAsInt(), rules));
        }
        return dimensions;
    }

    static Map<String, Set<String>> parseDimensions(JsonObject byId) {
        Map<String, Set<String>> dimensions = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : byId.entrySet()) {
            dimensions.put(normalizeId(entry.getKey()), new LinkedHashSet<>(parseBiomeList(entry.getValue().getAsJsonObject().get("biomes"))));
        }
        return dimensions;
    }

    /** Parses a map of structure set id to datapack-style structure set JSON. */
    public static Map<String, StructureSet> parseStructureSets(JsonObject byId) {
        Map<String, StructureSet> sets = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : byId.entrySet()) {
            String id = normalizeId(entry.getKey());
            try {
                sets.put(id, parseStructureSet(id, entry.getValue().getAsJsonObject()));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Invalid structure set " + id + ": " + e.getMessage(), e);
            }
        }
        return sets;
    }

    /** Parses one structure set in datapack format. */
    public static StructureSet parseStructureSet(String id, JsonObject json) {
        List<StructureSet.Entry> entries = new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray("structures")) {
            JsonObject entry = element.getAsJsonObject();
            entries.add(new StructureSet.Entry(normalizeId(entry.get("structure").getAsString()), entry.get("weight").getAsInt()));
        }
        return new StructureSet(id, entries, parsePlacement(json.getAsJsonObject("placement")));
    }

    static StructurePlacement parsePlacement(JsonObject json) {
        String type = normalizeId(json.get("type").getAsString());
        BlockPos locateOffset = json.has("locate_offset") ? parseOffset(json.getAsJsonArray("locate_offset")) : BlockPos.ZERO;
        return switch (type) {
            case RandomSpreadPlacement.TYPE -> new RandomSpreadPlacement(
                json.get("spacing").getAsInt(),
                json.get("separation").getAsInt(),
                json.has("spread_type") ? SpreadType.byId(json.get("spread_type").getAsString()) : SpreadType.LINEAR,
                json.get("salt").getAsInt(),
                json.has("frequency") ? json.get("frequency").getAsFloat() : 1.0f,
                json.has("frequency_reduction_method")
                    ? FrequencyReduction.byId(json.get("frequency_reduction_method").getAsString())
                    : FrequencyReduction.DEFAULT,
                parseExclusionZone(json),
                locateOffset);
            case ConcentricRingsPlacement.TYPE -> new ConcentricRingsPlacement(
                json.get("distance").getAsInt(),
                json.get("spread").getAsInt(),
                json.get("count").getAsInt(),
                parseBiomeList(json.get("preferred_biomes")),
                json.get("salt").getAsInt(),
                locateOffset);
            case DimensionOriginPlacement.TYPE -> new DimensionOriginPlacement();
            default -> throw new IllegalArgumentException("Unsupported placement type " + type);
        };
    }

    static Optional<ExclusionZone> parseExclusionZone(JsonObject placement) {
        if (!placement.has("exclusion_zone")) {
            return Optional.empty();
        }
        JsonObject zone = placement.getAsJsonObject("exclusion_zone");
        JsonElement otherSet = zone.get("other_set");
        if (!otherSet.isJsonPrimitive()) {
            throw new IllegalArgumentException("exclusion_zone.other_set must name a structure set");
        }
        return Optional.of(new ExclusionZone(normalizeId(otherSet.getAsString()), zone.get("chunk_count").getAsInt()));
    }

    static BlockPos parseOffset(JsonArray xyz) {
        return new BlockPos(xyz.get(0).getAsInt(), xyz.get(1).getAsInt(), xyz.get(2).getAsInt());
    }

    /** A biome list in datapack format: one id, one tag ("#..."), or an array of ids. */
    static List<String> parseBiomeList(JsonElement json) {
        List<String> biomes = new ArrayList<>();
        if (json.isJsonArray()) {
            json.getAsJsonArray().forEach(biome -> biomes.add(normalizeId(biome.getAsString())));
        } else {
            biomes.add(normalizeId(json.getAsString()));
        }
        return biomes;
    }

    static Map<String, StructureInfo> parseStructures(JsonObject byId) {
        Map<String, StructureInfo> structures = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : byId.entrySet()) {
            JsonObject json = entry.getValue().getAsJsonObject();
            String id = normalizeId(entry.getKey());
            structures.put(id, new StructureInfo(id, json.get("step").getAsString(), parseBiomeList(json.get("biomes"))));
        }
        return structures;
    }

    /** Adds the {@code minecraft:} namespace when it is missing, like Minecraft does ("#tag" stays a tag). */
    static String normalizeId(String id) {
        boolean tag = id.startsWith("#");
        String bare = tag ? id.substring(1) : id;
        String namespaced = bare.contains(":") ? bare : "minecraft:" + bare;
        return tag ? "#" + namespaced : namespaced;
    }

    private static JsonObject readResource(String path) {
        try (InputStream in = VersionDataLoader.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing bundled data file " + path);
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }
}
