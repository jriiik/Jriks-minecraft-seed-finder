import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.material.condition.MaterialCondition;
import net.minecraft.world.level.levelgen.material.condition.NotCondition;
import net.minecraft.world.level.levelgen.material.condition.VerticalGradientCondition;
import net.minecraft.world.level.levelgen.material.rule.BlockRule;
import net.minecraft.world.level.levelgen.material.rule.ConditionRule;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.SequenceRule;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * The oracle's Phase 3 part: the random generators used for blocks (Xoroshiro128++), the seeds Minecraft picks when
 * no seed is typed, and the random bedrock layers, recorded from real generated chunks.
 */
final class BedrockOracle {

    /** Chunks whose bedrock is recorded: around spawn, negative coordinates and next to the world border. */
    static final int[][] CHUNKS = {{0, 0}, {-1, -1}, {7, -3}, {1_874_999, -1_875_000}};

    /** Block positions for the positional random generators, including extremes. */
    static final int[][] POSITIONS = {
        {0, 0, 0}, {1, -63, 2}, {-1, -64, -1}, {5, -63, -9}, {0, 1, 0}, {0, -1, 0}, {123_456, -60, -654_321},
        {30_000_000, -64, -30_000_000}, {-29_999_984, 319, 29_999_984},
        {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE}, {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE},
    };

    static final String[] HASHED_NAMES = {"", "minecraft:bedrock_floor", "minecraft:bedrock_roof", "minecraft:deepslate", "äöü-日本"};

    static final int[] BOUNDS = {1, 3, 24, 100, 1 << 30, 0x55555556, Integer.MAX_VALUE};

    /** Text people might type into the seed box, to check our copy of Minecraft's seed parsing. */
    static final String[] TYPED_SEEDS = {
        "", "   ", "abc", " abc ", "123", " 123 ", "+5", "-0", "0x10", "1_000", "99999999999999999999",
        "-9223372036854775808", "9223372036854775807", "１２３", "North Carolina", "Jrik", "Hello World!",
    };

    private BedrockOracle() {
    }

    /**
     * Version data: for each dimension of a default world, its block random source and the bedrock rules at the start
     * of its material rules, with heights resolved to absolute y values.
     */
    static JsonObject bedrockRulesJson(String version, HolderLookup.Provider registries) {
        JsonObject byDimension = new JsonObject();
        WorldPresets.createNormalWorldDimensions(registries).dimensions().entrySet().stream()
            .sorted(Comparator.comparing(entry -> entry.getKey().identifier().toString()))
            .forEach(entry -> {
                LevelStem stem = entry.getValue();
                if (!(stem.generator() instanceof NoiseBasedChunkGenerator generator)) {
                    return;
                }
                NoiseGeneratorSettings settings = generator.generatorSettings().value();
                DimensionType type = stem.type().value();
                WorldGenerationContext context = new WorldGenerationContext(generator, LevelHeightAccessor.create(type.minY(), type.height()));
                JsonArray rules = new JsonArray();
                List<MaterialRule> sequence = sequence(settings.materialRule().value());
                int index = 0;
                for (; index < sequence.size(); index++) {
                    VerticalGradientCondition gradient = bedrockGradient(sequence.get(index));
                    if (gradient == null) {
                        break;
                    }
                    JsonObject rule = new JsonObject();
                    rule.addProperty("random_name", gradient.randomName().toString());
                    rule.addProperty("true_at_and_below", gradient.trueAtAndBelow().resolveY(context));
                    rule.addProperty("false_at_and_above", gradient.falseAtAndAbove().resolveY(context));
                    rule.addProperty("inverted", isInverted(sequence.get(index)));
                    rules.add(rule);
                }
                for (; index < sequence.size(); index++) {
                    if (placesBedrock(sequence.get(index))) {
                        throw new IllegalStateException(entry.getKey() + " places bedrock in a way the engine does not model yet");
                    }
                }
                JsonObject dimension = new JsonObject();
                dimension.addProperty("legacy_random_source", settings.useLegacyRandomSource());
                dimension.addProperty("min_y", context.getMinGenY());
                dimension.addProperty("height", context.getGenDepth());
                dimension.add("bedrock_rules", rules);
                byDimension.add(entry.getKey().identifier().toString(), dimension);
            });
        JsonObject root = Oracle.header(version);
        root.add("dimensions", byDimension);
        return root;
    }

