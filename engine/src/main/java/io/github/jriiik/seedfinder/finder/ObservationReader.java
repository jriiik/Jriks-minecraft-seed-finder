package io.github.jriiik.seedfinder.finder;

import io.github.jriiik.seedfinder.bedrock.BedrockDimension;
import io.github.jriiik.seedfinder.bedrock.BedrockObservation;
import io.github.jriiik.seedfinder.bedrock.BedrockRule;
import io.github.jriiik.seedfinder.biome.OverworldBiomes;
import io.github.jriiik.seedfinder.version.VersionData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a list of observations: structures (one per line, see {@link ObservationParser}) and bedrock. Bedrock can be
 * written three ways:
 * <pre>
 * bedrock-heights 0 0          the top bedrock block of each column, below a header with the x z of the
 * 0120?1                       north-west column. One row per z (north to south), one character per x (west to
 * 1024...                      east): the last digit of the height (0 = y -60 ... 4 = y -64), ? = not looked at.
 *                              Rows may also list the heights as numbers: -60 -61 -62
 *
 * bedrock-layer 0 -60 0        one layer, below a header with the x y z of its north-west block:
 * B..B?B                       B = bedrock, . = something else, ? = not looked at
 *
 * bedrock 10 -61 20            single blocks
 * not-bedrock 11 -61 20
 * </pre>
 * Overworld biomes can be written two ways:
 * <pre>
 * biome dark_forest 120 64 -35     the biome F3 shows at a block (x y z); "minecraft:" may be left out
 *
 * biome-grid 112 64 -48 a=plains b=forest   the biomes the game stored for 4 x 4 x 4 block cells (what the mod reads),
 * aab?                                      below a header with a block of the north-west cell and a legend: one row
 * abbb                                      per cell southwards (4 blocks), one letter per cell eastwards, ? = not read
 * </pre>
 * A line {@code preset large_biomes} (or {@code amplified}, {@code normal}) says which world preset the world uses;
 * without it the biomes are tried with each preset in turn.
 * A line {@code hashed-seed <number>} gives the hashed seed the server sent (the mod writes it into its notes).
 * The overworld is assumed; write the dimension after the keyword for the nether ({@code bedrock-heights nether 0 0}).
 * A grid ends at an empty line or at the first line that is not a row.
 */
public final class ObservationReader {

    /** Structures, bedrock, biomes, the world preset and the hashed seed read from a list of lines. */
    public record Observations(List<StructureObservation> structures, List<BedrockObservation> bedrock, List<BiomeObservation> biomes,
                               Optional<OverworldBiomes.Preset> preset, OptionalLong hashedSeed) {
    }

    /** A mistake in one line; {@link #lineIndex()} is 0-based. */
    public static final class LineException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final int lineIndex;

        LineException(int lineIndex, String message) {
            super(message);
            this.lineIndex = lineIndex;
        }

