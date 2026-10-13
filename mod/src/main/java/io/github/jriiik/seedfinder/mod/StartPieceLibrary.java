package io.github.jriiik.seedfinder.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.jriiik.seedfinder.recognize.StartPiece;
import io.github.jriiik.seedfinder.structure.RandomSpreadPlacement;
import io.github.jriiik.seedfinder.structure.StructureSet;
import io.github.jriiik.seedfinder.version.VersionData;
import io.github.jriiik.seedfinder.world.BlockPos;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Builds the start-piece blueprints for automatic recognition from the game's own files: structure definitions,
 * template pools, processor lists, block tags and structure templates, read from the running game's jar. Nothing of
 * Minecraft is copied into the mod.
 *
 * <p>For every structure of a supported set it takes each piece of the start pool and keeps the blocks that are
 * sure to be there after generation: no air, jigsaw or structure blocks, no plants or other blocks that anything can
 * replace, and nothing that one of the piece's processors may swap or remove.
 */
final class StartPieceLibrary {

    /**
     * The structure sets recognized from blocks: the jigsaw structures whose start-piece rule was checked against
     * the game's own generation code.
     */
    static final List<String> SETS = List.of("villages", "pillager_outposts", "trial_chambers", "ancient_cities", "trail_ruins", "abandoned_camp");

    /** Smaller blueprints would match by chance too easily. */
    private static final int MIN_BLOCKS = 20;
    private static final int MIN_BUILT_BLOCKS = 5;

    private static final Set<Block> IGNORED = Set.of(
        Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR, Blocks.STRUCTURE_VOID, Blocks.STRUCTURE_BLOCK, Blocks.JIGSAW);

    /** Opens a file of the game's built-in data, by its path below {@code data/minecraft/}. */
    @FunctionalInterface
    interface DataFiles {
        InputStream open(String path) throws IOException;
    }

    /**
     * The game's own built-in data pack (the game jar only). Not the class loader: other mods' jars come first there,
     * and a mod shipping its own copy of a vanilla file (a block tag, say) would replace the game's.
     */
    static DataFiles vanilla(PackResources pack) {
        return path -> {
            IoSupplier<InputStream> resource = pack.getResource(PackType.SERVER_DATA, Identifier.withDefaultNamespace(path));
            if (resource == null) {
                throw new FileNotFoundException("data/minecraft/" + path);
            }
            return resource.get();
        };
    }

    /** The blueprints, and what could not be used (for the log). */
    record Library(List<StartPiece> pieces, List<String> problems) {
    }

    private record Jigsaw(int x, int y, int z, String name, String target, String pool, int frontX, int frontY, int frontZ) {
    }

    private record Placed(int x, int y, int z, BlockState state) {
    }

    private record Template(List<Placed> blocks, List<Jigsaw> jigsaws) {
    }

    private final VersionData data;
    private final DataFiles files;
    private final Map<String, Template> templates = new HashMap<>();
    private final Map<String, Set<Block>> tags = new HashMap<>();
    private final List<String> problems = new ArrayList<>();

    private StartPieceLibrary(VersionData data, DataFiles files) {
        this.data = data;
        this.files = files;
    }

    static Library load(VersionData data, DataFiles files) {
        StartPieceLibrary library = new StartPieceLibrary(data, files);
        List<StartPiece> pieces = new ArrayList<>();
        for (String id : SETS) {
            try {
                library.addSet(id, pieces);
            } catch (IOException | RuntimeException e) {
                library.problems.add(id + ": " + e);
            }
        }
        return new Library(List.copyOf(pieces), List.copyOf(library.problems));
    }

