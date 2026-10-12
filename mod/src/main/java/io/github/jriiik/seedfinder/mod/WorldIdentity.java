package io.github.jriiik.seedfinder.mod;

import java.nio.file.Path;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;

/** Which world the player is in, worked out locally (nothing is sent anywhere). */
final class WorldIdentity {

    private WorldIdentity() {
    }

    /** "singleplayer:&lt;save folder&gt;", "server:&lt;address&gt;", "lan:...", "realm:&lt;name&gt;", or null outside a world. */
    static String current(Minecraft minecraft) {
        IntegratedServer local = minecraft.getSingleplayerServer();
        if (minecraft.isLocalServer() && local != null) {
            Path folder = local.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            return "singleplayer:" + folder.getFileName();
        }
        ServerData server = minecraft.getCurrentServer();
        if (server != null) {
            if (server.isRealm()) {
                return "realm:" + server.name;
            }
            return (server.isLan() ? "lan:" : "server:") + server.ip.trim().toLowerCase(Locale.ROOT);
        }
        return minecraft.level == null ? null : "unknown";
    }

    /**
     * The key the mod keeps notes under: where the world is, plus the hashed seed the server sent. Two worlds behind
     * one address (a lobby and a survival world on a network, a reset world, Realm slots) get separate notes, so
     * their structures and bedrock never mix. Null outside a world.
     */
    static String key(Minecraft minecraft) {
        String identity = current(minecraft);
        return identity == null || minecraft.level == null ? null : key(identity, HashedSeed.read(minecraft.level));
    }

    static String key(String identity, long hashedSeed) {
        return identity + " [" + String.format("%016x", hashedSeed) + "]";
    }

    /** Superflat worlds have a flat bedrock floor; the server tells every client whether the world is superflat. */
    static boolean isSuperflat(ClientLevel level) {
        return level.getLevelData().voidDarknessOnsetRange() == 1.0F;
    }
}
