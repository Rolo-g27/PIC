/*
 * utils.c - Utilities: Logging, hex operations
 */

#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <string.h>
#include <time.h>
#include <ctype.h>

#include "utils.h"

static LogLevel global_log_level = LOG_INFO;

/**
 * Set global log level
 */
void log_set_level(LogLevel level) {
    global_log_level = level;
}

/**
 * Internal logging function
 */
static void log_write(LogLevel level, const char *fmt, va_list args) {
    if (level < global_log_level) {
        return;
    }
    
    const char *level_str[] = {"[DEBUG]", "[INFO] ", "[WARN] ", "[ERROR]"};
    
    time_t now = time(NULL);
    struct tm *tm_info = localtime(&now);
    
    char time_str[20];
    strftime(time_str, sizeof(time_str), "%H:%M:%S", tm_info);
    
    fprintf(stderr, "%s %s ", time_str, level_str[level]);
    vfprintf(stderr, fmt, args);
    fprintf(stderr, "\n");
    fflush(stderr);
}

/**
 * Log functions
 */
void log_debug(const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    log_write(LOG_DEBUG, fmt, args);
    va_end(args);
}

void log_info(const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    log_write(LOG_INFO, fmt, args);
    va_end(args);
}

void log_warn(const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    log_write(LOG_WARN, fmt, args);
    va_end(args);
}

void log_error(const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    log_write(LOG_ERROR, fmt, args);
    va_end(args);
}

/**
 * Print hex data
 */
void hex_print(const char *label, const uint8_t *data, size_t len) {
    if (label != NULL) {
        fprintf(stderr, "%s", label);
    }
    
    for (size_t i = 0; i < len; i++) {
        fprintf(stderr, "%02X", data[i]);
        if (i % 16 == 15 && i < len - 1) {
            fprintf(stderr, "\n%*s", label ? (int)strlen(label) : 0, "");
        }
    }
    fprintf(stderr, "\n");
    fflush(stderr);
}

/**
 * Parse hex string to bytes
 */
int hex_parse(const char *hex_str, uint8_t *buffer, size_t buffer_size) {
    if (hex_str == NULL || buffer == NULL) {
        return -1;
    }
    
    size_t hex_len = strlen(hex_str);
    if (hex_len % 2 != 0) {
        return -1;
    }
    
    size_t byte_count = hex_len / 2;
    if (byte_count > buffer_size) {
        return -1;
    }
    
    for (size_t i = 0; i < byte_count; i++) {
        unsigned int byte = 0;
        if (sscanf(hex_str + i * 2, "%02x", &byte) != 1) {
            return -1;
        }
        buffer[i] = (uint8_t)byte;
    }
    
    return (int)byte_count;
}

/**
 * XOR two byte arrays
 */
void bytes_xor(const uint8_t *a, const uint8_t *b, uint8_t *result, size_t len) {
    for (size_t i = 0; i < len; i++) {
        result[i] = a[i] ^ b[i];
    }
}
