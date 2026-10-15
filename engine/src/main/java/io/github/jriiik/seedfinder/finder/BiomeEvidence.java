package io.github.jriiik.seedfinder.finder;

import io.github.jriiik.seedfinder.biome.BiomeParameters;
import io.github.jriiik.seedfinder.biome.BiomeZoom;
import io.github.jriiik.seedfinder.biome.OverworldBiomes;
import io.github.jriiik.seedfinder.version.MinecraftVersion;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Overworld biomes as evidence for the upper 16 bits of the world seed. They depend on all 64 bits, so once the
 * structure seed (the lower 48 bits) is known, each of its 65,536 world seeds can be checked against the biomes seen.
 *
 * <p>When is a seed proven? The noted biomes are put into groups by position only: a group is the notes within
 * {@link #GROUP_RADIUS} blocks of its first note, and groups count as evidence only when all their notes are at
 * least {@link #groupSpacing} blocks apart (biomes close together are alike in every world, so near groups are not
 * independent): as many as can be, chosen by the notes alone ({@link #groups}). For each counted group, the number k
 * of the other candidates that fit it
 * estimates how often a wrong seed fits it: (k + {@link #PSEUDO_COUNT}) / M of the M candidates checked. A seed
 * is proven when it is the only candidate that fits every note and a wrong seed would fit all counted groups with
 * a chance of at most {@link #MAX_FALSE_FIT_CHANCE} over all the seeds it could have been mistaken for:
 * <pre>
 *   H x LIFT^(G - 1) x product over the G groups of (k + 3) / M  &lt;= 0.001
 * </pre>
 * H is the number of seeds tested: all candidates checked, or, when the seed is one Minecraft could have picked
 * itself or a text seed would give ({@link Seeds#randomSeedCandidates}, {@link Seeds#textSeedCandidate}), only those
 * likely seeds. {@link #LIFT} allows for groups that are still slightly alike at the spacing. The counts are measured
 * on the candidates themselves, so areas that fit many seeds (all ocean, say) prove little, and notes from chunks a
 * server changed or an older version made can't make a wrong seed look proven: they only make the right one miss.
 *
 * <p>In practice (measured in 60 worlds): one loaded area (render distance 8) with two or more biomes proves a
 * likely seed; any other seed needs a second area {@link #groupSpacing} blocks away. One area can never prove a
 * seed among all 65,536 candidates: with k = 0 the chance is still estimated at 3/65,536 per candidate.
 */
public final class BiomeEvidence {

    /** A seed counts as proven when a wrong seed would fit the biomes with at most this chance. */
    public static final double MAX_FALSE_FIT_CHANCE = 0.001;
    /** The world seeds of one structure seed: one per value of the upper 16 bits. */
    public static final int CANDIDATES = 1 << 16;
    /** Notes within this many blocks (horizontally) of a group's first note belong to it. */
    public static final int GROUP_RADIUS = 512;
    /** Added to every group's count of fitting candidates: a rate below 3 in M can't be shown from M candidates. */
    static final int PSEUDO_COUNT = 3;
    /** How much more often than independent groups may fit together at the spacing (measured at most 1.1 to 1.6). */
    static final double LIFT = 2;
    /** Above this many structure seeds the biomes are not checked (each costs about 3 seconds per preset). */
    public static final int MAX_STRUCTURE_SEEDS = 16;
    /** The order in which presets are tried: the next only when no seed of the earlier ones fits. */
    public static final List<OverworldBiomes.Preset> PRESET_ORDER = List.of(OverworldBiomes.Preset.NORMAL, OverworldBiomes.Preset.LARGE_BIOMES,
        OverworldBiomes.Preset.AMPLIFIED);

    private final MinecraftVersion version;
    private final int noted;
    private final List<BiomeObservation> notes;
    private final List<String> problems;
    private final List<Cluster> clusters;

    /** Notes close together; their quarts to check are worked out once. */
    private record Cluster(int minX, int maxX, int minZ, int maxZ, int[] biome, int[][] quarts, String key) {

        int size() {
            return biome.length;
        }

        int distinctBiomes() {
            return (int) java.util.Arrays.stream(biome).distinct().count();
        }

        /** Horizontal distance between the boxes around two clusters' notes (never more than between any two notes). */
        double distanceTo(Cluster other) {
            long dx = Math.max(0, Math.max(other.minX - maxX, minX - other.maxX));
            long dz = Math.max(0, Math.max(other.minZ - maxZ, minZ - other.maxZ));
            return Math.sqrt((double) dx * dx + (double) dz * dz);
        }
    }

    /**
     * @param hashedSeed the hashed seed the server sent, if noted: F3 notes are then checked at the exact cell the
     *                   game zooms to; without it any of the 8 cells around the block counts (about 9% weaker)
     */
    public BiomeEvidence(MinecraftVersion version, List<BiomeObservation> notes, OptionalLong hashedSeed) {
        this.version = version;
        OverworldBiomes biomes = OverworldBiomes.of(version, OverworldBiomes.Preset.NORMAL);
        BiomeParameters parameters = biomes.parameters();
        List<BiomeObservation> valid = new ArrayList<>();
        List<String> found = new ArrayList<>();
        Set<String> unknown = new LinkedHashSet<>();
        for (BiomeObservation note : new LinkedHashSet<>(notes)) {
            if (parameters.biomeIndex(note.biome()) < 0) {
                unknown.add(note.biome());
            } else {
                valid.add(note);
            }
        }
        unknown.forEach(biome -> found.add("'" + biome + "' is not an overworld biome of Minecraft " + version.displayName()
            + "; those notes are left out"));
        this.noted = valid.size();
        this.notes = informative(valid);
        this.problems = List.copyOf(found);
        this.clusters = cluster(this.notes, biomes, hashedSeed);
    }

    /**
     * The notes worth checking. Of the stored cells (from the mod, often whole areas), a cell inside a patch of one
     * biome says little more than its neighbours, and checking it costs time for every seed that fits the patch; so
     * only cells next to a cell of another biome (borders, where the evidence is) and one cell per chunk (its
     * north-west one) are kept. F3 notes are all kept. This depends only on the notes, never on the seeds checked,
     * so the counts the rule uses are measured on exactly the notes kept.
     */
    private static List<BiomeObservation> informative(List<BiomeObservation> notes) {
        Map<List<Integer>, String> cells = new LinkedHashMap<>();
        for (BiomeObservation note : notes) {
            if (note.exactCell()) {
                cells.put(List.of(note.x() >> 2, note.y() >> 2, note.z() >> 2), note.biome());
            }
        }
        List<BiomeObservation> kept = new ArrayList<>();
        Set<List<Integer>> keptCells = new LinkedHashSet<>();
        for (BiomeObservation note : notes) {
            if (!note.exactCell()) {
                kept.add(note);
                continue;
            }
            int qx = note.x() >> 2;
            int qy = note.y() >> 2;
            int qz = note.z() >> 2;
            if (!keptCells.add(List.of(qx, qy, qz))) {
                continue;
            }
            boolean border = false;
            for (int[] step : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                String neighbour = cells.get(List.of(qx + step[0], qy, qz + step[1]));
                border |= neighbour != null && !neighbour.equals(note.biome());
            }
            if (border || (qx & 3) == 0 && (qz & 3) == 0) {
                kept.add(note);
            }
        }
        return List.copyOf(kept);
    }

    private static List<Cluster> cluster(List<BiomeObservation> notes, OverworldBiomes biomes, OptionalLong hashedSeed) {
        List<List<BiomeObservation>> groups = new ArrayList<>();
        List<int[]> centers = new ArrayList<>();
        for (BiomeObservation note : notes) {
            int found = -1;
            for (int i = 0; i < centers.size() && found < 0; i++) {
                long dx = note.x() - centers.get(i)[0];
                long dz = note.z() - centers.get(i)[1];
                if (dx * dx + dz * dz <= (long) GROUP_RADIUS * GROUP_RADIUS) {
                    found = i;
                }
            }
            if (found < 0) {
                found = groups.size();
                groups.add(new ArrayList<>());
                centers.add(new int[] {note.x(), note.z()});
            }
            groups.get(found).add(note);
        }
        List<Cluster> clusters = new ArrayList<>();
        for (List<BiomeObservation> group : groups) {
            List<BiomeObservation> ordered = alternatingBiomes(group);
            int[] biome = new int[ordered.size()];
            int[][] quarts = new int[ordered.size()][];
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxZ = Integer.MIN_VALUE;
            StringBuilder key = new StringBuilder();
            for (int i = 0; i < ordered.size(); i++) {
                BiomeObservation note = ordered.get(i);
                biome[i] = biomes.parameters().biomeIndex(note.biome());
                quarts[i] = quartsOf(note, biomes, hashedSeed);
                minX = Math.min(minX, note.x());
                maxX = Math.max(maxX, note.x());
                minZ = Math.min(minZ, note.z());
                maxZ = Math.max(maxZ, note.z());
                key.append(biome[i]);
                for (int q : quarts[i]) {
                    key.append(',').append(q);
                }
                key.append(';');
            }
            clusters.add(new Cluster(minX, maxX, minZ, maxZ, biome, quarts, digest(key.toString())));
        }
        return List.copyOf(clusters);
    }

    /** A short stand-in for a place's notes in cache keys (SHA-256: two different places never share one). */
    private static String digest(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java runtime has SHA-256", e);
        }
    }

    /**
     * The notes with one of each biome first, then the next of each, and so on: a wrong seed usually fails on a
     * biome it doesn't have nearby, so it is found out sooner than by a run of the same biome.
     */
    private static List<BiomeObservation> alternatingBiomes(List<BiomeObservation> notes) {
        Map<String, List<BiomeObservation>> byBiome = new LinkedHashMap<>();
        for (BiomeObservation note : notes) {
            byBiome.computeIfAbsent(note.biome(), b -> new ArrayList<>()).add(note);
        }
        List<BiomeObservation> ordered = new ArrayList<>();
        for (int round = 0; ordered.size() < notes.size(); round++) {
            for (List<BiomeObservation> list : byBiome.values()) {
                if (round < list.size()) {
                    ordered.add(list.get(round));
                }
            }
        }
        return ordered;
    }

    /** The quarts (x, y, z packed in threes) one of which must have the noted biome; y as the game clamps it. */
    private static int[] quartsOf(BiomeObservation note, OverworldBiomes biomes, OptionalLong hashedSeed) {
        int[][] candidates;
        if (note.exactCell()) {
            candidates = new int[][] {{note.x() >> 2, note.y() >> 2, note.z() >> 2}};
        } else if (hashedSeed.isPresent()) {
            candidates = new int[][] {BiomeZoom.quart(hashedSeed.getAsLong(), note.x(), note.y(), note.z())};
        } else {
            candidates = BiomeZoom.candidates(note.x(), note.y(), note.z());
        }
        Set<List<Integer>> distinct = new LinkedHashSet<>();
        for (int[] quart : candidates) {
            int y = Math.max(biomes.minQuartY(), Math.min(biomes.maxQuartY(), quart[1]));
            distinct.add(List.of(quart[0], y, quart[2]));
        }
        int[] packed = new int[distinct.size() * 3];
        int i = 0;
        for (List<Integer> quart : distinct) {
            packed[i++] = quart.get(0);
            packed[i++] = quart.get(1);
            packed[i++] = quart.get(2);
        }
        return packed;
    }

    /** How many notes were given (unknown biomes left out). */
    public int noted() {
        return noted;
    }

    /** The notes checked: see {@link #informative}. */
    public List<BiomeObservation> notes() {
        return notes;
    }

    /** Notes that can't be used, in words. */
    public List<String> problems() {
        return problems;
    }

    public boolean isEmpty() {
        return clusters.isEmpty();
    }

    /** How many places (groups of notes within {@link #GROUP_RADIUS} blocks) were noted. */
    public int places() {
        return clusters.size();
    }

    /** How many blocks apart two groups must be to count separately in a world of this preset. */
    public static int groupSpacing(OverworldBiomes.Preset preset) {
        // Large-biome worlds use noises 4 times coarser: their temperatures stay alike over 12,000 blocks.
        return preset == OverworldBiomes.Preset.LARGE_BIOMES ? 16_384 : 4_096;
    }

    /** Up to this many places, {@link #groups} finds the most places that can count together; above it, it picks greedily. */
    private static final int EXACT_GROUP_CHOICE = 16;

    private final Map<OverworldBiomes.Preset, List<Integer>> groups = new java.util.EnumMap<>(OverworldBiomes.Preset.class);

    /**
     * The places that count as separate groups of evidence in a world of this preset, in note order: as many places
     * as can all be {@link #groupSpacing} apart, preferring places with more biomes, then more notes, then those noted
     * first. The choice depends on the notes only, never on the seeds checked, so the counts the rule uses stay fair.
     */
    public synchronized List<Integer> groups(OverworldBiomes.Preset preset) {
        return groups.computeIfAbsent(preset, p -> chooseGroups(groupSpacing(p)));
    }

    private List<Integer> chooseGroups(int spacing) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < clusters.size(); i++) {
            order.add(i);
        }
        order.sort(java.util.Comparator.comparingInt((Integer i) -> -clusters.get(i).distinctBiomes())
            .thenComparingInt(i -> -clusters.get(i).size())
            .thenComparingInt(i -> i));
        List<Integer> best = new ArrayList<>();
        if (order.size() <= EXACT_GROUP_CHOICE) {
            choose(order, 0, new ArrayList<>(), best, spacing);
        } else {
            for (int place : order) {
                if (farFromAll(place, best, spacing)) {
                    best.add(place);
                }
            }
        }
        best.sort(null);
        return List.copyOf(best);
    }

    /** Depth first, taking places before leaving them out: the first largest set found is the one preferred. */
    private void choose(List<Integer> order, int next, List<Integer> chosen, List<Integer> best, int spacing) {
        if (chosen.size() > best.size()) {
            best.clear();
            best.addAll(chosen);
        }
        if (next >= order.size() || chosen.size() + (order.size() - next) <= best.size()) {
            return;
        }
        int place = order.get(next);
        if (farFromAll(place, chosen, spacing)) {
            chosen.add(place);
            choose(order, next + 1, chosen, best, spacing);
            chosen.remove(chosen.size() - 1);
        }
        choose(order, next + 1, chosen, best, spacing);
    }

    private boolean farFromAll(int place, List<Integer> chosen, int spacing) {
        for (int other : chosen) {
            if (clusters.get(place).distanceTo(clusters.get(other)) < spacing) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ checking candidates

    /** Which of the 65,536 world seeds of one structure seed fit each place's notes, for one preset. */
    public static final class Scan {

        private final long structureSeed;
        private final OverworldBiomes.Preset preset;
        /** Per place, one bit per value of the upper 16 bits. */
        private final long[][] fits;
        private final boolean complete;

        Scan(long structureSeed, OverworldBiomes.Preset preset, long[][] fits, boolean complete) {
            this.structureSeed = structureSeed;
            this.preset = preset;
            this.fits = fits;
            this.complete = complete;
        }

        public long structureSeed() {
            return structureSeed;
        }

        public OverworldBiomes.Preset preset() {
            return preset;
        }

        /** False when the check was stopped early; such a scan proves nothing. */
        public boolean complete() {
            return complete;
        }

        /** The world seeds that fit every note. */
        public List<Long> fittingAll() {
            List<Long> seeds = new ArrayList<>();
            for (int word = 0; word < CANDIDATES / 64; word++) {
                long all = -1L;
                for (long[] place : fits) {
                    all &= place[word];
                }
                while (all != 0) {
                    int bit = Long.numberOfTrailingZeros(all);
                    all &= all - 1;
                    seeds.add(worldSeed(structureSeed, word * 64 + bit));
                }
            }
            return seeds;
        }

        boolean fits(int place, long worldSeed) {
            int upper = (int) (worldSeed >>> 48);
            return (fits[place][upper >> 6] >>> (upper & 63) & 1) != 0;
        }

        int count(int place) {
            int count = 0;
            for (long word : fits[place]) {
                count += Long.bitCount(word);
            }
            return count;
        }
    }

    static long worldSeed(long structureSeed, int upper) {
        return ((long) upper << 48) | (structureSeed & 0xFFFF_FFFF_FFFFL);
    }

    /**
     * Remembers which candidates fit each place, so that a search run again after a new place was noted only checks
     * the new place. Thread-safe; keeps the most recently used entries (about 8 KB each).
     */
    public static final class ScanCache {

        private final Map<String, long[]> entries;

        public ScanCache(int maxEntries) {
            this.entries = new LinkedHashMap<>(16, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, long[]> eldest) {
                    return size() > maxEntries;
                }
            };
        }

        synchronized long[] get(String key) {
            return entries.get(key);
        }

        synchronized void put(String key, long[] fits) {
            entries.put(key, fits);
        }
    }

    /**
     * Checks all 65,536 world seeds of a structure seed against every place's notes.
     *
     * @param cache may be null
     * @param stop  checked often; when it says stop, the scan returns incomplete
     */
    public Scan scan(long structureSeed, OverworldBiomes.Preset preset, int threads, BooleanSupplier stop, ScanCache cache) {
        long[][] fits = new long[clusters.size()][];
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < clusters.size(); i++) {
            long[] cached = cache == null ? null : cache.get(cacheKey(structureSeed, preset, clusters.get(i)));
            if (cached != null) {
                fits[i] = cached;
            } else {
                fits[i] = new long[CANDIDATES / 64];
                missing.add(i);
            }
        }
        if (missing.isEmpty()) {
            return new Scan(structureSeed, preset, fits, true);
        }
        OverworldBiomes biomes = OverworldBiomes.of(version, preset);
        int[] places = missing.stream().mapToInt(Integer::intValue).toArray();
        // Blocks of 64 candidates, one word of every place's bits, so threads never write the same word.
        AtomicInteger next = new AtomicInteger();
        Runnable worker = () -> {
            OverworldBiomes.Sampler sampler = biomes.newSampler();
            for (int word = next.getAndIncrement(); word < CANDIDATES / 64; word = next.getAndIncrement()) {
                if (stop.getAsBoolean()) {
                    return;
                }
                for (int bit = 0; bit < 64; bit++) {
                    sampler.seed(worldSeed(structureSeed, word * 64 + bit));
                    for (int place : places) {
                        if (fits(sampler, clusters.get(place))) {
                            fits[place][word] |= 1L << bit;
                        }
                    }
                }
            }
        };
        runOn(Math.max(1, threads), worker);
        boolean complete = next.get() >= CANDIDATES / 64 && !stop.getAsBoolean();
        if (complete && cache != null) {
            for (int place : places) {
                cache.put(cacheKey(structureSeed, preset, clusters.get(place)), fits[place]);
            }
        }
        return new Scan(structureSeed, preset, fits, complete);
    }

    /** True if one world seed fits every note under this preset (quick: no other candidates are checked). */
    public boolean fits(long worldSeed, OverworldBiomes.Preset preset) {
        OverworldBiomes.Sampler sampler = OverworldBiomes.of(version, preset).newSampler().seed(worldSeed);
        for (Cluster cluster : clusters) {
            if (!fits(sampler, cluster)) {
                return false;
            }
        }
        return true;
    }

    /** True if one world seed fits every note under some preset of {@link #PRESET_ORDER}. */
    public boolean fitsUnderAnyPreset(long worldSeed) {
        return PRESET_ORDER.stream().anyMatch(preset -> fits(worldSeed, preset));
    }

    private static boolean fits(OverworldBiomes.Sampler sampler, Cluster cluster) {
        for (int i = 0; i < cluster.size(); i++) {
            int[] quarts = cluster.quarts[i];
            boolean any = false;
            for (int q = 0; q < quarts.length && !any; q += 3) {
                any = sampler.couldBe(cluster.biome[i], quarts[q], quarts[q + 1], quarts[q + 2]);
            }
            if (!any) {
                return false;
            }
        }
        return true;
    }

    private String cacheKey(long structureSeed, OverworldBiomes.Preset preset, Cluster cluster) {
        return version.displayName() + "|" + preset + "|" + (structureSeed & 0xFFFF_FFFF_FFFFL) + "|" + cluster.key;
    }

    private static void runOn(int threads, Runnable worker) {
        if (threads == 1) {
            worker.run();
            return;
        }
        List<Thread> started = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread thread = new Thread(worker, "biome-check-" + i);
            thread.setDaemon(true);
            thread.start();
            started.add(thread);
        }
        for (Thread thread : started) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ------------------------------------------------------------------ deciding

    /**
     * What the biomes say after the scans.
     *
     * @param fitting        the world seeds that fit every note, over all scans
     * @param seed           the proven world seed, if any
     * @param preset         the preset under which it fits
     * @param falseFitChance for a single fitting seed, at most this chance that a wrong seed fits the notes as well
     *                       (1 when no single seed fits)
     * @param groups         the separate groups of notes that counted (for the fitting seed's preset, or normal)
     * @param likely         whether the single fitting seed is one Minecraft could have picked itself or a text seed
     *                       would give
     * @param complete       false when a scan was stopped early
     */
    public record Decision(List<Long> fitting, OptionalLong seed, Optional<OverworldBiomes.Preset> preset, double falseFitChance, int groups,
                           boolean likely, boolean complete) {

        public Decision {
            fitting = List.copyOf(fitting);
        }

        public boolean proven() {
            return seed.isPresent();
        }
    }

    /**
     * Decides from the scans done so far.
     *
     * @param likelySeeds the seeds Minecraft could have picked itself or a text seed would give, for the structure seeds
     *                    scanned
     */
    public Decision decide(List<Scan> scans, Collection<Long> likelySeeds) {
        boolean complete = scans.stream().allMatch(Scan::complete);
        Map<Long, OverworldBiomes.Preset> fitting = new LinkedHashMap<>();
        for (Scan scan : scans) {
            for (long seed : scan.fittingAll()) {
                fitting.putIfAbsent(seed, scan.preset());
            }
        }
        List<Long> seeds = new ArrayList<>(fitting.keySet());
        if (seeds.size() != 1 || !complete) {
            return new Decision(seeds, OptionalLong.empty(), Optional.empty(), 1, groups(OverworldBiomes.Preset.NORMAL).size(), false, complete);
        }
        long seed = seeds.get(0);
        OverworldBiomes.Preset preset = fitting.get(seed);
        Set<OverworldBiomes.Preset> presets = new LinkedHashSet<>();
        scans.forEach(scan -> presets.add(scan.preset()));
        long candidates = (long) scans.size() * CANDIDATES;
        boolean likely = likelySeeds.contains(seed);
        double tested = likely ? (double) likelySeeds.size() * presets.size() : candidates;
        double chance = Math.min(1, tested * fitChance(scans, preset, seed));
        boolean proven = chance <= MAX_FALSE_FIT_CHANCE;
        return new Decision(seeds, proven ? OptionalLong.of(seed) : OptionalLong.empty(), Optional.of(preset), chance, groups(preset).size(),
            likely, true);
    }

    /**
     * For one seed tested on its own (a typed seed): the first preset of {@link #PRESET_ORDER} under which it fits
     * every note, and at most this chance that a wrong seed fits the notes as well under some preset tried up to
     * that one (each preset tried is one more way for a wrong seed to fit). Checks the 65,536 seeds of its structure
     * seed under that preset (a few seconds, cached). Empty when it fits under no preset.
     *
     * @return the chance, or empty when the seed doesn't fit; NaN when the check was stopped
     */
    public OptionalDouble falseFitChance(long seed, int threads, BooleanSupplier stop, ScanCache cache) {
        for (int tried = 0; tried < PRESET_ORDER.size(); tried++) {
            OverworldBiomes.Preset preset = PRESET_ORDER.get(tried);
            if (!fits(seed, preset)) {
                continue;
            }
            Scan scan = scan(seed, preset, threads, stop, cache);
            if (!scan.complete()) {
                return OptionalDouble.of(Double.NaN);
            }
            return OptionalDouble.of(Math.min(1, (tried + 1) * fitChance(List.of(scan), preset, seed)));
        }
        return OptionalDouble.empty();
    }

    /** The chance that one wrong seed fits every counted group, measured on the other candidates of the preset's scans. */
    private double fitChance(List<Scan> scans, OverworldBiomes.Preset preset, long seed) {
        List<Scan> same = scans.stream().filter(scan -> scan.preset() == preset).toList();
        long candidates = (long) same.size() * CANDIDATES;
        List<Integer> groups = groups(preset);
        double chance = Math.pow(LIFT, groups.size() - 1);
        for (int place : groups) {
            long others = 0;
            for (Scan scan : same) {
                others += scan.count(place);
                if ((scan.structureSeed() & 0xFFFF_FFFF_FFFFL) == (seed & 0xFFFF_FFFF_FFFFL) && scan.fits(place, seed)) {
                    others--;
                }
            }
            chance *= (double) (others + PSEUDO_COUNT) / candidates;
        }
        return chance;
    }

    /**
     * Scans the structure seeds preset by preset (the next preset only when no seed of the earlier ones fits) and
     * decides.
     *
     * @param structureSeeds at most {@link #MAX_STRUCTURE_SEEDS}
     */
    public Decision search(Collection<Long> structureSeeds, Collection<Long> likelySeeds, int threads, BooleanSupplier stop, ScanCache cache) {
        return search(structureSeeds, likelySeeds, PRESET_ORDER, threads, stop, cache);
    }

    /** {@link #search} with the presets to try, in order (just one when the world's preset is known). */
    public Decision search(Collection<Long> structureSeeds, Collection<Long> likelySeeds, List<OverworldBiomes.Preset> presets, int threads,
                           BooleanSupplier stop, ScanCache cache) {
        List<Scan> scans = new ArrayList<>();
        Decision decision = decide(scans, likelySeeds);
        for (OverworldBiomes.Preset preset : presets) {
            for (long structureSeed : structureSeeds) {
                scans.add(scan(structureSeed, preset, threads, stop, cache));
            }
            decision = decide(scans, likelySeeds);
            if (!decision.fitting().isEmpty() || !decision.complete()) {
                return decision;
            }
        }
        return decision;
    }
}
