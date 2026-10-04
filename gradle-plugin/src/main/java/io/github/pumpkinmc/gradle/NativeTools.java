package io.github.pumpkinmc.gradle;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

final class NativeTools {
    private final Path cache;
    private final boolean windows = System.getProperty("os.name").startsWith("Windows");

    NativeTools(Path cache) {
        this.cache = cache;
    }

    Path wasmTools() throws IOException, InterruptedException {
        String expectedVersion = ToolVersions.get("wasmToolsVersion");
        String override = System.getenv("WASM_TOOLS");
        Path result;
        if (override != null && !override.isBlank()) {
            result = Path.of(override);
        } else {
            Path directory = cache.resolve("wasm-tools-" + expectedVersion);
            String name = "wasm-tools-" + expectedVersion + "-" + arch(false) + "-" + os();
            install(directory, "https://github.com/bytecodealliance/wasm-tools/releases/download/v" + expectedVersion + "/" + name + (windows ? ".zip" : ".tar.gz"), "wasm-tools" + suffix());
            result = find(directory, "wasm-tools" + suffix());
        }
        String version = new String(new ProcessBuilder(result.toString(), "--version").start().getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (!version.startsWith("wasm-tools " + expectedVersion)) throw new IOException("Unexpected wasm-tools version: " + version);
        return result;
    }

    Path clang() throws IOException {
        String override = System.getenv("WASI_SDK_PATH");
        if (override != null && !override.isBlank()) {
            Path result = Path.of(override).resolve("bin/clang" + suffix());
            if (!Files.isRegularFile(result)) throw new IOException("WASI_SDK_PATH does not contain bin/clang: " + override);
            return result;
        }
        String version = ToolVersions.get("wasiSdkVersion");
        Path directory = cache.resolve("wasi-sdk-" + version);
        String archive = "wasi-sdk-" + version + "-" + arch(true) + "-" + os() + ".tar.gz";
        install(directory, "https://github.com/WebAssembly/wasi-sdk/releases/download/wasi-sdk-" + version.split("\\.")[0] + "/" + archive, "clang" + suffix());
        return find(directory, "clang" + suffix());
    }

    private String suffix() {
        return windows ? ".exe" : "";
    }

    private String arch(boolean sdk) {
        String value = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        if (value.equals("aarch64") || value.equals("arm64")) return sdk ? "arm64" : "aarch64";
        if (value.equals("amd64") || value.equals("x86_64")) return "x86_64";
        throw new IllegalStateException("Unsupported build architecture: " + value);
    }

    private String os() {
        String name = System.getProperty("os.name");
        if (windows) return "windows";
        if (name.startsWith("Mac")) return "macos";
        if (name.equals("Linux")) return "linux";
        throw new IllegalStateException("Unsupported build platform: " + name);
    }

    private Path find(Path directory, String name) throws IOException {
        try (var files = Files.walk(directory)) {
            return files.filter(path -> path.getFileName().toString().equals(name) && Files.isRegularFile(path)).findFirst().orElseThrow(() -> new IOException("Missing " + name + " under " + directory));
        }
    }

    private void install(Path directory, String url, String executable) throws IOException {
        if (Files.isRegularFile(directory.resolve("installed"))) return;
        Files.createDirectories(directory);
        Path archive = directory.resolve(url.endsWith(".zip") ? "download.zip" : "download.tar.gz");
        if (!Files.isRegularFile(archive)) {
            System.out.println("Downloading " + url);
            Path temporary = directory.resolve("download.part");
            try (InputStream input = URI.create(url).toURL().openStream()) {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(temporary, archive, StandardCopyOption.REPLACE_EXISTING);
        }
        if (url.endsWith(".zip")) {
            try (ZipInputStream input = new ZipInputStream(Files.newInputStream(archive))) {
                java.util.zip.ZipEntry entry;
                while ((entry = input.getNextEntry()) != null) {
                    Path target = entryPath(directory, entry.getName());
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } else {
            try (TarArchiveInputStream input = new TarArchiveInputStream(new GzipCompressorInputStream(Files.newInputStream(archive)))) {
                org.apache.commons.compress.archivers.tar.TarArchiveEntry entry;
                while ((entry = input.getNextEntry()) != null) {
                    Path target = entryPath(directory, entry.getName());
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                    } else if (entry.isFile()) {
                        Files.createDirectories(target.getParent());
                        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                        if ((entry.getMode() & 0111) != 0) target.toFile().setExecutable(true, false);
                    } else if (entry.isSymbolicLink()) {
                        Path link = target.getParent().resolve(entry.getLinkName()).normalize();
                        if (!link.toAbsolutePath().startsWith(directory.toAbsolutePath())) throw new IOException("Archive link escapes tool directory");
                        Files.createDirectories(target.getParent());
                        if (!Files.exists(target)) Files.createSymbolicLink(target, Path.of(entry.getLinkName()));
                    }
                }
            }
        }
        find(directory, executable).toFile().setExecutable(true, false);
        Files.writeString(directory.resolve("installed"), url);
    }

    private Path entryPath(Path directory, String name) throws IOException {
        Path result = directory.resolve(name).toAbsolutePath().normalize();
        if (!result.startsWith(directory.toAbsolutePath().normalize())) throw new IOException("Archive entry escapes tool directory");
        return result;
    }
}
