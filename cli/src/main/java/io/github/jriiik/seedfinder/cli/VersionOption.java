package io.github.jriiik.seedfinder.cli;

import io.github.jriiik.seedfinder.version.MinecraftVersion;
import java.util.Arrays;
import java.util.stream.Collectors;
import picocli.CommandLine;

/** The shared {@code --minecraft} option for picking a Minecraft version. */
final class VersionOption {

    @CommandLine.Option(
        names = {"-m", "--minecraft"},
        paramLabel = "<version>",
        description = "Minecraft version of the world (default: the newest supported version)."
    )
    String name;

    MinecraftVersion resolve(CommandLine commandLine) {
        if (name == null) {
            return MinecraftVersion.latest();
        }
        return MinecraftVersion.byName(name).orElseThrow(() -> new CommandLine.ParameterException(commandLine,
            "Minecraft " + name + " is not supported yet. Supported: "
                + Arrays.stream(MinecraftVersion.values()).map(MinecraftVersion::displayName).collect(Collectors.joining(", "))));
    }
}
