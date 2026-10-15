import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityBufferPool;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * How the overworld picks its biomes, asked from the real game: the climate functions of each world preset with
 * the noises they use, the overworld's list of biome parameters (version data), and the game's own climate values,
 * biome lookups, generated chunk biomes and zoomed block biomes for a few seeds (test fixtures).
 */
final class BiomeOracle {

    /** The router functions of the six climate parameters, in the order of {@code Climate.TargetPoint}. */
    static final String[] CLIMATE = {"temperature", "vegetation", "continents", "erosion", "depth", "ridges"};

    /** The world presets with their own overworld noise settings. */
    static final Map<String, ResourceKey<NoiseGeneratorSettings>> PRESETS = new LinkedHashMap<>();

    static {
        PRESETS.put("minecraft:normal", NoiseGeneratorSettings.OVERWORLD);
        PRESETS.put("minecraft:large_biomes", NoiseGeneratorSettings.LARGE_BIOMES);
        PRESETS.put("minecraft:amplified", NoiseGeneratorSettings.AMPLIFIED);
    }

    /** Seeds for the climate fixtures: the normal preset gets more of them, the other two a couple each. */
    static final long[] NORMAL_SEEDS = {0L, -1L, "Jrik".hashCode(), 8091867987493326313L};
    static final long[] OTHER_SEEDS = {1L, -6984854390176336655L};

    /** The y levels at which depth is recorded; the depth gradient is clamped below -64 and above 320. */
    static final int[] DEPTH_Y = {-80, -64, -60, -4, 0, 60, 64, 128, 316, 320, 400};

    private BiomeOracle() {
    }

    // ------------------------------------------------------------------ version data

    /**
     * The climate functions of every preset as the game's codec writes them (references to other functions stay as
     * their ids), every function and noise they reach, and the overworld's height.
     */
    static JsonObject climateJson(String version, HolderLookup.Provider registries) {
        RegistryOps<JsonElement> ops = registries.createSerializationContext(JsonOps.INSTANCE);
        HolderLookup.RegistryLookup<DensityFunction> functions = registries.lookupOrThrow(Registries.DENSITY_FUNCTION);
        HolderLookup.RegistryLookup<NormalNoise> noises = registries.lookupOrThrow(Registries.NOISE);
        JsonObject json = Oracle.header(version);
        DimensionType overworld = overworldType(registries);
        JsonObject dimension = new JsonObject();
        dimension.addProperty("min_y", overworld.minY());
        dimension.addProperty("height", overworld.height());
        json.add("overworld", dimension);

        TreeMap<String, JsonElement> functionJson = new TreeMap<>();
        TreeMap<String, JsonElement> noiseJson = new TreeMap<>();
        JsonObject presets = new JsonObject();
        for (Map.Entry<String, ResourceKey<NoiseGeneratorSettings>> preset : PRESETS.entrySet()) {
            NoiseRouter router = registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(preset.getValue()).value().noiseRouter();
            DensityFunction[] climate = {router.temperature(), router.vegetation(), router.continents(), router.erosion(), router.depth(), router.ridges()};
            JsonObject routerJson = new JsonObject();
            for (int i = 0; i < CLIMATE.length; i++) {
                JsonElement encoded = DensityFunction.CODEC.encodeStart(ops, climate[i]).getOrThrow();
                routerJson.add(CLIMATE[i], encoded);
                collect(encoded, null, ops, functions, noises, functionJson, noiseJson);
            }
            presets.add(preset.getKey(), routerJson);
        }
        json.add("presets", presets);
        JsonObject functionsObject = new JsonObject();
        functionJson.forEach(functionsObject::add);
        json.add("density_functions", functionsObject);
        JsonObject noisesObject = new JsonObject();
        noiseJson.forEach(noisesObject::add);
        json.add("noises", noisesObject);
        return json;
    }

