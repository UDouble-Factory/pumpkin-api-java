plugins {
    base
}

subprojects {
    group = providers.gradleProperty("pumpkinMavenGroup").get()
    version = providers.gradleProperty("pumpkin_api_version").get()
}

tasks.named("build") {
    dependsOn(":api:build", ":gradle-plugin:build")
}

tasks.named("clean") {
    dependsOn(":api:clean", ":gradle-plugin:clean")
}
