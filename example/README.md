# Java plugin template

Copy this directory to start an independent Pumpkin plugin project. Use JDK 17 or later; the Gradle wrapper is included.

1. In `build.gradle.kts`, replace both `<jitpack-version>` placeholders with the same successfully built JitPack tag or commit. Keep the `v` prefix if the tag has one.
2. In `settings.gradle.kts`, change `rootProject.name` to your plugin's name.
3. Edit `src/main/java/example/ExamplePlugin.java`, including its metadata. If you rename or move the class, update `pumpkin.pluginClass` in `build.gradle.kts`.

Open the copied directory in IntelliJ, or build from that directory:

```sh
./gradlew build
```

On Windows, use `./gradlew.bat build`. Copy `build/<project-name>.wasm` into the matching Pumpkin server's `plugins` directory.

The API and build plugin are downloaded from [JitPack](https://jitpack.io/#UDouble-Factory/pumpkin-api-java). You do not need the API source repository or a local Maven publication.
