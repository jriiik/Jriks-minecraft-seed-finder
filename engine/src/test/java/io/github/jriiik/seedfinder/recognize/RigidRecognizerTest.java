package io.github.jriiik.seedfinder.recognize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jriiik.seedfinder.version.MinecraftVersion;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * The rigid-structure recognizer on made-up layouts. The real layouts are built from the game's files by the mod at
 * runtime; that they and the thresholds work on real worlds was measured with the game's own generation code, and is
 * checked again by the mod's in-game test.
 */
class RigidRecognizerTest {

    private static final int MIN_Y = 0;
    private static final int MAX_Y = 127;

    /** A block of a made-up template, at its template-local position. */
    private record Block(int x, int y, int z, String id) {
    }

    /** A made-up template: its blocks and the spots where it has air. */
    private record Template(List<Block> blocks, List<int[]> air) {

        /** Turned about the template origin by quarter turns, as ocean ruins are. */
        Template turned(int quarters) {
            List<Block> blocks = new ArrayList<>();
            for (Block block : this.blocks) {
                int[] at = turn(block.x(), block.z(), quarters);
                blocks.add(new Block(at[0], block.y(), at[1], block.id()));
            }
            List<int[]> air = new ArrayList<>();
            for (int[] spot : this.air) {
                int[] at = turn(spot[0], spot[2], quarters);
                air.add(new int[] {at[0], spot[1], at[1]});
            }
            return new Template(blocks, air);
        }

        RigidLayout layout(RigidKind kind, String name) {
            List<RigidLayout.Entry> entries = new ArrayList<>();
            for (Block block : blocks) {
                entries.add(new RigidLayout.Entry(block.x(), block.y(), block.z(), Set.of(block.id())));
            }
            return RigidLayout.of(kind, name, entries, air);
        }

        /** The template with a share of its blocks missing (turned into air). */
        Template damaged(double share, long seed) {
            Random random = new Random(seed);
            List<Block> kept = new ArrayList<>();
            List<int[]> air = new ArrayList<>(this.air);
            for (Block block : blocks) {
                if (random.nextDouble() < share) {
                    air.add(new int[] {block.x(), block.y(), block.z()});
                } else {
                    kept.add(block);
                }
            }
            return new Template(kept, air);
        }
    }

    private static int[] turn(int x, int z, int quarters) {
        return switch (quarters & 3) {
            case 0 -> new int[] {x, z};
            case 1 -> new int[] {-z, x};
            case 2 -> new int[] {-x, -z};
            default -> new int[] {z, -x};
        };
    }

    /** A world of stone, then water, then air; loaded chunks only; blocks placed on top. */
    private static final class World implements BlockView {
        final Map<Long, String> blocks = new HashMap<>();
        final Set<Long> loaded = new HashSet<>();
        int stoneTop = 69;
        int waterTop = 69;
        Function<int[], String> biome = at -> null;

        World load(int fromChunk, int toChunk) {
            for (int x = fromChunk; x <= toChunk; x++) {
                for (int z = fromChunk; z <= toChunk; z++) {
                    loaded.add(RigidRecognizer.chunkKey(x, z));
                }
            }
            return this;
        }

        @Override
        public boolean isLoaded(int chunkX, int chunkZ) {
            return loaded.contains(RigidRecognizer.chunkKey(chunkX, chunkZ));
        }

        @Override
        public String blockAt(int x, int y, int z) {
            if (y < MIN_Y || y > MAX_Y || !isLoaded(x >> 4, z >> 4)) {
                return null;
            }
            String placed = blocks.get(RigidRecognizer.position(x, y, z));
            if (placed != null) {
                return placed;
            }
            return y <= stoneTop ? "minecraft:stone" : y <= waterTop ? "minecraft:water" : "minecraft:air";
        }

        @Override
        public String biomeAt(int x, int y, int z) {
            return isLoaded(x >> 4, z >> 4) ? biome.apply(new int[] {x, y, z}) : null;
        }

        void set(int x, int y, int z, String id) {
            blocks.put(RigidRecognizer.position(x, y, z), id);
        }

        /** Builds a template with its origin at (x, y, z); its air spots get {@code emptyFill} (null: left as they are). */
        void place(Template template, int x, int y, int z, String emptyFill) {
            for (Block block : template.blocks()) {
                set(x + block.x(), y + block.y(), z + block.z(), block.id());
            }
            if (emptyFill != null) {
                for (int[] spot : template.air()) {
                    set(x + spot[0], y + spot[1], z + spot[2], emptyFill);
                }
            }
        }
    }

    private static Map<RigidKind, RigidRecognizer.Rules> rules(Set<String> biomes) {
        Map<RigidKind, RigidRecognizer.Rules> rules = new EnumMap<>(RigidKind.class);
        rules.put(RigidKind.DESERT_PYRAMID, new RigidRecognizer.Rules(32, 8, biomes));
        rules.put(RigidKind.SWAMP_HUT, new RigidRecognizer.Rules(32, 8, biomes));
        rules.put(RigidKind.OCEAN_RUIN, new RigidRecognizer.Rules(20, 8, biomes));
        rules.put(RigidKind.RUINED_PORTAL, new RigidRecognizer.Rules(40, 15, biomes));
        return rules;
    }

    /** A random sandstone building of 15 x 8 x 15 blocks, about 60% blocks and 40% air. */
    private static Template pyramid() {
        Random random = new Random(7);
        List<String> ids = List.of("minecraft:orange_terracotta", "minecraft:blue_terracotta", "minecraft:cut_sandstone", "minecraft:chiseled_sandstone",
            "minecraft:sandstone_stairs", "minecraft:sandstone_slab");
        List<Block> blocks = new ArrayList<>();
        List<int[]> air = new ArrayList<>();
        for (int x = 0; x < 15; x++) {
            for (int y = 0; y < 8; y++) {
                for (int z = 0; z < 15; z++) {
                    if (random.nextDouble() < 0.6) {
                        blocks.add(new Block(x, y, z, ids.get(random.nextInt(ids.size()))));
                    } else {
                        air.add(new int[] {x, y, z});
                    }
                }
            }
        }
        return new Template(blocks, air);
    }

