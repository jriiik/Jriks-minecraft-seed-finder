package io.github.jriiik.seedfinder.cli;

import io.github.jriiik.seedfinder.structure.ConcentricRingsPlacement;
import io.github.jriiik.seedfinder.structure.RandomSpreadPlacement;
import io.github.jriiik.seedfinder.structure.StructureSet;
import io.github.jriiik.seedfinder.version.VersionData;
import java.io.PrintWriter;
import java.util.stream.Collectors;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "structures", mixinStandardHelpOptions = true, description = "List the structure types the finder knows about.")
final class StructuresCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @CommandLine.Mixin
    VersionOption version = new VersionOption();

    @Override
    public void run() {
        VersionData data = version.resolve(spec.commandLine()).data();
        PrintWriter out = spec.commandLine().getOut();
        out.printf("Structure types in Minecraft %s (use these names with 'seedfinder locate'):%n%n", data.version());
        out.printf("  %-20s %-20s %s%n", "Name", "Dimension", "How often");
        for (StructureSet set : data.structureSets()) {
            String dimensions = data.dimensionsOf(set).stream().map(Dimension::displayName).collect(Collectors.joining(" + "));
            out.printf("  %-20s %-20s %s%n", set.shortId(), dimensions, howOften(set));
        }
        out.println();
        out.println("Variants (like the five village styles) depend on the biome; they are listed together under one name.");
    }

    private static String howOften(StructureSet set) {
        return switch (set.placement()) {
            case RandomSpreadPlacement spread when spread.spacing() == 1 ->
                String.format(java.util.Locale.ROOT, "in about %.1f%% of chunks", spread.frequency() * 100);
            case RandomSpreadPlacement spread -> "one try per " + spread.spacing() + "x" + spread.spacing() + " chunks"
                + (spread.frequency() < 1 ? String.format(java.util.Locale.ROOT, ", %.0f%% chance", spread.frequency() * 100) : "");
            case ConcentricRingsPlacement rings -> rings.count() + " in rings around spawn (not supported yet)";
            default -> set.placement().type();
        };
    }
}
