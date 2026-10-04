plugins {
    java
    id("io.github.udouble-factory.pumpkin")
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
    apiVersion.set(providers.gradleProperty("pumpkin_api_version"))
    pluginClass.set("example.ExamplePlugin")
}
