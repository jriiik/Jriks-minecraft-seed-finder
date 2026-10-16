package io.github.jriiik.seedfinder.biome;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.jriiik.seedfinder.rng.XoroshiroPositionalFactory;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The six climate functions of one world preset as Minecraft 1.21.x computes them: its density functions
 * ({@code DensityFunctions}) in double arithmetic, read from the classic data format the oracle exported for 1.21.x
 * ({@code argument1}/{@code argument2}, {@code shifted_noise}, {@code flat_cache} ...). Splines compute in float from
 * their coordinate cast to float, as the game's {@code CubicSpline} does. Only the function types the climate uses
 * are supported; anything else fails when the data is read, never silently.
 *
 * <p>The functions are evaluated as the game's climate sampler evaluates them: cache markers only remember values,
 * an add or a multiplication with a constant side is the game's {@code MulOrAdd} (no shortcut for 0), and blending is
 * off (alpha 1, offset 0), as for every chunk that is not next to chunks of an older version.
 */
final class ClassicClimateFunctions implements ClimateEvaluator {

    private final Node[] climate;
    private final List<ClassicNormalNoise.Recipe> noises;
    private final Map<String, ClassicNormalNoise.Recipe> noisesById;
    private final int cacheSlots;

    private ClassicClimateFunctions(Node[] climate, Map<String, ClassicNormalNoise.Recipe> noisesById, int cacheSlots) {
        this.climate = climate;
        this.noises = List.copyOf(noisesById.values());
        this.noisesById = Map.copyOf(noisesById);
        this.cacheSlots = cacheSlots;
    }

    /**
     * Reads a preset's climate functions.
     *
     * @param router    the preset's six router fields (each a function or the id of one)
     * @param functions every density function the router reaches, by id
     * @param noises    the noise parameters, by id
     */
    static ClassicClimateFunctions parse(JsonObject router, JsonObject functions, JsonObject noises) {
        Parser parser = new Parser(functions, noises);
        Node[] climate = new Node[FIELDS.length];
        for (int i = 0; i < FIELDS.length; i++) {
            JsonElement field = router.get(FIELDS[i]);
            if (field == null) {
                throw new IllegalArgumentException("climate router has no " + FIELDS[i]);
            }
            climate[i] = parser.parse(field);
        }
        return new ClassicClimateFunctions(climate, parser.noiseRecipes, parser.cacheSlots);
    }

    @Override
    public DoubleState newState() {
        return new DoubleState(this);
    }

    @Override
    public boolean dependsOnY(int function) {
        return climate[function].dependsOnY;
    }

    @Override
    public double noiseValue(String id, XoroshiroPositionalFactory world, double x, double y, double z) {
        ClassicNormalNoise.Recipe recipe = noisesById.get(id);
        if (recipe == null) {
            throw new IllegalArgumentException("the climate does not use the noise " + id);
        }
        return recipe.create(world).get(x, y, z);
    }

    /** The noises and caches of one world seed, see {@link ClimateEvaluator.State}. */
    static final class DoubleState implements ClimateEvaluator.State {

        private final ClassicClimateFunctions functions;
        private final ClassicNormalNoise[] noises;
        private final boolean[] cacheFilled;
        private final int[] cacheX;
        private final int[] cacheY;
        private final int[] cacheZ;
        private final double[] cacheValue;
        private XoroshiroPositionalFactory world;

        private DoubleState(ClassicClimateFunctions functions) {
            this.functions = functions;
            this.noises = new ClassicNormalNoise[functions.noises.size()];
            this.cacheFilled = new boolean[functions.cacheSlots];
            this.cacheX = new int[functions.cacheSlots];
            this.cacheY = new int[functions.cacheSlots];
            this.cacheZ = new int[functions.cacheSlots];
            this.cacheValue = new double[functions.cacheSlots];
        }

        @Override
        public void reset(XoroshiroPositionalFactory world) {
            this.world = world;
            Arrays.fill(noises, null);
            Arrays.fill(cacheFilled, false);
        }

        @Override
        public float climate(int function, int x, int y, int z) {
            return (float) exact(function, x, y, z);
        }

        @Override
        public double exact(int function, int x, int y, int z) {
            return functions.climate[function].compute(this, x, y, z);
        }

        ClassicNormalNoise noise(int index) {
            ClassicNormalNoise noise = noises[index];
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

        abstract double compute(DoubleState state, int x, int y, int z);
    }

    private static final class Constant extends Node {

