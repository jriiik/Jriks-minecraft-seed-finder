package io.github.jriiik.seedfinder.mod;

import io.github.jriiik.seedfinder.finder.ObservationReader;
import io.github.jriiik.seedfinder.finder.WorldSeedSearch;
import io.github.jriiik.seedfinder.version.VersionData;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;

/**
 * Runs searches off the game's main thread, on one low-priority thread of its own, and hands the results back to
 * the main thread. A search starts when the notes have stayed unchanged for a moment.
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
        if (session.structures().isEmpty()) {
            session.setResult(session.version(), null, null);
            return;
        }
        start(session);
    }

    private void start(WorldSession session) {
        int version = session.version();
        List<String> lines = session.notesLines();
        String structureKey = session.world() + "\n" + String.join("\n", session.structureLines());
        WorldSeedSearch.StructureStep reuse = structureKey.equals(cachedFor) ? cachedStep : null;
        running = true;
        percent = reuse != null ? 100 : 0;
        CompletableFuture
            .supplyAsync(() -> {
                ObservationReader.Observations notes = ObservationReader.read(data, lines);
                WorldSeedSearch.StructureStep step = reuse != null ? reuse
                    : WorldSeedSearch.structureSeeds(data, notes.structures(), threads, (done, total) -> percent = (int) (100 * done / total));
                return new Outcome(structureKey, step, WorldSeedSearch.fullSeed(step, notes.bedrock(), notes.hashedSeed(), threads));
            }, WORKER)
            .whenCompleteAsync((outcome, error) -> {
                running = false;
                percent = -1;
                if (outcome != null) {
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

    private record Outcome(String structureKey, WorldSeedSearch.StructureStep step, WorldSeedSearch.Result result) {
    }
}
