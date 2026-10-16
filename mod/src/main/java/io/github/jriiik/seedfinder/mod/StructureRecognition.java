package io.github.jriiik.seedfinder.mod;

import io.github.jriiik.seedfinder.recognize.BlockView;
import io.github.jriiik.seedfinder.recognize.RecognizedStarts;
import io.github.jriiik.seedfinder.recognize.RigidKind;
import io.github.jriiik.seedfinder.recognize.RigidLayout;
import io.github.jriiik.seedfinder.recognize.RigidRecognizer;
import io.github.jriiik.seedfinder.recognize.RigidTracker;
import io.github.jriiik.seedfinder.recognize.StartPieceRecognizer;
import io.github.jriiik.seedfinder.structure.RandomSpreadPlacement;
import io.github.jriiik.seedfinder.version.VersionData;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Recognizes structures from the blocks of loaded chunks and notes their start chunks: villages, pillager outposts,
 * trial chambers, ancient cities, trail ruins and camps by their start piece ({@link StartPieceRecognizer}), and
 * desert pyramids, jungle temples, swamp huts, igloos, shipwrecks, ocean ruins and ruined portals as a whole
 * ({@link RigidRecognizer}, run chunk by chunk by a {@link RigidTracker}). Runs on the game's main thread in small
 * steps, so it never holds up a frame for long.
 *
 * <p>Underground structures can only be seen when the server sends the real blocks; servers that hide them
 * (anti-xray) simply mean they are not found. The mod never tries to get around that.
 */
final class StructureRecognition {

    static final String SOURCE = "recognized automatically";
    /** Work at most this long per tick (2 ms of the 50 ms a tick has). */
    private static final long BUDGET_NANOS = 2_000_000L;
    /** Of that, judging rigid structures gets this much first (and what is left once nothing else is to do). */
    private static final long RIGID_BUDGET_NANOS = 1_000_000L;
    /** Placements waiting for chunks to load are checked again every 2 seconds. */
    private static final int RETRY_TICKS = 40;
    private static final int MAX_WAITING = 20_000;
    private static final int MAX_SEEN = 500_000;
    /** Blocks looked at between two checks of the time budget. */
    private static final int SCAN_SLICE = 512;
    /** Every 10 seconds, what was found far away is forgotten (beyond the render distance plus this many chunks). */
    private static final int FORGET_TICKS = 200;
    private static final int FORGET_MARGIN = 8;

    /** The blueprints, read once from the game's files in the background. */
    private static CompletableFuture<StartPieceRecognizer> recognizer;
    /** The rigid-structure layouts, built once from the game's files and code in the background. */
    private static CompletableFuture<RigidLibrary.Library> rigidLibrary;
    /**
     * A rigid-structure recognizer per dimension where rigid structures appear, made in the background with the
     * layouts (set before {@link #rigidLibrary} completes).
     */
    private static volatile Map<String, RigidRecognizer> rigidRecognizers = Map.of();

    private final VersionData data;
    /** The dimensions the recognized structure sets can appear in. */
    private final Set<String> dimensions;
    /** Each recognized set's spacing (the size of its placement regions, in chunks). */
    private final Map<String, Integer> spacing = new HashMap<>();
    private ClientLevel level;
    private Set<Block> anchors;
    private final Map<Block, String> ids = new IdentityHashMap<>();
    private final Set<Long> chunksToScan = new LinkedHashSet<>();
    private final ArrayDeque<StartPieceRecognizer.Candidate> toCheck = new ArrayDeque<>();
    private final ArrayDeque<StartPieceRecognizer.Candidate> waiting = new ArrayDeque<>();
    private final Set<StartPieceRecognizer.Candidate> seen = new HashSet<>();
    private final RecognizedStarts starts = new RecognizedStarts();
    /** Starts offered to the notes this session, and those that were added. */
    private final Set<RecognizedStarts.Start> offered = new HashSet<>();
    private final Set<RecognizedStarts.Start> noted = new HashSet<>();
    private final Set<RecognizedStarts.Start> distrusted = new HashSet<>();
    private final Set<RecognizedStarts.Start> setAside = new HashSet<>();
    private boolean scanAllLoaded = true;
    private int ticks;
    /** The chunk being scanned, and where in it. */
    private LevelChunk scanning;
    private int scanSection;
    private int scanBlock;
    /** The rigid structures of the current level: null when none appear in its dimension (or until ready). */
    private RigidTracker tracker;
    private boolean trackerMade;
    /** Whether the tracker was made with ruined portals in the nether noted (see {@link WorldSession#netherPortals}). */
    private boolean trackerNetherPortals;
    /** For the current tracker: each block's trigger class, or -1. */
    private final Map<Block, Integer> triggerClasses = new IdentityHashMap<>();
    /** The trigger blocks found so far in the chunk being scanned. */
    private int[] found = new int[64];
    private int foundCount;
    private final Predicate<BlockState> isWanted = state -> anchors.contains(state.getBlock()) || triggerClass(state.getBlock()) >= 0;
    /** Starts left out because they disagreed with a note; offered again after the player removes a note. */
    private final Set<RecognizedStarts.Start> disagreed = new HashSet<>();
    private int removals;

