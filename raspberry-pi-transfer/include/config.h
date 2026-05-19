#ifndef CONFIG_H
#define CONFIG_H

#include <stdint.h>

/* Configuration - Load PIN, key, paths */

typedef struct {
    uint8_t pin[4];
    uint8_t symmetric_key[16];
    uint8_t iv[16];         /* Initialization vector if needed */
    char output_dir[256];
    int crypto_mode;        /* 0=CBC, 1=CTR */
} Config;

/* Load configuration from INI file */
int config_load(const char *config_file, Config *config);

/* Print configuration (for debugging) */
void config_print(const Config *config);

#endif
