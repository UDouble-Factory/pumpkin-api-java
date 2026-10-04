static uint32_t pumpkin_decode_utf8(const uint8_t **cursor, const uint8_t *end) {
    uint32_t first = *(*cursor)++;
    if (first < 0x80) return first;
    int count;
    uint32_t value;
    uint32_t minimum;
    if (first >= 0xC2 && first <= 0xDF) {
        count = 1;
        value = first & 0x1F;
        minimum = 0x80;
    } else if (first >= 0xE0 && first <= 0xEF) {
        count = 2;
        value = first & 0x0F;
        minimum = 0x800;
    } else if (first >= 0xF0 && first <= 0xF4) {
        count = 3;
        value = first & 7;
        minimum = 0x10000;
    } else {
        return 0xFFFD;
    }
    if (end - *cursor < count) return 0xFFFD;
    for (int i = 0; i < count; i++) {
        uint8_t next = (*cursor)[i];
        if ((next & 0xC0) != 0x80) return 0xFFFD;
        value = (value << 6) | (next & 0x3F);
    }
    if (value < minimum || value > 0x10FFFF || (value >= 0xD800 && value <= 0xDFFF)) return 0xFFFD;
    *cursor += count;
    return value;
}

char16_t *teavm_mbToChar16(char *cstring, int32_t *length) {
    size_t bytes = strlen(cstring);
    if (bytes > INT32_MAX || bytes > SIZE_MAX / sizeof(char16_t) - 1) abort();
    char16_t *result = malloc((bytes + 1) * sizeof(char16_t));
    if (result == NULL) abort();
    const uint8_t *cursor = (const uint8_t *) cstring;
    const uint8_t *end = cursor + bytes;
    int32_t count = 0;
    while (cursor < end) {
        uint32_t value = pumpkin_decode_utf8(&cursor, end);
        if (value >= 0x10000) {
            value -= 0x10000;
            result[count++] = 0xD800 | (value >> 10);
            result[count++] = 0xDC00 | (value & 0x3FF);
        } else {
            result[count++] = value;
        }
    }
    result[count] = 0;
    *length = count;
    return result;
}

char *teavm_char16ToMb(char16_t *characters, int32_t length) {
    if (length < 0 || (size_t) length > (SIZE_MAX - 1) / 3) abort();
    uint8_t *result = malloc((size_t) length * 3 + 1);
    if (result == NULL) abort();
    size_t count = 0;
    for (int32_t i = 0; i < length; i++) {
        uint32_t value = characters[i];
        if (value >= 0xD800 && value <= 0xDBFF && i + 1 < length && characters[i + 1] >= 0xDC00 && characters[i + 1] <= 0xDFFF) {
            value = 0x10000 + ((value - 0xD800) << 10) + (characters[++i] - 0xDC00);
        } else if (value >= 0xD800 && value <= 0xDFFF) {
            value = 0xFFFD;
        }
        if (value < 0x80) {
            result[count++] = value;
        } else if (value < 0x800) {
            result[count++] = 0xC0 | (value >> 6);
            result[count++] = 0x80 | (value & 0x3F);
        } else if (value < 0x10000) {
            result[count++] = 0xE0 | (value >> 12);
            result[count++] = 0x80 | ((value >> 6) & 0x3F);
            result[count++] = 0x80 | (value & 0x3F);
        } else {
            result[count++] = 0xF0 | (value >> 18);
            result[count++] = 0x80 | ((value >> 12) & 0x3F);
            result[count++] = 0x80 | ((value >> 6) & 0x3F);
            result[count++] = 0x80 | (value & 0x3F);
        }
    }
    result[count] = 0;
    return (char *) result;
}

TeaVM_String *teavm_cToString(char *cstring) {
    if (cstring == NULL) return NULL;
    int32_t length;
    char16_t *characters = teavm_mbToChar16(cstring, &length);
    TEAVM_ALLOC_STACK(1);
    TEAVM_CALL_SITE(-1);
    TEAVM_GC_ROOT_RELEASE(0);
    TeaVM_Array *array = teavm_allocateCharArray(length);
    TEAVM_GC_ROOT(0, array);
    memcpy(TEAVM_ARRAY_DATA(array, char16_t), characters, (size_t) length * sizeof(char16_t));
    free(characters);
    TeaVM_String *result = teavm_createString(array);
    TEAVM_RELEASE_STACK;
    return result;
}
