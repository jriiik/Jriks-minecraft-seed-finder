package io.github.jriiik.seedfinder.recognize;

/** What recognition can see of a world: which chunks are loaded, and their blocks. */
public interface BlockView {

    boolean isLoaded(int chunkX, int chunkZ);

    /** The block id (e.g. {@code minecraft:bell}) at a position in a loaded chunk. */
    String blockAt(int x, int y, int z);
}
