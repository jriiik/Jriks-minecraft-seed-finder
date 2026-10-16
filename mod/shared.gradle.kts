// Build steps both mod projects share. mod/versions/<version>/build.gradle.kts applies this file after setting the
// values read here (extra properties) and setting up Loom, and adds itself what differs between Minecraft versions.
// (Loom's own settings stay in those files: a shared file like this one can't see Loom's classes.)
//
// The mod's code is shared: mod/src/main, mod/src/test and mod/src/gametest. Each version's project adds its own small
// source folders (src/... next to its build file) for the few calls that differ between versions.

/** The Minecraft version, as the game names it ("26.3", "1.21.11"). */
val minecraftVersion = extra["minecraftVersion"] as String
/** The Java version the mod and the in-game tests are compiled for (the game's own). */
val javaRelease = extra["javaRelease"] as Int
/** The Fabric API module that registers key bindings (renamed in 26.x). */
val keyModule = extra["keyModule"] as String
/** The jar that ships: the one players install, the guard test scans and the in-game test runs. */
val shippedJar = (extra["shippedJar"] as TaskProvider<*>).flatMap { (it as AbstractArchiveTask).archiveFile }

val libs = the<VersionCatalogsExtension>().named("libs")
val shared = rootProject.layout.projectDirectory.dir("mod/src")

configure<BasePluginExtension> {
    archivesName = "jriks-seed-finder-mod"
}

val sourceSets = the<SourceSetContainer>()
for (name in listOf("main", "test", "gametest")) {
    sourceSets.named(name) {
        java.srcDir(shared.dir("$name/java"))
        resources.srcDir(shared.dir("$name/resources"))
    }
}

dependencies {
    // The engine is a plain Java library; Loom puts its jar inside the mod jar. Gson is not bundled: Minecraft
    // ships the same Gson version.
    "implementation"(project(":engine"))
    "include"(project(":engine"))

    // Minecraft's own libraries (Gson among them) come without their dependencies.
    "compileOnly"(libs.findLibrary("errorprone-annotations").get())
    "testCompileOnly"(libs.findLibrary("errorprone-annotations").get())

    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<JavaCompile>().configureEach {
    options.release = javaRelease
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}
// The unit tests run on Gradle's Java 25 and read class files with java.lang.classfile (final since Java 24).
tasks.named<JavaCompile>("compileTestJava") {
    options.release = 25
}

tasks.named<ProcessResources>("processResources") {
    val values = mapOf(
        "version" to project.version.toString(),
        "minecraft" to "~$minecraftVersion",
        "java" to ">=$javaRelease",
        "keyModule" to keyModule,
    )
    inputs.properties(values)
    filesMatching("fabric.mod.json") { expand(values) }
}

// The guard test reads the jar that ships (with the engine jar inside it). The tests run inside the build folder, so
// the game's logger (on the test classpath) writes its logs there, not into the source tree.
tasks.named<Test>("test") {
    useJUnitPlatform()
    inputs.file(shippedJar).withPropertyName("modJar").withPathSensitivity(PathSensitivity.NONE)
    jvmArgumentProviders.add(ModJarArgument(shippedJar))
    val testRunDir = layout.buildDirectory.dir("test-run")
    workingDir(testRunDir)
    doFirst { testRunDir.get().asFile.mkdirs() }
}

class ModJarArgument(@get:InputFile @get:PathSensitive(PathSensitivity.NONE) val jar: Provider<RegularFile>) : CommandLineArgumentProvider {
    override fun asArguments() = listOf("-Dseedfinder.modJar=" + jar.get().asFile.absolutePath)
}

// The in-game test mod (the version's build file runs it together with the jar that ships and the full Fabric API).
tasks.register<Jar>("gametestJar") {
    archiveClassifier = "gametest"
    from(sourceSets.named("gametest").map { it.output })
}