    static MaterialRule unwrap(MaterialRule rule) {
        while (rule instanceof MaterialRule.HolderHolder(Holder<MaterialRule> holder)) {
            rule = holder.value();
        }
        return rule;
    }

    static MaterialCondition unwrap(MaterialCondition condition) {
        while (condition instanceof MaterialCondition.HolderHolder(Holder<MaterialCondition> holder)) {
            condition = holder.value();
        }
        return condition;
    }

    static List<MaterialRule> sequence(MaterialRule rule) {
        return unwrap(rule) instanceof SequenceRule(List<MaterialRule> rules) ? rules : List.of(rule);
    }

    /** The gradient of a rule "if (not) vertical_gradient then bedrock", or null for any other rule. */
    static VerticalGradientCondition bedrockGradient(MaterialRule rule) {
        if (!(unwrap(rule) instanceof ConditionRule(MaterialCondition ifTrue, MaterialRule thenRun))
            || !(unwrap(thenRun) instanceof BlockRule block) || !block.resultState().is(Blocks.BEDROCK)) {
            return null;
        }
        MaterialCondition condition = unwrap(ifTrue);
        if (condition instanceof NotCondition(MaterialCondition target)) {
            condition = unwrap(target);
        }
        return condition instanceof VerticalGradientCondition gradient ? gradient : null;
    }

    static boolean isInverted(MaterialRule rule) {
        return unwrap(rule) instanceof ConditionRule(MaterialCondition ifTrue, MaterialRule ignored) && unwrap(ifTrue) instanceof NotCondition;
    }

    static boolean placesBedrock(MaterialRule rule) {
        return switch (unwrap(rule)) {
            case SequenceRule(List<MaterialRule> rules) -> rules.stream().anyMatch(BedrockOracle::placesBedrock);
            case ConditionRule(MaterialCondition ignored, MaterialRule thenRun) -> placesBedrock(thenRun);
            case BlockRule block -> block.resultState().is(Blocks.BEDROCK);
            default -> false;
        };
    }

    /** Fixture: the Xoroshiro128++ generator, its seeding helpers and the positional generators built from it. */
    @SuppressWarnings("deprecation") // Mth.getSeed is deprecated in the game, but it is what bedrock uses
    static JsonObject xoroshiroJson(String version, HolderLookup.Provider registries) {
        JsonObject root = Oracle.header(version);

        JsonArray mix = new JsonArray();
        JsonArray upgrade = new JsonArray();
        for (long seed : Oracle.SEEDS) {
            mix.add(longs(seed, RandomSupport.mixStafford13(seed)));
            RandomSupport.Seed128bit upgraded = RandomSupport.upgradeSeedTo128bit(seed);
            upgrade.add(longs(seed, upgraded.seedLo(), upgraded.seedHi()));
        }
        root.add("mix_stafford13", mix);
        root.add("upgrade_seed_to_128bit", upgrade);

        JsonArray hashes = new JsonArray();
        for (String name : HASHED_NAMES) {
            RandomSupport.Seed128bit hash = RandomSupport.seedFromHashOf(name);
            JsonArray row = new JsonArray();
            row.add(name);
            row.add(Long.toString(hash.seedLo()));
            row.add(Long.toString(hash.seedHi()));
            hashes.add(row);
        }
        root.add("seed_from_hash_of", hashes);

        JsonArray positional = new JsonArray();
        for (int[] p : POSITIONS) {
            JsonArray row = Oracle.ints(p);
            row.add(Long.toString(Mth.getSeed(p[0], p[1], p[2])));
            positional.add(row);
        }
        root.add("positional_seed", positional);

        JsonObject bySeed = new JsonObject();
        for (long seed : Oracle.SEEDS) {
            JsonObject s = new JsonObject();
            s.add("samples", samples(new XoroshiroRandomSource(seed)));
            s.addProperty("fork_next_long", Long.toString(new XoroshiroRandomSource(seed).fork().nextLong()));
            PositionalRandomFactory factory = new XoroshiroRandomSource(seed).forkPositional();
            JsonArray at = new JsonArray();
            for (int[] p : POSITIONS) {
                at.add(Long.toString(factory.at(p[0], p[1], p[2]).nextLong()));
            }
            s.add("fork_positional_at_next_long", at);
            JsonArray fromHash = new JsonArray();
            for (String name : HASHED_NAMES) {
                fromHash.add(Long.toString(factory.fromHashOf(name).nextLong()));
            }
            s.add("fork_positional_from_hash_of_next_long", fromHash);
            s.add("world_bedrock_factories", worldBedrockFactories(registries, seed));
            bySeed.add(Long.toString(seed), s);
        }
        root.addProperty("format", "longs are strings; positions and names are listed in the same order as positional_seed and seed_from_hash_of");
        root.add("seeds", bySeed);
        return root;
    }

