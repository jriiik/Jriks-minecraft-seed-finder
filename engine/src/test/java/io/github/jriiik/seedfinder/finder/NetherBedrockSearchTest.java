package io.github.jriiik.seedfinder.finder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.jriiik.seedfinder.Fixtures;
import io.github.jriiik.seedfinder.Seeds;
import io.github.jriiik.seedfinder.bedrock.BedrockDimension;
import io.github.jriiik.seedfinder.bedrock.BedrockObservation;
import io.github.jriiik.seedfinder.bedrock.BedrockPattern;
import io.github.jriiik.seedfinder.bedrock.BedrockRule;
import io.github.jriiik.seedfinder.rng.JavaRandom;
import io.github.jriiik.seedfinder.rng.LegacyPositionalFactory;
import io.github.jriiik.seedfinder.rng.RandomState;
import io.github.jriiik.seedfinder.rng.WorldgenSeeds;
import io.github.jriiik.seedfinder.version.MinecraftVersion;
import io.github.jriiik.seedfinder.version.VersionData;
import io.github.jriiik.seedfinder.world.ChunkPos;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.Test;

class NetherBedrockSearchTest {

    private static final VersionData DATA = MinecraftVersion.V26_3.data();
    private static final NetherBedrockSearch SEARCH = new NetherBedrockSearch(DATA);
    private static final BedrockDimension NETHER = DATA.bedrock().get(NetherBedrockSearch.NETHER);
    private static final BedrockDimension OVERWORLD = DATA.bedrock().get("minecraft:overworld");
    private static final BedrockRule FLOOR = NETHER.rules().stream().filter(rule -> !rule.inverted()).findFirst().orElseThrow();
    private static final BedrockRule ROOF = NETHER.rules().stream().filter(BedrockRule::inverted).findFirst().orElseThrow();
    private static final int[] FLOOR_YS = {1, 2, 3, 4};
    private static final int[] ROOF_YS = {123, 124, 125, 126};
    private static final int[] BOTH_YS = {1, 2, 3, 4, 123, 124, 125, 126};
    private static final long MASK = JavaRandom.MASK;
    private static final BooleanSupplier NEVER = () -> false;
    private static final IntConsumer QUIET = pieces -> { };
    private static final int THREADS = 2;

    /** The blocks of a box of columns at these heights, as a seed's nether has them. */
    private static List<BedrockObservation> columns(BedrockPattern pattern, int minX, int minZ, int sizeX, int sizeZ, int... ys) {
        List<BedrockObservation> blocks = new ArrayList<>();
        for (int y : ys) {
            for (int dz = 0; dz < sizeZ; dz++) {
                for (int dx = 0; dx < sizeX; dx++) {
                    int x = minX + dx;
                    int z = minZ + dz;
                    blocks.add(new BedrockObservation(NETHER, x, y, z, pattern.isBedrock(x, y, z)));
                }
            }
        }
        return blocks;
    }

    private static List<BedrockObservation> chunk(long worldSeed, int chunkX, int chunkZ, int... ys) {
        return columns(new BedrockPattern(NETHER, worldSeed), chunkX * 16, chunkZ * 16, 16, 16, ys);
    }

    /** A chunk's floor and roof where every block is bedrock or not at random: no seed made this. */
    private static List<BedrockObservation> randomChunk(Random random, int chunkX, int chunkZ) {
        List<BedrockObservation> blocks = new ArrayList<>();
        for (int y : BOTH_YS) {
            for (int i = 0; i < 256; i++) {
                blocks.add(new BedrockObservation(NETHER, chunkX * 16 + (i & 15), y, chunkZ * 16 + (i >> 4), random.nextBoolean()));
            }
        }
        return blocks;
    }

    private static boolean fitsEveryBlock(long structureSeed, List<BedrockObservation> blocks) {
        BedrockPattern pattern = new BedrockPattern(NETHER, structureSeed);
        return blocks.stream().allMatch(block -> pattern.isBedrock(block.x(), block.y(), block.z()) == block.bedrock());
    }

