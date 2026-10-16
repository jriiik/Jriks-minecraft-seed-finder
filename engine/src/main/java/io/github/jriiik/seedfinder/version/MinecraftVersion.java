package io.github.jriiik.seedfinder.version;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/** The Minecraft Java Edition versions the engine supports, oldest first. */
public enum MinecraftVersion {
    V1_21_11("1.21.11"),
    V26_3("26.3");

    private final String name;
    private volatile VersionData data;

    MinecraftVersion(String name) {
        this.name = name;
    }

    /** The version as players write it, e.g. "26.3". */
    public String displayName() {
        return name;
    }

    /** The world generation data for this version (loaded once, on first use). */
    public VersionData data() {
        VersionData loaded = data;
        if (loaded == null) {
            synchronized (this) {
                loaded = data;
                if (loaded == null) {
                    loaded = VersionDataLoader.loadBundled(name);
                    data = loaded;
                }
            }
        }
        return loaded;
    }

    public static MinecraftVersion latest() {
        MinecraftVersion[] all = values();
        return all[all.length - 1];
    }

    public static Optional<MinecraftVersion> byName(String name) {
        return Arrays.stream(values()).filter(v -> v.name.equals(name.trim())).findFirst();
    }

    /** The supported versions as a list for messages, e.g. "1.21.11, 26.3". */
    public static String supportedNames() {
        return Arrays.stream(values()).map(MinecraftVersion::displayName).collect(Collectors.joining(", "));
    }
}
