package io.github.jriiik.seedfinder.recognize;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Block classes for recognizing rigid structures ({@link RigidRecognizer}). The game varies these structures at
 * random in ways that don't matter for recognition: shipwrecks and huts come in any wood, stone bricks may be mossy
 * or cracked, obsidian may be crying, cauldrons may hold something. Each such family becomes one class, e.g.
 * {@code minecraft:spruce_planks} and {@code minecraft:oak_planks} both become {@code wood_planks}.
 */
public final class RigidBlocks {

    /** Stone blocks the game swaps for one another on ruined portals (ageing, and blackstone in the nether). */
    static final Set<String> STONE_FAMILY = Set.of("stone_bricks", "chiseled_stone_bricks", "stone_brick_stairs", "stone_slab", "stone_brick_wall",
        "stone", "cobblestone", "blackstone", "blackstone_stairs", "blackstone_slab", "blackstone_wall", "polished_blackstone_stairs", "stone_stairs",
        "cobblestone_stairs", "cobblestone_slab", "cobblestone_wall", "polished_blackstone", "polished_blackstone_bricks", "chiseled_polished_blackstone",
        "polished_blackstone_brick_stairs", "polished_blackstone_slab", "polished_blackstone_brick_wall");

    /** The made (not natural) members of {@link #STONE_FAMILY}: what a portal's stone parts may be found as. */
    static final Set<String> STONE_FAMILY_ARTIFICIAL = Set.of("stone_bricks", "chiseled_stone_bricks", "stone_brick_stairs", "stone_slab",
        "stone_brick_wall", "blackstone_stairs", "blackstone_slab", "blackstone_wall", "polished_blackstone_stairs", "stone_stairs", "cobblestone_stairs",
        "cobblestone_slab", "cobblestone_wall", "polished_blackstone_bricks", "chiseled_polished_blackstone", "polished_blackstone_brick_stairs",
        "polished_blackstone_slab", "polished_blackstone_brick_wall");

    /** What lies on the sea floor around an ocean ruin: water, ice and sea plants. */
    static final Set<String> WATERY = Set.of("water", "ice", "packed_ice", "blue_ice", "kelp", "kelp_plant", "seagrass", "tall_seagrass",
        "bubble_column", "sea_pickle");

    private static final String[] WOODS = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak", "bamboo",
        "crimson", "warped"};
    private static final String[] WOOD_SUFFIXES = {"planks", "log", "wood", "stairs", "slab", "fence_gate", "fence", "trapdoor", "door", "pressure_plate",
        "button", "wall_sign", "sign"};
    private static final Map<String, String> OTHERS = Map.ofEntries(
        Map.entry("cave_air", "air"),
        Map.entry("void_air", "air"),
        Map.entry("mossy_cobblestone", "cobblestone"),
        Map.entry("mossy_stone_bricks", "stone_bricks"),
        Map.entry("cracked_stone_bricks", "stone_bricks"),
        Map.entry("infested_stone_bricks", "stone_bricks"),
        Map.entry("infested_mossy_stone_bricks", "stone_bricks"),
        Map.entry("infested_cracked_stone_bricks", "stone_bricks"),
        Map.entry("infested_chiseled_stone_bricks", "chiseled_stone_bricks"),
        Map.entry("mossy_stone_brick_stairs", "stone_brick_stairs"),
        Map.entry("stone_brick_slab", "stone_slab"),
        Map.entry("mossy_stone_brick_slab", "stone_slab"),
        Map.entry("smooth_stone_slab", "stone_slab"),
        Map.entry("mossy_stone_brick_wall", "stone_brick_wall"),
        // The nether portal's blackstone variants are their own family, so that bastion walls never count as stone
        // bricks for other structures.
        Map.entry("cracked_polished_blackstone_bricks", "polished_blackstone_bricks"),
        Map.entry("polished_blackstone_brick_slab", "polished_blackstone_slab"),
        Map.entry("crying_obsidian", "obsidian"),
        Map.entry("magma_block", "netherrack"),
        Map.entry("iron_chain", "iron_bars"),
        Map.entry("suspicious_sand", "sand"),
        Map.entry("suspicious_gravel", "gravel"),
        Map.entry("water_cauldron", "cauldron"),
        Map.entry("lava_cauldron", "cauldron"),
        Map.entry("powder_snow_cauldron", "cauldron"),
        Map.entry("trapped_chest", "chest"),
        Map.entry("wall_torch", "torch"),
        Map.entry("redstone_wall_torch", "redstone_torch"));

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private RigidBlocks() {
    }

    /** The class of a block id such as {@code minecraft:mossy_cobblestone} ({@code cobblestone}); null for null. */
    public static String canonical(String id) {
        if (id == null) {
            return null;
        }
        return CACHE.computeIfAbsent(id, RigidBlocks::classify);
    }

    private static String classify(String id) {
        String name = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
        if (name.startsWith("stripped_")) {
            name = name.substring("stripped_".length());
        }
        for (String wood : WOODS) {
            if (name.startsWith(wood + "_")) {
                String rest = name.substring(wood.length() + 1);
                for (String suffix : WOOD_SUFFIXES) {
                    if (rest.equals(suffix)) {
                        return "wood_" + suffix;
                    }
                }
                if (rest.equals("stem") || rest.equals("hyphae")) {
                    return "wood_log";
                }
            }
        }
        return OTHERS.getOrDefault(name, name);
    }
}
