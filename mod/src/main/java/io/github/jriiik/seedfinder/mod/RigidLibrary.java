package io.github.jriiik.seedfinder.mod;

import io.github.jriiik.seedfinder.recognize.RigidBlocks;
import io.github.jriiik.seedfinder.recognize.RigidKind;
import io.github.jriiik.seedfinder.recognize.RigidLayout;
import io.github.jriiik.seedfinder.structure.RigidStructures;
import io.github.jriiik.seedfinder.version.VersionData;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.structures.DesertPyramidPiece;
import net.minecraft.world.level.levelgen.structure.structures.JungleTemplePiece;
import net.minecraft.world.level.levelgen.structure.structures.SwampHutPiece;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Builds the layouts for recognizing rigid structures ({@link io.github.jriiik.seedfinder.recognize.RigidRecognizer})
 * from the game itself, at runtime: templates are read from the game jar and placed the way the oracle saw the game
 * place them (version data: templates, rotations, mirrors, pivots), turned with the game's own
 * {@link StructureTemplate#transform}; the pieces the game builds in code (desert pyramids, jungle temples, swamp
 * huts) are laid out by running the game's own building code in a {@link SyntheticLevel}. Nothing of Minecraft is
 * copied into the mod.
 */
final class RigidLibrary {

    /** The layouts, and what could not be used (for the log). */
    record Library(List<RigidLayout> layouts, List<String> problems) {
    }

    private record Placed(int x, int y, int z, String id) {
    }

    private final StartPieceLibrary.DataFiles files;
    private final Map<String, List<Placed>> templates = new HashMap<>();
    private final List<String> problems = new ArrayList<>();

    private RigidLibrary(StartPieceLibrary.DataFiles files) {
        this.files = files;
    }

    static Library load(VersionData data, StartPieceLibrary.DataFiles files, RegistryAccess registries) {
        RigidLibrary library = new RigidLibrary(files);
        List<RigidLayout> layouts = new ArrayList<>();
        RigidStructures facts = data.rigidStructures();
        for (RigidKind kind : RigidKind.values()) {
            String set = "minecraft:" + kind.structureSet();
            try {
                RigidStructures.TemplateSet templateSet = facts.templateSets().get(set);
                List<String> pieceTypes = facts.codeBuiltSets().get(set);
                if (templateSet != null) {
                    layouts.addAll(library.templateLayouts(kind, templateSet));
                } else if (pieceTypes != null) {
                    layouts.addAll(library.codeBuiltLayouts(kind, pieceTypes, registries));
                } else {
                    library.problems.add(kind.structureSet() + ": the version data does not say how it is built");
                }
            } catch (IOException | RuntimeException e) {
                library.problems.add(kind.structureSet() + ": " + e);
            }
        }
        return new Library(List.copyOf(layouts), List.copyOf(library.problems));
    }

    /**
     * Every piece of the set in every rotation and mirror. Templates placed at one spot (cold ocean ruins) give a
     * layout of all of them together, and one of each alone: each finds its own floor, so they can end up at
     * different heights.
     */
    private List<RigidLayout> templateLayouts(RigidKind kind, RigidStructures.TemplateSet set) throws IOException {
        List<RigidLayout> layouts = new ArrayList<>();
        Set<String> done = new HashSet<>();
        for (RigidStructures.Piece piece : set.pieces()) {
            BlockPos pivot = new BlockPos(piece.pivot().x(), piece.pivot().y(), piece.pivot().z());
            List<List<String>> variants = new ArrayList<>();
            variants.add(piece.templates());
            if (piece.templates().size() > 1) {
                piece.templates().forEach(template -> variants.add(List.of(template)));
            }
            for (String rotationName : set.rotations()) {
                for (String mirrorName : set.mirrors()) {
                    Rotation rotation = rotation(rotationName);
                    Mirror mirror = mirror(mirrorName);
                    for (List<String> names : variants) {
                        String variant = String.join("+", names) + " " + rotationName + " " + mirrorName;
                        if (done.add(variant)) {
                            layouts.add(templateLayout(kind, variant, names, rotation, mirror, pivot));
                        }
                    }
                }
            }
        }
        return layouts;
    }

    private RigidLayout templateLayout(RigidKind kind, String variant, List<String> names, Rotation rotation, Mirror mirror, BlockPos pivot)
        throws IOException {
        List<RigidLayout.Entry> entries = new ArrayList<>();
        List<int[]> air = new ArrayList<>();
        int trapdoors = 0;
        for (String name : names) {
            for (Placed placed : template(name)) {
                BlockPos at = StructureTemplate.transform(new BlockPos(placed.x(), placed.y(), placed.z()), mirror, rotation, pivot);
                String c = RigidBlocks.canonical(placed.id());
                if (c.equals("air")) {
                    air.add(new int[] {at.getX(), at.getY(), at.getZ()});
                } else if (kind == RigidKind.IGLOO && c.equals("wood_trapdoor")) {
                    // The trapdoor over the basement ladder turns into snow when the igloo has no basement.
                    trapdoors++;
                    entries.add(new RigidLayout.Entry(at.getX(), at.getY(), at.getZ(), Set.of(placed.id(), "minecraft:snow_block")));
                } else {
                    entries.add(new RigidLayout.Entry(at.getX(), at.getY(), at.getZ(), Set.of(placed.id())));
                }
            }
        }
        if (kind == RigidKind.IGLOO && trapdoors != 1) {
            problems.add(variant + ": expected one trapdoor (over the basement), found " + trapdoors);
        }
        return RigidLayout.of(kind, variant, entries, air);
    }

