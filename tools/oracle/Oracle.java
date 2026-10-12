import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

/**
 * The "oracle": asks the real Minecraft server code for the answers our own engine must reproduce.
 *
 * <p>It runs against the official server jar (downloaded by run-oracle.sh, never committed) and writes:
 * <ul>
 *   <li>version data: the vanilla structure sets in datapack JSON format, plus the biomes each structure may use;</li>
 *   <li>test fixtures: RNG outputs, structure placement results and stronghold positions for a list of seeds.</li>
 * </ul>
 * Only plain facts (numbers and names) are written; no Minecraft code ends up in the repository.
 *
 * <p>Usage: java -cp server.jar:libraries/* Oracle.java versionDataDir fixturesDir
 */
public final class Oracle {

    /** Seeds used for fixtures: edge cases, a text seed ("Jrik".hashCode()) and a few arbitrary 64-bit values. */
    static final long[] SEEDS = {
        0L, 1L, -1L, 12345L, Long.MIN_VALUE, Long.MAX_VALUE, "Jrik".hashCode(),
        8091867987493326313L, -6984854390176336655L, 3141592653589793238L,
    };

    /** Regions -R..R on both axes are checked for every random-spread structure set. */
    static final int REGION_RADIUS = 3;

    /** A few regions far from spawn, to catch overflow and negative-coordinate mistakes. */
    static final int[][] FAR_REGIONS = {{55_000, -55_000}, {-55_000, 55_000}, {-123_456, -65_432}, {30_000, 30_001}};

    /** For sets with spacing 1 (every chunk is a candidate) we scan chunks -C..C on both axes. */
    static final int CHUNK_RADIUS = 40;