    StructureRecognition(VersionData data) {
        this.data = data;
        Set<String> inDimensions = new HashSet<>();
        List<String> sets = new ArrayList<>(StartPieceLibrary.SETS);
        Arrays.stream(RigidKind.values()).forEach(kind -> sets.add(kind.structureSet()));
        for (String set : sets) {
            data.structureSet(set).ifPresent(structureSet -> {
                inDimensions.addAll(data.dimensionsOf(structureSet));
                if (structureSet.placement() instanceof RandomSpreadPlacement placement) {
                    spacing.put(set, placement.spacing());
                }
            });
        }
        this.dimensions = Set.copyOf(inDimensions);
    }

    /** Starts reading the blueprints, once (call on the main thread). */
    static synchronized CompletableFuture<StartPieceRecognizer> recognizer(VersionData data) {
        if (recognizer == null) {
            StartPieceLibrary.DataFiles files = StartPieceLibrary.vanilla(GameVersion.vanillaData(Minecraft.getInstance()));
            recognizer = CompletableFuture.supplyAsync(() -> {
                StartPieceLibrary.Library library = StartPieceLibrary.load(data, files);
                library.problems().forEach(problem -> SeedFinderClient.LOG.warn("Structure recognition: {}", problem));
                SeedFinderClient.LOG.info("Structure recognition: {} start pieces of {} structure sets", library.pieces().size(),
                    library.pieces().stream().map(piece -> piece.structureSet()).distinct().count());
                return new StartPieceRecognizer(library.pieces());
            });
            recognizer = recognizer.exceptionally(error -> {
                SeedFinderClient.LOG.warn("Structure recognition is not available", error);
                return new StartPieceRecognizer(List.of());
            });
        }
        return recognizer;
    }

    /** Starts building the rigid-structure layouts, once (call on the main thread, in a world). */
    static synchronized CompletableFuture<RigidLibrary.Library> rigidLibrary(VersionData data, RegistryAccess registries) {
        if (rigidLibrary == null) {
            StartPieceLibrary.DataFiles files = StartPieceLibrary.vanilla(GameVersion.vanillaData(Minecraft.getInstance()));
            rigidLibrary = CompletableFuture.supplyAsync(() -> {
                RigidLibrary.Library library = RigidLibrary.load(data, files, registries);
                library.problems().forEach(problem -> SeedFinderClient.LOG.warn("Structure recognition: {}", problem));
                SeedFinderClient.LOG.info("Structure recognition: {} layouts of {} rigid structure kinds", library.layouts().size(),
                    library.layouts().stream().map(RigidLayout::kind).distinct().count());
                Set<String> dimensions = new HashSet<>();
                for (RigidKind kind : RigidKind.values()) {
                    data.structureSet(kind.structureSet()).ifPresent(set -> dimensions.addAll(data.dimensionsOf(set)));
                }
                Map<String, RigidRecognizer> made = new HashMap<>();
                for (String dimension : dimensions) {
                    RigidRecognizer recognizer = RigidRecognizer.create(library.layouts(), data, dimension);
                    if (!recognizer.layouts().isEmpty()) {
                        made.put(dimension, recognizer);
                    }
                }
                rigidRecognizers = Map.copyOf(made);
                return library;
            });
            rigidLibrary = rigidLibrary.exceptionally(error -> {
                SeedFinderClient.LOG.warn("Recognition of rigid structures is not available", error);
                return new RigidLibrary.Library(List.of(), List.of("could not be prepared: " + error));
            });
        }
        return rigidLibrary;
    }

