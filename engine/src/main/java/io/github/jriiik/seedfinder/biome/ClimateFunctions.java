package io.github.jriiik.seedfinder.biome;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.jriiik.seedfinder.rng.XoroshiroPositionalFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The six climate functions of one world preset (temperature, vegetation, continents, erosion, depth, ridges), read
 * from the density functions the oracle exported for 26.x and evaluated with float arithmetic in the same order as
 * the game's compiled samplers (26.x {@code DensityFunctionCompiler}). Only the function types the climate uses are
 * supported; anything else fails when the data is read, never silently.
 *
 * <p>The functions themselves do not depend on the seed: the noises they sample and their caches live in a
 * {@link FloatState}, one per world seed.
 */
final class ClimateFunctions implements ClimateEvaluator {

    private final Node[] climate;
    private final List<NormalNoise.Recipe> noises;
    private final Map<String, NormalNoise.Recipe> noisesById;
    private final int cacheSlots;

    private ClimateFunctions(Node[] climate, Map<String, NormalNoise.Recipe> noisesById, int cacheSlots) {
        this.climate = climate;
        this.noises = List.copyOf(noisesById.values());
        this.noisesById = Map.copyOf(noisesById);
        this.cacheSlots = cacheSlots;
    }

    @Override
    public double noiseValue(String id, XoroshiroPositionalFactory world, double x, double y, double z) {
        NormalNoise.Recipe recipe = noisesById.get(id);
        if (recipe == null) {
            throw new IllegalArgumentException("the climate does not use the noise " + id);
        }
        return recipe.create(world).get(x, y, z);
    }

    /**
     * Reads a preset's climate functions.
     *
     * @param router    the preset's six router fields (each a function or the id of one)
     * @param functions every density function the router reaches, by id
     * @param noises    the noise parameters, by id
     */
    static ClimateFunctions parse(JsonObject router, JsonObject functions, JsonObject noises) {
        Parser parser = new Parser(functions, noises);
        Node[] climate = new Node[FIELDS.length];
        for (int i = 0; i < FIELDS.length; i++) {
            JsonElement field = router.get(FIELDS[i]);
            if (field == null) {
                throw new IllegalArgumentException("climate router has no " + FIELDS[i]);
            }
            climate[i] = parser.parse(field);
        }
        return new ClimateFunctions(climate, parser.noiseRecipes, parser.cacheSlots);
    }

    @Override
    public FloatState newState() {
        return new FloatState(this);
    }

    @Override
    public boolean dependsOnY(int function) {
        return climate[function].dependsOnY;
    }

    /** The noises and caches of one world seed, see {@link ClimateEvaluator.State}. */
    static final class FloatState implements ClimateEvaluator.State {

        private final ClimateFunctions functions;
        private final NormalNoise[] noises;
        private final boolean[] cacheFilled;
        private final int[] cacheX;
        private final int[] cacheY;
        private final int[] cacheZ;
        private final float[] cacheValue;
        private XoroshiroPositionalFactory world;

        private FloatState(ClimateFunctions functions) {
            this.functions = functions;
            this.noises = new NormalNoise[functions.noises.size()];
            this.cacheFilled = new boolean[functions.cacheSlots];
            this.cacheX = new int[functions.cacheSlots];
            this.cacheY = new int[functions.cacheSlots];
            this.cacheZ = new int[functions.cacheSlots];
            this.cacheValue = new float[functions.cacheSlots];
        }

        @Override
        public void reset(XoroshiroPositionalFactory world) {
            this.world = world;
            Arrays.fill(noises, null);
            Arrays.fill(cacheFilled, false);
        }

        @Override
        public float climate(int function, int x, int y, int z) {
            return functions.climate[function].compute(this, x, y, z);
        }

        @Override
        public double exact(int function, int x, int y, int z) {
            return climate(function, x, y, z);
        }

        NormalNoise noise(int index) {
            NormalNoise noise = noises[index];
            if (noise == null) {
                if (world == null) {
                    throw new IllegalStateException("no world seed set");
                }
                noise = functions.noises.get(index).create(world);
                noises[index] = noise;
            }
            return noise;
        }
    }

