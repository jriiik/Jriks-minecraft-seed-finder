package io.github.jriiik.seedfinder.mod;

import io.github.jriiik.seedfinder.recognize.PieceRotation;
import io.github.jriiik.seedfinder.recognize.RecognizedStarts;
import io.github.jriiik.seedfinder.recognize.StartPiece;
import io.github.jriiik.seedfinder.recognize.StartPieceRecognizer;
import io.github.jriiik.seedfinder.structure.RigidStructures;
import io.github.jriiik.seedfinder.version.VersionData;
import io.github.jriiik.seedfinder.world.BlockPos;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Recognises the answer to "/place template ...": a structure's own template placed by command can look like a real
 * start (a ruined portal, an igloo, a shipwreck, an ocean ruin, or the start piece of a village and the like), so the
 * start chunks it could pass for must never be noted. Only reads the chat; never sends.
 *
 * <p>The answer names the template and where its origin was put, not how it was turned, so every pose is considered.
 *
 * @param template the template id, like {@code minecraft:ruined_portal/portal_3}
 * @param x, z     where the template's origin was put
 */
record PlacedTemplateMessage(String template, int x, int z) {

    static final String SUCCESS_KEY = "commands.place.template.success";

    static Optional<PlacedTemplateMessage> parse(Component message) {
        TranslatableContents contents = LocateMessage.find(message, SUCCESS_KEY);
        if (contents == null || contents.getArgs().length < 4) {
            return Optional.empty();
        }
        Object[] args = contents.getArgs();
        try {
            return Optional.of(new PlacedTemplateMessage(LocateMessage.text(args[0]).strip(), Integer.parseInt(LocateMessage.text(args[1]).strip()),
                Integer.parseInt(LocateMessage.text(args[3]).strip())));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * The starts the placed template could be recognized as: rigid structures turned about their pivot (the command
     * turns about the origin), and start pieces whose reference point lands on a chunk corner.
     *
     * @param pieces the start pieces, or null when they are not read yet
     */
    List<RecognizedStarts.Start> lookalikes(VersionData data, StartPieceRecognizer pieces) {
        Set<RecognizedStarts.Start> starts = new LinkedHashSet<>();
        for (Map.Entry<String, RigidStructures.TemplateSet> entry : data.rigidStructures().templateSets().entrySet()) {
            String set = entry.getKey().substring(entry.getKey().indexOf(':') + 1);
            RigidStructures.TemplateSet templates = entry.getValue();
            for (RigidStructures.Piece piece : templates.pieces()) {
                if (!piece.templates().contains(template)) {
                    continue;
                }
                net.minecraft.core.BlockPos pivot = new net.minecraft.core.BlockPos(piece.pivot().x(), piece.pivot().y(), piece.pivot().z());
                for (String rotation : templates.rotations()) {
                    for (String mirror : templates.mirrors()) {
                        // The game turns the template about the pivot with its origin on the start chunk's corner;
                        // the command turns it about the origin, so the same blocks start at x - (pivot - turned pivot).
                        net.minecraft.core.BlockPos shift = StructureTemplate.transform(net.minecraft.core.BlockPos.ZERO,
                            RigidLibrary.mirror(mirror), RigidLibrary.rotation(rotation), pivot);
                        addIfCorner(starts, set, x - shift.getX(), z - shift.getZ());
                    }
                }
            }
        }
        if (pieces != null) {
            String path = template.substring(template.indexOf(':') + 1);
            for (StartPiece piece : pieces.pieces()) {
                if (!piece.name().equals(path) && !piece.name().equals(template)) {
                    continue;
                }
                for (PieceRotation rotation : PieceRotation.values()) {
                    for (BlockPos reference : piece.references()) {
                        addIfCorner(starts, piece.structureSet(), x + rotation.x(reference.x(), reference.z()), z + rotation.z(reference.x(), reference.z()));
                    }
                }
            }
        }
        return new ArrayList<>(starts);
    }

    private static void addIfCorner(Set<RecognizedStarts.Start> starts, String set, int cornerX, int cornerZ) {
        if ((cornerX & 15) == 0 && (cornerZ & 15) == 0) {
            starts.add(new RecognizedStarts.Start(set, cornerX >> 4, cornerZ >> 4));
        }
    }
}