    /** The structure seed and, when it has one, its twin: the seeds with the same bedrock key. */
    private static List<Long> withTwin(long worldSeed) {
        return Arrays.stream(NetherBedrockSearch.structureSeedsOfKey(NetherBedrockSearch.bedrockKey(Seeds.structureSeed(worldSeed))))
            .boxed().toList();
    }

    // ------------------------------------------------------------------------------------------------ the model

    @Test
    void theKeyAndHiddenNumbersAreTheOnesOfTheEnginesGenerators() {
        SplittableRandom random = new SplittableRandom(5);
        for (int i = 0; i < 200; i++) {
            long worldSeed = random.nextLong();
            long key = NetherBedrockSearch.bedrockKey(Seeds.structureSeed(worldSeed));
            assertEquals(new JavaRandom(worldSeed).nextLong() & MASK, key);
            RandomState state = new RandomState(worldSeed, true);
            for (BedrockRule rule : List.of(FLOOR, ROOF)) {
                long factorySeed = ((LegacyPositionalFactory) state.factory(rule.randomName())).seed();
                assertEquals((factorySeed ^ JavaRandom.MULTIPLIER) & MASK, NetherBedrockSearch.hiddenNumber(key, rule.randomName().hashCode()));
            }
        }
    }

    @Test
    void steppingBackFromAKeyGivesEveryStructureSeedWithIt() {
        SplittableRandom random = new SplittableRandom(99);
        int seeds = 3000;
        int twins = 0;
        int borrows = 0;
        for (int i = 0; i < seeds; i++) {
            long seed = random.nextLong() & MASK;
            long key = NetherBedrockSearch.bedrockKey(seed);
            borrows += (int) ((key >>> 31) & 1);
            long[] same = NetherBedrockSearch.structureSeedsOfKey(key);
            assertTrue(Arrays.stream(same).anyMatch(other -> other == seed), "seed " + seed);
            for (long other : same) {
                assertEquals(key, NetherBedrockSearch.bedrockKey(other));
            }
            twins += same.length > 1 ? 1 : 0;
        }
        assertTrue(borrows > seeds / 3, "keys whose low half is negative (the borrow) are covered: " + borrows);
        // Measured in the research on 200,000 seeds: 22.8% have a twin (never more than one).
        assertTrue(twins > seeds * 0.19 && twins < seeds * 0.27, twins + " of " + seeds + " have a twin");
    }

    @Test
    void theInformationOfSingleBlocksMatchesTheTable() {
        // Bits when bedrock / when not, per height (research table 3.3): y1 and y126 0.32 / 2.32 ... y4 and y123 2.32 / 0.32.
        double[][] expected = {{0.32, 2.32}, {0.74, 1.32}, {1.32, 0.74}, {2.32, 0.32}};
        for (int i = 0; i < 4; i++) {
            for (boolean bedrock : new boolean[] {true, false}) {
                double bits = expected[i][bedrock ? 0 : 1];
                int rare = bits > 1 ? 1 : 0;
                NetherBedrockSearch.Plan floor = SEARCH.plan(List.of(new BedrockObservation(NETHER, 5, FLOOR_YS[i], 7, bedrock)));
                assertEquals(1, floor.floorBlocks());
                assertEquals(0, floor.roofBlocks());
                assertEquals(rare, floor.floorRareBlocks());
                assertEquals(bits, floor.floorBits(), 0.005, "floor y" + FLOOR_YS[i] + " bedrock " + bedrock);
                NetherBedrockSearch.Plan roof = SEARCH.plan(List.of(new BedrockObservation(NETHER, 5, ROOF_YS[3 - i], 7, bedrock)));
                assertEquals(1, roof.roofBlocks());
                assertEquals(rare, roof.roofRareBlocks());
                assertEquals(bits, roof.roofBits(), 0.005, "roof y" + ROOF_YS[3 - i] + " bedrock " + bedrock);
            }
        }
        // Heights that never change tell nothing; neither do other dimensions.
        NetherBedrockSearch.Plan none = SEARCH.plan(List.of(new BedrockObservation(NETHER, 0, 0, 0, true),
            new BedrockObservation(NETHER, 0, 127, 0, true), new BedrockObservation(NETHER, 0, 60, 0, false),
            new BedrockObservation(OVERWORLD, 0, -62, 0, true)));
        assertEquals(0, none.blocks());
        assertTrue(none.problems().isEmpty());
        assertFalse(none.canSearch());
        // A whole chunk: about 0.85 bits per block (research: 1703 to 1730 bits).
        NetherBedrockSearch.Plan chunk = SEARCH.plan(chunk(12345L, 3, -4, BOTH_YS));
        assertTrue(chunk.bits() > 1600 && chunk.bits() < 1850, "bits " + chunk.bits());
        assertTrue(chunk.enough() && chunk.canSearch());
        assertEquals(0, chunk.expectedFalseKeys(), 1e-300);
    }

