package io.github.jriiik.seedfinder.structure;

import io.github.jriiik.seedfinder.rng.JavaRandom;

/** How a random-spread structure picks its chunk inside its region. */
public enum SpreadType {
    /** Any chunk in the allowed area is equally likely. */
    LINEAR("linear"),
    /** Average of two rolls, so chunks near the middle of the region are more likely. */
    TRIANGULAR("triangular");

    private final String id;

    SpreadType(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public int evaluate(JavaRandom random, int limit) {
        return switch (this) {
            case LINEAR -> random.nextInt(limit);
            case TRIANGULAR -> (random.nextInt(limit) + random.nextInt(limit)) / 2;
        };
    }

    public static SpreadType byId(String id) {
        for (SpreadType type : values()) {
            if (type.id.equals(id)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown spread type: " + id);
    }
}