        public int lineIndex() {
            return lineIndex;
        }
    }

    private static final Pattern HEADER = Pattern.compile(
        "(?i)(bedrock[- ]heights|bedrock[- ]layer|not[- ]bedrock|no[- ]bedrock|bedrock)(?=$|[\\s:,(\\[])(.*)");
    private static final Pattern HASHED_SEED = Pattern.compile("(?i)hashed[- ]seed[\\s:=]+(-?\\d+)");
    private static final Pattern BIOME = Pattern.compile("(?i)(biome[- ]grid|biome)(?=$|[\\s:,(\\[])(.*)");
    private static final Pattern PRESET = Pattern.compile("(?i)(?:world[- ])?preset[\\s:=]+(\\S.*?)\\s*");
    private static final Pattern BIOME_ROW = Pattern.compile("[A-Za-z?]+");
    private static final Pattern NUMBER_ROW = Pattern.compile("(?:-?\\d+|\\?)(?:[\\s,;]+(?:-?\\d+|\\?))*");
    private static final Pattern DIGIT_ROW = Pattern.compile("[0-9?]+");
    private static final Pattern LAYER_ROW = Pattern.compile("[bB.?]+");
    /** Lines made only of grid characters: rows of bedrock notes. */
    private static final Pattern ROW_LIKE = Pattern.compile("[0-9?.\\-,;\\s]+|[bB.?\\s]+");
    /** Minecraft's world border. */
    private static final int WORLD_LIMIT = 30_000_000;

    private ObservationReader() {
    }

    public static Observations read(VersionData data, List<String> lines) {
        List<StructureObservation> structures = new ArrayList<>();
        List<BedrockObservation> bedrock = new ArrayList<>();
        List<BiomeObservation> biomes = new ArrayList<>();
        Optional<OverworldBiomes.Preset> preset = Optional.empty();
        OptionalLong hashedSeed = OptionalLong.empty();
        int i = 0;
        while (i < lines.size()) {
            String text = lines.get(i).strip();
            Matcher header = HEADER.matcher(withoutComment(text));
            try {
                Matcher hashed = HASHED_SEED.matcher(withoutComment(text));
                if (hashed.matches()) {
                    try {
                        hashedSeed = OptionalLong.of(Long.parseLong(hashed.group(1)));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("the hashed seed must be a whole number from -9223372036854775808 to 9223372036854775807");
                    }
                    i++;
                    continue;
                }
                Matcher presetLine = PRESET.matcher(withoutComment(text));
                if (presetLine.matches()) {
                    String name = presetLine.group(1);
                    preset = Optional.of(OverworldBiomes.Preset.byName(name.replace(' ', '_')).orElseThrow(() -> new IllegalArgumentException(
                        "unknown world preset '" + name + "' (use normal, large_biomes or amplified)")));
                    i++;
                    continue;
                }
                Matcher biome = BIOME.matcher(withoutComment(text));
                if (biome.matches()) {
                    if (biome.group(1).length() > "biome".length()) {
                        i = readBiomeGrid(lines, i, biome.group(2), biomes);
                    } else {
                        biomes.add(biomeLine(biome.group(2)));
                        i++;
                    }
                    continue;
                }
                if (!header.matches()) {
                    if (!text.startsWith("#") && ROW_LIKE.matcher(withoutComment(text)).matches()) {
                        throw new IllegalArgumentException("this line has no structure name. If it is a row of bedrock notes, it "
                            + "must be right below a bedrock-heights or bedrock-layer line (an empty line ends the rows)");
                    }
                    ObservationParser.parseLine(data, text).ifPresent(structures::add);
                    i++;
                    continue;
                }
                String keyword = header.group(1).toLowerCase(Locale.ROOT).replace(' ', '-');
                Arguments arguments = arguments(data, header.group(2));
                switch (keyword) {
                    case "bedrock", "not-bedrock", "no-bedrock" -> {
                        arguments.requireNumbers(3, keyword + " x y z");
                        bedrock.add(new BedrockObservation(arguments.dimension, arguments.numbers.get(0), arguments.numbers.get(1),
                            arguments.numbers.get(2), keyword.equals("bedrock")));
                        i++;
                    }
                    case "bedrock-heights" -> {
                        if (arguments.numbers.size() == 3) {
                            arguments.numbers.remove(1); // "x y z" from F3: the height is not needed
                        }
                        arguments.requireNumbers(2, "bedrock-heights x z");
                        i = readHeights(lines, i, arguments, bedrock);
                    }
                    default -> {
                        arguments.requireNumbers(3, "bedrock-layer x y z");
                        i = readLayer(lines, i, arguments, bedrock);
                    }
                }
            } catch (LineException e) {
                throw e;
            } catch (IllegalArgumentException e) {
                throw new LineException(i, e.getMessage());
            }
        }
        return new Observations(structures, bedrock, biomes, preset, hashedSeed);
    }

    /** "biome dark_forest 120 64 -35": the name (words may be joined by spaces), then the block's x y z. */
    private static BiomeObservation biomeLine(String text) {
        String[] tokens = text.replaceAll("[\\[\\]()]", " ").strip().split("[\\s,;]+");
        List<String> name = new ArrayList<>();
        List<Integer> numbers = new ArrayList<>();
        for (String raw : tokens) {
            String token = stripColons(raw);
            if (token.isEmpty() || token.equals("~")) {
                continue;
            }
            if (numbers.isEmpty() && token.matches(".*[A-Za-z].*")) {
                name.add(token);
            } else {
                numbers.add(coordinate(token));
            }
        }
        if (name.isEmpty() || numbers.size() != 3) {
            throw new IllegalArgumentException("expected biome <name> x y z, like: biome dark_forest 120 64 -35");
        }
        return new BiomeObservation(biomeId(String.join("_", name)), numbers.get(0), numbers.get(1), numbers.get(2), false);
    }

    /** "biome: forest" and "biome-grid: 3000 64 -3000" are read like the lines without the colon. */
    private static String stripColons(String token) {
        return token.replaceAll("^:+|:+$", "");
    }

    /** "Dark Forest", "dark_forest" and "minecraft:dark_forest" all name the same biome. */
    private static String biomeId(String name) {
        String id = name.strip().toLowerCase(Locale.ROOT).replace(' ', '_');
        return id.contains(":") ? id : "minecraft:" + id;
    }

    private static int readBiomeGrid(List<String> lines, int headerIndex, String header, List<BiomeObservation> out) {
        Map<Character, String> legend = new HashMap<>();
        List<Integer> numbers = new ArrayList<>();
        for (String raw : header.replaceAll("[\\[\\]()]", " ").strip().split("[\\s,;]+")) {
            String token = stripColons(raw);
            if (token.isEmpty()) {
                continue;
            }
            int equals = token.indexOf('=');
            if (equals > 0) {
                if (equals != 1 || !Character.isLetter(token.charAt(0))) {
                    throw new LineException(headerIndex, "a legend entry is one letter, '=' and a biome, like a=plains; not '" + token + "'");
                }
                legend.put(token.charAt(0), biomeId(token.substring(2)));
            } else {
                try {
                    numbers.add(coordinate(token));
                } catch (IllegalArgumentException e) {
                    throw new LineException(headerIndex, e.getMessage());
                }
            }
        }
        if (numbers.size() != 3) {
            throw new LineException(headerIndex, "expected biome-grid x y z a=plains b=forest ... (a block of the first cell, then the legend)");
        }
        int x0 = numbers.get(0) & ~3;
        int y = numbers.get(1);
        int z0 = numbers.get(2) & ~3;
        int row = 0;
        int i = headerIndex + 1;
        for (; i < lines.size(); i++) {
            if (lines.get(i).strip().startsWith("#")) {
                continue;
            }
            String text = withoutComment(lines.get(i).strip());
            String compact = text.replaceAll("\\s+", "");
            if (compact.isEmpty() || !BIOME_ROW.matcher(compact).matches()) {
                if (row == 0) {
                    throw new LineException(i, "biome-grid needs rows of legend letters below it, like aab? (? = not read)");
                }
                break;
            }
            for (int column = 0; column < compact.length(); column++) {
                char cell = compact.charAt(column);
                if (cell == '?') {
                    continue;
                }
                String biome = legend.get(cell);
                if (biome == null) {
                    throw new LineException(i, "the letter '" + cell + "' is not in the legend of the biome-grid line above");
                }
                out.add(new BiomeObservation(biome, x0 + 4 * column, y, z0 + 4 * row, true));
            }
            row++;
        }
        if (row == 0) {
            throw new LineException(headerIndex, "biome-grid needs rows of legend letters below it, like aab? (? = not read)");
        }
        return i;
    }

    private static int coordinate(String token) {
        if (token.matches("-?\\d*[.]\\d+|-?\\d+[.]\\d*")) {
            throw new IllegalArgumentException("'" + token + "' is not a whole block coordinate");
        }
        if (!token.matches("-?\\d+")) {
            throw new IllegalArgumentException("'" + token + "' is not a whole number");
        }
        long value = token.length() > 12 ? Long.MAX_VALUE : Long.parseLong(token);
        if (Math.abs(value) > WORLD_LIMIT) {
            throw new IllegalArgumentException(token + " is outside the world (coordinates go from -30000000 to 30000000)");
        }
        return (int) value;
    }

    /** A bedrock line without a trailing "# comment" (structure lines keep theirs: /locate output can contain '#'). */
    private static String withoutComment(String text) {
        int hash = text.indexOf('#');
        return hash < 0 ? text : text.substring(0, hash).strip();
    }

    /**
     * Whether a line that is not a valid row ends the grid. The first line below a header must be a row, and a line
     * made of grid characters is a broken row, so mistakes there are reported instead of ending the grid silently.
     */
    private static boolean endsGrid(String row, boolean valid, int rowsSoFar, int index, String example) {
        if (valid) {
            return false;
        }
        if (rowsSoFar == 0 && !row.isEmpty()) {
            throw new LineException(index, "expected a row like " + example + " here");
        }
        if (!row.isEmpty() && ROW_LIKE.matcher(row).matches()) {
            throw new LineException(index, "this row has a character that doesn't belong in it; use " + example);
        }
        return true;
    }

    private static int readHeights(List<String> lines, int headerIndex, Arguments arguments, List<BedrockObservation> out) {
        BedrockDimension dimension = arguments.dimension;
        BedrockRule floor = dimension.floor().orElseThrow(() ->
            new IllegalArgumentException("the " + dimension.displayName() + " has no random bedrock floor"));
        int lowest = floor.trueAtAndBelow();
        int highest = floor.falseAtAndAbove() - 1;
        int x0 = arguments.numbers.get(0);
        int z0 = arguments.numbers.get(1);
        int row = 0;
        int i = headerIndex + 1;
        for (; i < lines.size(); i++) {
            if (lines.get(i).strip().startsWith("#")) {
                continue;
            }
            String text = withoutComment(lines.get(i).strip()).replaceFirst("[\\s,;]+$", "");
            // Either full heights with a minus sign ("-60 -61 ?"), or one last digit per column ("01?2", "0 1 ? 2").
            boolean numbers = text.contains("-") && NUMBER_ROW.matcher(text).matches();
            String compact = text.replaceAll("[\\s,;]+", "");
            if (endsGrid(text, numbers || !compact.isEmpty() && DIGIT_ROW.matcher(compact).matches(), row, i, "0120 (digits 0-4)")) {
                break;
            }
            List<String> cells = numbers ? List.of(text.split("[\\s,;]+")) : compact.chars().mapToObj(Character::toString).toList();
            for (int column = 0; column < cells.size(); column++) {
                String cell = cells.get(column);
                if (cell.equals("?")) {
                    continue;
                }
                int top;
                try {
                    int value = Integer.parseInt(cell);
                    top = cell.length() == 1 ? heightWithLastDigit(value, lowest, highest) : value;
                } catch (IllegalArgumentException e) {
                    throw new LineException(i, e instanceof NumberFormatException ? "'" + cell + "' is not a height" : e.getMessage());
                }
                if (top < lowest || top > highest) {
                    throw new LineException(i, "a top bedrock height of " + top + " is impossible; the " + dimension.displayName()
                        + " bedrock floor is between y=" + lowest + " and y=" + highest);
                }
                int x = x0 + column;
                int z = z0 + row;
                out.add(new BedrockObservation(dimension, x, top, z, true));
                for (int y = top + 1; y <= highest; y++) {
                    out.add(new BedrockObservation(dimension, x, y, z, false));
                }
            }
            row++;
        }
        if (row == 0) {
            throw new LineException(headerIndex, "bedrock-heights needs rows of heights below it, like 0120");
        }
        return i;
    }

    /** The height between lowest and highest whose last digit is this one (0 is y=-60 in the overworld). */
    private static int heightWithLastDigit(int digit, int lowest, int highest) {
        for (int y = lowest; y <= highest; y++) {
            if (Math.abs(y) % 10 == digit) {
                return y;
            }
        }
        StringBuilder allowed = new StringBuilder();
        for (int y = lowest; y <= highest; y++) {
            allowed.append(allowed.isEmpty() ? "" : ", ").append(Math.abs(y) % 10).append(" for y=").append(y);
        }
        throw new IllegalArgumentException("the height digit " + digit + " is impossible here; use " + allowed);
    }

    private static int readLayer(List<String> lines, int headerIndex, Arguments arguments, List<BedrockObservation> out) {
        int x0 = arguments.numbers.get(0);
        int y = arguments.numbers.get(1);
        int z0 = arguments.numbers.get(2);
        int row = 0;
        int i = headerIndex + 1;
        for (; i < lines.size(); i++) {
            if (lines.get(i).strip().startsWith("#")) {
                continue;
            }
            String text = withoutComment(lines.get(i).strip());
            String compact = text.replaceAll("\\s+", "");
            if (endsGrid(text, !compact.isEmpty() && LAYER_ROW.matcher(compact).matches(), row, i, "B..B (B = bedrock, . = anything else)")) {
                break;
            }
            for (int column = 0; column < compact.length(); column++) {
                char cell = compact.charAt(column);
                if (cell != '?') {
                    out.add(new BedrockObservation(arguments.dimension, x0 + column, y, z0 + row, cell == 'B' || cell == 'b'));
                }
            }
            row++;
        }
        if (row == 0) {
            throw new LineException(headerIndex, "bedrock-layer needs rows below it, like B..B (B = bedrock, . = something else)");
        }
        return i;
    }

    /** The words after a bedrock keyword: an optional dimension, then numbers. */
    private record Arguments(BedrockDimension dimension, List<Integer> numbers) {

        void requireNumbers(int count, String example) {
            if (numbers.size() != count) {
                throw new IllegalArgumentException("expected " + example + " (block coordinates)");
            }
        }
    }

    private static Arguments arguments(VersionData data, String text) {
        String[] tokens = text.replaceAll("[\\[\\]()]", " ").strip().split("[\\s,;]+");
        BedrockDimension dimension = null;
        List<Integer> numbers = new ArrayList<>();
        for (String raw : tokens) {
            String token = raw.replaceAll("^:+|:+$", "");
            if (token.isEmpty() || token.equals("~")) {
                continue;
            }
            if (numbers.isEmpty() && dimension == null && token.matches(".*[A-Za-z].*")) {
                dimension = data.bedrockDimension(token).orElseThrow(() ->
                    new IllegalArgumentException("unknown dimension '" + token + "' (use overworld or nether)"));
                if (dimension.rules().isEmpty()) {
                    throw new IllegalArgumentException("the " + dimension.displayName() + " has no random bedrock");
                }
                continue;
            }
            if (token.matches("-?\\d*[.]\\d+|-?\\d+[.]\\d*")) {
                throw new IllegalArgumentException("'" + token + "' is not a whole block coordinate; use the block's position "
                    + "(F3 shows it as \"Targeted Block\")");
            }
            if (!token.matches("-?\\d+")) {
                throw new IllegalArgumentException("'" + token + "' is not a whole number");
            }
            long value = token.length() > 12 ? Long.MAX_VALUE : Long.parseLong(token);
            if (Math.abs(value) > WORLD_LIMIT) {
                throw new IllegalArgumentException(token + " is outside the world (coordinates go from -30000000 to 30000000)");
            }
            numbers.add((int) value);
        }
        if (dimension == null) {
            dimension = data.bedrockDimension("overworld").orElseThrow();
        }
        return new Arguments(dimension, numbers);
    }
}
