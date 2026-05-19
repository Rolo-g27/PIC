/*
 * main.c - PIC Raspberry Pi: Download and decrypt files from smartcard
 * 
 * Flow:
 * 1. Load configuration (PIN, encryption key, output directory)
 * 2. Connect to smartcard reader
 * 3. Select applet and authenticate with PIN
 * 4. Get list of files on card
 * 5. For each file:
 *    - Download encrypted data from card (in 200-byte chunks)
 *    - Decrypt using AES-128
 *    - Save to disk
 * 6. Confirm download (card auto-wipes)
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "config.h"
#include "apdu.h"
#include "crypto.h"
#include "files.h"
#include "utils.h"

/* Forward declarations */
static int download_and_decrypt_file(APDUClient *client, const Config *config,
                                      uint8_t file_index);

int main(int argc, char *argv[]) {
    Config config;
    APDUClient *client = NULL;
    CardStatus status;
    int exit_code = EXIT_FAILURE;
    
    log_info("========================================");
    log_info("PIC Raspberry Pi - File Transfer");
    log_info("========================================");
    
    /* 1. Load configuration */
    if (argc < 2) {
        log_error("Usage: %s <config_file>", argv[0]);
        return EXIT_FAILURE;
    }
    
    log_info("Loading configuration from: %s", argv[1]);
    if (config_load(argv[1], &config) != 0) {
        log_error("Failed to load configuration");
        return EXIT_FAILURE;
    }
    
    /* 2. Initialize APDU client */
    log_info("Connecting to smartcard reader...");
    client = apdu_init();
    if (client == NULL) {
        log_error("Failed to initialize APDU client");
        goto cleanup;
    }
    
    /* 3. Select applet */
    log_info("Selecting SecureFileTransferApplet...");
    if (apdu_select_app(client) != 0) {
        log_error("Failed to select applet");
        goto cleanup;
    }
    
    /* 4. Authenticate with PIN */
    log_info("Authenticating with PIN...");
    if (apdu_verify_pin(client, config.pin) != 0) {
        log_error("PIN verification failed");
        goto cleanup;
    }
    log_info("Authentication successful");
    
    /* 5. Get card status */
    if (apdu_get_status(client, &status) != 0) {
        log_error("Failed to get card status");
        goto cleanup;
    }
    log_info("Card status: %u files, state=%u", status.file_count, status.state);
    
    /* 6. Create output directory */
    if (files_create_output_dir(config.output_dir) != 0) {
        log_error("Failed to create output directory: %s", config.output_dir);
        goto cleanup;
    }
    
    /* 7. Download and decrypt each file */
    for (uint8_t i = 0; i < status.file_count; i++) {
        log_info("Processing file %u/%u...", i + 1, status.file_count);
        if (download_and_decrypt_file(client, &config, i) != 0) {
            log_error("Failed to download/decrypt file %u", i);
            goto cleanup;
        }
    }
    
    /* 8. Confirm download (card auto-wipes) */
    log_info("Confirming download (card will auto-wipe)...");
    if (apdu_confirm_download(client) != 0) {
        log_error("Failed to confirm download");
        goto cleanup;
    }
    
    log_info("========================================");
    log_info("All files downloaded and decrypted successfully!");
    log_info("========================================");
    exit_code = EXIT_SUCCESS;
    
cleanup:
    if (client != NULL) {
        apdu_cleanup(client);
    }
    
    if (exit_code != EXIT_SUCCESS) {
        log_error("Program failed");
    }
    
    return exit_code;
}

/**
 * Download entire file from card and decrypt it
 */
static int download_and_decrypt_file(APDUClient *client, const Config *config,
                                      uint8_t file_index) {
    FileInfo info;
    uint8_t *encrypted_data = NULL;
    uint8_t *decrypted_data = NULL;
    uint16_t bytes_read = 0;
    int ret = -1;
    
    /* Get file info */
    if (apdu_get_file_info(client, file_index, &info) != 0) {
        log_error("Failed to get file info for file %u", file_index);
        goto cleanup;
    }
    
    log_info("  File: %.*s (size: %u bytes)", info.name_len, info.name, info.file_size);
    
    /* Allocate buffers */
    encrypted_data = (uint8_t *)malloc(info.file_size);
    decrypted_data = (uint8_t *)malloc(info.file_size);
    if (encrypted_data == NULL || decrypted_data == NULL) {
        log_error("Memory allocation failed");
        goto cleanup;
    }
    
    /* Download encrypted file */
    log_info("  Downloading encrypted data...");
    if (apdu_read_file(client, file_index, encrypted_data, info.file_size, &bytes_read) != 0) {
        log_error("Failed to download file");
        goto cleanup;
    }
    if (bytes_read != info.file_size) {
        log_error("Incomplete download: got %u/%u bytes", bytes_read, info.file_size);
        goto cleanup;
    }
    log_info("  Downloaded %u bytes", bytes_read);
    
    /* Decrypt file */
    log_info("  Decrypting file...");
    size_t decrypted_size = 0;
    if (crypto_decrypt_buffer(config->symmetric_key, config->iv, config->crypto_mode,
                              encrypted_data, bytes_read,
                              decrypted_data, info.file_size) != 0) {
        log_error("Decryption failed");
        goto cleanup;
    }
    decrypted_size = bytes_read;  /* May differ if padding removed */
    
    /* Save to disk */
    log_info("  Saving to disk...");
    if (files_write(config->output_dir, (const char *)info.name, decrypted_data, decrypted_size) != 0) {
        log_error("Failed to save file to disk");
        goto cleanup;
    }
    
    log_info("  File saved to: %s/%.*s", config->output_dir, info.name_len, info.name);
    ret = 0;
    
cleanup:
    if (encrypted_data != NULL) free(encrypted_data);
    if (decrypted_data != NULL) free(decrypted_data);
    
    return ret;
}
