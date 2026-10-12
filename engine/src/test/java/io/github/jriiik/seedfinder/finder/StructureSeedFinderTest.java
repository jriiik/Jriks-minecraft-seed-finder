package io.github.jriiik.seedfinder.finder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.jriiik.seedfinder.Fixtures;
import io.github.jriiik.seedfinder.Seeds;
import io.github.jriiik.seedfinder.structure.RandomSpreadPlacement;
import io.github.jriiik.seedfinder.structure.StructureCandidate;
import io.github.jriiik.seedfinder.structure.StructureLocator;
import io.github.jriiik.seedfinder.structure.StructureSet;
import io.github.jriiik.seedfinder.version.MinecraftVersion;
import io.github.jriiik.seedfinder.version.VersionData;
import io.github.jriiik.seedfinder.world.ChunkPos;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StructureSeedFinderTest {

    private static final VersionData DATA = MinecraftVersion.V26_3.data();
    private static final int THREADS = Runtime.getRuntime().availableProcessors();

    /**
     * Structure starts for a seed, as the engine (checked against the game) places them. Each structure type comes
     * from its own area, like structures found while exploring; temple types sharing a region would be linked.
     */
    private static List<StructureObservation> observe(long worldSeed, String set, int count) {
        StructureSet structureSet = DATA.structureSet(set).orElseThrow();
        int areaX = new ArrayList<>(DATA.structureSets()).indexOf(structureSet) * 400 - 4000;
        List<StructureCandidate> candidates = new StructureLocator(DATA, worldSeed).candidates(structureSet, areaX, -150, areaX + 300, 150);
        assertTrue(candidates.size() >= count, "not enough " + set + " in the area");
        return candidates.subList(0, count).stream().map(c -> new StructureObservation(structureSet, c.chunk())).toList();
    }

    private static StructureObservation at(String set, int chunkX, int chunkZ) {
        return new StructureObservation(DATA.structureSet(set).orElseThrow(), new ChunkPos(chunkX, chunkZ));
    }

    /** Six structures of seed 8091867987493326313 for which a twin seed fits too. */
    private static final List<StructureObservation> TWIN_EXAMPLE = List.of(
        at("desert_pyramids", -138, -124), at("desert_pyramids", -139, -41), at("igloos", -145, -10),
        at("villages", -111, -150), at("villages", -128, -111), at("shipwrecks", -143, -142));

    private static StructureSeedFinder finder(List<StructureObservation> observations) {
        return new StructureSeedFinder(DATA, observations);
    }

    private static List<StructureObservation> templesVillagesAndAShipwreck(long worldSeed) {
        List<StructureObservation> observations = new ArrayList<>();
        observations.addAll(observe(worldSeed, "desert_pyramids", 2));
        observations.addAll(observe(worldSeed, "igloos", 1));
        observations.addAll(observe(worldSeed, "villages", 2));
        observations.addAll(observe(worldSeed, "shipwrecks", 1));
        return observations;
    }

    private static boolean fitsAll(long structureSeed, List<StructureObservation> observations) {
        StructureLocator locator = new StructureLocator(DATA, structureSeed);
        return observations.stream().allMatch(o -> locator.isStructureChunk(o.set(), o.chunk().x(), o.chunk().z()));
    }

    @Test
    void findsTheStructureSeedFromTemplesVillagesAndAShipwreck() {
        long worldSeed = 8091867987493326313L;
        List<StructureObservation> observations = templesVillagesAndAShipwreck(worldSeed);
        StructureSeedFinder finder = finder(observations);
        SearchPlan plan = finder.plan();
        assertTrue(plan.canSearch(), plan.problems().toString());
        assertEquals(20, plan.liftedBits());

        List<Long> found = finder.search(THREADS, null);
        assertTrue(found.contains(Seeds.structureSeed(worldSeed)), found.toString());
        for (long seed : found) {
            assertTrue(fitsAll(seed, observations), "every answer must fit every observation: " + seed);
        }
    }

    @Test
    void findsTheSeedAndItsTwin() {
        // A "twin" differs only in its top 13 bits and fits all of these structures too; the plan warns beforehand.
        StructureSeedFinder finder = finder(TWIN_EXAMPLE);
        assertTrue(finder.plan().warnings().stream().anyMatch(w -> w.toLowerCase(java.util.Locale.ROOT).contains("other structure seeds")), finder.plan().warnings().toString());
        List<Long> found = finder.search(THREADS, null);
        assertEquals(List.of(Seeds.structureSeed(8091867987493326313L), 77549457968617L), found);
        assertEquals(0, (found.get(1) - found.get(0)) % (1L << 35), "twins differ by a multiple of 2^35");
        assertTrue(fitsAll(found.get(1), TWIN_EXAMPLE));
    }

    @Test
    void suggestsStructureTypesThatTellSeedsApart() {
        // Temples (odd factor 3), villages (13) and shipwrecks (5) leave wrong seeds that react alike; types with a new
        // odd factor or a power-of-two region size break that, so those are suggested, biggest odd factor first.
        SearchPlan plan = finder(templesVillagesAndAShipwreck(1L)).plan();
        assertEquals(List.of("abandoned_camp", "ruined_portals", "trial_chambers", "ancient_cities"), plan.suggestedStructures());
        assertTrue(plan.warnings().stream().anyMatch(w -> w.contains("abandoned_camp, ruined_portals")), plan.warnings().toString());

        List<StructureObservation> withPortal = new ArrayList<>(templesVillagesAndAShipwreck(1L));
        withPortal.addAll(observe(1L, "ruined_portals", 1));
        SearchPlan better = finder(withPortal).plan();
        assertTrue(better.expectedFalseMatches() < 0.1, "a ruined portal removes almost all doubt: " + better.expectedFalseMatches());
        assertTrue(better.warnings().isEmpty(), better.warnings().toString());
    }

    @Test
    void aDifferentStructureTypeTellsTwinsApart() {
        long worldSeed = 8091867987493326313L;
        List<StructureObservation> observations = new ArrayList<>(TWIN_EXAMPLE);
        observations.addAll(observe(worldSeed, "ruined_portals", 1));
        assertEquals(List.of(Seeds.structureSeed(worldSeed)), finder(observations).search(THREADS, null));
    }

    @Test
    void theWrongSeedEstimateMatchesReality() {
        // Run real searches for many random seeds and compare how many wrong structure seeds turned up on average
        // with the plan's estimate. The estimate only needs to be in the right ballpark (it guides the user).
        java.util.Random random = new java.util.Random(7);
        int seeds = 120;
        long wrong = 0;
        double estimated = 0;
        for (int i = 0; i < seeds; i++) {
            long worldSeed = random.nextLong();
            StructureSeedFinder finder = finder(templesVillagesAndAShipwreck(worldSeed));
            List<Long> found = finder.search(THREADS, null);
            assertTrue(found.contains(Seeds.structureSeed(worldSeed)), "the true seed is always found");
            wrong += found.size() - 1;
            estimated += finder.plan().expectedFalseMatches();
        }
        double actualAverage = (double) wrong / seeds;
        double estimatedAverage = estimated / seeds;
        assertTrue(actualAverage > 0.1 && actualAverage < 5, "this recipe sometimes leaves wrong seeds: " + actualAverage);
        assertTrue(estimatedAverage > actualAverage / 2.5 && estimatedAverage < actualAverage * 2.5,
            "estimated " + estimatedAverage + " wrong seeds per search, saw " + actualAverage);
    }

    @Test
    void findsTheSeedFromPositionsRecordedFromTheRealGame() {
        // Structure chunks Minecraft 26.3 itself picked for two seeds (tools/oracle), fed in as observations.
        JsonObject sets = Fixtures.load("26.3", "placements.json").getAsJsonObject("sets");
        for (long worldSeed : new long[] {12345L, -6984854390176336655L}) {
            List<StructureObservation> observations = new ArrayList<>();
            for (String set : List.of("minecraft:desert_pyramids", "minecraft:swamp_huts", "minecraft:villages", "minecraft:pillager_outposts")) {
                StructureSet structureSet = DATA.structureSet(set).orElseThrow();
                JsonArray rows = sets.getAsJsonObject(set).getAsJsonObject("seeds").getAsJsonArray(Long.toString(worldSeed));
                int taken = 0;
                for (JsonElement element : rows) {
                    JsonArray row = element.getAsJsonArray();
                    boolean nearSpawn = Math.abs(row.get(0).getAsInt()) <= 1 && Math.abs(row.get(1).getAsInt()) <= 1;
                    if (nearSpawn && row.get(5).getAsBoolean() && taken < 2) {
                        observations.add(new StructureObservation(structureSet, new ChunkPos(row.get(2).getAsInt(), row.get(3).getAsInt())));
                        taken++;
                    }
                }
            }
            StructureSeedFinder finder = finder(observations);
            assertTrue(finder.plan().canSearch(), finder.plan().problems().toString());
            List<Long> found = finder.search(THREADS, null);
            assertTrue(found.contains(Seeds.structureSeed(worldSeed)), "seed " + worldSeed + " not in " + found);
        }
    }

    @Test
    void confirmsCandidatesWithTheFullRules() {
        // An outpost spot that loses its 1-in-5 roll is not a real outpost, so the true seed must not fit.
        long worldSeed = 12345L;
        StructureSet outposts = DATA.structureSet("pillager_outposts").orElseThrow();
        RandomSpreadPlacement placement = (RandomSpreadPlacement) outposts.placement();
        ChunkPos failedRoll = null;
        for (int r = 0; failedRoll == null; r++) {
            ChunkPos chunk = placement.potentialChunk(worldSeed, r, 0);
            if (!placement.passesFrequency(worldSeed, chunk.x(), chunk.z())) {
                failedRoll = chunk;
            }
        }
        List<StructureObservation> observations = new ArrayList<>(observe(worldSeed, "desert_pyramids", 2));
        observations.addAll(observe(worldSeed, "igloos", 1));
        observations.addAll(observe(worldSeed, "villages", 2));
        observations.addAll(observe(worldSeed, "ruined_portals", 1));
        observations.add(new StructureObservation(outposts, failedRoll));
        StructureSeedFinder finder = finder(observations);
        assertTrue(finder.plan().canSearch(), finder.plan().problems().toString());
        assertFalse(finder.search(THREADS, null).contains(Seeds.structureSeed(worldSeed)));

        // The same structures without the bad outpost do find it.
        assertTrue(finder(observations.subList(0, 6)).search(THREADS, null).contains(Seeds.structureSeed(worldSeed)));
    }

    @Test
    void reportsProgressUpToTheTotal() {
        long worldSeed = -1L;
        List<StructureObservation> observations = new ArrayList<>(observe(worldSeed, "desert_pyramids", 2));
        observations.addAll(observe(worldSeed, "swamp_huts", 2));
        observations.addAll(observe(worldSeed, "villages", 1));
        observations.addAll(observe(worldSeed, "trial_chambers", 1));
        StructureSeedFinder finder = finder(observations);
        long[] last = {0, 0};
        List<Long> found = finder.search(2, (done, total) -> {
            synchronized (last) {
                last[0] = Math.max(last[0], done);
                last[1] = total;
            }
        });
        assertTrue(found.contains(Seeds.structureSeed(worldSeed)));
        assertTrue(last[1] > 0);
        assertEquals(last[1], last[0]);
    }

    @Test
    void villagesAloneWorkButNeedMoreOfThemAndLeaveTwins() {
        // Each village coordinate reveals one low bit, so the low 18 bits narrow down slowly. For each low candidate,
        // 2^17 middle combinations remain, and the solver leaves 631 (8192 / 13) top values for each of those.
        long worldSeed = 4242424242424242L;
        List<StructureObservation> villages = observe(worldSeed, "villages", 5);
        SearchPlan plan = finder(villages).plan();
        assertTrue(plan.canSearch(), plan.problems().toString());
        assertEquals(18, plan.liftedBits());
        assertEquals((plan.lowCandidates() << 17) * 631, plan.seedsToTest());
        assertTrue(plan.seedsToTest() > 1L << 31, "more than an int can hold: " + plan.seedsToTest());
        assertTrue(plan.expectedFalseMatches() > 1, "villages alone leave twins: " + plan.expectedFalseMatches());
        assertTrue(plan.warnings().stream().anyMatch(w -> w.toLowerCase(java.util.Locale.ROOT).contains("other structure seeds")), plan.warnings().toString());
    }

    @Test
    void needsAStructureThatRevealsLowBits() {
        SearchPlan plan = finder(observe(42L, "ruined_portals", 6)).plan();
        assertFalse(plan.canSearch());
        assertTrue(plan.problems().get(0).contains("desert_pyramids"), plan.problems().toString());
        assertThrows(IllegalStateException.class, () -> finder(observe(42L, "ruined_portals", 6)).search(1, null));
    }

    @Test
    void refusesWhenTooManySeedsWouldFit() {
        SearchPlan plan = finder(observe(42L, "desert_pyramids", 2)).plan();
        assertFalse(plan.canSearch());
        assertTrue(plan.problems().stream().anyMatch(p -> p.contains("too many")), plan.problems().toString());
    }

    @Test
    void warnsWhenAFewWrongSeedsMayFit() {
        List<StructureObservation> observations = new ArrayList<>(observe(42L, "desert_pyramids", 3));
        observations.addAll(observe(42L, "igloos", 1));
        observations.addAll(observe(42L, "ruined_portals", 1));
        SearchPlan plan = finder(observations).plan();
        assertTrue(plan.canSearch(), plan.problems().toString());
        assertTrue(plan.expectedFalseMatches() > 0.5 && plan.expectedFalseMatches() < 1000);
        assertTrue(plan.warnings().stream().anyMatch(w -> w.toLowerCase(java.util.Locale.ROOT).contains("other structure seeds")), plan.warnings().toString());
    }

    @Test
    void rejectsImpossibleAndConflictingObservations() {
        StructureSet villages = DATA.structureSet("villages").orElseThrow();
        SearchPlan impossible = finder(List.of(new StructureObservation(villages, new ChunkPos(30, 0)))).plan();
        assertTrue(impossible.problems().get(0).contains("never start in that chunk"), impossible.problems().toString());

        SearchPlan conflict = finder(List.of(
            new StructureObservation(villages, new ChunkPos(1, 2)),
            new StructureObservation(villages, new ChunkPos(3, 4)))).plan();
        assertTrue(conflict.problems().get(0).contains("can't both be right"), conflict.problems().toString());

        StructureSet strongholds = DATA.structureSet("strongholds").orElseThrow();
        SearchPlan rings = finder(List.of(new StructureObservation(strongholds, new ChunkPos(1, 2)))).plan();
        assertTrue(rings.problems().get(0).contains("depend on biomes"), rings.problems().toString());

        assertFalse(finder(List.of()).plan().canSearch());
    }

    @Test
    void ignoresDuplicatesAndObservationsWithoutInformation() {
        long worldSeed = 99L;
        List<StructureObservation> observations = new ArrayList<>(observe(worldSeed, "desert_pyramids", 2));
        observations.add(observations.get(0));
        StructureSet fossils = DATA.structureSet("nether_fossils").orElseThrow();
        observations.add(new StructureObservation(fossils, new ChunkPos(4, 8)));
        SearchPlan plan = finder(observations).plan();
        assertEquals(2, plan.observations().size());
        assertTrue(plan.warnings().stream().anyMatch(w -> w.contains("reveals nothing")), plan.warnings().toString());
    }
}
