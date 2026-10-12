package io.github.jriiik.seedfinder.world;

/** A block position, also used for small offsets such as a structure's locate offset. */
public record BlockPos(int x, int y, int z) {

    public static final BlockPos ZERO = new BlockPos(0, 0, 0);

    public BlockPos offset(BlockPos other) {
        return new BlockPos(x + other.x, y + other.y, z + other.z);
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }
}
