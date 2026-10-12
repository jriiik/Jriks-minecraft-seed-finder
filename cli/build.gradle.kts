// The command-line app. `./gradlew :cli:fatJar` builds a single runnable file: cli/build/libs/seedfinder.jar
plugins {
    application
}

dependencies {
    implementation(project(":engine"))
    implementation(libs.picocli)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass = "io.github.jriiik.seedfinder.cli.SeedFinderCli"
    applicationName = "seedfinder"
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}

val fatJar = tasks.register<Jar>("fatJar") {
    description = "Builds one runnable jar that contains the CLI and everything it needs."
    group = "build"
    archiveFileName = "seedfinder.jar"
    manifest {
        attributes(
            "Main-Class" to application.mainClass,
            "Implementation-Version" to project.version,
        )
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(configurations.runtimeClasspath)
    from(sourceSets.main.map { it.output })
    from(configurations.runtimeClasspath.map { classpath -> classpath.map { zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/*/module-info.class", "module-info.class")
    }
}

tasks.assemble {
    dependsOn(fatJar)
}

tasks.test {
    useJUnitPlatform()
}
