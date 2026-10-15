package io.github.jriiik.seedfinder.mod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.jriiik.seedfinder.Seeds;
import io.github.jriiik.seedfinder.biome.OverworldBiomes;
import io.github.jriiik.seedfinder.finder.BiomeEvidence;
import io.github.jriiik.seedfinder.finder.BiomeText;
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
 * What the mod knows about one world: whether collecting is switched on, the structures, bedrock and biomes noted so far,
 * the hashed seed, and the latest search result. Changed only on the game's main thread.
 *
 * <p>It is saved in the config folder as JSON (the mod's own memory) and as a notes file in the format the
 * command-line app reads ({@code seedfinder find --file <notes file>}).
 */
final class WorldSession {

    /** A chunk whose bedrock is left out (see {@link #leaveOutBedrock}). */
    record LeftOutChunk(String dimension, int chunkX, int chunkZ) {
    }

    /** A structure noted in this world: its set, a block in its start chunk, and how it was found. */
    record NotedStructure(String set, int x, int z, String source) {
    }

    /** The source of a left-out spot where a structure was placed with /place. */
    static final String PLACED = "placed with /place";

    /** At most this many chunks of bedrock per dimension: one chunk is nearly always enough. */
    static final int MAX_BEDROCK_CHUNKS = 4;

    /**
     * Biomes are noted in areas: the chunks within this many blocks of an area's first chunk, at most
     * {@link #MAX_BIOME_CHUNKS_PER_AREA} of them (one loaded area with two or more biomes proves a seed Minecraft picked
     * itself or a text seed).
     */
    static final int BIOME_AREA_RADIUS = 192;
    static final int MAX_BIOME_CHUNKS_PER_AREA = 100;
    /**
     * Biomes closer together than this are alike in every world, so only areas whose notes are this far apart count
     * separately (two such areas prove any seed); large-biome worlds need {@link #LARGE_BIOME_AREA_SPACING}.
     */
    static final int BIOME_AREA_SPACING = BiomeEvidence.groupSpacing(OverworldBiomes.Preset.NORMAL);
    static final int LARGE_BIOME_AREA_SPACING = BiomeEvidence.groupSpacing(OverworldBiomes.Preset.LARGE_BIOMES);
    /** Areas spaced for a normal world; then one more, far enough from one of them to count in a large-biome world. */
    static final int MAX_BIOME_AREAS = 4;
    /** How far an area's notes reach from its first chunk's corner, both ways together: 2 x the radius, plus a chunk's cells. */
    private static final int BIOME_AREA_REACH = 2 * BIOME_AREA_RADIUS + 12;

    /**
     * Once this many chunks of a dimension are left out (see {@link #leaveOutBedrock}), chunks within
     * {@link #LEFT_OUT_SPACING_CHUNKS} of one of them are not read: old land (an upgraded world's nether around its
     * portals) is one connected area, while new land farther away still is.
     */
    static final int LEFT_OUT_BEFORE_SPACING = 8;
    static final int LEFT_OUT_SPACING_CHUNKS = 8;
    /** At most this many left-out chunks are remembered (the oldest are forgotten). */
    static final int MAX_LEFT_OUT_BEDROCK_CHUNKS = 256;
    /** Characters that stand for biomes in the saved file (each file has its own list of biomes). */
    private static final String BIOME_CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ+/";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final String world;
    private boolean collecting;
    private boolean showStatus = true;
    /**
     * Whether ruined portals recognized in the nether are noted. Off unless the player says the world's nether was
     * made by Minecraft 1.18.2 or newer: from 1.16 to 1.18.1 nether portals used another grid, and such chunks keep
     * them there.
     */
    private boolean netherPortals;
    private OptionalLong hashedSeed = OptionalLong.empty();
    private final List<NotedStructure> structures = new ArrayList<>();
    /**
     * Structures automatic recognition must not note again: removed by the player, or recognized starts that
     * contradicted another note in the same placement region.
     */
    private final List<NotedStructure> removed = new ArrayList<>();
    /**
     * Spots where structures were placed with /place while collecting was off: kept only for this session (nothing
     * is saved before the player switches collecting on), and left out for good once collecting is switched on.
     */
    private final List<NotedStructure> placedWhileOff = new ArrayList<>();
    /** Recognized starts left out this session because the notes already have that set elsewhere in the region. */
    private final List<String> disagreements = new ArrayList<>();
    private int removals;
    private final List<ScannedChunk> bedrock = new ArrayList<>();
    /**
     * Chunks whose bedrock the search found unusable (nether chunks made before 1.18, or changed): left out of the
     * notes, never read again, and not counted against {@link #MAX_BEDROCK_CHUNKS}. Entries: dimension, chunk x, z.
     */
    private final List<LeftOutChunk> leftOutBedrock = new ArrayList<>();
    private final List<BiomeChunk> biomes = new ArrayList<>();
    /** The first chunk of each biome area (block x, z of its north-west corner). */
    private final List<int[]> biomeAreas = new ArrayList<>();
    /**
     * A biome the vanilla overworld doesn't have, once one was seen: then a data pack or a mod changes the world's
     * biomes, and biomes are not noted in this world.
     */
    private String foreignBiome;
    /** The typed-seed search ({@link TypedSeedSearch}): where it resumes, and the seed once one matched. */
    private int typedSeedPieces;
    private OptionalLong typedSeed = OptionalLong.empty();
    /** How the notes fit the typed seed: checked by the search runner whenever the notes change. */
    private SeedCheck typedSeedCheck;
    /** Typed-seed progress not saved yet (it doesn't count as a change of the notes). */
    private boolean progressToSave;
    /** The version at the last "Remove all notes": checks of the notes from before it are out of date. */
    private int clearedAt;

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

    /**
     * Notes how the notes of {@code forVersion} fit the typed seed (main thread); a check of another seed, or of notes
     * from before "Remove all notes", is ignored.
     */
    void setTypedSeedCheck(int forVersion, SeedCheck check) {
        if (typedSeed.isEmpty() || typedSeed.getAsLong() != check.worldSeed() || forVersion < clearedAt) {
            return;
        }
        if (typedSeedCheck == null || typedSeedCheck.verdict() != check.verdict()) {
            progressToSave = true; // the notes file says what the notes make of the seed
        }
        typedSeedCheck = check;
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
        if (typedSeedCheck != null && hashedSeedIsGenuine()) {
            return typedSeed; // single player: its hash is the world's own hashed seed
        }
        if (result != null && result.worldSeed().isPresent()) {
            return result.worldSeed();
        }
        boolean trusted = typedSeedCheck != null && typedSeedCheck.verdict() != SeedCheck.Verdict.CONTRADICTED;
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
        if (collecting) {
            List<NotedStructure> placed = List.copyOf(placedWhileOff);
            placedWhileOff.clear();
            placed.forEach(spot -> placed(spot.set(), spot.x(), spot.z()));
        }
    }

    boolean netherPortals() {
        return netherPortals;
    }

    void setNetherPortals(boolean netherPortals) {
        this.netherPortals = netherPortals;
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
        if (removed.stream().anyMatch(s -> s.set().equals(structure.set()) && Math.floorDiv(s.x(), 16) == chunkX && Math.floorDiv(s.z(), 16) == chunkZ)
            || wasPlaced(structure.set(), chunkX, chunkZ)) {
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
        distrust(set, x, z, "not trusted");
    }

    /**
     * A structure placed with /place (in the chunk holding x, z): it sits where a start of its set could be but
     * doesn't follow the seed, so it is never noted. While collecting is off it is only remembered for this session.
     */
    void placed(String set, int x, int z) {
        if (collecting) {
            distrust(set, x, z, PLACED);
        } else if (!wasPlaced(set, Math.floorDiv(x, 16), Math.floorDiv(z, 16))) {
            placedWhileOff.add(new NotedStructure(set, Math.floorDiv(x, 16) * 16, Math.floorDiv(z, 16) * 16, PLACED));
        }
    }

    /** Whether a structure of the set was placed with /place in this chunk (as far as the mod saw). */
    boolean wasPlaced(String set, int chunkX, int chunkZ) {
        return removed.stream().anyMatch(s -> s.source().equals(PLACED) && s.set().equals(set) && Math.floorDiv(s.x(), 16) == chunkX
            && Math.floorDiv(s.z(), 16) == chunkZ) || placedWhileOff.stream().anyMatch(s -> s.set().equals(set)
            && Math.floorDiv(s.x(), 16) == chunkX && Math.floorDiv(s.z(), 16) == chunkZ);
    }

    private void distrust(String set, int x, int z, String source) {
        int chunkX = Math.floorDiv(x, 16);
        int chunkZ = Math.floorDiv(z, 16);
        boolean changed = structures.removeIf(s -> s.set().equals(set) && s.source().equals(StructureRecognition.SOURCE)
            && Math.floorDiv(s.x(), 16) == chunkX && Math.floorDiv(s.z(), 16) == chunkZ);
        if (removed.stream().noneMatch(s -> s.set().equals(set) && Math.floorDiv(s.x(), 16) == chunkX && Math.floorDiv(s.z(), 16) == chunkZ
            && (s.source().equals(source) || !source.equals(PLACED)))) {
            removed.add(new NotedStructure(set, chunkX * 16, chunkZ * 16, source));
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

    /** Whether this dimension's bedrock is still wanted: fewer than {@link #MAX_BEDROCK_CHUNKS} kept. */
    boolean wantsBedrock(String dimension) {
        return bedrockChunks(dimension) < MAX_BEDROCK_CHUNKS;
    }

    /**
     * Whether this chunk's bedrock would be kept (cheap; the chunk isn't read): the dimension still needs bedrock, the
     * chunk isn't noted or left out, and it isn't next to many left-out chunks.
     */
    boolean wantsBedrock(String dimension, int chunkX, int chunkZ) {
        if (!wantsBedrock(dimension) || bedrock.stream().anyMatch(c -> c.dimension().equals(dimension) && c.chunkX() == chunkX && c.chunkZ() == chunkZ)) {
            return false;
        }
        int left = 0;
        boolean near = false;
        for (LeftOutChunk c : leftOutBedrock) {
            if (!c.dimension().equals(dimension)) {
                continue;
            }
            if (c.chunkX() == chunkX && c.chunkZ() == chunkZ) {
                return false;
            }
            left++;
            near |= Math.max(Math.abs(c.chunkX() - chunkX), Math.abs(c.chunkZ() - chunkZ)) <= LEFT_OUT_SPACING_CHUNKS;
        }
        return left < LEFT_OUT_BEFORE_SPACING || !near;
    }

    /** Keeps the chunk if its bedrock is wanted (see {@link #wantsBedrock(String, int, int)}); returns whether it was kept. */
    boolean offerBedrock(ScannedChunk chunk) {
        if (!wantsBedrock(chunk.dimension(), chunk.chunkX(), chunk.chunkZ())) {
            return false;
        }
        bedrock.add(chunk);
        version++;
        return true;
    }

    int leftOutBedrockChunks(String dimension) {
        return (int) leftOutBedrock.stream().filter(chunk -> chunk.dimension().equals(dimension)).count();
    }

    /**
     * Leaves out the bedrock of chunks the search found unusable (made by an older Minecraft version, or changed): their
     * notes are dropped and the chunks are not read again, so newer chunks can take their place. Main thread.
     */
    void leaveOutBedrock(String dimension, List<io.github.jriiik.seedfinder.world.ChunkPos> chunks) {
        for (io.github.jriiik.seedfinder.world.ChunkPos chunk : chunks) {
            LeftOutChunk left = new LeftOutChunk(dimension, chunk.x(), chunk.z());
            boolean dropped = bedrock.removeIf(c -> c.dimension().equals(dimension) && c.chunkX() == chunk.x() && c.chunkZ() == chunk.z());
            if (dropped && !leftOutBedrock.contains(left)) {
                leftOutBedrock.add(left);
                version++;
            }
        }
        while (leftOutBedrock.size() > MAX_LEFT_OUT_BEDROCK_CHUNKS) {
            leftOutBedrock.remove(0);
        }
    }

    List<BiomeChunk> biomes() {
        return List.copyOf(biomes);
    }

    int biomeAreas() {
        return biomeAreas.size();
    }

    int biomeChunks() {
        return biomes.size();
    }

    /** The biome a data pack or mod added, if one was seen (then biomes are not noted). */
    Optional<String> foreignBiome() {
        return Optional.ofNullable(foreignBiome);
    }

    /** The area this chunk belongs to, or -1. */
    private int biomeArea(int chunkX, int chunkZ) {
        for (int i = 0; i < biomeAreas.size(); i++) {
            long dx = chunkX * 16L - biomeAreas.get(i)[0];
            long dz = chunkZ * 16L - biomeAreas.get(i)[1];
            if (dx * dx + dz * dz <= (long) BIOME_AREA_RADIUS * BIOME_AREA_RADIUS) {
                return i;
            }
        }
        return -1;
    }

    /** Whether the biomes of this chunk would be noted (cheap; the chunk isn't read). */
    boolean wantsBiomes(int chunkX, int chunkZ) {
        if (foreignBiome != null || biomes.stream().anyMatch(c -> c.chunkX() == chunkX && c.chunkZ() == chunkZ)) {
            return false;
        }
        int area = biomeArea(chunkX, chunkZ);
        if (area >= 0) {
            return biomes.stream().filter(c -> biomeArea(c.chunkX(), c.chunkZ()) == area).count() < MAX_BIOME_CHUNKS_PER_AREA;
        }
        if (biomeAreas.size() > MAX_BIOME_AREAS || !biomeAreas.stream().allMatch(first -> apart(first, chunkX, chunkZ, BIOME_AREA_SPACING))) {
            return false;
        }
        // The last area only for large-biome worlds: far enough from one of the others to count there too.
        return biomeAreas.size() < MAX_BIOME_AREAS || biomeAreas.stream().anyMatch(first -> apart(first, chunkX, chunkZ, LARGE_BIOME_AREA_SPACING));
    }

    /**
     * Whether an area starting at this chunk is sure to have all its notes at least {@code spacing} blocks from those
     * of the area starting at {@code first}, measured between the boxes around their notes as the seed search does.
     */
    private static boolean apart(int[] first, int chunkX, int chunkZ, int spacing) {
        long gapX = Math.max(0, Math.abs(chunkX * 16L - first[0]) - BIOME_AREA_REACH);
        long gapZ = Math.max(0, Math.abs(chunkZ * 16L - first[1]) - BIOME_AREA_REACH);
        return gapX * gapX + gapZ * gapZ >= (long) spacing * spacing;
    }

    /** About how far (in blocks, rounded up) a new area's first chunk must be from the other areas' first chunks. */
    static int biomeAreaDistance(int spacing) {
        return (spacing + BIOME_AREA_REACH + 99) / 100 * 100;
    }

    /** Keeps the chunk's biomes if they are wanted (see {@link #wantsBiomes}); returns whether they were kept. */
    boolean offerBiomes(BiomeChunk chunk) {
        if (!wantsBiomes(chunk.chunkX(), chunk.chunkZ())) {
            return false;
        }
        if (biomeArea(chunk.chunkX(), chunk.chunkZ()) < 0) {
            biomeAreas.add(new int[] {chunk.chunkX() * 16, chunk.chunkZ() * 16});
        }
        biomes.add(chunk);
        version++;
        return true;
    }

    /** A biome the vanilla overworld doesn't have was seen: the biomes noted so far are dropped and no more are noted. */
    void foreignBiomeSeen(String biome) {
        if (foreignBiome == null) {
            foreignBiome = biome;
            biomes.clear();
            biomeAreas.clear();
            version++;
        }
    }

    /**
     * Forgets the structures and bedrock noted in this world (collecting stays as it is); for a world that was reset
     * or replaced. The hashed seed and the typed-seed search are kept: the hashed seed is part of the key these notes
     * are kept under, and the typed seed depends only on it. What recognition must not note (structures the player
     * removed, placed with /place, or not trusted) stays left out: noting one of them again could be wrong, while
     * leaving one out after a real reset only misses it.
     */
    void clearAll() {
        structures.clear();
        disagreements.clear();
        typedSeedCheck = null;
        bedrock.clear();
        leftOutBedrock.clear();
        biomes.clear();
        biomeAreas.clear();
        foreignBiome = null;
        version++;
        clearedAt = version;
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
        if (forVersion < clearedAt) {
            return; // a search of the notes from before "Remove all notes"
        }
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
        hashedSeed.ifPresent(value -> {
            lines.add("# If the seed was typed (as text or a short number): seedfinder typed-seed --file <this file>");
            lines.add("hashed-seed " + value);
        });
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
        for (int area = 0; area < biomeAreas.size(); area++) {
            int index = area;
            List<BiomeChunk> chunks = biomes.stream().filter(c -> biomeArea(c.chunkX(), c.chunkZ()) == index).toList();
            if (chunks.isEmpty()) {
                continue;
            }
            int minX = chunks.stream().mapToInt(BiomeChunk::chunkX).min().orElseThrow();
            int maxX = chunks.stream().mapToInt(BiomeChunk::chunkX).max().orElseThrow();
            int minZ = chunks.stream().mapToInt(BiomeChunk::chunkZ).min().orElseThrow();
            int maxZ = chunks.stream().mapToInt(BiomeChunk::chunkZ).max().orElseThrow();
            for (int y : BiomeScanner.HEIGHTS) {
                String[][] cells = new String[(maxZ - minZ + 1) * 4][(maxX - minX + 1) * 4];
                for (BiomeChunk chunk : chunks) {
                    for (int cz = 0; cz < 4; cz++) {
                        for (int cx = 0; cx < 4; cx++) {
                            cells[(chunk.chunkZ() - minZ) * 4 + cz][(chunk.chunkX() - minX) * 4 + cx] = chunk.cell(y, cx, cz);
                        }
                    }
                }
                lines.add("");
                lines.add("# biomes the game stored in the area around " + biomeAreas.get(area)[0] + ", " + biomeAreas.get(area)[1]
                    + " at y " + y + " (cells of 4 blocks)");
                lines.addAll(BiomeText.grid(minX * 16, y, minZ * 16, cells));
            }
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
            case CONFIRMED -> "# The world seed is " + seed + ": it was typed, its hash is the hashed seed, and these notes confirm it.";
            case UNCONFIRMED -> "# The world seed is " + seed + " (it was typed, and its hash is the hashed seed), unless the server sends a "
                + "made-up hashed seed: these notes don't confirm it yet.";
            case CONTRADICTED -> "# The hashed seed is the one of the typed seed " + seed + ", but these notes don't fit that seed (the server "
                + "may send a made-up hashed seed).";
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
        json.addProperty("nether_portals", netherPortals);
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
        JsonArray leftOut = new JsonArray();
        for (LeftOutChunk chunk : leftOutBedrock) {
            JsonObject c = new JsonObject();
            c.addProperty("dimension", chunk.dimension());
            c.addProperty("chunk_x", chunk.chunkX());
            c.addProperty("chunk_z", chunk.chunkZ());
            leftOut.add(c);
        }
        json.add("left_out_bedrock", leftOut);
        List<String> palette = new ArrayList<>();
        JsonArray biomeArray = new JsonArray();
        for (BiomeChunk chunk : biomes) {
            JsonObject c = new JsonObject();
            c.addProperty("chunk_x", chunk.chunkX());
            c.addProperty("chunk_z", chunk.chunkZ());
            JsonObject levels = new JsonObject();
            chunk.levels().forEach((y, cells) -> {
                StringBuilder text = new StringBuilder();
                for (String biome : cells) {
                    if (!palette.contains(biome)) {
                        palette.add(biome);
                    }
                    text.append(BIOME_CHARS.charAt(palette.indexOf(biome)));
                }
                levels.addProperty(Integer.toString(y), text.toString());
            });
            c.add("levels", levels);
            biomeArray.add(c);
        }
        JsonArray paletteArray = new JsonArray();
        palette.forEach(paletteArray::add);
        json.add("biome_palette", paletteArray);
        json.add("biomes", biomeArray);
        JsonArray areas = new JsonArray();
        for (int[] center : biomeAreas) {
            JsonArray pair = new JsonArray();
            pair.add(center[0]);
            pair.add(center[1]);
            areas.add(pair);
        }
        json.add("biome_areas", areas);
        if (foreignBiome != null) {
            json.addProperty("foreign_biome", foreignBiome);
        }
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

    private static void readBiomes(JsonObject json, WorldSession session) {
        if (json.has("foreign_biome")) {
            session.foreignBiome = json.get("foreign_biome").getAsString();
        }
        if (!json.has("biomes")) {
            return;
        }
        List<String> palette = new ArrayList<>();
        json.getAsJsonArray("biome_palette").forEach(biome -> palette.add(biome.getAsString()));
        for (JsonElement element : json.getAsJsonArray("biome_areas")) {
            JsonArray pair = element.getAsJsonArray();
            session.biomeAreas.add(new int[] {pair.get(0).getAsInt(), pair.get(1).getAsInt()});
        }
        for (JsonElement element : json.getAsJsonArray("biomes")) {
            JsonObject c = element.getAsJsonObject();
            Map<Integer, List<String>> levels = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> level : c.getAsJsonObject("levels").entrySet()) {
                List<String> cells = new ArrayList<>();
                level.getValue().getAsString().chars().forEach(ch -> cells.add(palette.get(BIOME_CHARS.indexOf(ch))));
                levels.put(Integer.parseInt(level.getKey()), cells);
            }
            session.biomes.add(new BiomeChunk(c.get("chunk_x").getAsInt(), c.get("chunk_z").getAsInt(), levels));
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
            session.netherPortals = json.has("nether_portals") && json.get("nether_portals").getAsBoolean();
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
            if (json.has("left_out_bedrock")) {
                for (JsonElement element : json.getAsJsonArray("left_out_bedrock")) {
                    JsonObject c = element.getAsJsonObject();
                    session.leftOutBedrock.add(new LeftOutChunk(c.get("dimension").getAsString(), c.get("chunk_x").getAsInt(), c.get("chunk_z").getAsInt()));
                }
            }
            readBiomes(json, session);
        } catch (IOException | RuntimeException e) {
            SeedFinderClient.LOG.warn("Could not read {}, starting over for this world", file, e);
            return new WorldSession(world);
        }
        session.version = 1;
        session.savedVersion = 1;
        return session;
    }
}
