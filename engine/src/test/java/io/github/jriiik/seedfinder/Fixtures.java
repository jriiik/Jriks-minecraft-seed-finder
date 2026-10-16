package io.github.jriiik.seedfinder;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.jriiik.seedfinder.version.MinecraftVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Loads the test fixtures that tools/oracle generated from the real game. Every supported version has its own
 * fixtures, and every test that compares with the game runs for each version ({@link #versions()}).
 */
public final class Fixtures {

    private static final Map<String, JsonObject> LOADED = new ConcurrentHashMap<>();

    private Fixtures() {
    }

    /** Every supported version: each must have fixtures from its own game (a missing file fails the tests). */
    public static Stream<MinecraftVersion> versions() {
        return Stream.of(MinecraftVersion.values());
    }

    /** A version's fixture, read once (tests must not change it). */
    public static JsonObject load(MinecraftVersion version, String name) {
        return LOADED.computeIfAbsent(version.displayName() + "/" + name, key -> load(version.displayName(), name));
    }

    public static JsonObject load(String version, String name) {
        String path = "/fixtures/" + version + "/" + name;
        try (InputStream in = Fixtures.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture " + path + " (run tools/oracle/run-oracle.sh " + version + ")");
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
