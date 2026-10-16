package io.github.jriiik.seedfinder.cli;

import io.github.jriiik.seedfinder.finder.ObservationReader;
import io.github.jriiik.seedfinder.version.MinecraftVersion;
import java.util.Arrays;
import java.util.List;
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

    /**
     * Whether the notes were read as the newest version only because neither --minecraft nor a line in them names a
     * version. Biome notes from another version's world then may not fit the right seed.
     */
    boolean assumed(ObservationReader.Observations notes) {
        return name == null && notes.minecraft().isEmpty();
    }

    /**
     * The --minecraft option a printed command that reads the same notes file needs: when the version is not the
     * newest and the file doesn't name it (it came from --minecraft or from a line on the command line), else "".
     */
    static String argumentFor(MinecraftVersion used, ObservationInput notes) {
        return used == MinecraftVersion.latest() || notes.fileNamesVersion() ? "" : " --minecraft " + used.displayName();
    }

    /**
     * Says that notes naming no version were read as {@code used}, and how to name another version: for biome notes
     * that don't fit (biomes differ between versions). Empty when there is no other version.
     */
    static List<String> assumedVersionHint(MinecraftVersion used) {
        List<String> others = Arrays.stream(MinecraftVersion.values()).filter(other -> other != used).map(MinecraftVersion::displayName).toList();
        if (others.isEmpty()) {
            return List.of();
        }
        return List.of(
            "Your notes name no Minecraft version, so they were read as Minecraft " + used.displayName() + ", and biomes differ between versions.",
            "If the world is from Minecraft " + String.join(" or ", others) + ", add a line 'minecraft " + others.get(0) + "' to the notes (or use"
                + " --minecraft " + others.get(0) + ").");
    }
}
