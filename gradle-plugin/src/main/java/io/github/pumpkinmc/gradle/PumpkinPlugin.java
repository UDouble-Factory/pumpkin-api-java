package io.github.pumpkinmc.gradle;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.Directory;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskProvider;

public final class PumpkinPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getPluginManager().apply("java");
        PumpkinExtension extension = project.getExtensions().create("pumpkin", PumpkinExtension.class);
        JavaPluginExtension javaExtension = project.getExtensions().getByType(JavaPluginExtension.class);
        SourceSet source = javaExtension.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        Configuration nativeApi = project.getConfigurations().create("pumpkinApi");
        nativeApi.setCanBeConsumed(false);
        nativeApi.setTransitive(false);
        Configuration compiler = project.getConfigurations().create("pumpkinCompiler");
        compiler.setCanBeConsumed(false);
        project.getDependencies().add(compiler.getName(), "org.teavm:teavm-tooling:" + ToolVersions.get("teaVmVersion"));
        project.getDependencies().add(compiler.getName(), "org.apache.commons:commons-compress:1.28.0");
        project.getDependencies().add("implementation", "org.teavm:teavm-classlib:" + ToolVersions.get("teaVmVersion"));

        Provider<Directory> bootstrapDirectory = project.getLayout().getBuildDirectory().dir("generated/pumpkin-bootstrap");
        source.getJava().srcDir(bootstrapDirectory);
        var bootstrap = project.getTasks().register("generatePumpkinPluginBootstrap", task -> {
            task.getInputs().property("pluginClass", extension.getPluginClass());
            task.getOutputs().dir(bootstrapDirectory);
            task.doLast(ignored -> {
                String implementation = extension.getPluginClass().get();
                if (!implementation.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")) {
                    throw new GradleException("pumpkin.pluginClass must be a fully qualified Java class name");
                }
                File output = bootstrapDirectory.get().file("plugin/PumpkinBootstrap.java").getAsFile();
                String contents = "package plugin;\n\npublic final class PumpkinBootstrap {\n    private PumpkinBootstrap() {\n    }\n\n    public static void main(String[] args) {\n        pumpkin.runtime.PluginRuntime.start(new " + implementation + "());\n    }\n}\n";
                try {
                    Files.createDirectories(output.toPath().getParent());
                    Files.writeString(output.toPath(), contents, StandardCharsets.UTF_8);
                } catch (IOException failure) {
                    throw new GradleException("Cannot generate Pumpkin plugin bootstrap", failure);
                }
            });
        });
        project.getTasks().named("compileJava").configure(task -> task.dependsOn(bootstrap));

        Provider<Directory> nativeDirectory = project.getLayout().getBuildDirectory().dir("pumpkin/native");
        TaskProvider<Sync> unpack = project.getTasks().register("unpackPumpkinNative", Sync.class, task -> {
            task.from(project.provider(() -> project.zipTree(nativeApi.getSingleFile())));
            task.include("pumpkin/native/**");
            task.into(nativeDirectory);
        });

        File compilerLocation;
        try {
            compilerLocation = new File(NativeCompiler.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException failure) {
            throw new GradleException("Cannot locate Pumpkin compiler", failure);
        }
        File location = compilerLocation;
        TaskProvider<JavaExec> assemble = project.getTasks().register("assemblePluginRelease", JavaExec.class, task -> {
            task.setGroup("build");
            task.setDescription("Compiles the Java plugin to a validated Pumpkin WebAssembly component.");
            task.dependsOn(project.getTasks().named("classes"), unpack);
            task.getMainClass().set(NativeCompiler.class.getName());
            task.setClasspath(compiler.plus(project.files(location)));
            task.setMaxHeapSize("2g");
            task.getInputs().files(source.getRuntimeClasspath()).withPropertyName("pluginClasspath");
            task.getInputs().dir(nativeDirectory);
            task.getInputs().property("pluginClass", extension.getPluginClass());
            task.getOutputs().file(project.getLayout().getBuildDirectory().file(project.getName() + ".wasm"));
            task.doFirst(ignored -> task.setArgs(java.util.List.of(
                source.getRuntimeClasspath().getAsPath(),
                nativeDirectory.get().dir("pumpkin/native").getAsFile().getAbsolutePath(),
                project.getLayout().getBuildDirectory().dir("pumpkin/compiled").get().getAsFile().getAbsolutePath(),
                project.getLayout().getBuildDirectory().file(project.getName() + ".wasm").get().getAsFile().getAbsolutePath(),
                new File(project.getGradle().getGradleUserHomeDir(), "caches/pumpkin-java/tools").getAbsolutePath()
            )));
        });
        project.getTasks().named("assemble").configure(task -> task.dependsOn(assemble));
        project.getTasks().named("check").configure(task -> task.dependsOn(assemble));

        project.afterEvaluate(ignored -> {
            String coordinates = "io.github.udouble-factory:pumpkin-api-java:" + extension.getApiVersion().get();
            project.getDependencies().add("implementation", coordinates);
            project.getDependencies().add(nativeApi.getName(), coordinates);
        });
    }
}
