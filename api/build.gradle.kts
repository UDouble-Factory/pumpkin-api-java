import java.util.Properties

plugins {
    `java-library`
    `maven-publish`
}

val toolVersions = Properties().apply {
    rootProject.file("gradle/tool-versions.properties").inputStream().use { load(it) }
}

group = providers.gradleProperty("pumpkinMavenGroup").getOrElse("io.github.udouble-factory")
version = providers.gradleProperty("pumpkinApiVersion").getOrElse("0.1.1")

base {
    archivesName.set("pumpkin-api-java")
}

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

dependencies {
    api("org.teavm:teavm-interop:${toolVersions.getProperty("teaVmVersion")}")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val generatedBindings = layout.buildDirectory.dir("generated")
val generateWitBindings by tasks.registering(Exec::class) {
    inputs.dir(rootProject.layout.projectDirectory.dir("wit/v0.1"))
    inputs.files(rootProject.fileTree("binding-generator") { include("*.py") })
    inputs.file(rootProject.layout.projectDirectory.file("gradle/tool-versions.properties"))
    outputs.dir(generatedBindings)

    commandLine(
        if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3",
        "-X", "utf8",
        rootProject.file("binding-generator/build.py").absolutePath,
        "--wit", rootProject.file("wit/v0.1").absolutePath,
        "--output", generatedBindings.get().asFile.absolutePath,
        "--cache", layout.projectDirectory.dir("tools").asFile.absolutePath,
        "--versions", rootProject.file("gradle/tool-versions.properties").absolutePath,
    )
}

sourceSets.main {
    java.srcDir(generatedBindings.map { it.dir("java") })
}

tasks.compileTestJava {
    options.encoding = "UTF-8"
}

tasks.compileJava {
    dependsOn(generateWitBindings)
    options.release.set(17)
    options.encoding = "UTF-8"
}

tasks.processResources {
    dependsOn(generateWitBindings)
    from(generatedBindings.map { it.dir("native") }) {
        into("pumpkin/native")
    }
    from(generatedBindings.map { it.file("manifest.json") }) {
        into("pumpkin")
    }
    from(rootProject.file("wit/v0.1")) {
        into("pumpkin/wit")
    }
    from(rootProject.file("wasi_snapshot_preview1.reactor.wasm")) {
        into("pumpkin/native")
    }
}

tasks.named("sourcesJar") {
    dependsOn(generateWitBindings)
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "pumpkin-api-java"
        }
    }
}
