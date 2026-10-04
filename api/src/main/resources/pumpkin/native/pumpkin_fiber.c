#include "fiber.h"
#include <locale.h>
#include <stdint.h>
#include <time.h>
#include <errno.h>

void teavm_initFiber(void) {
    setlocale(LC_ALL, "C.UTF-8");
}

void teavm_waitFor(int64_t timeout) {
    if (timeout <= 0) return;
    struct timespec remaining = {timeout / 1000, (timeout % 1000) * 1000000};
    while (nanosleep(&remaining, &remaining) != 0 && errno == EINTR) {
    }
}

void teavm_interrupt(void) {
}
