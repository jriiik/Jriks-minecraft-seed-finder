package io.github.jriiik.seedfinder.cli;

import io.github.jriiik.seedfinder.Seeds;
import io.github.jriiik.seedfinder.bedrock.BedrockEvidence;
import io.github.jriiik.seedfinder.finder.FullSeedFinder;
import io.github.jriiik.seedfinder.finder.ObservationReader;
import io.github.jriiik.seedfinder.version.MinecraftVersion;
import io.github.jriiik.seedfinder.version.VersionData;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "full-seed",
    mixinStandardHelpOptions = true,
    description = {
        "Work out the full world seed from its structure seed (what 'find' gives you), optionally with bedrock or biomes you "
            + "have seen.",
        "Without them it names the likely seeds: the one Minecraft picks when nobody types a seed, and the one a text "
            + "seed would give. With bedrock or biomes it checks all 65,536 world seeds that share the structure seed.",
        "",
        "Bedrock: at the bottom of the overworld, clear an area down to the bedrock (one chunk, 16 x 16 columns, is nearly "
            + "always enough; press F3+G to see chunk borders). For each column, note the height of the top bedrock block "
            + "as its last digit: 0 for y=-60, 1 for -61, 2 for -62, 3 for -63, 4 for -64. Write one row per z, from north "
            + "to south, each row from west to east, below a line with the x and z of the north-west column. Use ? for "
            + "columns you did not check. Don't use bedrock inside mineshafts or strongholds.",
        "",
        "Biomes: stand somewhere, press F3 and note the biome it shows with your block position: 'biome forest 120 64 -35'. "
            + "Note several places where different biomes meet. The biomes of one area (what you see around you) prove a seed "
            + "Minecraft picked by itself or a text seed; any other seed needs a second area at least 4,096 blocks away "
            + "(16,384 in a large-biomes world). The mod notes the biomes of whole areas for you.",
    },
    footer = {
        "",
        "Example file:",
        "  bedrock-heights 0 0",
        "  0120211030213012",
        "  1102300121013320",
        "  ...",
        "",
        "Other ways to write bedrock: 'bedrock-layer x y z' followed by rows of B (bedrock) and . (anything else), or",
        "single blocks: 'bedrock x y z' and 'not-bedrock x y z'. Add 'nether' after the keyword for nether bedrock, which",
        "only depends on the structure seed. 'seedfinder bedrock' prints the bedrock of a seed you know in this format.",
        "Biomes the game stored for 4 x 4 block cells (the mod writes these): 'biome-grid x y z a=plains b=forest' followed by",
        "rows of those letters (? = not read). 'preset large_biomes' (or amplified) says which world preset the world uses.",
        "",
        "Examples:",
        "  seedfinder full-seed 12345",
        "  seedfinder full-seed 12345 --file my-bedrock.txt",
    }
)
final class FullSeedCommand implements Callable<Integer> {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @CommandLine.Parameters(paramLabel = "<structure-seed>", arity = "1..*",
        description = "The structure seed from 'find' (several if it found several).")
    List<Long> structureSeeds = new ArrayList<>();

    @CommandLine.Option(names = {"-f", "--file"}, paramLabel = "<file>", description = "Read bedrock and biome notes from a text file.")
    Path file;

    @CommandLine.Option(names = "--threads", paramLabel = "<count>", description = "How many CPU threads to use (default: all of them).")
    int threads;

    @CommandLine.Mixin
    VersionOption version = new VersionOption();

    @Override
    public Integer call() {
        CommandLine commandLine = spec.commandLine();
        PrintWriter out = commandLine.getOut();
        MinecraftVersion minecraft = version.resolve(commandLine);
        VersionData data = minecraft.data();
        for (long seed : structureSeeds) {
            if (seed < 0 || seed > Seeds.STRUCTURE_SEED_MASK) {
                throw new CommandLine.ParameterException(commandLine, seed + " is not a structure seed: those go from 0 to "
                    + Seeds.STRUCTURE_SEED_MASK + ". For a world seed you know, use 'seedfinder structure-seed'.");
            }
        }
        structureSeeds = new ArrayList<>(new LinkedHashSet<>(structureSeeds));
        if (structureSeeds.size() > FullSeedFinder.MAX_STRUCTURE_SEEDS) {
            throw new CommandLine.ParameterException(commandLine, "Give at most " + FullSeedFinder.MAX_STRUCTURE_SEEDS + " structure seeds.");
        }
        ObservationReader.Observations observations = ObservationInput.of(commandLine, file, List.of()).read(commandLine, data);
        if (!observations.structures().isEmpty()) {
            out.println("Note: the structures in the file are not used here; 'seedfinder find' works with them.");
        }
        BedrockEvidence evidence = new BedrockEvidence(observations.bedrock());
        if (!evidence.problems().isEmpty()) {
            evidence.problems().forEach(problem -> out.println("Can't use the bedrock notes: " + problem + "."));
            return 1;
        }
        int threadCount = Threads.resolve(commandLine, threads);
        FullSeedReport.Biomes biomes = FullSeedReport.Biomes.of(out, observations, minecraft);
        return FullSeedReport.run(out, data, structureSeeds, evidence, biomes, observations.hashedSeed(), threadCount) ? 0 : 1;
    }
}
