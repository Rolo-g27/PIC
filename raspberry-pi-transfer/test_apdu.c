/*
 * test_apdu.c - Simple APDU test without encryption
 * 
 * This program tests:
 * 1. Connect to smartcard
 * 2. Select applet
 * 3. Verify PIN
 * 4. List files
 * 5. Download files (raw encrypted - no decryption)
 * 
 * Compile:
 *   gcc -Wall -Wextra test_apdu.c src/apdu.c src/utils.c \
 *       $(pkg-config --cflags --libs libpcsclite) -o test_apdu
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "include/apdu.h"
#include "include/utils.h"

int main(int argc, char *argv[]) {
    APDUClient *client = NULL;
    CardStatus status;
    FileInfo info;
    uint8_t pin[4] = {0x01, 0x02, 0x03, 0x04};
    int exit_code = EXIT_FAILURE;
    
    log_info("========================================");
    log_info("PIC Smartcard APDU Test (No Decryption)");
    log_info("========================================");
    
    /* 1. Initialize APDU client */
    log_info("\n[1] Initializing APDU client...");
    client = apdu_init();
    if (client == NULL) {
        log_error("Failed to initialize APDU client");
        return EXIT_FAILURE;
    }
    log_info("✓ APDU client initialized");
    
    /* 2. Select applet */
    log_info("\n[2] Selecting SecureFileTransferApplet...");
    if (apdu_select_app(client) != 0) {
        log_error("Failed to select applet");
        goto cleanup;
    }
    log_info("✓ Applet selected");
    
    /* 3. Verify PIN */
    log_info("\n[3] Verifying PIN (01 02 03 04)...");
    if (apdu_verify_pin(client, pin) != 0) {
        log_error("PIN verification failed");
        log_info("Try entering the correct PIN");
        goto cleanup;
    }
    log_info("✓ PIN verified successfully");
    
    /* 4. Get card status */
    log_info("\n[4] Getting card status...");
    if (apdu_get_status(client, &status) != 0) {
        log_error("Failed to get status");
        goto cleanup;
    }
    log_info("✓ Card status retrieved:");
    log_info("  - PIN validated: %s", status.pin_validated ? "YES" : "NO");
    log_info("  - State: %u (0=EMPTY, 1=LOADING, 2=READY)", status.state);
    log_info("  - Files on card: %u/%u", status.file_count, status.max_files);
    
    /* 5. List and download files */
    if (status.file_count == 0) {
        log_info("\n✓ Card is empty (no files to download)");
        exit_code = EXIT_SUCCESS;
        goto cleanup;
    }
    
    log_info("\n[5] Downloading files from card...");
    
    for (uint8_t i = 0; i < status.file_count; i++) {
        log_info("\n  File %u/%u:", i + 1, status.file_count);
        
        /* Get file info */
        if (apdu_get_file_info(client, i, &info) != 0) {
            log_error("    Failed to get file info");
            continue;
        }
        
        log_info("    Name: %.*s", info.name_len, info.name);
        log_info("    Size: %u bytes", info.file_size);
        
        /* Download file */
        uint8_t *encrypted_data = (uint8_t *)malloc(info.file_size);
        if (encrypted_data == NULL) {
            log_error("    Memory allocation failed");
            continue;
        }
        
        uint16_t bytes_read = 0;
        log_info("    Downloading... (in 200-byte chunks)");
        
        if (apdu_read_file(client, i, encrypted_data, info.file_size, &bytes_read) != 0) {
            log_error("    Failed to download file");
            free(encrypted_data);
            continue;
        }
        
        log_info("    ✓ Downloaded %u bytes", bytes_read);
        
        /* Print first 32 bytes as hex (to verify data) */
        log_info("    First 32 bytes (hex):");
        size_t show_len = bytes_read > 32 ? 32 : bytes_read;
        for (size_t j = 0; j < show_len; j++) {
            if (j % 16 == 0) {
                fprintf(stderr, "      ");
            }
            fprintf(stderr, "%02X ", encrypted_data[j]);
            if (j % 16 == 15) {
                fprintf(stderr, "\n");
            }
        }
        if (show_len % 16 != 0) {
            fprintf(stderr, "\n");
        }
        
        /* Save encrypted file to disk (for later decryption analysis) */
        char filename[256];
        snprintf(filename, sizeof(filename), "test_file_%u_encrypted.bin", i);
        
        FILE *fp = fopen(filename, "wb");
        if (fp != NULL) {
            size_t written = fwrite(encrypted_data, 1, bytes_read, fp);
            fclose(fp);
            if (written == bytes_read) {
                log_info("    ✓ Saved to: %s (%zu bytes)", filename, written);
            } else {
                log_error("    Failed to save file");
            }
        } else {
            log_error("    Failed to open file for writing");
        }
        
        free(encrypted_data);
    }
    
    /* 6. Confirm download */
    log_info("\n[6] Confirming download (card will auto-wipe)...");
    if (apdu_confirm_download(client) != 0) {
        log_error("Failed to confirm download");
        goto cleanup;
    }
    log_info("✓ Download confirmed - card auto-wiped");
    
    log_info("\n========================================");
    log_info("✓ Test completed successfully!");
    log_info("========================================");
    exit_code = EXIT_SUCCESS;
    
cleanup:
    if (client != NULL) {
        apdu_cleanup(client);
    }
    
    if (exit_code != EXIT_SUCCESS) {
        log_error("\n✗ Test failed");
    }
    
    return exit_code;
}
