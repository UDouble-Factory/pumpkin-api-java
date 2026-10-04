pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "io.github.udouble-factory.pumpkin") {
                useModule("com.github.UDouble-Factory.pumpkin-api-java:gradle-plugin:${requested.version}")
            }
        }
    }

    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "pumpkin-example"