    /**
     * Runs the game's own code for the piece in each of its four orientations, on a flat made-up world, and keeps
     * what it builds relative to the piece's box corner (which the game puts on the start chunk's corner).
     */
    private List<RigidLayout> codeBuiltLayouts(RigidKind kind, List<String> pieceTypes, RegistryAccess registries) {
        Function<RandomSource, StructurePiece> factory = switch (kind) {
            case DESERT_PYRAMID -> random -> new DesertPyramidPiece(random, 0, 0);
            case JUNGLE_TEMPLE -> random -> new JungleTemplePiece(random, 0, 0);
            case SWAMP_HUT -> random -> new SwampHutPiece(random, 0, 0);
            default -> throw new IllegalArgumentException(kind + " is not built by code");
        };
        Map<Direction, StructurePiece> byDirection = new LinkedHashMap<>();
        for (int i = 0; byDirection.size() < 4 && i < 1000; i++) {
            StructurePiece piece = factory.apply(RandomSource.create(i * 0x9E3779B97F4A7C15L + 12345L));
            byDirection.putIfAbsent(piece.getOrientation(), piece);
        }
        List<RigidLayout> layouts = new ArrayList<>();
        for (Map.Entry<Direction, StructurePiece> entry : byDirection.entrySet()) {
            StructurePiece piece = entry.getValue();
            String type = BuiltInRegistries.STRUCTURE_PIECE.getKey(piece.getType()).toString();
            if (!pieceTypes.contains(type)) {
                throw new IllegalStateException("the game builds it with " + pieceTypes + ", not " + type);
            }
            SyntheticLevel level = new SyntheticLevel(registries);
            try {
                piece.postProcess(level, null, null, RandomSource.create(42), new BoundingBox(-64, -63, -64, 80, 319, 80), new ChunkPos(0, 0),
                    BlockPos.ZERO);
            } catch (UnsupportedOperationException e) {
                // The swamp hut spawns its witch and cat (which needs a real server) after placing all its blocks.
                if (kind != RigidKind.SWAMP_HUT) {
                    throw e;
                }
            }
            BoundingBox box = piece.getBoundingBox();
            List<RigidLayout.Entry> entries = new ArrayList<>();
            List<int[]> air = new ArrayList<>();
            level.written().forEach((pos, state) -> {
                int x = pos.getX() - box.minX();
                int y = pos.getY() - box.minY();
                int z = pos.getZ() - box.minZ();
                String id = id(state);
                if (RigidBlocks.canonical(id).equals("air")) {
                    air.add(new int[] {x, y, z});
                } else {
                    entries.add(new RigidLayout.Entry(x, y, z, Set.of(id)));
                }
            });
            layouts.add(RigidLayout.of(kind, kind.structureSet() + " facing " + entry.getKey().getSerializedName(), entries, air));
        }
        if (layouts.size() != 4) {
            problems.add(kind.structureSet() + ": only " + layouts.size() + " orientations seen");
        }
        return layouts;
    }

    static Rotation rotation(String name) {
        for (Rotation rotation : Rotation.values()) {
            if (rotation.getSerializedName().equals(name)) {
                return rotation;
            }
        }
        throw new IllegalArgumentException("unknown rotation " + name);
    }

    static Mirror mirror(String name) {
        for (Mirror mirror : Mirror.values()) {
            if (mirror.getSerializedName().equals(name)) {
                return mirror;
            }
        }
        throw new IllegalArgumentException("unknown mirror " + name);
    }

    private static String id(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    /** A template's blocks with their template-local positions (palette 0: wood types are one class anyway). */
    private List<Placed> template(String name) throws IOException {
        List<Placed> cached = templates.get(name);
        if (cached != null) {
            return cached;
        }
        Identifier location = Identifier.parse(name);
        if (!location.getNamespace().equals("minecraft")) {
            throw new IllegalArgumentException("not a Minecraft template: " + name);
        }
        CompoundTag tag;
        try (InputStream in = files.open("structure/" + location.getPath() + ".nbt")) {
            tag = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        }
        ListTag palette = tag.getListOrEmpty("palette");
        if (palette.isEmpty()) {
            palette = tag.getListOrEmpty("palettes").getListOrEmpty(0);
        }
        String[] ids = new String[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            CompoundTag entry = palette.getCompoundOrEmpty(i);
            ids[i] = id(NbtUtils.readBlockState(BuiltInRegistries.BLOCK, entry));
            String written = entry.getStringOr("id", entry.getStringOr("Name", ""));
            if (!ids[i].equals(Identifier.parse(written).toString())) {
                // An unknown block reads as air: the template is from another version of the game.
                throw new IllegalArgumentException(name + " has block " + written + ", which this game does not know");
            }
        }
        List<Placed> blocks = new ArrayList<>();
        ListTag list = tag.getListOrEmpty("blocks");
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompoundOrEmpty(i);
            ListTag pos = entry.getListOrEmpty("pos");
            int state = entry.getIntOr("state", -1);
            if (state < 0 || state >= ids.length) {
                throw new IllegalArgumentException(name + " has a block without a valid state");
            }
            blocks.add(new Placed(pos.getIntOr(0, 0), pos.getIntOr(1, 0), pos.getIntOr(2, 0), ids[state]));
        }
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException(name + " has no blocks");
        }
        List<Placed> result = List.copyOf(blocks);
        templates.put(name, result);
        return result;
    }
}
