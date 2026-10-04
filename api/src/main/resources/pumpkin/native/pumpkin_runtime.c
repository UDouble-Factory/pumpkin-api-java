#include "pumpkin_runtime.h"
#include <stdlib.h>
#include <stdio.h>

extern int pumpkin_java_main(int argc, char **argv);
static pumpkin_callback callback;
static int initializing;

void pumpkin_java_register(pumpkin_callback value) {
    if (callback != NULL || value == NULL) abort();
    callback = value;
}

void pumpkin_java_initialize(void) {
    if (callback != NULL) return;
    if (initializing) abort();
    initializing = 1;
    pumpkin_java_main(0, NULL);
    initializing = 0;
    if (callback == NULL) abort();
}

void *pumpkin_java_dispatch(int32_t opcode, void *data, int32_t length) {
    pumpkin_java_initialize();
    return callback(opcode, data, length);
}

void *pumpkin_java_allocate(int32_t length) {
    if (length < 0) abort();
    void *result = malloc(length == 0 ? 1 : (size_t) length);
    if (result == NULL) abort();
    return result;
}

void pumpkin_java_free(void *pointer) {
    free(pointer);
}

void pumpkin_java_report_failure(const uint8_t *data, int32_t length) {
    fputs("Pumpkin Java: ", stderr);
    fwrite(data, 1, (size_t) length, stderr);
    fputc('\n', stderr);
    fflush(stderr);
}
