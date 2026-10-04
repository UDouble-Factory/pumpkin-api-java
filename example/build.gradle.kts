plugins {
    java
    id("io.github.udouble-factory.pumpkin") version "<jitpack-version>"
}

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

tasks.compileJava {
    options.release.set(17)
    options.encoding = "UTF-8"
}

pumpkin {
    apiGroup.set("com.github.UDouble-Factory.pumpkin-api-java")
    apiVersion.set("<jitpack-version>")
    pluginClass.set("example.ExamplePlugin")
}
