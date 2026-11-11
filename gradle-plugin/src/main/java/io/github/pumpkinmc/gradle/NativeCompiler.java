package io.github.pumpkinmc.gradle;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.teavm.tooling.ConsoleTeaVMToolLog;
import org.teavm.tooling.TeaVMTargetType;
import org.teavm.tooling.TeaVMTool;
import org.teavm.tooling.TeaVMProblemRenderer;
import org.teavm.vm.TeaVMOptimizationLevel;

public final class NativeCompiler {
    private static final int UNITS = 32;
    private static final List<String> API_SOURCES = List.of("pumpkin_runtime.c", "pumpkin_bridge.c", "plugin.c");

    private NativeCompiler() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("Expected classpath, native directory, work directory, output component, cache directory");
        Path nativeDirectory = Path.of(args[1]);
        Path work = Path.of(args[2]).toAbsolutePath().normalize();
        Path output = Path.of(args[3]);
        Path cache = Path.of(args[4]);
        Path generated = work.resolve("java-c");
        if (!generated.toAbsolutePath().normalize().startsWith(work)) throw new IOException("Generated sources escape the work directory");
        clear(generated);
        Files.createDirectories(output.toAbsolutePath().getParent());
        List<URL> urls = new ArrayList<>();
        for (String entry : args[0].split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            urls.add(Path.of(entry).toUri().toURL());
        }
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), NativeCompiler.class.getClassLoader())) {
            TeaVMTool tool = new TeaVMTool();
            tool.setClassLoader(loader);
            tool.setTargetType(TeaVMTargetType.C);
            tool.setTargetDirectory(generated.toFile());
            tool.setMainClass("plugin.PumpkinBootstrap");
            tool.setEntryPointName("pumpkin_java_main");
            tool.setOptimizationLevel(TeaVMOptimizationLevel.ADVANCED);
            tool.setMinHeapSize(4 * 1024 * 1024);
            tool.setMaxHeapSize(128 * 1024 * 1024);
            tool.setLog(new ConsoleTeaVMToolLog(false));
            tool.getTransformers().add(ShiftCounts.class.getName());
            tool.generate();
            if (!tool.getProblemProvider().getSevereProblems().isEmpty()) {
                TeaVMProblemRenderer.describeProblems(tool.getDependencyInfo().getCallGraph(), tool.getProblemProvider(), new ConsoleTeaVMToolLog(false));
                throw new IllegalStateException("TeaVM compilation failed");
            }
        }
        WasiRuntime.prepare(generated, nativeDirectory);
        NativeTools tools = new NativeTools(cache.resolve("tools"));
        Path clang = tools.clang();
        Path wasmTools = tools.wasmTools();
        Path core = work.resolve("plugin.core.wasm");
        List<String> flags = List.of("-O2", "-fno-strict-aliasing", "-fwrapv", "-fno-strict-float-cast-overflow", "-mllvm", "-wasm-enable-sjlj", "-mllvm", "-wasm-use-legacy-eh=false", "-D_WASI_EMULATED_MMAN", "-D_WASI_EMULATED_PROCESS_CLOCKS", "-Wno-parentheses-equality", "-Wno-tautological-compare", "-Wno-constant-conversion");

        List<String> command = new ArrayList<>();
        command.add(clang.toString());
        command.addAll(flags);
        command.add("-mexec-model=reactor");
        for (Path object : compile(clang, flags, generated, nativeDirectory, work.resolve("objects"), cache.resolve("objects"))) {
            command.add(object.toString());
        }
        command.addAll(List.of(nativeDirectory.resolve("plugin_component_type.o").toString(), "-lsetjmp", "-lwasi-emulated-mman", "-lwasi-emulated-process-clocks", "-Wl,-z,stack-size=2097152", "-o", core.toString()));

        run(command, null, work.resolve("clang.log"));
        run(List.of(wasmTools.toString(), "component", "new", core.toString(), "--adapt", "wasi_snapshot_preview1=" + nativeDirectory.resolve("wasi_snapshot_preview1.reactor.wasm"), "-o", output.toString()), null, work.resolve("component.log"));
        run(List.of(wasmTools.toString(), "validate", output.toString()), null, work.resolve("validate.log"));
        System.out.println("Built Pumpkin component: " + output + " (" + Files.size(output) + " bytes)");
    }

    private static List<Path> compile(Path clang, List<String> flags, Path generated, Path nativeDirectory, Path objects, Path cache) throws IOException, InterruptedException {
        clear(objects);

        List<String> preamble = new ArrayList<>();
        List<String> includes = new ArrayList<>();
        for (String line : Files.readAllLines(generated.resolve("all.c"), StandardCharsets.UTF_8)) {
            (line.startsWith("#include") ? includes : preamble).add(line);
        }

        List<Callable<Path>> tasks = new ArrayList<>();
        int size = Math.max(1, (includes.size() + UNITS - 1) / UNITS);
        for (int start = 0; start < includes.size(); start += size) {
            String name = "pumpkin_unit_" + tasks.size();
            Path source = generated.resolve(name + ".c");
            Path object = objects.resolve(name + ".o");
            List<String> lines = new ArrayList<>(preamble);
            lines.addAll(includes.subList(start, Math.min(includes.size(), start + size)));
            Files.write(source, lines, StandardCharsets.UTF_8);

            List<String> command = new ArrayList<>();
            command.add(clang.toString());
            command.addAll(flags);
            command.addAll(List.of("-I", nativeDirectory.toString(), "-include", nativeDirectory.resolve("pumpkin_runtime.h").toString(), "-c", source.toString(), "-o", object.toString()));
            tasks.add(() -> {
                run(command, null, objects.resolve(name + ".log"));
                return object;
            });
        }

        String compiler = new String(new ProcessBuilder(clang.toString(), "--version").start().getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        for (String source : API_SOURCES) {
            tasks.add(() -> compileApiSource(clang, compiler, flags, nativeDirectory, source, objects.resolve(source + ".log"), cache));
        }

        ExecutorService pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        try {
            List<Path> result = new ArrayList<>();
            for (Future<Path> item : pool.invokeAll(tasks)) {
                result.add(item.get());
            }
            return result;
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof IOException cause) throw cause;
            throw new IOException("Cannot compile generated sources", failure.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    private static Path compileApiSource(Path clang, String compiler, List<String> flags, Path nativeDirectory, String source, Path log, Path cache) throws IOException, InterruptedException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }

        digest.update((compiler + "\n" + String.join("\n", flags) + "\n" + source + "\n").getBytes(StandardCharsets.UTF_8));
        digest.update(Files.readAllBytes(nativeDirectory.resolve(source)));
        List<Path> headers;
        try (var files = Files.list(nativeDirectory)) {
            headers = files.filter(path -> path.getFileName().toString().endsWith(".h")).sorted().toList();
        }
        for (Path header : headers) {
            digest.update((header.getFileName() + "\n").getBytes(StandardCharsets.UTF_8));
            digest.update(Files.readAllBytes(header));
        }

        String key = HexFormat.of().formatHex(digest.digest());
        Path object = cache.resolve(key + ".o");
        if (Files.isRegularFile(object)) return object;

        Files.createDirectories(cache);
        Path temporary = Files.createTempFile(cache, key, ".tmp");
        try {
            List<String> command = new ArrayList<>();
            command.add(clang.toString());
            command.addAll(flags);
            command.addAll(List.of("-I", ".", "-include", "pumpkin_runtime.h", "-c", source, "-o", temporary.toString()));
            run(command, nativeDirectory, log);

            try {
                Files.move(temporary, object, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException failure) {
                if (!Files.isRegularFile(object)) throw failure;
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return object;
    }

    private static void clear(Path directory) throws IOException {
        if (Files.exists(directory)) {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        Files.createDirectories(directory);
    }

    private static void run(List<String> command, Path directory, Path log) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).directory(directory == null ? null : directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        int exit = process.waitFor();
        if (exit != 0) {
            String output = Files.readString(log, StandardCharsets.UTF_8);
            throw new IOException("Command failed (" + exit + "): " + command.get(0) + "\n" + output);
        }
    }
}