        private final double value;

        Constant(double value) {
            super(false);
            this.value = value;
        }

        @Override
        double compute(DoubleState state, int x, int y, int z) {
            return value;
        }
    }

    /**
     * {@code blend_alpha} (1) and {@code blend_offset} (0): blending changes only chunks next to chunks of older
     * versions. To the game these are not constants, so arithmetic with them keeps its general form.
     */
    private static final class Blending extends Node {

        private final double value;

        Blending(double value) {
            super(false);
            this.value = value;
        }

        @Override
        double compute(DoubleState state, int x, int y, int z) {
            return value;
        }
    }

    /** {@code flat_cache} and {@code cache_2d}: remembers the last value; values never depend on it, only speed does. */
    private static final class Cache extends Node {

        private final Node input;
        private final int slot;

        Cache(Node input, int slot) {
            super(input.dependsOnY);
            this.input = input;
            this.slot = slot;
        }

        @Override
        double compute(DoubleState state, int x, int y, int z) {
            if (state.cacheFilled[slot] && state.cacheX[slot] == x && state.cacheZ[slot] == z && (!dependsOnY || state.cacheY[slot] == y)) {
                return state.cacheValue[slot];
            }
            double value = input.compute(state, x, y, z);
            state.cacheFilled[slot] = true;
            state.cacheX[slot] = x;
            state.cacheY[slot] = y;
            state.cacheZ[slot] = z;
            state.cacheValue[slot] = value;
            return value;
        }
    }

    /** {@code shifted_noise}: the noise at the scaled block position plus the three shifts. */
    private static final class ShiftedNoise extends Node {

        private final int noise;
        private final double xzScale;
        private final double yScale;
        private final Node shiftX;
        private final Node shiftY;
        private final Node shiftZ;

        ShiftedNoise(int noise, double xzScale, double yScale, Node shiftX, Node shiftY, Node shiftZ) {
            super(yScale != 0.0 || shiftX.dependsOnY || shiftY.dependsOnY || shiftZ.dependsOnY);
            this.noise = noise;
            this.xzScale = xzScale;
            this.yScale = yScale;
            this.shiftX = shiftX;
            this.shiftY = shiftY;
            this.shiftZ = shiftZ;
        }

        @Override
        double compute(DoubleState state, int x, int y, int z) {
            double noiseX = x * xzScale + shiftX.compute(state, x, y, z);
            double noiseY = y * yScale + shiftY.compute(state, x, y, z);
            double noiseZ = z * xzScale + shiftZ.compute(state, x, y, z);
            return state.noise(noise).get(noiseX, noiseY, noiseZ);
        }
    }

    /** {@code shift_a} and {@code shift_b}: the offset noise at a quarter of the scale (y left at 0), times 4. */
    private static final class Shift extends Node {

        enum Kind { A, B }

        private final int noise;
        private final Kind kind;

        Shift(int noise, Kind kind) {
            super(false);
            this.noise = noise;
            this.kind = kind;
        }

        @Override
        double compute(DoubleState state, int x, int y, int z) {
            ClassicNormalNoise offset = state.noise(noise);
            return switch (kind) {
                case A -> offset.get(x * 0.25, 0.0, z * 0.25) * 4.0;
                case B -> offset.get(z * 0.25, x * 0.25, 0.0) * 4.0;
            };
        }
    }

    private enum Arithmetic { ADD, MUL }

    /** {@code add} and {@code mul} of two functions ({@code Ap2}): a multiplication skips its right side when the left is 0. */
    private static final class TwoArguments extends Node {

        private final Arithmetic kind;
        private final Node left;
        private final Node right;

        TwoArguments(Arithmetic kind, Node left, Node right) {
            super(left.dependsOnY || right.dependsOnY);
            this.kind = kind;
            this.left = left;
            this.right = right;
        }

        @Override
        double compute(DoubleState state, int x, int y, int z) {
            double value = left.compute(state, x, y, z);
            return switch (kind) {
                case ADD -> value + right.compute(state, x, y, z);
                case MUL -> value == 0.0 ? 0.0 : value * right.compute(state, x, y, z);
            };
        }
    }

    /** {@code add} and {@code mul} with a constant side ({@code MulOrAdd}). */
    private static final class MulOrAdd extends Node {

        private final Arithmetic kind;
        private final Node input;
        private final double argument;

        MulOrAdd(Arithmetic kind, Node input, double argument) {
            super(input.dependsOnY);
            this.kind = kind;
            this.input = input;
            this.argument = argument;
        }