    /** The structure sets whose blueprints or layouts could not be made (none until reading has finished). */
    static synchronized List<String> missingSets() {
        StartPieceRecognizer done = recognizer == null ? null : recognizer.getNow(null);
        RigidLibrary.Library rigid = rigidLibrary == null ? null : rigidLibrary.getNow(null);
        List<String> missing = new ArrayList<>();
        if (done != null) {
            Set<String> loaded = new HashSet<>();
            done.pieces().forEach(piece -> loaded.add(piece.structureSet()));
            StartPieceLibrary.setsOf(SeedFinderClient.data()).stream().filter(set -> !loaded.contains(set)).forEach(missing::add);
        }
        if (rigid != null) {
            Set<RigidKind> kinds = new HashSet<>();
            rigid.layouts().forEach(layout -> kinds.add(layout.kind()));
            Arrays.stream(RigidKind.values()).filter(kind -> !kinds.contains(kind)).forEach(kind -> missing.add(kind.structureSet()));
        }
        return missing;
    }

    /** The structure sets recognition has blueprints or layouts for (empty until they are made). */
    static synchronized Set<String> recognizedSets() {
        StartPieceRecognizer done = recognizer == null ? null : recognizer.getNow(null);
        RigidLibrary.Library rigid = rigidLibrary == null ? null : rigidLibrary.getNow(null);
        Set<String> sets = new HashSet<>();
        if (done != null) {
            done.pieces().forEach(piece -> sets.add(piece.structureSet()));
        }
        if (rigid != null) {
            rigid.layouts().forEach(layout -> sets.add(layout.kind().structureSet()));
        }
        return sets;
    }

    /** Whether the session knows a structure of the set was placed by command in the chunk; such a match is set aside. */
    private boolean wasPlaced(WorldSession session, String set, int chunkX, int chunkZ) {
        if (!session.wasPlaced(set, chunkX, chunkZ)) {
            return false;
        }
        if (setAside.add(new RecognizedStarts.Start(set, chunkX, chunkZ))) {
            SeedFinderClient.LOG.info("Structure recognition: {} in chunk {}, {} was placed by command; not a start", set, chunkX, chunkZ);
        }
        return true;
    }

    /** Matches set aside because a structure was placed there by command (for the in-game test). */
    Set<RecognizedStarts.Start> setAside() {
        return Set.copyOf(setAside);
    }

    /**
     * What rigid recognition knows about a chunk, and what a scan of the loaded chunks around it finds there (for the
     * in-game test's messages; slow).
     */
    String describeRigid(int chunkX, int chunkZ) {
        if (tracker == null) {
            return "no rigid tracker here";
        }
        View view = new View();
        RigidRecognizer recognizer = tracker.recognizer();
        StringBuilder text = new StringBuilder(tracker.describe(chunkX, chunkZ, view));
        int minY = level.getMinY();
        int maxY = level.getMaxY();
        text.append("; scan: ").append(recognizer.scan(view, chunkX - 3, chunkX + 3, chunkZ - 3, chunkZ + 3, minY, maxY, false));
        Map<Long, int[]> triggers = new HashMap<>();
        List<RigidRecognizer.Candidate> own = recognizer.candidates(chunkX, chunkZ, (x, z) -> triggers.computeIfAbsent(Chunks.key(x, z),
            key -> recognizer.triggersOf(view, x, z, minY, maxY)));
        for (RigidRecognizer.Candidate candidate : own) {
            RigidRecognizer.Detection d = recognizer.verify(candidate, view, false);
            if (d != null) {
                text.append("; fit ").append(d).append(" acceptable ").append(recognizer.acceptable(d, view));
            }
        }
        return text.toString();
    }

    /** A structure was placed by command there: if it was taken for a start, that is taken back. */
    void placed(String set, int chunkX, int chunkZ) {
        Integer setSpacing = spacing.get(set);
        if (setSpacing != null) {
            starts.remove(set, setSpacing, chunkX, chunkZ);
        }
    }

    /** The starts found so far in this world (for the in-game test). */
    List<RecognizedStarts.Start> starts() {
        return starts.starts();
    }

