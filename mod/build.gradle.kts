// The Fabric client mod. It collects observations while the player explores and runs the engine's searches.
// Loom needs Gradle itself to run on Java 25, because Minecraft 26.x is compiled for Java 25.
plugins {
    alias(libs.plugins.fabric.loom)
}

val fabricApiVersion: String = libs.versions.fabric.api.get()

base {
    archivesName = "jriks-seed-finder-mod"
}

dependencies {
    minecraft(libs.minecraft)
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

    // The engine is a plain Java library; Loom puts its jar inside the mod jar. Gson is not bundled: Minecraft
    // 26.3 ships the same Gson version.
    implementation(project(":engine"))
    include(project(":engine"))

    // Minecraft's own libraries (Gson among them) come without their dependencies.
    compileOnly(libs.errorprone.annotations)
    testCompileOnly(libs.errorprone.annotations)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

loom {
    // Makes the private BiomeManager.biomeZoomSeed (the hashed seed the server sent) readable.
    accessWidenerPath = file("src/main/resources/jriksseedfinder.classtweaker")
}

// In-game tests: a separate "gametest" source set and test mod that see the full Fabric API (like a player's
// install); the mod's own classpath does not. Run: xvfb-run -a ./gradlew :mod:runProductionClientGameTest
fabricApi {
    configureTests {
        createSourceSet = true
        modId = "jriksseedfinder-gametest"
        enableGameTests = false
        enableClientGameTests = true
        eula = true
    }
}

dependencies {
    "gametestImplementation"(libs.fabric.api)
    "productionRuntimeMods"(libs.fabric.api)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.processResources {
    val modVersion = project.version.toString()
    inputs.property("version", modVersion)
    filesMatching("fabric.mod.json") { expand("version" to modVersion) }
}

// The guard test reads the finished mod jar (with the engine jar inside it).
tasks.test {
    useJUnitPlatform()
    val modJar = tasks.jar.flatMap { it.archiveFile }
    inputs.file(modJar).withPropertyName("modJar")
    jvmArgumentProviders.add(ModJarArgument(modJar))
}

class ModJarArgument(@get:InputFile @get:PathSensitive(PathSensitivity.NONE) val jar: Provider<RegularFile>) : CommandLineArgumentProvider {
    override fun asArguments() = listOf("-Dseedfinder.modJar=" + jar.get().asFile.absolutePath)
}

// The in-game test the way a player runs the mod: the finished mod jar plus the full Fabric API.
val gametestJar = tasks.register<Jar>("gametestJar") {
    archiveClassifier = "gametest"
    from(sourceSets.named("gametest").map { it.output })
}
tasks.register<net.fabricmc.loom.task.prod.ClientProductionRunTask>("runProductionClientGameTest") {
    mods.from(gametestJar)
    jvmArgs.add("-Dfabric.client.gametest")
    runDir = layout.buildDirectory.dir("run/productionClientGameTest")
}
