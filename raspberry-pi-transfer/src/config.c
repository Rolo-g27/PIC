/*
 * config.c - Configuration File Parsing
 * Simple INI-style configuration parser
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>

#include "config.h"
#include "utils.h"

#define MAX_LINE 256

/**
 * Trim whitespace from string
 */
static char* trim(char *str) {
    if (str == NULL) return str;
    
    /* Trim leading */
    while (isspace(*str)) str++;
    
    /* Trim trailing */
    char *end = str + strlen(str) - 1;
    while (end > str && isspace(*end)) end--;
    *(end + 1) = '\0';
    
    return str;
}

/**
 * Parse hex string to bytes
 */
static int parse_hex_string(const char *hex_str, uint8_t *buffer, size_t buf_size) {
    if (hex_str == NULL || buffer == NULL) {
        return -1;
    }
    
    size_t hex_len = strlen(hex_str);
    if (hex_len % 2 != 0) {
        log_error("Hex string has odd length: %s", hex_str);
        return -1;
    }
    
    size_t byte_count = hex_len / 2;
    if (byte_count > buf_size) {
        log_error("Hex buffer too small");
        return -1;
    }
    
    for (size_t i = 0; i < byte_count; i++) {
        unsigned int byte = 0;
        if (sscanf(hex_str + i * 2, "%02x", &byte) != 1) {
            log_error("Invalid hex digit at position %zu", i * 2);
            return -1;
        }
        buffer[i] = (uint8_t)byte;
    }
    
    return (int)byte_count;
}

/**
 * Load configuration from file
 */
int config_load(const char *config_file, Config *config) {
    FILE *fp = NULL;
    char line[MAX_LINE];
    char section[64] = "";
    int ret = -1;
    
    if (config_file == NULL || config == NULL) {
        log_error("Invalid parameters");
        return -1;
    }
    
    /* Initialize defaults */
    memset(config, 0, sizeof(Config));
    strncpy(config->output_dir, "./downloads", sizeof(config->output_dir) - 1);
    config->crypto_mode = 0;  /* CBC by default */
    
    /* Open config file */
    fp = fopen(config_file, "r");
    if (fp == NULL) {
        log_error("Failed to open config file: %s", config_file);
        return -1;
    }
    
    /* Parse file */
    while (fgets(line, sizeof(line), fp) != NULL) {
        /* Trim whitespace */
        char *trimmed = trim(line);
        
        /* Skip empty lines and comments */
        if (trimmed[0] == '\0' || trimmed[0] == '#' || trimmed[0] == ';') {
            continue;
        }
        
        /* Section header [section] */
        if (trimmed[0] == '[') {
            char *end = strchr(trimmed, ']');
            if (end != NULL) {
                *end = '\0';
                strncpy(section, trimmed + 1, sizeof(section) - 1);
            }
            continue;
        }
        
        /* Key=Value pair */
        char *equals = strchr(trimmed, '=');
        if (equals == NULL) {
            continue;
        }
        
        *equals = '\0';
        char *key = trim(trimmed);
        char *value = trim(equals + 1);
        
        /* Parse based on section */
        if (strcmp(section, "smartcard") == 0) {
            if (strcmp(key, "pin") == 0) {
                int bytes = parse_hex_string(value, config->pin, sizeof(config->pin));
                if (bytes != 4) {
                    log_error("PIN must be 4 bytes (8 hex chars)");
                    goto cleanup;
                }
            }
        } else if (strcmp(section, "crypto") == 0) {
            if (strcmp(key, "symmetric_key") == 0) {
                int bytes = parse_hex_string(value, config->symmetric_key, sizeof(config->symmetric_key));
                if (bytes != 16) {
                    log_error("Symmetric key must be 16 bytes (32 hex chars)");
                    goto cleanup;
                }
            } else if (strcmp(key, "iv") == 0) {
                int bytes = parse_hex_string(value, config->iv, sizeof(config->iv));
                if (bytes != 16) {
                    log_error("IV must be 16 bytes (32 hex chars)");
                    goto cleanup;
                }
            } else if (strcmp(key, "mode") == 0) {
                if (strcmp(value, "CBC") == 0 || strcmp(value, "cbc") == 0) {
                    config->crypto_mode = 0;
                } else if (strcmp(value, "CTR") == 0 || strcmp(value, "ctr") == 0) {
                    config->crypto_mode = 1;
                } else {
                    log_error("Unknown crypto mode: %s", value);
                    goto cleanup;
                }
            }
        } else if (strcmp(section, "local") == 0) {
            if (strcmp(key, "output_dir") == 0) {
                strncpy(config->output_dir, value, sizeof(config->output_dir) - 1);
            }
        }
    }
    
    /* Verify required fields */
    if (config->pin[0] == 0 && config->pin[1] == 0 && config->pin[2] == 0 && config->pin[3] == 0) {
        /* All zeros could be valid, but log warning */
        log_warn("PIN not configured or all zeros");
    }
    
    if (config->symmetric_key[0] == 0 && config->symmetric_key[15] == 0) {
        log_warn("Symmetric key not configured or all zeros");
    }
    
    log_info("Configuration loaded successfully");
    ret = 0;
    
cleanup:
    if (fp != NULL) {
        fclose(fp);
    }
    
    return ret;
}

/**
 * Print configuration (for debugging)
 */
void config_print(const Config *config) {
    if (config == NULL) return;
    
    log_info("=== Configuration ===");
    log_info("PIN: ");
    hex_print("  ", config->pin, 4);
    log_info("Symmetric Key: ");
    hex_print("  ", config->symmetric_key, 16);
    log_info("IV: ");
    hex_print("  ", config->iv, 16);
    log_info("Crypto Mode: %s", config->crypto_mode == 0 ? "CBC" : "CTR");
    log_info("Output Directory: %s", config->output_dir);
}