        @Override
        double compute(DoubleState state, int x, int y, int z) {
            double value = input.compute(state, x, y, z);
            return switch (kind) {
                case ADD -> value + argument;
                case MUL -> value * argument;
            };
        }
    }

    private static final class Abs extends Node {

        private final Node input;

        Abs(Node input) {
            super(input.dependsOnY);
            this.input = input;
        }

        @Override
        double compute(DoubleState state, int x, int y, int z) {
            return Math.abs(input.compute(state, x, y, z));
        }
    }

    /** {@code y_clamped_gradient}: {@code Mth.clampedMap} of the block's y. */
    private static final class Gradient extends Node {

        private final double fromY;
        private final double toY;
        private final double fromValue;
        private final double toValue;

        Gradient(int fromY, int toY, double fromValue, double toValue) {
            super(true);
            this.fromY = fromY;
            this.toY = toY;
            this.fromValue = fromValue;
            this.toValue = toValue;
        }

        @Override
        double compute(DoubleState state, int x, int y, int z) {
            double t = (y - fromY) / (toY - fromY);
            if (t < 0.0) {
                return fromValue;
            }
            return t > 1.0 ? toValue : fromValue + t * (toValue - fromValue);
        }
    }

    /** {@code spline}: a float spline, widened to double. */
    private static final class SplineFunction extends Node {

        private final SplineValue spline;

        SplineFunction(SplineValue spline) {
            super(spline.dependsOnY());
            this.spline = spline;
        }

        @Override
        double compute(DoubleState state, int x, int y, int z) {
            return spline.apply(state, x, y, z);
        }
    }

    /** A point value of a spline: a float or a spline itself (Minecraft's {@code CubicSpline}). */
    private abstract static class SplineValue {

        abstract float apply(DoubleState state, int x, int y, int z);

        abstract boolean dependsOnY();
    }

    private static final class ConstantSpline extends SplineValue {

        private final float value;

        ConstantSpline(float value) {
            this.value = value;
        }

        @Override
        float apply(DoubleState state, int x, int y, int z) {
            return value;
        }

        @Override
        boolean dependsOnY() {
            return false;
        }
    }

    private static final class Multipoint extends SplineValue {

        private final Node coordinate;
        private final float[] locations;
        private final float[] derivatives;
        private final SplineValue[] values;

        Multipoint(Node coordinate, float[] locations, float[] derivatives, SplineValue[] values) {
            this.coordinate = coordinate;
            this.locations = locations;
            this.derivatives = derivatives;
            this.values = values;
        }