    @Test
    void duplicatesCountOnceAndContradictionsAreLeftOutAndReported() {
        BedrockObservation block = new BedrockObservation(NETHER, 1, 4, 2, true);
        NetherBedrockSearch.Plan twice = SEARCH.plan(List.of(block, block));
        assertEquals(1, twice.floorBlocks());
        assertTrue(twice.problems().isEmpty());
        NetherBedrockSearch.Plan contradiction = SEARCH.plan(List.of(block, new BedrockObservation(NETHER, 1, 4, 2, false),
            new BedrockObservation(NETHER, 1, 3, 2, false)));
        assertEquals(1, contradiction.floorBlocks(), "only the block noted once is used");
        assertEquals(1, contradiction.problems().size());
        assertTrue(contradiction.problems().get(0).contains("both as bedrock and as not bedrock"), contradiction.problems().get(0));
        NetherBedrockSearch.Plan wrong = SEARCH.plan(List.of(new BedrockObservation(NETHER, 1, 0, 2, false)));
        assertEquals(0, wrong.blocks());
        assertEquals(1, wrong.problems().size(), "the nether always has bedrock at y 0");
    }

    @Test
    void fewBlocksAreNotSearched() {
        List<BedrockObservation> few = chunk(777L, 0, 0, 1).subList(0, 12);
        NetherBedrockSearch.Plan plan = SEARCH.plan(few);
        assertFalse(plan.canSearch());
        NetherBedrockSearch.Outcome outcome = SEARCH.search(few, THREADS, NEVER, QUIET);
        assertEquals(0, outcome.piecesDone());
        assertTrue(outcome.structureSeeds().isEmpty());
        assertFalse(outcome.complete());
        assertFalse(outcome.proven());
        assertThrows(IllegalArgumentException.class, () -> SEARCH.search(few, 0, NEVER, QUIET));

        // Plenty of information, but only common blocks (bedrock at y 1 and 2, none at y 3 and 4): such a search would
        // take more than an hour, so it doesn't run.
        BedrockPattern pattern = new BedrockPattern(NETHER, 777L);
        List<BedrockObservation> common = columns(pattern, 0, 0, 32, 32, FLOOR_YS).stream()
            .filter(block -> block.bedrock() == block.y() <= 2)
            .toList();
        NetherBedrockSearch.Plan commonPlan = SEARCH.plan(common);
        assertTrue(commonPlan.floorBits() > 100, "bits " + commonPlan.floorBits());
        assertEquals(0, commonPlan.floorRareBlocks());
        assertFalse(commonPlan.canSearch());
        List<BedrockObservation> withRare = new ArrayList<>(common);
        withRare.addAll(columns(pattern, 0, 0, 32, 32, FLOOR_YS).stream().filter(block -> block.bedrock() != block.y() <= 2).limit(8).toList());
        assertTrue(SEARCH.plan(withRare).canSearch());
    }

    // ------------------------------------------------------------------------------------------------ whole searches