    // ------------------------------------------------------------------ the function types

    private abstract static class Node {

        final boolean dependsOnY;

        Node(boolean dependsOnY) {
            this.dependsOnY = dependsOnY;
        }

        abstract float compute(FloatState state, int x, int y, int z);

        boolean isConstant() {
            return false;
        }

        float constantValue() {
            throw new IllegalStateException("not a constant");
        }
    }

    private static final class Constant extends Node {

        private final float value;

        Constant(float value) {
            super(false);
            this.value = value;
        }

        @Override
        float compute(FloatState state, int x, int y, int z) {
            return value;
        }

        @Override
        boolean isConstant() {
            return true;
        }

        @Override
        float constantValue() {
            return value;
        }
    }

    /** Remembers the last value, like the game's cache; values never depend on it, only speed does. */
    private static final class Cache extends Node {

        private final Node input;
        private final int slot;

        Cache(Node input, int slot) {
            super(input.dependsOnY);
            this.input = input;
            this.slot = slot;
        }

        @Override
        float compute(FloatState state, int x, int y, int z) {
            if (state.cacheFilled[slot] && state.cacheX[slot] == x && state.cacheZ[slot] == z && (!dependsOnY || state.cacheY[slot] == y)) {
                return state.cacheValue[slot];
            }
            float value = input.compute(state, x, y, z);
            state.cacheFilled[slot] = true;
            state.cacheX[slot] = x;
            state.cacheY[slot] = y;
            state.cacheZ[slot] = z;
            state.cacheValue[slot] = value;
            return value;
        }
    }

    /** {@code NoiseFunction}: the plain sampler, or the shifted one when any shift is not the constant 0. */
    private static final class Noise extends Node {

        private final int noise;
        private final double xzScale;
        private final double yScale;
        private final Node shiftX;
        private final Node shiftY;
        private final Node shiftZ;

        Noise(int noise, double xzScale, double yScale, Node shiftX, Node shiftY, Node shiftZ) {
            super(yScale != 0.0 || shiftX != null && shiftX.dependsOnY || shiftY != null && shiftY.dependsOnY || shiftZ != null && shiftZ.dependsOnY);
            this.noise = noise;
            this.xzScale = xzScale;
            this.yScale = yScale;
            this.shiftX = shiftX;
            this.shiftY = shiftY;
            this.shiftZ = shiftZ;
        }

        @Override
        float compute(FloatState state, int x, int y, int z) {
            if (shiftX == null && shiftY == null && shiftZ == null) {
                return state.noise(noise).get(x * xzScale, y * yScale, z * xzScale);
            }
            double noiseX = x * xzScale + (shiftX == null ? 0.0F : shiftX.compute(state, x, y, z));
            double noiseY = shiftY == null ? y * yScale : y * yScale + shiftY.compute(state, x, y, z);
            double noiseZ = z * xzScale + (shiftZ == null ? 0.0F : shiftZ.compute(state, x, y, z));
            return state.noise(noise).get(noiseX, noiseY, noiseZ);
        }
    }

    /** {@code shift}, {@code shift_a} and {@code shift_b}: the offset noise at a quarter of the scale, times 4. */
    private static final class Shift extends Node {

        enum Kind { XYZ, A, B }

        private final int noise;
        private final Kind kind;

        Shift(int noise, Kind kind) {
            super(kind == Kind.XYZ);
            this.noise = noise;
            this.kind = kind;
        }

        @Override
        float compute(FloatState state, int x, int y, int z) {
            NormalNoise offset = state.noise(noise);
            return switch (kind) {
                case XYZ -> offset.get(x * 0.25, y * 0.25, z * 0.25) * 4.0F;
                case A -> offset.get(x * 0.25, y * 0.0, z * 0.25) * 4.0F;
                case B -> offset.get(z * 0.25, x * 0.25, 0.0) * 4.0F;
            };
        }
    }

