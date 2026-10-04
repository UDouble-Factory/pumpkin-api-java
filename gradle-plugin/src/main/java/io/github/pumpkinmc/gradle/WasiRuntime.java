package io.github.pumpkinmc.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class WasiRuntime {
    private WasiRuntime() {
    }

    static void prepare(Path generated, Path nativeDirectory) throws IOException {
        Path arrayClasses = generated.resolve("arrayclass.c");
        String arrays = Files.readString(arrayClasses);
        String pool = "static TEAVM_OBJECT_CLASS teavm_dynamicClassPool[TEAVM_DYNAMIC_CLASS_POOL_CAPACITY];";
        if (!arrays.contains(pool)) throw new IOException("Unsupported TeaVM array class runtime layout");
        arrays = arrays.replace(pool, "typedef struct {\n    alignas(8) TEAVM_OBJECT_CLASS value;\n} PumpkinAlignedClass;\n\nstatic PumpkinAlignedClass teavm_dynamicClassPool[TEAVM_DYNAMIC_CLASS_POOL_CAPACITY];");
        arrays = arrays.replace("&teavm_dynamicClassPool[teavm_dynamicClassPoolSize++]", "&teavm_dynamicClassPool[teavm_dynamicClassPoolSize++].value");
        arrays = arrays.replace("&teavm_dynamicClassPool[index].parent", "&teavm_dynamicClassPool[index].value.parent");
        Files.writeString(arrayClasses, arrays);
        Path memory = generated.resolve("memory.c");
        String source = Files.readString(memory);
        int start = source.indexOf("#if defined(__EMSCRIPTEN__)");
        int end = source.indexOf("#elif TEAVM_UNIX", start);
        if (start < 0 || end < 0) throw new IOException("Unsupported TeaVM memory runtime layout");
        source = source.substring(0, start) + "#if defined(__wasi__)\n    static void* teavm_virtualAlloc(int64_t size) {\n        return calloc(1, (size_t) size);\n    }\n    static void teavm_virtualCommit(void* address, int64_t size) {\n    }\n    static void teavm_virtualUncommit(void* address, int64_t size) {\n        memset(address, 0, (size_t) size);\n    }\n    static int64_t teavm_pageSize() {\n        return 65536;\n    }\n" + source.substring(end);
        Files.writeString(memory, source);
        Files.copy(nativeDirectory.resolve("pumpkin_fiber.c"), generated.resolve("fiber.c"), StandardCopyOption.REPLACE_EXISTING);
        Path files = generated.resolve("file.c");
        source = Files.readString(files).replace("#include <pwd.h>", "");
        source = source.replace("struct passwd *pw = getpwuid(getuid());", "const char *home = getenv(\"HOME\");\n    if (home == NULL) home = \".\";");
        source = source.replace("pw->pw_dir", "(char *) home");
        source = source.replace("pathconf(\".\", _PC_PATH_MAX)", "4096");
        Files.writeString(files, source);
        Path strings = generated.resolve("string.c");
        source = Files.readString(strings);
        source = rename(source, "TeaVM_String* teavm_cToString(char*", "static TeaVM_String* pumpkin_legacyCToString(char*");
        source = rename(source, "char16_t* teavm_mbToChar16(char*", "static char16_t* pumpkin_legacyMbToChar16(char*");
        source = rename(source, "char* teavm_char16ToMb(char16_t*", "static char* pumpkin_legacyChar16ToMb(char16_t*");
        Files.copy(nativeDirectory.resolve("pumpkin_utf8.inc"), generated.resolve("pumpkin_utf8.inc"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(strings, source + "\n#include \"pumpkin_utf8.inc\"\n");
    }

    private static String rename(String source, String expected, String replacement) throws IOException {
        if (!source.contains(expected)) throw new IOException("Unsupported TeaVM string runtime layout: " + expected);
        return source.replace(expected, replacement);
    }
}
