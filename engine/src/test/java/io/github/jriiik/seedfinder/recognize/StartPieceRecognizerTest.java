package io.github.jriiik.seedfinder.recognize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jriiik.seedfinder.world.BlockPos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The recognizer on made-up blueprints. That the game really puts start pieces this way is checked against the
 * game itself by the mod's in-game test, which compares recognized starts with /locate.
 */
class StartPieceRecognizerTest {

    private static final List<String> BUILT = List.of("minecraft:oak_planks", "minecraft:white_wool", "minecraft:cobblestone_wall",
        "minecraft:barrel", "minecraft:spruce_stairs", "minecraft:hay_block");

    /** A world made of a few blocks; everything else in a loaded chunk is air. */
    private static final class TestWorld implements BlockView {
        final Map<BlockPos, String> blocks = new HashMap<>();
        final Set<Long> loaded = new HashSet<>();

        void load(int fromChunkX, int toChunkX, int fromChunkZ, int toChunkZ) {
            for (int x = fromChunkX; x <= toChunkX; x++) {
                for (int z = fromChunkZ; z <= toChunkZ; z++) {
                    loaded.add(((long) x << 32) | (z & 0xFFFFFFFFL));
                }
            }
        }

        @Override
        public boolean isLoaded(int chunkX, int chunkZ) {
            return loaded.contains(((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL));
        }

        @Override
        public String blockAt(int x, int y, int z) {
            return blocks.getOrDefault(new BlockPos(x, y, z), "minecraft:air");
        }

        /** Places a piece the way the game does: its reference point on the chunk's corner, turned around it. */
        void place(StartPiece piece, List<StartPiece.Block> blocks, int chunkX, int chunkZ, int y, PieceRotation rotation) {
            BlockPos reference = piece.references().get(0);
            int originX = chunkX * 16 - rotation.x(reference.x(), reference.z());
            int originZ = chunkZ * 16 - rotation.z(reference.x(), reference.z());
            for (StartPiece.Block block : blocks) {
                this.blocks.put(new BlockPos(originX + rotation.x(block.x(), block.z()), y + block.y(), originZ + rotation.z(block.x(), block.z())),
                    block.id());
            }
        }

        /** Every start chunk the recognizer finds, by checking every candidate of every anchor block. */
        RecognizedStarts recognize(StartPieceRecognizer recognizer) {
            RecognizedStarts starts = new RecognizedStarts();
            blocks.forEach((pos, id) -> recognizer.candidates(id, pos.x(), pos.y(), pos.z()).forEach(candidate -> {
                if (recognizer.check(candidate, this).verdict() == StartPieceRecognizer.Verdict.MATCH) {
                    starts.add(candidate);
                }
            }));
            return starts;
        }
    }

    /** An irregular 11 x 6 x 9 blueprint: grass floor, built walls of mixed blocks, one bell when asked. */
    private static List<StartPiece.Block> blueprint(long seed, boolean bell) {
        Random random = new Random(seed);
        List<StartPiece.Block> blocks = new ArrayList<>();
        for (int x = 0; x < 11; x++) {
            for (int z = 0; z < 9; z++) {
                blocks.add(new StartPiece.Block(x, 0, z, "minecraft:grass_block"));
                for (int y = 1; y < 6; y++) {
                    if (random.nextInt(3) == 0) {
                        blocks.add(new StartPiece.Block(x, y, z, BUILT.get(random.nextInt(BUILT.size()))));
                    }
                }
            }
        }
        if (bell) {
            blocks.add(new StartPiece.Block(4, 6, 3, "minecraft:bell"));
        }
        return blocks;
    }

    private static StartPiece piece(List<StartPiece.Block> blocks, BlockPos reference) {
        return new StartPiece("test_piece", "villages", 34, 8, blocks, List.of(reference));
    }

    @Test
    void rotationsTurnAQuarterEachTime() {
        int[][] offsets = {{3, 0}, {0, 5}, {-2, 7}, {4, -1}};
        for (int[] offset : offsets) {
            int x = offset[0];
            int z = offset[1];
            for (PieceRotation rotation : PieceRotation.values()) {
                int turns = rotation.ordinal() == 3 ? 3 : rotation.ordinal();
                int expectedX = x;
                int expectedZ = z;
                for (int i = 0; i < turns; i++) {
                    int turnedX = -expectedZ;
                    expectedZ = expectedX;
                    expectedX = turnedX;
                }
                assertEquals(expectedX, rotation.x(x, z), rotation + " x of " + x + "," + z);
                assertEquals(expectedZ, rotation.z(x, z), rotation + " z of " + x + "," + z);
            }
        }
        // Clockwise seen from above (x east, z south): east turns to south.
        assertEquals(0, PieceRotation.CLOCKWISE_90.x(1, 0));
        assertEquals(1, PieceRotation.CLOCKWISE_90.z(1, 0));
    }

