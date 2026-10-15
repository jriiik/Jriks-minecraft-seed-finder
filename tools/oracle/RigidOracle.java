import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;

/**
 * Facts for recognizing rigid structures from their blocks: how the game places the main piece of each structure
 * built from templates (igloos, shipwrecks, ocean ruins, ruined portals), and which code-built piece the others use
 * (desert pyramids, jungle temples, swamp huts). Found by running the game's own structure generation in many chunks,
 * every biome allowed. Only names and numbers are written; the templates themselves stay in the game.
 *
 * <p>The main pieces are those whose template origin lies on the start chunk's north-west corner (the cluster ruins
 * around a big ocean ruin and an igloo's basement do not). Sampling goes on until no new placement has turned up for a
 * long while, and must show every template in every rotation and mirror seen in its set; otherwise the oracle fails.
 * Code-built pieces must have their box's corner on the start chunk's corner.
 */
final class RigidOracle {

    static final List<String> TEMPLATE_SETS = List.of("minecraft:igloos", "minecraft:ocean_ruins", "minecraft:ruined_portals", "minecraft:shipwrecks");
    static final List<String> CODE_BUILT_SETS = List.of("minecraft:desert_pyramids", "minecraft:jungle_temples", "minecraft:swamp_huts");
    static final long SEED = 20261013L;
    /** Sampling stops once this many structure starts in a row brought nothing new (and at least MIN_SAMPLES were seen). */
    static final int QUIET_SAMPLES = 6000;
    static final int MIN_SAMPLES = 2000;
    static final int MAX_SAMPLES = 400_000;
    static final int CODE_BUILT_SAMPLES = 400;

    private RigidOracle() {
    }

    /** One way a main piece is placed: the templates stacked at the start chunk's corner, turned and mirrored about a pivot. */
    record Placement(List<String> templates, String rotation, String mirror, BlockPos pivot) {
    }

    static JsonObject rigidStructuresJson(String version, Oracle.LoadedRegistries loaded) throws IOException {
        HolderLookup.Provider registries = loaded.lookup();
        Path storageDir = Files.createDirectories(Path.of("rigid-oracle-storage"));
        try (LevelStorageSource.LevelStorageAccess storage = LevelStorageSource.createDefault(storageDir).createAccess("oracle")) {
            StructureTemplateManager templates = new StructureTemplateManager(loaded.resources(), storage, DataFixers.getDataFixer(),
                registries.lookupOrThrow(Registries.BLOCK).filterFeatures(FeatureFlags.DEFAULT_FLAGS));
            StructurePieceSerializationContext context = new StructurePieceSerializationContext(loaded.resources(), loaded.all(), templates);
            Map<ResourceKey<LevelStem>, LevelStem> dimensions = WorldPresets.createNormalWorldDimensions(registries).dimensions();
            List<Dimension> worlds = new ArrayList<>();
            for (ResourceKey<LevelStem> key : List.of(LevelStem.OVERWORLD, LevelStem.NETHER)) {
                worlds.add(new Dimension(registries, key, dimensions.get(key)));
            }
            HolderLookup.RegistryLookup<StructureSet> sets = registries.lookupOrThrow(Registries.STRUCTURE_SET);

            JsonObject templateSets = new JsonObject();
            for (String id : TEMPLATE_SETS) {
                StructureSet set = sets.getOrThrow(ResourceKey.create(Registries.STRUCTURE_SET, Identifier.parse(id))).value();
                templateSets.add(id, templateSet(id, set, worlds, templates, context, loaded));
            }
            JsonObject codeBuilt = new JsonObject();
            for (String id : CODE_BUILT_SETS) {
                StructureSet set = sets.getOrThrow(ResourceKey.create(Registries.STRUCTURE_SET, Identifier.parse(id))).value();
                codeBuilt.add(id, codeBuiltSet(id, set, worlds, templates, loaded));
            }
            JsonObject root = Oracle.header(version);
            root.addProperty("format", "template sets: every template list appears with every rotation and mirror listed; pivots are template-local;"
                + " the templates' origin is the start chunk's north-west corner. code-built sets: the piece types, whose bounding box starts"
                + " at the start chunk's north-west corner");
            root.add("template_structure_sets", templateSets);
            root.add("code_built_structure_sets", codeBuilt);
            return root;
        }
    }