    /** A fixed sequence of calls on one generator, in this exact order. */
    static JsonObject samples(RandomSource random) {
        JsonObject s = new JsonObject();
        s.addProperty("next_long", Long.toString(random.nextLong()));
        s.addProperty("next_int", random.nextInt());
        JsonArray bounded = new JsonArray();
        for (int bound : BOUNDS) {
            bounded.add(random.nextInt(bound));
        }
        for (int i = 0; i < 20; i++) {
            bounded.add(random.nextInt(0x55555556));
        }
        s.add("next_int_bounded", bounded);
        s.addProperty("next_float", random.nextFloat());
        s.addProperty("next_double", random.nextDouble());
        s.addProperty("next_boolean", random.nextBoolean());
        s.addProperty("next_long_after", Long.toString(random.nextLong()));
        return s;
    }

    /**
     * For each dimension and bedrock rule: the first nextLong() of the world's own positional generator for that rule
     * (RandomState.getOrCreateRandomFactory) at each test position.
     */
    static JsonObject worldBedrockFactories(HolderLookup.Provider registries, long seed) {
        JsonObject byDimension = new JsonObject();
        WorldPresets.createNormalWorldDimensions(registries).dimensions().entrySet().stream()
            .sorted(Comparator.comparing(entry -> entry.getKey().identifier().toString()))
            .forEach(entry -> {
                if (!(entry.getValue().generator() instanceof NoiseBasedChunkGenerator generator)) {
                    return;
                }
                NoiseGeneratorSettings settings = generator.generatorSettings().value();
                RandomState state = RandomState.create(registries.lookupOrThrow(Registries.NOISE), seed, settings);
                JsonObject byName = new JsonObject();
                for (MaterialRule rule : sequence(settings.materialRule().value())) {
                    VerticalGradientCondition gradient = bedrockGradient(rule);
                    if (gradient == null) {
                        break;
                    }
                    PositionalRandomFactory factory = state.getOrCreateRandomFactory(gradient.randomName());
                    JsonArray values = new JsonArray();
                    for (int[] p : POSITIONS) {
                        values.add(Long.toString(factory.at(p[0], p[1], p[2]).nextLong()));
                    }
                    byName.add(gradient.randomName().toString(), values);
                }
                byDimension.add(entry.getKey().identifier().toString(), byName);
            });
        return byDimension;
    }

