package io.github.jriiik.seedfinder.recognize;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Runs the {@link RigidRecognizer} on a world that loads chunk by chunk, a little at a time. The caller reports the
 * trigger blocks of each chunk as it reads it ({@link #chunkScanned}); the tracker then judges each chunk that may
 * hold a start ({@link #work}), in steps that each read at most a few hundred blocks, so a time budget is never
 * overrun by much.
 *
 * <p>A chunk is first judged on its own fits: those whose set can start there are verified and tested
 * ({@link RigidRecognizer#acceptable}); that needs only the chunks within {@link RigidRecognizer#readSpan} of it,
 * and most chunks are done then. If one passes, the chunk is judged with the fits of every chunk within
 * {@link #RIVALS} chunks of it, each fit compared over its whole extent: so a wrong fit next to a real structure
 * always meets the real one, complete (fits that share blocks never start farther apart). That needs everything the
 * fits read loaded and read: the chunks within {@link #reach}. Either way the answer is what
 * {@link RigidRecognizer#resolve} gives for the chunk on the fits of the chunks within {@link #RIVALS}, compared in
 * full with the blocks as last read: when a chunk is read again, what was learned from its blocks is learned afresh
 * (blocks that change in a loaded chunk are not seen). Each chunk is judged once. Two accepted fits of one set that are
 * farther apart than {@link #RIVALS} but closer than the set's separation can't both be starts; unlike a scan of one
 * big window, which keeps the stronger, the tracker reports both, and {@link RecognizedStarts} then finds them in one
 * placement region and reports neither: a miss, never a wrong start.
 */
public final class RigidTracker {

    /** Rival fits are those whose start chunk is within this many chunks of the judged one. */
    public static final int RIVALS = 2;
    /** Blocks read per step at most. */
    static final int STEP_READS = 512;
    /** Trigger blocks counted per step at most. */
    static final int STEP_TRIGGERS = 512;
    /** Chunks looked at per call when searching for one that can be worked on. */
    private static final int READY_CHECKS = 64;
    /**
     * Waiting chunks looked at again per call although nothing new was read around them (a chunk may load again
     * without being read again).
     */
    private static final int IDLE_CHECKS = 4;

    private static final int OWN = 0;
    private static final int GATHER = 1;
    private static final int VERIFY = 2;
    private static final int RESOLVE = 3;

    private final RigidRecognizer recognizer;
    private final boolean nether;
    /** How far from a chunk everything must be loaded and read to judge it on its own fits. */
    private final int ownReach;
    /** How far from a chunk everything must be loaded and read to judge it with its rivals. */
    private final int reach;
    private final RigidRecognizer.Votes votes;
    private final Map<Long, int[]> triggers = new HashMap<>();
    /** Chunks that may hold a start and are not judged yet (in the order they are looked at when idle). */
    private final LinkedHashSet<Long> pending = new LinkedHashSet<>();
    /** Waiting chunks to look at: a chunk around them was read since they were last looked at. */
    private final LinkedHashSet<Long> toCheck = new LinkedHashSet<>();
    private final Set<Long> judged = new HashSet<>();
    /** What is known about the fits with their origin on each chunk's corner. */
    private final Map<Long, Origin> origins = new HashMap<>();
    private Job job;
    private int idleChecks;
    /** The piece of work in progress: votes for an origin, a fit's comparison, a cluster test. */
    private Origin voting;
    private int votingSource;
    private int votingNext;
    private RigidRecognizer.Verification verification;
    private Origin verifyingOrigin;
    private int verifyingIndex;
    private RigidRecognizer.ClusterTest clusterTest;

    /** The fits with their origin on one chunk's corner. */
    private static final class Origin {
        final long key;
        /** In the order {@link RigidRecognizer#candidates} gives them; null until the votes are counted. */
        List<RigidRecognizer.Candidate> candidates;
        /** For each candidate, once verified: the fit, or null when it cannot matter. */
        RigidRecognizer.Detection[] detections;
        boolean[] verified;
        /** Whether a fit here that could start here is acceptable; null until known. */
        Boolean ownAcceptable;
        /** The answers of the cluster test. */
        final Map<RigidRecognizer.Detection, Boolean> clusterPiece = new IdentityHashMap<>();

        Origin(long key) {
            this.key = key;
        }

        void voted(List<RigidRecognizer.Candidate> candidates) {
            this.candidates = candidates;
            detections = new RigidRecognizer.Detection[candidates.size()];
            verified = new boolean[candidates.size()];
        }

        /** Forgets the comparisons with the world (keeping the votes); true when there were any. */
        boolean forgetComparisons() {
            boolean any = ownAcceptable != null || forgetClusterAnswers();
            if (candidates != null) {
                for (int i = 0; i < verified.length && !any; i++) {
                    any = verified[i];
                }
                detections = new RigidRecognizer.Detection[candidates.size()];
                verified = new boolean[candidates.size()];
            }
            ownAcceptable = null;
            return any;
        }

        /** Forgets the answers of the cluster test; true when there were any. */
        boolean forgetClusterAnswers() {
            boolean any = !clusterPiece.isEmpty();
            clusterPiece.clear();
            return any;
        }
    }

    /** The chunk being judged, and how far along. */
    private static final class Job {
        final long key;
        final Origin own;
        int stage;
        int index;
        int candidate;
        RigidRecognizer.Resolution resolution;

        Job(long key, Origin own, int stage) {
            this.key = key;
            this.own = own;
            this.stage = stage;
        }
    }

    public RigidTracker(RigidRecognizer recognizer, boolean nether) {
        this.recognizer = recognizer;
        this.nether = nether;
        this.ownReach = recognizer.readSpan();
        this.reach = RIVALS + Math.max(recognizer.readSpan(), recognizer.clusterSpan());
        this.votes = recognizer.votes();
    }

    public RigidRecognizer recognizer() {
        return recognizer;
    }

    /** How far from a chunk everything must be loaded and read before a start there is reported. */
    public int reach() {
        return reach;
    }

    /** The trigger blocks of a chunk were read ({@link RigidRecognizer#pack}); call again when it is loaded again. */
    public void chunkScanned(int chunkX, int chunkZ, int[] found) {
        long key = RigidRecognizer.chunkKey(chunkX, chunkZ);
        int[] before = triggers.put(key, found.clone());
        boolean changed = before == null || !Arrays.equals(before, found);
        // Its blocks may have changed since it was read last: what was learned from them is learned afresh when
        // needed. Votes change only with the trigger blocks; fits are compared again, and cluster tests done again.
        int span = recognizer.span();
        int readSpan = recognizer.readSpan();
        int affected = Math.max(readSpan, recognizer.clusterSpan());
        boolean forgot = false;
        for (int x = chunkX - affected; x <= chunkX + affected; x++) {
            for (int z = chunkZ - affected; z <= chunkZ + affected; z++) {
                long originKey = RigidRecognizer.chunkKey(x, z);
                Origin origin = origins.get(originKey);
                if (origin == null) {
                    continue;
                }
                int distance = Math.max(Math.abs(x - chunkX), Math.abs(z - chunkZ));
                if (changed && distance <= span) {
                    origins.remove(originKey);
                    forgot = true;
                } else {
                    forgot |= distance <= readSpan ? origin.forgetComparisons() : origin.forgetClusterAnswers();
                }
                if (verifyingOrigin == origin) {
                    verification = null;
                    verifyingOrigin = null;
                }
            }
        }
        if (forgot && job != null && near(job.key, chunkX, chunkZ, reach)) {
            stopJob();
        }
        if (found.length > 0) {
            for (int x = chunkX - span; x <= chunkX + span; x++) {
                for (int z = chunkZ - span; z <= chunkZ + span; z++) {
                    long around = RigidRecognizer.chunkKey(x, z);
                    if (!judged.contains(around) && (job == null || job.key != around)) {
                        pending.add(around);
                    }
                }
            }
        }
        for (int x = chunkX - reach; x <= chunkX + reach; x++) {
            for (int z = chunkZ - reach; z <= chunkZ + reach; z++) {
                long around = RigidRecognizer.chunkKey(x, z);
                if (pending.contains(around)) {
                    toCheck.add(around);
                }
            }
        }
    }

    /** Forgets what was found more than {@code radius} chunks from the given chunk (in either direction). */
    public void forgetFarFrom(int chunkX, int chunkZ, int radius) {
        triggers.keySet().removeIf(key -> !near(key, chunkX, chunkZ, radius));
        pending.removeIf(key -> !near(key, chunkX, chunkZ, radius));
        toCheck.removeIf(key -> !near(key, chunkX, chunkZ, radius));
        judged.removeIf(key -> !near(key, chunkX, chunkZ, radius));
        origins.keySet().removeIf(key -> !near(key, chunkX, chunkZ, radius));
        if (job != null && !near(job.key, chunkX, chunkZ, radius)) {
            job = null;
            voting = null;
            verification = null;
            verifyingOrigin = null;
            clusterTest = null;
        }
    }

    private static boolean near(long key, int chunkX, int chunkZ, int radius) {
        return Math.abs((int) (key >> 32) - chunkX) <= radius && Math.abs((int) key - chunkZ) <= radius;
    }

    /** Chunks waiting to be judged, the one being judged included (for tests and the log). */
    public int pendingCount() {
        return pending.size() + (job == null ? 0 : 1);
    }

    /**
     * Does work until {@code timeUp} says stop or nothing can be done: picks a chunk whose surroundings are loaded
     * and read and judges it, one small step at a time (a chunk may take many calls). Returns the fits accepted in
     * the chunks decided.
     */
    public List<RigidRecognizer.Detection> work(BlockView view, BooleanSupplier timeUp) {
        List<RigidRecognizer.Detection> found = new ArrayList<>();
        if (job != null && !around(job.key, job.stage == OWN ? ownReach : reach, view)) {
            // Something unloaded since the last call: go on later. (What was learned so far is kept.)
            stopJob();
        }
        idleChecks = 0;
        while (!timeUp.getAsBoolean()) {
            if (job == null) {
                if (!startJob(view, timeUp)) {
                    break;
                }
            } else if (step(view, found)) {
                finishJob();
            }
        }
        return found;
    }

    /** Finds a chunk that can be worked on and starts on it; false when there is none (or the time is up). */
    private boolean startJob(BlockView view, BooleanSupplier timeUp) {
        for (int i = 0; i < READY_CHECKS; i++) {
            if (i > 0 && timeUp.getAsBoolean()) {
                return false;
            }
            Long key = poll(toCheck);
            if (key == null) {
                if (idleChecks >= IDLE_CHECKS || pending.isEmpty()) {
                    return false;
                }
                idleChecks++;
                key = poll(pending);
                pending.add(key);
            }
            if (!pending.contains(key)) {
                continue;
            }
            Origin own = origins.get(key);
            boolean withRivals = own != null && Boolean.TRUE.equals(own.ownAcceptable);
            if (!around(key, withRivals ? reach : ownReach, view)) {
                continue;
            }
            pending.remove(key);
            if (own == null) {
                own = new Origin(key);
                origins.put(key, own);
            }
            job = new Job(key, own, withRivals ? GATHER : OWN);
            return true;
        }
        return false;
    }

    private static Long poll(LinkedHashSet<Long> set) {
        Iterator<Long> first = set.iterator();
        if (!first.hasNext()) {
            return null;
        }
        Long key = first.next();
        first.remove();
        return key;
    }

    /** The job waits again (for chunks around it to load, or to be counted afresh). */
    private void stopJob() {
        pending.add(job.key);
        job = null;
    }

    /** The chunk is judged: what only its judging needed is forgotten. */
    private void finishJob() {
        long key = job.key;
        judged.add(key);
        job = null;
        int x = (int) (key >> 32);
        int z = (int) key;
        for (int ox = x - RIVALS; ox <= x + RIVALS; ox++) {
            for (int oz = z - RIVALS; oz <= z + RIVALS; oz++) {
                long origin = RigidRecognizer.chunkKey(ox, oz);
                if (origins.containsKey(origin) && !anyPendingNear(ox, oz)) {
                    origins.remove(origin);
                }
            }
        }
    }

    private boolean anyPendingNear(int chunkX, int chunkZ) {
        for (int x = chunkX - RIVALS; x <= chunkX + RIVALS; x++) {
            for (int z = chunkZ - RIVALS; z <= chunkZ + RIVALS; z++) {
                if (pending.contains(RigidRecognizer.chunkKey(x, z))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** One small piece of the current job; true when the chunk is decided. */
    private boolean step(BlockView view, List<RigidRecognizer.Detection> found) {
        Job j = job;
        int side = 2 * RIVALS + 1;
        switch (j.stage) {
            case OWN -> {
                Origin own = j.own;
                if (own.candidates == null) {
                    vote(own);
                    return false;
                }
                if (own.ownAcceptable == null) {
                    while (j.candidate < own.candidates.size() && !couldStart(own.candidates.get(j.candidate))) {
                        j.candidate++;
                    }
                    if (j.candidate < own.candidates.size()) {
                        int i = j.candidate;
                        if (!own.verified[i]) {
                            verify(own, i, view);
                        } else {
                            j.candidate++;
                            if (own.detections[i] != null && recognizer.acceptable(own.detections[i], view)) {
                                own.ownAcceptable = true;
                            }
                        }
                        return false;
                    }
                    own.ownAcceptable = false;
                }
                if (!own.ownAcceptable) {
                    // Nothing here can be reported, whatever the rivals.
                    return true;
                }
                if (!around(j.key, reach, view)) {
                    // Rivals are needed, and not everything around is there yet.
                    stopJob();
                    return false;
                }
                j.stage = GATHER;
                j.index = 0;
                return false;
            }
            case GATHER -> {
                while (j.index < side * side) {
                    Origin origin = origin(j, j.index);
                    if (origin.candidates == null) {
                        vote(origin);
                        return false;
                    }
                    j.index++;
                }
                j.stage = VERIFY;
                j.index = 0;
                j.candidate = 0;
                return false;
            }
            case VERIFY -> {
                while (j.index < side * side) {
                    Origin origin = origin(j, j.index);
                    while (j.candidate < origin.candidates.size()) {
                        if (!origin.verified[j.candidate]) {
                            verify(origin, j.candidate, view);
                            return false;
                        }
                        j.candidate++;
                    }
                    j.index++;
                    j.candidate = 0;
                }
                List<RigidRecognizer.Detection> batch = new ArrayList<>();
                for (int i = 0; i < side * side; i++) {
                    for (RigidRecognizer.Detection d : origin(j, i).detections) {
                        if (d != null) {
                            batch.add(d);
                        }
                    }
                }
                j.resolution = recognizer.resolution(batch);
                j.stage = RESOLVE;
                return false;
            }
            default -> {
                RigidRecognizer.Detection waiting = j.resolution.waitingFor();
                if (waiting != null && clusterAnswer(waiting) == null) {
                    clusterStep(waiting, view);
                    return false;
                }
                if (!j.resolution.step(view, this::clusterAnswer)) {
                    return false;
                }
                int chunkX = (int) (j.key >> 32);
                int chunkZ = (int) j.key;
                for (RigidRecognizer.Detection d : j.resolution.result()) {
                    if (d.chunkX() == chunkX && d.chunkZ() == chunkZ) {
                        found.add(d);
                    }
                }
                return true;
            }
        }
    }

    /** The origin of the job's batch at this index (x-major over the chunks within {@link #RIVALS}). */
    private Origin origin(Job j, int index) {
        int side = 2 * RIVALS + 1;
        long key = RigidRecognizer.chunkKey((int) (j.key >> 32) + index / side - RIVALS, (int) j.key + index % side - RIVALS);
        Origin origin = origins.get(key);
        if (origin == null) {
            origin = new Origin(key);
            origins.put(key, origin);
        }
        return origin;
    }

    private boolean couldStart(RigidRecognizer.Candidate candidate) {
        return recognizer.rules(candidate.layout().kind()).couldStartIn(candidate.chunkX(), candidate.chunkZ());
    }

    /** Counts up to {@link #STEP_TRIGGERS} votes for an origin (its chunks within span are read). */
    private void vote(Origin origin) {
        int span = recognizer.span();
        int side = 2 * span + 1;
        int originX = (int) (origin.key >> 32);
        int originZ = (int) origin.key;
        if (voting != origin) {
            voting = origin;
            votingSource = 0;
            votingNext = 0;
            votes.start(originX, originZ);
        }
        int budget = STEP_TRIGGERS;
        while (votingSource < side * side && budget > 0) {
            int sourceX = originX + votingSource / side - span;
            int sourceZ = originZ + votingSource % side - span;
            int[] found = triggers.get(RigidRecognizer.chunkKey(sourceX, sourceZ));
            int to = Math.min(found.length, votingNext + budget);
            votes.add(sourceX, sourceZ, found, votingNext, to);
            budget -= Math.max(1, to - votingNext);
            votingNext = to;
            if (votingNext == found.length) {
                votingSource++;
                votingNext = 0;
            }
        }
        if (votingSource == side * side) {
            origin.voted(votes.candidates());
            voting = null;
        }
    }

    /** Compares up to {@link #STEP_READS} blocks of a fit. */
    private void verify(Origin origin, int index, BlockView view) {
        if (verifyingOrigin != origin || verifyingIndex != index || verification == null) {
            verification = recognizer.verification(origin.candidates.get(index), nether);
            verifyingOrigin = origin;
            verifyingIndex = index;
        }
        if (verification.step(view, STEP_READS)) {
            origin.detections[index] = verification.result();
            origin.verified[index] = true;
            verification = null;
            verifyingOrigin = null;
        }
    }

    private Boolean clusterAnswer(RigidRecognizer.Detection d) {
        Origin origin = origins.get(RigidRecognizer.chunkKey(d.chunkX(), d.chunkZ()));
        return origin == null ? null : origin.clusterPiece.get(d);
    }

    /** One step of the cluster test of a fit in the batch. */
    private void clusterStep(RigidRecognizer.Detection d, BlockView view) {
        if (clusterTest == null || clusterTest.detection() != d) {
            clusterTest = recognizer.clusterTest(d);
        }
        if (clusterTest.step(view, STEP_READS)) {
            origins.get(RigidRecognizer.chunkKey(d.chunkX(), d.chunkZ())).clusterPiece.put(d, clusterTest.isClusterPiece());
            clusterTest = null;
        }
    }

    /** Whether everything within {@code radius} chunks is loaded and read. */
    private boolean around(long key, int radius, BlockView view) {
        int chunkX = (int) (key >> 32);
        int chunkZ = (int) key;
        for (int x = chunkX - radius; x <= chunkX + radius; x++) {
            for (int z = chunkZ - radius; z <= chunkZ + radius; z++) {
                if (!triggers.containsKey(RigidRecognizer.chunkKey(x, z)) || !view.isLoaded(x, z)) {
                    return false;
                }
            }
        }
        return true;
    }
}