    /** Chunk loaded: remember to look for anchor and trigger blocks in it. */
    void chunkLoaded(ClientLevel level, LevelChunk chunk) {
        switchLevel(level);
        if (inStructureDimension()) {
            chunksToScan.add(Chunks.key(Chunks.x(chunk.getPos()), Chunks.z(chunk.getPos())));
        }
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
            scanning = null;
            scanAllLoaded = true;
            tracker = null;
            trackerMade = false;
            triggerClasses.clear();
        }
    }

    /** A little work every tick (while collecting): check placements, judge rigid structures, read newly loaded chunks. */
    void tick(Minecraft minecraft, WorldSession session) {
        ticks++;
        StartPieceRecognizer pieces = recognizer(data).getNow(null);
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        RigidLibrary.Library rigid = rigidLibrary(data, minecraft.level.registryAccess()).getNow(null);
        switchLevel(minecraft.level);
        if (pieces == null || rigid == null || !inStructureDimension()) {
            return;
        }
        boolean nether = level.dimension() == Level.NETHER;
        if (trackerMade && nether && trackerNetherPortals != session.netherPortals()) {
            // The player switched nether portals on or off.
            trackerMade = false;
            triggerClasses.clear();
        }
        if (!trackerMade) {
            trackerMade = true;
            trackerNetherPortals = session.netherPortals();
            // Ruined portals in nether chunks made by Minecraft 1.16 to 1.18.1 sit on another grid: only noted when the
            // player says the nether is newer.
            RigidRecognizer rigidRecognizer = nether && !session.netherPortals() ? null : rigidRecognizers.get(level.dimension().identifier().toString());
            tracker = rigidRecognizer == null ? null : new RigidTracker(rigidRecognizer, nether);
            // Chunks read before the tracker was there have no trigger blocks noted: read them again.
            scanAllLoaded = true;
            scanning = null;
        }
        int centerX = minecraft.player.blockPosition().getX() >> 4;
        int centerZ = minecraft.player.blockPosition().getZ() >> 4;
        if (scanAllLoaded) {
            scanAllLoaded = false;
            int radius = minecraft.options.getEffectiveRenderDistance() + 2;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (level.getChunkSource().getChunk(centerX + dx, centerZ + dz, ChunkStatus.FULL, false) != null) {
                        chunksToScan.add(Chunks.key(centerX + dx, centerZ + dz));
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
        if (tracker != null && ticks % FORGET_TICKS == 0) {
            tracker.forgetFarFrom(centerX, centerZ, minecraft.options.getEffectiveRenderDistance() + FORGET_MARGIN);
        }
        if (session.removals() != removals) {
            // The player removed a note: starts that disagreed with it may fit now.
            removals = session.removals();
            offered.removeAll(disagreed);
            disagreed.clear();
            updateNotes(session);
        }
        long start = System.nanoTime();
        long end = start + BUDGET_NANOS;
        View view = new View();
        if (tracker != null) {
            long rigidEnd = start + RIGID_BUDGET_NANOS;
            noteRigid(tracker.work(view, () -> System.nanoTime() > rigidEnd), session);
        }
        while (System.nanoTime() < end) {
            if (!toCheck.isEmpty()) {
                check(pieces, toCheck.poll(), view, session);
            } else if (scanning != null) {
                scanSome(pieces);
            } else if (!chunksToScan.isEmpty()) {
                long pos = chunksToScan.iterator().next();
                chunksToScan.remove(pos);
                scanning = level.getChunkSource().getChunk(Chunks.keyX(pos), Chunks.keyZ(pos), ChunkStatus.FULL, false);
                scanSection = 0;
                scanBlock = 0;
                foundCount = 0;
            } else {
                break;
            }
        }
        if (tracker != null && System.nanoTime() < end) {
            // Nothing else to do: judging rigid structures may use the rest of the budget.
            noteRigid(tracker.work(view, () -> System.nanoTime() > end), session);
        }
    }

    private void noteRigid(List<RigidRecognizer.Detection> detections, WorldSession session) {
        for (RigidRecognizer.Detection d : detections) {
            SeedFinderClient.LOG.info("Recognized {}", d);
            // A structure placed by command is no start: it may not count against the real one of its region.
            if (wasPlaced(session, d.kind().structureSet(), d.chunkX(), d.chunkZ())) {
                continue;
            }
            if (starts.add(d.kind().structureSet(), spacing.get(d.kind().structureSet()), d.chunkX(), d.chunkZ())) {
                updateNotes(session);
            }
        }
    }

    private boolean inStructureDimension() {
        return dimensions.contains(level.dimension().identifier().toString());
    }

    private int triggerClass(Block block) {
        if (tracker == null) {
            return -1;
        }
        Integer known = triggerClasses.get(block);
        if (known == null) {
            known = tracker.recognizer().triggerClass(id(block));
            triggerClasses.put(block, known);
        }
        return known;
    }

    /**
     * Looks for anchor and trigger blocks in the chunk being scanned, a slice of at most {@link #SCAN_SLICE} blocks at
     * a time so the budget is never overrun by much. Sections whose palette can't hold such a block are skipped whole.
     * When the chunk is done, its trigger blocks go to the rigid-structure tracker.
     */
    private void scanSome(StartPieceRecognizer pieces) {
        LevelChunkSection[] sections = scanning.getSections();
        while (scanSection < sections.length) {
            LevelChunkSection section = sections[scanSection];
            if (scanBlock == 0 && (section.hasOnlyAir() || !section.maybeHas(isWanted))) {
                scanSection++;
                continue;
            }
            int minX = scanning.getPos().getMinBlockX();
            int minY = SectionPos.sectionToBlockCoord(scanning.getSectionYFromSectionIndex(scanSection));
            int minZ = scanning.getPos().getMinBlockZ();
            int stop = Math.min(4096, scanBlock + SCAN_SLICE);
            for (; scanBlock < stop; scanBlock++) {
                int x = scanBlock & 15;
                int z = (scanBlock >> 4) & 15;
                int y = scanBlock >> 8;
                Block block = section.getBlockState(x, y, z).getBlock();
                if (anchors.contains(block)) {
                    for (StartPieceRecognizer.Candidate candidate : pieces.candidates(id(block), minX + x, minY + y, minZ + z)) {
                        if (seen.size() >= MAX_SEEN) {
                            seen.clear();
                        }
                        if (seen.add(candidate)) {
                            toCheck.add(candidate);
                        }
                    }
                }
                int triggerClass = triggerClass(block);
                if (triggerClass >= 0) {
                    if (foundCount == found.length) {
                        found = Arrays.copyOf(found, found.length * 2);
                    }
                    found[foundCount++] = RigidRecognizer.pack(x, minY + y, z, triggerClass);
                }
            }
            if (scanBlock == 4096) {
                scanSection++;
                scanBlock = 0;
            }
            return;
        }
        if (tracker != null) {
            tracker.chunkScanned(Chunks.x(scanning.getPos()), Chunks.z(scanning.getPos()), Arrays.copyOf(found, foundCount));
        }
        scanning = null;
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
                SeedFinderClient.LOG.info("Recognized {} starting in chunk {}, {} ({} turned {}; {}/{} blocks, {}/{} built, {}/{} distinct)",
                    candidate.piece().structureSet(), candidate.chunkX(), candidate.chunkZ(), candidate.piece().name(), candidate.rotation(),
                    check.matched(), candidate.piece().blockCount(), check.builtMatched(), candidate.piece().builtCount(),
                    check.distinctMatched(), candidate.piece().distinctCount());
                if (!wasPlaced(session, candidate.piece().structureSet(), candidate.chunkX(), candidate.chunkZ()) && starts.add(candidate)) {
                    updateNotes(session);
                }
            }
        }
    }

    /** Notes new starts, and takes back starts whose region turned out to hold contradicting matches. */
    private void updateNotes(WorldSession session) {
        for (RecognizedStarts.Start start : starts.starts()) {
            if (offered.add(start)) {
                WorldSession.Offer offer = session.addRecognized(
                    new WorldSession.NotedStructure(start.structureSet(), start.chunkX() * 16, start.chunkZ() * 16, SOURCE), spacing.get(start.structureSet()));
                if (offer == WorldSession.Offer.ADDED) {
                    noted.add(start);
                } else if (offer == WorldSession.Offer.DISAGREES) {
                    disagreed.add(start);
                } else if (offer != WorldSession.Offer.KNOWN) {
                    SeedFinderClient.LOG.warn("Structure recognition: {} start in chunk {}, {} is not noted ({})",
                        start.structureSet(), start.chunkX(), start.chunkZ(), offer);
                }
            }
        }
        List<RecognizedStarts.Start> conflicting = new ArrayList<>(starts.conflicting());
        for (RecognizedStarts.Start start : conflicting) {
            if (distrusted.add(start)) {
                SeedFinderClient.LOG.warn("Structure recognition: matches disagree near {} start in chunk {}, {}; noting none there",
                    start.structureSet(), start.chunkX(), start.chunkZ());
                noted.remove(start);
                session.distrust(start.structureSet(), start.chunkX() * 16, start.chunkZ() * 16);
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
            if (level.isOutsideBuildHeight(y)) {
                return null;
            }
            if (chunk == null || Chunks.x(chunk.getPos()) != x >> 4 || Chunks.z(chunk.getPos()) != z >> 4) {
                chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
                if (chunk == null) {
                    return null;
                }
            }
            return id(chunk.getBlockState(pos.set(x, y, z)).getBlock());
        }

        @Override
        public String biomeAt(int x, int y, int z) {
            if (!isLoaded(x >> 4, z >> 4)) {
                return null;
            }
            return level.getNoiseBiome(x >> 2, y >> 2, z >> 2).unwrapKey().map(key -> key.identifier().toString()).orElse(null);
        }
    }
}
