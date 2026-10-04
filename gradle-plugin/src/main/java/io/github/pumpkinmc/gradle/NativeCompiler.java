package io.github.pumpkinmc.gradle;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.teavm.tooling.ConsoleTeaVMToolLog;
import org.teavm.tooling.TeaVMTargetType;
import org.teavm.tooling.TeaVMTool;
import org.teavm.tooling.TeaVMProblemRenderer;
import org.teavm.vm.TeaVMOptimizationLevel;

public final class NativeCompiler {
    private NativeCompiler() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("Expected classpath, native directory, work directory, output component, tool cache");
        Path nativeDirectory = Path.of(args[1]);
        Path work = Path.of(args[2]).toAbsolutePath().normalize();
        Path output = Path.of(args[3]);
        Path generated = work.resolve("java-c");
        if (!generated.toAbsolutePath().normalize().startsWith(work)) throw new IOException("Generated sources escape the work directory");
        if (Files.exists(generated)) {
            try (var paths = Files.walk(generated)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        Files.createDirectories(generated);
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
            tool.generate();
            if (!tool.getProblemProvider().getSevereProblems().isEmpty()) {
                TeaVMProblemRenderer.describeProblems(tool.getDependencyInfo().getCallGraph(), tool.getProblemProvider(), new ConsoleTeaVMToolLog(false));
                throw new IllegalStateException("TeaVM compilation failed");
            }
        }
        WasiRuntime.prepare(generated, nativeDirectory);
        NativeTools tools = new NativeTools(Path.of(args[4]));
        Path clang = tools.clang();
        Path wasmTools = tools.wasmTools();
        Path core = work.resolve("plugin.core.wasm");
        List<String> command = new ArrayList<>(List.of(clang.toString(), "-O2", "-fno-strict-aliasing", "-fwrapv", "-mllvm", "-wasm-enable-sjlj", "-mllvm", "-wasm-use-legacy-eh=false", "-D_WASI_EMULATED_MMAN", "-D_WASI_EMULATED_PROCESS_CLOCKS", "-Wno-parentheses-equality", "-Wno-tautological-compare", "-Wno-constant-conversion", "-I", nativeDirectory.toString(), "-include", nativeDirectory.resolve("pumpkin_runtime.h").toString(), "-mexec-model=reactor", generated.resolve("all.c").toString(), nativeDirectory.resolve("pumpkin_runtime.c").toString(), nativeDirectory.resolve("pumpkin_bridge.c").toString(), nativeDirectory.resolve("plugin.c").toString(), nativeDirectory.resolve("plugin_component_type.o").toString(), "-lsetjmp", "-lwasi-emulated-mman", "-lwasi-emulated-process-clocks", "-Wl,-z,stack-size=2097152", "-o", core.toString()));
        run(command, work.resolve("clang.log"));
        run(List.of(wasmTools.toString(), "component", "new", core.toString(), "--adapt", "wasi_snapshot_preview1=" + nativeDirectory.resolve("wasi_snapshot_preview1.reactor.wasm"), "-o", output.toString()), work.resolve("component.log"));
        run(List.of(wasmTools.toString(), "validate", output.toString()), work.resolve("validate.log"));
        System.out.println("Built Pumpkin component: " + output + " (" + Files.size(output) + " bytes)");
    }

    private static void run(List<String> command, Path log) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        int exit = process.waitFor();
        if (exit != 0) {
            String output = Files.readString(log, StandardCharsets.UTF_8);
            throw new IOException("Command failed (" + exit + "): " + command.get(0) + "\n" + output);
        }
    }
}
