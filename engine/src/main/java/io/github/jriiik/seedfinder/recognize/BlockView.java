package io.github.jriiik.seedfinder.recognize;

/** What recognition can see of a world: which chunks are loaded, and their blocks and biomes. */
public interface BlockView {

    boolean isLoaded(int chunkX, int chunkZ);

    /**
     * The block id (e.g. {@code minecraft:bell}) at a position in a loaded chunk; null when the chunk is not loaded
     * or the height is outside the world.
     */
    String blockAt(int x, int y, int z);

    /**
     * The biome id (e.g. {@code minecraft:desert}) of the 4 x 4 x 4 cell holding this position, as the client
     * received it; null when not known.
     */
    default String biomeAt(int x, int y, int z) {
        return null;
    }
}
