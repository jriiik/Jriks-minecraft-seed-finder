package io.github.jriiik.seedfinder.mod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.jriiik.seedfinder.finder.WorldSeedSearch;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * What the mod knows about one world: whether collecting is switched on, the structures and bedrock noted so far,
 * the hashed seed, and the latest search result. Changed only on the game's main thread.
 *
 * <p>It is saved in the config folder as JSON (the mod's own memory) and as a notes file in the format the
 * command-line app reads ({@code seedfinder find --file <notes file>}).
 */
final class WorldSession {

    /** A structure noted in this world: its set, a block in its start chunk, and how it was found. */
    record NotedStructure(String set, int x, int z, String source) {
    }

    /** At most this many chunks of bedrock per dimension: one chunk is nearly always enough. */
    static final int MAX_BEDROCK_CHUNKS = 4;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final String world;
    private boolean collecting;
    private boolean showStatus = true;
    private OptionalLong hashedSeed = OptionalLong.empty();
    /** True when the server sent a different hashed seed than the one stored with these notes (this session). */
    private boolean hashedSeedChanged;
    private final List<NotedStructure> structures = new ArrayList<>();
    /** Structures the player removed; automatic recognition doesn't add them again. */
    private final List<NotedStructure> removed = new ArrayList<>();
    private final List<ScannedChunk> bedrock = new ArrayList<>();

    /** Increases with every change, so a search result can tell whether it is still up to date. */
    private int version;
    private int savedVersion;
    private WorldSeedSearch.Result result;
    private int resultVersion = -1;
    private String resultError;

    WorldSession(String world) {
        this.world = world;
    }

    String world() {
        return world;
    }

    boolean collecting() {
        return collecting;
    }

    void setCollecting(boolean collecting) {
        this.collecting = collecting;
        version++;
    }

    boolean showStatus() {
        return showStatus;
    }

    void setShowStatus(boolean showStatus) {
        this.showStatus = showStatus;
        version++;
    }

    OptionalLong hashedSeed() {
        return hashedSeed;
    }

    void setHashedSeed(long value) {
        if (hashedSeed.isEmpty() || hashedSeed.getAsLong() != value) {
            hashedSeedChanged |= hashedSeed.isPresent();
            hashedSeed = OptionalLong.of(value);
            version++;
        }
    }

    /**
     * Whether the hashed seed changed while these notes were kept: the world behind this address may have been reset
     * or replaced, and then the older notes belong to a different world.
     */
    boolean hashedSeedChanged() {
        return hashedSeedChanged;
    }

    List<NotedStructure> structures() {
        return List.copyOf(structures);
    }

    /** Adds a structure unless the same structure set already has one in that chunk; returns whether it was added. */
    boolean addStructure(NotedStructure structure) {
        boolean known = structures.stream().anyMatch(s -> s.set().equals(structure.set())
            && Math.floorDiv(s.x(), 16) == Math.floorDiv(structure.x(), 16) && Math.floorDiv(s.z(), 16) == Math.floorDiv(structure.z(), 16));
        if (known) {
            return false;
        }
        structures.add(structure);
        version++;
        return true;
    }

    /** Removes a structure the player picked; recognition will not note it again. */
    void removeStructure(int index) {
        removed.add(structures.remove(index));
        version++;
    }

    /** Adds a structure recognized automatically, unless the player removed it before. */
    boolean addRecognized(NotedStructure structure) {
        boolean wasRemoved = removed.stream().anyMatch(s -> s.set().equals(structure.set())
            && Math.floorDiv(s.x(), 16) == Math.floorDiv(structure.x(), 16) && Math.floorDiv(s.z(), 16) == Math.floorDiv(structure.z(), 16));
        return !wasRemoved && addStructure(structure);
    }

    /** Removes a structure noted from the given source in that chunk, if there is one. */
    void removeStructure(String set, int x, int z, String source) {
        if (structures.removeIf(s -> s.set().equals(set) && s.source().equals(source)
            && Math.floorDiv(s.x(), 16) == Math.floorDiv(x, 16) && Math.floorDiv(s.z(), 16) == Math.floorDiv(z, 16))) {
            version++;
        }
    }

    List<ScannedChunk> bedrock() {
        return List.copyOf(bedrock);
    }

    int bedrockChunks(String dimension) {
        return (int) bedrock.stream().filter(chunk -> chunk.dimension().equals(dimension)).count();
    }

    /** Keeps the chunk if this dimension still needs bedrock; returns whether it was kept. */
    boolean offerBedrock(ScannedChunk chunk) {
        if (bedrockChunks(chunk.dimension()) >= MAX_BEDROCK_CHUNKS || bedrock.stream().anyMatch(c ->
            c.dimension().equals(chunk.dimension()) && c.chunkX() == chunk.chunkX() && c.chunkZ() == chunk.chunkZ())) {
            return false;
        }
        bedrock.add(chunk);
        version++;
        return true;
    }

    /** Forgets everything noted in this world (collecting stays as it is); for a world that was reset or replaced. */
    void clearAll() {
        structures.clear();
        removed.clear();
        bedrock.clear();
        hashedSeed = OptionalLong.empty();
        hashedSeedChanged = false;
        version++;
    }

    int version() {
        return version;
    }

    WorldSeedSearch.Result result() {
        return result;
    }

    String resultError() {
        return resultError;
    }

    boolean resultIsCurrent() {
        return resultVersion == version;
    }

    void setResult(int forVersion, WorldSeedSearch.Result result, String error) {
        this.result = result;
        this.resultError = error;
        this.resultVersion = forVersion;
    }

    /** The structures as notes lines (what the structure search uses). */
    List<String> structureLines() {
        List<String> lines = new ArrayList<>();
        for (NotedStructure structure : structures) {
            lines.add(structure.set() + " " + structure.x() + " " + structure.z());
        }
        return lines;
    }

    /** Everything as a notes file the command-line app can read. */
    List<String> notesLines() {
        List<String> lines = new ArrayList<>();
        lines.add("# Jrik's Seed Finder notes for " + world);
        lines.add("# Written by the mod. The command-line app reads this file too: seedfinder find --file <this file>");
        hashedSeed.ifPresent(value -> lines.add("hashed-seed " + value));
        lines.add("");
        for (NotedStructure structure : structures) {
            lines.add("# " + structure.source());
            lines.add(structure.set() + " " + structure.x() + " " + structure.z());
        }
        for (ScannedChunk chunk : bedrock) {
            lines.add("");
            lines.add("# bedrock of " + chunk.dimension() + " chunk " + chunk.chunkX() + ", " + chunk.chunkZ());
            lines.addAll(chunk.notesLines());
        }
        return lines;
    }

    boolean needsSaving() {
        return savedVersion != version;
    }

    /** File names: the world key with unusual characters replaced, plus a short hash so different keys never clash. */
    static String fileName(String world) {
        return world.replaceAll("[^A-Za-z0-9._-]", "_") + "-" + String.format("%08x", world.hashCode());
    }

    void save(Path folder) throws IOException {
        Files.createDirectories(folder);
        JsonObject json = new JsonObject();
        json.addProperty("world", world);
        json.addProperty("collecting", collecting);
        json.addProperty("show_status", showStatus);
        hashedSeed.ifPresent(value -> json.addProperty("hashed_seed", Long.toString(value)));
        json.add("structures", structuresJson(structures));
        json.add("removed_structures", structuresJson(removed));
        JsonArray bedrockArray = new JsonArray();
        for (ScannedChunk chunk : bedrock) {
            JsonObject c = new JsonObject();
            c.addProperty("dimension", chunk.dimension());
            c.addProperty("chunk_x", chunk.chunkX());
            c.addProperty("chunk_z", chunk.chunkZ());
            JsonObject layers = new JsonObject();
            chunk.layers().forEach((y, rows) -> {
                JsonArray array = new JsonArray();
                rows.forEach(array::add);
                layers.add(Integer.toString(y), array);
            });
            c.add("layers", layers);
            bedrockArray.add(c);
        }
        json.add("bedrock", bedrockArray);
        String base = fileName(world);
        Files.writeString(folder.resolve(base + ".json"), GSON.toJson(json) + "\n", StandardCharsets.UTF_8);
        Files.write(folder.resolve(base + ".txt"), notesLines(), StandardCharsets.UTF_8);
        savedVersion = version;
    }

    private static JsonArray structuresJson(List<NotedStructure> list) {
        JsonArray array = new JsonArray();
        for (NotedStructure structure : list) {
            JsonObject s = new JsonObject();
            s.addProperty("set", structure.set());
            s.addProperty("x", structure.x());
            s.addProperty("z", structure.z());
            s.addProperty("source", structure.source());
            array.add(s);
        }
        return array;
    }

    private static void readStructures(JsonObject json, String key, List<NotedStructure> into) {
        if (!json.has(key)) {
            return;
        }
        for (JsonElement element : json.getAsJsonArray(key)) {
            JsonObject s = element.getAsJsonObject();
            into.add(new NotedStructure(s.get("set").getAsString(), s.get("x").getAsInt(), s.get("z").getAsInt(), s.get("source").getAsString()));
        }
    }

    /** Loads what was saved for this world, or starts empty (collecting switched off). */
    static WorldSession load(Path folder, String world) {
        WorldSession session = new WorldSession(world);
        Path file = folder.resolve(fileName(world) + ".json");
        if (!Files.isRegularFile(file)) {
            return session;
        }
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            session.collecting = json.has("collecting") && json.get("collecting").getAsBoolean();
            session.showStatus = !json.has("show_status") || json.get("show_status").getAsBoolean();
            if (json.has("hashed_seed")) {
                session.hashedSeed = OptionalLong.of(Long.parseLong(json.get("hashed_seed").getAsString()));
            }
            readStructures(json, "structures", session.structures);
            readStructures(json, "removed_structures", session.removed);
            for (JsonElement element : json.getAsJsonArray("bedrock")) {
                JsonObject c = element.getAsJsonObject();
                Map<Integer, List<String>> layers = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> layer : c.getAsJsonObject("layers").entrySet()) {
                    List<String> rows = new ArrayList<>();
                    layer.getValue().getAsJsonArray().forEach(row -> rows.add(row.getAsString()));
                    layers.put(Integer.parseInt(layer.getKey()), rows);
                }
                session.bedrock.add(new ScannedChunk(c.get("dimension").getAsString(), c.get("chunk_x").getAsInt(), c.get("chunk_z").getAsInt(), layers));
            }
        } catch (IOException | RuntimeException e) {
            SeedFinderClient.LOG.warn("Could not read {}, starting over for this world", file, e);
            return new WorldSession(world);
        }
        session.version = 1;
        session.savedVersion = 1;
        return session;
    }
}