    private static List<RigidLayout> poses(Template template, RigidKind kind, String name) {
        List<RigidLayout> layouts = new ArrayList<>();
        for (int quarters = 0; quarters < 4; quarters++) {
            layouts.add(template.turned(quarters).layout(kind, name + " turned " + quarters));
        }
        return layouts;
    }

    private static List<String> found(List<RigidRecognizer.Detection> detections) {
        List<String> found = new ArrayList<>();
        for (RigidRecognizer.Detection d : detections) {
            found.add(d.kind() + " " + d.chunkX() + "," + d.chunkZ() + " y " + d.y());
        }
        Collections.sort(found);
        return found;
    }

    private static List<RigidRecognizer.Detection> scan(RigidRecognizer recognizer, World world, int chunkX, int chunkZ) {
        return recognizer.scan(world, chunkX - 2, chunkX + 2, chunkZ - 2, chunkZ + 2, MIN_Y, MAX_Y, false);
    }

    @Test
    void findsTheStartChunkOfABuildingInEachPose() {
        Template pyramid = pyramid();
        RigidRecognizer recognizer = new RigidRecognizer(poses(pyramid, RigidKind.DESERT_PYRAMID, "pyramid"), rules(Set.of()));
        for (int quarters = 0; quarters < 4; quarters++) {
            // Chunk -9 is the last row where the set can start in its region (spacing 32, separation 8).
            World world = new World().load(-12, 6);
            world.place(pyramid.turned(quarters), 3 * 16, 70, -9 * 16, "minecraft:air");
            assertEquals(List.of("DESERT_PYRAMID 3,-9 y 70"), found(scan(recognizer, world, 3, -9)), "turned " + quarters);
        }
    }

    @Test
    void theBiomeMustBeOneTheStructureStartsIn() {
        Template pyramid = pyramid();
        RigidRecognizer recognizer = new RigidRecognizer(poses(pyramid, RigidKind.DESERT_PYRAMID, "pyramid"), rules(Set.of("minecraft:desert")));
        World world = new World().load(-4, 4);
        world.place(pyramid, 16, 70, 16, "minecraft:air");
        world.biome = at -> "minecraft:plains";
        assertEquals(List.of(), found(scan(recognizer, world, 1, 1)));
        // The game tests the chunk's middle near the structure's base; a neighbouring cell counts too.
        world.biome = at -> at[0] >> 2 == (16 + 8 + 4) >> 2 && at[2] >> 2 == (16 + 8) >> 2 && at[1] >> 2 == 80 >> 2 ? "minecraft:desert" : "minecraft:plains";
        assertEquals(List.of("DESERT_PYRAMID 1,1 y 70"), found(scan(recognizer, world, 1, 1)));
        // Too far from the middle, or too far above the base, does not count.
        world.biome = at -> at[0] >> 2 == (16 + 8 + 8) >> 2 && at[2] >> 2 == (16 + 8) >> 2 ? "minecraft:desert" : "minecraft:plains";
        assertEquals(List.of(), found(scan(recognizer, world, 1, 1)));
        world.biome = at -> at[1] >> 2 == 92 >> 2 ? "minecraft:desert" : "minecraft:plains";
        assertEquals(List.of(), found(scan(recognizer, world, 1, 1)));
        // Unknown biomes do not count against a fit.
        world.biome = at -> null;
        assertEquals(List.of("DESERT_PYRAMID 1,1 y 70"), found(scan(recognizer, world, 1, 1)));
    }

    @Test
    void nothingIsReportedWhereTheSetCannotStart() {
        Template pyramid = pyramid();
        RigidRecognizer recognizer = new RigidRecognizer(poses(pyramid, RigidKind.DESERT_PYRAMID, "pyramid"), rules(Set.of()));
        // Spacing 32, separation 8: starts are only in the first 24 chunks of each region of 32.
        World world = new World().load(20, 32);
        world.place(pyramid, 26 * 16, 70, 26 * 16, "minecraft:air");
        assertEquals(List.of(), found(scan(recognizer, world, 26, 26)));
        world = new World().load(20, 32);
        world.place(pyramid, 23 * 16, 70, 23 * 16, "minecraft:air");
        assertEquals(List.of("DESERT_PYRAMID 23,23 y 70"), found(scan(recognizer, world, 23, 23)));
    }

    @Test
    void emptySpotsFilledWithTheStructuresOwnMaterialRuleAFitOut() {
        Template pyramid = pyramid();
        RigidRecognizer recognizer = new RigidRecognizer(poses(pyramid, RigidKind.DESERT_PYRAMID, "pyramid"), rules(Set.of()));
        World world = new World().load(-4, 4);
        // A solid block of sandstone has every block of the building, but none of its rooms.
        world.place(pyramid, 0, 70, 0, "minecraft:cut_sandstone");
        assertEquals(List.of(), found(scan(recognizer, world, 0, 0)));
    }

    @Test
    void ofTwoStartsTooCloseForTheGridOnlyTheStrongerIsKept() {
        Template pyramid = pyramid();
        RigidRecognizer recognizer = new RigidRecognizer(poses(pyramid, RigidKind.DESERT_PYRAMID, "pyramid"), rules(Set.of()));
        World world = new World().load(-6, 10);
        world.place(pyramid, 0, 70, 0, "minecraft:air");
        world.place(pyramid.damaged(0.25, 3), 2 * 16, 70, 0, "minecraft:air");
        assertEquals(List.of("DESERT_PYRAMID 0,0 y 70"), found(recognizer.scan(world, -2, 4, -2, 2, MIN_Y, MAX_Y, false)));
        // Farther apart than the separation, both fit; that both cannot start in one region is checked later
        // (RecognizedStarts).
        world = new World().load(-6, 16);
        world.place(pyramid, 0, 70, 0, "minecraft:air");
        world.place(pyramid.damaged(0.25, 3), 9 * 16, 70, 0, "minecraft:air");
        assertEquals(List.of("DESERT_PYRAMID 0,0 y 70", "DESERT_PYRAMID 9,0 y 70"), found(recognizer.scan(world, -2, 11, -2, 2, MIN_Y, MAX_Y, false)));
    }

