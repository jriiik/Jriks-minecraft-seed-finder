package io.github.jriiik.seedfinder.mod;

import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;

/**
 * The few calls that differ between Minecraft versions, as Minecraft 26.3 has them. Each version's project has its own
 * copy of this class (mod/versions/&lt;version&gt;); the rest of the mod is the same for every version.
 */
final class GameVersion {

    /** The Minecraft version this jar is made for, as the game names it. */
    static final String NAME = "26.3";

    private GameVersion() {
    }

    static KeyMapping registerKey(KeyMapping key) {
        return KeyMappingHelper.registerKeyMapping(key);
    }

    /** Shows a line of white text at the top left of the screen whenever {@code text} gives one (not null). */
    static void addStatusLine(Identifier id, Supplier<String> text) {
        HudElementRegistry.addLast(id, (graphics, delta) -> {
            String line = text.get();
            if (line != null) {
                graphics.text(Minecraft.getInstance().font, Component.literal(line), 4, 4, 0xFFFFFFFF);
            }
        });
    }

    static void setScreen(Minecraft minecraft, Screen screen) {
        minecraft.gui.setScreen(screen);
    }

    /** The game's own data files (structures, templates, tags), as shipped in its jar. */
    static PackResources vanillaData(Minecraft minecraft) {
        return minecraft.getVanillaPackResources().fullResources();
    }
}
