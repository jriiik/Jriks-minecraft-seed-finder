package io.github.jriiik.seedfinder.mod;

import io.github.jriiik.seedfinder.recognize.BlockView;
import io.github.jriiik.seedfinder.recognize.RecognizedStarts;
import io.github.jriiik.seedfinder.recognize.StartPieceRecognizer;
import io.github.jriiik.seedfinder.version.VersionData;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Recognizes villages, pillager outposts, trial chambers, ancient cities, trail ruins and camps from the blocks of
 * loaded chunks, and notes their start chunks (see {@link StartPieceRecognizer} for how). Runs on the game's main
 * thread in small steps, so it never holds up a frame for long.
 *
 * <p>Underground structures can only be seen when the server sends the real blocks; servers that hide them
 * (anti-xray) simply mean they are not found. The mod never tries to get around that.
 */
final class StructureRecognition {

    static final String SOURCE = "recognized automatically";
    /** Work at most this long per tick (2 ms of the 50 ms a tick has). */
    private static final long BUDGET_NANOS = 2_000_000L;
    /** Placements waiting for chunks to load are checked again every 2 seconds. */
    private static final int RETRY_TICKS = 40;
    private static final int MAX_WAITING = 20_000;
    private static final int MAX_SEEN = 500_000;

    /** The blueprints, read once from the game's files in the background. */
    private static CompletableFuture<StartPieceRecognizer> recognizer;

    private final VersionData data;
    /** The dimensions the recognized structure sets can appear in. */
    private final Set<String> dimensions;
    private ClientLevel level;
    private Set<Block> anchors;
    private final Map<Block, String> ids = new IdentityHashMap<>();
    private final Set<Long> chunksToScan = new LinkedHashSet<>();
    private final ArrayDeque<StartPieceRecognizer.Candidate> toCheck = new ArrayDeque<>();
    private final ArrayDeque<StartPieceRecognizer.Candidate> waiting = new ArrayDeque<>();
    private final Set<StartPieceRecognizer.Candidate> seen = new HashSet<>();
    private final RecognizedStarts starts = new RecognizedStarts();
    private final Set<RecognizedStarts.Start> noted = new HashSet<>();
    private boolean scanAllLoaded = true;
    private int ticks;

    StructureRecognition(VersionData data) {
        this.data = data;
        Set<String> found = new HashSet<>();
        for (String set : StartPieceLibrary.SETS) {
            data.structureSet(set).ifPresent(structureSet -> found.addAll(data.dimensionsOf(structureSet)));
        }
        this.dimensions = Set.copyOf(found);
    }

    /** Starts reading the blueprints, once. */
    static synchronized CompletableFuture<StartPieceRecognizer> recognizer(VersionData data) {
        if (recognizer == null) {
            recognizer = CompletableFuture.supplyAsync(() -> {
                StartPieceLibrary.Library library = StartPieceLibrary.load(data, StartPieceLibrary.GAME);
                library.problems().forEach(problem -> SeedFinderClient.LOG.warn("Structure recognition: {}", problem));
                SeedFinderClient.LOG.info("Structure recognition: {} start pieces of {} structure sets", library.pieces().size(),
                    library.pieces().stream().map(piece -> piece.structureSet()).distinct().count());
                return new StartPieceRecognizer(library.pieces());
            });
            recognizer = recognizer.exceptionally(error -> {
                SeedFinderClient.LOG.warn("Structure recognition is not available", error);
                return null;
            });
        }
        return recognizer;
    }

    /** Whether reading the blueprints failed (recognition is then off). */
    static synchronized boolean failed() {
        return recognizer != null && recognizer.isDone() && recognizer.getNow(null) == null;
    }

    /** The starts found so far in this world (for the in-game test). */
    List<RecognizedStarts.Start> starts() {
        return starts.starts();
    }

    /** Chunk loaded: remember to look for anchor blocks in it. */
    void chunkLoaded(ClientLevel level, LevelChunk chunk) {
        switchLevel(level);
        chunksToScan.add(chunk.getPos().pack());
    }

    /** Collecting was just switched on: look at every chunk that is already loaded. */
    void scanAllLoaded() {
        scanAllLoaded = true;
    }

    private void switchLevel(ClientLevel level) {
        if (this.level != level) {
            this.level = level;
            chunksToScan.clear();
            toCheck.clear();
            waiting.clear();
            seen.clear();
            scanAllLoaded = true;
        }
    }

