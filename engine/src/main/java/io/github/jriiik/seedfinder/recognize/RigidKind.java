package io.github.jriiik.seedfinder.recognize;

import java.util.Set;

/**
 * The structures built from one fixed piece: desert pyramids, jungle temples and swamp huts (built by the game's
 * code), igloos, shipwrecks, ocean ruins and ruined portals (built from templates). For each: its structure set, the
 * block classes ({@link RigidBlocks#canonical}) that cast votes for a placement, the classes that count for the
 * score, and its thresholds. Measured with the game's own generation code (see the roadmap); keep them unless a new
 * measurement shows a change is safe.
 */
public enum RigidKind {

    DESERT_PYRAMID("desert_pyramids",
        Set.of("orange_terracotta", "blue_terracotta", "cut_sandstone", "chiseled_sandstone", "sandstone_stairs", "tnt", "stone_pressure_plate",
            "sandstone_slab"),
        Set.of("orange_terracotta", "blue_terracotta", "cut_sandstone", "chiseled_sandstone", "sandstone_stairs", "tnt", "stone_pressure_plate",
            "sandstone_slab", "chest"),
        0.5, 0.5, 0.3, false),
    JUNGLE_TEMPLE("jungle_temples",
        Set.of("cobblestone", "chiseled_stone_bricks", "cobblestone_stairs", "dispenser", "sticky_piston", "lever", "tripwire_hook", "repeater",
            "redstone_wire", "tripwire"),
        Set.of("cobblestone", "chiseled_stone_bricks", "cobblestone_stairs", "dispenser", "sticky_piston", "lever", "tripwire_hook", "repeater",
            "redstone_wire", "tripwire", "chest"),
        0.5, 0.5, 0.3, false),
    SWAMP_HUT("swamp_huts",
        Set.of("wood_planks", "wood_stairs", "wood_fence", "crafting_table", "cauldron", "potted_red_mushroom"),
        Set.of("wood_planks", "wood_stairs", "wood_fence", "crafting_table", "cauldron", "potted_red_mushroom"),
        0.5, 0.5, 0.3, false),
    IGLOO("igloos",
        Set.of("white_carpet", "light_gray_carpet", "red_bed", "redstone_torch", "furnace", "crafting_table", "wood_trapdoor"),
        Set.of("white_carpet", "light_gray_carpet", "red_bed", "redstone_torch", "furnace", "crafting_table", "wood_trapdoor", "ice"),
        0.5, 0.5, 0.3, false),
    SHIPWRECK("shipwrecks",
        Set.of("wood_planks", "wood_stairs", "wood_slab", "wood_fence", "wood_trapdoor", "wood_door", "chest"),
        Set.of("wood_planks", "wood_stairs", "wood_slab", "wood_fence", "wood_trapdoor", "wood_door", "wood_log", "chest"),
        0.5, 0.5, 0.3, false),
    OCEAN_RUIN("ocean_ruins",
        Set.of("stone_bricks", "chiseled_stone_bricks", "stone_brick_stairs", "cobblestone", "cut_sandstone", "chiseled_sandstone", "sandstone_stairs",
            "polished_granite", "polished_diorite", "sea_lantern", "prismarine", "light_blue_terracotta", "purple_glazed_terracotta", "bricks",
            "wood_planks"),
        Set.of("stone_bricks", "chiseled_stone_bricks", "stone_brick_stairs", "cobblestone", "cut_sandstone", "chiseled_sandstone", "sandstone_stairs",
            "polished_granite", "polished_diorite", "sea_lantern", "prismarine", "light_blue_terracotta", "purple_glazed_terracotta", "bricks",
            "wood_planks"),
        0.55, 0.45, 0.3, false),
    /**
     * True ruined portals never hold their own material where the template has air; a player's complete frame (or a
     * floor under it) does, so a single filled empty spot rules a fit out.
     */
    RUINED_PORTAL("ruined_portals",
        Set.of("obsidian"),
        union(Set.of("obsidian", "iron_bars"), RigidBlocks.STONE_FAMILY_ARTIFICIAL),
        0.5, 0.6, 0.0, true);

    private final String structureSet;
    private final Set<String> triggers;
    private final Set<String> informative;
    private final double minScore;
    private final double minBalanced;
    private final double maxFilled;
    private final boolean biomeAtCorner;

    RigidKind(String structureSet, Set<String> triggers, Set<String> informative, double minScore, double minBalanced, double maxFilled,
              boolean biomeAtCorner) {
        this.structureSet = structureSet;
        this.triggers = triggers;
        this.informative = informative;
        this.minScore = minScore;
        this.minBalanced = minBalanced;
        this.maxFilled = maxFilled;
        this.biomeAtCorner = biomeAtCorner;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        java.util.Set<String> all = new java.util.HashSet<>(a);
        all.addAll(b);
        return Set.copyOf(all);
    }

    /** The structure set's short id, e.g. {@code desert_pyramids}. */
    public String structureSet() {
        return structureSet;
    }

    /** Classes whose blocks vote for the placements they could belong to. */
    public Set<String> triggers() {
        return triggers;
    }

    /** Classes that count for the score: the structure's own materials, which natural terrain rarely has. */
    public Set<String> informative() {
        return informative;
    }

    /** At least this share of the checked blocks must match. */
    public double minScore() {
        return minScore;
    }

    /** At least this share must match when every class of block counts equally ({@link RigidRecognizer.Detection#balanced}). */
    public double minBalanced() {
        return minBalanced;
    }

    /** At most this share of the template's empty spots may hold the structure's own material. */
    public double maxFilled() {
        return maxFilled;
    }

    /** Whether the game tests the biome at the start chunk's corner (ruined portals) rather than its middle. */
    public boolean biomeAtCorner() {
        return biomeAtCorner;
    }
}
