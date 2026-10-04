plugins {
    java
    id("io.github.udouble-factory.pumpkin") version "0.1.1"
}

repositories {
    val ciRepository = providers.environmentVariable("PUMPKIN_CI_REPOSITORY").orNull
    if (ciRepository != null) {
        maven { url = uri(ciRepository) }
    } else {
        mavenLocal()
    }
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.compileJava {
    options.release.set(17)
    options.encoding = "UTF-8"
}

pumpkin {
    apiVersion.set("0.1.1")
    pluginClass.set("example.ExamplePlugin")
}
