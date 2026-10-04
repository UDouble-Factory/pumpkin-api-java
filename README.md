# Pumpkin Plugin API for Java

Build Java plugins for [Pumpkin](https://github.com/Pumpkin-MC/Pumpkin) as WebAssembly components.

The `api` module packages generated Java bindings, the C export bridge, and the WIT snapshot and WASI adapter needed to build a component. The `gradle-plugin` module compiles Java through TeaVM's C backend and WASI SDK. Plugin authors do not need Rust, Python, `wit-bindgen`, or a WIT checkout.

This fork is configured for JitPack distribution of the API and Gradle plugin. It is not published to Maven Central or the Gradle Plugin Portal. Use a Git tag or commit containing the JitPack configuration, and confirm that its [JitPack build](https://jitpack.io/#UDouble-Factory/pumpkin-api-java) succeeds before using it. Local development packages are described under [API development](#api-development).

## Create a plugin

Create a Gradle project with a Gradle 9.x wrapper and JDK 17 or later.

You can copy the [plugin template](example/) to a new directory, or create the following files yourself. The template is an independent project that downloads the published packages from JitPack.

In your plugin project's `gradle.properties`, set the version used by both the API and Gradle plugin:

```properties
pumpkin_api_version=<jitpack-version>
```

Replace `<jitpack-version>` with a successfully built Git tag or commit. A tag's `v` prefix is part of the version. Existing tags created before the JitPack configuration do not include it.

In `settings.gradle.kts`:

```kotlin
pluginManagement {
    val pumpkin_api_version = providers.gradleProperty("pumpkin_api_version").get()

    plugins {
        id("io.github.udouble-factory.pumpkin") version pumpkin_api_version
    }

    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "io.github.udouble-factory.pumpkin") {
                useModule("com.github.UDouble-Factory.pumpkin-api-java:gradle-plugin:$pumpkinApiVersion")
            }
        }
    }

    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "my-plugin"
```

In `build.gradle.kts`:

```kotlin
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
    pluginClass.set("example.MyPlugin")
}
```

Put your implementation in `src/main/java/example/MyPlugin.java`:

```java
package example;

import java.util.List;
import plugin.PluginMetadata;
import plugin.PumpkinPlugin;
import pumpkin.context.Context;
import pumpkin.logging.Level;
import pumpkin.logging.Logging;
import pumpkin.runtime.Result;

public final class MyPlugin extends PumpkinPlugin {
    @Override
    public PluginMetadata metadata() {
        return new PluginMetadata("my-plugin", "0.1.0", List.of("Your name"), "My first Java plugin", List.of(), List.of());
    }

    @Override
    public Result<Void, String> onLoad(Context context) {
        try (context) {
            Logging.log(Level.INFO, "Hello from Java!");
        }
        return Result.success(null);
    }
}
```

The configured class must extend `PumpkinPlugin` and be constructible without arguments. Override the callbacks your plugin uses; most optional handler defaults throw if invoked without an implementation.

Close owned resources with try-with-resources when finished. Passing an `own` argument transfers ownership; resources returned from a callback must remain open until it returns. `u32` values use `long`, and `u64` values use all 64 bits of `long` with Java's unsigned operations. Optional and error results use `Option<T>` and `Result<T, E>`.

## Build and load

From your plugin project:

```sh
./gradlew build
```

The Gradle plugin downloads pinned WASI SDK and `wasm-tools` binaries, compiles the Java code, assembles the component, and validates it. The component is written to `build/<project-name>.wasm` (`build/my-plugin.wasm` above). Copy it into the Pumpkin server's `plugins` directory and start the server. A JVM is not required on the server.

To assemble only the component, run `./gradlew assemblePluginRelease`. Generated files live under `build/`; downloaded tools are cached under the Gradle user home in `caches/pumpkin-java/tools`.

## How the dependency and bootstrap work

The API publishes a Java JAR containing the bindings and native build resources. The Gradle plugin adds the API dependency, extracts those resources, and generates `PumpkinBootstrap` from `pumpkin.pluginClass`. You do not add a separate `implementation(...)` dependency or run binding generation yourself.

On the first exported callback, the bridge constructs and caches your plugin instance. Later callbacks use the same instance. Tasks created with `tasks().afterTicks(...)` are dispatched automatically and cancelled when the plugin unloads.

## Server compatibility

Choose an API version compatible with your Pumpkin server. Errors such as `no export ... found`, `type-checking export func ...`, or missing imports indicate a possible mismatch between the plugin's packaged WIT and the server's contract. Update `pumpkin.apiVersion` to a compatible release and rebuild the component.

The current WIT snapshot is `39bf330`. Matching the `0.1.0` package label alone is insufficient. Java library support follows TeaVM, so arbitrary JVM libraries, JNI, and dynamic class loading are not automatically supported.

File access requires a server-granted `fs.read.data` or `fs.write.data` permission in plugin metadata. With the pinned TeaVM version, use `Files.readAllBytes` and explicit decoding instead of `Files.readString`.

## API development

Contributors building the API itself need JDK 17 or later and Python 3.12+. Gradle downloads pinned binding tools and generates the Java and C bindings from WIT; Rust is not required.

From this repository, generate the bindings and publish both development packages locally:

```sh
git submodule update --init --recursive
./gradlew :api:publishToMavenLocal :gradle-plugin:publishToMavenLocal
```

For a local consumer, remove the JitPack `resolutionStrategy` above, use `mavenLocal()` in both repository blocks, set `pumpkin_api_version=0.1.1` in the consumer's `gradle.properties`, and omit `pumpkin.apiGroup` so it defaults to `io.github.udouble-factory`. After republishing changes under the same version, run `./gradlew build --refresh-dependencies` in the consumer.

The [example](example/) directory is a standalone plugin template. CI copies it to `build/template-check` and changes only that copy to use the temporary Maven repository and development version, then verifies the resulting WebAssembly component.

The root build checks `api` and `gradle-plugin` automatically. Tool versions live in `gradle/tool-versions.properties`. Existing installations can be selected with `WASI_SDK_PATH`, `WASM_TOOLS`, and, for API generation, `WIT_BINDGEN`.

### JitPack publishing

`jitpack.yml` selects JDK 21, initializes the WIT submodule, and uses uv to provide Python 3.13 while running the Gradle build and local Maven publication tasks. The JitPack environment supplies the repository group, name, and requested version through `GROUP`, `ARTIFACT`, and `VERSION`; these become `pumpkinMavenGroup` and `pumpkin_api_version` Gradle properties for both modules.

The published module coordinates are `com.github.UDouble-Factory.pumpkin-api-java:pumpkin-api-java:<jitpack-version>` for the API and `com.github.UDouble-Factory.pumpkin-api-java:gradle-plugin:<jitpack-version>` for the build plugin. The consumer uses `useModule(...)` to resolve the plugin directly without relying on its original plugin marker coordinates.

Push the configuration and create a new tag, or select that commit on [JitPack](https://jitpack.io/#UDouble-Factory/pumpkin-api-java), then request a build and check its log. To use the published packages, copy the template, set `pumpkin_api_version` in its `gradle.properties` to that tag or commit, and run from the copied directory:

```sh
./gradlew build
```

### Updating the WIT

API maintainers should update the `wit` submodule to the revision compatible with the target Pumpkin server, then regenerate and republish the API. Commit the updated submodule revision with any required callback changes so builds use the same contract.

## Attributions

This Java adaptation is based on [Pumpkin's Kotlin API](https://github.com/Pumpkin-MC/pumpkin-api-kt). The original project was primarily derived from [@jmrtsh](https://github.com/jmrtsh)'s work on [Kotlin/sample-wasi-http-kotlin](https://github.com/Kotlin/sample-wasi-http-kotlin), which is licensed under [Apache-2.0](https://github.com/Kotlin/sample-wasi-http-kotlin/blob/main/LICENSE).
