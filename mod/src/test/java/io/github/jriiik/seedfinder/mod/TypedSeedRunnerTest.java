package io.github.jriiik.seedfinder.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jriiik.seedfinder.finder.TypedSeedSearch;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The background typed-seed search as the mod runs it, with a stand-in search (no Minecraft needed). */
class TypedSeedRunnerTest {

    private static final String WORLD = WorldIdentity.key("server:example.net", 42L);

    /** The game's main thread: tasks handed over by the runner, run by the test. */
    private final BlockingQueue<Runnable> mainThread = new LinkedBlockingQueue<>();
    private final List<WorldSession> saved = new CopyOnWriteArrayList<>();

    private static WorldSession collecting() {
        WorldSession session = new WorldSession(WORLD);
        session.setCollecting(true);
        session.setHashedSeed(42L);
        return session;
    }

    private void runHandOver() throws InterruptedException {
        Runnable task = mainThread.poll(30, TimeUnit.SECONDS);
        assertTrue(task != null, "the search handed its outcome over");
        task.run();
    }

    @Test
    void progressIsSavedWhereTheSearchCanSafelyResume() throws InterruptedException {
        // The stand-in has checked 10 pieces, but a piece before them is still running: only 4 are safe.
        CountDownLatch reported = new CountDownLatch(1);
        TypedSeedRunner runner = new TypedSeedRunner(mainThread::add, saved::add, (hashedSeed, from, threads, stop, progress) -> {
            progress.update(10, 4);
            reported.countDown();
            while (!stop.getAsBoolean()) {
                Thread.onSpinWait();
            }
            return new TypedSeedSearch.Outcome(OptionalLong.empty(), 4);
        });
        WorldSession session = collecting();
        runner.tick(session);
        assertTrue(runner.runningFor(session));
        assertTrue(reported.await(30, TimeUnit.SECONDS));
        for (int tick = 0; tick < 600; tick++) {
            runner.tick(session);
        }
        assertEquals(4, session.typedSeedPieces(), "the 30-second save keeps where it can resume, not the pieces checked");

        // Leaving the world stops it; the outcome is handed over to the main thread and saved.
        runner.tick(null);
        runHandOver();
        assertFalse(runner.runningFor(session));
        assertEquals(4, session.typedSeedPieces());
        assertEquals(List.of(session), saved);
    }

    @Test
    void aSeedFoundIsNotedAndTheSearchIsNotRunAgain() throws InterruptedException {
        int[] runs = {0};
        TypedSeedRunner runner = new TypedSeedRunner(mainThread::add, saved::add, (hashedSeed, from, threads, stop, progress) -> {
            runs[0]++;
            return new TypedSeedSearch.Outcome(OptionalLong.of(12345L), from);
        });
        WorldSession session = collecting();
        runner.tick(session);
        runHandOver();
        assertEquals(OptionalLong.of(12345L), session.typedSeed());
        assertTrue(session.typedSeedSearchDone());
        for (int tick = 0; tick < 10; tick++) {
            runner.tick(session);
        }
        assertEquals(1, runs[0]);
        assertTrue(mainThread.isEmpty());
    }

    @Test
    void aFailedSearchIsShownAndNotRetriedForTheseNotes() throws InterruptedException {
        TypedSeedRunner runner = new TypedSeedRunner(mainThread::add, saved::add, (hashedSeed, from, threads, stop, progress) -> {
            throw new IllegalStateException("test");
        });
        WorldSession session = collecting();
        runner.tick(session);
        runHandOver();
        assertTrue(runner.failedFor(session));
        runner.tick(session);
        assertFalse(runner.runningFor(session));
        assertTrue(mainThread.isEmpty());
    }

    @Test
    void nothingRunsWhileCollectingIsOff() {
        TypedSeedRunner runner = new TypedSeedRunner(mainThread::add, saved::add, (hashedSeed, from, threads, stop, progress) -> {
            throw new AssertionError("must not run");
        });
        WorldSession session = new WorldSession(WORLD);
        session.setHashedSeed(42L);
        runner.tick(session);
        assertFalse(runner.runningFor(session));
    }
}