    /** {@code add}, {@code sub} and {@code mul}, with the game's special cases for a constant side. */
    private static final class Binary extends Node {

        enum Kind { ADD, SUB, MUL }

        private final Kind kind;
        private final Node left;
        private final Node right;

        Binary(Kind kind, Node left, Node right) {
            super(left.dependsOnY || right.dependsOnY);
            this.kind = kind;
            this.left = left;
            this.right = right;
        }

        @Override
        float compute(FloatState state, int x, int y, int z) {
            switch (kind) {
                case ADD:
                    if (left.isConstant()) {
                        return right.compute(state, x, y, z) + left.constantValue();
                    }
                    if (right.isConstant()) {
                        return left.compute(state, x, y, z) + right.constantValue();
                    }
                    return left.compute(state, x, y, z) + right.compute(state, x, y, z);
                case SUB:
                    if (left.isConstant()) {
                        return left.constantValue() - right.compute(state, x, y, z);
                    }
                    if (right.isConstant()) {
                        return left.compute(state, x, y, z) + -right.constantValue();
                    }
                    return left.compute(state, x, y, z) - right.compute(state, x, y, z);
                default:
                    if (left.isConstant()) {
                        return right.compute(state, x, y, z) * left.constantValue();
                    }
                    if (right.isConstant()) {
                        return left.compute(state, x, y, z) * right.constantValue();
                    }
                    float value = left.compute(state, x, y, z);
                    return value == 0.0F ? 0.0F : value * right.compute(state, x, y, z);
            }
        }
    }

    private static final class Abs extends Node {

        private final Node input;

        Abs(Node input) {
            super(input.dependsOnY);
            this.input = input;
        }

        @Override
        float compute(FloatState state, int x, int y, int z) {
            return Math.abs(input.compute(state, x, y, z));
        }
    }

    /** {@code lerp}: the first value at alpha 0, the second at alpha 1, a float lerp in between. */
    private static final class Lerp extends Node {

        private final Node alpha;
        private final Node first;
        private final Node second;

        Lerp(Node alpha, Node first, Node second) {
            super(alpha.dependsOnY || first.dependsOnY || second.dependsOnY);
            this.alpha = alpha;
            this.first = first;
            this.second = second;
        }

        @Override
        float compute(FloatState state, int x, int y, int z) {
            float a = alpha.compute(state, x, y, z);
            if (a == 0.0F) {
                return first.compute(state, x, y, z);
            }
            if (a == 1.0F) {
                return second.compute(state, x, y, z);
            }
            return PerlinNoise.lerp(a, first.compute(state, x, y, z), second.compute(state, x, y, z));
        }
    }

    /** {@code gradient} along y, clamped to its two ends. */
    private static final class Gradient extends Node {

        private final int fromY;
        private final int minY;
        private final int maxY;
        private final float fromValue;
        private final float factor;

        Gradient(int fromY, int toY, float fromValue, float toValue) {
            super(true);
            this.fromY = fromY;
            this.minY = Math.min(fromY, toY);
            this.maxY = Math.max(fromY, toY);
            this.fromValue = fromValue;
            this.factor = (toValue - fromValue) / (toY - fromY);
        }

        @Override
        float compute(FloatState state, int x, int y, int z) {
            int relative = Math.min(Math.max(y, minY), maxY) - fromY;
            return fromValue + relative * factor;
        }
    }

    /** {@code spline}: a cubic spline whose point values may be splines themselves (Minecraft's {@code CubicSpline}). */
    private static final class Spline extends Node {

        private final Node coordinate;
        private final float[] locations;
        private final float[] derivatives;
        private final Node[] values;

        Spline(Node coordinate, float[] locations, float[] derivatives, Node[] values) {
            super(coordinate.dependsOnY || anyDependsOnY(values));
            this.coordinate = coordinate;
            this.locations = locations;
            this.derivatives = derivatives;
            this.values = values;
        }

        private static boolean anyDependsOnY(Node[] nodes) {
            for (Node node : nodes) {
                if (node.dependsOnY) {
                    return true;
                }
            }
            return false;
        }