    /** Chunks made by the game itself (fixture from the oracle): one chunk's floor and roof give the structure seed. */
    @Test
    void realChunksFromTheGameGiveTheirStructureSeed() {
        JsonObject seeds = Fixtures.load("26.3", "bedrock.json").getAsJsonObject("seeds");
        int index = 0;
        for (Map.Entry<String, JsonElement> seedEntry : seeds.entrySet()) {
            long worldSeed = Long.parseLong(seedEntry.getKey());
            JsonObject chunks = seedEntry.getValue().getAsJsonObject().getAsJsonObject(NetherBedrockSearch.NETHER);
            // One chunk per seed, a different one each time (among them one near the world border).
            List<String> names = new ArrayList<>(chunks.keySet());
            String name = names.get(index++ % names.size());
            String[] position = name.split(",");
            int minX = Integer.parseInt(position[0]) * 16;
            int minZ = Integer.parseInt(position[1]) * 16;
            List<BedrockObservation> blocks = new ArrayList<>();
            for (Map.Entry<String, JsonElement> layer : chunks.getAsJsonObject(name).entrySet()) {
                int y = Integer.parseInt(layer.getKey());
                String[] rows = layer.getValue().getAsString().split(" ");
                for (int dz = 0; dz < 16; dz++) {
                    int bits = Integer.parseInt(rows[dz], 16);
                    for (int dx = 0; dx < 16; dx++) {
                        blocks.add(new BedrockObservation(NETHER, minX + dx, y, minZ + dz, (bits >> dx & 1) != 0));
                    }
                }
            }
            NetherBedrockSearch.Outcome outcome = SEARCH.search(blocks, THREADS, NEVER, QUIET);
            String what = "seed " + worldSeed + " chunk " + name;
            assertTrue(outcome.plan().problems().isEmpty(), what);
            assertEquals(1024, outcome.plan().floorBlocks(), what);
            assertEquals(1024, outcome.plan().roofBlocks(), what);
            assertEquals(withTwin(worldSeed), outcome.structureSeeds(), what);
            assertEquals(List.of(NetherBedrockSearch.bedrockKey(Seeds.structureSeed(worldSeed))), outcome.bedrockKeys(), what);
            assertTrue(outcome.proven(), what);
        }
        assertEquals(10, index);
    }

    @Test
    void randomSeedsAreFoundFromOneChunk() {
        Random random = new Random(42);
        for (int i = 0; i < 4; i++) {
            long worldSeed = random.nextLong();
            int chunkX = random.nextInt(2000) - 1000;
            int chunkZ = random.nextInt(2000) - 1000;
            List<BedrockObservation> blocks = new ArrayList<>(chunk(worldSeed, chunkX, chunkZ, BOTH_YS));
            // Also: the same note twice, an overworld note, and a block noted both ways (left out).
            blocks.add(blocks.get(17));
            blocks.add(new BedrockObservation(OVERWORLD, 0, -62, 0, true));
            BedrockObservation some = blocks.get(300);
            blocks.add(new BedrockObservation(NETHER, some.x(), some.y(), some.z(), !some.bedrock()));
            NetherBedrockSearch.Outcome outcome = SEARCH.search(blocks, THREADS, NEVER, QUIET);
            String what = "seed " + worldSeed;
            assertEquals(2047, outcome.plan().blocks(), what);
            assertEquals(1, outcome.plan().problems().size(), what);
            assertTrue(outcome.complete(), what);
            assertTrue(outcome.proven(), what);
            assertTrue(outcome.structureSeeds().contains(Seeds.structureSeed(worldSeed)), what);
            assertEquals(withTwin(worldSeed), outcome.structureSeeds(), what);
            assertEquals(1, outcome.bedrockKeys().size(), what);
            for (long seed : outcome.structureSeeds()) {
                assertTrue(fitsEveryBlock(seed, chunk(worldSeed, chunkX, chunkZ, BOTH_YS)), what);
            }
        }
    }

