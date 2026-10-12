package io.github.jriiik.seedfinder.mod;

import net.minecraft.client.multiplayer.ClientLevel;

/**
 * The hashed seed the server sends to every player at login (the first 8 bytes of SHA-256 of the world seed, used
 * for blending biomes). Reading it needs the class tweaker; the mod never sends it anywhere.
 */
final class HashedSeed {

    private HashedSeed() {
    }

    static long read(ClientLevel level) {
        return level.getBiomeManager().biomeZoomSeed;
    }
}