    /** Fixture: world seeds Minecraft picks when no seed is typed, and how typed seeds are read. */
    static JsonObject randomSeedsJson(String version) {
        JsonObject root = Oracle.header(version);
        List<Long> generatorSeeds = new ArrayList<>(List.of(0L, 1L, -1L, 42L, 123_456_789L, Long.MIN_VALUE, Long.MAX_VALUE,
            0x5DEECE66DL, 0x5DEECE66DL ^ 0xFFFF_FFFF_FFFFL, 0xFFFF_8000_0000L, 0xFFFF_7FFF_FFFFL, 0x8000_0000L));
        java.util.Random list = new java.util.Random(263);
        while (generatorSeeds.size() < 300) {
            generatorSeeds.add(list.nextLong());
        }
        JsonArray nextLong = new JsonArray();
        for (long g : generatorSeeds) {
            // WorldOptions.randomSeed() is RandomSource.create().nextLong(), with RandomSource.create() seeded by
            // RandomSupport.generateUniqueSeed(); here the generator seed is fixed so the output is repeatable.
            nextLong.add(longs(g, RandomSource.create(g).nextLong()));
        }
        root.addProperty("next_long_format", "[generator seed, RandomSource.create(generator seed).nextLong()]");
        root.add("next_long", nextLong);

        JsonArray parsed = new JsonArray();
        for (String text : TYPED_SEEDS) {
            OptionalLong seed = WorldOptions.parseSeed(text);
            JsonArray row = new JsonArray();
            row.add(text);
            row.add(seed.isPresent() ? Long.toString(seed.getAsLong()) : null);
            parsed.add(row);
        }
        root.addProperty("parse_seed_format", "[typed text, WorldOptions.parseSeed(text) or null for a random seed]");
        root.add("parse_seed", parsed);

        JsonArray hashed = new JsonArray();
        for (long seed : Oracle.SEEDS) {
            hashed.add(longs(seed, BiomeManager.obfuscateSeed(seed)));
        }
        for (int i = 0; i < 200; i++) {
            long seed = list.nextLong();
            hashed.add(longs(seed, BiomeManager.obfuscateSeed(seed)));
        }
        root.addProperty("hashed_seed_format", "[world seed, BiomeManager.obfuscateSeed(world seed)], the value servers send to clients");
        root.add("hashed_seed", hashed);
        return root;
    }

