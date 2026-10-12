package io.github.jriiik.seedfinder.finder;

import io.github.jriiik.seedfinder.bedrock.BedrockDimension;
import io.github.jriiik.seedfinder.bedrock.BedrockObservation;
import io.github.jriiik.seedfinder.bedrock.BedrockRule;
import io.github.jriiik.seedfinder.version.VersionData;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * The overworld is assumed; write the dimension after the keyword for the nether ({@code bedrock-heights nether 0 0}).
 * A grid ends at an empty line or at the first line that is not a row.
 */
public final class ObservationReader {

    /** Structures and bedrock read from a list of lines. */
    public record Observations(List<StructureObservation> structures, List<BedrockObservation> bedrock) {
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

    private static final Pattern HEADER = Pattern.compile("(?i)(bedrock-heights|bedrock-layer|not-bedrock|no-bedrock|bedrock)(?=$|[\\s:,(\\[])(.*)");
    private static final Pattern NUMBER_ROW = Pattern.compile("(?:-?\\d+|\\?)(?:[\\s,;]+(?:-?\\d+|\\?))*");
    private static final Pattern DIGIT_ROW = Pattern.compile("[0-9?]+");
    private static final Pattern LAYER_ROW = Pattern.compile("[bB.?]+");

    private ObservationReader() {
    }

    public static Observations read(VersionData data, List<String> lines) {
        List<StructureObservation> structures = new ArrayList<>();
        List<BedrockObservation> bedrock = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            String text = lines.get(i).strip();
            Matcher header = HEADER.matcher(text);
            try {
                if (!header.matches()) {
                    ObservationParser.parseLine(data, text).ifPresent(structures::add);
                    i++;
                    continue;
                }
                String keyword = header.group(1).toLowerCase(Locale.ROOT);
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
        return new Observations(structures, bedrock);
    }

    private static int readHeights(List<String> lines, int headerIndex, Arguments arguments, List<BedrockObservation> out) {
        BedrockDimension dimension = arguments.dimension;
        BedrockRule floor = dimension.floor().orElseThrow(() ->
            new IllegalArgumentException("the " + dimension.shortName() + " has no random bedrock floor"));
        int lowest = floor.trueAtAndBelow();
        int highest = floor.falseAtAndAbove() - 1;
        int x0 = arguments.numbers.get(0);
        int z0 = arguments.numbers.get(1);
        int row = 0;
        int i = headerIndex + 1;
        for (; i < lines.size(); i++) {
            String text = lines.get(i).strip();
            if (text.startsWith("#")) {
                continue;
            }
            // Either full heights with a minus sign ("-60 -61 ?"), or one last digit per column ("01?2", "0 1 ? 2").
            boolean numbers = text.contains("-") && NUMBER_ROW.matcher(text).matches();
            String compact = text.replaceAll("[\\s,;]+", "");
            if (text.isEmpty() || !numbers && !DIGIT_ROW.matcher(compact).matches()) {
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
                    throw new LineException(i, "a top bedrock height of " + top + " is impossible; the " + dimension.shortName()
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
            String text = lines.get(i).strip();
            if (text.startsWith("#")) {
                continue;
            }
            String compact = text.replaceAll("\\s+", "");
            if (compact.isEmpty() || !LAYER_ROW.matcher(compact).matches()) {
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
        String[] tokens = text.replaceAll("[\\[\\]():]", " ").strip().split("[\\s,;]+");
        BedrockDimension dimension = null;
        List<Integer> numbers = new ArrayList<>();
        for (String token : tokens) {
            if (token.isEmpty() || token.equals("~")) {
                continue;
            }
            if (numbers.isEmpty() && dimension == null && !token.matches("-?\\d+")) {
                dimension = data.bedrockDimension(token).orElseThrow(() ->
                    new IllegalArgumentException("unknown dimension '" + token + "' (use overworld or nether)"));
                if (dimension.rules().isEmpty()) {
                    throw new IllegalArgumentException("the " + dimension.shortName() + " has no random bedrock");
                }
                continue;
            }
            try {
                numbers.add(Integer.parseInt(token));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + token + "' is not a whole number");
            }
        }
        if (dimension == null) {
            dimension = data.bedrockDimension("overworld").orElseThrow();
        }
        return new Arguments(dimension, numbers);
    }
}
