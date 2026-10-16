package io.github.jriiik.seedfinder.cli;

import io.github.jriiik.seedfinder.Seeds;
import io.github.jriiik.seedfinder.bedrock.BedrockDimension;
import io.github.jriiik.seedfinder.bedrock.BedrockPattern;
import io.github.jriiik.seedfinder.bedrock.BedrockRule;
import io.github.jriiik.seedfinder.bedrock.BedrockText;
import io.github.jriiik.seedfinder.finder.ObservationReader;
import io.github.jriiik.seedfinder.version.MinecraftVersion;
import io.github.jriiik.seedfinder.version.VersionData;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "bedrock",
    mixinStandardHelpOptions = true,
    description = {
        "Show the bedrock floor a world should have, in the format 'find' and 'full-seed' read (the explanation lines start "
            + "with #, so the whole output can be saved as a notes file).",
        "Handy to check your notes against a world whose seed you know, or to see what the bedrock notes look like.",
    },
    footer = {
        "",
        "Examples:",
        "  seedfinder bedrock --seed 12345",
        "  seedfinder bedrock --seed 12345 --at 160,-48 --size 8 --layers",
        "  seedfinder bedrock --seed 12345 --dimension nether",
    }
)
final class BedrockCommand implements Runnable {

    private static final int WORLD_LIMIT = 30_000_000;
    private static final int MAX_SIZE = 64;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @CommandLine.Option(names = {"-s", "--seed"}, required = true, paramLabel = "<seed>",
        description = "The world seed: a number, or text exactly as it was typed into Minecraft.")
    String seed;

    @CommandLine.Option(names = "--at", paramLabel = "<x,z>", defaultValue = "0,0",
        description = "Block x and z of the north-west corner of the area (default: ${DEFAULT-VALUE}).")
    String at;

    @CommandLine.Option(names = "--size", paramLabel = "<columns>", defaultValue = "16",
        description = "Width and length of the area in blocks (default: ${DEFAULT-VALUE}, one chunk).")
    int size;

    @CommandLine.Option(names = {"-d", "--dimension"}, paramLabel = "<dimension>", defaultValue = "overworld",
        description = "overworld or nether (default: ${DEFAULT-VALUE}).")
    Dimension dimension;

    @CommandLine.Option(names = "--layers", description = "Also show every random bedrock layer block by block.")
    boolean layers;

    @CommandLine.Mixin
    VersionOption version = new VersionOption();

    @Override
    public void run() {
        CommandLine commandLine = spec.commandLine();
        PrintWriter out = commandLine.getOut();
        MinecraftVersion minecraft = version.resolve(commandLine);
        VersionData data = minecraft.data();
        OptionalLong parsed = Seeds.parse(seed);
        if (parsed.isEmpty()) {
            throw new CommandLine.ParameterException(commandLine, "--seed must not be empty");
        }
        int[] corner = parseAt(commandLine);
        if (size < 1 || size > MAX_SIZE) {
            throw new CommandLine.ParameterException(commandLine, "--size must be between 1 and " + MAX_SIZE);
        }
        BedrockDimension bedrock = data.bedrockDimension(dimension.id()).orElseThrow();
        BedrockRule floor = bedrock.floor().orElseThrow(() ->
            new CommandLine.ParameterException(commandLine, "The " + (dimension == Dimension.END ? "End" : dimension.displayName())
                + " has no random bedrock layer."));
        long worldSeed = parsed.getAsLong();
        BedrockPattern pattern = new BedrockPattern(bedrock, worldSeed);

        out.printf("# Bedrock floor of the %s for seed %d (Minecraft %s), %d x %d columns from block (%d, %d).%n",
            dimension.displayName(), worldSeed, data.version(), size, size, corner[0], corner[1]);
        List<String> digits = new ArrayList<>();
        for (int y = floor.falseAtAndAbove() - 1; y >= floor.trueAtAndBelow(); y--) {
            digits.add(Math.abs(y) % 10 + " = y " + y);
        }
        out.println("# Each digit is the last digit of the height of the top bedrock block: " + String.join(", ", digits) + ".");
        out.println("# Rows go from north to south (z), each row from west to east (x).");
        if (!bedrock.dependsOnFullSeed()) {
            out.println("# Bedrock here only depends on the structure seed, " + Seeds.structureSeed(worldSeed) + ".");
        }
        out.println();
        out.println(ObservationReader.versionLine(minecraft));
        BedrockText.heights(pattern, corner[0], corner[1], size, size).forEach(out::println);
        if (layers) {
            for (int y = bedrock.minY(); y < bedrock.minY() + bedrock.height(); y++) {
                if (bedrock.isRandomAt(y)) {
                    out.println();
                    BedrockText.layer(pattern, corner[0], y, corner[1], size, size).forEach(out::println);
                }
            }
        }
    }

    private int[] parseAt(CommandLine commandLine) {
        String[] parts = at.split(",");
        try {
            if (parts.length == 2) {
                int x = Integer.parseInt(parts[0].trim());
                int z = Integer.parseInt(parts[1].trim());
                if (Math.abs(x) <= WORLD_LIMIT && Math.abs(z) <= WORLD_LIMIT) {
                    return new int[] {x, z};
                }
            }
        } catch (NumberFormatException ignored) {
            // reported below
        }
        throw new CommandLine.ParameterException(commandLine, "--at must be a block x,z inside the world (for example --at 160,-48), got: " + at);
    }
}
