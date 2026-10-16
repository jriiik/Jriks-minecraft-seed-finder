pluginManagement {
    repositories {
        // Fabric's Maven hosts Loom, the Gradle plugin for Minecraft mods (only the mod projects use it).
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

include("engine", "cli")

// The Fabric mod, once per Minecraft version: the code is shared (mod/src), and each version's project
// (mod/versions/<version>) adds the few lines that differ between the versions and builds that version's jar.
for (minecraft in listOf("26.3", "1.21.11")) {
    include("mod-$minecraft")
    project(":mod-$minecraft").projectDir = file("mod/versions/$minecraft")
}
