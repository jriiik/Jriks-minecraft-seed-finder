package io.github.jriiik.seedfinder.cli;

import io.github.jriiik.seedfinder.version.MinecraftVersion;
import java.io.PrintWriter;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "versions", mixinStandardHelpOptions = true, description = "List the Minecraft versions this finder supports.")
final class VersionsCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        PrintWriter out = spec.commandLine().getOut();
        out.println("Supported Minecraft Java Edition versions:");
        for (MinecraftVersion version : MinecraftVersion.values()) {
            out.println("  " + version.displayName() + (version == MinecraftVersion.latest() ? "  (newest)" : ""));
        }
    }
}