        @Override
        boolean dependsOnY() {
            if (coordinate.dependsOnY) {
                return true;
            }
            for (SplineValue value : values) {
                if (value.dependsOnY()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        float apply(DoubleState state, int x, int y, int z) {
            float input = (float) coordinate.compute(state, x, y, z);
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
                return linearExtend(input, values[0].apply(state, x, y, z), 0);
            }
            if (start == last) {
                return linearExtend(input, values[last].apply(state, x, y, z), last);
            }
            float x1 = locations[start];
            float x2 = locations[start + 1];
            float t = (input - x1) / (x2 - x1);
            float d1 = derivatives[start];
            float d2 = derivatives[start + 1];
            float y1 = values[start].apply(state, x, y, z);
            float y2 = values[start + 1].apply(state, x, y, z);
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
        private final Map<String, ClassicNormalNoise.Recipe> noiseRecipes = new LinkedHashMap<>();
        private final Map<String, Integer> noiseIndexes = new HashMap<>();
        private int cacheSlots;

        Parser(JsonObject functions, JsonObject noises) {
            this.functions = functions;
            this.noises = noises;
        }

        Node parse(JsonElement element) {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
                return new Constant(element.getAsDouble());
            }
            if (element.isJsonPrimitive()) {
                return reference(element.getAsString());
            }
            JsonObject object = element.getAsJsonObject();
            String type = field(object, "type").getAsString();
            switch (type) {
                case "minecraft:flat_cache":
                case "minecraft:cache_2d":
                    return cache(parse(field(object, "argument")));
                case "minecraft:cache_once":
                    // caches only inside a chunk's interpolation loop; a climate point computes its input
                    return parse(field(object, "argument"));
                case "minecraft:add":
                    return arithmetic(Arithmetic.ADD, parse(field(object, "argument1")), parse(field(object, "argument2")));
                case "minecraft:mul":
                    return arithmetic(Arithmetic.MUL, parse(field(object, "argument1")), parse(field(object, "argument2")));
                case "minecraft:abs":
                    return new Abs(parse(field(object, "argument")));
                case "minecraft:shifted_noise":
                    return new ShiftedNoise(noise(field(object, "noise")), field(object, "xz_scale").getAsDouble(),
                        field(object, "y_scale").getAsDouble(), parse(field(object, "shift_x")), parse(field(object, "shift_y")),
                        parse(field(object, "shift_z")));
                case "minecraft:shift_a":
                    return new Shift(noise(field(object, "argument")), Shift.Kind.A);
                case "minecraft:shift_b":
                    return new Shift(noise(field(object, "argument")), Shift.Kind.B);
                case "minecraft:blend_alpha":
                    return new Blending(1.0);
                case "minecraft:blend_offset":
                    return new Blending(0.0);
                case "minecraft:y_clamped_gradient":
                    return new Gradient(field(object, "from_y").getAsInt(), field(object, "to_y").getAsInt(),
                        field(object, "from_value").getAsDouble(), field(object, "to_value").getAsDouble());
                case "minecraft:spline":
                    return new SplineFunction(spline(field(object, "spline")));
                default:
                    throw new IllegalArgumentException("unsupported density function type " + type);
            }
        }

        private static JsonElement field(JsonObject object, String name) {
            JsonElement value = object.get(name);
            if (value == null) {
                throw new IllegalArgumentException("density function without " + name + ": " + object);
            }
            return value;
        }

        /**
         * A cache of a constant is that constant, as the game's sampler sees it once the cache markers are gone. The
         * game's chunk caches of these kinds keep one value per column, so an input that depends on y would give
         * other values in chunks than at single points: not supported.
         */
        private Node cache(Node input) {
            if (input.dependsOnY) {
                throw new IllegalArgumentException("unsupported: a column cache of a function that depends on y");
            }
            return input instanceof Constant ? input : new Cache(input, cacheSlots++);
        }

        /** As {@code TwoArgumentSimpleFunction.create}: a constant side makes it a {@code MulOrAdd}. */
        private static Node arithmetic(Arithmetic kind, Node left, Node right) {
            if (left instanceof Constant constant) {
                return new MulOrAdd(kind, right, constant.value);
            }
            if (right instanceof Constant constant) {
                return new MulOrAdd(kind, left, constant.value);
            }
            return new TwoArguments(kind, left, right);
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

        private SplineValue spline(JsonElement element) {
            if (element.isJsonPrimitive()) {
                return new ConstantSpline(element.getAsFloat());
            }
            JsonObject object = element.getAsJsonObject();
            Node coordinate = parse(field(object, "coordinate"));
            JsonArray points = field(object, "points").getAsJsonArray();
            if (points.isEmpty()) {
                throw new IllegalArgumentException("spline without points");
            }
            float[] locations = new float[points.size()];
            float[] derivatives = new float[points.size()];
            SplineValue[] values = new SplineValue[points.size()];
            for (int i = 0; i < points.size(); i++) {
                JsonObject point = points.get(i).getAsJsonObject();
                locations[i] = field(point, "location").getAsFloat();
                derivatives[i] = field(point, "derivative").getAsFloat();
                values[i] = spline(field(point, "value"));
            }
            return new Multipoint(coordinate, locations, derivatives, values);
        }

        /** A noise named by id; the climate's noises are always ids in the exported data. */
        private int noise(JsonElement reference) {
            if (!reference.isJsonPrimitive() || !reference.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("a noise must be given by id, not " + reference);
            }
            String id = reference.getAsString();
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

        /** {@code NormalNoise.NoiseParameters}: {@code firstOctave} and one amplitude per octave. */
        private static ClassicNormalNoise.Recipe recipe(String id, JsonObject parameters) {
            JsonElement firstOctave = parameters.get("firstOctave");
            JsonElement amplitudes = parameters.get("amplitudes");
            if (firstOctave == null || amplitudes == null || parameters.size() != 2) {
                throw new IllegalArgumentException(id + ": expected the noise parameters firstOctave and amplitudes, not " + parameters);
            }
            JsonArray list = amplitudes.getAsJsonArray();
            double[] values = new double[list.size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = list.get(i).getAsDouble();
            }
            return new ClassicNormalNoise.Recipe(id, firstOctave.getAsInt(), values);
        }
    }
}
