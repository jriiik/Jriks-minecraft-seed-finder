package io.github.jriiik.seedfinder.mod;

import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/** The calls of the in-game test that differ between Minecraft versions (Fabric API's test API), as for 26.3. */
final class GameTestVersion {

    private GameTestVersion() {
    }

    static void waitForChunksRender(TestSingleplayerContext world) {
        world.getConnection().waitForChunksRender();
    }
}
