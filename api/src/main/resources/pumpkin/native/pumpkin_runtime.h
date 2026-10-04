#pragma once
#include <stdint.h>
#include <stddef.h>

typedef void *(*pumpkin_callback)(int32_t, void *, int32_t);

void pumpkin_java_register(pumpkin_callback callback);
void pumpkin_java_initialize(void);
void *pumpkin_java_dispatch(int32_t opcode, void *data, int32_t length);
void *pumpkin_java_call(int32_t opcode, const uint8_t *data, int32_t length);
void pumpkin_java_drop(int32_t type, int32_t handle);
void *pumpkin_java_allocate(int32_t length);
void pumpkin_java_free(void *pointer);
void pumpkin_java_report_failure(const uint8_t *data, int32_t length);