    @Test
    void whenAnotherStartChunkFitsAsWellNothingIsReported() {
        List<Block> line = new ArrayList<>();
        for (int x = 0; x < 48; x++) {
            line.add(new Block(x, 0, 0, "minecraft:oak_planks"));
        }
        RigidLayout layout = new Template(line, List.of()).layout(RigidKind.SWAMP_HUT, "line");
        RigidRecognizer recognizer = new RigidRecognizer(List.of(layout), rules(Set.of()));
        World world = new World().load(-6, 8);
        for (int x = 0; x < 64; x++) {
            world.set(x, 70, 0, "minecraft:spruce_planks");
        }
        // The planks fit a start in chunk 0 and in chunk 1 equally well.
        assertEquals(List.of(), found(recognizer.scan(world, -2, 5, -2, 2, MIN_Y, MAX_Y, false)));
        // With the line as long as the layout, only chunk 0 fits it whole.
        world = new World().load(-6, 8);
        for (int x = 0; x < 48; x++) {
            world.set(x, 70, 0, "minecraft:birch_planks");
        }
        assertEquals(List.of("SWAMP_HUT 0,0 y 70"), found(recognizer.scan(world, -2, 5, -2, 2, MIN_Y, MAX_Y, false)));
    }

    /** A small ocean ruin: a 7 x 3 x 7 block of stone bricks with water expected above it. */
    private static Template smallRuin() {
        List<Block> blocks = new ArrayList<>();
        List<int[]> air = new ArrayList<>();
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                for (int y = 0; y < 3; y++) {
                    blocks.add(new Block(x, y, z, y == 1 && x == 3 ? "minecraft:cracked_stone_bricks" : "minecraft:stone_bricks"));
                }
                air.add(new int[] {x, 3, z});
            }
        }
        return new Template(blocks, air);
    }

    @Test
    void oceanRuinsMustLieInOpenWater() {
        Template ruin = smallRuin();
        RigidRecognizer recognizer = new RigidRecognizer(List.of(ruin.layout(RigidKind.OCEAN_RUIN, "ruin")), rules(Set.of()));
        World world = new World().load(-4, 4);
        world.stoneTop = 39;
        world.waterTop = 62;
        world.place(ruin, 0, 40, 0, null);
        assertEquals(List.of("OCEAN_RUIN 0,0 y 40"), found(scan(recognizer, world, 0, 0)));

        // Buried in rock: no column ends in open water.
        world = new World().load(-4, 4);
        world.stoneTop = 50;
        world.waterTop = 62;
        world.place(ruin, 0, 40, 0, null);
        assertEquals(List.of(), found(scan(recognizer, world, 0, 0)));

        // On dry land: open air above sea level, but no water in any of its empty spots.
        world = new World().load(-4, 4);
        world.stoneTop = 63;
        world.waterTop = 63;
        world.place(ruin, 0, 64, 0, null);
        assertEquals(List.of(), found(scan(recognizer, world, 0, 0)));
    }

    @Test
    void aSmallRuinThatFitsBetterOffTheChunkGridIsAClusterPiece() {
        Template ruin = smallRuin();
        RigidRecognizer recognizer = new RigidRecognizer(List.of(ruin.layout(RigidKind.OCEAN_RUIN, "ruin"),
            ruin.turned(2).layout(RigidKind.OCEAN_RUIN, "ruin turned 2")), rules(Set.of()));
        World world = new World().load(-4, 4);
        world.stoneTop = 39;
        world.waterTop = 62;
        // One block east of the chunk corner: the grid fit matches 6 of 7 columns, the true one all of them.
        world.place(ruin, 1, 40, 0, null);
        List<RigidRecognizer.Detection> all = new ArrayList<>();
        int[] none = new int[0];
        Map<Long, int[]> triggers = new HashMap<>();
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                triggers.put(RigidRecognizer.chunkKey(x, z), recognizer.triggersOf(world, x, z, MIN_Y, MAX_Y));
            }
        }
        for (RigidRecognizer.Candidate candidate : recognizer.candidates(0, 0, (x, z) -> triggers.getOrDefault(RigidRecognizer.chunkKey(x, z), none))) {
            RigidRecognizer.Detection d = recognizer.verify(candidate, world, false);
            if (d != null) {
                all.add(d);
            }
        }
        RigidRecognizer.Detection grid = all.stream().filter(d -> d.layout().variant().equals("ruin") && d.y() == 40).findFirst().orElseThrow();
        assertTrue(recognizer.acceptable(grid, world), grid.toString());
        assertTrue(recognizer.isClusterPiece(grid, world));
        assertEquals(List.of(), found(scan(recognizer, world, 0, 0)));

        world = new World().load(-4, 4);
        world.stoneTop = 39;
        world.waterTop = 62;
        world.place(ruin, 0, 40, 0, null);
        assertEquals(List.of("OCEAN_RUIN 0,0 y 40"), found(scan(recognizer, world, 0, 0)));
    }

    /** A ruined portal: a 4 x 5 obsidian frame standing on an 8 x 5 stone-brick base, with air in and around it. */
    private static Template portal() {
        List<Block> blocks = new ArrayList<>();
        List<int[]> air = new ArrayList<>();
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 5; z++) {
                blocks.add(new Block(x, 0, z, "minecraft:stone_bricks"));
            }
        }
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 5; j++) {
                boolean edge = i == 0 || i == 3 || j == 0 || j == 4;
                if (edge) {
                    blocks.add(new Block(2 + i, 1 + j, 2, "minecraft:obsidian"));
                } else {
                    air.add(new int[] {2 + i, 1 + j, 2});
                }
            }
        }
        for (int x = 0; x < 8; x++) {
            for (int y = 1; y < 7; y++) {
                air.add(new int[] {x, y, 1});
                air.add(new int[] {x, y, 3});
            }
        }
        blocks.add(new Block(0, 1, 2, "minecraft:gold_block"));
        return new Template(blocks, air);
    }

    @Test
    void portalBlocksUnderNetherrackAreNotJudgedButAFrameAloneIsNoRuinedPortal() {
        Template portal = portal();
        RigidRecognizer recognizer = new RigidRecognizer(poses(portal, RigidKind.RUINED_PORTAL, "portal"), rules(Set.of()));
        World world = new World().load(-4, 4);
        world.place(portal, 16, 70, 16, "minecraft:air");
        assertEquals(List.of("RUINED_PORTAL 1,1 y 70"), found(scan(recognizer, world, 1, 1)));

        // Half the base covered with netherrack (and magma): still a ruined portal.
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 5; z++) {
                if ((x + z) % 2 == 0) {
                    world.set(16 + x, 70, 16 + z, x == 0 ? "minecraft:magma_block" : "minecraft:netherrack");
                }
            }
        }
        assertEquals(List.of("RUINED_PORTAL 1,1 y 70"), found(scan(recognizer, world, 1, 1)));

        // The whole base covered: only the obsidian frame is left to judge, which is not enough.
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 5; z++) {
                world.set(16 + x, 70, 16 + z, "minecraft:netherrack");
            }
        }
        assertEquals(List.of(), found(scan(recognizer, world, 1, 1)));
    }

    @Test
    void aPortalWithItsOwnMaterialInAnEmptySpotIsAPlayersNotARuin() {
        Template portal = portal();
        RigidRecognizer recognizer = new RigidRecognizer(poses(portal, RigidKind.RUINED_PORTAL, "portal"), rules(Set.of()));
        World world = new World().load(-4, 4);
        world.place(portal, 16, 70, 16, "minecraft:air");
        world.set(16 + 3, 70 + 2, 16 + 2, "minecraft:obsidian");
        assertEquals(List.of(), found(scan(recognizer, world, 1, 1)));
    }

    @Test
    void inTheNetherLavaAroundAPortalIsNotJudged() {
        Template portal = portal();
        RigidRecognizer recognizer = new RigidRecognizer(poses(portal, RigidKind.RUINED_PORTAL, "portal"), rules(Set.of()));
        World world = new World().load(-4, 4);
        world.place(portal, 16, 70, 16, "minecraft:air");
        // Lava has replaced most of the base (it stood in the nether's lava sea).
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 5; z++) {
                if (x > 1) {
                    world.set(16 + x, 70, 16 + z, "minecraft:lava");
                }
            }
        }
        assertEquals(List.of(), found(scan(recognizer, world, 1, 1)));
        assertEquals(List.of("RUINED_PORTAL 1,1 y 70"), found(recognizer.scan(world, -1, 3, -1, 3, MIN_Y, MAX_Y, true)));
    }

    @Test
    void verifyLeavesOutOnlyFitsThatCanNeverMatter() {
        List<Block> blocks = new ArrayList<>();
        for (int x = 0; x < 20; x++) {
            blocks.add(new Block(x, 0, 0, "minecraft:cut_sandstone"));
        }
        RigidLayout layout = new Template(blocks, List.of()).layout(RigidKind.DESERT_PYRAMID, "line");
        RigidRecognizer recognizer = new RigidRecognizer(List.of(layout), rules(Set.of()));
        RigidRecognizer.Candidate candidate = new RigidRecognizer.Candidate(layout, 0, 70, 0, 3);
        // A rival must score at least 0.5 - 0.15 = 0.35: 7 of 20 still counts, 6 of 20 never can.
        World world = new World().load(-2, 2);
        for (int x = 0; x < 7; x++) {
            world.set(x * 3 % 20, 70, 0, "minecraft:cut_sandstone");
        }
        RigidRecognizer.Detection d = recognizer.verify(candidate, world, false);
        assertNotNull(d);
        assertEquals(7, d.matched());
        assertEquals(20, d.checked());
        world.set(18, 70, 0, "minecraft:stone");
        assertNull(recognizer.verify(candidate, world, false));
    }

    @Test
    void theTrackerFindsWhatAScanFindsAndOnlyOnceEverythingAroundIsRead() {
        Template pyramid = pyramid();
        Template ruin = smallRuin();
        Template portal = portal();
        List<RigidLayout> layouts = new ArrayList<>(poses(pyramid, RigidKind.DESERT_PYRAMID, "pyramid"));
        layouts.add(ruin.layout(RigidKind.OCEAN_RUIN, "ruin"));
        layouts.addAll(poses(portal, RigidKind.RUINED_PORTAL, "portal"));
        RigidRecognizer recognizer = new RigidRecognizer(layouts, rules(Set.of()));
        World world = new World().load(-14, 9);
        world.stoneTop = 39;
        world.waterTop = 62;
        world.place(pyramid.turned(1), 3 * 16, 70, 4 * 16, "minecraft:air");
        world.place(ruin, -10 * 16, 40, 2 * 16, null);
        world.place(portal.turned(3), 0, 64, 0, "minecraft:air");
        world.place(pyramid.damaged(0.2, 5), -9 * 16, 70, -9 * 16, "minecraft:air");
        List<String> expected = found(recognizer.scan(world, -14, 9, -14, 9, MIN_Y, MAX_Y, false));
        assertEquals(List.of("DESERT_PYRAMID -9,-9 y 70", "DESERT_PYRAMID 3,4 y 70", "OCEAN_RUIN -10,2 y 40", "RUINED_PORTAL 0,0 y 64"), expected);

        RigidTracker tracker = new RigidTracker(recognizer, false);
        List<long[]> chunks = new ArrayList<>();
        for (int x = -14; x <= 9; x++) {
            for (int z = -14; z <= 9; z++) {
                chunks.add(new long[] {x, z});
            }
        }
        Collections.shuffle(chunks, new Random(11));
        Set<Long> scanned = new HashSet<>();
        List<RigidRecognizer.Detection> detections = new ArrayList<>();
        for (int i = 0; i <= chunks.size(); i++) {
            if (i < chunks.size()) {
                int x = (int) chunks.get(i)[0];
                int z = (int) chunks.get(i)[1];
                tracker.chunkScanned(x, z, recognizer.triggersOf(world, x, z, MIN_Y, MAX_Y));
                scanned.add(RigidRecognizer.chunkKey(x, z));
            }
            // Each call looks at a limited number of waiting chunks; at the end, call until all are decided.
            for (int calls = 0; calls < (i < chunks.size() ? 1 : 1000); calls++) {
                for (RigidRecognizer.Detection d : tracker.work(world, () -> false)) {
                    for (int dx = -tracker.reach(); dx <= tracker.reach(); dx++) {
                        for (int dz = -tracker.reach(); dz <= tracker.reach(); dz++) {
                            assertTrue(scanned.contains(RigidRecognizer.chunkKey(d.chunkX() + dx, d.chunkZ() + dz)), d + " decided too early");
                        }
                    }
                    detections.add(d);
                }
            }
        }
        assertEquals(expected, found(detections));
        assertEquals(0, tracker.pendingCount());
    }

    @Test
    void theTrackerPicksUpAgainWhereItStopped() {
        Template pyramid = pyramid();
        RigidRecognizer recognizer = new RigidRecognizer(poses(pyramid, RigidKind.DESERT_PYRAMID, "pyramid"), rules(Set.of()));
        World world = new World().load(-6, 6);
        world.place(pyramid, 0, 70, 0, "minecraft:air");
        world.place(pyramid.damaged(0.3, 1), 16 * 2, 70, 16 * 2, "minecraft:air");
        RigidTracker tracker = new RigidTracker(recognizer, false);
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                tracker.chunkScanned(x, z, recognizer.triggersOf(world, x, z, MIN_Y, MAX_Y));
            }
        }
        List<RigidRecognizer.Detection> detections = new ArrayList<>();
        int calls = 0;
        while (tracker.pendingCount() > 0 || calls == 0) {
            int[] steps = {0};
            // One step per call: as when the time budget runs out after every piece of work.
            detections.addAll(tracker.work(world, () -> steps[0]++ > 0));
            calls++;
            assertTrue(calls < 100_000);
        }
        detections.addAll(tracker.work(world, () -> false));
        assertEquals(List.of("DESERT_PYRAMID 0,0 y 70"), found(detections));
    }

    @Test
    void theTrackerWaitsWhileChunksAroundAreMissing() {
        Template pyramid = pyramid();
        RigidRecognizer recognizer = new RigidRecognizer(poses(pyramid, RigidKind.DESERT_PYRAMID, "pyramid"), rules(Set.of()));
        World world = new World().load(-6, 6);
        world.place(pyramid, 0, 70, 0, "minecraft:air");
        RigidTracker tracker = new RigidTracker(recognizer, false);
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                if (x != tracker.reach() || z != 0) {
                    tracker.chunkScanned(x, z, recognizer.triggersOf(world, x, z, MIN_Y, MAX_Y));
                }
            }
        }
        assertEquals(List.of(), tracker.work(world, () -> false));
        // Read, but unloaded since: still waiting.
        tracker.chunkScanned(tracker.reach(), 0, new int[0]);
        world.loaded.remove(RigidRecognizer.chunkKey(-tracker.reach(), 1));
        assertEquals(List.of(), tracker.work(world, () -> false));
        world.loaded.add(RigidRecognizer.chunkKey(-tracker.reach(), 1));
        assertEquals(List.of("DESERT_PYRAMID 0,0 y 70"), found(tracker.work(world, () -> false)));

        // A chunk is decided over several calls; one that unloads in between makes it wait, but it is not forgotten.
        RigidTracker interrupted = new RigidTracker(recognizer, false);
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                interrupted.chunkScanned(x, z, recognizer.triggersOf(world, x, z, MIN_Y, MAX_Y));
            }
        }
        long flickering = RigidRecognizer.chunkKey(interrupted.reach(), 0);
        List<RigidRecognizer.Detection> detections = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            int[] steps = {0};
            detections.addAll(interrupted.work(world, () -> steps[0]++ > 0));
            if (i % 2 == 0) {
                world.loaded.remove(flickering);
            } else {
                world.loaded.add(flickering);
            }
        }
        world.loaded.add(flickering);
        for (int i = 0; i < 1000; i++) {
            detections.addAll(interrupted.work(world, () -> false));
        }
        assertEquals(List.of("DESERT_PYRAMID 0,0 y 70"), found(detections));
    }

    @Test
    void startsTooCloseForTheGridButFartherThanTheRivalsAreBothDroppedByTheirRegion() {
        // The tracker only lets fits within RIVALS chunks compete. Two fits farther apart but within the set's
        // separation can't both be starts: the tracker reports both, and their placement region then reports neither.
        Template pyramid = pyramid();
        RigidRecognizer recognizer = new RigidRecognizer(poses(pyramid, RigidKind.DESERT_PYRAMID, "pyramid"), rules(Set.of()));
        World world = new World().load(-6, 12);
        int apart = RigidTracker.RIVALS + 2;
        world.place(pyramid, 0, 70, 0, "minecraft:air");
        world.place(pyramid.damaged(0.25, 9), apart * 16, 70, 0, "minecraft:air");
        assertEquals(List.of("DESERT_PYRAMID 0,0 y 70"), found(recognizer.scan(world, -2, apart + 2, -2, 2, MIN_Y, MAX_Y, false)));
        RigidTracker tracker = new RigidTracker(recognizer, false);
        for (int x = -6; x <= 12; x++) {
            for (int z = -6; z <= 12; z++) {
                tracker.chunkScanned(x, z, recognizer.triggersOf(world, x, z, MIN_Y, MAX_Y));
            }
        }
        List<RigidRecognizer.Detection> detections = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            detections.addAll(tracker.work(world, () -> false));
        }
        assertEquals(List.of("DESERT_PYRAMID 0,0 y 70", "DESERT_PYRAMID " + apart + ",0 y 70"), found(detections));
        RecognizedStarts starts = new RecognizedStarts();
        detections.forEach(d -> starts.add(d.kind().structureSet(), recognizer.rules(d.kind()).spacing(), d.chunkX(), d.chunkZ()));
        assertEquals(List.of(), starts.starts());
        assertEquals(2, starts.conflicting().size());
    }

    /** Counts the blocks and biomes read. */
    private static final class Counting implements BlockView {
        final BlockView view;
        long reads;

        Counting(BlockView view) {
            this.view = view;
        }

        @Override
        public boolean isLoaded(int chunkX, int chunkZ) {
            return view.isLoaded(chunkX, chunkZ);
        }

        @Override
        public String blockAt(int x, int y, int z) {
            reads++;
            return view.blockAt(x, y, z);
        }

        @Override
        public String biomeAt(int x, int y, int z) {
            reads++;
            return view.biomeAt(x, y, z);
        }
    }

    /** Many small random layouts and a world strewn with their blocks: many placements get votes. */
    private static List<RigidLayout> randomLayouts(int count, long seed) {
        Random random = new Random(seed);
        List<String> ids = List.of("minecraft:orange_terracotta", "minecraft:blue_terracotta", "minecraft:cut_sandstone", "minecraft:chiseled_sandstone");
        List<RigidLayout> layouts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            List<Block> blocks = new ArrayList<>();
            for (int b = 0; b < 30; b++) {
                blocks.add(new Block(random.nextInt(20) - 2, random.nextInt(8), random.nextInt(20) - 2, ids.get(random.nextInt(ids.size()))));
            }
            layouts.add(new Template(blocks, List.of(new int[] {30, 0, 30})).layout(RigidKind.DESERT_PYRAMID, "random " + i));
        }
        return layouts;
    }

    private static World strewn(long seed) {
        Random random = new Random(seed);
        List<String> ids = List.of("minecraft:orange_terracotta", "minecraft:blue_terracotta", "minecraft:cut_sandstone", "minecraft:chiseled_sandstone");
        World world = new World().load(-4, 4);
        for (int i = 0; i < 200_000; i++) {
            world.set(random.nextInt(5 * 16) - 32, random.nextInt(MAX_Y + 1), random.nextInt(5 * 16) - 32, ids.get(random.nextInt(ids.size())));
        }
        return world;
    }

    @Test
    void votesCountedAFewBlocksAtATimeAreTheSameAsAllAtOnce() {
        RigidRecognizer recognizer = new RigidRecognizer(randomLayouts(40, 3), rules(Set.of()));
        World world = strewn(5);
        Map<Long, int[]> triggers = new HashMap<>();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                triggers.put(RigidRecognizer.chunkKey(x, z), recognizer.triggersOf(world, x, z, MIN_Y, MAX_Y));
            }
        }
        RigidRecognizer.Votes votes = recognizer.votes();
        int span = recognizer.span();
        for (int[] origin : new int[][] {{0, 0}, {1, -1}, {0, 0}}) {
            List<RigidRecognizer.Candidate> all = recognizer.candidates(origin[0], origin[1], (x, z) -> triggers.get(RigidRecognizer.chunkKey(x, z)));
            assertTrue(all.size() > 300, "candidates: " + all.size());
            // The same counter, used again, in slices of 7 trigger blocks.
            votes.start(origin[0], origin[1]);
            for (int x = origin[0] - span; x <= origin[0] + span; x++) {
                for (int z = origin[1] - span; z <= origin[1] + span; z++) {
                    int[] found = triggers.get(RigidRecognizer.chunkKey(x, z));
                    for (int from = 0; from < found.length; from += 7) {
                        votes.add(x, z, found, from, Math.min(found.length, from + 7));
                    }
                }
            }
            assertEquals(all, votes.candidates());
        }
        // A chunk not looked at yet: no answer.
        assertNull(recognizer.candidates(4, 4, (x, z) -> triggers.get(RigidRecognizer.chunkKey(x, z))));
    }

    @Test
    void aComparisonDoneABlockAtATimeIsTheSame() {
        Template pyramid = pyramid();
        List<RigidLayout> layouts = new ArrayList<>(poses(pyramid, RigidKind.DESERT_PYRAMID, "pyramid"));
        layouts.add(smallRuin().layout(RigidKind.OCEAN_RUIN, "ruin"));
        RigidRecognizer recognizer = new RigidRecognizer(layouts, rules(Set.of()));
        World world = new World().load(-4, 4);
        world.stoneTop = 39;
        world.waterTop = 62;
        world.place(pyramid.damaged(0.3, 4), 0, 70, 0, "minecraft:air");
        world.place(smallRuin(), 16, 40, 16, null);
        int compared = 0;
        int detected = 0;
        for (int cx = -1; cx <= 1; cx++) {
            for (int cz = -1; cz <= 1; cz++) {
                Map<Long, int[]> triggers = new HashMap<>();
                for (int x = cx - 1; x <= cx + 1; x++) {
                    for (int z = cz - 1; z <= cz + 1; z++) {
                        triggers.put(RigidRecognizer.chunkKey(x, z), recognizer.triggersOf(world, x, z, MIN_Y, MAX_Y));
                    }
                }
                for (RigidRecognizer.Candidate candidate : recognizer.candidates(cx, cz, (x, z) -> triggers.get(RigidRecognizer.chunkKey(x, z)))) {
                    RigidRecognizer.Detection whole = recognizer.verify(candidate, world, false);
                    Counting counting = new Counting(world);
                    RigidRecognizer.Verification verification = recognizer.verification(candidate, false);
                    long before = 0;
                    while (!verification.step(counting, 1)) {
                        assertTrue(counting.reads - before <= 1);
                        before = counting.reads;
                    }
                    assertTrue(counting.reads - before <= 1);
                    RigidRecognizer.Detection stepped = verification.result();
                    compared++;
                    if (whole == null) {
                        assertNull(stepped);
                        continue;
                    }
                    detected++;
                    assertEquals(whole.toString(), stepped.toString());
                    assertEquals(Double.doubleToLongBits(whole.balanced()), Double.doubleToLongBits(stepped.balanced()));
                    assertTrue(java.util.Arrays.equals(whole.matchedPositions, stepped.matchedPositions));
                    assertEquals(recognizer.acceptable(whole, world), recognizer.acceptable(stepped, world));
                }
            }
        }
        assertTrue(compared > 10 && detected >= 2, compared + " compared, " + detected + " detected");
    }

    @Test
    void theClusterTestDoneAFewBlocksAtATimeIsTheSame() {
        Template ruin = smallRuin();
        RigidRecognizer recognizer = new RigidRecognizer(List.of(ruin.layout(RigidKind.OCEAN_RUIN, "ruin"),
            ruin.turned(2).layout(RigidKind.OCEAN_RUIN, "ruin turned 2")), rules(Set.of()));
        for (int shift = 0; shift <= 1; shift++) {
            World world = new World().load(-4, 4);
            world.stoneTop = 39;
            world.waterTop = 62;
            world.place(ruin, shift, 40, 0, null);
            RigidRecognizer.Detection grid = recognizer.verify(new RigidRecognizer.Candidate(recognizer.layouts().get(0), 0, 40, 0, 3), world, false);
            assertNotNull(grid);
            RigidRecognizer.ClusterTest test = recognizer.clusterTest(grid);
            Counting counting = new Counting(world);
            long before = 0;
            while (!test.step(counting, 10)) {
                assertTrue(counting.reads - before <= 10);
                before = counting.reads;
            }
            assertEquals(recognizer.isClusterPiece(grid, world), test.isClusterPiece());
            assertEquals(shift == 1, test.isClusterPiece());
        }
    }

    @Test
    void theTrackerNeverReadsMuchInOneStep() {
        Template pyramid = pyramid();
        Template ruin = smallRuin();
        List<RigidLayout> layouts = new ArrayList<>(poses(pyramid, RigidKind.DESERT_PYRAMID, "pyramid"));
        layouts.add(ruin.layout(RigidKind.OCEAN_RUIN, "ruin"));
        layouts.add(ruin.turned(2).layout(RigidKind.OCEAN_RUIN, "ruin turned 2"));
        RigidRecognizer recognizer = new RigidRecognizer(layouts, rules(Set.of()));
        World world = new World().load(-10, 10);
        world.stoneTop = 39;
        world.waterTop = 62;
        world.place(pyramid, 0, 70, 0, "minecraft:air");
        world.place(ruin, 2 * 16, 40, 4 * 16, null);
        // One block off the chunk grid: a cluster ruin, whose grid fit is left out.
        world.place(ruin, 5 * 16 + 1, 40, 5 * 16, null);
        Counting counting = new Counting(world);
        RigidTracker tracker = new RigidTracker(recognizer, false);
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 10; z++) {
                tracker.chunkScanned(x, z, recognizer.triggersOf(world, x, z, MIN_Y, MAX_Y));
            }
        }
        List<RigidRecognizer.Detection> detections = new ArrayList<>();
        long[] most = {0};
        long[] before = {0};
        for (int calls = 0; calls < 100_000 && tracker.pendingCount() > 0; calls++) {
            detections.addAll(tracker.work(counting, () -> {
                most[0] = Math.max(most[0], counting.reads - before[0]);
                before[0] = counting.reads;
                return false;
            }));
            most[0] = Math.max(most[0], counting.reads - before[0]);
            before[0] = counting.reads;
        }
        assertEquals(List.of("DESERT_PYRAMID 0,0 y 70", "OCEAN_RUIN 2,4 y 40"), found(detections));
        assertTrue(most[0] <= RigidTracker.STEP_READS, "most reads in one step: " + most[0]);
        // Nothing left to do: a call reads nothing.
        long reads = counting.reads;
        tracker.work(counting, () -> false);
        assertEquals(reads, counting.reads);
    }

    @Test
    void aChunkWhereNothingFitsIsJudgedWithoutWaitingForItsRivals() {
        Template pyramid = pyramid();
        List<RigidLayout> layouts = new ArrayList<>(poses(pyramid, RigidKind.DESERT_PYRAMID, "pyramid"));
        layouts.addAll(poses(portal(), RigidKind.RUINED_PORTAL, "portal"));
        RigidRecognizer recognizer = new RigidRecognizer(layouts, rules(Set.of()));
        World world = new World().load(-8, 8);
        // A few blocks of the pyramid: enough votes to compare, far from enough to fit.
        int placed = 0;
        for (Block block : pyramid.blocks()) {
            if (placed++ % 9 == 0) {
                world.set(block.x(), 70 + block.y(), block.z(), block.id());
            }
        }
        RigidTracker tracker = new RigidTracker(recognizer, false);
        assertTrue(tracker.reach() > recognizer.readSpan() + recognizer.span());
        int read = recognizer.readSpan() + recognizer.span();
        for (int x = -read; x <= read; x++) {
            for (int z = -read; z <= read; z++) {
                tracker.chunkScanned(x, z, recognizer.triggersOf(world, x, z, MIN_Y, MAX_Y));
            }
        }
        int[] none = new int[0];
        assertFalse(recognizer.candidates(0, 0, (x, z) -> Math.max(Math.abs(x), Math.abs(z)) <= read ? recognizer.triggersOf(world, x, z, MIN_Y, MAX_Y)
            : none).isEmpty());
        assertTrue(tracker.pendingCount() > 0);
        for (int i = 0; i < 100; i++) {
            assertEquals(List.of(), tracker.work(world, () -> false));
        }
        assertEquals(0, tracker.pendingCount());
    }

    @Test
    void theTrackerWaitsForEverythingTheClusterTestReads() {
        // A small ruin 29 blocks long: the cluster test around a fit reads 24 blocks to either side of its origin.
        List<Block> line = new ArrayList<>();
        for (int x = -14; x <= 14; x++) {
            line.add(new Block(x, 0, 0, "minecraft:stone_bricks"));
        }
        RigidRecognizer recognizer = new RigidRecognizer(List.of(new Template(line, List.of()).layout(RigidKind.OCEAN_RUIN, "line")), rules(Set.of()));
        assertEquals(1, recognizer.span());
        assertEquals(1, recognizer.readSpan());
        assertEquals(2, recognizer.clusterSpan());
        assertEquals(RigidTracker.RIVALS + 2, new RigidTracker(recognizer, false).reach());
        assertEquals(0, new RigidRecognizer(poses(pyramid(), RigidKind.DESERT_PYRAMID, "pyramid"), rules(Set.of())).clusterSpan());
    }

    @Test
    void layoutsMergeStackedTemplatesAndApplyEachKindsRules() {
        List<RigidLayout.Entry> entries = List.of(
            new RigidLayout.Entry(0, 0, 0, Set.of("minecraft:obsidian")),
            new RigidLayout.Entry(1, 0, 0, Set.of("minecraft:mossy_stone_bricks")),
            new RigidLayout.Entry(2, 0, 0, Set.of("minecraft:gold_block")),
            new RigidLayout.Entry(3, 0, 0, Set.of("minecraft:structure_void")),
            new RigidLayout.Entry(4, 0, 0, Set.of("minecraft:netherrack")),
            new RigidLayout.Entry(0, 1, 0, Set.of("minecraft:crying_obsidian")));
        List<int[]> air = List.of(new int[] {2, 0, 0}, new int[] {3, 0, 0}, new int[] {5, 0, 0}, new int[] {5, 0, 0}, new int[] {0, 0, 0});
        RigidLayout portal = RigidLayout.of(RigidKind.RUINED_PORTAL, "test", entries, air);
        // Gold and structure voids are left out, but their spots are not empty.
        assertEquals(4, portal.size());
        assertEquals(1, portal.ex.length);
        assertEquals(5, portal.ex[0]);
        assertEquals(Set.of("obsidian"), Set.of(portal.accept[0]));
        assertEquals(RigidBlocks.STONE_FAMILY_ARTIFICIAL, Set.of(portal.accept[1]));
        assertTrue(portal.informative[1]);
        // Netherrack is part of the template but never tells a portal apart.
        assertFalse(portal.informative[2]);
        assertEquals(3, portal.informativeCount());
        assertEquals(Set.of("obsidian"), Set.of(portal.trigger[3]));

        // Stacked templates: the classes at one spot are merged; a spot is telling only if all its classes are.
        RigidLayout igloo = RigidLayout.of(RigidKind.IGLOO, "test", List.of(
            new RigidLayout.Entry(3, 0, 5, Set.of("minecraft:spruce_trapdoor", "minecraft:snow_block")),
            new RigidLayout.Entry(3, 1, 5, Set.of("minecraft:white_carpet")),
            new RigidLayout.Entry(3, 1, 5, Set.of("minecraft:light_gray_carpet"))), List.of());
        assertEquals(2, igloo.size());
        assertEquals(new TreeSet<>(Set.of("snow_block", "wood_trapdoor")), new TreeSet<>(Set.of(igloo.accept[0])));
        assertFalse(igloo.informative[0]);
        assertEquals(Set.of("wood_trapdoor"), Set.of(igloo.trigger[0]));
        assertTrue(igloo.informative[1]);
        assertEquals(Set.of("light_gray_carpet", "white_carpet"), Set.of(igloo.accept[1]));
    }

    @Test
    void blockClassesCollapseWhatTheGameVariesAtRandom() {
        assertEquals("wood_planks", RigidBlocks.canonical("minecraft:dark_oak_planks"));
        assertEquals("wood_planks", RigidBlocks.canonical("minecraft:pale_oak_planks"));
        assertEquals("wood_log", RigidBlocks.canonical("minecraft:stripped_spruce_log"));
        assertEquals("wood_log", RigidBlocks.canonical("minecraft:crimson_stem"));
        assertEquals("wood_fence_gate", RigidBlocks.canonical("minecraft:birch_fence_gate"));
        assertEquals("wood_wall_sign", RigidBlocks.canonical("minecraft:oak_wall_sign"));
        assertEquals("stone_bricks", RigidBlocks.canonical("minecraft:infested_cracked_stone_bricks"));
        assertEquals("cobblestone", RigidBlocks.canonical("minecraft:mossy_cobblestone"));
        assertEquals("obsidian", RigidBlocks.canonical("minecraft:crying_obsidian"));
        assertEquals("cauldron", RigidBlocks.canonical("minecraft:water_cauldron"));
        assertEquals("air", RigidBlocks.canonical("minecraft:cave_air"));
        assertEquals("stone", RigidBlocks.canonical("minecraft:stone"));
        assertEquals("oak_leaves", RigidBlocks.canonical("minecraft:oak_leaves"));
        assertEquals("polished_blackstone_bricks", RigidBlocks.canonical("minecraft:cracked_polished_blackstone_bricks"));
        assertNull(RigidBlocks.canonical(null));
    }

    @Test
    void theRulesComeFromTheVersionDataPerDimension() {
        RigidRecognizer overworld = RigidRecognizer.create(List.of(), MinecraftVersion.V26_3.data(), "minecraft:overworld");
        assertEquals(Set.of(RigidKind.values()), overworld.kinds());
        assertEquals(new RigidRecognizer.Rules(32, 8, overworld.rules(RigidKind.DESERT_PYRAMID).biomes()), overworld.rules(RigidKind.DESERT_PYRAMID));
        assertEquals(Set.of("minecraft:desert"), overworld.rules(RigidKind.DESERT_PYRAMID).biomes());
        assertEquals(24, overworld.rules(RigidKind.SHIPWRECK).spacing());
        assertTrue(overworld.rules(RigidKind.SHIPWRECK).biomes().contains("minecraft:beach"));
        assertTrue(overworld.rules(RigidKind.OCEAN_RUIN).biomes().containsAll(Set.of("minecraft:warm_ocean", "minecraft:frozen_ocean")));
        RigidRecognizer nether = RigidRecognizer.create(List.of(), MinecraftVersion.V26_3.data(), "minecraft:the_nether");
        assertEquals(Set.of(RigidKind.RUINED_PORTAL), nether.kinds());
        assertEquals(40, nether.rules(RigidKind.RUINED_PORTAL).spacing());
        assertEquals(15, nether.rules(RigidKind.RUINED_PORTAL).separation());
        assertTrue(nether.rules(RigidKind.RUINED_PORTAL).biomes().contains("minecraft:nether_wastes"));
        assertEquals(Set.of(), RigidRecognizer.create(List.of(), MinecraftVersion.V26_3.data(), "minecraft:the_end").kinds());
    }
}
