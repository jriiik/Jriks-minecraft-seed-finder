package io.github.jriiik.seedfinder;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Loads the test fixtures that tools/oracle generated from the real game. */
public final class Fixtures {

    private Fixtures() {
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