    @Test
    void findsTheStartChunkInEveryRotation() {
        List<StartPiece.Block> blocks = blueprint(1, true);
        StartPiece piece = piece(blocks, BlockPos.ZERO);
        StartPieceRecognizer recognizer = new StartPieceRecognizer(List.of(piece));
        int[][] chunks = {{5, 7}, {-30, 3}, {-34, -10}, {68 + 2, -34 * 3 + 20}};
        for (int[] chunk : chunks) {
            for (PieceRotation rotation : PieceRotation.values()) {
                TestWorld world = new TestWorld();
                world.load(chunk[0] - 2, chunk[0] + 2, chunk[1] - 2, chunk[1] + 2);
                world.place(piece, blocks, chunk[0], chunk[1], 64, rotation);
                RecognizedStarts found = world.recognize(recognizer);
                assertEquals(List.of(new RecognizedStarts.Start("villages", chunk[0], chunk[1])), found.starts(), rotation + " at " + chunk[0] + "," + chunk[1]);
            }
        }
    }

    @Test
    void anInnerReferencePointWorksToo() {
        // Like an ancient city centre, whose named jigsaw block (not the origin) sits on the chunk corner.
        List<StartPiece.Block> blocks = blueprint(2, true);
        StartPiece piece = piece(blocks, new BlockPos(7, 3, 5));
        StartPieceRecognizer recognizer = new StartPieceRecognizer(List.of(piece));
        for (PieceRotation rotation : PieceRotation.values()) {
            TestWorld world = new TestWorld();
            world.load(-3, 3, 0, 6);
            world.place(piece, blocks, 1, 3, -40, rotation);
            assertEquals(List.of(new RecognizedStarts.Start("villages", 1, 3)), world.recognize(recognizer).starts(), rotation.name());
        }
    }

    @Test
    void aPieceOffTheChunkCornerIsNotAStart() {
        List<StartPiece.Block> blocks = blueprint(3, true);
        StartPiece piece = piece(blocks, BlockPos.ZERO);
        TestWorld world = new TestWorld();
        world.load(-2, 4, -2, 4);
        // The same blocks one block east of where the game would put them.
        for (StartPiece.Block block : blocks) {
            world.blocks.put(new BlockPos(16 + 1 + block.x(), 70 + block.y(), 16 + block.z()), block.id());
        }
        assertEquals(List.of(), world.recognize(new StartPieceRecognizer(List.of(piece))).starts());
    }

    @Test
    void chunksWhereTheSetNeverStartsAreSkipped() {
        // Villages (spacing 34, separation 8) never start in the last 8 chunks of a region.
        List<StartPiece.Block> blocks = blueprint(4, true);
        StartPiece piece = piece(blocks, BlockPos.ZERO);
        assertTrue(piece.couldStartIn(25, 0));
        assertTrue(!piece.couldStartIn(26, 0) && !piece.couldStartIn(0, 33) && !piece.couldStartIn(-1, 0));
        TestWorld world = new TestWorld();
        world.load(25, 29, -2, 2);
        world.place(piece, blocks, 27, 0, 64, PieceRotation.NONE);
        assertEquals(List.of(), world.recognize(new StartPieceRecognizer(List.of(piece))).starts());
    }

    @Test
    void waitsUntilEveryChunkUnderThePieceIsLoaded() {
        List<StartPiece.Block> blocks = blueprint(5, true);
        StartPiece piece = piece(blocks, BlockPos.ZERO);
        StartPieceRecognizer recognizer = new StartPieceRecognizer(List.of(piece));
        TestWorld world = new TestWorld();
        // Turned 180 degrees the piece lies north-west of the corner of chunk (2, 2), reaching into chunks (1..2, 1..2).
        world.load(2, 2, 2, 2);
        world.place(piece, blocks, 2, 2, 64, PieceRotation.CLOCKWISE_180);
        BlockPos bell = new BlockPos(32 - 4, 64 + 6, 32 - 3);
        List<StartPieceRecognizer.Candidate> candidates = recognizer.candidates("minecraft:bell", bell.x(), bell.y(), bell.z());
        assertEquals(1, candidates.size());
        assertEquals(StartPieceRecognizer.Verdict.NOT_LOADED, recognizer.check(candidates.get(0), world).verdict());
        world.load(1, 2, 1, 2);
        assertEquals(StartPieceRecognizer.Verdict.MATCH, recognizer.check(candidates.get(0), world).verdict());
    }

    @Test
    void toleratesSomeChangesButNotTooMany() {
        List<StartPiece.Block> blocks = blueprint(6, true);
        StartPiece piece = piece(blocks, BlockPos.ZERO);
        StartPieceRecognizer recognizer = new StartPieceRecognizer(List.of(piece));
        for (double removed : new double[] {0.0, 0.1, 0.15, 0.45, 0.6}) {
            TestWorld world = new TestWorld();
            world.load(-2, 2, -2, 2);
            world.place(piece, blocks, 0, 0, 64, PieceRotation.COUNTERCLOCKWISE_90);
            Random random = new Random(7);
            world.blocks.replaceAll((pos, id) -> !id.equals("minecraft:bell") && random.nextDouble() < removed ? "minecraft:stone" : id);
            boolean found = !world.recognize(recognizer).starts().isEmpty();
            assertEquals(removed < 0.3, found, removed + " of the blocks changed");
        }
    }

