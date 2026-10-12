package io.github.jriiik.seedfinder.recognize;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Which blocks count as ground, which make poor anchors, and which count as the same block when comparing. */
final class BlockKinds {

    /**
     * Ground blocks. A blueprint that is mostly ground matches plain ground almost anywhere, so recognition also
     * scores the other ("built") blocks on their own.
     */
    static final Set<String> TERRAIN = ids(
        "grass_block", "dirt", "coarse_dirt", "podzol", "mycelium", "rooted_dirt", "dirt_path", "farmland", "mud", "sand",
        "red_sand", "sandstone", "red_sandstone", "gravel", "clay", "stone", "deepslate", "tuff", "granite", "diorite",
        "andesite", "calcite", "water", "lava", "ice", "packed_ice", "blue_ice", "snow_block", "powder_snow", "terracotta",
        "moss_block", "sculk");

    /**
     * Built blocks that also generate outside structures (trees, badlands, rocks, caves...). They would still give
     * right answers as anchors, but finding them everywhere makes scanning slow, so they are never anchors.
     */
    static final Set<String> NOT_ANCHORS = notAnchors();

    /** Blocks that change into each other in a world over time: grass dies back to dirt, water freezes. */
    private static final Map<String, String> SAME_AS = Map.of(
        "minecraft:dirt", "minecraft:grass_block",
        "minecraft:ice", "minecraft:water",
        "minecraft:frosted_ice", "minecraft:water");

    private BlockKinds() {
    }

    /** The block id used for comparing: dirt counts as grass, ice as water; everything else as itself. */
    static String canonical(String id) {
        return SAME_AS.getOrDefault(id, id);
    }

    private static Set<String> notAnchors() {
        Set<String> blocks = new HashSet<>(TERRAIN);
        for (String wood : new String[] {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak", "poplar"}) {
            blocks.add("minecraft:" + wood + "_log");
            blocks.add("minecraft:" + wood + "_wood");
            blocks.add("minecraft:" + wood + "_leaves");
        }
        for (String colour : new String[] {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray",
            "cyan", "purple", "blue", "brown", "green", "red", "black"}) {
            blocks.add("minecraft:" + colour + "_terracotta");
        }
        blocks.addAll(ids("cobblestone", "mossy_cobblestone", "obsidian", "magma_block", "bone_block", "dripstone_block",
            "pointed_dripstone", "amethyst_block", "budding_amethyst", "smooth_basalt", "basalt", "blackstone", "netherrack",
            "brown_mushroom_block", "red_mushroom_block", "mushroom_stem", "mangrove_roots", "muddy_mangrove_roots", "vine",
            "glow_lichen", "cobweb", "oak_planks", "oak_fence", "rail", "bee_nest", "pumpkin", "melon", "sculk_vein",
            "sculk_sensor", "sculk_shrieker", "sculk_catalyst", "cobbled_deepslate", "infested_stone", "infested_deepslate",
            "coal_ore", "iron_ore", "copper_ore", "gold_ore", "deepslate_iron_ore", "deepslate_copper_ore",
            "red_poplar_leaves", "orange_poplar_leaves", "yellow_poplar_leaves", "azalea_leaves", "flowering_azalea_leaves",
            "crimson_stem", "warped_stem", "crimson_hyphae", "warped_hyphae",
            // Plants that grow wild.
            "sweet_berry_bush", "brown_mushroom", "red_mushroom", "shelf_mushroom", "pale_hanging_moss", "pale_moss_carpet",
            "pale_moss_block", "moss_carpet", "firefly_bush", "bush", "dead_bush", "lily_pad", "cactus", "cactus_flower",
            "sugar_cane", "bamboo", "kelp", "kelp_plant", "seagrass", "tall_seagrass", "cocoa", "leaf_litter", "wildflowers",
            "short_dry_grass", "tall_dry_grass", "azalea", "flowering_azalea", "big_dripleaf", "big_dripleaf_stem",
            "small_dripleaf", "spore_blossom", "hanging_roots", "cave_vines", "cave_vines_plant", "resin_clump", "red_shrub"));
        return Set.copyOf(blocks);
    }

    private static Set<String> ids(String... paths) {
        Set<String> ids = new HashSet<>();
        for (String path : paths) {
            ids.add("minecraft:" + path);
        }
        return Set.copyOf(ids);
    }
}
