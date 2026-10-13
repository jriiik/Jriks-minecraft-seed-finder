package io.github.jriiik.seedfinder.mod;

import io.github.jriiik.seedfinder.bedrock.BedrockDimension;
import io.github.jriiik.seedfinder.bedrock.BedrockRule;
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
     * Blocks that only occur in the bedrock layers where the game's bedrock roll said "no": natural stone, ores and
     * lava. Air and built blocks (mineshafts, strongholds, players) count as unknown instead, because a structure
     * could have replaced bedrock there.
     */
    private static final Set<Block> NATURAL = Set.of(
        Blocks.DEEPSLATE, Blocks.TUFF, Blocks.LAVA, Blocks.CALCITE, Blocks.SMOOTH_BASALT, Blocks.AMETHYST_BLOCK,
        Blocks.BUDDING_AMETHYST, Blocks.SCULK, Blocks.RAW_IRON_BLOCK, Blocks.RAW_COPPER_BLOCK,
        Blocks.DEEPSLATE_COAL_ORE, Blocks.DEEPSLATE_IRON_ORE, Blocks.DEEPSLATE_COPPER_ORE, Blocks.DEEPSLATE_GOLD_ORE,
        Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.DEEPSLATE_EMERALD_ORE, Blocks.DEEPSLATE_LAPIS_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
        Blocks.NETHERRACK, Blocks.BASALT, Blocks.BLACKSTONE, Blocks.SOUL_SAND, Blocks.SOUL_SOIL, Blocks.MAGMA_BLOCK,
        Blocks.NETHER_GOLD_ORE, Blocks.NETHER_QUARTZ_ORE, Blocks.ANCIENT_DEBRIS, Blocks.GRAVEL);

    private BedrockScanner() {
    }

    /**
     * The random layers of this chunk, or empty when the chunk looks unusual: a layer that is always bedrock isn't
     * (an upgraded old chunk, a changed world), or nothing useful was readable.
     */
    static Optional<ScannedChunk> scan(BedrockDimension dimension, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        BlockPos.MutableBlockPos block = new BlockPos.MutableBlockPos();
        for (BedrockRule rule : dimension.rules()) {
            int alwaysBedrock = rule.inverted() ? rule.falseAtAndAbove() : rule.trueAtAndBelow();
            for (int dz = 0; dz < 16; dz++) {
                for (int dx = 0; dx < 16; dx++) {
                    if (!chunk.getBlockState(block.set(pos.getMinBlockX() + dx, alwaysBedrock, pos.getMinBlockZ() + dz)).is(Blocks.BEDROCK)) {
                        return Optional.empty();
                    }
                }
            }
        }
        Map<Integer, List<String>> layers = new LinkedHashMap<>();
        for (int y = dimension.minY(); y < dimension.minY() + dimension.height(); y++) {
            if (!dimension.isRandomAt(y)) {
                continue;
            }
            List<String> rows = new ArrayList<>(16);
            for (int dz = 0; dz < 16; dz++) {
                StringBuilder row = new StringBuilder(16);
                for (int dx = 0; dx < 16; dx++) {
                    BlockState state = chunk.getBlockState(block.set(pos.getMinBlockX() + dx, y, pos.getMinBlockZ() + dz));
                    row.append(state.is(Blocks.BEDROCK) ? 'B' : NATURAL.contains(state.getBlock()) ? '.' : '?');
                }
                rows.add(row.toString());
            }
            layers.put(y, rows);
        }
        ScannedChunk scanned = new ScannedChunk(dimension.id(), pos.x(), pos.z(), layers);
        return scanned.knownBlocks() > 0 ? Optional.of(scanned) : Optional.empty();
    }
}
