package io.github.pumpkinmc.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class WasiRuntime {
    private static final Pattern CLONE_ITEM_SIZE = Pattern.compile("(\\w+_RuntimeClass_isPrimitive)\\((\\w+)\\)\\) \\? sizeof\\(void\\*\\) : TEAVM_FIELD\\(TEAVM_FIELD\\(\\2, TeaVM_Class, itemType\\), TeaVM_Class, size\\)");
    private static final Pattern ARRAY_ALLOCATION = Pattern.compile("void\\* \\w+_Allocator_allocateArray\\(void\\* \\w+, int32_t (\\w+)\\) \\{");

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

        fixArrayDataOffset(generated);
        fixArrayClone(generated);
        guardArraySize(generated);
    }

    private static void fixArrayDataOffset(Path generated) throws IOException {
        Path core = generated.resolve("core.h");
        String source = Files.readString(core);
        source = rename(source, "((type*) TEAVM_ALIGN((((TeaVM_Array*) (array)) + 1), sizeof(type)))", "((type*) ((char*) (array) + (sizeof(TeaVM_Array) + sizeof(type) - 1) / sizeof(type) * sizeof(type)))");
        Files.writeString(core, source);

        Path reflection = generated.resolve("reflection.c");
        source = Files.readString(reflection);
        source = rename(source, "TEAVM_ALIGN(((TeaVM_Array*) array) + 1, size)", "((char*) array + (sizeof(TeaVM_Array) + size - 1) / size * size)");
        Files.writeString(reflection, source);
    }

    private static void fixArrayClone(Path generated) throws IOException {
        Path object = generated.resolve("classes/java/lang/Object.c");
        String source = Files.readString(object);
        Matcher itemSize = CLONE_ITEM_SIZE.matcher(source);
        if (!itemSize.find()) throw new IOException("Unsupported TeaVM array clone layout");

        source = itemSize.replaceAll("$1(TEAVM_FIELD($2, TeaVM_Class, itemType))) ? sizeof(void*) : TEAVM_FIELD(TEAVM_FIELD($2, TeaVM_Class, itemType), TeaVM_Class, size)");
        Files.writeString(object, source);
    }

    private static void guardArraySize(Path generated) throws IOException {
        Path allocator = generated.resolve("classes/org/teavm/runtime/Allocator.c");
        String source = Files.readString(allocator);
        Matcher allocation = ARRAY_ALLOCATION.matcher(source);
        if (!allocation.find()) throw new IOException("Unsupported TeaVM array allocation layout");

        source = "#include <stdio.h>\n" + allocation.replaceFirst("$0\n    if ($1 < 0) {\n        fputs(\"Pumpkin Java: array size cannot be negative\\\\n\", stderr);\n        abort();\n    }\n    if ($1 > INT32_C(0x0FFFFFF0)) {\n        teavm_outOfMemory();\n    }");
        Files.writeString(allocator, source);
    }

    private static String rename(String source, String expected, String replacement) throws IOException {
        if (!source.contains(expected)) throw new IOException("Unsupported TeaVM runtime layout: " + expected);
        return source.replace(expected, replacement);
    }
}
