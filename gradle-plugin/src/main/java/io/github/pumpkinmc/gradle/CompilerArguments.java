package io.github.pumpkinmc.gradle;

import java.io.File;
import java.util.List;
import org.gradle.api.file.Directory;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.process.CommandLineArgumentProvider;

final class CompilerArguments implements CommandLineArgumentProvider {
    private final FileCollection pluginClasspath;
    private final Provider<Directory> nativeDirectory;
    private final Provider<Directory> workDirectory;
    private final Provider<RegularFile> component;
    private final File cacheDirectory;
    private final Provider<String> wasiSdkPath;
    private final Provider<String> wasmTools;

    CompilerArguments(FileCollection pluginClasspath, Provider<Directory> nativeDirectory, Provider<Directory> workDirectory, Provider<RegularFile> component, File cacheDirectory, Provider<String> wasiSdkPath, Provider<String> wasmTools) {
        this.pluginClasspath = pluginClasspath;
        this.nativeDirectory = nativeDirectory;
        this.workDirectory = workDirectory;
        this.component = component;
        this.cacheDirectory = cacheDirectory;
        this.wasiSdkPath = wasiSdkPath;
        this.wasmTools = wasmTools;
    }

    @Classpath
    public FileCollection getPluginClasspath() {
        return pluginClasspath;
    }

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public Provider<Directory> getNativeDirectory() {
        return nativeDirectory;
    }

    @Internal
    public Provider<Directory> getWorkDirectory() {
        return workDirectory;
    }

    @OutputFile
    public Provider<RegularFile> getComponent() {
        return component;
    }

    @Internal
    public File getCacheDirectory() {
        return cacheDirectory;
    }

    @Input
    @Optional
    public Provider<String> getWasiSdkPath() {
        return wasiSdkPath;
    }

    @Input
    @Optional
    public Provider<String> getWasmTools() {
        return wasmTools;
    }

    @Override
    public Iterable<String> asArguments() {
        return List.of(
            pluginClasspath.getAsPath(),
            nativeDirectory.get().dir("pumpkin/native").getAsFile().getAbsolutePath(),
            workDirectory.get().getAsFile().getAbsolutePath(),
            component.get().getAsFile().getAbsolutePath(),
            cacheDirectory.getAbsolutePath()
        );
    }
}
