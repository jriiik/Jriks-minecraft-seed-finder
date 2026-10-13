pluginManagement {
    repositories {
        // Fabric's Maven hosts Loom, the Gradle plugin for Minecraft mods (only the mod project uses it).
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "jriks-seed-finder"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include("engine", "cli", "mod")
