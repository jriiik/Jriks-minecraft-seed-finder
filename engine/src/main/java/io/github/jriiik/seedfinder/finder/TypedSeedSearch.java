package io.github.jriiik.seedfinder.finder;

import io.github.jriiik.seedfinder.Seeds;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Finds a world seed from the hashed seed alone, when the seed was typed. Minecraft turns typed text into its 32-bit
 * {@code hashCode()} and keeps typed numbers as they are, so every text seed, and every typed number from
 * -2,147,483,648 to 2,147,483,647, is one of the 2^32 values of an {@code int}. Checking all of them against the
 * hashed seed takes minutes (about 60 ns per seed and CPU thread with SHA instructions). Seeds Minecraft picked by
 * itself, and longer typed numbers, are not among them, so for those worlds this finds nothing.
 *
 * <p>A match is certain: a wrong seed matches a 64-bit hashed seed by chance with probability 2^32 / 2^64, about
 * one in four billion. (A server could send a made-up hashed seed; one that happens to equal the hash of a typed
 * seed is not something the game does.)
 *
 * <p>The values are checked in {@link #PIECES} pieces, smallest numbers first (0 and up, then below 0, then the next
 * ones), so typed numbers like 12345 come out at once. A search can be stopped and resumed from the pieces done.
 */
public final class TypedSeedSearch {

    /** How many pieces the 2^32 typed seeds are checked in. */
    public static final int PIECES = 1024;
    /**
     * The layout of the pieces (their number, size and order). Saved progress only means something with the same
     * layout, so this changes whenever the pieces do.
     */
    public static final int LAYOUT = 1;
    private static final long PIECE_SIZE = (1L << 32) / PIECES;
    /** Check whether to stop this often (about every 4 ms). */
    private static final long STOP_CHECK = 1 << 16;

    /**
     * @param seed       the typed world seed whose hash is the hashed seed, if one is
     * @param piecesDone how many pieces, counted from the first, are all checked: {@link #PIECES} when the search
     *                   is complete, otherwise where to resume
     */
    public record Outcome(OptionalLong seed, int piecesDone) {

        /** True when the search has an answer: the seed, or that no typed seed has this hashed seed. */
        public boolean complete() {
            return seed.isPresent() || piecesDone >= PIECES;
        }
    }

    /** Hears how far the search is: called from the search threads, one call at a time, after each piece. */
    @FunctionalInterface
    public interface Progress {

        /**
         * @param checked    how many pieces are checked, counting the ones before {@code fromPiece}: for showing
         *                   progress (a piece in the middle may still be running)
         * @param resumeFrom where a later search can safely resume: every piece before it is checked
         */
        void update(int checked, int resumeFrom);
    }

    private TypedSeedSearch() {
    }

    /** The first typed seed of a piece: piece 2k starts at k * 2^22, piece 2k + 1 at -(k + 1) * 2^22. */
    static long pieceStart(int piece) {
        long k = piece / 2;
        return piece % 2 == 0 ? k * PIECE_SIZE : -(k + 1) * PIECE_SIZE;
    }

    /** The piece a typed seed is in. */
    static int pieceOf(int seed) {
        return seed >= 0 ? 2 * (int) (seed / PIECE_SIZE) : 2 * (int) ((-(long) seed - 1) / PIECE_SIZE) + 1;
    }

    /** Searches all pieces from {@code fromPiece} on; see {@link #search(long, int, int, int, BooleanSupplier, Progress)}. */
    public static Outcome search(long hashedSeed, int fromPiece, int threads, BooleanSupplier stop, Progress progress) {
        return search(hashedSeed, fromPiece, PIECES, threads, stop, progress);
    }

    /**
     * Checks the typed seeds of pieces {@code fromPiece} (inclusive) to {@code toPiece} (exclusive) against the hashed
     * seed, on {@code threads} threads of its own, until a seed matches, every piece is checked, or {@code stop} says
     * so. Pieces before {@code fromPiece} count as done. If a search thread fails (or {@code stop} or
     * {@code progress} throws), the others stop and the failure is thrown from here, unless a seed was found.
     */
    public static Outcome search(long hashedSeed, int fromPiece, int toPiece, int threads, BooleanSupplier stop, Progress progress) {
        if (fromPiece < 0 || toPiece > PIECES || fromPiece > toPiece || threads < 1) {
            throw new IllegalArgumentException("pieces " + fromPiece + ".." + toPiece + " on " + threads + " threads");
        }
        AtomicInteger next = new AtomicInteger(fromPiece);
        // Which pieces are checked, how many, and the first one that isn't (guarded by the lock).
        Object lock = new Object();
        boolean[] done = new boolean[PIECES];
        int[] checkedCount = {fromPiece};
        int[] resumeFrom = {fromPiece};
        AtomicBoolean found = new AtomicBoolean();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicLong seed = new AtomicLong();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        BooleanSupplier quit = () -> found.get() || cancelled.get() || stop.getAsBoolean();
        Runnable work = () -> {
            try {
                Seeds.Hasher hasher = new Seeds.Hasher();
                int piece;
                while (!quit.getAsBoolean() && (piece = next.getAndIncrement()) < toPiece) {
                    long start = pieceStart(piece);
                    long end = start + PIECE_SIZE;
                    boolean checked = true;
                    for (long candidate = start; candidate < end; candidate++) {
                        if ((candidate & (STOP_CHECK - 1)) == 0 && quit.getAsBoolean()) {
                            checked = false;
                            break;
                        }
                        if (hasher.hashedSeed(candidate) == hashedSeed) {
                            seed.set(candidate);
                            found.set(true);
                            break;
                        }
                    }
                    if (checked && !found.get()) {
                        synchronized (lock) {
                            done[piece] = true;
                            checkedCount[0]++;
                            while (resumeFrom[0] < toPiece && done[resumeFrom[0]]) {
                                resumeFrom[0]++;
                            }
                            progress.update(checkedCount[0], resumeFrom[0]);
                        }
                    }
                }
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
                cancelled.set(true);
            }
        };
        // The workers take this thread's priority (low in the mod).
        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(work, "typed-seed-search-" + i);
            workers[i].setDaemon(true);
            workers[i].start();
        }
        boolean interrupted = false;
        for (Thread worker : workers) {
            while (worker.isAlive()) {
                try {
                    worker.join();
                } catch (InterruptedException e) {
                    // Stop the workers (they notice within milliseconds), wait for them, then pass the interrupt on.
                    interrupted = true;
                    cancelled.set(true);
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (found.get()) {
            return new Outcome(OptionalLong.of(seed.get()), fromPiece);
        }
        if (failure.get() != null) {
            throw new IllegalStateException("The typed-seed search failed", failure.get());
        }
        synchronized (lock) {
            return new Outcome(OptionalLong.empty(), resumeFrom[0]);
        }
    }
}
