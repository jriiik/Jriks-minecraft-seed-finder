package io.github.jriiik.seedfinder.recognize;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Runs the {@link RigidRecognizer} on a world that loads chunk by chunk, a little at a time. The caller reports the
 * trigger blocks of each chunk as it reads it ({@link #chunkScanned}); the tracker then judges each chunk that may
 * hold a start once everything around it is loaded and read ({@link #work}).
 *
 * <p>A chunk is judged with the fits of every chunk within {@link #RIVALS} chunks of it, each fit compared over its
 * whole extent: so a wrong fit next to a real structure always meets the real one, complete (fits that share blocks
 * never start farther apart). That needs the chunks within {@code RIVALS + span} of the judged chunk loaded. Each chunk
 * is judged once. Two accepted fits of one set that are farther apart than that but closer than the set's separation
 * can't both be starts; unlike a scan of one big window, which keeps the stronger, the tracker reports both, and
 * {@link RecognizedStarts} then finds them in one placement region and reports neither: a miss, never a wrong start.
 */
public final class RigidTracker {

    /** Rival fits are those whose start chunk is within this many chunks of the judged one. */
    public static final int RIVALS = 2;
    /** Chunks looked at per call when searching for one that is ready to be judged. */
    private static final int READY_CHECKS = 64;

    private final RigidRecognizer recognizer;
    private final boolean nether;
    /** How far from a judged chunk everything must be loaded and read. */
    private final int reach;
    private final Map<Long, int[]> triggers = new HashMap<>();
    /** Chunks that may hold a start and are not judged yet, in the order they came up. */
    private final ArrayDeque<Long> pending = new ArrayDeque<>();
    private final Set<Long> pendingSet = new HashSet<>();
    private final Set<Long> judged = new HashSet<>();
    private final Map<Long, List<RigidRecognizer.Candidate>> candidates = new HashMap<>();
    private final Map<RigidRecognizer.Candidate, Optional<RigidRecognizer.Detection>> verified = new HashMap<>();
    /**
     * The chunk being judged (when {@code jobCandidates} is not null): the fits around it are gathered one chunk at a
     * time, then verified one by one, across as many calls as needed.
     */
    private long job;
    private List<RigidRecognizer.Candidate> jobCandidates;
    private int jobGathered;
    private int jobNext;

    public RigidTracker(RigidRecognizer recognizer, boolean nether) {
        this.recognizer = recognizer;
        this.nether = nether;
        this.reach = RIVALS + recognizer.span();
    }

    public RigidRecognizer recognizer() {
        return recognizer;
    }

    /** How far from a chunk everything must be loaded and read before it is judged. */
    public int reach() {
        return reach;
    }

    /** The trigger blocks of a chunk were read ({@link RigidRecognizer#pack}); call again when it is loaded again. */
    public void chunkScanned(int chunkX, int chunkZ, int[] found) {
        int span = recognizer.span();
        if (triggers.put(RigidRecognizer.chunkKey(chunkX, chunkZ), found.clone()) != null) {
            // Read again: the votes of the chunks around it are counted afresh.
            for (int x = chunkX - span; x <= chunkX + span; x++) {
                for (int z = chunkZ - span; z <= chunkZ + span; z++) {
                    candidates.remove(RigidRecognizer.chunkKey(x, z));
                }
            }
        }
        if (found.length > 0) {
            for (int x = chunkX - span; x <= chunkX + span; x++) {
                for (int z = chunkZ - span; z <= chunkZ + span; z++) {
                    long key = RigidRecognizer.chunkKey(x, z);
                    if (!judged.contains(key) && pendingSet.add(key)) {
                        pending.add(key);
                    }
                }
            }
        }
    }

    /** Forgets what was found more than {@code radius} chunks from the given chunk (in either direction). */
    public void forgetFarFrom(int chunkX, int chunkZ, int radius) {
        triggers.keySet().removeIf(key -> far(key, chunkX, chunkZ, radius));
        candidates.keySet().removeIf(key -> far(key, chunkX, chunkZ, radius));
        verified.keySet().removeIf(candidate -> Math.abs(candidate.chunkX() - chunkX) > radius || Math.abs(candidate.chunkZ() - chunkZ) > radius);
        pending.removeIf(key -> far(key, chunkX, chunkZ, radius));
        pendingSet.removeIf(key -> far(key, chunkX, chunkZ, radius));
        if (jobCandidates != null && far(job, chunkX, chunkZ, radius)) {
            jobCandidates = null;
        }
    }

    private static boolean far(long key, int chunkX, int chunkZ, int radius) {
        return Math.abs((int) (key >> 32) - chunkX) > radius || Math.abs((int) key - chunkZ) > radius;
    }

    /** Chunks waiting to be judged (for tests and the log). */
    public int pendingCount() {
        return pending.size();
    }

    /**
     * Does work until {@code timeUp} says stop or nothing is ready: picks a chunk whose surroundings are loaded and
     * read, gathers and verifies the fits around it, and decides; each step is small, and a chunk may take several
     * calls. Returns the fits accepted in the chunks decided.
     */
    public List<RigidRecognizer.Detection> work(BlockView view, BooleanSupplier timeUp) {
        List<RigidRecognizer.Detection> found = new ArrayList<>();
        if (jobCandidates != null && !ready(job, view)) {
            // Something unloaded since the last call: decide later. (What was verified stays valid.)
            jobCandidates = null;
            if (pendingSet.add(job)) {
                pending.add(job);
            }
        }
        int side = 2 * RIVALS + 1;
        while (!timeUp.getAsBoolean()) {
            if (jobCandidates == null) {
                if (!startJob(view, timeUp)) {
                    break;
                }
            } else if (jobGathered < side * side) {
                int x = (int) (job >> 32) + jobGathered / side - RIVALS;
                int z = (int) job + jobGathered % side - RIVALS;
                jobGathered++;
                jobCandidates.addAll(candidatesOf(RigidRecognizer.chunkKey(x, z)));
            } else if (jobNext < jobCandidates.size()) {
                RigidRecognizer.Candidate candidate = jobCandidates.get(jobNext++);
                if (!verified.containsKey(candidate)) {
                    verified.put(candidate, Optional.ofNullable(recognizer.verify(candidate, view, nether)));
                }
            } else {
                int chunkX = (int) (job >> 32);
                int chunkZ = (int) job;
                List<RigidRecognizer.Detection> batch = jobCandidates.stream().map(verified::get).flatMap(Optional::stream).toList();
                for (RigidRecognizer.Detection d : recognizer.resolve(batch, view, d -> recognizer.isClusterPiece(d, view))) {
                    if (d.chunkX() == chunkX && d.chunkZ() == chunkZ) {
                        found.add(d);
                    }
                }
                judged.add(job);
                jobCandidates = null;
            }
        }
        return found;
    }

    /** Finds a chunk ready to be judged and starts on it; false when none is ready (or the time is up). */
    private boolean startJob(BlockView view, BooleanSupplier timeUp) {
        int checks = Math.min(READY_CHECKS, pending.size());
        for (int i = 0; i < checks; i++) {
            if (i > 0 && timeUp.getAsBoolean()) {
                return false;
            }
            long key = pending.poll();
            List<RigidRecognizer.Candidate> own = candidatesOf(key);
            if (own != null && own.stream().noneMatch(this::couldStart)) {
                // Nothing here could ever be reported.
                pendingSet.remove(key);
                judged.add(key);
                continue;
            }
            if (own == null || !ready(key, view)) {
                pending.add(key);
                continue;
            }
            pendingSet.remove(key);
            job = key;
            jobCandidates = new ArrayList<>();
            jobGathered = 0;
            jobNext = 0;
            return true;
        }
        return false;
    }

    private boolean couldStart(RigidRecognizer.Candidate candidate) {
        return recognizer.rules(candidate.layout().kind()).couldStartIn(candidate.chunkX(), candidate.chunkZ());
    }

    /** Whether everything within {@link #reach} chunks is loaded and read. */
    private boolean ready(long key, BlockView view) {
        int chunkX = (int) (key >> 32);
        int chunkZ = (int) key;
        for (int x = chunkX - reach; x <= chunkX + reach; x++) {
            for (int z = chunkZ - reach; z <= chunkZ + reach; z++) {
                if (!triggers.containsKey(RigidRecognizer.chunkKey(x, z)) || !view.isLoaded(x, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The fits with their origin on this chunk's corner; null until the chunks around it are read. */
    private List<RigidRecognizer.Candidate> candidatesOf(long key) {
        List<RigidRecognizer.Candidate> known = candidates.get(key);
        if (known == null) {
            known = recognizer.candidates((int) (key >> 32), (int) key, (x, z) -> triggers.get(RigidRecognizer.chunkKey(x, z)));
            if (known != null) {
                candidates.put(key, known);
            }
        }
        return known;
    }
}