    private void addSet(String id, List<StartPiece> pieces) throws IOException {
        StructureSet set = data.structureSet(id).orElseThrow(() -> new IllegalArgumentException("unknown structure set"));
        if (!(set.placement() instanceof RandomSpreadPlacement placement)) {
            throw new IllegalArgumentException("not a random-spread set");
        }
        Set<String> done = new HashSet<>();
        for (StructureSet.Entry entry : set.structures()) {
            JsonObject structure = json("worldgen/structure/" + path(entry.structure()) + ".json");
            if (!"minecraft:jigsaw".equals(string(structure, "type"))) {
                throw new IllegalArgumentException(entry.structure() + " is not a jigsaw structure");
            }
            Optional<String> startJigsaw = structure.has("start_jigsaw_name") ? Optional.of(string(structure, "start_jigsaw_name")) : Optional.empty();
            for (JsonObject element : elements(string(structure, "start_pool"))) {
                String location = string(element, "location");
                if (!done.add(location)) {
                    continue;
                }
                if (!"rigid".equals(string(element, "projection"))) {
                    throw new IllegalArgumentException(location + " is not placed rigidly");
                }
                Template template = template(location);
                List<StartPiece.Block> blocks = signature(template, processors(element.get("processors")));
                if (!blocks.isEmpty()) {
                    BlockPos reference = startJigsaw.map(name -> jigsawNamed(template, name, location)).orElse(BlockPos.ZERO);
                    add(pieces, location, set, placement, blocks, List.of(reference));
                } else {
                    addAttached(pieces, template, set, placement, done);
                }
            }
        }
    }

    /**
     * For a start piece that places no blocks at all (the pillager outpost's base plate): the pieces attached to it
     * by jigsaw blocks facing each other horizontally. Those take the start piece's rotation, so the start piece's
     * origin is a fixed point in their own coordinates: child jigsaw - start jigsaw - the start jigsaw's facing.
     */
    private void addAttached(List<StartPiece> pieces, Template start, StructureSet set, RandomSpreadPlacement placement, Set<String> done)
        throws IOException {
        Map<String, List<BlockPos>> references = new LinkedHashMap<>();
        Map<String, List<StartPiece.Block>> blueprints = new HashMap<>();
        for (Jigsaw from : start.jigsaws()) {
            if (from.frontY() != 0 || from.pool().equals("minecraft:empty")) {
                continue;
            }
            for (JsonObject child : elements(from.pool())) {
                String location = string(child, "location");
                if (!"rigid".equals(string(child, "projection"))) {
                    continue;
                }
                Template template = template(location);
                List<StartPiece.Block> blocks = signature(template, processors(child.get("processors")));
                if (blocks.isEmpty()) {
                    continue;
                }
                for (Jigsaw to : template.jigsaws()) {
                    if (to.name().equals(from.target()) && to.frontY() == 0 && to.frontX() == -from.frontX() && to.frontZ() == -from.frontZ()) {
                        List<BlockPos> list = references.computeIfAbsent(location, key -> new ArrayList<>());
                        BlockPos reference = new BlockPos(to.x() - from.x() - from.frontX(), to.y() - from.y(), to.z() - from.z() - from.frontZ());
                        if (!list.contains(reference)) {
                            list.add(reference);
                        }
                        blueprints.put(location, blocks);
                    }
                }
            }
        }
        if (references.isEmpty()) {
            problems.add(set.shortId() + ": a start piece places no blocks and has nothing attached that does");
        }
        for (Map.Entry<String, List<BlockPos>> entry : references.entrySet()) {
            if (done.add(entry.getKey())) {
                add(pieces, entry.getKey(), set, placement, blueprints.get(entry.getKey()), entry.getValue());
            }
        }
    }

    private void add(List<StartPiece> pieces, String location, StructureSet set, RandomSpreadPlacement placement,
                     List<StartPiece.Block> blocks, List<BlockPos> references) {
        StartPiece piece = new StartPiece(path(location), set.shortId(), placement.spacing(), placement.separation(), blocks, references);
        if (piece.blockCount() < MIN_BLOCKS || piece.builtCount() < MIN_BUILT_BLOCKS || piece.anchors().isEmpty()) {
            problems.add(piece + ": too little to recognize it by");
        } else {
            pieces.add(piece);
        }
    }

    /** The blocks of a template that are sure to be there after the game places it. */
    private List<StartPiece.Block> signature(Template template, Predicate<BlockState> processorsChange) throws IOException {
        Set<Block> flowers = tag("minecraft:flowers");
        Set<Block> saplings = tag("minecraft:saplings");
        Set<Block> leaves = tag("minecraft:leaves");
        List<StartPiece.Block> blocks = new ArrayList<>();
        for (Placed placed : template.blocks()) {
            BlockState state = placed.state();
            Block block = state.getBlock();
            if (IGNORED.contains(block) || processorsChange.test(state)) {
                continue;
            }
            boolean soft = !state.is(Blocks.WATER) && (state.canBeReplaced() || state.is(Blocks.SNOW)
                || flowers.contains(block) || saplings.contains(block) || leaves.contains(block));
            if (!soft) {
                blocks.add(new StartPiece.Block(placed.x(), placed.y(), placed.z(), BuiltInRegistries.BLOCK.getKey(block).toString()));
            }
        }
        return blocks;
    }