    /** A dimension's generator, ready to try structure starts. */
    record Dimension(ResourceKey<Level> level, NoiseBasedChunkGenerator generator, RandomState randomState, LevelHeightAccessor height,
                     Set<ResourceKey<Biome>> biomes) {

        Dimension(HolderLookup.Provider registries, ResourceKey<LevelStem> key, LevelStem stem) {
            this(key == LevelStem.NETHER ? Level.NETHER : Level.OVERWORLD, (NoiseBasedChunkGenerator) stem.generator(),
                RandomState.create(registries.lookupOrThrow(Registries.NOISE), SEED, ((NoiseBasedChunkGenerator) stem.generator()).generatorSettings().value()),
                height(stem.type().value()), keys(stem.generator().getBiomeSource().possibleBiomes()));
        }

        private static Set<ResourceKey<Biome>> keys(Set<Holder<Biome>> biomes) {
            Set<ResourceKey<Biome>> keys = new java.util.HashSet<>();
            biomes.forEach(biome -> keys.add(biome.unwrapKey().orElseThrow()));
            return keys;
        }

        private static LevelHeightAccessor height(DimensionType type) {
            return LevelHeightAccessor.create(type.minY(), type.height());
        }

        /** Whether the structure can appear in this dimension: one of its biomes is here (the game's own rule). */
        boolean hosts(Structure structure) {
            return structure.biomes().stream().anyMatch(biome -> biomes.contains(biome.unwrapKey().orElseThrow()));
        }

        StructureStart start(Holder<Structure> structure, Oracle.LoadedRegistries loaded, StructureTemplateManager templates, ChunkPos chunk) {
            // As ChunkGenerator.createStructures does for each chunk.
            return structure.value().generate(structure, level, loaded.all(), generator, generator.getBiomeSource(),
                randomState.createClimateSampler(SamplerContext.builder().enableCaches().build()), randomState, templates, SEED, chunk, 0, height,
                biome -> true);
        }
    }

    private static JsonObject templateSet(String id, StructureSet set, List<Dimension> worlds, StructureTemplateManager templates,
                                          StructurePieceSerializationContext context, Oracle.LoadedRegistries loaded) {
        List<Map.Entry<Holder<Structure>, Dimension>> sources = sources(set, worlds);
        Random random = new Random(SEED ^ id.hashCode());
        Set<Placement> seen = new java.util.HashSet<>();
        int samples = 0;
        int quiet = 0;
        int attempts = 0;
        while (samples < MIN_SAMPLES || quiet < QUIET_SAMPLES) {
            if (samples >= MAX_SAMPLES || ++attempts > 4 * MAX_SAMPLES) {
                throw new IllegalStateException(id + ": still finding new placements after " + samples + " starts");
            }
            Map.Entry<Holder<Structure>, Dimension> source = sources.get(samples % sources.size());
            ChunkPos chunk = new ChunkPos(random.nextInt(20_001) - 10_000, random.nextInt(20_001) - 10_000);
            StructureStart start = source.getValue().start(source.getKey(), loaded, templates, chunk);
            if (!start.isValid()) {
                continue;
            }
            samples++;
            List<TemplateStructurePiece> main = new ArrayList<>();
            for (StructurePiece piece : start.getPieces()) {
                if (piece instanceof TemplateStructurePiece template && template.templatePosition().getX() == chunk.getMinBlockX()
                    && template.templatePosition().getZ() == chunk.getMinBlockZ()) {
                    main.add(template);
                }
            }
            if (main.isEmpty()) {
                throw new IllegalStateException(id + ": no template piece at the corner of start chunk " + chunk);
            }
            TemplateStructurePiece first = main.get(0);
            List<String> names = new ArrayList<>();
            for (TemplateStructurePiece piece : main) {
                if (piece.placeSettings().getRotation() != first.placeSettings().getRotation() || piece.placeSettings().getMirror() != first.placeSettings().getMirror()
                    || !piece.placeSettings().getRotationPivot().equals(first.placeSettings().getRotationPivot())) {
                    throw new IllegalStateException(id + ": stacked pieces placed differently in " + chunk);
                }
                names.add(piece.createTag(context).getStringOr("Template", ""));
            }
            Placement placement = new Placement(List.copyOf(names), first.placeSettings().getRotation().getSerializedName(),
                first.placeSettings().getMirror().getSerializedName(), first.placeSettings().getRotationPivot().immutable());
            if (seen.add(placement)) {
                quiet = 0;
            } else {
                quiet++;
            }
        }

        // Every template list must have shown up in every rotation and mirror of the set, always with one pivot.
        Set<String> rotations = new TreeSet<>();
        Set<String> mirrors = new TreeSet<>();
        Map<String, BlockPos> pivots = new TreeMap<>();
        Map<String, List<String>> lists = new TreeMap<>();
        for (Placement placement : seen) {
            rotations.add(placement.rotation());
            mirrors.add(placement.mirror());
            String key = String.join(" ", placement.templates());
            lists.put(key, placement.templates());
            BlockPos known = pivots.putIfAbsent(key, placement.pivot());
            if (known != null && !known.equals(placement.pivot())) {
                throw new IllegalStateException(id + ": " + key + " turns about " + known + " and " + placement.pivot());
            }
        }
        for (String key : lists.keySet()) {
            for (String rotation : rotations) {
                for (String mirror : mirrors) {
                    if (!seen.contains(new Placement(lists.get(key), rotation, mirror, pivots.get(key)))) {
                        throw new IllegalStateException(id + ": " + key + " never seen with rotation " + rotation + " and mirror " + mirror
                            + " in " + samples + " starts; sample longer");
                    }
                }
            }
        }
        System.out.printf("%s: %d template lists, rotations %s, mirrors %s, from %d starts%n", id, lists.size(), rotations, mirrors, samples);

        JsonObject json = new JsonObject();
        JsonArray rotationArray = new JsonArray();
        rotations.forEach(rotationArray::add);
        JsonArray mirrorArray = new JsonArray();
        mirrors.forEach(mirrorArray::add);
        json.add("rotations", rotationArray);
        json.add("mirrors", mirrorArray);
        JsonArray pieces = new JsonArray();
        for (String key : lists.keySet()) {
            JsonObject piece = new JsonObject();
            JsonArray names = new JsonArray();
            lists.get(key).forEach(names::add);
            piece.add("templates", names);
            BlockPos pivot = pivots.get(key);
            piece.add("pivot", Oracle.ints(pivot.getX(), pivot.getY(), pivot.getZ()));
            pieces.add(piece);
        }
        json.add("pieces", pieces);
        return json;
    }

