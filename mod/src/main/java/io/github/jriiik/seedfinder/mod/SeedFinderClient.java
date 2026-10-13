package io.github.jriiik.seedfinder.mod;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.jriiik.seedfinder.bedrock.BedrockDimension;
import io.github.jriiik.seedfinder.version.MinecraftVersion;
import io.github.jriiik.seedfinder.version.VersionData;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The mod's entry point (client only). It opens with a key the player chooses (unbound at first), never adds chat
 * commands and never sends anything to the server: it only looks at what the game has already received, and only
 * in worlds where the player switched collecting on.
 */
public final class SeedFinderClient implements ClientModInitializer {

    static final String MOD_ID = "jriksseedfinder";
    static final Logger LOG = LoggerFactory.getLogger(MOD_ID);
    /** Save at most every 5 seconds while things change. */
    private static final int SAVE_EVERY_TICKS = 100;
    /** While bedrock is still wanted, look at the already loaded chunks every 2 seconds. */
    private static final int LOADED_CHUNK_SCAN_TICKS = 40;
    private static final int LOADED_CHUNK_RADIUS = 8;

    private static VersionData data;
    static KeyMapping openKey;
    private static SearchRunner runner;
    private static final TypedSeedRunner TYPED_SEEDS = new TypedSeedRunner();
    /** The current world's notes; only touched on the game's main thread. */
    private static WorldSession session;
    private static StructureRecognition recognition;
    private static boolean wasCollecting;
    private static int ticks;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        openKey = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.jriksseedfinder.open", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), category));
        runner = new SearchRunner(data());

        ClientTickEvents.END_CLIENT_TICK.register(SeedFinderClient::onTick);
        ClientChunkEvents.CHUNK_LOAD.register(SeedFinderClient::onChunkLoad);
        ClientReceiveMessageEvents.GAME.register(SeedFinderClient::onGameMessage);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "status"), (graphics, delta) -> {
            if (session != null && session.collecting() && session.showStatus()) {
                Minecraft minecraft = Minecraft.getInstance();
                graphics.text(minecraft.font, Component.literal(StatusText.statusLine(session, runner)), 4, 4, 0xFFFFFFFF);
            }
        });
    }

    static synchronized VersionData data() {
        if (data == null) {
            data = MinecraftVersion.V26_3.data();
        }
        return data;
    }

    static Path folder() {
        return FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
    }

    /** The session for the world the player is in, opening or switching it when needed (main thread). */
    static WorldSession session(Minecraft minecraft) {
        String world = WorldIdentity.key(minecraft);
        if (!Objects.equals(world, session == null ? null : session.world())) {
            save();
            session = world == null ? null : WorldSession.load(folder(), world, WorldIdentity.current(minecraft), HashedSeed.read(minecraft.level));
            recognition = world == null ? null : new StructureRecognition(data());
            wasCollecting = false;
        }
        return session;
    }

    /** Forgets all notes of the current world and starts recognizing structures afresh (main thread). */
    static void startOver() {
        if (session != null) {
            session.clearAll();
            recognition = new StructureRecognition(data());
            wasCollecting = false;
        }
    }

    /** Automatic structure recognition in the current world (main thread). */
    static StructureRecognition recognition() {
        return recognition;
    }

    static SearchRunner runner() {
        return runner;
    }

    static TypedSeedRunner typedSeeds() {
        return TYPED_SEEDS;
    }

    /** Saves notes of a world the player has left (a background search finished after the switch). */
    static void saveIfLeft(WorldSession notes) {
        if (notes != session && notes.needsSaving()) {
            try {
                notes.save(folder());
            } catch (IOException e) {
                LOG.warn("Could not save the seed finder notes for {}", notes.world(), e);
            }
        }
    }

    private static void onTick(Minecraft minecraft) {
        ticks++;
        WorldSession current = session(minecraft);
        TYPED_SEEDS.tick(current);
        if (current != null) {
            if (current.collecting() && minecraft.level != null) {
                current.setHashedSeed(HashedSeed.read(minecraft.level));
                if (ticks % LOADED_CHUNK_SCAN_TICKS == 0) {
                    scanLoadedChunks(minecraft, current);
                }
                if (!wasCollecting) {
                    recognition.scanAllLoaded();
                }
                recognition.tick(minecraft, current);
            }
            wasCollecting = current.collecting();
            runner.tick(current);
            if (ticks % SAVE_EVERY_TICKS == 0) {
                save();
            }
        }
        while (openKey.consumeClick()) {
            if (current != null) {
                minecraft.gui.setScreen(new SeedFinderScreen(current, runner));
            }
        }
    }

    private static void save() {
        if (session != null && session.needsSaving()) {
            try {
                session.save(folder());
            } catch (IOException e) {
                LOG.warn("Could not save the seed finder notes for {}", session.world(), e);
            }
        }
    }

    private static void onChunkLoad(ClientLevel level, LevelChunk chunk) {
        WorldSession current = session(Minecraft.getInstance());
        if (current != null && current.collecting()) {
            bedrockDimension(level, current).ifPresent(dimension -> BedrockScanner.scan(dimension, chunk).ifPresent(current::offerBedrock));
            recognition.chunkLoaded(level, chunk);
        }
    }

    /** The dimension's bedrock rules, when its bedrock is still wanted. */
    private static Optional<BedrockDimension> bedrockDimension(ClientLevel level, WorldSession current) {
        if (WorldIdentity.isSuperflat(level)) {
            return Optional.empty();
        }
        return data().bedrockDimension(level.dimension().identifier().toString())
            .filter(dimension -> !dimension.rules().isEmpty() && current.bedrockChunks(dimension.id()) < WorldSession.MAX_BEDROCK_CHUNKS);
    }

    /** Chunks that were already loaded when collecting was switched on, nearest first. */
    private static void scanLoadedChunks(Minecraft minecraft, WorldSession current) {
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            return;
        }
        Optional<BedrockDimension> dimension = bedrockDimension(level, current);
        if (dimension.isEmpty()) {
            return;
        }
        int centerX = minecraft.player.blockPosition().getX() >> 4;
        int centerZ = minecraft.player.blockPosition().getZ() >> 4;
        for (int radius = 0; radius <= LOADED_CHUNK_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    if (current.bedrockChunks(dimension.get().id()) >= WorldSession.MAX_BEDROCK_CHUNKS) {
                        return;
                    }
                    LevelChunk chunk = level.getChunkSource().getChunk(centerX + dx, centerZ + dz, ChunkStatus.FULL, false);
                    if (chunk != null) {
                        BedrockScanner.scan(dimension.get(), chunk).ifPresent(current::offerBedrock);
                    }
                }
            }
        }
    }

    /**
     * Picks up the answer to a /locate command the player typed, and structures placed with /place, which must never
     * be taken for real starts (the mod itself never sends commands).
     */
    private static void onGameMessage(Component message, boolean overlay) {
        if (overlay || session == null || !session.collecting()) {
            return;
        }
        LocateMessage.parse(message).ifPresent(found -> data().findStructureSet(found.structure()).ifPresent(set ->
            session.addStructure(new WorldSession.NotedStructure(set.shortId(), found.x(), found.z(), "from /locate"))));
        PlacedStructureMessage.parse(message).ifPresent(placed -> data().findStructureSet(placed.structure()).ifPresent(set ->
            session.distrust(set.shortId(), placed.x(), placed.z())));
    }
}