    private static BlockPos jigsawNamed(Template template, String name, String location) {
        List<Jigsaw> named = template.jigsaws().stream().filter(jigsaw -> jigsaw.name().equals(name)).toList();
        if (named.size() != 1) {
            throw new IllegalArgumentException(location + " has " + named.size() + " jigsaw blocks named " + name);
        }
        return new BlockPos(named.get(0).x(), named.get(0).y(), named.get(0).z());
    }

    /** The single pieces of a template pool (pieces of a list element are placed on top of each other). */
    private List<JsonObject> elements(String pool) throws IOException {
        List<JsonObject> elements = new ArrayList<>();
        for (JsonElement entry : json("worldgen/template_pool/" + path(pool) + ".json").getAsJsonArray("elements")) {
            JsonObject element = entry.getAsJsonObject().getAsJsonObject("element");
            String type = string(element, "element_type");
            switch (type) {
                case "minecraft:single_pool_element", "minecraft:legacy_single_pool_element" -> elements.add(element);
                case "minecraft:list_pool_element" -> element.getAsJsonArray("elements").forEach(inner -> elements.add(inner.getAsJsonObject()));
                case "minecraft:empty_pool_element", "minecraft:feature_pool_element" -> {
                }
                default -> throw new IllegalArgumentException("unknown pool element " + type + " in " + pool);
            }
        }
        return elements;
    }

    /** Which template blocks the processors may swap or remove (whatever the random numbers turn out to be). */
    private Predicate<BlockState> processors(JsonElement processors) throws IOException {
        JsonArray list;
        if (processors == null) {
            return state -> false;
        } else if (processors.isJsonPrimitive()) {
            list = json("worldgen/processor_list/" + path(processors.getAsString()) + ".json").getAsJsonArray("processors");
        } else if (processors.isJsonObject()) {
            list = processors.getAsJsonObject().getAsJsonArray("processors");
        } else {
            list = processors.getAsJsonArray();
        }
        Predicate<BlockState> changes = state -> false;
        for (JsonElement processor : list) {
            changes = changes.or(processor(processor.getAsJsonObject()));
        }
        return changes;
    }

    private Predicate<BlockState> processor(JsonObject processor) throws IOException {
        String type = string(processor, "processor_type");
        switch (type) {
            case "minecraft:rule" -> {
                Predicate<BlockState> changes = state -> false;
                for (JsonElement rule : processor.getAsJsonArray("rules")) {
                    changes = changes.or(ruleInput(rule.getAsJsonObject().getAsJsonObject("input_predicate")));
                }
                return changes;
            }
            case "minecraft:capped" -> {
                return processor(processor.getAsJsonObject("delegate"));
            }
            case "minecraft:block_rot" -> {
                return state -> true;
            }
            case "minecraft:protected_blocks", "minecraft:nop" -> {
                return state -> false;
            }
            default -> throw new IllegalArgumentException("unknown processor " + type);
        }
    }

    /** A rule's input test, as if every random roll came out in favour of the rule. */
    private Predicate<BlockState> ruleInput(JsonObject test) throws IOException {
        String type = string(test, "predicate_type");
        switch (type) {
            case "minecraft:always_true" -> {
                return state -> true;
            }
            case "minecraft:block_match", "minecraft:random_block_match" -> {
                if (test.has("probability") && test.get("probability").getAsFloat() <= 0) {
                    return state -> false;
                }
                Block block = block(string(test, "block"));
                return state -> state.is(block);
            }
            case "minecraft:blockstate_match", "minecraft:random_blockstate_match" -> {
                if (test.has("probability") && test.get("probability").getAsFloat() <= 0) {
                    return state -> false;
                }
                JsonElement blockState = test.get("block_state");
                Block block = block(blockState != null && blockState.isJsonObject() ? string(blockState.getAsJsonObject(), "id") : string(test, "block_state"));
                return state -> state.is(block);
            }
            case "minecraft:tag_match" -> {
                Set<Block> blocks = tag(string(test, "tag"));
                return state -> blocks.contains(state.getBlock());
            }
            default -> throw new IllegalArgumentException("unknown rule test " + type);
        }
    }