        @Override
        float compute(FloatState state, int x, int y, int z) {
            float input = coordinate.compute(state, x, y, z);
            // Mth.binarySearch(0, n, i -> input < locations[i]) - 1
            int from = 0;
            int length = locations.length;
            while (length > 0) {
                int half = length / 2;
                int middle = from + half;
                if (input < locations[middle]) {
                    length = half;
                } else {
                    from = middle + 1;
                    length -= half + 1;
                }
            }
            int start = from - 1;
            int last = locations.length - 1;
            if (start < 0) {
                return linearExtend(input, values[0].compute(state, x, y, z), 0);
            }
            if (start == last) {
                return linearExtend(input, values[last].compute(state, x, y, z), last);
            }
            float x1 = locations[start];
            float x2 = locations[start + 1];
            float t = (input - x1) / (x2 - x1);
            float d1 = derivatives[start];
            float d2 = derivatives[start + 1];
            float y1 = values[start].compute(state, x, y, z);
            float y2 = values[start + 1].compute(state, x, y, z);
            float a = d1 * (x2 - x1) - (y2 - y1);
            float b = -d2 * (x2 - x1) + (y2 - y1);
            return PerlinNoise.lerp(t, y1, y2) + t * (1.0F - t) * PerlinNoise.lerp(t, a, b);
        }

        private float linearExtend(float input, float value, int index) {
            float derivative = derivatives[index];
            return derivative == 0.0F ? value : value + derivative * (input - locations[index]);
        }
    }

    // ------------------------------------------------------------------ reading the oracle's export

    private static final class Parser {

        private final JsonObject functions;
        private final JsonObject noises;
        private final Map<String, Node> references = new HashMap<>();
        private final Map<String, NormalNoise.Recipe> noiseRecipes = new LinkedHashMap<>();
        private final Map<String, Integer> noiseIndexes = new HashMap<>();
        private int cacheSlots;

        Parser(JsonObject functions, JsonObject noises) {
            this.functions = functions;
            this.noises = noises;
        }

        Node parse(JsonElement element) {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
                return new Constant(element.getAsFloat());
            }
            if (element.isJsonPrimitive()) {
                return reference(element.getAsString());
            }
            JsonObject object = element.getAsJsonObject();
            String type = object.get("type").getAsString();
            switch (type) {
                case "minecraft:constant":
                    return new Constant(object.get("value").getAsFloat());
                case "minecraft:cache":
                    return new Cache(parse(object.get("input")), cacheSlots++);
                case "minecraft:noise":
                    return new Noise(noise(object.get("noise").getAsString()), object.get("xz_scale").getAsDouble(), object.get("y_scale").getAsDouble(),
                        shift(object, "shift_x"), shift(object, "shift_y"), shift(object, "shift_z"));
                case "minecraft:shift":
                    return new Shift(noise(object.get("noise").getAsString()), Shift.Kind.XYZ);
                case "minecraft:shift_a":
                    return new Shift(noise(object.get("noise").getAsString()), Shift.Kind.A);
                case "minecraft:shift_b":
                    return new Shift(noise(object.get("noise").getAsString()), Shift.Kind.B);
                case "minecraft:add":
                    return new Binary(Binary.Kind.ADD, parse(object.get("left")), parse(object.get("right")));
                case "minecraft:sub":
                    return new Binary(Binary.Kind.SUB, parse(object.get("left")), parse(object.get("right")));
                case "minecraft:mul":
                    return new Binary(Binary.Kind.MUL, parse(object.get("left")), parse(object.get("right")));
                case "minecraft:abs":
                    return new Abs(parse(object.get("input")));
                case "minecraft:lerp":
                    return new Lerp(parse(object.get("alpha")), parse(object.get("first")), parse(object.get("second")));
                case "minecraft:blend_alpha":
                    // Only chunks next to chunks of older versions are blended; for every other chunk alpha is 1.
                    return new Constant(1.0F);
                case "minecraft:blend_offset":
                    return new Constant(0.0F);
                case "minecraft:gradient":
                    return gradient(object);
                case "minecraft:spline":
                    return spline(object.get("spline"));
                default:
                    throw new IllegalArgumentException("unsupported density function type " + type);
            }
        }

