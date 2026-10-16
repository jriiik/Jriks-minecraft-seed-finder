// Loom, the Gradle plugin of the mod projects, is loaded once for the whole build: Loom asks for that when several
// projects use it. Each mod project then applies the variant its Minecraft version needs.
plugins {
    alias(libs.plugins.loom.unobfuscated) apply false
    alias(libs.plugins.loom.remap) apply false
}