    /** The blocks of a block tag as the game's own files define it (the server may not have sent tags yet). */
    private Set<Block> tag(String id) throws IOException {
        Set<Block> cached = tags.get(id);
        if (cached != null) {
            return cached;
        }
        Set<Block> blocks = new HashSet<>();
        for (JsonElement value : json("tags/block/" + path(id) + ".json").getAsJsonArray("values")) {
            String entry = value.isJsonObject() ? string(value.getAsJsonObject(), "id") : value.getAsString();
            if (entry.startsWith("#")) {
                if (!Identifier.parse(entry.substring(1)).getNamespace().equals("minecraft")) {
                    throw new IllegalArgumentException("block tag " + id + " includes " + entry + ", which is not part of the game");
                }
                blocks.addAll(tag(entry.substring(1)));
            } else {
                BuiltInRegistries.BLOCK.getOptional(Identifier.parse(entry)).ifPresent(blocks::add);
            }
        }
        Set<Block> result = Set.copyOf(blocks);
        tags.put(id, result);
        return result;
    }

    private static Block block(String id) {
        return BuiltInRegistries.BLOCK.getOptional(Identifier.parse(id))
            .orElseThrow(() -> new IllegalArgumentException("unknown block " + id));
    }

    private Template template(String location) throws IOException {
        Template cached = templates.get(location);
        if (cached != null) {
            return cached;
        }
        CompoundTag tag;
        try (InputStream in = files.open("structure/" + path(location) + ".nbt")) {
            tag = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        }
        ListTag palette = tag.getListOrEmpty("palette");
        if (palette.isEmpty()) {
            palette = tag.getListOrEmpty("palettes").getListOrEmpty(0);
        }
        BlockState[] states = new BlockState[palette.size()];
        String[] orientations = new String[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            CompoundTag entry = palette.getCompoundOrEmpty(i);
            states[i] = NbtUtils.readBlockState(BuiltInRegistries.BLOCK, entry);
            orientations[i] = entry.getCompoundOrEmpty("properties").getStringOr("orientation", "");
        }
        List<Placed> blocks = new ArrayList<>();
        List<Jigsaw> jigsaws = new ArrayList<>();
        ListTag list = tag.getListOrEmpty("blocks");
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompoundOrEmpty(i);
            ListTag pos = entry.getListOrEmpty("pos");
            int x = pos.getIntOr(0, 0);
            int y = pos.getIntOr(1, 0);
            int z = pos.getIntOr(2, 0);
            int state = entry.getIntOr("state", -1);
            if (state < 0 || state >= states.length) {
                throw new IllegalArgumentException(location + " has a block without a valid state");
            }
            blocks.add(new Placed(x, y, z, states[state]));
            if (states[state].is(Blocks.JIGSAW)) {
                CompoundTag nbt = entry.getCompoundOrEmpty("nbt");
                int[] front = front(orientations[state]);
                jigsaws.add(new Jigsaw(x, y, z, nbt.getStringOr("name", ""), nbt.getStringOr("target", ""), nbt.getStringOr("pool", "minecraft:empty"),
                    front[0], front[1], front[2]));
            }
        }
        Template template = new Template(List.copyOf(blocks), List.copyOf(jigsaws));
        templates.put(location, template);
        return template;
    }

    /** The direction a jigsaw block faces, from its orientation (e.g. {@code north_up}: facing north). */
    private static int[] front(String orientation) {
        String facing = orientation.contains("_") ? orientation.substring(0, orientation.indexOf('_')) : orientation;
        return switch (facing) {
            case "north" -> new int[] {0, 0, -1};
            case "south" -> new int[] {0, 0, 1};
            case "west" -> new int[] {-1, 0, 0};
            case "east" -> new int[] {1, 0, 0};
            case "up" -> new int[] {0, 1, 0};
            case "down" -> new int[] {0, -1, 0};
            default -> throw new IllegalArgumentException("unknown jigsaw orientation " + orientation);
        };
    }

    private JsonObject json(String path) throws IOException {
        try (InputStream in = files.open(path)) {
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException("missing " + key);
        }
        return value.getAsString();
    }

    /** The path of a {@code minecraft:} id; ids of other namespaces are not in the game's files. */
    private static String path(String id) {
        Identifier identifier = Identifier.parse(id);
        if (!identifier.getNamespace().equals("minecraft")) {
            throw new IllegalArgumentException("not a Minecraft id: " + id);
        }
        return identifier.getPath();
    }
}
