package io.github.jriiik.seedfinder.mod;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.datafixers.util.Pair;
import io.github.jriiik.seedfinder.Seeds;
import io.github.jriiik.seedfinder.bedrock.BedrockEvidence;
import io.github.jriiik.seedfinder.biome.OverworldBiomes;
import io.github.jriiik.seedfinder.finder.BiomeEvidence;
import io.github.jriiik.seedfinder.finder.BiomeObservation;
import io.github.jriiik.seedfinder.finder.NetherBedrockSearch;
import io.github.jriiik.seedfinder.finder.ObservationReader;
import io.github.jriiik.seedfinder.finder.SeedCheck;
import io.github.jriiik.seedfinder.finder.WorldSeedSearch;
import io.github.jriiik.seedfinder.recognize.RecognizedStarts;
import io.github.jriiik.seedfinder.recognize.RigidKind;
import io.github.jriiik.seedfinder.recognize.RigidRecognizer;
import io.github.jriiik.seedfinder.recognize.StartPieceRecognizer;
import io.github.jriiik.seedfinder.structure.RandomSpreadPlacement;
import io.github.jriiik.seedfinder.structure.StructureLocator;
import io.github.jriiik.seedfinder.structure.StructureSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.levelgen.structure.Structure;

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

    static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static void check(boolean ok, String what) {
        log((ok ? "PASS " : "FAIL ") + what);
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    /**
     * Automatic recognition: the player visits the nearest structure of each type the mod recognizes (found with
     * the game's own /locate search), and a ruined portal in the nether; the mod must recognize its start chunk from
     * the blocks alone. Every start it recognized on the way must be a real start in the game's own records, and the
     * seed must still come out right.
     */
    static void recognizesStructures(ClientGameTestContext context, TestSingleplayerContext world) {
        context.runOnClient(client -> client.options.renderDistance().set(8));
        world.getServer().runCommand("gamemode spectator @a");
        List<String> jigsawSets = StartPieceLibrary.setsOf(SeedFinderClient.data());
        List<String> inGame = world.getServer().computeOnServer(server -> StartPieceLibrary.SETS.stream()
            .filter(set -> server.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET).containsKey(Identifier.withDefaultNamespace(set)))
            .toList());
        check(jigsawSets.equals(inGame), "the version data has the recognized jigsaw sets this game has: " + jigsawSets);
        List<String> sets = new ArrayList<>(jigsawSets);
        for (RigidKind kind : RigidKind.values()) {
            sets.add(kind.structureSet());
        }
        ChunkPos last = null;
        for (String set : sets) {
            ChunkPos start = world.getServer().computeOnServer(server -> nearestStart(server.overworld(), set, BlockPos.ZERO));
            check(start != null, "the game has a " + set + " start to visit");
            visit(context, world, "minecraft:overworld", set, start);
            last = start;
        }
        // Both dimensions share the ruined portals' grid, so the nearest nether portal may start in the very chunk of
        // the overworld one just visited: look for one in another region, far away.
        ChunkPos netherPortal = world.getServer().computeOnServer(server -> nearestStart(server.getLevel(Level.NETHER), "ruined_portals",
            new BlockPos(16 * 40 * 6, 64, 16 * 40 * 6)));
        check(netherPortal != null && context.computeOnClient(client -> SeedFinderClient.recognition().starts().stream()
                .noneMatch(start -> start.structureSet().equals("ruined_portals") && start.chunkX() == Chunks.x(netherPortal) && start.chunkZ() == Chunks.z(netherPortal))),
            "the game has a ruined portal in the nether to visit, in a chunk not recognized yet");
        // Ruined portals in the nether are only noted once the player says the nether is new enough.
        check(!context.computeOnClient(client -> SeedFinderClient.session(client).netherPortals()), "nether portals are not noted by default");
        world.getServer().runCommand("execute in minecraft:the_nether run tp @a " + (netherPortal.getMinBlockX() + 8) + " 100 "
            + (netherPortal.getMinBlockZ() + 8));
        context.waitTicks(20 * 30);
        check(context.computeOnClient(client -> SeedFinderClient.recognition().starts().stream()
                .noneMatch(start -> start.chunkX() == Chunks.x(netherPortal) && start.chunkZ() == Chunks.z(netherPortal))),
            "with nether portals switched off, the portal in the nether is not taken for a start");
        context.runOnClient(client -> SeedFinderClient.session(client).setNetherPortals(true));
        visit(context, world, "minecraft:the_nether", "ruined_portals", netherPortal);
        world.getServer().runCommand("execute in minecraft:overworld run tp @a " + (last.getMinBlockX() + 8) + " 160 " + (last.getMinBlockZ() + 8));
        context.waitTicks(100);

        // The nether's bedrock was read from real chunks there: on its own it gives the structure seed (or it and its twin).
        check(context.computeOnClient(client -> SeedFinderClient.session(client).bedrockChunks(NetherBedrockSearch.NETHER)) > 0,
            "nether bedrock was read in the nether");
        ObservationReader.Observations netherNotes = ObservationReader.read(SeedFinderClient.data(),
            context.computeOnClient(client -> SeedFinderClient.session(client).notesLines()));
        NetherBedrockSearch.Finding nether = new NetherBedrockSearch(SeedFinderClient.data()).find(netherNotes.bedrock(), 2, () -> false,
            (search, pieces) -> { });
        check(nether.proven() && nether.structureSeeds().contains(Seeds.structureSeed(SEED)) && nether.unusableChunks().isEmpty(),
            "the nether bedrock read from the game alone proves the structure seed: " + nether.structureSeeds());

        List<RecognizedStarts.Start> recognized = context.computeOnClient(client -> SeedFinderClient.recognition().starts());
        log("recognized: " + recognized);
        for (RecognizedStarts.Start start : recognized) {
            // Ruined portals of both dimensions share one placement grid.
            List<String> real = world.getServer().computeOnServer(server -> {
                List<String> both = new ArrayList<>(realStarts(server.overworld(), start.chunkX(), start.chunkZ()));
                both.addAll(realStarts(server.getLevel(Level.NETHER), start.chunkX(), start.chunkZ()));
                return both;
            });
            List<String> inSet = SeedFinderClient.data().structureSet(start.structureSet()).orElseThrow().structures().stream()
                .map(StructureSet.Entry::structure).toList();
            check(real.stream().anyMatch(inSet::contains), start + " is a real start (the game has " + real + " there)");
            check(context.computeOnClient(client -> SeedFinderClient.session(client).structures().stream().anyMatch(s -> s.set().equals(start.structureSet())
                && Math.floorDiv(s.x(), 16) == start.chunkX() && Math.floorDiv(s.z(), 16) == start.chunkZ())), start + " is in the notes");
        }

        check(context.computeOnClient(client -> SeedFinderClient.session(client).disagreements().isEmpty()),
            "no recognized start disagreed with the /locate answers");

        context.waitFor(client -> {
            WorldSession session = SeedFinderClient.session(client);
            return session.resultIsCurrent() && session.result() != null;
        }, 20 * 180);
        WorldSeedSearch.Result result = context.computeOnClient(client -> SeedFinderClient.session(client).result());
        check(result.stage() == WorldSeedSearch.Stage.WORLD_SEED && result.worldSeed().getAsLong() == SEED && result.leftOut().isEmpty(),
            "with the recognized structures the seed is still " + SEED + ", with nothing left out");
        context.getInput().pressKey(SeedFinderClient.openKey);
        context.waitForScreen(SeedFinderScreen.class);
        context.takeScreenshot("seedfinder-recognized");

        // "Remove all notes" forgets everything; recognition then notes the structure around the player again.
        context.clickScreenButton("gui.jriksseedfinder.remove_structure");
        context.waitForScreen(RemoveStructureScreen.class);
        context.clickScreenButton("gui.jriksseedfinder.start_over");
        context.waitForScreen(ConfirmScreen.class);
        context.clickScreenButton("gui.yes");
        context.waitForScreen(SeedFinderScreen.class);
        check(context.computeOnClient(client -> SeedFinderClient.session(client).structures().stream()
            .noneMatch(s -> s.source().equals("from /locate"))), "Remove all notes removed the notes");
        context.waitFor(client -> SeedFinderClient.session(client).structures().stream()
            .anyMatch(s -> s.source().equals(StructureRecognition.SOURCE)), 20 * 60);
        check(true, "after starting over the structure nearby was recognized again");
        context.clickScreenButton("gui.jriksseedfinder.close");
        context.waitForScreen(null);
    }

    /** Teleports the player to a start and waits until the mod recognizes it. */
    static void visit(ClientGameTestContext context, TestSingleplayerContext world, String dimension, String set, ChunkPos start) {
        world.getServer().runCommand("execute in " + dimension + " run tp @a " + (start.getMinBlockX() + 8) + " " + (dimension.endsWith("nether") ? 100 : 160)
            + " " + (start.getMinBlockZ() + 8));
        RecognizedStarts.Start expected = new RecognizedStarts.Start(set, Chunks.x(start), Chunks.z(start));
        boolean found = false;
        for (int wait = 0; wait < 20 * 120 && !found; wait += 20) {
            context.waitTicks(20);
            found = context.computeOnClient(client -> SeedFinderClient.recognition().starts().contains(expected));
        }
        check(found, "recognized " + set + " starting in chunk " + Chunks.x(start) + ", " + Chunks.z(start) + " (" + dimension + ") from its blocks");
    }

    /** The recognizers on the nearest real starts of each set, generated by the game (see {@link RecognitionSweep}). */
    static void recognizesRealStartsAround(ClientGameTestContext context, TestSingleplayerContext world) {
        StartPieceRecognizer recognizer = context.computeOnClient(client -> StructureRecognition.recognizer(SeedFinderClient.data()).getNow(null));
        RecognitionSweep.Result result = world.getServer().computeOnServer(server -> RecognitionSweep.run(server.overworld(), recognizer));
        log("sweep: " + result.found() + " of " + result.real() + " real starts recognized; missed " + result.missed() + "; pieces " + result.pieces());
        check(result.wrong().isEmpty(), "the sweep recognized no start that isn't real " + result.wrong());
        check(result.conflicting().isEmpty(), "no region had contradicting matches " + result.conflicting());
        check(result.sets().containsAll(StartPieceLibrary.setsOf(SeedFinderClient.data())), "the sweep had real starts of every type " + result.sets());
        check(result.real() >= 18 && result.found() >= result.real() * 0.85,
            "the sweep recognized " + result.found() + " of " + result.real() + " real starts of the "
                + StartPieceLibrary.setsOf(SeedFinderClient.data()).size() + " types");

        RigidLibrary.Library library = context.computeOnClient(client ->
            StructureRecognition.rigidLibrary(SeedFinderClient.data(), client.level.registryAccess()).getNow(null));
        check(library != null && library.problems().isEmpty() && library.layouts().size() > 400,
            "the rigid-structure layouts were built from the game (" + (library == null ? "not yet" : library.layouts().size() + " layouts, problems "
                + library.problems()) + ")");
        for (String dimension : List.of("minecraft:overworld", "minecraft:the_nether")) {
            RigidRecognizer rigid = RigidRecognizer.create(library.layouts(), SeedFinderClient.data(), dimension);
            RecognitionSweep.Result found = world.getServer().computeOnServer(server -> RecognitionSweep.runRigid(
                dimension.endsWith("nether") ? server.getLevel(Level.NETHER) : server.overworld(), rigid, dimension.endsWith("nether")));
            log("rigid sweep " + dimension + ": " + found.found() + " of " + found.real() + " real starts recognized; missed " + found.missed()
                + "; layouts " + found.pieces());
            check(found.wrong().isEmpty(), "the rigid sweep in " + dimension + " recognized no start that isn't real " + found.wrong());
            check(found.conflicting().isEmpty(), "no region had contradicting rigid matches " + found.conflicting());
            // Up to 4 starts per kind near the world's centre; kinds of rare biomes may have fewer, but at least one.
            check(rigid.kinds().stream().allMatch(kind -> found.sets().contains(kind.structureSet())),
                "the rigid sweep in " + dimension + " had real starts of every kind " + found.sets());
            check(found.real() >= 2 * rigid.kinds().size() && found.found() >= found.real() * 0.85,
                "the rigid sweep in " + dimension + " recognized " + found.found() + " of " + found.real() + " real starts");
        }
    }

    /**
     * A structure placed with /place sits on a chunk corner like a real start; the mod reads the command's answer
     * and never notes it. An abandoned camp where the version has them (26.x), else a pillager outpost. Chunk 172, 172
     * is one where both can start, in snowy plains, and no real start of either is near it for seed 12345.
     */
    static void ignoresPlacedStructures(ClientGameTestContext context, TestSingleplayerContext world) {
        boolean camps = StartPieceLibrary.setsOf(SeedFinderClient.data()).contains("abandoned_camp");
        String set = camps ? "abandoned_camp" : "pillager_outposts";
        String structure = camps ? "minecraft:abandoned_camp_taiga" : "minecraft:pillager_outpost";
        int chunk = 172;
        StructureSet placement = SeedFinderClient.data().structureSet(set).orElseThrow();
        RandomSpreadPlacement grid = (RandomSpreadPlacement) placement.placement();
        check(Math.floorMod(chunk, grid.spacing()) < grid.spacing() - grid.separation()
                && new StructureLocator(SeedFinderClient.data(), SEED).candidates(placement, chunk - 16, chunk - 16, chunk + 16, chunk + 16).isEmpty(),
            "chunk " + chunk + ", " + chunk + " can hold a start of " + set + ", and no real one may start within 16 chunks of it");
        int x = chunk * 16;
        int z = chunk * 16;
        // /place needs the whole area loaded on the server. Afterwards the player leaves and comes back, so the
        // chunks are sent again with the placed structure in them.
        world.getServer().runCommand("forceload add " + (x - 96) + " " + (z - 96) + " " + (x + 96) + " " + (z + 96));
        world.getServer().runCommand("tp @a " + (x + 8) + " 160 " + (z + 8));
        context.waitTicks(100);
        context.runOnClient(client -> client.getConnection().sendCommand("place structure " + structure + " " + x + " 100 " + z));
        context.waitTicks(40);
        world.getServer().runCommand("forceload remove all");
        world.getServer().runCommand("tp @a " + (x + 8 + 1000) + " 160 " + (z + 8));
        context.waitTicks(100);
        world.getServer().runCommand("tp @a " + (x + 8) + " 160 " + (z + 8));
        RecognizedStarts.Start placed = new RecognizedStarts.Start(set, chunk, chunk);
        for (int wait = 0; wait < 20 * 60 && !context.computeOnClient(client -> SeedFinderClient.recognition().setAside().contains(placed)); wait += 20) {
            context.waitTicks(20);
        }
        check(context.computeOnClient(client -> SeedFinderClient.recognition().setAside().contains(placed)),
            "the " + structure + " placed with /place looks like a start by its blocks, and is set aside");
        context.waitTicks(40);
        check(context.computeOnClient(client -> SeedFinderClient.session(client).structures().stream()
            .noneMatch(s -> s.set().equals(set) && Math.floorDiv(s.x(), 16) == chunk && Math.floorDiv(s.z(), 16) == chunk)),
            "but it is not noted, because the mod read the /place answer");
    }

    /**
     * The same for a rigid structure: a ruined portal placed with /place structure in a chunk where portals can start,
     * at least 12 chunks from where the real portal of that placement region may be (so it is not in view), and a
     * portal template placed with /place template on the corner of a chunk 3 chunks from it.
     */
    static void ignoresPlacedRigidStructures(ClientGameTestContext context, TestSingleplayerContext world) {
        RandomSpreadPlacement grid = (RandomSpreadPlacement) SeedFinderClient.data().structureSet("ruined_portals").orElseThrow().placement();
        int regionX = 4;
        int regionZ = 4;
        io.github.jriiik.seedfinder.world.ChunkPos real = grid.potentialChunk(SEED, regionX, regionZ);
        int limit = grid.spacing() - grid.separation();
        int chunkX = regionX * grid.spacing() + (real.x() - regionX * grid.spacing() < limit / 2 ? limit - 1 : 0);
        int chunkZ = regionZ * grid.spacing() + (real.z() - regionZ * grid.spacing() < limit / 2 ? limit - 1 : 0);
        int x = chunkX * 16;
        int z = chunkZ * 16;
        int templateChunkX = chunkX + (chunkX - regionX * grid.spacing() == 0 ? 3 : -3);
        // Placed while the player is far away (the chunks are force-loaded on the server): the mod first sees these
        // chunks with the structures in them. (A chunk is judged once; one judged before something was placed in it
        // is not judged again, so a structure placed there is never noted anyway.)
        world.getServer().runCommand("tp @a " + (x + 8 + 1000) + " 160 " + (z + 8));
        world.getServer().runCommand("forceload add " + (x - 96) + " " + (z - 64) + " " + (x + 96) + " " + (z + 64));
        context.waitTicks(100);
        context.runOnClient(client -> client.getConnection().sendCommand("place structure minecraft:ruined_portal " + x + " 100 " + z));
        context.runOnClient(client -> client.getConnection().sendCommand("place template minecraft:ruined_portal/portal_3 " + templateChunkX * 16 + " 100 " + z));
        context.waitTicks(40);
        world.getServer().runCommand("forceload remove all");
        context.waitTicks(100);
        world.getServer().runCommand("tp @a " + (x + 8) + " 160 " + (z + 8));
        RecognizedStarts.Start placed = new RecognizedStarts.Start("ruined_portals", chunkX, chunkZ);
        RecognizedStarts.Start template = new RecognizedStarts.Start("ruined_portals", templateChunkX, chunkZ);
        for (int wait = 0; wait < 20 * 90 && !context.computeOnClient(client -> SeedFinderClient.recognition().setAside().containsAll(List.of(placed, template)));
             wait += 20) {
            context.waitTicks(20);
        }
        check(context.computeOnClient(client -> SeedFinderClient.recognition().setAside().contains(placed)),
            "the ruined portal placed with /place structure in chunk " + chunkX + ", " + chunkZ + " looks like a start by its blocks, and is set aside");
        if (!context.computeOnClient(client -> SeedFinderClient.recognition().setAside().contains(template))) {
            log("template chunk: " + context.computeOnClient(client -> SeedFinderClient.recognition().describeRigid(templateChunkX, chunkZ)));
            log("placed spots known: " + context.computeOnClient(client -> SeedFinderClient.session(client).wasPlaced("ruined_portals", templateChunkX, chunkZ))
                + ", player in chunk " + context.computeOnClient(client -> client.player.chunkPosition()) + ", render distance "
                + context.computeOnClient(client -> client.options.getEffectiveRenderDistance()));
        }
        check(context.computeOnClient(client -> SeedFinderClient.recognition().setAside().contains(template)),
            "the portal template placed with /place template in chunk " + templateChunkX + ", " + chunkZ + " looks like a start by its blocks, and is set aside");
        context.waitTicks(40);
        check(context.computeOnClient(client -> SeedFinderClient.session(client).structures().stream()
            .noneMatch(s -> s.set().equals("ruined_portals") && Math.floorDiv(s.z(), 16) == chunkZ
                && (Math.floorDiv(s.x(), 16) == chunkX || Math.floorDiv(s.x(), 16) == templateChunkX))),
            "but neither is noted, because the mod read the /place answers");
    }

    /** The start chunk of the structure of a set nearest to a position, with the search /locate uses. */
    static ChunkPos nearestStart(ServerLevel level, String set, BlockPos from) {
        Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        List<Holder<Structure>> structures = new ArrayList<>();
        for (StructureSet.Entry entry : SeedFinderClient.data().structureSet(set).orElseThrow().structures()) {
            structures.add(registry.get(Identifier.parse(entry.structure())).orElseThrow());
        }
        Pair<BlockPos, Holder<Structure>> found = level.getChunkSource().getGenerator()
            .findNearestMapStructure(level, HolderSet.direct(structures), from, 100, false);
        return found == null ? null : new ChunkPos(found.getFirst().getX() >> 4, found.getFirst().getZ() >> 4);
    }

    /** The structures that really start in a chunk, from the game's own records. */
    static List<String> realStarts(ServerLevel level, int chunkX, int chunkZ) {
        Registry<Structure> structures = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        return level.getChunk(chunkX, chunkZ, ChunkStatus.STRUCTURE_STARTS, true).getAllStarts().entrySet().stream()
            .filter(entry -> entry.getValue().isValid())
            .map(entry -> String.valueOf(structures.getKey(entry.getKey())))
            .toList();
    }

    /**
     * The biomes the client received: every cell the mod noted is the engine's biome for the seed, the F3 biome of a
     * block is the stored cell the engine's zoom picks with the hashed seed, and the one area noted proves the seed
     * among its likely seeds (12345 can be typed as a number).
     */
    private static void checkBiomes(ClientGameTestContext context, WorldSession session, ObservationReader.Observations read,
                                    WorldSeedSearch.Result result) {
        check(session.biomeAreas() == 1 && session.biomeChunks() >= 50, "biomes of " + session.biomeChunks() + " chunks in one area were noted");
        check(!result.biomesDisagree(), "the biomes the mod read fit the seed");
        OverworldBiomes biomes = OverworldBiomes.of(SeedFinderClient.minecraftVersion(), OverworldBiomes.Preset.NORMAL);
        OverworldBiomes.Sampler sampler = biomes.newSampler().seed(SEED);
        List<BiomeObservation> wrong = new ArrayList<>();
        for (BiomeObservation note : read.biomes()) {
            if (!sampler.couldBe(biomes.parameters().biomeIndex(note.biome()), note.x() >> 2, note.y() >> 2, note.z() >> 2)) {
                wrong.add(note);
            }
        }
        check(wrong.isEmpty() && read.biomes().size() >= 50 * 16 * BiomeScanner.HEIGHTS.length,
            "every one of the " + read.biomes().size() + " biome cells read from the game fits seed " + SEED + (wrong.isEmpty() ? "" : ": " + wrong));
        long hashed = Seeds.hashedSeed(SEED);
        String zoom = context.computeOnClient(client -> {
            BlockPos player = client.player.blockPosition();
            for (int i = 0; i < 400; i++) {
                int x = player.getX() + (i * 37) % 97 - 48;
                int y = 40 + (i * 13) % 60;
                int z = player.getZ() + (i * 53) % 89 - 44;
                int[] quart = io.github.jriiik.seedfinder.biome.BiomeZoom.quart(hashed, x, y, z);
                Holder<net.minecraft.world.level.biome.Biome> shown = client.level.getBiome(new BlockPos(x, y, z));
                Holder<net.minecraft.world.level.biome.Biome> cell = client.level.getNoiseBiome(quart[0], quart[1], quart[2]);
                if (!shown.equals(cell)) {
                    return "block " + x + " " + y + " " + z + " shows " + shown + ", the zoomed cell has " + cell;
                }
            }
            return "";
        });
        check(zoom.isEmpty(), "the F3 biome of 400 blocks is the stored cell the engine's zoom picks " + zoom);
        WorldSeedSearch.StructureStep step = WorldSeedSearch.structureSeeds(SeedFinderClient.data(), read.structures(), 2, (done, total) -> { });
        WorldSeedSearch.Result fromBiomes = WorldSeedSearch.fullSeed(step, List.of(), new WorldSeedSearch.BiomeInput(
            new BiomeEvidence(SeedFinderClient.minecraftVersion(), read.biomes(), java.util.OptionalLong.empty()), null, () -> false), java.util.OptionalLong.empty(), 2);
        check(fromBiomes.proof().orElse(null) == WorldSeedSearch.Proof.BIOMES && fromBiomes.worldSeed().getAsLong() == SEED,
            "the biomes of the area (with the structures) also prove the seed: " + fromBiomes.proof() + " " + fromBiomes.worldSeed());
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        context.runOnClient(client -> {
            SeedFinderClient.openKey.setKey(InputConstants.getKey("key.keyboard.j"));
            KeyMapping.resetMapping();
        });

        // 1) The default test world is superflat: switching collecting on notes no bedrock there.
        try (TestSingleplayerContext flat = context.worldBuilder().create()) {
            GameTestVersion.waitForChunksRender(flat);
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
            GameTestVersion.waitForChunksRender(world);
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

            // The seed was typed (as the number 12345), so the hashed seed alone gives it, before any structure, and the
            // bedrock read around the player confirms it.
            context.waitFor(client -> SeedFinderClient.session(client).typedSeedCheck()
                .map(typed -> typed.verdict() == SeedCheck.Verdict.CONFIRMED).orElse(false), 20 * 90);
            check(context.computeOnClient(client -> SeedFinderClient.session(client).worldSeed().orElse(0)) == SEED,
                "the hashed seed alone gave the typed seed " + SEED + ", and the notes confirm it");
            String statusLine = context.computeOnClient(client -> StatusText.statusLine(SeedFinderClient.session(client), SeedFinderClient.runner()));
            check(statusLine.equals("Seed Finder: world seed " + SEED), "the status line shows it: " + statusLine);
            context.getInput().pressKey(SeedFinderClient.openKey);
            context.waitForScreen(SeedFinderScreen.class);
            context.takeScreenshot("seedfinder-typed-seed");
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
            // The notes exactly as the player saves or copies them (with their version line), read by the engine.
            List<String> notes = context.computeOnClient(client -> SeedFinderClient.session(client).notesLines());
            ObservationReader.Observations read = ObservationReader.read(SeedFinderClient.data(), notes);
            check(read.minecraft().equals(java.util.Optional.of(SeedFinderClient.minecraftVersion())),
                "the notes name Minecraft " + SeedFinderClient.minecraftVersion().displayName() + ", the version the game runs");
            BedrockEvidence bedrock = new BedrockEvidence(read.bedrock());
            check(bedrock.problems().isEmpty() && bedrock.fullSeedObservations().size() > 1000,
                "the notes hold " + bedrock.fullSeedObservations().size() + " informative bedrock blocks");
            check(bedrock.mismatchesOf(SEED).isEmpty(), "every bedrock block read from the game fits seed " + SEED);
            check(read.hashedSeed().getAsLong() == Seeds.hashedSeed(SEED), "the notes carry the hashed seed");
            WorldSeedSearch.Result fromBedrock = WorldSeedSearch.run(SeedFinderClient.data(), read.structures(), read.bedrock(),
                java.util.OptionalLong.empty(), 2, (done, total) -> { });
            check(fromBedrock.proof().orElse(null) == WorldSeedSearch.Proof.BEDROCK && fromBedrock.worldSeed().getAsLong() == SEED,
                "the bedrock alone also proves the seed");
            checkBiomes(context, session, read, result);

            // Copy seed, Copy notes, and the saved notes the command-line app can read: they name the Minecraft version.
            String versionLine = ObservationReader.versionLine(SeedFinderClient.minecraftVersion());
            context.getInput().pressKey(SeedFinderClient.openKey);
            context.waitForScreen(SeedFinderScreen.class);
            context.clickScreenButton("gui.jriksseedfinder.copy_seed");
            check(context.computeOnClient(client -> client.keyboardHandler.getClipboard()).equals(Long.toString(SEED)), "Copy seed copies " + SEED);
            context.clickScreenButton("gui.jriksseedfinder.copy_notes");
            String copied = context.computeOnClient(client -> client.keyboardHandler.getClipboard());
            check(copied.startsWith(versionLine + "\n") && copied.contains("hashed-seed " + Seeds.hashedSeed(SEED)),
                "Copy notes copies the notes, starting with '" + versionLine + "'");
            context.clickScreenButton("gui.jriksseedfinder.close");
            context.waitForScreen(null);
            context.waitTicks(120);
            Path folder = SeedFinderClient.folder();
            String base = WorldSession.fileName(session.world());
            check(Files.isRegularFile(folder.resolve(base + ".json")) && Files.isRegularFile(folder.resolve(base + ".txt")),
                "the notes were saved in " + folder);
            List<String> saved = readLines(folder.resolve(base + ".txt"));
            check(saved.getFirst().equals(versionLine), "the saved notes file starts with '" + versionLine + "': " + saved.getFirst());
            for (String which : List.of("copied", "saved")) {
                // What the command-line app gets reads back with the engine, as notes of the version the game runs.
                ObservationReader.Observations back = ObservationReader.read(SeedFinderClient.data(), which.equals("saved") ? saved : copied.lines().toList());
                check(back.minecraft().equals(read.minecraft()) && back.hashedSeed().equals(read.hashedSeed())
                        && back.bedrock().size() >= read.bedrock().size() && !back.biomes().isEmpty(),
                    "the " + which + " notes read back with the engine: " + back.bedrock().size() + " bedrock notes, " + back.biomes().size()
                        + " biome cells");
            }

            recognizesStructures(context, world);
            recognizesRealStartsAround(context, world);
            ignoresPlacedStructures(context, world);
            ignoresPlacedRigidStructures(context, world);
        }
        log("ALL CHECKS PASSED");
    }
}
