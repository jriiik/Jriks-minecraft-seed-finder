package io.github.jriiik.seedfinder.bedrock;

/** One block seen in a world's bedrock layers: whether the block at (x, y, z) of a dimension is bedrock. */
public record BedrockObservation(BedrockDimension dimension, int x, int y, int z, boolean bedrock) {

    @Override
    public String toString() {
        return (bedrock ? "bedrock" : "no bedrock") + " at (" + x + ", " + y + ", " + z + ")"
            + (dimension.id().equals("minecraft:overworld") ? "" : " in the " + dimension.displayName());
    }
}
