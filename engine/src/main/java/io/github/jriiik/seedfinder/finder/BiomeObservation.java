package io.github.jriiik.seedfinder.finder;

/**
 * A biome seen in the overworld.
 *
 * @param biome     the biome id, like {@code minecraft:forest} ({@code minecraft:} may be left out)
 * @param x, y, z   a block position
 * @param exactCell true when this is the biome the game stored for the 4 x 4 x 4 block cell containing the block
 *                  (what the mod reads); false when it is the biome the F3 screen shows at the block, which the game
 *                  picks among the 8 cells around it with a jitter that depends on the hashed seed
 */
public record BiomeObservation(String biome, int x, int y, int z, boolean exactCell) {

    public BiomeObservation {
        biome = biome.contains(":") ? biome : "minecraft:" + biome;
    }
}
