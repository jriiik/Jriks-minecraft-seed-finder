package io.github.jriiik.seedfinder.recognize;

/**
 * The four ways Minecraft turns a structure piece. Jigsaw pieces are turned around the template's own origin
 * (block 0, 0, 0) and never mirrored; the height is not changed. Seen from above, with x to the east and z to the
 * south.
 */
public enum PieceRotation {
    NONE,
    CLOCKWISE_90,
    CLOCKWISE_180,
    COUNTERCLOCKWISE_90;

    /** The x offset of the template-local offset (x, z) after turning. */
    public int x(int x, int z) {
        return switch (this) {
            case NONE -> x;
            case CLOCKWISE_90 -> -z;
            case CLOCKWISE_180 -> -x;
            case COUNTERCLOCKWISE_90 -> z;
        };
    }

    /** The z offset of the template-local offset (x, z) after turning. */
    public int z(int x, int z) {
        return switch (this) {
            case NONE -> z;
            case CLOCKWISE_90 -> x;
            case CLOCKWISE_180 -> -z;
            case COUNTERCLOCKWISE_90 -> -x;
        };
    }
}
