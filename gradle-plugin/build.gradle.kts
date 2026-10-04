import java.util.Properties

plugins {
    `java-gradle-plugin`
    `maven-publish`
}

val toolVersions = Properties().apply {
    rootProject.file("gradle/tool-versions.properties").inputStream().use { load(it) }
}

group = "io.github.udouble-factory"
version = providers.gradleProperty("pumpkinApiVersion").getOrElse("0.1.1")

base {
    archivesName.set("pumpkin-api-java-gradle-plugin")
}

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation("org.teavm:teavm-tooling:${toolVersions.getProperty("teaVmVersion")}")
    implementation("org.apache.commons:commons-compress:1.28.0")
}

tasks.compileJava {
    options.release.set(17)
    options.encoding = "UTF-8"
}

tasks.processResources {
    from(rootProject.file("gradle/tool-versions.properties")) {
        into("io/github/pumpkinmc/gradle")
    }
}

gradlePlugin {
    plugins {
        create("pumpkinPlugin") {
            id = "io.github.udouble-factory.pumpkin"
            implementationClass = "io.github.pumpkinmc.gradle.PumpkinPlugin"
            displayName = "Pumpkin Java plugin build"
            description = "Builds Java Pumpkin plugins as WebAssembly components."
        }
    }
}
