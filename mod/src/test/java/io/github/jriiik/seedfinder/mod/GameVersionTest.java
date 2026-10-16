package io.github.jriiik.seedfinder.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.jriiik.seedfinder.version.MinecraftVersion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * Each version's project names its Minecraft version by hand ({@code GameVersion.NAME}, in mod/versions/&lt;version&gt;),
 * and the build knows it too (it passes it here). They must agree, or the mod would read a world with another version's
 * data; so must the Minecraft version the jar that ships asks Fabric for.
 */
class GameVersionTest {

    /** The Minecraft version the build makes this project's jar for. */
    private static String builtFor() {
        String version = System.getProperty("seedfinder.minecraftVersion");
        assertNotNull(version, "the build passes the Minecraft version it builds for");
        return version;
    }

    @Test
    void theModNamesTheVersionItIsBuiltFor() {
        assertEquals(builtFor(), GameVersion.NAME);
        assertTrue(MinecraftVersion.byName(GameVersion.NAME).isPresent(), "the engine has data for Minecraft " + GameVersion.NAME);
    }

    @Test
    void theJarAsksForTheVersionItIsBuiltFor() throws IOException {
        try (ZipFile zip = new ZipFile(NoNetworkGuardTest.modJar().toFile())) {
            JsonObject modJson = JsonParser.parseString(new String(zip.getInputStream(zip.getEntry("fabric.mod.json")).readAllBytes(),
                StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("~" + builtFor(), modJson.getAsJsonObject("depends").get("minecraft").getAsString());
        }
    }
}
