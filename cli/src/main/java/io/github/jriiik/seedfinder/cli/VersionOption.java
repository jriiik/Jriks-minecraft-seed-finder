package io.github.jriiik.seedfinder.cli;

import io.github.jriiik.seedfinder.version.MinecraftVersion;
import java.util.Optional;
import picocli.CommandLine;

/** The shared {@code --minecraft} option for picking a Minecraft version. */
final class VersionOption {

    @CommandLine.Option(
        names = {"-m", "--minecraft"},
        paramLabel = "<version>",
        description = "Minecraft version of the world (default: the version a 'minecraft <version>' line in the notes names, "
            + "else the newest supported version)."
    )
    String name;

    /** The version given with --minecraft, else the newest. */
    MinecraftVersion resolve(CommandLine commandLine) {
        if (name == null) {
            return MinecraftVersion.latest();
        }
        return MinecraftVersion.byName(name).orElseThrow(() -> new CommandLine.ParameterException(commandLine,
            "Minecraft " + name + " is not supported yet. Supported: " + MinecraftVersion.supportedNames()));
    }

    /**
     * The version for reading notes: --minecraft, else the version the notes name in a 'minecraft <version>' line,
     * else the newest. Refuses --minecraft when the notes name another version.
     */
    MinecraftVersion resolve(CommandLine commandLine, ObservationInput notes) {
        Optional<MinecraftVersion> named = notes.minecraftVersion(commandLine);
        if (name == null) {
            return named.orElse(MinecraftVersion.latest());
        }
        MinecraftVersion chosen = resolve(commandLine);
        if (named.isPresent() && named.get() != chosen) {
            throw new CommandLine.ParameterException(commandLine, "The notes say they are from Minecraft " + named.get().displayName()
                + " (their 'minecraft " + named.get().displayName() + "' line), but --minecraft says " + chosen.displayName()
                + ". Leave out --minecraft, or change that line if the notes are really from Minecraft " + chosen.displayName() + ".");
        }
        return chosen;
    }
}
