package io.github.jriiik.seedfinder.cli;

import io.github.jriiik.seedfinder.finder.ObservationReader;
import io.github.jriiik.seedfinder.version.MinecraftVersion;
import io.github.jriiik.seedfinder.version.VersionData;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import picocli.CommandLine;

/**
 * Observations from a file and from the command line, with error messages that point at the line.
 *
 * @param lines    the file's lines followed by the command-line arguments
 * @param fromFile how many of the lines come from the file
 */
record ObservationInput(Path file, List<String> lines, int fromFile) {

    /** Lines that belong to bedrock notes, for which the structure hint would not help. */
    private static final Pattern BEDROCK_LINE = Pattern.compile("(?i)\\s*((not[- ]|no[- ])?bedrock.*|[0-9?bB.,;\\s-]+)");
    /**
     * Biome, preset, hashed-seed and Minecraft version lines ("minecraft 26.3", "minecraft: 1.20.1"): not structures
     * either. The version starts with a digit, unlike a structure name such as "minecraft:igloo".
     */
    private static final Pattern OTHER_NOTE = Pattern.compile(
        "(?i)\\s*(biome|(world[- ])?preset|hashed[- ]seed|minecraft([- ]version)?[\\s:=]+\\d).*");
    /** A row of a biome grid (letters of its legend). */
    private static final Pattern GRID_ROW = Pattern.compile("[A-Za-z?\\s]+");

    static ObservationInput of(CommandLine commandLine, Path file, List<String> arguments) {
        List<String> lines = new ArrayList<>();
        if (file != null) {
            try {
                lines.addAll(decode(Files.readAllBytes(file)).lines().toList());
            } catch (IOException e) {
                throw new CommandLine.ParameterException(commandLine, "Can't read the file " + file + " (" + e.getMessage() + ")");
            }
        }
        int fromFile = lines.size();
        lines.addAll(arguments);
        return new ObservationInput(file, lines, fromFile);
    }

    /**
     * Text files come in several encodings: UTF-8 (with or without the marker Notepad adds), UTF-16 (what Windows
     * PowerShell writes when output is saved with '>'), or the old Windows encoding.
     */
    static String decode(byte[] bytes) {
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        int start = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, start, bytes.length - start))
                .toString();
        } catch (CharacterCodingException notUtf8) {
            return new String(bytes, Charset.forName("windows-1252"));
        }
    }

    /** True when the line is one of the rows below a biome-grid line. */
    private boolean isGridRow(int index) {
        int i = index;
        while (i > 0 && GRID_ROW.matcher(lines.get(i)).matches()) {
            i--;
        }
        return i < index && lines.get(i).strip().toLowerCase(java.util.Locale.ROOT).startsWith("biome");
    }

    boolean isEmpty() {
        return lines.stream().allMatch(String::isBlank);
    }

    /** The Minecraft version the notes name in a 'minecraft &lt;version&gt;' line, if any. */
    Optional<MinecraftVersion> minecraftVersion(CommandLine commandLine) {
        try {
            return ObservationReader.minecraftVersion(lines);
        } catch (ObservationReader.LineException e) {
            throw problem(commandLine, e);
        }
    }

    ObservationReader.Observations read(CommandLine commandLine, VersionData data) {
        try {
            return ObservationReader.read(data, lines);
        } catch (ObservationReader.LineException e) {
            throw problem(commandLine, e);
        }
    }

    /** The mistake with where it is: the line of the file or the argument. */
    private CommandLine.ParameterException problem(CommandLine commandLine, ObservationReader.LineException e) {
        int i = e.lineIndex();
        String line = lines.get(i);
        String where = i < fromFile ? "line " + (i + 1) + " of " + file : "'" + line + "'";
        String hint = BEDROCK_LINE.matcher(line).matches() || OTHER_NOTE.matcher(line).matches() || isGridRow(i) ? ""
            : ". Write each structure as its name and the block x and z of its start chunk, like igloo:48,208";
        return new CommandLine.ParameterException(commandLine, where + ": " + e.getMessage() + hint + ".");
    }
}
