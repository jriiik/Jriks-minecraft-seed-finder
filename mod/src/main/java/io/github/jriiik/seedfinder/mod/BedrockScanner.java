package io.github.jriiik.seedfinder.mod;

import io.github.jriiik.seedfinder.bedrock.BedrockDimension;
import io.github.jriiik.seedfinder.bedrock.BedrockRule;
import io.github.jriiik.seedfinder.finder.NetherBedrockSearch;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/** Reads the random bedrock layers of a chunk the client has just received. */
final class BedrockScanner {

    /**
     * Blocks that only occur in the bedrock layers where the game's bedrock roll said "no": natural stone, ores, and
     * lava in the nether. Air and built blocks (mineshafts, strongholds, players) count as unknown instead, because a
     * structure could have replaced bedrock there.
     *
     * <p>Also unknown, in every version: blocks the game's terrain step itself can put in a bedrock layer, before the
     * bedrock is rolled. The roll only runs on the stone that step leaves (before 26.x only on its default block, in
     * 26.x not on fluids), so there such a block says nothing about the roll: the blocks of iron ore veins (tuff, raw
     * iron blocks and deepslate iron ore, down to y -60) and, in the overworld, lava (a cave that reaches the bedrock
     * layers fills with lava below y -54). In the nether the terrain step only puts netherrack in the bedrock layers
     * (its terrain is always solid there), so lava there was dug out later, where the roll said "no".
     */
    private static final Set<Block> NATURAL = Set.of(
        Blocks.DEEPSLATE, Blocks.CALCITE, Blocks.SMOOTH_BASALT, Blocks.AMETHYST_BLOCK,
        Blocks.BUDDING_AMETHYST, Blocks.SCULK, Blocks.RAW_COPPER_BLOCK,
        Blocks.DEEPSLATE_COAL_ORE, Blocks.DEEPSLATE_COPPER_ORE, Blocks.DEEPSLATE_GOLD_ORE,
        Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.DEEPSLATE_EMERALD_ORE, Blocks.DEEPSLATE_LAPIS_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
        Blocks.NETHERRACK, Blocks.BASALT, Blocks.BLACKSTONE, Blocks.SOUL_SAND, Blocks.SOUL_SOIL, Blocks.MAGMA_BLOCK,
        Blocks.NETHER_GOLD_ORE, Blocks.NETHER_QUARTZ_ORE, Blocks.ANCIENT_DEBRIS, Blocks.GRAVEL);

    private BedrockScanner() {
    }

    /** A block of a random bedrock layer as noted: B bedrock, . not bedrock (the roll said no), ? unknown. */
    static char read(String dimension, Block block) {
        if (block == Blocks.BEDROCK) {
            return 'B';
        }
        return NATURAL.contains(block) || block == Blocks.LAVA && dimension.equals(NetherBedrockSearch.NETHER) ? '.' : '?';
    }

    /**
     * The random layers of this chunk, or empty when nothing useful was readable. A floor or roof whose layer that is
     * always bedrock isn't (an upgraded old chunk, a changed world, or a hole players made in the nether roof) is left
     * out; the other one is still read.
     */
    static Optional<ScannedChunk> scan(BedrockDimension dimension, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        BlockPos.MutableBlockPos block = new BlockPos.MutableBlockPos();
        List<BedrockRule> intact = new ArrayList<>();
        for (BedrockRule rule : dimension.rules()) {
            int alwaysBedrock = rule.inverted() ? rule.falseAtAndAbove() : rule.trueAtAndBelow();
            boolean whole = true;
            for (int dz = 0; dz < 16 && whole; dz++) {
                for (int dx = 0; dx < 16 && whole; dx++) {
                    whole = chunk.getBlockState(block.set(pos.getMinBlockX() + dx, alwaysBedrock, pos.getMinBlockZ() + dz)).is(Blocks.BEDROCK);
                }
            }
            if (whole) {
                intact.add(rule);
            }
        }
        Map<Integer, List<String>> layers = new LinkedHashMap<>();
        for (int y = dimension.minY(); y < dimension.minY() + dimension.height(); y++) {
            int height = y;
            if (intact.stream().noneMatch(rule -> rule.isRandomAt(height))) {
                continue;
            }
            List<String> rows = new ArrayList<>(16);
            for (int dz = 0; dz < 16; dz++) {
                StringBuilder row = new StringBuilder(16);
                for (int dx = 0; dx < 16; dx++) {
                    BlockState state = chunk.getBlockState(block.set(pos.getMinBlockX() + dx, y, pos.getMinBlockZ() + dz));
                    row.append(read(dimension.id(), state.getBlock()));
                }
                rows.add(row.toString());
            }
            layers.put(y, rows);
        }
        ScannedChunk scanned = new ScannedChunk(dimension.id(), Chunks.x(pos), Chunks.z(pos), layers);
        return scanned.knownBlocks() > 0 ? Optional.of(scanned) : Optional.empty();
    }
}
