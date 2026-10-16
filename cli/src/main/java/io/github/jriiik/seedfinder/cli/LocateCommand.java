package io.github.jriiik.seedfinder.cli;

import io.github.jriiik.seedfinder.Seeds;
import io.github.jriiik.seedfinder.structure.RandomSpreadPlacement;
import io.github.jriiik.seedfinder.structure.StructureCandidate;
import io.github.jriiik.seedfinder.structure.StructureLocator;
import io.github.jriiik.seedfinder.structure.StructureSet;
import io.github.jriiik.seedfinder.version.MinecraftVersion;
import io.github.jriiik.seedfinder.version.VersionData;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalLong;
import java.util.stream.Collectors;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "locate",
    mixinStandardHelpOptions = true,
    description = {
        "Show where structures will try to generate in a world, like a map of possible structure spots.",
        "A structure only appears at its spot if the biome there allows it. The finder does not check biomes yet, "
            + "so some spots will turn out empty in the game.",
    },
    footer = {
        "",
        "Examples:",
        "  seedfinder locate --seed 12345",
        "  seedfinder locate --seed \"my world\" --at 500,-1200 --radius 2000 villages pillager_outposts",
        "  seedfinder locate --seed 12345 --dimension nether",
    }
)
final class LocateCommand implements Runnable {

    /** Sets with a candidate in (nearly) every chunk; listing them by default would drown everything else. */
    private static final int VERY_COMMON_SPACING = 2;
    /** Very common sets are checked chunk by chunk, so their search area is kept reasonable. */
    private static final int VERY_COMMON_MAX_RADIUS = 3000;
    /** Minecraft's world border sits at 30 million blocks from the center. */
    private static final int WORLD_LIMIT = 30_000_000;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @CommandLine.Option(names = {"-s", "--seed"}, required = true, paramLabel = "<seed>",
        description = "The world seed: a number, or text exactly as it was typed into Minecraft.")
    String seed;

    @CommandLine.Option(names = "--at", paramLabel = "<x,z>", defaultValue = "0,0",
        description = "Search around this block position (default: ${DEFAULT-VALUE}).")
    String at;

    @CommandLine.Option(names = {"-r", "--radius"}, paramLabel = "<blocks>", defaultValue = "1000",
        description = "How far to search, in blocks (default: ${DEFAULT-VALUE}).")
    int radius;

    @CommandLine.Option(names = {"-d", "--dimension"}, paramLabel = "<dimension>", defaultValue = "overworld",
        description = "overworld, nether or end (default: ${DEFAULT-VALUE}).")
    Dimension dimension;

    @CommandLine.Option(names = "--limit", paramLabel = "<count>", defaultValue = "50",
        description = "Show at most this many spots, nearest first (default: ${DEFAULT-VALUE}).")
    int limit;

    @CommandLine.Mixin
    VersionOption version = new VersionOption();

    @CommandLine.Parameters(paramLabel = "<structure>", arity = "0..*",
        description = "Which structures to show, e.g. villages or desert_pyramids (see 'seedfinder structures'). Default: all common ones in the dimension.")
    List<String> structureNames = new ArrayList<>();

    @Override
    public void run() {
        CommandLine commandLine = spec.commandLine();
        PrintWriter out = commandLine.getOut();
        VersionData data = version.resolve(commandLine).data();
        long worldSeed = parseSeed(commandLine);
        int[] center = parseAt(commandLine);
        if (radius < 1 || radius > 100_000) {
            throw new CommandLine.ParameterException(commandLine, "--radius must be between 1 and 100000 blocks");
        }

        if (limit < 1) {
            throw new CommandLine.ParameterException(commandLine, "--limit must be at least 1");
        }

        List<StructureSet> sets = chooseSets(commandLine, data);
        if (radius > VERY_COMMON_MAX_RADIUS && sets.stream().anyMatch(LocateCommand::isVeryCommon)) {
            throw new CommandLine.ParameterException(commandLine,
                "For very common structures (like mineshafts) please use a --radius of at most " + VERY_COMMON_MAX_RADIUS + " blocks.");
        }
        StructureLocator locator = new StructureLocator(data, worldSeed);
        int minChunkX = Math.floorDiv(center[0] - radius, 16);
        int maxChunkX = Math.floorDiv(center[0] + radius, 16);
        int minChunkZ = Math.floorDiv(center[1] - radius, 16);
        int maxChunkZ = Math.floorDiv(center[1] + radius, 16);

        List<Spot> spots = new ArrayList<>();
        for (StructureSet set : sets) {
            for (StructureCandidate candidate : locator.candidates(set, minChunkX, minChunkZ, maxChunkX, maxChunkZ)) {
                double distance = Math.hypot(candidate.locatePos().x() - center[0], candidate.locatePos().z() - center[1]);
                if (distance <= radius) {
                    spots.add(new Spot(candidate, distance));
                }
            }
        }
        spots.sort(Comparator.comparingDouble(Spot::distance));

        out.printf("Minecraft %s, %s, %s, within %d blocks of (%d, %d)%n%n",
            data.version(), describeSeed(worldSeed), dimension.displayName(), radius, center[0], center[1]);
        if (spots.isEmpty()) {
            out.println("No structure spots in this area. Try a bigger --radius.");
        } else {
            out.printf("  %-20s %8s %8s %10s%n", "Structure", "X", "Z", "Distance");
            spots.stream().limit(limit).forEach(spot -> out.printf("  %-20s %8d %8d %10d%n",
                spot.candidate().set().shortId(), spot.candidate().locatePos().x(), spot.candidate().locatePos().z(),
                Math.round(spot.distance())));
            if (spots.size() > limit) {
                out.printf("  ... and %d more (use --limit to show more)%n", spots.size() - limit);
            }
        }
        out.println();
        out.println("These are the spots where each structure tries to generate. It only appears if the biome there");
        out.println("allows it (the finder does not check biomes yet), so some spots will be empty in the game.");
        if (structureNames.isEmpty()) {
            String skipped = data.structureSets().stream()
                .filter(set -> inDimension(data, set) && !StructureLocator.canLocate(set))
                .map(StructureSet::shortId).collect(Collectors.joining(", "));
            if (!skipped.isEmpty()) {
                out.println("Not shown yet because they depend on biomes: " + skipped + ".");
            }
            String common = data.structureSets().stream()
                .filter(set -> inDimension(data, set) && isVeryCommon(set))
                .map(StructureSet::shortId).collect(Collectors.joining(", "));
            if (!common.isEmpty()) {
                out.println("Very common structures are hidden unless you ask for them by name: " + common + ".");
            }
        }
    }