    /** Adds every density function and noise an encoded function refers to, following references. */
    private static void collect(
        JsonElement element, String key, RegistryOps<JsonElement> ops, HolderLookup.RegistryLookup<DensityFunction> functions,
        HolderLookup.RegistryLookup<NormalNoise> noises, Map<String, JsonElement> functionJson, Map<String, JsonElement> noiseJson
    ) {
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                collect(entry.getValue(), entry.getKey(), ops, functions, noises, functionJson, noiseJson);
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collect(child, key, ops, functions, noises, functionJson, noiseJson);
            }
        } else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString() && !"type".equals(key)
            && element.getAsString().contains(":")) {
            // References are written as full ids; plain names like the gradient's axis "y" are not references.
            String id = element.getAsString();
            Identifier identifier = Identifier.tryParse(id);
            if (identifier == null) {
                return;
            }
            if ("noise".equals(key)) {
                if (!noiseJson.containsKey(id)) {
                    NormalNoise noise = noises.getOrThrow(ResourceKey.create(Registries.NOISE, identifier)).value();
                    noiseJson.put(id, NormalNoise.DIRECT_CODEC.encodeStart(ops, noise).getOrThrow());
                }
                return;
            }
            var function = functions.get(ResourceKey.create(Registries.DENSITY_FUNCTION, identifier));
            if (function.isPresent() && !functionJson.containsKey(id)) {
                JsonElement encoded = DensityFunctions.DIRECT_CODEC.encodeStart(ops, function.get().value()).getOrThrow();
                functionJson.put(id, encoded);
                collect(encoded, null, ops, functions, noises, functionJson, noiseJson);
            }
        }
    }

    /**
     * The overworld's biome parameters in the game's order (the order decides ties): the biome names, then one
     * string per entry with the biome's index, min and max of the six parameters (as stored, x 10000) and the offset.
     */
    static JsonObject biomeParametersJson(String version) {
        JsonObject json = Oracle.header(version);
        json.addProperty("format", "entries: 'biome t h c e d w offset' in the game's order (the order decides ties); biome indexes "
            + "biomes, each parameter indexes that parameter's intervals ([min, max] as stored, x 10000)");
        JsonObject overworld = new JsonObject();
        List<String> biomes = new ArrayList<>();
        List<List<String>> intervals = new ArrayList<>();
        for (int d = 0; d < 6; d++) {
            intervals.add(new ArrayList<>());
        }
        JsonArray entries = new JsonArray();
        for (Pair<Climate.ParameterPoint, ResourceKey<Biome>> entry : overworldList().values()) {
            String biome = entry.getSecond().identifier().toString();
            if (!biomes.contains(biome)) {
                biomes.add(biome);
            }
            StringBuilder row = new StringBuilder().append(biomes.indexOf(biome));
            List<Climate.Parameter> parameters = parameters(entry.getFirst());
            for (int d = 0; d < 6; d++) {
                String interval = parameters.get(d).min() + " " + parameters.get(d).max();
                if (!intervals.get(d).contains(interval)) {
                    intervals.get(d).add(interval);
                }
                row.append(' ').append(intervals.get(d).indexOf(interval));
            }
            entries.add(row.append(' ').append(entry.getFirst().offset()).toString());
        }
        JsonArray names = new JsonArray();
        biomes.forEach(names::add);
        overworld.add("biomes", names);
        JsonObject intervalsJson = new JsonObject();
        String[] parameterNames = {"temperature", "humidity", "continentalness", "erosion", "depth", "weirdness"};
        for (int d = 0; d < 6; d++) {
            JsonArray list = new JsonArray();
            intervals.get(d).forEach(list::add);
            intervalsJson.add(parameterNames[d], list);
        }
        overworld.add("intervals", intervalsJson);
        overworld.add("entries", entries);
        json.add("overworld", overworld);
        return json;
    }

    /**
     * Writes like {@link Oracle#write}, except that arrays of strings keep one string per line and objects without
     * nested objects or arrays (like spline points) go on one line, which keeps these larger files readable and small.
     */
    static void write(java.nio.file.Path file, JsonObject json) throws java.io.IOException {
        String text = FLAT_NUMBER_ARRAY.matcher(Oracle.GSON.toJson(json)).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(
            "[" + m.group(1).strip().replaceAll("\\s*\\n\\s*", " ") + "]"));
        text = FLAT_OBJECT.matcher(text).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(
            "{" + m.group(1).strip().replaceAll("\\s*\\n\\s*", " ") + "}"));
        java.nio.file.Files.writeString(file, text + "\n", java.nio.charset.StandardCharsets.UTF_8);
        System.out.println("wrote " + file);
    }

    static final java.util.regex.Pattern FLAT_NUMBER_ARRAY = java.util.regex.Pattern.compile("\\[([^\\[\\]{}\"]*)]");

    static final java.util.regex.Pattern FLAT_OBJECT = java.util.regex.Pattern.compile("\\{([^\\[\\]{}]*)}");

    private static List<Climate.Parameter> parameters(Climate.ParameterPoint point) {
        return List.of(point.temperature(), point.humidity(), point.continentalness(), point.erosion(), point.depth(), point.weirdness());
    }

    static Climate.ParameterList<ResourceKey<Biome>> overworldList() {
        return MultiNoiseBiomeSourceParameterList.knownPresets().get(MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD);
    }

    private static DimensionType overworldType(HolderLookup.Provider registries) {
        return WorldPresets.createNormalWorldDimensions(registries).dimensions().get(LevelStem.OVERWORLD).type().value();
    }

    // ------------------------------------------------------------------ fixtures

    /** The game's answers: noise values, climate values, a sequence of biome lookups, chunk biomes and the zoom. */
    static JsonObject biomeFixturesJson(String version, HolderLookup.Provider registries, RegistryAccess.Frozen world) {
        JsonObject json = Oracle.header(version);
        json.addProperty("format",
            "noises: 'seed noise x y z value'; climate: 'x z temperature vegetation continents erosion ridges depth...' at block "
                + "(x, z) with depth at each of depth_y; search: 'temperature humidity continentalness erosion depth weirdness biome' looked "
                + "up in this order on one thread with a fresh list; chunks: biome indices of all quarts in the game's fill order (sections "
                + "upwards, then x, y, z), as 'index*count' runs, filled on a fresh thread; zoom: 'x y z corner' where corner = "
                + "floorMod(qx + 2qy + 4qz, 8) of the quart the zoom picks");
        JsonArray depthY = new JsonArray();
        for (int y : DEPTH_Y) {
            depthY.add(y);
        }
        json.add("depth_y", depthY);
        HolderLookup.RegistryLookup<NormalNoise> noiseLookup = registries.lookupOrThrow(Registries.NOISE);
        Map<String, NoiseRouter> routers = new LinkedHashMap<>();
        Map<String, NoiseGeneratorSettings> settings = new LinkedHashMap<>();
        PRESETS.forEach((preset, key) -> {
            NoiseGeneratorSettings value = registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(key).value();
            settings.put(preset, value);
            routers.put(preset, value.noiseRouter());
        });
        Random random = new Random(26_3_0L);

        JsonObject noises = new JsonObject();
        JsonObject climate = new JsonObject();
        List<long[]> targets = new ArrayList<>();
        for (Map.Entry<String, NoiseGeneratorSettings> preset : settings.entrySet()) {
            JsonArray noiseRows = new JsonArray();
            JsonArray climateRows = new JsonArray();
            boolean normal = preset.getKey().equals("minecraft:normal");
            for (long seed : normal ? NORMAL_SEEDS : OTHER_SEEDS) {
                RandomState state = RandomState.create(noiseLookup, seed, preset.getValue());
                for (String noise : noiseIds(preset.getKey())) {
                    Noise instance = state.getOrCreateNoise(ResourceKey.create(Registries.NOISE, Identifier.parse(noise)));
                    for (double[] point : noisePoints(random)) {
                        noiseRows.add(seed + " " + noise + " " + point[0] + " " + point[1] + " " + point[2] + " "
                            + instance.get(point[0], point[1], point[2]));
                    }
                }
                Climate.Sampler sampler = state.createClimateSampler(SamplerContext.EMPTY_UNCACHED);
                DensitySampler.Bound[] parameters = {sampler.temperature(), sampler.humidity(), sampler.continentalness(), sampler.erosion(),
                    sampler.weirdness(), sampler.depth()};
                for (int column = 0; column < 24; column++) {
                    int[] xz = column(random, column);
                    StringBuilder row = new StringBuilder().append(xz[0]).append(' ').append(xz[1]);
                    for (int i = 0; i < 5; i++) {
                        row.append(' ').append(parameters[i].sampleValue(xz[0], 0, xz[1]));
                    }
                    for (int y : DEPTH_Y) {
                        row.append(' ').append(sampler.depth().sampleValue(xz[0], y, xz[1]));
                    }
                    climateRows.add(row.toString());
                    if (normal) {
                        for (int y : new int[] {-64, 0, 64, 256}) {
                            Climate.TargetPoint target = sampler.sample(QuartPos.fromBlock(xz[0]), QuartPos.fromBlock(y), QuartPos.fromBlock(xz[1]));
                            targets.add(new long[] {target.temperature(), target.humidity(), target.continentalness(), target.erosion(),
                                target.depth(), target.weirdness()});
                        }
                    }
                }
            }
            noises.add(preset.getKey(), noiseRows);
            climate.add(preset.getKey(), climateRows);
        }
        json.add("noises", noises);
        json.add("climate", climate);
        json.add("search", searchRows(targets, random));
        json.add("chunks", chunksJson(registries, world, settings, random));
        json.add("zoom", zoomRows(registries, random));
        return json;
    }

    /** The noises the climate functions of a preset use (the shift noise and the five climate noises). */
    private static List<String> noiseIds(String preset) {
        String suffix = preset.equals("minecraft:large_biomes") ? "_large" : "";
        return List.of("minecraft:offset", "minecraft:temperature" + suffix, "minecraft:vegetation" + suffix,
            "minecraft:continentalness" + suffix, "minecraft:erosion" + suffix, "minecraft:ridge");
    }

    /** Sample points: small, ordinary, fractional and huge coordinates (the noise wraps very large ones). */
    private static List<double[]> noisePoints(Random random) {
        List<double[]> points = new ArrayList<>();
        points.add(new double[] {0.0, 0.0, 0.0});
        points.add(new double[] {-0.25, 0.0, 17.75});
        points.add(new double[] {random.nextInt(2000) - 1000 + 0.25, 0.0, random.nextInt(2000) - 1000 - 0.5});
        points.add(new double[] {random.nextDouble() * 4096 - 2048, random.nextDouble() * 64, random.nextDouble() * 4096 - 2048});
        points.add(new double[] {3.3554432E7 + 0.75, 0.0, -1.6777216E7});
        points.add(new double[] {-7_500_000.25 * 1.0181268882175227, 12.5, 7_499_999.0});
        return points;
    }

    /** Quart-aligned block columns: near the origin, ordinary, far out and at the world border. */
    private static int[] column(Random random, int index) {
        int range = switch (index % 4) {
            case 0 -> 512;
            case 1 -> 20_000;
            case 2 -> 1_000_000;
            default -> 29_999_984;
        };
        return new int[] {(random.nextInt(2 * range) - range) & ~3, (random.nextInt(2 * range) - range) & ~3};
    }

    /**
     * Looks up the climate targets, then targets placed on the edges of the biomes' parameter ranges (where ties
     * happen), in that order on one fresh thread with a fresh list, so the game's "start from the last answer"
     * shortcut starts empty.
     */
    private static JsonArray searchRows(List<long[]> climateTargets, Random random) {
        List<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> values = overworldList().values();
        Climate.ParameterList<ResourceKey<Biome>> fresh = new Climate.ParameterList<>(values);
        List<String> biomes = biomeNames(values);
        List<long[]> targets = new ArrayList<>(climateTargets);
        for (int i = 0; i < 1500; i++) {
            long[] target = new long[6];
            for (int d = 0; d < 6; d++) {
                Climate.Parameter parameter = parameters(values.get(random.nextInt(values.size())).getFirst()).get(d);
                target[d] = switch (random.nextInt(5)) {
                    case 0 -> parameter.min();
                    case 1 -> parameter.max();
                    case 2 -> parameter.min() - 1;
                    case 3 -> parameter.max() + 1;
                    default -> random.nextInt(24_001) - 12_000;
                };
            }
            targets.add(target);
        }
        JsonArray rows = new JsonArray();
        runOnFreshThread(() -> {
            for (long[] t : targets) {
                ResourceKey<Biome> biome = fresh.findValue(new Climate.TargetPoint(t[0], t[1], t[2], t[3], t[4], t[5]));
                rows.add(t[0] + " " + t[1] + " " + t[2] + " " + t[3] + " " + t[4] + " " + t[5] + " " + biomes.indexOf(biome.identifier().toString()));
            }
        });
        return rows;
    }

    private static List<String> biomeNames(List<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> values) {
        List<String> names = new ArrayList<>();
        for (Pair<Climate.ParameterPoint, ResourceKey<Biome>> value : values) {
            String name = value.getSecond().identifier().toString();
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Whole generated chunks, made as {@code ChunkGenerator.doCreateBiomes} makes them (caching sampler, volume
     * sampling, fill order), each on a fresh thread so that its first lookup starts without a previous answer.
     */
    private static JsonObject chunksJson(
        HolderLookup.Provider registries, RegistryAccess.Frozen world, Map<String, NoiseGeneratorSettings> settings, Random random
    ) {
        HolderLookup.RegistryLookup<NormalNoise> noiseLookup = registries.lookupOrThrow(Registries.NOISE);
        MultiNoiseBiomeSource source = MultiNoiseBiomeSource.createFromPreset(
            registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        List<String> biomes = biomeNames(overworldList().values());
        DimensionType type = overworldType(registries);
        LevelHeightAccessor height = LevelHeightAccessor.create(type.minY(), type.height());
        PalettedContainerFactory containers = PalettedContainerFactory.create(world);
        JsonObject chunks = new JsonObject();
        for (Map.Entry<String, NoiseGeneratorSettings> preset : settings.entrySet()) {
            boolean normal = preset.getKey().equals("minecraft:normal");
            JsonArray rows = new JsonArray();
            for (long seed : normal ? NORMAL_SEEDS : OTHER_SEEDS) {
                RandomState state = RandomState.create(noiseLookup, seed, preset.getValue());
                int count = normal ? 2 : 1;
                for (int c = 0; c < count; c++) {
                    ChunkPos pos = c == 0 && seed == NORMAL_SEEDS[0] ? new ChunkPos(0, 0)
                        : new ChunkPos(random.nextInt(4000) - 2000, random.nextInt(4000) - 2000);
                    ProtoChunk chunk = new ProtoChunk(pos, UpgradeData.EMPTY, height, containers, null);
                    runOnFreshThread(() -> {
                        DensityBufferPool pool = state.acquireDensityBufferPool();
                        try {
                            Climate.Sampler sampler = state.createClimateSampler(SamplerContext.builder().enableCaches().useBufferArena(pool).build());
                            BiomeResolver resolver = source.createResolverForChunk(sampler, QuartPos.fromBlock(pos.getMinBlockX()),
                                QuartPos.fromBlock(chunk.getMinY()), QuartPos.fromBlock(pos.getMinBlockZ()), QuartPos.fromBlock(16),
                                QuartPos.fromBlock(chunk.getHeight()), QuartPos.fromBlock(16));
                            chunk.fillBiomesFromNoise(resolver);
                        } finally {
                            state.releaseDensityBufferPool(pool);
                        }
                    });
                    chunk.setPersistedStatus(ChunkStatus.BIOMES);
                    StringBuilder runs = new StringBuilder();
                    int last = -1;
                    int run = 0;
                    int qx0 = QuartPos.fromBlock(pos.getMinBlockX());
                    int qz0 = QuartPos.fromBlock(pos.getMinBlockZ());
                    for (int section = height.getMinSectionY(); section <= height.getMaxSectionY(); section++) {
                        int qy0 = QuartPos.fromSection(section);
                        for (int x = 0; x < 4; x++) {
                            for (int y = 0; y < 4; y++) {
                                for (int z = 0; z < 4; z++) {
                                    Holder<Biome> biome = chunk.getNoiseBiome(qx0 + x, qy0 + y, qz0 + z);
                                    int index = biomes.indexOf(biome.unwrapKey().orElseThrow().identifier().toString());
                                    if (index != last && run > 0) {
                                        runs.append(runs.isEmpty() ? "" : " ").append(last).append('*').append(run);
                                        run = 0;
                                    }
                                    last = index;
                                    run++;
                                }
                            }
                        }
                    }
                    runs.append(runs.isEmpty() ? "" : " ").append(last).append('*').append(run);
                    JsonObject row = new JsonObject();
                    row.addProperty("seed", seed);
                    row.addProperty("chunk_x", pos.x());
                    row.addProperty("chunk_z", pos.z());
                    row.addProperty("biomes", runs.toString());
                    rows.add(row);
                }
            }
            chunks.add(preset.getKey(), rows);
        }
        return chunks;
    }

    /**
     * The zoom from quart biomes to block biomes, with a source whose biome tells which quart was asked for: the
     * corner code floorMod(qx + 2qy + 4qz, 8) differs for the 8 quarts the zoom chooses from.
     */
    private static JsonObject zoomRows(HolderLookup.Provider registries, Random random) {
        List<Holder<Biome>> codes = new ArrayList<>(registries.lookupOrThrow(Registries.BIOME).listElements()
            .sorted(java.util.Comparator.comparing(biome -> biome.key().identifier().toString())).limit(8).toList());
        JsonObject zoom = new JsonObject();
        for (long seed : new long[] {0L, "Jrik".hashCode(), 8091867987493326313L}) {
            long hashed = BiomeManager.obfuscateSeed(seed);
            BiomeManager manager = new BiomeManager((qx, qy, qz) -> codes.get(Math.floorMod(qx + 2 * qy + 4 * qz, 8)), hashed);
            JsonArray rows = new JsonArray();
            for (int i = 0; i < 150; i++) {
                int range = i % 3 == 0 ? 64 : i % 3 == 1 ? 100_000 : 30_000_000;
                int x = random.nextInt(2 * range) - range;
                int y = random.nextInt(400) - 80;
                int z = random.nextInt(2 * range) - range;
                rows.add(x + " " + y + " " + z + " " + codes.indexOf(manager.getBiome(x, y, z)));
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("hashed_seed", hashed);
            entry.add("blocks", rows);
            zoom.add(Long.toString(seed), entry);
        }
        return zoom;
    }

    private static void runOnFreshThread(Runnable task) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(task, "biome-oracle");
        thread.setUncaughtExceptionHandler((t, e) -> failure.set(e));
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        if (failure.get() != null) {
            throw new IllegalStateException("biome oracle thread failed", failure.get());
        }
    }
}
