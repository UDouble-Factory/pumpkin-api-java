#pragma once
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

typedef struct {
    uint8_t *data;
    size_t length;
    size_t capacity;
    size_t position;
} pj_buffer;

typedef struct pj_allocation {
    struct pj_allocation *next;
    void *pointer;
} pj_allocation;

typedef struct {
    pj_allocation *first;
} pj_arena;

static void pj_require(bool condition) {
    if (!condition) {
        fputs("Invalid Pumpkin Java bridge data\n", stderr);
        abort();
    }
}

static pj_buffer pj_input(const uint8_t *data, int32_t length) {
    pj_require(length >= 0);
    return (pj_buffer) {(uint8_t *) data, (size_t) length, (size_t) length, 0};
}

static pj_buffer pj_output(void) {
    return (pj_buffer) {0};
}

static void pj_append(pj_buffer *buffer, const void *source, size_t length) {
    pj_require(length <= INT32_MAX && buffer->length <= INT32_MAX - length);
    size_t needed = buffer->length + length;
    if (needed > buffer->capacity) {
        size_t capacity = needed > buffer->capacity * 2 ? needed : buffer->capacity * 2;
        uint8_t *data = realloc(buffer->data, capacity);
        pj_require(data != NULL);
        buffer->data = data;
        buffer->capacity = capacity;
    }
    if (length != 0) memcpy(buffer->data + buffer->length, source, length);
    buffer->length += length;
}

static void pj_take(pj_buffer *buffer, void *destination, size_t length) {
    pj_require(length <= buffer->length - buffer->position);
    if (length != 0) memcpy(destination, buffer->data + buffer->position, length);
    buffer->position += length;
}

static uint8_t pj_get_u8(pj_buffer *buffer) {
    uint8_t result;
    pj_take(buffer, &result, sizeof(result));
    return result;
}

static uint32_t pj_get_u32(pj_buffer *buffer) {
    uint32_t result;
    pj_take(buffer, &result, sizeof(result));
    return result;
}

static uint64_t pj_get_u64(pj_buffer *buffer) {
    uint64_t result;
    pj_take(buffer, &result, sizeof(result));
    return result;
}

static void pj_put_u8(pj_buffer *buffer, uint8_t value) {
    pj_append(buffer, &value, sizeof(value));
}

static void pj_put_u32(pj_buffer *buffer, uint32_t value) {
    pj_append(buffer, &value, sizeof(value));
}

static void pj_put_u64(pj_buffer *buffer, uint64_t value) {
    pj_append(buffer, &value, sizeof(value));
}

static void *pj_allocate(pj_arena *arena, size_t count, size_t size) {
    pj_require(size == 0 || count <= SIZE_MAX / size);
    if (count == 0) return NULL;
    void *result = calloc(count, size);
    pj_require(result != NULL);
    if (arena != NULL) {
        pj_allocation *entry = malloc(sizeof(*entry));
        pj_require(entry != NULL);
        *entry = (pj_allocation) {arena->first, result};
        arena->first = entry;
    }
    return result;
}

static void pj_arena_release(pj_arena *arena) {
    while (arena->first != NULL) {
        pj_allocation *entry = arena->first;
        arena->first = entry->next;
        free(entry->pointer);
        free(entry);
    }
}

static uint8_t *pj_finish(pj_buffer *buffer) {
    pj_require(buffer->length <= INT32_MAX - 4);
    uint8_t *result = malloc(buffer->length + 4);
    pj_require(result != NULL);
    uint32_t length = buffer->length;
    memcpy(result, &length, 4);
    if (length != 0) memcpy(result + 4, buffer->data, length);
    free(buffer->data);
    return result;
}

static pj_buffer pj_result(uint8_t *payload) {
    pj_require(payload != NULL);
    uint32_t length;
    memcpy(&length, payload, 4);
    return pj_input(payload + 4, length);
}

#define PJ_SCALAR(name, type) \
static void pj_read_##name(pj_buffer *buffer, type *value, pj_arena *arena) { \
    pj_take(buffer, value, sizeof(*value)); \
} \
static void pj_write_##name(pj_buffer *buffer, const type *value) { \
    pj_append(buffer, value, sizeof(*value)); \
} \
static void pj_free_##name(type *value) { \
}

PJ_SCALAR(bool, bool)
PJ_SCALAR(u8, uint8_t)
PJ_SCALAR(s8, int8_t)
PJ_SCALAR(u16, uint16_t)
PJ_SCALAR(s16, int16_t)
PJ_SCALAR(u32, uint32_t)
PJ_SCALAR(s32, int32_t)
PJ_SCALAR(u64, uint64_t)
PJ_SCALAR(s64, int64_t)
PJ_SCALAR(f32, float)
PJ_SCALAR(f64, double)
PJ_SCALAR(char, uint32_t)

static void pj_read_string(pj_buffer *buffer, plugin_string_t *value, pj_arena *arena) {
    value->len = pj_get_u32(buffer);
    pj_require(value->len <= (buffer->length - buffer->position) / 2);
    value->ptr = pj_allocate(arena, value->len, 2);
    pj_take(buffer, value->ptr, value->len * 2);
}

static void pj_write_string(pj_buffer *buffer, const plugin_string_t *value) {
    pj_put_u32(buffer, value->len);
    pj_append(buffer, value->ptr, value->len * 2);
}

static void pj_free_string(plugin_string_t *value) {
    if (value->len != 0) free(value->ptr);
}
