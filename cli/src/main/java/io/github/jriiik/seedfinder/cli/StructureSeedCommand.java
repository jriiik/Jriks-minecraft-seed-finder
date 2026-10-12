package io.github.jriiik.seedfinder.cli;

import io.github.jriiik.seedfinder.Seeds;
import java.io.PrintWriter;
import java.util.OptionalLong;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "structure-seed",
    mixinStandardHelpOptions = true,
    description = "Show the structure seed of a world seed you know (for example from /seed), to compare with 'find'."
)
final class StructureSeedCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @CommandLine.Parameters(paramLabel = "<seed>", description = "The world seed: a number, or text exactly as it was typed into Minecraft.")
    String seed;

    @Override
    public void run() {
        CommandLine commandLine = spec.commandLine();
        OptionalLong parsed = Seeds.parse(seed);
        if (parsed.isEmpty()) {
            throw new CommandLine.ParameterException(commandLine, "The seed must not be empty");
        }
        long worldSeed = parsed.getAsLong();
        String typed = seed.trim();
        String described = typed.equals(Long.toString(worldSeed)) ? Long.toString(worldSeed)
            : "\"" + typed + "\" (text, which Minecraft turns into " + worldSeed + ")";
        PrintWriter out = commandLine.getOut();
        out.println("World seed " + described + " has structure seed " + Seeds.structureSeed(worldSeed) + ".");
    }
}