        private Node reference(String id) {
            Node node = references.get(id);
            if (node == null) {
                JsonElement definition = functions.get(id);
                if (definition == null) {
                    throw new IllegalArgumentException("unknown density function " + id);
                }
                node = parse(definition);
                references.put(id, node);
            }
            return node;
        }

        /** A shift that is missing or the constant 0 counts as no shift, as in the game. */
        private Node shift(JsonObject object, String field) {
            if (!object.has(field)) {
                return null;
            }
            Node shift = parse(object.get(field));
            return shift.isConstant() && shift.constantValue() == 0.0F ? null : shift;
        }

        private Node gradient(JsonObject object) {
            String axis = object.get("axis").getAsString();
            String tiling = object.has("tiling") ? object.get("tiling").getAsString() : "clamp_to_edge";
            if (!axis.equals("y") || !tiling.equals("clamp_to_edge")) {
                throw new IllegalArgumentException("unsupported gradient: axis " + axis + ", tiling " + tiling);
            }
            int from = object.get("from_coordinate").getAsInt();
            int to = object.get("to_coordinate").getAsInt();
            if (from == to) {
                throw new IllegalArgumentException("gradient from and to are equal");
            }
            return new Gradient(from, to, object.get("from_value").getAsFloat(), object.get("to_value").getAsFloat());
        }

        private Node spline(JsonElement element) {
            if (element.isJsonPrimitive()) {
                return new Constant(element.getAsFloat());
            }
            JsonObject object = element.getAsJsonObject();
            Node coordinate = parse(object.get("coordinate"));
            JsonArray points = object.getAsJsonArray("points");
            if (points.isEmpty()) {
                throw new IllegalArgumentException("spline without points");
            }
            float[] locations = new float[points.size()];
            float[] derivatives = new float[points.size()];
            Node[] values = new Node[points.size()];
            for (int i = 0; i < points.size(); i++) {
                JsonObject point = points.get(i).getAsJsonObject();
                locations[i] = point.get("location").getAsFloat();
                derivatives[i] = point.get("derivative").getAsFloat();
                values[i] = spline(point.get("value"));
            }
            return new Spline(coordinate, locations, derivatives, values);
        }

        private int noise(String id) {
            Integer index = noiseIndexes.get(id);
            if (index == null) {
                JsonElement definition = noises.get(id);
                if (definition == null) {
                    throw new IllegalArgumentException("unknown noise " + id);
                }
                noiseRecipes.put(id, recipe(id, definition.getAsJsonObject()));
                index = noiseIndexes.size();
                noiseIndexes.put(id, index);
            }
            return index;
        }

        /** {@code NormalNoise.Parameters} with the codec's defaults; the old "legacy" normalization is not supported. */
        private static NormalNoise.Recipe recipe(String id, JsonObject parameters) {
            boolean normalize = true;
            if (parameters.has("normalize")) {
                JsonElement value = parameters.get("normalize");
                if (!value.getAsJsonPrimitive().isBoolean()) {
                    throw new IllegalArgumentException(id + ": unsupported normalization " + value);
                }
                normalize = value.getAsBoolean();
            }
            List<Double> modifiers = new ArrayList<>();
            if (parameters.has("amplitude_modifiers")) {
                parameters.getAsJsonArray("amplitude_modifiers").forEach(modifier -> modifiers.add(modifier.getAsDouble()));
            }
            return new NormalNoise.Recipe(id, parameters.has("base_amplitude") ? parameters.get("base_amplitude").getAsDouble() : 1.0,
                parameters.get("base_octave").getAsInt(), parameters.has("octave_count") ? parameters.get("octave_count").getAsInt() : 1, normalize,
                modifiers.stream().mapToDouble(Double::doubleValue).toArray());
        }
    }
}