    /** A little work every tick (while collecting): check placements, then look through newly loaded chunks. */
    void tick(Minecraft minecraft, WorldSession session) {
        ticks++;
        StartPieceRecognizer pieces = recognizer(data).getNow(null);
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        switchLevel(minecraft.level);
        if (pieces == null || !inStructureDimension()) {
            return;
        }
        if (scanAllLoaded) {
            scanAllLoaded = false;
            int radius = minecraft.options.getEffectiveRenderDistance() + 2;
            int centerX = minecraft.player.blockPosition().getX() >> 4;
            int centerZ = minecraft.player.blockPosition().getZ() >> 4;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (level.getChunkSource().getChunk(centerX + dx, centerZ + dz, ChunkStatus.FULL, false) != null) {
                        chunksToScan.add(ChunkPos.pack(centerX + dx, centerZ + dz));
                    }
                }
            }
        }
        if (anchors == null) {
            anchors = Collections.newSetFromMap(new IdentityHashMap<>());
            for (String id : pieces.anchorBlocks()) {
                BuiltInRegistries.BLOCK.getOptional(Identifier.parse(id)).ifPresent(anchors::add);
            }
        }
        if (ticks % RETRY_TICKS == 0) {
            toCheck.addAll(waiting);
            waiting.clear();
        }
        long end = System.nanoTime() + BUDGET_NANOS;
        View view = new View();
        while (System.nanoTime() < end) {
            if (!toCheck.isEmpty()) {
                check(pieces, toCheck.poll(), view, session);
            } else if (!chunksToScan.isEmpty()) {
                long pos = chunksToScan.iterator().next();
                chunksToScan.remove(pos);
                LevelChunk chunk = level.getChunkSource().getChunk(ChunkPos.getX(pos), ChunkPos.getZ(pos), ChunkStatus.FULL, false);
                if (chunk != null) {
                    scan(pieces, chunk);
                }
            } else {
                break;
            }
        }
    }

    private boolean inStructureDimension() {
        return dimensions.contains(level.dimension().identifier().toString());
    }

    /** Finds anchor blocks in a chunk; only sections whose palette may hold one are looked at block by block. */
    private void scan(StartPieceRecognizer pieces, LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            if (section.hasOnlyAir() || !section.maybeHas(state -> anchors.contains(state.getBlock()))) {
                continue;
            }
            int minY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (anchors.contains(state.getBlock())) {
                            for (StartPieceRecognizer.Candidate candidate : pieces.candidates(id(state.getBlock()), minX + x, minY + y, minZ + z)) {
                                if (seen.size() >= MAX_SEEN) {
                                    seen.clear();
                                }
                                if (seen.add(candidate)) {
                                    toCheck.add(candidate);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private void check(StartPieceRecognizer pieces, StartPieceRecognizer.Candidate candidate, View view, WorldSession session) {
        StartPieceRecognizer.Check check = pieces.check(candidate, view);
        switch (check.verdict()) {
            case NOT_LOADED -> {
                if (waiting.size() >= MAX_WAITING) {
                    waiting.poll();
                }
                waiting.add(candidate);
            }
            case NO_MATCH -> {
            }
            case MATCH -> {
                SeedFinderClient.LOG.info("Recognized {} starting in chunk {}, {} ({} turned {}; {}/{} blocks, {}/{} built blocks)",
                    candidate.piece().structureSet(), candidate.chunkX(), candidate.chunkZ(), candidate.piece().name(), candidate.rotation(),
                    check.matched(), candidate.piece().blockCount(), check.builtMatched(), candidate.piece().builtCount());
                if (starts.add(candidate)) {
                    updateNotes(session);
                }
            }
        }
    }

    /** Notes new starts, and takes back starts whose region turned out to hold contradicting matches. */
    private void updateNotes(WorldSession session) {
        for (RecognizedStarts.Start start : starts.starts()) {
            if (noted.add(start)) {
                session.addRecognized(new WorldSession.NotedStructure(start.structureSet(), start.chunkX() * 16, start.chunkZ() * 16, SOURCE));
            }
        }
        List<RecognizedStarts.Start> conflicting = new ArrayList<>(starts.conflicting());
        for (RecognizedStarts.Start start : conflicting) {
            SeedFinderClient.LOG.warn("Structure recognition: matches disagree near {} start in chunk {}, {}; noting none there",
                start.structureSet(), start.chunkX(), start.chunkZ());
            if (noted.remove(start)) {
                session.removeStructure(start.structureSet(), start.chunkX() * 16, start.chunkZ() * 16, SOURCE);
            }
        }
    }

    private String id(Block block) {
        return ids.computeIfAbsent(block, key -> BuiltInRegistries.BLOCK.getKey(key).toString());
    }

    /** The loaded chunks of the current level, as recognition sees them. */
    private final class View implements BlockView {
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        private LevelChunk chunk;

        @Override
        public boolean isLoaded(int chunkX, int chunkZ) {
            return level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) != null;
        }

        @Override
        public String blockAt(int x, int y, int z) {
            if (chunk == null || chunk.getPos().x() != x >> 4 || chunk.getPos().z() != z >> 4) {
                chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
                if (chunk == null) {
                    return null;
                }
            }
            return id(chunk.getBlockState(pos.set(x, y, z)).getBlock());
        }
    }
}
