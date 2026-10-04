rootProject.name = "pumpkin-api-java"

pluginManagement {
    resolutionStrategy {
        repositories {
            gradlePluginPortal()
        }
    }
}

include("api")
include("gradle-plugin")