    /**
     * Fixture: the bedrock layers of real chunks, generated by the game's own pipeline up to the TERRAIN step (noise,
     * material rules, carvers), which is where bedrock is placed.
     */
    static JsonObject bedrockChunksJson(String version, HolderLookup.Provider registries, RegistryAccess.Frozen worldRegistries) {
        JsonObject bySeed = new JsonObject();
        for (long seed : Oracle.SEEDS) {
            JsonObject byDimension = new JsonObject();
            WorldPresets.createNormalWorldDimensions(registries).dimensions().entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().identifier().toString()))
                .forEach(entry -> {
                    if (!(entry.getValue().generator() instanceof NoiseBasedChunkGenerator generator)) {
                        return;
                    }
                    List<int[]> bands = new ArrayList<>();
                    for (MaterialRule rule : sequence(generator.generatorSettings().value().materialRule().value())) {
                        if (bedrockGradient(rule) == null) {
                            break;
                        }
                        DimensionType type = entry.getValue().type().value();
                        WorldGenerationContext context = new WorldGenerationContext(generator, LevelHeightAccessor.create(type.minY(), type.height()));
                        VerticalGradientCondition gradient = bedrockGradient(rule);
                        bands.add(new int[] {gradient.trueAtAndBelow().resolveY(context), gradient.falseAtAndAbove().resolveY(context)});
                    }
                    if (bands.isEmpty()) {
                        return;
                    }
                    ChunkHarness harness = new ChunkHarness(registries, worldRegistries, entry.getValue(), generator, seed);
                    JsonObject byChunk = new JsonObject();
                    for (int[] c : CHUNKS) {
                        ProtoChunk chunk = harness.terrain(c[0], c[1]);
                        JsonObject byY = new JsonObject();
                        for (int[] band : bands) {
                            for (int y = band[0]; y <= band[1]; y++) {
                                byY.addProperty(Integer.toString(y), layer(chunk, y));
                            }
                        }
                        byChunk.add(c[0] + "," + c[1], byY);
                    }
                    byDimension.add(entry.getKey().identifier().toString(), byChunk);
                });
            bySeed.add(Long.toString(seed), byDimension);
        }
        JsonObject root = Oracle.header(version);
        root.addProperty("format", "seed -> dimension -> \"chunkX,chunkZ\" -> y -> 16 rows of 4 hex digits, one row per z from the chunk's "
            + "north edge; bit x (0 = west edge) of a row is set where the block is bedrock");
        root.add("seeds", bySeed);
        return root;
    }

    static String layer(ProtoChunk chunk, int y) {
        ChunkPos pos = chunk.getPos();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        StringBuilder rows = new StringBuilder();
        for (int dz = 0; dz < 16; dz++) {
            int bits = 0;
            for (int dx = 0; dx < 16; dx++) {
                if (chunk.getBlockState(p.set(pos.getMinBlockX() + dx, y, pos.getMinBlockZ() + dz)).is(Blocks.BEDROCK)) {
                    bits |= 1 << dx;
                }
            }
            rows.append(dz == 0 ? "" : " ").append(String.format("%04x", bits));
        }
        return rows.toString();
    }

    static JsonArray longs(long... values) {
        JsonArray a = new JsonArray();
        for (long v : values) {
            a.add(Long.toString(v));
        }
        return a;
    }

    /**
     * Generates chunks the way a server does (ChunkMap / ChunkStatusTasks), without a server: BIOMES for the chunk and
     * its neighbours, then TERRAIN. On a server, structure starts come first and the noise fill adapts the terrain
     * around them (the "beardifier"); this harness leaves structures out. That is fine for the bedrock layers (with
     * real structures, 0 blocks changed at y=-64..-59 in the research runs), but not for other blocks: don't record
     * other terrain with it without adding real structures.
     */
    static final class ChunkHarness {
        final NoiseBasedChunkGenerator generator;
        final LevelHeightAccessor height;
        final RandomState randomState;
        final PalettedContainerFactory containers;
        final BiomeManager biomeManager;
        final StructureManager structures;
        final Map<Long, ProtoChunk> biomeChunks = new HashMap<>();

        ChunkHarness(HolderLookup.Provider registries, RegistryAccess.Frozen worldRegistries, LevelStem stem,
                     NoiseBasedChunkGenerator generator, long seed) {
            this.generator = generator;
            DimensionType type = stem.type().value();
            this.height = LevelHeightAccessor.create(type.minY(), type.height());
            this.randomState = RandomState.create(registries.lookupOrThrow(Registries.NOISE), seed, generator.generatorSettings().value());
            this.containers = PalettedContainerFactory.create(worldRegistries);
            BiomeResolver uncached = generator.getBiomeSource().createUncachedResolver(randomState);
            BiomeResolver regionBiomes = (qx, qy, qz) -> {
                ProtoChunk chunk = biomeChunks.get(ChunkPos.pack(QuartPos.toSection(qx), QuartPos.toSection(qz)));
                return chunk != null ? chunk.getNoiseBiome(qx, qy, qz) : uncached.getNoiseBiome(qx, qy, qz);
            };
            this.biomeManager = new BiomeManager(regionBiomes, BiomeManager.obfuscateSeed(seed));
            this.structures = new NoStructures(seed);
        }

        ProtoChunk biomes(int x, int z) {
            return biomeChunks.computeIfAbsent(ChunkPos.pack(x, z), key -> {
                ProtoChunk chunk = new ProtoChunk(new ChunkPos(x, z), UpgradeData.EMPTY, height, containers, null);
                generator.createBiomes(randomState, Blender.empty(), structures, chunk).join();
                chunk.setPersistedStatus(ChunkStatus.BIOMES);
                return chunk;
            });
        }

        ProtoChunk terrain(int x, int z) {
            Set<Holder<Biome>> possibleBiomes = new ObjectOpenHashSet<>();
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    biomes(x + dx, z + dz).collectBiomesInPalette(possibleBiomes);
                }
            }
            ProtoChunk chunk = biomes(x, z);
            generator.buildTerrain(chunk, Blender.empty(), randomState, structures, biomeManager, null, possibleBiomes).join();
            chunk.setPersistedStatus(ChunkStatus.TERRAIN);
            return chunk;
        }
    }

    /** A structure manager that reports no structures, so terrain is not adapted around any. */
    static final class NoStructures extends StructureManager {
        NoStructures(long seed) {
            super(null, new WorldOptions(seed, true, false), null);
        }

        @Override
        public List<StructureStart> startsForStructure(int sectionX, int sectionZ, Predicate<Structure> matcher) {
            return List.of();
        }

        @Override
        public List<StructureStart> startsForStructure(int sectionX, int sectionZ, Structure structure) {
            return List.of();
        }
    }
}
