package io.github.jriiik.seedfinder.cli;

import io.github.jriiik.seedfinder.Seeds;
import io.github.jriiik.seedfinder.biome.OverworldBiomes;
import io.github.jriiik.seedfinder.finder.BiomeText;
import io.github.jriiik.seedfinder.finder.ObservationReader;
import io.github.jriiik.seedfinder.version.MinecraftVersion;
import java.io.PrintWriter;
import java.util.OptionalLong;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "biomes",
    mixinStandardHelpOptions = true,
    description = {
        "Show the overworld biomes a world should have, in the format 'find' and 'full-seed' read (the explanation lines start "
            + "with #, so the whole output can be saved as a notes file).",
        "Each letter is the biome the game stores for a cell of 4 x 4 x 4 blocks (F3 shows a block's biome, picked among the "
            + "cells around it).",
    },
    footer = {
        "",
        "Examples:",
        "  seedfinder biomes --seed 12345",
        "  seedfinder biomes --seed 12345 --at 160,-48 --y 0 --size 32 --preset large_biomes",
    }
)
final class BiomesCommand implements Runnable {

    private static final int WORLD_LIMIT = 30_000_000;
    private static final int MAX_CELLS = 128;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @CommandLine.Option(names = {"-s", "--seed"}, required = true, paramLabel = "<seed>",
        description = "The world seed: a number, or text exactly as it was typed into Minecraft.")
    String seed;

    @CommandLine.Option(names = "--at", paramLabel = "<x,z>", defaultValue = "0,0",
        description = "Block x and z of the north-west corner of the area (default: ${DEFAULT-VALUE}).")
    String at;

    @CommandLine.Option(names = "--y", paramLabel = "<y>", defaultValue = "64", description = "The height (default: ${DEFAULT-VALUE}).")
    int y;

    @CommandLine.Option(names = "--size", paramLabel = "<cells>", defaultValue = "16",
        description = "Width and length of the area in cells of 4 blocks (default: ${DEFAULT-VALUE}, 4 x 4 chunks).")
    int size;

    @CommandLine.Option(names = "--preset", paramLabel = "<preset>", defaultValue = "normal",
        description = "The world preset: normal, large_biomes or amplified (default: ${DEFAULT-VALUE}).")
    String preset;

    @CommandLine.Mixin
    VersionOption version = new VersionOption();

    @Override
    public void run() {
        CommandLine commandLine = spec.commandLine();
        PrintWriter out = commandLine.getOut();
        MinecraftVersion minecraft = version.resolve(commandLine);
        OptionalLong parsed = Seeds.parse(seed);
        if (parsed.isEmpty()) {
            throw new CommandLine.ParameterException(commandLine, "--seed must not be empty");
        }
        OverworldBiomes.Preset worldPreset = OverworldBiomes.Preset.byName(preset).orElseThrow(() ->
            new CommandLine.ParameterException(commandLine, "--preset must be normal, large_biomes or amplified"));
        if (size < 1 || size > MAX_CELLS) {
            throw new CommandLine.ParameterException(commandLine, "--size must be between 1 and " + MAX_CELLS);
        }
        String[] parts = at.split(",");
        int x;
        int z;
        try {
            if (parts.length != 2) {
                throw new NumberFormatException();
            }
            x = Integer.parseInt(parts[0].strip());
            z = Integer.parseInt(parts[1].strip());
        } catch (NumberFormatException e) {
            throw new CommandLine.ParameterException(commandLine, "--at must be block x and z like 160,-48");
        }
        if (Math.abs((long) x) > WORLD_LIMIT || Math.abs((long) z) > WORLD_LIMIT) {
            throw new CommandLine.ParameterException(commandLine, "--at must be inside the world (-30000000 to 30000000)");
        }
        long worldSeed = parsed.getAsLong();
        OverworldBiomes biomes = OverworldBiomes.of(minecraft, worldPreset);
        out.printf("# Overworld biomes for seed %d (Minecraft %s, %s preset) at y %d, %d x %d cells of 4 blocks from block (%d, %d).%n",
            worldSeed, minecraft.displayName(), worldPreset.id().substring("minecraft:".length()), y, size, size, x & ~3, z & ~3);
        out.println("# Rows go from north to south (z), each row from west to east (x).");
        out.println(ObservationReader.versionLine(minecraft));
        if (worldPreset != OverworldBiomes.Preset.NORMAL) {
            out.println("preset " + worldPreset.id().substring("minecraft:".length()));
        }
        BiomeText.grid(biomes, worldSeed, x, y, z, size, size).forEach(out::println);
    }
}
