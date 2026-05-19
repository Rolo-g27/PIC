#ifndef UTILS_H
#define UTILS_H

#include <stdint.h>
#include <stddef.h>

/* Utilities - Logging and helpers */

typedef enum {
    LOG_DEBUG = 0,
    LOG_INFO,
    LOG_WARN,
    LOG_ERROR
} LogLevel;

/* Set global log level */
void log_set_level(LogLevel level);

/* Log messages */
void log_debug(const char *fmt, ...);
void log_info(const char *fmt, ...);
void log_warn(const char *fmt, ...);
void log_error(const char *fmt, ...);

/* Hex utilities */
void hex_print(const char *label, const uint8_t *data, size_t len);
int hex_parse(const char *hex_str, uint8_t *buffer, size_t buffer_size);

/* Byte utilities */
void bytes_xor(const uint8_t *a, const uint8_t *b, uint8_t *result, size_t len);

#endif
