package io.github.jriiik.seedfinder.finder;

import io.github.jriiik.seedfinder.structure.StructureSet;
import io.github.jriiik.seedfinder.version.MinecraftVersion;
import io.github.jriiik.seedfinder.version.VersionData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads observations written the way people naturally note them down. Each line is one structure:
 * <pre>
 * desert_pyramid -1408 -1264        name and block x z
 * village 336 70 80                 name and block x y z (the height is ignored)
 * igloo: 48, 208                    punctuation is fine
 * The nearest minecraft:igloo is at [48, ~, 208] (213 blocks away)     a line pasted from /locate
 * # comment
 * </pre>
 */
public final class ObservationParser {

    private static final Pattern LOCATE_OUTPUT = Pattern.compile(
        "nearest\\s+(\\S+)(?:\\s+\\(([^)\\s]+)\\))?\\s+is at\\s+\\[\\s*(-?\\d+)\\s*,\\s*~\\s*,\\s*(-?\\d+)\\s*]");
    /** The colon in "igloo: 48 208" or "igloo:48,208" (but not the one in "minecraft:igloo"). */
    private static final Pattern NAME_COLON = Pattern.compile(":(?=\\s*-?\\d)");

    private ObservationParser() {
    }

    /** Parses all lines; errors mention the line number. */
    public static List<StructureObservation> parseLines(VersionData data, List<String> lines) {
        List<StructureObservation> observations = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            try {
                parseLine(data, lines.get(i)).ifPresent(observations::add);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Line " + (i + 1) + ": " + e.getMessage(), e);
            }
        }
        return observations;
    }

    /** Parses one line; empty for blank lines and comments. */
    public static Optional<StructureObservation> parseLine(VersionData data, String line) {
        String text = line.strip();
        if (text.isEmpty() || text.startsWith("#")) {
            return Optional.empty();
        }
        Matcher locate = LOCATE_OUTPUT.matcher(text);
        if (locate.find()) {
            String name = locate.group(2) != null ? locate.group(2) : locate.group(1);
            return Optional.of(observation(data, name, parseInt(locate.group(3)), parseInt(locate.group(4))));
        }
        String[] tokens = NAME_COLON.matcher(text).replaceFirst(" ").replaceAll("[\\[\\]()]", " ").strip().split("[\\s,;]+");
        List<Integer> numbers = new ArrayList<>();
        for (int i = 1; i < tokens.length; i++) {
            if (!tokens[i].equals("~")) {
                numbers.add(parseInt(tokens[i]));
            }
        }
        if (numbers.size() != 2 && numbers.size() != 3) {
            throw new IllegalArgumentException("expected a structure name and its x and z block coordinates, like: igloo 48 208");
        }
        return Optional.of(observation(data, tokens[0], numbers.get(0), numbers.get(numbers.size() - 1)));
    }

    private static StructureObservation observation(VersionData data, String name, int x, int z) {
        StructureSet set = data.findStructureSet(name).orElseThrow(() -> new IllegalArgumentException(unknown(data, name)));
        return StructureObservation.atBlock(set, x, z);
    }

    /** Names the versions that have a structure the notes' version lacks (like abandoned camps, new in 26.x). */
    private static String unknown(VersionData data, String name) {
        List<String> others = Arrays.stream(MinecraftVersion.values())
            .filter(version -> !version.displayName().equals(data.version()) && version.data().findStructureSet(name).isPresent())
            .map(MinecraftVersion::displayName)
            .toList();
        if (others.isEmpty()) {
            return "unknown structure '" + name + "'";
        }
        return "Minecraft " + data.version() + " has no structure '" + name + "' (only Minecraft " + String.join(", ", others) + " has it)";
    }

    private static int parseInt(String token) {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + token + "' is not a whole number");
        }
    }
}
