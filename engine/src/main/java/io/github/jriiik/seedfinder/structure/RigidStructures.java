package io.github.jriiik.seedfinder.structure;

import io.github.jriiik.seedfinder.world.BlockPos;
import java.util.List;
import java.util.Map;

/**
 * How the game builds the main piece of each rigid structure, as the oracle found it by running the game's own
 * structure generation (version data {@code rigid_structures.json}).
 *
 * @param templateSets  for structure sets built from templates (igloos, shipwrecks, ocean ruins, ruined portals), by
 *                      set id: the templates' origin is always the start chunk's north-west corner
 * @param codeBuiltSets for structure sets built by code (desert pyramids, jungle temples, swamp huts), by set id: the
 *                      piece types; their bounding box always starts at the start chunk's north-west corner
 */
public record RigidStructures(Map<String, TemplateSet> templateSets, Map<String, List<String>> codeBuiltSets) {

    public static final RigidStructures NONE = new RigidStructures(Map.of(), Map.of());

    public RigidStructures {
        templateSets = Map.copyOf(templateSets);
        codeBuiltSets = Map.copyOf(codeBuiltSets);
    }

    /**
     * The ways a set's main piece is placed: every piece below in every rotation and mirror listed (names as the game
     * writes them, e.g. {@code clockwise_90}, {@code front_back}).
     */
    public record TemplateSet(List<String> rotations, List<String> mirrors, List<Piece> pieces) {

        public TemplateSet {
            rotations = List.copyOf(rotations);
            mirrors = List.copyOf(mirrors);
            pieces = List.copyOf(pieces);
        }
    }

    /**
     * Templates placed at the same spot, one on top of the other (cold ocean ruins have three), turned and mirrored
     * about the template-local pivot.
     */
    public record Piece(List<String> templates, BlockPos pivot) {

        public Piece {
            templates = List.copyOf(templates);
        }
    }
}