    @Test
    void bothTwinsAreFound() {
        SplittableRandom random = new SplittableRandom(2024);
        long seed;
        long[] twins;
        do {
            seed = random.nextLong() & MASK;
            twins = NetherBedrockSearch.structureSeedsOfKey(NetherBedrockSearch.bedrockKey(seed));
        } while (twins.length != 2);
        // The two have the same nether bedrock (checked here with the engine's own model).
        List<BedrockObservation> blocks = chunk(seed, -7, 11, BOTH_YS);
        long other = twins[0] == seed ? twins[1] : twins[0];
        assertTrue(fitsEveryBlock(other, blocks));
        assertEquals(new JavaRandom(seed).nextLong() & MASK, new JavaRandom(other).nextLong() & MASK);

        NetherBedrockSearch.Outcome outcome = SEARCH.search(blocks, THREADS, NEVER, QUIET);
        assertEquals(List.of(twins[0], twins[1]), outcome.structureSeeds());
        assertEquals(1, outcome.bedrockKeys().size());
        assertTrue(outcome.proven());
    }

    @Test
    void bedrockNoSeedMadeFitsNothing() {
        Random random = new Random(7);
        NetherBedrockSearch.Outcome noise = SEARCH.search(randomChunk(random, 4, 4), THREADS, NEVER, QUIET);
        assertTrue(noise.complete());
        assertTrue(noise.structureSeeds().isEmpty());
        assertFalse(noise.proven());

        // The floor of one world and the roof of another: each fits its own world, together they fit none.
        List<BedrockObservation> mixed = new ArrayList<>(chunk(111L, 2, 3, FLOOR_YS));
        mixed.addAll(chunk(222L, 2, 3, ROOF_YS));
        NetherBedrockSearch.Outcome outcome = SEARCH.search(mixed, THREADS, NEVER, QUIET);
        assertTrue(outcome.complete());
        assertTrue(outcome.structureSeeds().isEmpty());
    }

    @Test
    void aMisreadBlockGivesNoSeedRatherThanAWrongOne() {
        List<BedrockObservation> blocks = new ArrayList<>(chunk(-5L, 9, 9, BOTH_YS));
        BedrockObservation read = blocks.get(1500);
        blocks.set(1500, new BedrockObservation(NETHER, read.x(), read.y(), read.z(), !read.bedrock()));
        NetherBedrockSearch.Outcome outcome = SEARCH.search(blocks, THREADS, NEVER, QUIET);
        assertTrue(outcome.complete());
        assertEquals(List.of(), outcome.structureSeeds());
    }

