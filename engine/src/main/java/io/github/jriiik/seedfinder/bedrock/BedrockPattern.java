package io.github.jriiik.seedfinder.bedrock;

import io.github.jriiik.seedfinder.rng.PositionalRandomFactory;
import io.github.jriiik.seedfinder.rng.RandomState;
import java.util.List;

/**
 * The random bedrock of one dimension of one world: which blocks the game's bedrock rules turn into bedrock.
 *
 * <p>In the game a rule only applies to blocks that are solid at that point of generation; the bedrock layers
 * practically always are, and carvers (caves) cannot remove bedrock afterwards.
 */
public final class BedrockPattern {

    private final BedrockDimension dimension;
    private final List<BedrockRule> rules;
    private final PositionalRandomFactory[] factories;

    public BedrockPattern(BedrockDimension dimension, long worldSeed) {
        this.dimension = dimension;
        this.rules = dimension.rules();
        RandomState state = new RandomState(worldSeed, dimension.legacyRandomSource());
        this.factories = rules.stream().map(rule -> state.factory(rule.randomName())).toArray(PositionalRandomFactory[]::new);
    }

    public BedrockDimension dimension() {
        return dimension;
    }

    public boolean isBedrock(int x, int y, int z) {
        for (int i = 0; i < factories.length; i++) {
            if (rules.get(i).isBedrock(factories[i], x, y, z)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The height of the highest bedrock block of the floor in this column, which is what a player sees after digging
     * down to bedrock.
     */
    public int floorTop(int x, int z) {
        BedrockRule floor = dimension.floor()
            .orElseThrow(() -> new IllegalStateException(dimension.id() + " has no random bedrock floor"));
        for (int y = floor.falseAtAndAbove() - 1; y > floor.trueAtAndBelow(); y--) {
            if (isBedrock(x, y, z)) {
                return y;
            }
        }
        return floor.trueAtAndBelow();
    }
}