    /** Stronghold rings need biome lookups, which are slow, so only a few seeds get them. */
    static final int STRONGHOLD_SEEDS = 3;

    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: Oracle <versionDataDir> <fixturesDir>");
            System.exit(2);
        }
        Path versionDataDir = Path.of(args[0]);
        Path fixturesDir = Path.of(args[1]);
        Files.createDirectories(versionDataDir);
        Files.createDirectories(fixturesDir);

        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        String version = SharedConstants.getCurrentVersion().name();
        HolderLookup.Provider registries = loadVanillaWorldRegistries();
        HolderLookup.RegistryLookup<StructureSet> structureSets = registries.lookupOrThrow(Registries.STRUCTURE_SET);
        List<Holder.Reference<StructureSet>> sets = structureSets.listElements()
            .sorted(Comparator.comparing(ref -> ref.key().identifier().toString()))
            .toList();

        write(versionDataDir.resolve("structure_sets.json"), structureSetsJson(version, registries, sets));
        write(versionDataDir.resolve("structures.json"), structuresJson(version, sets));
        write(versionDataDir.resolve("dimensions.json"), dimensionsJson(version, registries));
        write(fixturesDir.resolve("rng.json"), rngJson(version));
        write(fixturesDir.resolve("placements.json"), placementsJson(version, registries, structureSets, sets));
        write(fixturesDir.resolve("strongholds.json"), strongholdsJson(version, registries, structureSets, sets));
        System.out.println("Oracle finished for Minecraft " + version);
        System.exit(0); // the game's background executors would otherwise keep the JVM alive
    }

    /**
     * Loads the world-generation registries from the built-in vanilla data pack, including tags such as
     * "#minecraft:has_structure/village_plains", following the same steps as the server's WorldLoader.
     */
    static HolderLookup.Provider loadVanillaWorldRegistries() {
        PackRepository packs = ServerPacksSource.createVanillaTrustedRepository();
        packs.reload();
        packs.setSelected(List.of("vanilla"));
        ResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, packs.openAllSelected());
        LayeredRegistryAccess<RegistryLayer> layers = RegistryLayer.createRegistryAccess();
        List<Registry.PendingTags<?>> staticTags = TagLoader.loadTagsForExistingRegistries(resources, layers.getLayer(RegistryLayer.STATIC));
        List<HolderLookup.RegistryLookup<?>> context = TagLoader.buildUpdatedLookups(layers.getAccessForLoading(RegistryLayer.WORLD), staticTags);
        RegistryAccess.Frozen world = RegistryDataLoader.load(resources, context, RegistryDataLoader.WORLD_REGISTRIES, Runnable::run).join();
        return HolderLookup.Provider.create(Stream.concat(context.stream(), world.listRegistries()));
    }

    /** All structure sets, exactly as a datapack would define them. */
    static JsonObject structureSetsJson(String version, HolderLookup.Provider registries, List<Holder.Reference<StructureSet>> sets) {
        RegistryOps<JsonElement> ops = registries.createSerializationContext(JsonOps.INSTANCE);
        JsonObject bySet = new JsonObject();
        for (Holder.Reference<StructureSet> set : sets) {
            JsonElement json = StructureSet.DIRECT_CODEC.encodeStart(ops, set.value()).getOrThrow();
            bySet.add(set.key().identifier().toString(), json);
        }
        JsonObject root = header(version);
        root.add("structure_sets", bySet);
        return root;
    }

    /** For each structure: its generation step and the biomes it may start in (needed once we check biomes). */
    static JsonObject structuresJson(String version, List<Holder.Reference<StructureSet>> sets) {
        JsonObject byStructure = new JsonObject();
        for (Holder.Reference<StructureSet> set : sets) {
            for (StructureSet.StructureSelectionEntry entry : set.value().structures()) {
                String id = entry.structure().unwrapKey().orElseThrow().identifier().toString();
                JsonObject structure = new JsonObject();
                structure.addProperty("step", entry.structure().value().step().getSerializedName());
                JsonArray biomes = new JsonArray();
                entry.structure().value().biomes().stream()
                    .map(biome -> biome.unwrapKey().orElseThrow().identifier().toString())
                    .sorted()
                    .forEach(biomes::add);
                structure.add("biomes", biomes);
                byStructure.add(id, structure);
            }
        }
        JsonObject root = header(version);
        root.add("structures", byStructure);
        return root;
    }

    /**
     * The biomes each dimension of a default world can contain. A structure set belongs to a dimension when one of
     * its structures may use one of these biomes (the game's own rule).
     */
    static JsonObject dimensionsJson(String version, HolderLookup.Provider registries) {
        JsonObject byDimension = new JsonObject();
        WorldPresets.createNormalWorldDimensions(registries).dimensions().entrySet().stream()
            .sorted(Comparator.comparing(entry -> entry.getKey().identifier().toString()))
            .forEach(entry -> {
                JsonArray biomes = new JsonArray();
                entry.getValue().generator().getBiomeSource().possibleBiomes().stream()
                    .map(biome -> biome.unwrapKey().orElseThrow().identifier().toString())
                    .sorted()
                    .forEach(biomes::add);
                JsonObject dimension = new JsonObject();
                dimension.add("biomes", biomes);
                byDimension.add(entry.getKey().identifier().toString(), dimension);
            });
        JsonObject root = header(version);
        root.add("dimensions", byDimension);
        return root;
    }

    /** Raw RNG outputs after each of the game's seeding helpers. */
    static JsonObject rngJson(String version) {
        int[][] coords = {{0, 0}, {1, -1}, {-7, 13}, {123_456, -654_321}, {-1_875_000, 1_875_000}};
        int[] salts = {0, 10387312, 165745296, 94251327};
        JsonArray largeFeatureWithSalt = new JsonArray();
        JsonArray largeFeature = new JsonArray();
        JsonArray decoration = new JsonArray();
        for (long seed : SEEDS) {
            for (int[] c : coords) {
                for (int salt : salts) {
                    WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
                    random.setLargeFeatureWithSalt(seed, c[0], c[1], salt);
                    largeFeatureWithSalt.add(row(seed, c[0], c[1], salt, samples(random)));
                }
                WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
                random.setLargeFeatureSeed(seed, c[0], c[1]);
                largeFeature.add(row(seed, c[0], c[1], null, samples(random)));

                WorldgenRandom deco = new WorldgenRandom(new LegacyRandomSource(0L));
                long decorationSeed = deco.setDecorationSeed(seed, c[0] * 16, c[1] * 16);
                JsonObject d = row(seed, c[0] * 16, c[1] * 16, null, samples(deco));
                d.addProperty("decoration_seed", Long.toString(decorationSeed));
                JsonArray features = new JsonArray();
                for (int step = 0; step < 3; step++) {
                    for (int index = 0; index < 3; index++) {
                        WorldgenRandom feature = new WorldgenRandom(new LegacyRandomSource(0L));
                        feature.setFeatureSeed(decorationSeed, index, step);
                        features.add(Long.toString(feature.nextLong()));
                    }
                }
                d.add("feature_next_long", features);
                decoration.add(d);
            }
        }
        JsonObject root = header(version);
        root.add("large_feature_with_salt", largeFeatureWithSalt);
        root.add("large_feature", largeFeature);
        root.add("decoration", decoration);
        return root;
    }

    static JsonObject row(long seed, int x, int z, Integer salt, JsonObject samples) {
        JsonObject o = new JsonObject();
        o.addProperty("seed", Long.toString(seed));
        o.addProperty("x", x);
        o.addProperty("z", z);
        if (salt != null) {
            o.addProperty("salt", salt);
        }
        o.add("samples", samples);
        return o;
    }

    /** A fixed sequence of calls covering every RNG method the engine needs, in this exact order. */
    static JsonObject samples(WorldgenRandom random) {
        JsonObject s = new JsonObject();
        s.addProperty("next_int_24", random.nextInt(24));
        s.addProperty("next_int_32", random.nextInt(32));
        s.addProperty("next_int", random.nextInt());
        s.addProperty("next_long", Long.toString(random.nextLong()));
        s.addProperty("next_float", random.nextFloat());
        s.addProperty("next_double", random.nextDouble());
        s.addProperty("next_boolean", random.nextBoolean());
        return s;
    }

    /**
     * For every random-spread set and seed: the potential chunk in each region, whether it survives the
     * frequency check, and whether it is a real structure chunk (frequency + exclusion zones).
     */
    static JsonObject placementsJson(
        String version, HolderLookup.Provider registries, HolderLookup.RegistryLookup<StructureSet> structureSets,
        List<Holder.Reference<StructureSet>> sets
    ) {
        JsonObject bySet = new JsonObject();
        for (Holder.Reference<StructureSet> set : sets) {
            if (!(set.value().placement() instanceof RandomSpreadStructurePlacement placement)) {
                continue;
            }
            JsonObject bySeed = new JsonObject();
            for (long seed : SEEDS) {
                ChunkGeneratorStructureState state = overworldState(registries, structureSets, seed);
                JsonArray rows = new JsonArray();
                if (placement.spacing() == 1) {
                    // Every chunk is a candidate, so only list the chunks where the structure can start.
                    for (int cx = -CHUNK_RADIUS; cx <= CHUNK_RADIUS; cx++) {
                        for (int cz = -CHUNK_RADIUS; cz <= CHUNK_RADIUS; cz++) {
                            if (placement.isStructureChunk(state, cx, cz)) {
                                rows.add(ints(cx, cz));
                            }
                        }
                    }
                } else {
                    for (int rx = -REGION_RADIUS; rx <= REGION_RADIUS; rx++) {
                        for (int rz = -REGION_RADIUS; rz <= REGION_RADIUS; rz++) {
                            rows.add(placementRow(placement, state, seed, rx, rz));
                        }
                    }
                    for (int[] far : FAR_REGIONS) {
                        rows.add(placementRow(placement, state, seed, far[0], far[1]));
                    }
                }
                bySeed.add(Long.toString(seed), rows);
            }
            JsonObject setJson = new JsonObject();
            setJson.addProperty("format", placement.spacing() == 1
                ? "[chunkX, chunkZ] for every chunk where the structure can start"
                : "[regionX, regionZ, chunkX, chunkZ, passesFrequency, isStructureChunk]");
            setJson.add("seeds", bySeed);
            bySet.add(set.key().identifier().toString(), setJson);
        }
        JsonObject root = header(version);
        root.addProperty("region_radius", REGION_RADIUS);
        root.addProperty("chunk_radius", CHUNK_RADIUS);
        root.add("sets", bySet);
        return root;
    }

    static JsonArray placementRow(RandomSpreadStructurePlacement placement, ChunkGeneratorStructureState state, long seed, int rx, int rz) {
        ChunkPos chunk = placement.getPotentialStructureChunk(seed, rx * placement.spacing(), rz * placement.spacing());
        boolean frequency = placement.applyAdditionalChunkRestrictions(chunk.x(), chunk.z(), seed);
        boolean structureChunk = placement.isStructureChunk(state, chunk.x(), chunk.z());
        JsonArray row = ints(rx, rz, chunk.x(), chunk.z());
        row.add(frequency);
        row.add(structureChunk);
        return row;
    }

    /** Stronghold chunk positions (concentric rings), which depend on biomes. */
    static JsonObject strongholdsJson(
        String version, HolderLookup.Provider registries, HolderLookup.RegistryLookup<StructureSet> structureSets,
        List<Holder.Reference<StructureSet>> sets
    ) {
        JsonObject bySeed = new JsonObject();
        for (int i = 0; i < STRONGHOLD_SEEDS; i++) {
            long seed = SEEDS[i == 0 ? 3 : i == 1 ? 7 : 8];
            ChunkGeneratorStructureState state = overworldState(registries, structureSets, seed);
            for (Holder.Reference<StructureSet> set : sets) {
                if (set.value().placement() instanceof ConcentricRingsStructurePlacement rings) {
                    JsonArray positions = new JsonArray();
                    for (ChunkPos pos : state.getRingPositionsFor(rings)) {
                        positions.add(ints(pos.x(), pos.z()));
                    }
                    bySeed.add(Long.toString(seed), positions);
                }
            }
        }
        JsonObject root = header(version);
        root.addProperty("format", "[chunkX, chunkZ] of every stronghold, in generation order");
        root.add("seeds", bySeed);
        return root;
    }

    static ChunkGeneratorStructureState overworldState(
        HolderLookup.Provider registries, HolderLookup.RegistryLookup<StructureSet> structureSets, long seed
    ) {
        NoiseGeneratorSettings settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS)
            .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        RandomState randomState = RandomState.create(registries.lookupOrThrow(Registries.NOISE), seed, settings);
        BiomeSource biomes = MultiNoiseBiomeSource.createFromPreset(
            registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        return ChunkGeneratorStructureState.createForNormal(randomState, seed, ChunkPos.ZERO, biomes, structureSets);
    }

    static JsonObject header(String version) {
        JsonObject o = new JsonObject();
        o.addProperty("minecraft_version", version);
        o.addProperty("generated_by", "tools/oracle (calls the official Minecraft server code)");
        return o;
    }

    static JsonArray ints(int... values) {
        JsonArray a = new JsonArray();
        for (int v : values) {
            a.add(v);
        }
        return a;
    }

    static void write(Path file, JsonObject json) throws IOException {
        Files.writeString(file, collapseFlatArrays(GSON.toJson(json)) + "\n", StandardCharsets.UTF_8);
        System.out.println("wrote " + file);
    }

    /** Puts arrays of plain values on a single line, which keeps the fixture files small and diff-friendly. */
    static String collapseFlatArrays(String json) {
        return FLAT_ARRAY.matcher(json).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(
            "[" + m.group(1).strip().replaceAll("\\s*\\n\\s*", " ") + "]"));
    }

    static final java.util.regex.Pattern FLAT_ARRAY = java.util.regex.Pattern.compile("\\[([^\\[\\]{}]*)]");
}
