package io.github.jriiik.seedfinder.mod.gametest;

import com.mojang.renderpearl.backend.opengl.GlBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * TEST ONLY (the in-game test mod, never the real mod). Minecraft 26.3 asks for an sRGB-capable OpenGL window, which
 * the virtual display used for automated tests (Xvfb) can't provide, so the window could not be created. This asks for
 * a plain one instead.
 */
@Mixin(GlBackend.class)
public class XvfbSrgbWorkaroundMixin {

    @ModifyArg(method = "createWindow", at = @At(value = "INVOKE", target = "Lorg/lwjgl/sdl/SDLVideo;SDL_GL_SetAttribute(II)Z", ordinal = 4), index = 1)
    private int jriksseedfinder$plainFramebuffer(int value) {
        return 0;
    }
}
