package io.github.jriiik.seedfinder.biome;

import com.google.gson.JsonObject;
import io.github.jriiik.seedfinder.rng.XoroshiroRandom;
import io.github.jriiik.seedfinder.version.MinecraftVersion;
import io.github.jriiik.seedfinder.version.VersionDataLoader;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which biome the overworld generator puts where, for any world seed, bit for bit as Minecraft does it: six climate
 * values from noises (temperature, vegetation, continents, erosion, depth, ridges), then the closest entry of the
 * biome list. The biomes are those of the 4 x 4 x 4 block cells ("quarts") the game stores; the biome the F3 screen
 * shows for a block is picked among the quarts around it, see {@link BiomeZoom}.
 *
 * <p>The overworld biomes depend on all 64 bits of the seed, so they can tell apart seeds that share their lower 48
 * bits (the structure seed). Data is read once per version and preset, on first use.
 */
public final class OverworldBiomes {

    /** The world presets whose overworld has its own climate (the biome list is the same for all). */
    public enum Preset {
        NORMAL("minecraft:normal"),
        LARGE_BIOMES("minecraft:large_biomes"),
        AMPLIFIED("minecraft:amplified");

        private final String id;

        Preset(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        /** By id or name, like {@code minecraft:large_biomes}, {@code large_biomes} or {@code large-biomes}. */
        public static Optional<Preset> byName(String name) {
            String plain = name.trim().toLowerCase(Locale.ROOT).replace('-', '_');
            for (Preset preset : values()) {
                if (preset.id.equals(plain) || preset.id.equals("minecraft:" + plain)) {
                    return Optional.of(preset);
                }
            }
            return Optional.empty();
        }
    }

    private static final Map<String, OverworldBiomes> LOADED = new ConcurrentHashMap<>();
    private static final Map<String, BiomeParameters> PARAMETERS = new ConcurrentHashMap<>();

    private final ClimateFunctions functions;
    private final BiomeParameters parameters;
    private final int minQuartY;
    private final int maxQuartY;

    private OverworldBiomes(ClimateFunctions functions, BiomeParameters parameters, int minY, int height) {
        this.functions = functions;
        this.parameters = parameters;
        this.minQuartY = Math.floorDiv(minY, 4);
        this.maxQuartY = Math.floorDiv(minY + height, 4) - 1;
    }

    /** The overworld biomes of a version and preset. */
    public static OverworldBiomes of(MinecraftVersion version, Preset preset) {
        return LOADED.computeIfAbsent(version.displayName() + " " + preset.id(), key -> load(version.displayName(), preset));
    }

    private static OverworldBiomes load(String version, Preset preset) {
        JsonObject climate = VersionDataLoader.readBundled(version, "climate.json");
        JsonObject router = climate.getAsJsonObject("presets").getAsJsonObject(preset.id());
        if (router == null) {
            throw new IllegalStateException("No climate data for " + preset.id() + " in " + version);
        }
        ClimateFunctions functions = ClimateFunctions.parse(router, climate.getAsJsonObject("density_functions"), climate.getAsJsonObject("noises"));
        BiomeParameters parameters = PARAMETERS.computeIfAbsent(version,
            v -> BiomeParameters.parse(VersionDataLoader.readBundled(v, "biome_parameters.json").getAsJsonObject("overworld")));
        JsonObject overworld = climate.getAsJsonObject("overworld");
        return new OverworldBiomes(functions, parameters, overworld.get("min_y").getAsInt(), overworld.get("height").getAsInt());
    }

    ClimateFunctions functions() {
        return functions;
    }

    public BiomeParameters parameters() {
        return parameters;
    }

    /** The lowest quart y the overworld stores (the game clamps lower ones to it). */
    public int minQuartY() {
        return minQuartY;
    }

    /** The highest quart y the overworld stores. */
    public int maxQuartY() {
        return maxQuartY;
    }

    /** A sampler for one thread; set its seed with {@link Sampler#seed}. */
    public Sampler newSampler() {
        return new Sampler();
    }

    /**
     * Computes biomes for one world seed at a time. Reusing one sampler for many seeds is cheap: the noises of a seed
     * are made only when needed. Not thread-safe.
     */
    public final class Sampler {

        private final ClimateFunctions.State state = functions.newState();
        private final long[] target = new long[BiomeTree.DIMENSIONS];
        private boolean seeded;

        private Sampler() {
        }

        /** Switches to a world seed. */
        public Sampler seed(long worldSeed) {
            state.reset(new XoroshiroRandom(worldSeed).forkPositional());
            seeded = true;
            return this;
        }

        /** One climate value (see {@link ClimateFunctions#TEMPERATURE} ...) at a block position, as a float. */
        float climate(int function, int blockX, int blockY, int blockZ) {
            if (!seeded) {
                throw new IllegalStateException("no seed set");
            }
            return functions.sample(state, function, blockX, blockY, blockZ);
        }

        /** The climate point of a quart: the six values x 10000 (rounded towards zero, as the game does), then 0. */
        long[] target(int quartX, int quartY, int quartZ) {
            int x = quartX << 2;
            int y = quartY << 2;
            int z = quartZ << 2;
            for (int f = 0; f < ClimateFunctions.FIELDS.length; f++) {
                target[f] = (long) (climate(f, x, y, z) * 10000.0F);
            }
            target[6] = 0;
            return target;
        }

        /**
         * The biome (index in {@link BiomeParameters#biomes()}) of a quart, as the game finds it with no earlier
         * lookup on the thread. Where two biomes are exactly equally close, the game's answer can depend on its
         * earlier lookups; {@link #couldBe} accepts either.
         */
        public int biome(int quartX, int quartY, int quartZ) {
            return parameters.biomeOfEntry(parameters.search(target(quartX, quartY, quartZ), -1));
        }

        /** True if the game can generate this biome at this quart (it is among the closest biomes). */
        public boolean couldBe(int biome, int quartX, int quartY, int quartZ) {
            return parameters.isClosest(biome, target(quartX, quartY, quartZ));
        }

        /**
         * A whole chunk's biomes as the game fills them on a thread with no earlier lookups: sections upwards, then
         * x, y, z within each, each lookup starting from the previous answer.
         */
        public int[] chunkBiomes(int chunkX, int chunkZ) {
            int quarts = (maxQuartY - minQuartY + 1) * 16;
            int[] biomes = new int[quarts];
            int previous = -1;
            int i = 0;
            for (int sectionQuartY = minQuartY; sectionQuartY <= maxQuartY; sectionQuartY += 4) {
                for (int x = 0; x < 4; x++) {
                    for (int y = 0; y < 4; y++) {
                        for (int z = 0; z < 4; z++) {
                            previous = parameters.search(target(chunkX * 4 + x, sectionQuartY + y, chunkZ * 4 + z), previous);
                            biomes[i++] = parameters.biomeOfEntry(previous);
                        }
                    }
                }
            }
            return biomes;
        }
    }
}