    private static JsonObject codeBuiltSet(String id, StructureSet set, List<Dimension> worlds, StructureTemplateManager templates,
                                           Oracle.LoadedRegistries loaded) {
        List<Map.Entry<Holder<Structure>, Dimension>> sources = sources(set, worlds);
        Random random = new Random(SEED ^ id.hashCode());
        Set<String> types = new TreeSet<>();
        int samples = 0;
        int attempts = 0;
        while (samples < CODE_BUILT_SAMPLES) {
            if (++attempts > 100 * CODE_BUILT_SAMPLES) {
                throw new IllegalStateException(id + ": too few valid starts");
            }
            Map.Entry<Holder<Structure>, Dimension> source = sources.get(samples % sources.size());
            ChunkPos chunk = new ChunkPos(random.nextInt(20_001) - 10_000, random.nextInt(20_001) - 10_000);
            StructureStart start = source.getValue().start(source.getKey(), loaded, templates, chunk);
            if (!start.isValid()) {
                continue;
            }
            samples++;
            if (start.getPieces().size() != 1 || start.getPieces().get(0) instanceof TemplateStructurePiece) {
                throw new IllegalStateException(id + ": expected one code-built piece in " + chunk + ", got " + start.getPieces());
            }
            StructurePiece piece = start.getPieces().get(0);
            if (piece.getBoundingBox().minX() != chunk.getMinBlockX() || piece.getBoundingBox().minZ() != chunk.getMinBlockZ()) {
                throw new IllegalStateException(id + ": piece box " + piece.getBoundingBox() + " does not start at the corner of " + chunk);
            }
            types.add(BuiltInRegistries.STRUCTURE_PIECE.getKey(piece.getType()).toString());
        }
        System.out.printf("%s: piece types %s, from %d starts%n", id, types, samples);
        JsonObject json = new JsonObject();
        JsonArray array = new JsonArray();
        types.forEach(array::add);
        json.add("piece_types", array);
        return json;
    }

    /** The set's structures, each with the dimensions it can appear in. */
    private static List<Map.Entry<Holder<Structure>, Dimension>> sources(StructureSet set, List<Dimension> worlds) {
        List<Map.Entry<Holder<Structure>, Dimension>> sources = new ArrayList<>();
        for (StructureSet.StructureSelectionEntry entry : set.structures()) {
            for (Dimension world : worlds) {
                if (world.hosts(entry.structure().value())) {
                    sources.add(Map.entry(entry.structure(), world));
                }
            }
        }
        if (sources.isEmpty()) {
            throw new IllegalStateException("no dimension hosts " + set);
        }
        return sources;
    }
}
