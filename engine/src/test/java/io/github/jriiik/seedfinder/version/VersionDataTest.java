package io.github.jriiik.seedfinder.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.jriiik.seedfinder.structure.ConcentricRingsPlacement;
import io.github.jriiik.seedfinder.structure.ExclusionZone;
import io.github.jriiik.seedfinder.structure.FrequencyReduction;
import io.github.jriiik.seedfinder.structure.RandomSpreadPlacement;
import io.github.jriiik.seedfinder.structure.SpreadType;
import io.github.jriiik.seedfinder.structure.StructureSet;
import io.github.jriiik.seedfinder.world.BlockPos;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class VersionDataTest {

    private static final VersionData DATA = MinecraftVersion.V26_3.data();

    @Test
    void loadsAllStructureSetsOf26_3() {
        assertEquals("26.3", DATA.version());
        assertEquals(21, DATA.structureSets().size());
        assertTrue(DATA.structureSet("minecraft:abandoned_camp").isPresent(), "new in 26.x");
    }

    @Test
    void villagesUseTheKnownPlacement() {
        StructureSet villages = DATA.structureSet("villages").orElseThrow();
        assertEquals(5, villages.structures().size());
        assertEquals(
            new RandomSpreadPlacement(34, 8, SpreadType.LINEAR, 10387312, 1.0f, FrequencyReduction.DEFAULT, Optional.empty(), BlockPos.ZERO),
            villages.placement());
    }

    @Test
    void outpostsHaveAFrequencyAndAnExclusionZone() {
        RandomSpreadPlacement outposts = (RandomSpreadPlacement) DATA.structureSet("pillager_outposts").orElseThrow().placement();
        assertEquals(0.2f, outposts.frequency());
        assertEquals(FrequencyReduction.LEGACY_TYPE_1, outposts.frequencyReduction());
        assertEquals(Optional.of(new ExclusionZone("minecraft:villages", 10)), outposts.exclusionZone());
    }

    @Test
    void strongholdsUseConcentricRings() {
        ConcentricRingsPlacement rings = assertInstanceOf(ConcentricRingsPlacement.class, DATA.structureSet("strongholds").orElseThrow().placement());
        assertEquals(128, rings.count());
        assertEquals(List.of("#minecraft:stronghold_biased_to"), rings.preferredBiomes());
    }

    @Test
    void structureSetsKnowTheirDimensions() {
        assertEquals(List.of("minecraft:overworld"), DATA.dimensionsOf(DATA.structureSet("villages").orElseThrow()));
        assertEquals(List.of("minecraft:the_nether"), DATA.dimensionsOf(DATA.structureSet("nether_complexes").orElseThrow()));
        assertEquals(List.of("minecraft:the_end"), DATA.dimensionsOf(DATA.structureSet("end_cities").orElseThrow()));
        assertEquals(
            List.of("minecraft:overworld", "minecraft:the_nether"),
            DATA.dimensionsOf(DATA.structureSet("ruined_portals").orElseThrow()));
        for (StructureSet set : DATA.structureSets()) {
            assertTrue(!DATA.dimensionsOf(set).isEmpty(), set.id() + " should belong to a dimension");
        }
    }

    @Test
    void structuresKnowTheirBiomes() {
        assertEquals(List.of("minecraft:meadow", "minecraft:plains"), DATA.structure("village_plains").orElseThrow().biomes());
    }

    @Test
    void datapackDefaultsAreApplied() {
        JsonObject json = JsonParser.parseString("""
            {"structures": [{"structure": "my_pack:tower", "weight": 2}],
             "placement": {"type": "random_spread", "spacing": 10, "separation": 3, "salt": 7}}
            """).getAsJsonObject();
        StructureSet set = VersionDataLoader.parseStructureSet("my_pack:towers", json);
        assertEquals(List.of(new StructureSet.Entry("my_pack:tower", 2)), set.structures());
        assertEquals(
            new RandomSpreadPlacement(10, 3, SpreadType.LINEAR, 7, 1.0f, FrequencyReduction.DEFAULT, Optional.empty(), BlockPos.ZERO),
            set.placement());
    }

    @Test
    void rejectsInvalidPlacements() {
        JsonObject json = JsonParser.parseString("""
            {"structures": [], "placement": {"type": "minecraft:random_spread", "spacing": 5, "separation": 5, "salt": 1}}
            """).getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> VersionDataLoader.parseStructureSet("x", json));
    }

    @Test
    void normalizesIds() {
        assertEquals("minecraft:villages", VersionDataLoader.normalizeId("villages"));
        assertEquals("pack:thing", VersionDataLoader.normalizeId("pack:thing"));
        assertEquals("#minecraft:is_ocean", VersionDataLoader.normalizeId("#is_ocean"));
    }

    @Test
    void knowsTheLatestVersion() {
        assertEquals(MinecraftVersion.V26_3, MinecraftVersion.latest());
        assertEquals(Optional.of(MinecraftVersion.V26_3), MinecraftVersion.byName(" 26.3 "));
        assertTrue(MinecraftVersion.byName("1.8").isEmpty());
    }
}
