package io.github.jriiik.seedfinder.finder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import io.github.jriiik.seedfinder.Fixtures;
import io.github.jriiik.seedfinder.Seeds;
import io.github.jriiik.seedfinder.version.MinecraftVersion;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class TypedSeedSearchTest {

    private static final long PIECE_SIZE = (1L << 32) / TypedSeedSearch.PIECES;
    /** A seed Minecraft picked itself: its hashed seed is no typed seed's. */
    private static final long PICKED = 8091867987493326313L;
    private static final long NOT_TYPED = Seeds.hashedSeed(PICKED);

    @Test
    void thePiecesCoverEveryTypedSeedOnceSmallestNumbersFirst() {
        List<long[]> ranges = new ArrayList<>();
        long largestSoFar = -1;
        for (int piece = 0; piece < TypedSeedSearch.PIECES; piece++) {
            long start = TypedSeedSearch.pieceStart(piece);
            long last = start + PIECE_SIZE - 1;
            ranges.add(new long[] {start, last});
            assertEquals(piece, TypedSeedSearch.pieceOf((int) start));
            assertEquals(piece, TypedSeedSearch.pieceOf((int) last));
            long largest = Math.max(Math.abs(start), Math.abs(last));
            assertTrue(largest > largestSoFar, "piece " + piece + " holds larger numbers than the ones before");
            largestSoFar = largest;
        }
        ranges.sort(Comparator.comparingLong(range -> range[0]));
        assertEquals(Integer.MIN_VALUE, ranges.get(0)[0]);
        for (int i = 1; i < ranges.size(); i++) {
            assertEquals(ranges.get(i - 1)[1] + 1, ranges.get(i)[0]);
        }
        assertEquals(Integer.MAX_VALUE, ranges.get(ranges.size() - 1)[1]);
    }

    @ParameterizedTest
    @MethodSource("io.github.jriiik.seedfinder.Fixtures#versions")
    void findsTheTypedSeedsWhoseHashedSeedWasRecordedFromTheGame(MinecraftVersion version) {
        JsonArray rows = Fixtures.load(version, "random_seeds.json").getAsJsonArray("hashed_seed");
        int checked = 0;
        for (JsonElement row : rows) {
            long worldSeed = Long.parseLong(row.getAsJsonArray().get(0).getAsString());
            long hashedSeed = Long.parseLong(row.getAsJsonArray().get(1).getAsString());
            if (worldSeed != (int) worldSeed) {
                continue;
            }
            // Only the pieces up to the seed's own are searched, to keep the test quick.
            TypedSeedSearch.Outcome outcome = TypedSeedSearch.search(hashedSeed, 0, TypedSeedSearch.pieceOf((int) worldSeed) + 1, 2,
                () -> false, (done, resumeFrom) -> { });
            assertEquals(OptionalLong.of(worldSeed), outcome.seed());
            assertTrue(outcome.complete());
            checked++;
        }
        assertEquals(5, checked, "the fixture's typed seeds: 0, 1, -1, 12345 and 2317450");
    }

    @Test
    void aSearchStopsAndResumesWhereItWas() {
        long typed = -(1L << 22) - 5;
        long hashedSeed = Seeds.hashedSeed(typed);
        assertEquals(3, TypedSeedSearch.pieceOf((int) typed));

        // The first three pieces don't hold it.
        TypedSeedSearch.Outcome firstPieces = TypedSeedSearch.search(hashedSeed, 0, 3, 2, () -> false, (checked, resumeFrom) -> { });
        assertEquals(OptionalLong.empty(), firstPieces.seed());
        assertEquals(3, firstPieces.piecesDone());
        assertFalse(firstPieces.complete());

        // Stopped after one more piece: resumes after it.
        AtomicInteger seen = new AtomicInteger();
        TypedSeedSearch.Outcome stopped = TypedSeedSearch.search(NOT_TYPED, 3, 1, () -> seen.get() >= 4,
            (checked, resumeFrom) -> seen.set(resumeFrom));
        assertEquals(OptionalLong.empty(), stopped.seed());
        assertEquals(4, stopped.piecesDone());

        TypedSeedSearch.Outcome resumed = TypedSeedSearch.search(hashedSeed, firstPieces.piecesDone(), 1, () -> false, (checked, resumeFrom) -> { });
        assertEquals(OptionalLong.of(typed), resumed.seed());
    }

    @Test
    void aSeedMinecraftPickedItselfIsNotATypedSeed() {
        assertTrue(Seeds.couldBeRandomSeed(PICKED));
        TypedSeedSearch.Outcome outcome = TypedSeedSearch.search(NOT_TYPED, 0, 2, 2, () -> false, (checked, resumeFrom) -> { });
        assertEquals(OptionalLong.empty(), outcome.seed());
        assertEquals(2, outcome.piecesDone());
    }

    @Test
    void severalThreadsCountEveryPieceAndTheResumePointCatchesUp() {
        List<int[]> reports = Collections.synchronizedList(new ArrayList<>());
        TypedSeedSearch.Outcome outcome = TypedSeedSearch.search(NOT_TYPED, 0, 12, 4, () -> false,
            (checked, resumeFrom) -> reports.add(new int[] {checked, resumeFrom}));
        assertEquals(OptionalLong.empty(), outcome.seed());
        assertEquals(12, outcome.piecesDone());
        assertEquals(12, reports.size());
        for (int i = 0; i < reports.size(); i++) {
            assertEquals(i + 1, reports.get(i)[0], "one call per piece, one at a time");
            assertTrue(reports.get(i)[1] <= reports.get(i)[0]);
            assertTrue(i == 0 || reports.get(i)[1] >= reports.get(i - 1)[1], "the resume point never goes back");
        }
        assertEquals(12, reports.get(11)[1]);
    }

    @Test
    void theResumePointNeverPassesAPieceThatIsStillBeingChecked() throws InterruptedException {
        // One search thread is held inside its first piece (as if the computer gave it no time) while the other goes
        // on. Resuming from any point reported must not skip the held piece.
        Map<Thread, Integer> calls = new ConcurrentHashMap<>();
        AtomicReference<Thread> held = new AtomicReference<>();
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean stopNow = new AtomicBoolean();
        BooleanSupplier stop = () -> {
            // A thread's second question comes at the first seed of its first piece.
            if (calls.merge(Thread.currentThread(), 1, Integer::sum) == 2 && held.compareAndSet(null, Thread.currentThread())) {
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return stopNow.get();
        };
        List<int[]> reports = Collections.synchronizedList(new ArrayList<>());
        TypedSeedSearch.Outcome outcome = TypedSeedSearch.search(NOT_TYPED, 0, 2, stop, (checked, resumeFrom) -> {
            reports.add(new int[] {checked, resumeFrom});
            if (reports.size() == 4) {
                stopNow.set(true);
                release.countDown();
            }
        });
        assertEquals(OptionalLong.empty(), outcome.seed());
        assertEquals(4, reports.size(), "the other thread checked four pieces");
        // The held thread had piece 0 or 1, so nothing after it counts as done.
        assertTrue(outcome.piecesDone() <= 1, "resumes at " + outcome.piecesDone());
        for (int[] report : reports) {
            assertTrue(report[1] <= outcome.piecesDone(), "reported resume point " + report[1] + " skips the held piece");
        }
        assertEquals(4, reports.get(3)[0], "the count still shows the other thread's progress");
    }

    @Test
    void anInterruptStopsTheSearchAndIsPassedOn() throws InterruptedException {
        AtomicReference<TypedSeedSearch.Outcome> outcome = new AtomicReference<>();
        AtomicBoolean stillInterrupted = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        Thread caller = new Thread(() -> {
            outcome.set(TypedSeedSearch.search(NOT_TYPED, 0, 2, () -> {
                started.countDown();
                return false;
            }, (checked, resumeFrom) -> { }));
            stillInterrupted.set(Thread.currentThread().isInterrupted());
        });
        caller.start();
        assertTrue(started.await(30, TimeUnit.SECONDS));
        caller.interrupt();
        caller.join(30_000);
        assertFalse(caller.isAlive(), "the search returns soon after the interrupt");
        assertTrue(stillInterrupted.get(), "the interrupt is passed on");
        assertFalse(outcome.get().complete());
    }

    @Test
    void aFailingSearchThreadStopsTheSearchWithItsError() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> TypedSeedSearch.search(NOT_TYPED, 0, 8, 2,
            () -> false, (checked, resumeFrom) -> {
                throw new ArithmeticException("broken progress");
            }));
        assertEquals("broken progress", failure.getCause().getMessage());
    }
}
