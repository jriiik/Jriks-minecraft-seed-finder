package io.github.jriiik.seedfinder.mod;

import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;

/**
 * The few calls that differ between Minecraft versions, as Minecraft 1.21.11 has them. Each version's project has its
 * own copy of this class (mod/versions/&lt;version&gt;); the rest of the mod is the same for every version.
 */
final class GameVersion {

    /** The Minecraft version this jar is made for, as the game names it. */
    static final String NAME = "1.21.11";

    private GameVersion() {
    }

    static KeyMapping registerKey(KeyMapping key) {
        return KeyBindingHelper.registerKeyBinding(key);
    }

    /** Shows a line of white text at the top left of the screen whenever {@code text} gives one (not null). */
    static void addStatusLine(Identifier id, Supplier<String> text) {
        HudElementRegistry.addLast(id, (graphics, delta) -> {
            String line = text.get();
            if (line != null) {
                graphics.drawString(Minecraft.getInstance().font, Component.literal(line), 4, 4, 0xFFFFFFFF);
            }
        });
    }

    static void setScreen(Minecraft minecraft, Screen screen) {
        minecraft.setScreen(screen);
    }

    /** The game's own data files (structures, templates, tags), as shipped in its jar. */
    static PackResources vanillaData(Minecraft minecraft) {
        return minecraft.getVanillaPackResources();
    }
}
