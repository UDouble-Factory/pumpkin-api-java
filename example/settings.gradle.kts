pluginManagement {
    val pumpkin_api_version = providers.gradleProperty("pumpkin_api_version").get()

    plugins {
        id("io.github.udouble-factory.pumpkin") version pumpkin_api_version
    }

    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "io.github.udouble-factory.pumpkin") {
                useModule("com.github.UDouble-Factory.pumpkin-api-java:gradle-plugin:$pumpkin_api_version")
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