    @Test
    void grassAndDirtCountAsTheSameButOtherSoilsDoNot() {
        List<StartPiece.Block> blocks = blueprint(8, true);
        StartPiece piece = piece(blocks, BlockPos.ZERO);
        StartPieceRecognizer recognizer = new StartPieceRecognizer(List.of(piece));
        TestWorld world = new TestWorld();
        world.load(-2, 2, -2, 2);
        world.place(piece, blocks, 0, 0, 64, PieceRotation.NONE);
        world.blocks.replaceAll((pos, id) -> id.equals("minecraft:grass_block") ? "minecraft:dirt" : id);
        assertEquals(1, world.recognize(recognizer).starts().size());
        world.blocks.replaceAll((pos, id) -> id.equals("minecraft:dirt") ? "minecraft:podzol" : id);
        assertEquals(0, world.recognize(recognizer).starts().size());
    }

    @Test
    void groundAloneIsNotEnough() {
        // 80% of the blueprint is floor; all of it is there, but the built part is gone except the bell.
        List<StartPiece.Block> blocks = new ArrayList<>();
        for (int x = 0; x < 10; x++) {
            for (int z = 0; z < 8; z++) {
                blocks.add(new StartPiece.Block(x, 0, z, "minecraft:grass_block"));
            }
        }
        for (int i = 0; i < 19; i++) {
            blocks.add(new StartPiece.Block(i % 10, 1 + i / 10, 2, "minecraft:oak_planks"));
        }
        blocks.add(new StartPiece.Block(3, 3, 3, "minecraft:bell"));
        StartPiece piece = piece(blocks, BlockPos.ZERO);
        TestWorld world = new TestWorld();
        world.load(-2, 2, -2, 2);
        world.place(piece, blocks, 0, 0, 64, PieceRotation.NONE);
        world.blocks.replaceAll((pos, id) -> id.equals("minecraft:oak_planks") ? "minecraft:air" : id);
        assertTrue((double) (blocks.size() - 19) / blocks.size() >= StartPieceRecognizer.MIN_SCORE);
        assertEquals(List.of(), world.recognize(new StartPieceRecognizer(List.of(piece))).starts());
    }

    @Test
    void anchorsAreTheBellOrTheRarestBlocksThatDontGrowInNature() {
        assertEquals(Set.of("minecraft:bell"), piece(blueprint(9, true), BlockPos.ZERO).anchorBlocks());
        List<StartPiece.Block> blocks = new ArrayList<>(blueprint(9, false));
        blocks.add(new StartPiece.Block(0, 7, 0, "minecraft:red_terracotta"));
        blocks.add(new StartPiece.Block(1, 7, 0, "minecraft:oak_log"));
        blocks.add(new StartPiece.Block(2, 7, 0, "minecraft:lantern"));
        blocks.add(new StartPiece.Block(3, 7, 0, "minecraft:lantern"));
        blocks.add(new StartPiece.Block(4, 7, 0, "minecraft:campfire"));
        StartPiece piece = piece(blocks, BlockPos.ZERO);
        assertEquals(3, piece.anchorBlocks().size());
        assertTrue(piece.anchorBlocks().containsAll(Set.of("minecraft:campfire", "minecraft:lantern")), piece.anchorBlocks().toString());
        assertTrue(piece.anchorBlocks().stream().noneMatch(id -> id.endsWith("terracotta") || id.endsWith("_log")));
    }

    @Test
    void twoStartsOfASetInDifferentRegionsAreBothKept() {
        List<StartPiece.Block> blocks = blueprint(10, true);
        StartPiece piece = piece(blocks, BlockPos.ZERO);
        TestWorld world = new TestWorld();
        world.load(18, 40, -3, 3);
        world.place(piece, blocks, 20, 0, 64, PieceRotation.NONE);
        world.place(piece, blocks, 36, 1, 64, PieceRotation.CLOCKWISE_180);
        RecognizedStarts found = world.recognize(new StartPieceRecognizer(List.of(piece)));
        assertEquals(Set.of(new RecognizedStarts.Start("villages", 20, 0), new RecognizedStarts.Start("villages", 36, 1)), Set.copyOf(found.starts()));
        assertEquals(List.of(), found.conflicting());
    }

    @Test
    void twoStartsInOneRegionContradictEachOther() {
        List<StartPiece.Block> blocks = blueprint(11, true);
        StartPiece piece = piece(blocks, BlockPos.ZERO);
        TestWorld world = new TestWorld();
        world.load(-1, 8, -1, 8);
        world.place(piece, blocks, 1, 1, 64, PieceRotation.NONE);
        world.place(piece, blocks, 5, 6, 64, PieceRotation.NONE);
        RecognizedStarts found = world.recognize(new StartPieceRecognizer(List.of(piece)));
        assertEquals(List.of(), found.starts());
        assertEquals(2, found.conflicting().size());
    }
}
