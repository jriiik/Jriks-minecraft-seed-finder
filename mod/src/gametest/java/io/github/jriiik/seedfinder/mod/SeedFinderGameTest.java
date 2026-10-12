package io.github.jriiik.seedfinder.mod;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.jriiik.seedfinder.Seeds;
import io.github.jriiik.seedfinder.bedrock.BedrockEvidence;
import io.github.jriiik.seedfinder.finder.ObservationReader;
import io.github.jriiik.seedfinder.finder.WorldSeedSearch;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Runs inside a real Minecraft client (on a virtual display) and uses the mod the way a player would. Prints
 * "SEEDFINDER-GAMETEST" lines and fails on the first check that doesn't hold.
 */
public class SeedFinderGameTest implements FabricClientGameTest {

    static final long SEED = 12345L;
    /** Structure types to /locate; tags pick any variant, the nearest one wins. */
    static final List<String> LOCATE = List.of(
        "#minecraft:village", "minecraft:pillager_outpost", "#minecraft:shipwreck", "#minecraft:ruined_portal",
        "minecraft:trial_chambers", "#minecraft:ocean_ruin", "minecraft:desert_pyramid", "minecraft:swamp_hut", "minecraft:igloo");

    static void log(String text) {
        System.out.println("SEEDFINDER-GAMETEST " + text);
    }

    static void check(boolean ok, String what) {
        log((ok ? "PASS " : "FAIL ") + what);
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        context.runOnClient(client -> {
            SeedFinderClient.openKey.setKey(InputConstants.getKey("key.keyboard.j"));
            KeyMapping.resetMapping();
        });

        // 1) The default test world is superflat: switching collecting on notes no bedrock there.
        try (TestSingleplayerContext flat = context.worldBuilder().create()) {
            flat.getConnection().waitForChunksRender();
            WorldSession session = context.computeOnClient(SeedFinderClient::session);
            check(session != null && !session.collecting(), "a new world starts with collecting switched off");
            context.runOnClient(client -> SeedFinderClient.session(client).setCollecting(true));
            context.waitTicks(60);
            check(context.computeOnClient(client -> SeedFinderClient.session(client).bedrock().isEmpty()), "no bedrock is noted in a superflat world");
        }

        // 2) A normal world with a known seed.
        try (TestSingleplayerContext world = context.worldBuilder().adjustSettings(settings -> {
            Holder<WorldPreset> normal = settings.getSettings().worldgenLoadContext().lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL);
            settings.setWorldType(new WorldCreationUiState.WorldTypeEntry(normal));
            settings.setSeed(Long.toString(SEED));
            settings.setGenerateStructures(true);
            settings.setAllowCommands(true);
            settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
        }).create()) {
            world.getConnection().waitForChunksRender();
            check(!context.computeOnClient(client -> SeedFinderClient.session(client).collecting()), "collecting is off until the player switches it on");
            context.waitTicks(60);
            check(context.computeOnClient(client -> SeedFinderClient.session(client).bedrock().isEmpty()
                && SeedFinderClient.session(client).hashedSeed().isEmpty()), "nothing is noted while collecting is off");

            // The player opens the screen with the key and switches collecting on.
            context.getInput().pressKey(SeedFinderClient.openKey);
            context.waitForScreen(SeedFinderScreen.class);
            context.clickScreenButton("gui.jriksseedfinder.collect.off");
            check(context.computeOnClient(client -> SeedFinderClient.session(client).collecting()), "the screen's button switches collecting on");
            context.takeScreenshot("seedfinder-screen");
            context.clickScreenButton("gui.jriksseedfinder.close");
            context.waitForScreen(null);

            // The player types /locate commands (this test sends them; the mod only reads the answers).
            for (String structure : LOCATE) {
                context.runOnClient(client -> client.getConnection().sendCommand("locate structure " + structure));
                context.waitTicks(10);
            }
            context.waitFor(client -> SeedFinderClient.session(client).structures().size() >= 6, 1200);
            log("structures noted: " + context.computeOnClient(client -> SeedFinderClient.session(client).structures()));

            // The mod searches by itself and finds the seed (with the hashed seed the server sent).
            context.waitFor(client -> {
                WorldSession session = SeedFinderClient.session(client);
                return session.resultIsCurrent() && session.result() != null && session.result().stage() == WorldSeedSearch.Stage.WORLD_SEED;
            }, 20 * 180);
            WorldSeedSearch.Result result = context.computeOnClient(client -> SeedFinderClient.session(client).result());
            log("result: " + result.stage() + " " + result.worldSeed() + " " + result.proof() + ", structure seeds " + result.structureSeeds());
            check(result.worldSeed().getAsLong() == SEED, "the mod found the world seed " + SEED);
            check(!result.bedrockDisagrees(), "the bedrock the mod read fits the seed");
            context.takeScreenshot("seedfinder-status");

            // Bedrock was read from real chunks and matches the engine's prediction for the seed exactly.
            WorldSession session = context.computeOnClient(SeedFinderClient::session);
            check(session.bedrockChunks("minecraft:overworld") == WorldSession.MAX_BEDROCK_CHUNKS,
                "bedrock of " + WorldSession.MAX_BEDROCK_CHUNKS + " overworld chunks was noted");
            List<String> notes = context.computeOnClient(client -> SeedFinderClient.session(client).notesLines());
            ObservationReader.Observations read = ObservationReader.read(SeedFinderClient.data(), notes);
            BedrockEvidence bedrock = new BedrockEvidence(read.bedrock());
            check(bedrock.problems().isEmpty() && bedrock.fullSeedObservations().size() > 1000,
                "the notes hold " + bedrock.fullSeedObservations().size() + " informative bedrock blocks");
            check(bedrock.mismatchesOf(SEED).isEmpty(), "every bedrock block read from the game fits seed " + SEED);
            check(read.hashedSeed().getAsLong() == Seeds.hashedSeed(SEED), "the notes carry the hashed seed");
            WorldSeedSearch.Result fromBedrock = WorldSeedSearch.run(SeedFinderClient.data(), read.structures(), read.bedrock(),
                java.util.OptionalLong.empty(), 2, (done, total) -> { });
            check(fromBedrock.proof().orElse(null) == WorldSeedSearch.Proof.BEDROCK && fromBedrock.worldSeed().getAsLong() == SEED,
                "the bedrock alone also proves the seed");

            // Copy seed, and the saved notes the command-line app can read.
            context.getInput().pressKey(SeedFinderClient.openKey);
            context.waitForScreen(SeedFinderScreen.class);
            context.clickScreenButton("gui.jriksseedfinder.copy_seed");
            check(context.computeOnClient(client -> client.keyboardHandler.getClipboard()).equals(Long.toString(SEED)), "Copy seed copies " + SEED);
            context.clickScreenButton("gui.jriksseedfinder.close");
            context.waitForScreen(null);
            context.waitTicks(120);
            Path folder = SeedFinderClient.folder();
            String base = WorldSession.fileName(session.world());
            check(Files.isRegularFile(folder.resolve(base + ".json")) && Files.isRegularFile(folder.resolve(base + ".txt")),
                "the notes were saved in " + folder);
        }
        log("ALL CHECKS PASSED");
    }
}
