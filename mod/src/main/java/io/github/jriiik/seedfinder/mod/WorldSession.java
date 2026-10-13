package io.github.jriiik.seedfinder.mod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.jriiik.seedfinder.Seeds;
import io.github.jriiik.seedfinder.finder.SeedCheck;
import io.github.jriiik.seedfinder.finder.TypedSeedSearch;
import io.github.jriiik.seedfinder.finder.WorldSeedSearch;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final List<NotedStructure> structures = new ArrayList<>();
    /**
     * Structures automatic recognition must not note again: removed by the player, or recognized starts that
     * contradicted another note in the same placement region.
     */
    private final List<NotedStructure> removed = new ArrayList<>();
    /** Recognized starts left out this session because the notes already have that set elsewhere in the region. */
    private final List<String> disagreements = new ArrayList<>();
    private int removals;
    private final List<ScannedChunk> bedrock = new ArrayList<>();
    /** The typed-seed search ({@link TypedSeedSearch}): where it resumes, and the seed once one matched. */
    private int typedSeedPieces;
    private OptionalLong typedSeed = OptionalLong.empty();
    /** How the notes fit the typed seed: checked by the search runner whenever the notes change. */
    private SeedCheck typedSeedCheck;
    /** Typed-seed progress not saved yet (it doesn't count as a change of the notes). */
    private boolean progressToSave;
    /** Increases when all notes are removed, so that work started before is dropped. */
    private int generation;

    /** Increases with every change, so a search result can tell whether it is still up to date. */
    private int version;
    private int savedVersion;
    private WorldSeedSearch.Result result;
    private int resultVersion = -1;
    private String resultError;

    WorldSession(String world) {
        this.world = world;
    }

    /** How many pieces of the typed-seed search are done (where it continues). */
    int typedSeedPieces() {
        return typedSeedPieces;
    }

    /**
     * The seed the typed-seed search found: it was typed, and its hash is the hashed seed the server sent. It is the
     * world's seed unless the server sends a made-up hashed seed; see {@link #typedSeedCheck()}.
     */
    OptionalLong typedSeed() {
        return typedSeed;
    }

    /** How the notes fit the typed seed, once checked. */
    Optional<SeedCheck> typedSeedCheck() {
        return Optional.ofNullable(typedSeedCheck);
    }

    /** Notes how the notes fit the typed seed (main thread); a check of another seed is ignored. */
    void setTypedSeedCheck(SeedCheck check) {
        if (typedSeed.isEmpty() || typedSeed.getAsLong() != check.worldSeed()) {
            return;
        }
        if (typedSeedCheck == null || typedSeedCheck.verdict() != check.verdict()) {
            progressToSave = true; // the notes file says what the notes make of the seed
        }
        typedSeedCheck = check;
    }

    /** Counts "Remove all notes", so work started before it can be dropped. */
    int generation() {
        return generation;
    }

    /**
     * True in a single-player world: the game itself makes the hashed seed from the real seed there, so a typed seed
     * found from it is the world's even when some notes disagree (then those notes are wrong).
     */
    boolean hashedSeedIsGenuine() {
        return world.startsWith("singleplayer:");
    }

    /** True when the typed-seed search has its answer: the seed, or that the seed wasn't typed. */
    boolean typedSeedSearchDone() {
        return typedSeed.isPresent() || typedSeedPieces >= TypedSeedSearch.PIECES;
    }

    /** Notes where the typed-seed search got to; a found seed or a finished search counts as a change. */
    void setTypedSeedSearch(TypedSeedSearch.Outcome outcome) {
        boolean answer = outcome.complete() && !typedSeedSearchDone();
        typedSeedPieces = Math.max(typedSeedPieces, outcome.piecesDone());
        if (outcome.seed().isPresent() && !outcome.seed().equals(typedSeed)) {
            typedSeed = outcome.seed();
            typedSeedCheck = null;
        }
        if (answer) {
            version++;
        } else {
            progressToSave = true;
        }
    }

    /**
     * The world seed: proved by the seed search (structures with the hashed seed, or bedrock), or the typed seed once
     * the notes are checked and don't contradict it (or the hashed seed is genuine).
     */
    OptionalLong worldSeed() {
        if (result != null && result.worldSeed().isPresent()) {
            return result.worldSeed();
        }
        boolean trusted = typedSeedCheck != null && (typedSeedCheck.verdict() != SeedCheck.Verdict.CONTRADICTED || hashedSeedIsGenuine());
        return trusted ? typedSeed : OptionalLong.empty();
    }

    /** The key the notes are kept under (see {@link WorldIdentity#key}). */
    String world() {
        return world;
    }

    /** The world's address or save name, without the hashed seed. */
    String displayName() {
        int bracket = world.lastIndexOf(" [");
        return bracket < 0 ? world : world.substring(0, bracket);
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
            if (hashedSeed.isPresent()) {
                // The typed-seed search belongs to the old value.
                typedSeedPieces = 0;
                typedSeed = OptionalLong.empty();
                typedSeedCheck = null;
            }
            hashedSeed = OptionalLong.of(value);
            version++;
        }
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

    /** Removes a structure the player picked, if it is still there; recognition will not note it again. */
    void removeStructure(NotedStructure structure) {
        if (structures.remove(structure)) {
            removed.add(structure);
            disagreements.clear();
            removals++;
            version++;
        }
    }

    /** Counts the player's removals, so recognition can offer starts again that disagreed with a removed note. */
    int removals() {
        return removals;
    }

    /** What happened to a structure offered by automatic recognition. */
    enum Offer {
        ADDED,
        /** Already noted in that chunk. */
        KNOWN,
        /** The player removed it before (or it contradicted another recognized start). */
        LEFT_OUT,
        /** The notes have this set in another chunk of the same region, where only one start fits. */
        DISAGREES,
    }

    /**
     * Adds a structure recognized automatically. A structure set starts at most once per region of
     * {@code spacing} x {@code spacing} chunks, so a recognized start in the same region as another note of its set
     * can't both be right: an earlier recognized note is then taken back (neither is noted again), and a note from
     * /locate or the player is kept and the disagreement is shown, so the player can decide.
     */
    Offer addRecognized(NotedStructure structure, int spacing) {
        int chunkX = Math.floorDiv(structure.x(), 16);
        int chunkZ = Math.floorDiv(structure.z(), 16);
        if (removed.stream().anyMatch(s -> s.set().equals(structure.set()) && Math.floorDiv(s.x(), 16) == chunkX && Math.floorDiv(s.z(), 16) == chunkZ)) {
            return Offer.LEFT_OUT;
        }
        List<NotedStructure> sameRegion = structures.stream().filter(s -> s.set().equals(structure.set())
            && Math.floorDiv(Math.floorDiv(s.x(), 16), spacing) == Math.floorDiv(chunkX, spacing)
            && Math.floorDiv(Math.floorDiv(s.z(), 16), spacing) == Math.floorDiv(chunkZ, spacing)).toList();
        if (sameRegion.stream().anyMatch(s -> Math.floorDiv(s.x(), 16) == chunkX && Math.floorDiv(s.z(), 16) == chunkZ)) {
            return Offer.KNOWN;
        }
        if (sameRegion.isEmpty()) {
            structures.add(structure);
            version++;
            return Offer.ADDED;
        }
        List<NotedStructure> recognizedBefore = sameRegion.stream().filter(s -> s.source().equals(structure.source())).toList();
        if (!recognizedBefore.isEmpty()) {
            structures.removeAll(recognizedBefore);
            removed.addAll(recognizedBefore);
            removed.add(structure);
            version++;
            return Offer.LEFT_OUT;
        }
        NotedStructure other = sameRegion.get(0);
        String text = "Recognized " + structure.set() + " at " + structure.x() + ", " + structure.z() + " is left out: "
            + (other.source().equals("from /locate")
                ? "/locate places it at " + other.x() + ", " + other.z() + ", and /locate is always right."
                : "your note has it at " + other.x() + ", " + other.z() + " (" + other.source() + ") and only one can be right."
                    + " If your note is wrong, remove it.");
        if (!disagreements.contains(text)) {
            disagreements.add(text);
            version++;
        }
        return Offer.DISAGREES;
    }

    /** Recognized starts that were left out because they disagree with a note from /locate or the player. */
    List<String> disagreements() {
        return List.copyOf(disagreements);
    }

    /**
     * A start that recognition can't trust (its region holds contradicting matches, or a command put it there):
     * takes back an automatic note of it, if any, also one from an earlier session, and never notes it again.
     */
    void distrust(String set, int x, int z) {
        int chunkX = Math.floorDiv(x, 16);
        int chunkZ = Math.floorDiv(z, 16);
        boolean changed = structures.removeIf(s -> s.set().equals(set) && s.source().equals(StructureRecognition.SOURCE)
            && Math.floorDiv(s.x(), 16) == chunkX && Math.floorDiv(s.z(), 16) == chunkZ);
        if (removed.stream().noneMatch(s -> s.set().equals(set) && Math.floorDiv(s.x(), 16) == chunkX && Math.floorDiv(s.z(), 16) == chunkZ)) {
            removed.add(new NotedStructure(set, chunkX * 16, chunkZ * 16, "not trusted"));
            changed = true;
        }
        if (changed) {
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
        disagreements.clear();
        typedSeedPieces = 0;
        typedSeed = OptionalLong.empty();
        typedSeedCheck = null;
        bedrock.clear();
        hashedSeed = OptionalLong.empty();
        generation++;
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
        typedSeed.ifPresent(value -> lines.add(typedSeedComment(value)));
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

    private String typedSeedComment(long seed) {
        if (hashedSeedIsGenuine()) {
            return "# The world seed is " + seed + ": it was typed, and its hash is the hashed seed of this single-player world.";
        }
        if (typedSeedCheck == null) {
            return "# The hashed seed is the one of the typed seed " + seed + " (not checked against these notes yet).";
        }
        return switch (typedSeedCheck.verdict()) {
            case CONFIRMED -> "# The world seed is " + seed + ": it was typed, its hash is the hashed seed, and these notes fit it.";
            case UNCONFIRMED -> "# The world seed is " + seed + " (it was typed, and its hash is the hashed seed), unless the server sends a "
                + "made-up hashed seed: these notes don't confirm it yet.";
            case CONTRADICTED -> "# The hashed seed is the one of the typed seed " + seed + ", but these notes don't fit that seed: the server "
                + "probably sends a made-up hashed seed.";
        };
    }

    boolean needsSaving() {
        return savedVersion != version || progressToSave;
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
        json.addProperty("typed_seed_layout", TypedSeedSearch.LAYOUT);
        json.addProperty("typed_seed_pieces", typedSeedPieces);
        typedSeed.ifPresent(value -> json.addProperty("typed_seed", Long.toString(value)));
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
        progressToSave = false;
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

    /**
     * Loads what was saved for this world, or starts empty (collecting switched off). Notes saved by earlier versions
     * of the mod under the address alone are picked up when their hashed seed is the same (or wasn't known).
     */
    static WorldSession load(Path folder, String world, String identity, long hashedSeed) {
        if (!Files.isRegularFile(folder.resolve(fileName(world) + ".json")) && Files.isRegularFile(folder.resolve(fileName(identity) + ".json"))) {
            WorldSession earlier = load(folder, identity);
            if (earlier.hashedSeed.isEmpty() || earlier.hashedSeed.getAsLong() == hashedSeed) {
                WorldSession moved = load(folder, identity, world);
                moved.version++;
                return moved;
            }
        }
        return load(folder, world);
    }

    static WorldSession load(Path folder, String world) {
        return load(folder, world, world);
    }

    /**
     * The typed-seed search can always be done again, so anything doubtful starts it over instead of losing the notes:
     * progress saved for another layout of pieces, a seed whose hash is not the hashed seed, values that don't read.
     */
    private static void readTypedSeedSearch(JsonObject json, WorldSession session) {
        try {
            if (json.has("typed_seed_pieces") && json.has("typed_seed_layout") && json.get("typed_seed_layout").getAsInt() == TypedSeedSearch.LAYOUT) {
                session.typedSeedPieces = Math.clamp(json.get("typed_seed_pieces").getAsInt(), 0, TypedSeedSearch.PIECES);
            }
            if (json.has("typed_seed")) {
                long seed = Long.parseLong(json.get("typed_seed").getAsString());
                if (session.hashedSeed.isPresent() && Seeds.hashedSeed(seed) == session.hashedSeed.getAsLong()) {
                    session.typedSeed = OptionalLong.of(seed);
                } else {
                    session.typedSeedPieces = 0;
                }
            }
            if (session.hashedSeed.isEmpty()) {
                session.typedSeedPieces = 0; // progress only means something for the hashed seed it was made for
            }
        } catch (RuntimeException e) {
            SeedFinderClient.LOG.warn("Could not read the typed-seed search of {}, starting it over", session.world, e);
            session.typedSeedPieces = 0;
            session.typedSeed = OptionalLong.empty();
        }
    }

    /** Reads the notes saved under {@code savedAs} into a session for {@code world}. */
    private static WorldSession load(Path folder, String savedAs, String world) {
        WorldSession session = new WorldSession(world);
        Path file = folder.resolve(fileName(savedAs) + ".json");
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
            readTypedSeedSearch(json, session);
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