    private long parseSeed(CommandLine commandLine) {
        OptionalLong parsed = Seeds.parse(seed);
        if (parsed.isEmpty()) {
            throw new CommandLine.ParameterException(commandLine, "--seed must not be empty");
        }
        return parsed.getAsLong();
    }

    private String describeSeed(long worldSeed) {
        String typed = seed.trim();
        return typed.equals(Long.toString(worldSeed))
            ? "seed " + worldSeed
            : "seed \"" + typed + "\" (text, which Minecraft turns into " + worldSeed + ")";
    }

    private int[] parseAt(CommandLine commandLine) {
        String[] parts = at.split(",");
        try {
            if (parts.length == 2) {
                int x = Integer.parseInt(parts[0].trim());
                int z = Integer.parseInt(parts[1].trim());
                if (Math.abs(x) > WORLD_LIMIT || Math.abs(z) > WORLD_LIMIT) {
                    throw new CommandLine.ParameterException(commandLine, "--at must be inside the world (between -30000000 and 30000000)");
                }
                return new int[] {x, z};
            }
        } catch (NumberFormatException ignored) {
            // reported below
        }
        throw new CommandLine.ParameterException(commandLine, "--at must look like x,z (for example --at 250,-1200), got: " + at);
    }

    private List<StructureSet> chooseSets(CommandLine commandLine, VersionData data) {
        if (structureNames.isEmpty()) {
            return data.structureSets().stream()
                .filter(set -> inDimension(data, set) && StructureLocator.canLocate(set) && !isVeryCommon(set))
                .toList();
        }
        List<StructureSet> chosen = new ArrayList<>();
        for (String name : structureNames) {
            String id = name.trim().toLowerCase(java.util.Locale.ROOT);
            StructureSet set = data.structureSet(id).orElseThrow(() -> {
                String others = java.util.Arrays.stream(MinecraftVersion.values())
                    .filter(other -> !other.displayName().equals(data.version()) && other.data().structureSet(id).isPresent())
                    .map(MinecraftVersion::displayName).collect(Collectors.joining(", "));
                return new CommandLine.ParameterException(commandLine, others.isEmpty()
                    ? "Unknown structure '" + name + "'. Run 'seedfinder structures' to see the names."
                    : "Minecraft " + data.version() + " has no " + name + " (only Minecraft " + others + " has them). Run 'seedfinder structures --minecraft "
                        + data.version() + "' to see the names.");
            });
            if (!StructureLocator.canLocate(set)) {
                throw new CommandLine.ParameterException(commandLine, set.shortId() + " depend on biomes, which the finder can't calculate yet.");
            }
            if (!inDimension(data, set)) {
                String where = data.dimensionsOf(set).stream().map(Dimension::displayName).collect(Collectors.joining(" or "));
                throw new CommandLine.ParameterException(commandLine, set.shortId() + " generate in the " + where + ", not the "
                    + dimension.displayName() + ". Add --dimension " + where.split(" ")[0] + ".");
            }
            chosen.add(set);
        }
        return chosen;
    }

    private boolean inDimension(VersionData data, StructureSet set) {
        return data.dimensionsOf(set).contains(dimension.id());
    }

    private static boolean isVeryCommon(StructureSet set) {
        return set.placement() instanceof RandomSpreadPlacement spread && spread.spacing() <= VERY_COMMON_SPACING;
    }

    private record Spot(StructureCandidate candidate, double distance) {
    }
}
