package io.github.jriiik.seedfinder.mod;

import io.github.jriiik.seedfinder.finder.TypedSeedSearch;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;

/**
 * Checks in the background whether the world's seed was typed (as text or a short number): then the hashed seed
 * alone gives it ({@link TypedSeedSearch}, minutes of work). Runs only while collecting is on, the hashed seed is
 * known and no other search has found the seed; it stops when the player leaves the world and continues from where
 * it was next time. Whether the seed found fits the notes is checked by the {@link SearchRunner}.
 */
final class TypedSeedRunner {

    /** The search as the runner calls it ({@link TypedSeedSearch#search}; a stand-in in tests). */
    @FunctionalInterface
    interface Search {
        TypedSeedSearch.Outcome run(long hashedSeed, int fromPiece, int threads, BooleanSupplier stop, TypedSeedSearch.Progress progress);
    }

    /** Save the progress every 30 seconds while it runs. */
    private static final int CHECKPOINT_TICKS = 600;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "jriksseedfinder-typed-seed");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    /** Where results are handed over: the game's main thread. */
    private final Executor mainThread;
    private final Consumer<WorldSession> saveIfLeft;
    private final Search search;
    /** A quarter of the CPU threads (at least one): the search takes minutes, and the game should stay smooth. */
    private final int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
    private WorldSession runningFor;
    private volatile boolean stop;
    /** Pieces checked (for the percentage) and where the search can safely resume (every piece before it is checked). */
    private volatile int checked;
    private volatile int resumeFrom;
    private int ticks;
    /** A world whose search failed (see the game log): not tried again until the player comes back to it. */
    private WorldSession failed;

    TypedSeedRunner() {
        this(task -> Minecraft.getInstance().execute(task), SeedFinderClient::saveIfLeft, TypedSeedSearch::search);
    }

    /**
     * @param mainThread runs the hand-over of results on the game's main thread
     * @param saveIfLeft saves the notes of a world the player has left meanwhile
     * @param search     the search
     */
    TypedSeedRunner(Executor mainThread, Consumer<WorldSession> saveIfLeft, Search search) {
        this.mainThread = mainThread;
        this.saveIfLeft = saveIfLeft;
        this.search = search;
    }

    /** Whether the search runs for these notes. */
    boolean runningFor(WorldSession session) {
        return session != null && runningFor == session;
    }

    /** Whether the search failed for these notes. */
    boolean failedFor(WorldSession session) {
        return session != null && failed == session;
    }

    /** How far the search is, 0 to 100. */
    int percent() {
        return checked * 100 / TypedSeedSearch.PIECES;
    }

    /** Called on the main thread every tick, with the current world's notes (or null). */
    void tick(WorldSession current) {
        ticks++;
        boolean wanted = current != null && current.collecting() && current.hashedSeed().isPresent() && !current.typedSeedSearchDone()
            && current.worldSeed().isEmpty() && current != failed;
        if (runningFor != null) {
            if (runningFor != current || !wanted) {
                stop = true;
            } else if (ticks % CHECKPOINT_TICKS == 0) {
                runningFor.setTypedSeedSearch(new TypedSeedSearch.Outcome(OptionalLong.empty(), resumeFrom));
            }
            return;
        }
        if (wanted) {
            start(current);
        }
    }

    private void start(WorldSession session) {
        runningFor = session;
        stop = false;
        int from = session.typedSeedPieces();
        checked = from;
        resumeFrom = from;
        long hashedSeed = session.hashedSeed().getAsLong();
        CompletableFuture
            .supplyAsync(() -> search.run(hashedSeed, from, threads, () -> stop, (count, safe) -> {
                checked = count;
                resumeFrom = safe;
            }), WORKER)
            .whenCompleteAsync((outcome, error) -> {
                runningFor = null;
                if (error != null) {
                    SeedFinderClient.LOG.warn("The typed-seed search failed", error);
                    failed = session;
                    return;
                }
                session.setTypedSeedSearch(outcome);
                outcome.seed().ifPresent(seed -> SeedFinderClient.LOG.info("The hashed seed is the one of the typed seed {}", seed));
                saveIfLeft.accept(session);
            }, mainThread);
    }
}
