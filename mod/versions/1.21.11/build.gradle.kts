// The Fabric client mod for Minecraft 1.21.11. The code is shared with the other versions (see mod/shared.gradle.kts);
// this file adds what is 1.21.11's own. Minecraft 1.21.11 is obfuscated: the code uses Mojang's names (official
// mappings), and the jar that ships is turned ("remapped") into Fabric's intermediary names, which the game runs with.
plugins {
    id(libs.plugins.loom.remap.get().pluginId)
}

loom {
    // Makes the private BiomeManager.biomeZoomSeed (the hashed seed the server sent) readable.
    accessWidenerPath = file("src/main/resources/jriksseedfinder.classtweaker")
}

// In-game tests: a separate "gametest" source set and test mod that see the full Fabric API (like a player's
// install); the mod's own classpath does not. Run: xvfb-run -a ./gradlew :mod-1.21.11:runProductionClientGameTest
fabricApi {
    configureTests {
        createSourceSet = true
        modId = "jriksseedfinder-gametest"
        enableGameTests = false
        enableClientGameTests = true
        eula = true
    }
}

extra["minecraftVersion"] = "1.21.11"
extra["javaRelease"] = 21
extra["keyModule"] = "fabric-key-binding-api-v1"
extra["shippedJar"] = tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar")
apply(from = "../../shared.gradle.kts")

// This version's entries in gradle/libs.versions.toml.
val catalog = versionCatalogs.named("libs")
val minecraftLibrary = catalog.findLibrary("minecraft-1-21-11").get()
val fabricApiLibrary = catalog.findLibrary("fabric-api-1-21-11").get()
val fabricApiVersion = catalog.findVersion("fabric-api-1-21-11").get().requiredVersion

dependencies {
    minecraft(minecraftLibrary)
    mappings(loom.officialMojangMappings())
    modImplementation(libs.fabric.loader)

    // Only the Fabric API modules the mod needs. Deliberately NOT fabric-command-api-v2 and NOT
    // fabric-networking-api-v1: they are not on the compile classpath, so code using them cannot compile.
    listOf(
        "fabric-api-base",
        "fabric-key-binding-api-v1",
        "fabric-lifecycle-events-v1",
        "fabric-rendering-v1",
        "fabric-message-api-v1",
    ).forEach { modImplementation(fabricApi.module(it, fabricApiVersion)) }

    "modGametestImplementation"(fabricApiLibrary)
    "productionRuntimeMods"(fabricApiLibrary)
}

tasks.remapJar {
    archiveClassifier = "mc1.21.11"
}

// The guard test reads the remapped jar: it turns the intermediary names back into Minecraft's own with the mappings
// the jar was remapped with, so the same rules see the same names as for 26.x.
tasks.test {
    val mappings = layout.file(provider { loom.mappingsFile })
    inputs.file(mappings).withPropertyName("mappings").withPathSensitivity(PathSensitivity.NONE)
    jvmArgumentProviders.add(MappingsArgument(mappings))
}

class MappingsArgument(@get:InputFile @get:PathSensitive(PathSensitivity.NONE) val file: Provider<RegularFile>) : CommandLineArgumentProvider {
    override fun asArguments() = listOf("-Dseedfinder.mappings=" + file.get().asFile.absolutePath)
}

// The in-game test the way a player runs the mod: the jar that ships plus the full Fabric API. The game runs with
// intermediary names, so the test mod is remapped like the mod itself.
val remapGametestJar = tasks.register<net.fabricmc.loom.task.RemapJarTask>("remapGametestJar") {
    inputFile = tasks.named<Jar>("gametestJar").flatMap { it.archiveFile }
    archiveClassifier = "gametest-remapped"
    classpath.from(configurations.named("gametestCompileClasspath"))
}
tasks.register<net.fabricmc.loom.task.prod.ClientProductionRunTask>("runProductionClientGameTest") {
    mods.from(remapGametestJar)
    jvmArgs.add("-Dfabric.client.gametest")
    // Fabric API 0.141.6's in-game test harness for 1.21.11 never finishes loading a world with its packet
    // synchronizer on (also without this mod; the harness was rewritten for 26.x), so it is switched off. It only
    // makes the client and the built-in server wait for each other's packets; the test waits for what it checks.
    jvmArgs.add("-Dfabric.client.gametest.disableNetworkSynchronizer")
    runDir = layout.buildDirectory.dir("run/productionClientGameTest")
}
