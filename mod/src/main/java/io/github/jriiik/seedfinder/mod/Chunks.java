package io.github.jriiik.seedfinder.mod;

import net.minecraft.world.level.ChunkPos;

/**
 * Chunk coordinates in a way every Minecraft version supports: the game's own {@link ChunkPos} reads its x and z
 * differently from version to version.
 */
final class Chunks {

    private Chunks() {
    }

    static int x(ChunkPos pos) {
        return pos.getMinBlockX() >> 4;
    }

    static int z(ChunkPos pos) {
        return pos.getMinBlockZ() >> 4;
    }

    /** A chunk's x and z in one number (packed as the game packs them). */
    static long key(int x, int z) {
        return x & 0xFFFFFFFFL | (z & 0xFFFFFFFFL) << 32;
    }

    static int keyX(long key) {
        return (int) key;
    }

    static int keyZ(long key) {
        return (int) (key >>> 32);
    }
}
