package io.github.jriiik.seedfinder.world;

/** A chunk position: chunks are 16x16 block columns, so chunk (x, z) covers blocks 16x..16x+15. */
public record ChunkPos(int x, int z) {

    public static ChunkPos ofBlock(int blockX, int blockZ) {
        return new ChunkPos(blockX >> 4, blockZ >> 4);
    }

    public int minBlockX() {
        return x << 4;
    }

    public int minBlockZ() {
        return z << 4;
    }

    @Override
    public String toString() {
        return "chunk(" + x + ", " + z + ")";
    }
}
