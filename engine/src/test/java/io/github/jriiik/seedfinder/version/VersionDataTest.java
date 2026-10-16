package io.github.jriiik.seedfinder.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.jriiik.seedfinder.Fixtures;
import io.github.jriiik.seedfinder.structure.ConcentricRingsPlacement;
import io.github.jriiik.seedfinder.structure.ExclusionZone;
import io.github.jriiik.seedfinder.structure.FrequencyReduction;
import io.github.jriiik.seedfinder.structure.RandomSpreadPlacement;
import io.github.jriiik.seedfinder.structure.RigidStructures;
import io.github.jriiik.seedfinder.structure.SpreadType;
import io.github.jriiik.seedfinder.structure.StructureSet;
import io.github.jriiik.seedfinder.world.BlockPos;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class VersionDataTest {

    private static final String VERSIONS = "io.github.jriiik.seedfinder.Fixtures#versions";
    private static final VersionData DATA = MinecraftVersion.V26_3.data();
    private static final VersionData OLD = MinecraftVersion.V1_21_11.data();
    private static final List<String> DATA_FILES = List.of("bedrock.json", "biome_parameters.json", "climate.json", "dimensions.json",
        "rigid_structures.json", "structure_sets.json", "structures.json");
    private static final List<String> FIXTURE_FILES = List.of("bedrock.json", "biomes.json", "placements.json", "random_seeds.json", "rng.json",
        "strongholds.json", "xoroshiro.json");

    @Test
    void loadsAllStructureSetsOf26_3() {
        assertEquals("26.3", DATA.version());
        assertEquals(21, DATA.structureSets().size());
        assertTrue(DATA.structureSet("minecraft:abandoned_camp").isPresent(), "new in 26.x");
    }

    @Test
    void loadsAllStructureSetsOf1_21_11() {
        assertEquals("1.21.11", OLD.version());
        assertEquals(20, OLD.structureSets().size());
        assertTrue(OLD.structureSet("minecraft:abandoned_camp").isEmpty(), "new in 26.x");
        assertTrue(OLD.findStructureSet("abandoned_camp_meadow").isEmpty());
        assertTrue(OLD.structure("minecraft:abandoned_camp_meadow").isEmpty());
    }

    /**
     * Every structure set 1.21.11 has is placed exactly as in 26.3, and the bedrock and rigid structures are the same:
     * guards of measured estimates that use these (structure search, bedrock) need to run for one version only.
     */
    @Test
    void sharedStructuresBedrockAndRigidStructuresAreTheSameInBothVersions() {
        for (StructureSet set : OLD.structureSets()) {
            assertEquals(set, DATA.structureSet(set.id()).orElseThrow(), set.id());
        }
        assertEquals(Set.of("minecraft:abandoned_camp"), DATA.structureSets().stream().map(StructureSet::id)
            .filter(id -> OLD.structureSet(id).isEmpty()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(OLD.bedrock(), DATA.bedrock());
        assertEquals(OLD.rigidStructures(), DATA.rigidStructures());
    }

    @ParameterizedTest
    @MethodSource(VERSIONS)
    void everyVersionHasItsOwnDataAndFixtures(MinecraftVersion version) {
        for (String file : DATA_FILES) {
            assertEquals(version.displayName(), VersionDataLoader.readBundled(version.displayName(), file).get("minecraft_version").getAsString(), file);
        }
        for (String file : FIXTURE_FILES) {
            assertEquals(version.displayName(), Fixtures.load(version, file).get("minecraft_version").getAsString(), file);
        }
        assertEquals(version.displayName(), version.data().version());
    }

    @ParameterizedTest
    @MethodSource(VERSIONS)
    void villagesUseTheKnownPlacement(MinecraftVersion version) {
        StructureSet villages = version.data().structureSet("villages").orElseThrow();
        assertEquals(5, villages.structures().size());
        assertEquals(
            new RandomSpreadPlacement(34, 8, SpreadType.LINEAR, 10387312, 1.0f, FrequencyReduction.DEFAULT, Optional.empty(), BlockPos.ZERO),
            villages.placement());
    }

    @ParameterizedTest
    @MethodSource(VERSIONS)
    void outpostsHaveAFrequencyAndAnExclusionZone(MinecraftVersion version) {
        RandomSpreadPlacement outposts = (RandomSpreadPlacement) version.data().structureSet("pillager_outposts").orElseThrow().placement();
        assertEquals(0.2f, outposts.frequency());
        assertEquals(FrequencyReduction.LEGACY_TYPE_1, outposts.frequencyReduction());
        assertEquals(Optional.of(new ExclusionZone("minecraft:villages", 10)), outposts.exclusionZone());
    }

    @ParameterizedTest
    @MethodSource(VERSIONS)
    void strongholdsUseConcentricRings(MinecraftVersion version) {
        ConcentricRingsPlacement rings = assertInstanceOf(ConcentricRingsPlacement.class,
            version.data().structureSet("strongholds").orElseThrow().placement());
        assertEquals(128, rings.count());
        assertEquals(List.of("#minecraft:stronghold_biased_to"), rings.preferredBiomes());
    }

    @ParameterizedTest
    @MethodSource(VERSIONS)
    void structureSetsKnowTheirDimensions(MinecraftVersion version) {
        VersionData data = version.data();
        assertEquals(List.of("minecraft:overworld"), data.dimensionsOf(data.structureSet("villages").orElseThrow()));
        assertEquals(List.of("minecraft:the_nether"), data.dimensionsOf(data.structureSet("nether_complexes").orElseThrow()));
        assertEquals(List.of("minecraft:the_end"), data.dimensionsOf(data.structureSet("end_cities").orElseThrow()));
        assertEquals(
            List.of("minecraft:overworld", "minecraft:the_nether"),
            data.dimensionsOf(data.structureSet("ruined_portals").orElseThrow()));
        for (StructureSet set : data.structureSets()) {
            assertTrue(!data.dimensionsOf(set).isEmpty(), set.id() + " should belong to a dimension");
        }
    }

    @ParameterizedTest
    @MethodSource(VERSIONS)
    void rigidStructuresKnowHowTheirMainPieceIsPlaced(MinecraftVersion version) {
        RigidStructures rigid = version.data().rigidStructures();
        assertEquals(List.of("minecraft:igloos", "minecraft:ocean_ruins", "minecraft:ruined_portals", "minecraft:shipwrecks"),
            rigid.templateSets().keySet().stream().sorted().toList());
        RigidStructures.TemplateSet portals = rigid.templateSets().get("minecraft:ruined_portals");
        assertEquals(List.of("front_back", "none"), portals.mirrors());
        assertEquals(4, portals.rotations().size());
        assertEquals(13, portals.pieces().size());
        // Cold ocean ruins are three templates at one spot.
        assertTrue(rigid.templateSets().get("minecraft:ocean_ruins").pieces().stream().anyMatch(piece -> piece.templates().size() == 3));
        assertEquals(new BlockPos(3, 5, 5), rigid.templateSets().get("minecraft:igloos").pieces().get(0).pivot());
        assertEquals(List.of("minecraft:tedp"), rigid.codeBuiltSets().get("minecraft:desert_pyramids"));
        assertEquals(3, rigid.codeBuiltSets().size());
    }

    @ParameterizedTest
    @MethodSource(VERSIONS)
    void structuresKnowTheirBiomes(MinecraftVersion version) {
        assertEquals(List.of("minecraft:meadow", "minecraft:plains"), version.data().structure("village_plains").orElseThrow().biomes());
    }

    @Test
    void theNewBiomesOf26_3AreOnlyIn26_3() {
        assertTrue(DATA.structure("mineshaft").orElseThrow().biomes().contains("minecraft:dappled_forest"));
        assertTrue(!OLD.structure("mineshaft").orElseThrow().biomes().contains("minecraft:dappled_forest"));
        assertTrue(!OLD.structure("mineshaft").orElseThrow().biomes().contains("minecraft:sulfur_caves"));
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

    @ParameterizedTest
    @MethodSource(VERSIONS)
    void findsStructureSetsByTheNamesPeopleUse(MinecraftVersion version) {
        VersionData data = version.data();
        assertEquals("minecraft:villages", data.findStructureSet("village").orElseThrow().id());
        assertEquals("minecraft:villages", data.findStructureSet("Village_Desert").orElseThrow().id());
        assertEquals("minecraft:villages", data.findStructureSet("minecraft:villages").orElseThrow().id());
        assertEquals("minecraft:desert_pyramids", data.findStructureSet("desert_pyramid").orElseThrow().id());
        assertEquals("minecraft:ancient_cities", data.findStructureSet("ancient_city").orElseThrow().id());
        assertEquals("minecraft:nether_complexes", data.findStructureSet("fortress").orElseThrow().id());
        assertEquals("minecraft:nether_complexes", data.findStructureSet("bastion_remnant").orElseThrow().id());
        assertTrue(data.findStructureSet("castle").isEmpty());
    }

    @Test
    void findsAbandonedCampsIn26_3() {
        assertEquals("minecraft:abandoned_camp", DATA.findStructureSet("abandoned_camp_meadow").orElseThrow().id());
    }

    @Test
    void normalizesIds() {
        assertEquals("minecraft:villages", VersionDataLoader.normalizeId("villages"));
        assertEquals("pack:thing", VersionDataLoader.normalizeId("pack:thing"));
        assertEquals("#minecraft:is_ocean", VersionDataLoader.normalizeId("#is_ocean"));
    }

    @Test
    void knowsTheVersions() {
        assertEquals(MinecraftVersion.V26_3, MinecraftVersion.latest());
        assertEquals(Optional.of(MinecraftVersion.V26_3), MinecraftVersion.byName(" 26.3 "));
        assertEquals(Optional.of(MinecraftVersion.V1_21_11), MinecraftVersion.byName("1.21.11"));
        assertTrue(MinecraftVersion.byName("1.8").isEmpty());
        assertTrue(MinecraftVersion.byName("1.21.1").isEmpty());
        assertEquals("1.21.11, 26.3", MinecraftVersion.supportedNames());
    }
}