    @Test
    void oneSurfaceGivesCandidatesButNoProof() {
        long worldSeed = 8091867987493326313L;
        for (int[] ys : List.of(FLOOR_YS, ROOF_YS)) {
            NetherBedrockSearch.Outcome outcome = SEARCH.search(chunk(worldSeed, 1, 2, ys), THREADS, NEVER, QUIET);
            String what = "heights " + Arrays.toString(ys);
            assertTrue(outcome.complete(), what);
            assertTrue(outcome.structureSeeds().contains(Seeds.structureSeed(worldSeed)), what);
            assertEquals(ys == ROOF_YS, outcome.searchedRoof(), what);
            assertFalse(outcome.plan().enough(), what);
            assertFalse(outcome.proven(), what);
            assertTrue(outcome.surfaceFits() >= 1, what);
            for (long seed : outcome.structureSeeds()) {
                assertTrue(fitsEveryBlock(seed, chunk(worldSeed, 1, 2, ys)), what);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ exactness

    /** Blocks of one surface made from a chosen hidden number with the formula, at random spots near each other. */
    private static List<BedrockObservation> planted(long hidden, boolean roof, int count, Random random) {
        BedrockRule rule = roof ? ROOF : FLOOR;
        int[] ys = roof ? ROOF_YS : FLOOR_YS;
        int x0 = random.nextInt(20000) - 10000;
        int z0 = random.nextInt(20000) - 10000;
        int spread = 16 + count / 16;
        Set<List<Integer>> seen = new HashSet<>();
        List<BedrockObservation> blocks = new ArrayList<>();
        while (blocks.size() < count) {
            int x = x0 + random.nextInt(spread);
            int z = z0 + random.nextInt(spread);
            int y = ys[random.nextInt(4)];
            if (!seen.add(List.of(x, y, z))) {
                continue;
            }
            long u = (((WorldgenSeeds.positional(x, y, z) & MASK) ^ hidden) * JavaRandom.MULTIPLIER + JavaRandom.ADDEND) & MASK;
            boolean gradient = (u >>> 24) < rule.threshold(y);
            blocks.add(new BedrockObservation(NETHER, x, y, z, gradient != rule.inverted()));
        }
        return blocks;
    }

    /**
     * The pruned search finds exactly the numbers that fit when every number is tried, in a part of the tree (every
     * number with the same top bits as a planted one).
     */
    @Test
    void thePrunedSearchFindsExactlyWhatTryingEveryNumberFinds() {
        Random random = new Random(1234);
        // {blocks, unknown low bits}: few blocks leave many near-misses; 16 low bits go below the table step.
        int[][] cases = {{20, 22}, {60, 22}, {60, 16}, {300, 23}, {1024, 23}, {1024, 16}};
        int trial = 0;
        for (int[] testCase : cases) {
            for (int repeat = 0; repeat < 2; repeat++) {
                boolean roof = trial++ % 2 == 1;
                long hidden = random.nextLong() & MASK;
                List<BedrockObservation> blocks = planted(hidden, roof, testCase[0], random);
                BedrockSurface surface = SEARCH.surfaceOf(roof, blocks);
                assertEquals(testCase[0], surface.size());
                assertTrue(surface.accepts(hidden));
                BedrockSurfaceSearch search = new BedrockSurfaceSearch(surface);
                int low = testCase[1];
                long prefix = hidden & ~((1L << low) - 1);
                for (int k = 47; k >= low; k--) {
                    long node = hidden & ~((1L << k) - 1);
                    assertTrue(!search.hasTests(k) || search.passes(node, k), "the planted number's node fails at level " + k);
                }
                TreeSet<Long> pruned = new TreeSet<>();
                search.worker(pruned::add).searchBelow(prefix, low);
                TreeSet<Long> brute = new TreeSet<>();
                for (long below = 0; below < 1L << low; below++) {
                    if (surface.accepts(prefix | below)) {
                        brute.add(prefix | below);
                    }
                }
                String what = testCase[0] + " blocks, " + low + " low bits, " + (roof ? "roof" : "floor");
                assertEquals(brute, pruned, what);
                assertTrue(brute.contains(hidden), what);
            }
        }
    }

    /** The right hidden number passes every test of its surface's search on its way down, for many seeds and notes. */
    @Test
    void theRightNumberPassesEveryTest() {
        Random random = new Random(31337);
        int surfaces = 0;
        for (int i = 0; i < 40; i++) {
            long worldSeed = random.nextLong();
            BedrockPattern pattern = new BedrockPattern(NETHER, worldSeed);
            int minX = (random.nextInt(2000) - 1000) * 16;
            int minZ = (random.nextInt(2000) - 1000) * 16;
            List<BedrockObservation> blocks = switch (i % 4) {
                case 0 -> columns(pattern, minX, minZ, 16, 16, BOTH_YS);
                case 1 -> columns(pattern, minX, minZ, 4, 4, BOTH_YS);
                case 2 -> columns(pattern, minX, minZ, 16, 16, 4, 123);
                default -> {
                    // 60 scattered blocks of each surface in a 32 x 32 area
                    List<BedrockObservation> scattered = new ArrayList<>();
                    for (int[] ys : List.of(FLOOR_YS, ROOF_YS)) {
                        List<BedrockObservation> all = columns(pattern, minX, minZ, 32, 32, ys);
                        Collections.shuffle(all, random);
                        scattered.addAll(all.subList(0, 60));
                    }
                    yield scattered;
                }
            };
            long key = NetherBedrockSearch.bedrockKey(Seeds.structureSeed(worldSeed));
            for (boolean roof : new boolean[] {false, true}) {
                BedrockSurface surface = SEARCH.surfaceOf(roof, blocks);
                BedrockSurfaceSearch search = new BedrockSurfaceSearch(surface);
                long hidden = NetherBedrockSearch.hiddenNumber(key, surface.hash());
                String what = "seed " + worldSeed + " case " + i % 4 + (roof ? " roof" : " floor");
                assertTrue(surface.accepts(hidden), what);
                for (int k = 47; k >= 0; k--) {
                    long node = hidden & ~((1L << k) - 1);
                    assertTrue(!search.hasTests(k) || search.passes(node, k), what + " level " + k);
                }
                long node18 = hidden & ~((1L << BedrockSurfaceSearch.TABLE_LEVEL) - 1);
                long middle = (hidden >>> BedrockSurfaceSearch.TABLE_TARGET) & 63;
                assertTrue((search.tableMask(node18) >>> middle & 1) != 0, what + " table step");
                surfaces++;
            }
        }
        assertEquals(80, surfaces);
    }

    // ------------------------------------------------------------------------------------------------ chunk by chunk

    @Test
    void chunkByChunkSkipsChunksNoSeedMadeAndNamesThem() {
        long worldSeed = -6984854390176336655L;
        Random random = new Random(3);
        List<BedrockObservation> notes = new ArrayList<>();
        // A: random bedrock, the most informative chunk, tried first; B: the world's; C: half of another world's chunk;
        // D: 100 blocks of the world's.
        notes.addAll(randomChunk(random, 0, 0));
        notes.addAll(chunk(worldSeed, 1, 0, BOTH_YS));
        List<BedrockObservation> other = chunk(99L, 2, 0, BOTH_YS);
        notes.addAll(other.subList(0, 1024));
        List<BedrockObservation> some = new ArrayList<>(chunk(worldSeed, 3, 0, BOTH_YS));
        Collections.shuffle(some, random);
        notes.addAll(some.subList(0, 100));
        Collections.shuffle(notes, random);

        List<Integer> searches = Collections.synchronizedList(new ArrayList<>());
        NetherBedrockSearch.Finding finding = SEARCH.find(notes, THREADS, NEVER, (search, pieces) -> {
            if (pieces == NetherBedrockSearch.PIECES) {
                searches.add(search);
            }
        });
        assertEquals(List.of(0, 1), searches, "two chunks searched, one after the other");
        assertTrue(finding.complete());
        assertTrue(finding.proven());
        assertFalse(finding.together());
        assertEquals(withTwin(worldSeed), finding.structureSeeds());
        List<NetherBedrockSearch.ChunkReport> chunks = finding.chunks();
        assertEquals(List.of(new ChunkPos(0, 0), new ChunkPos(1, 0), new ChunkPos(2, 0), new ChunkPos(3, 0)),
            chunks.stream().map(NetherBedrockSearch.ChunkReport::chunk).toList(), "most informative first");
        assertEquals(List.of(NetherBedrockSearch.ChunkStatus.FITS_NO_SEED, NetherBedrockSearch.ChunkStatus.USED,
                NetherBedrockSearch.ChunkStatus.CONTRADICTS, NetherBedrockSearch.ChunkStatus.FITS),
            chunks.stream().map(NetherBedrockSearch.ChunkReport::status).toList());
        assertEquals(List.of(new ChunkPos(0, 0), new ChunkPos(2, 0)), finding.unusableChunks());
    }

    @Test
    void chunksWithOneSurfaceEachAreSearchedTogether() {
        long worldSeed = 3141592653589793238L;
        List<BedrockObservation> notes = new ArrayList<>(chunk(worldSeed, -3, 5, FLOOR_YS));
        notes.addAll(chunk(worldSeed, 6, -2, ROOF_YS));
        NetherBedrockSearch.Finding finding = SEARCH.find(notes, THREADS, NEVER, (search, pieces) -> { });
        assertTrue(finding.together());
        assertTrue(finding.complete());
        assertTrue(finding.proven());
        assertEquals(withTwin(worldSeed), finding.structureSeeds());
        assertTrue(finding.chunks().stream().allMatch(report -> report.status() == NetherBedrockSearch.ChunkStatus.USED));
        assertTrue(finding.unusableChunks().isEmpty());
    }

    /**
     * A chunk whose roof is nearly unknown has enough information, but its floor's near-misses get past two roof
     * blocks: candidates, not a proof. Together with a roof seen elsewhere they give the seed.
     */
    @Test
    void candidatesOfAChunkAreCombinedWithTheOtherChunks() {
        long worldSeed = 2317450L;
        List<BedrockObservation> weakRoof = new ArrayList<>(chunk(worldSeed, 0, 0, FLOOR_YS));
        weakRoof.addAll(chunk(worldSeed, 0, 0, 126).subList(0, 2));
        NetherBedrockSearch.Plan plan = SEARCH.plan(weakRoof);
        assertTrue(plan.enough());

        NetherBedrockSearch.Finding alone = SEARCH.find(weakRoof, THREADS, NEVER, (search, pieces) -> { });
        assertTrue(alone.complete());
        assertFalse(alone.together());
        assertFalse(alone.proven(), "near-misses of the floor: " + alone.outcome().orElseThrow().surfaceFits());
        assertTrue(alone.structureSeeds().contains(worldSeed));
        assertEquals(NetherBedrockSearch.ChunkStatus.USED, alone.chunks().get(0).status());

        List<BedrockObservation> notes = new ArrayList<>(weakRoof);
        notes.addAll(chunk(worldSeed, 5, 5, ROOF_YS));
        NetherBedrockSearch.Finding combined = SEARCH.find(notes, THREADS, NEVER, (search, pieces) -> { });
        assertTrue(combined.together());
        assertTrue(combined.proven());
        assertEquals(withTwin(worldSeed), combined.structureSeeds());
    }

    @Test
    void nothingToSearchIsNoError() {
        NetherBedrockSearch.Finding finding = SEARCH.find(List.of(new BedrockObservation(OVERWORLD, 0, -62, 0, true)), THREADS, NEVER,
            (search, pieces) -> { });
        assertTrue(finding.outcome().isEmpty());
        assertTrue(finding.chunks().isEmpty());
        assertTrue(finding.structureSeeds().isEmpty());
        assertFalse(finding.proven());
    }

    // ------------------------------------------------------------------------------------------------ running

    @Test
    void progressIsHeardOncePerPieceInOrder() {
        List<Integer> heard = Collections.synchronizedList(new ArrayList<>());
        NetherBedrockSearch.Outcome outcome = SEARCH.search(chunk(1L, 0, 0, BOTH_YS), THREADS, NEVER, heard::add);
        assertTrue(outcome.complete());
        assertEquals(NetherBedrockSearch.PIECES, outcome.piecesDone());
        assertEquals(NetherBedrockSearch.PIECES, heard.size());
        for (int i = 0; i < heard.size(); i++) {
            assertEquals(i + 1, heard.get(i));
        }
    }

    @Test
    void aSearchStopsWhenAsked() {
        AtomicInteger done = new AtomicInteger();
        List<BedrockObservation> blocks = chunk(2L, 5, 5, BOTH_YS);
        NetherBedrockSearch.Outcome stopped = SEARCH.search(blocks, THREADS, () -> done.get() >= 10, done::set);
        assertTrue(stopped.piecesDone() >= 10 && stopped.piecesDone() < 10 + THREADS + 1, "pieces " + stopped.piecesDone());
        assertFalse(stopped.complete());
        assertFalse(stopped.proven());

        NetherBedrockSearch.Outcome never = SEARCH.search(blocks, THREADS, () -> true, QUIET);
        assertEquals(0, never.piecesDone());
        assertFalse(never.proven());

        NetherBedrockSearch.Finding finding = SEARCH.find(blocks, THREADS, () -> true, (search, pieces) -> { });
        assertFalse(finding.complete());
        assertFalse(finding.proven());
        assertTrue(finding.structureSeeds().isEmpty());
        assertEquals(NetherBedrockSearch.ChunkStatus.NOT_CHECKED, finding.chunks().get(0).status());
    }

    @Test
    void aFailingSearchThreadStopsTheSearchWithItsError() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> SEARCH.search(chunk(3L, 0, 0, BOTH_YS), THREADS,
            NEVER, pieces -> {
                throw new ArithmeticException("broken progress");
            }));
        assertEquals("broken progress", failure.getCause().getMessage());
    }
}
