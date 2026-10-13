package io.github.jriiik.seedfinder.mod;

import io.github.jriiik.seedfinder.finder.ObservationReader;
import io.github.jriiik.seedfinder.finder.SeedCheck;
import io.github.jriiik.seedfinder.finder.WorldSeedSearch;
import io.github.jriiik.seedfinder.version.VersionData;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;

/**
 * Runs searches off the game's main thread, on one low-priority thread of its own, and hands the results back to
 * the main thread. A search starts when the notes have stayed unchanged for a moment. When the typed-seed search
 * found a seed, it is checked against the notes first.
 */
final class SearchRunner {

    /** Wait this many ticks (2 seconds) after the last change before searching. */
    private static final int QUIET_TICKS = 40;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "jriksseedfinder-search");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    private final VersionData data;
    private final int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    private boolean running;
    private volatile int percent = -1;
    private int watchedVersion = -1;
    private int quietTicks;
    /** The structure step is the slow part; it is kept while the structures stay the same. */
    private String cachedFor;
    private WorldSeedSearch.StructureStep cachedStep;

    SearchRunner(VersionData data) {
        this.data = data;
    }

    boolean running() {
        return running;
    }

    /** How far the structure search is, 0 to 100, or -1 when not known. */
    int percent() {
        return percent;
    }

    /** Called on the main thread every tick. */
    void tick(WorldSession session) {
        if (session.version() != watchedVersion) {
            watchedVersion = session.version();
            quietTicks = 0;
            return;
        }
        quietTicks++;
        if (running || !session.collecting() || session.resultIsCurrent() || quietTicks < QUIET_TICKS) {
            return;
        }
        if (session.structures().isEmpty() && session.typedSeed().isEmpty()) {
            session.setResult(session.version(), null, null);
            return;
        }
        start(session);
    }

    private void start(WorldSession session) {
        int version = session.version();
        List<String> lines = session.notesLines();
        OptionalLong typedSeed = session.typedSeed();
        String structureKey = session.world() + "\n" + String.join("\n", session.structureLines());
        WorldSeedSearch.StructureStep reuse = structureKey.equals(cachedFor) ? cachedStep : null;
        running = true;
        percent = reuse != null ? 100 : 0;
        CompletableFuture
            .supplyAsync(() -> {
                ObservationReader.Observations notes = ObservationReader.read(data, lines);
                boolean typedSeedConfirmed = false;
                if (typedSeed.isPresent()) {
                    SeedCheck check = SeedCheck.of(data, notes.structures(), notes.bedrock(), typedSeed.getAsLong());
                    // Shown at once: a structure search can take a while.
                    Minecraft.getInstance().execute(() -> session.setTypedSeedCheck(version, check));
                    typedSeedConfirmed = check.verdict() == SeedCheck.Verdict.CONFIRMED;
                }
                if (notes.structures().isEmpty()) {
                    return new Outcome(null, null, null);
                }
                WorldSeedSearch.StructureStep step = reuse != null ? reuse
                    : WorldSeedSearch.structureSeeds(data, notes.structures(), threads, (done, total) -> percent = (int) (100 * done / total));
                WorldSeedSearch.Result result = WorldSeedSearch.fullSeed(step, notes.bedrock(), notes.hashedSeed(), threads);
                if (result.stage() == WorldSeedSearch.Stage.NO_STRUCTURE_SEED && !typedSeedConfirmed) {
                    // One structure that doesn't follow the seed (older chunks, a command, a copy) spoils the search. Not
                    // worth the many searches when the notes already confirm the typed seed (they say which ones don't fit).
                    result = WorldSeedSearch.withoutMisfit(data, notes.structures(), notes.bedrock(), notes.hashedSeed(), threads).orElse(result);
                }
                return new Outcome(structureKey, step, result);
            }, WORKER)
            .whenCompleteAsync((outcome, error) -> {
                running = false;
                percent = -1;
                if (outcome != null && outcome.step() != null) {
                    cachedFor = outcome.structureKey();
                    cachedStep = outcome.step();
                }
                if (error != null) {
                    SeedFinderClient.LOG.warn("Search failed", error);
                }
                session.setResult(version, outcome == null ? null : outcome.result(),
                    error == null ? null : String.valueOf(error.getCause() != null ? error.getCause().getMessage() : error.getMessage()));
            }, Minecraft.getInstance());
    }

    /** What a search found; all null when there were no structures to search with. */
    private record Outcome(String structureKey, WorldSeedSearch.StructureStep step, WorldSeedSearch.Result result) {
    }
}
