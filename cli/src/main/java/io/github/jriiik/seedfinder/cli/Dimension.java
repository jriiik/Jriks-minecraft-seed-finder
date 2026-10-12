package io.github.jriiik.seedfinder.cli;

/** The dimensions as people call them, mapped to Minecraft's ids. */
enum Dimension {
    OVERWORLD("minecraft:overworld"),
    NETHER("minecraft:the_nether"),
    END("minecraft:the_end");

    private final String id;

    Dimension(String id) {
        this.id = id;
    }

    String id() {
        return id;
    }

    String displayName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    static String displayName(String id) {
        for (Dimension dimension : values()) {
            if (dimension.id.equals(id)) {
                return dimension.displayName();
            }
        }
        return id;
    }
}
