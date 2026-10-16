package io.github.jriiik.seedfinder.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.jriiik.seedfinder.finder.NetherBedrockSearch;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Which blocks of the random bedrock layers tell what the game's bedrock roll said (with the game's own blocks). */
class BedrockScannerTest {

    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = NetherBedrockSearch.NETHER;

    @BeforeAll
    static void startMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /**
     * Blocks the terrain step can put in a bedrock layer, before the roll: the roll then never runs there, so they say
     * nothing (before 26.x it only runs on the step's default block, in 26.x not on fluids). In the overworld: the
     * blocks of iron ore veins (y -60 and up) and lava (caves below y -54 fill with lava); air and water anyway.
     */
    @Test
    void blocksTheTerrainStepCanPlaceAreUnknown() {
        for (Block block : List.of(Blocks.TUFF, Blocks.RAW_IRON_BLOCK, Blocks.DEEPSLATE_IRON_ORE, Blocks.LAVA, Blocks.WATER, Blocks.AIR,
            Blocks.CAVE_AIR, Blocks.STONE)) {
            assertEquals('?', BedrockScanner.read(OVERWORLD, block), "overworld " + block);
        }
        for (Block block : List.of(Blocks.AIR, Blocks.CAVE_AIR, Blocks.WATER)) {
            assertEquals('?', BedrockScanner.read(NETHER, block), "nether " + block);
        }
    }

    /** Blocks that only come where the roll said no: the rest of the deep stone, ores and nether blocks. */
    @Test
    void naturalBlocksAreNotBedrock() {
        for (Block block : List.of(Blocks.DEEPSLATE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.CALCITE, Blocks.GRAVEL)) {
            assertEquals('.', BedrockScanner.read(OVERWORLD, block), "overworld " + block);
        }
        // The nether's terrain is always solid in its bedrock layers: lava there was dug out after the roll.
        for (Block block : List.of(Blocks.NETHERRACK, Blocks.LAVA, Blocks.BASALT, Blocks.BLACKSTONE, Blocks.NETHER_QUARTZ_ORE)) {
            assertEquals('.', BedrockScanner.read(NETHER, block), "nether " + block);
        }
        assertEquals('B', BedrockScanner.read(OVERWORLD, Blocks.BEDROCK));
        assertEquals('B', BedrockScanner.read(NETHER, Blocks.BEDROCK));
    }

    /** Built blocks (mineshafts, strongholds, players) may stand where bedrock was rolled. */
    @Test
    void builtBlocksAreUnknown() {
        for (Block block : List.of(Blocks.OAK_PLANKS, Blocks.COBBLESTONE, Blocks.STONE_BRICKS, Blocks.RAIL)) {
            assertEquals('?', BedrockScanner.read(OVERWORLD, block), "overworld " + block);
            assertEquals('?', BedrockScanner.read(NETHER, block), "nether " + block);
        }
    }
}
