// The Fabric client mod for Minecraft 26.3. It collects observations while the player explores and runs the engine's
// searches. The code is shared with the other versions (see mod/shared.gradle.kts); this file adds what is 26.3's own.
// Loom needs Gradle itself to run on Java 25, because Minecraft 26.x is compiled for Java 25. Minecraft 26.x comes with
// its own (Mojang's) names, so the mod jar is used as it is built.
plugins {
    id(libs.plugins.loom.unobfuscated.get().pluginId)
}

loom {
    // Makes the private BiomeManager.biomeZoomSeed (the hashed seed the server sent) readable.
    accessWidenerPath = file("src/main/resources/jriksseedfinder.classtweaker")
}

// In-game tests: a separate "gametest" source set and test mod that see the full Fabric API (like a player's
// install); the mod's own classpath does not. Run: xvfb-run -a ./gradlew :mod-26.3:runProductionClientGameTest
fabricApi {
    configureTests {
        createSourceSet = true
        modId = "jriksseedfinder-gametest"
        enableGameTests = false
        enableClientGameTests = true
        eula = true
    }
}

extra["minecraftVersion"] = "26.3"
extra["javaRelease"] = 25
extra["keyModule"] = "fabric-key-mapping-api-v1"
extra["shippedJar"] = tasks.named<Jar>("jar")
apply(from = "../../shared.gradle.kts")

// This version's entries in gradle/libs.versions.toml.
val catalog = versionCatalogs.named("libs")
val minecraftLibrary = catalog.findLibrary("minecraft-26-3").get()
val fabricApiLibrary = catalog.findLibrary("fabric-api-26-3").get()
val fabricApiVersion = catalog.findVersion("fabric-api-26-3").get().requiredVersion

dependencies {
    minecraft(minecraftLibrary)
    implementation(libs.fabric.loader)

    // Only the Fabric API modules the mod needs. Deliberately NOT fabric-command-api-v2 and NOT
    // fabric-networking-api-v1: they are not on the compile classpath, so code using them cannot compile.
    listOf(
        "fabric-api-base",
        "fabric-key-mapping-api-v1",
        "fabric-lifecycle-events-v1",
        "fabric-rendering-v1",
        "fabric-message-api-v1",
    ).forEach { implementation(fabricApi.module(it, fabricApiVersion)) }

    "gametestImplementation"(fabricApiLibrary)
    "productionRuntimeMods"(fabricApiLibrary)
}

tasks.jar {
    archiveClassifier = "mc26.3"
}

// The in-game test the way a player runs the mod: the jar that ships plus the full Fabric API.
tasks.register<net.fabricmc.loom.task.prod.ClientProductionRunTask>("runProductionClientGameTest") {
    mods.from(tasks.named("gametestJar"))
    jvmArgs.add("-Dfabric.client.gametest")
    runDir = layout.buildDirectory.dir("run/productionClientGameTest")
}
