/*
 * apdu.c - APDU Communication with Smartcard
 * Uses libpcsclite for PC/SC reader access
 * 
 * AID: A00000006203010D0301 (SecureFileTransferApplet)
 * CLA: 0x80
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
    #include <winscard.h>
#else
    #include <PCSC/winscard.h>
#endif

#include "apdu.h"
#include "utils.h"

#define APPLET_AID      "\xA0\x00\x00\x00\x62\x03\x01\x0D\x03\x01"
#define APPLET_AID_LEN  10
#define CLA             0x80

/* Command INS codes */
#define INS_GET_STATUS      0x10
#define INS_GET_VERSION     0x11
#define INS_VERIFY_PIN      0x20
#define INS_GET_FILE_INFO   0x40
#define INS_READ_CHUNK      0x50
#define INS_CONFIRM_DOWNLOAD 0x60

#define MAX_APDU_LEN    260

struct APDUClient {
    SCARDCONTEXT context;
    SCARDHANDLE card;
    DWORD protocol;
    int connected;
};

/**
 * Initialize APDU client - connect to reader and card
 */
APDUClient* apdu_init(void) {
    APDUClient *client = (APDUClient *)malloc(sizeof(APDUClient));
    if (client == NULL) {
        log_error("Memory allocation failed");
        return NULL;
    }
    
    memset(client, 0, sizeof(APDUClient));
    
    /* Establish PC/SC context */
    LONG rv = SCardEstablishContext(SCARD_SCOPE_SYSTEM, NULL, NULL, &client->context);
    if (rv != SCARD_S_SUCCESS) {
        log_error("Failed to establish PC/SC context: %ld", rv);
        free(client);
        return NULL;
    }
    
    /* TODO: List readers and connect to first available */
    /* CHAR *readers = NULL;
     * DWORD readers_len = 0;
     * SCardListReaders(context, NULL, readers, &readers_len);
     * ... parse readers and connect ...
     */
    
    log_warn("APDU: SCardConnect not yet fully implemented");
    
    return client;
}

/**
 * Cleanup APDU client
 */
void apdu_cleanup(APDUClient *client) {
    if (client == NULL) return;
    
    if (client->connected) {
        SCardDisconnect(client->card, SCARD_LEAVE_CARD);
    }
    
    SCardReleaseContext(client->context);
    free(client);
}

/**
 * Send APDU command and receive response
 */
static int apdu_transmit(APDUClient *client, const uint8_t *cmd, size_t cmd_len,
                         uint8_t *response, size_t response_size, size_t *response_len) {
    if (!client->connected) {
        log_error("Card not connected");
        return -1;
    }
    
    DWORD response_dword_len = (DWORD)response_size;
    LONG rv = SCardTransmit(client->card, SCARD_PCI_T0, cmd, (DWORD)cmd_len,
                            NULL, response, &response_dword_len);
    
    if (rv != SCARD_S_SUCCESS) {
        log_error("SCardTransmit failed: %ld", rv);
        return -1;
    }
    
    *response_len = (size_t)response_dword_len;
    
    /* Check SW (Status Word) - last 2 bytes */
    if (*response_len >= 2) {
        uint16_t sw = (response[*response_len - 2] << 8) | response[*response_len - 1];
        if (sw != 0x9000) {
            log_warn("APDU Response: SW=%04X", sw);
        }
    }
    
    return 0;
}

/**
 * Select applet
 */
int apdu_select_app(APDUClient *client) {
    uint8_t cmd[] = {0x00, 0xA4, 0x04, 0x00, APPLET_AID_LEN};
    uint8_t response[256];
    size_t response_len;
    
    log_info("APDU: SELECT APPLET");
    
    /* Build command */
    uint8_t full_cmd[sizeof(cmd) + APPLET_AID_LEN];
    memcpy(full_cmd, cmd, sizeof(cmd));
    memcpy(full_cmd + sizeof(cmd), APPLET_AID, APPLET_AID_LEN);
    
    if (apdu_transmit(client, full_cmd, sizeof(full_cmd), response, sizeof(response), &response_len) != 0) {
        return -1;
    }
    
    /* Check response (should be 9000) */
    if (response_len < 2 || response[response_len - 2] != 0x90 || response[response_len - 1] != 0x00) {
        log_error("SELECT APPLET failed");
        return -1;
    }
    
    log_info("APDU: SELECT APPLET success");
    return 0;
}

/**
 * Verify PIN
 */
int apdu_verify_pin(APDUClient *client, const uint8_t *pin) {
    uint8_t cmd[] = {CLA, INS_VERIFY_PIN, 0x00, 0x00, 0x04};
    uint8_t full_cmd[sizeof(cmd) + 4];
    uint8_t response[256];
    size_t response_len;
    
    log_info("APDU: VERIFY PIN");
    
    memcpy(full_cmd, cmd, sizeof(cmd));
    memcpy(full_cmd + sizeof(cmd), pin, 4);
    
    if (apdu_transmit(client, full_cmd, sizeof(full_cmd), response, sizeof(response), &response_len) != 0) {
        return -1;
    }
    
    if (response_len < 2) {
        log_error("Invalid response length");
        return -1;
    }
    
    uint16_t sw = (response[response_len - 2] << 8) | response[response_len - 1];
    
    if (sw == 0x9000) {
        log_info("APDU: VERIFY PIN success");
        return 0;
    } else if ((sw & 0xFFF0) == 0x63C0) {
        log_error("PIN wrong, %d attempts remaining", sw & 0x0F);
        return -1;
    } else if (sw == 0x6983) {
        log_error("PIN blocked");
        return -1;
    }
    
    log_error("VERIFY PIN failed: SW=%04X", sw);
    return -1;
}

/**
 * Get card status
 */
int apdu_get_status(APDUClient *client, CardStatus *status) {
    uint8_t cmd[] = {CLA, INS_GET_STATUS, 0x00, 0x00, 0x04};
    uint8_t response[256];
    size_t response_len;
    
    log_info("APDU: GET_STATUS");
    
    if (apdu_transmit(client, cmd, sizeof(cmd), response, sizeof(response), &response_len) != 0) {
        return -1;
    }
    
    if (response_len < 6) {  /* 4 bytes data + 2 bytes SW */
        log_error("Invalid response length");
        return -1;
    }
    
    status->pin_validated = response[0];
    status->state = response[1];
    status->file_count = response[2];
    status->max_files = response[3];
    
    log_info("APDU: GET_STATUS success");
    return 0;
}

/**
 * Get file info
 */
int apdu_get_file_info(APDUClient *client, uint8_t file_index, FileInfo *info) {
    uint8_t cmd[] = {CLA, INS_GET_FILE_INFO, file_index, 0x00};
    uint8_t response[256];
    size_t response_len;
    
    log_info("APDU: GET_FILE_INFO (file %u)", file_index);
    
    if (apdu_transmit(client, cmd, sizeof(cmd), response, sizeof(response), &response_len) != 0) {
        return -1;
    }
    
    if (response_len < 4) {  /* nameLen + name + size + SW */
        log_error("Invalid response length");
        return -1;
    }
    
    uint8_t name_len = response[0];
    if (name_len > 16 || response_len < name_len + 4) {
        log_error("Invalid name length");
        return -1;
    }
    
    info->name_len = name_len;
    memcpy(info->name, response + 1, name_len);
    info->file_size = (response[1 + name_len] << 8) | response[2 + name_len];
    
    log_info("APDU: GET_FILE_INFO success");
    return 0;
}

/**
 * Read chunk from file
 */
int apdu_read_chunk(APDUClient *client, uint8_t file_index, uint8_t chunk_index,
                    uint8_t *buffer, uint16_t *bytes_read) {
    uint8_t cmd[] = {CLA, INS_READ_CHUNK, file_index, chunk_index};
    uint8_t response[256];
    size_t response_len;
    
    if (apdu_transmit(client, cmd, sizeof(cmd), response, sizeof(response), &response_len) != 0) {
        return -1;
    }
    
    if (response_len < 2) {
        log_error("Invalid response length");
        return -1;
    }
    
    /* Response: [data...][SW1][SW2] */
    size_t data_len = response_len - 2;
    memcpy(buffer, response, data_len);
    *bytes_read = (uint16_t)data_len;
    
    return 0;
}

/**
 * Read entire file from card
 */
int apdu_read_file(APDUClient *client, uint8_t file_index, 
                   uint8_t *buffer, uint16_t buffer_size, uint16_t *bytes_read) {
    uint16_t total_read = 0;
    uint8_t chunk_index = 0;
    
    log_info("APDU: Reading file %u in chunks...", file_index);
    
    while (total_read < buffer_size) {
        uint16_t chunk_len;
        
        if (apdu_read_chunk(client, file_index, chunk_index, 
                           buffer + total_read, &chunk_len) != 0) {
            log_error("Chunk read failed");
            return -1;
        }
        
        if (chunk_len == 0) {
            /* End of file */
            break;
        }
        
        total_read += chunk_len;
        chunk_index++;
        
        if (total_read > buffer_size) {
            log_error("Buffer overflow");
            return -1;
        }
    }
    
    *bytes_read = total_read;
    log_info("APDU: File read complete (%u bytes)", total_read);
    return 0;
}

/**
 * Confirm download (card auto-wipes)
 */
int apdu_confirm_download(APDUClient *client) {
    uint8_t cmd[] = {CLA, INS_CONFIRM_DOWNLOAD, 0x00, 0x00};
    uint8_t response[256];
    size_t response_len;
    
    log_info("APDU: CONFIRM_DOWNLOAD");
    
    if (apdu_transmit(client, cmd, sizeof(cmd), response, sizeof(response), &response_len) != 0) {
        return -1;
    }
    
    if (response_len < 2 || response[response_len - 2] != 0x90 || response[response_len - 1] != 0x00) {
        log_error("CONFIRM_DOWNLOAD failed");
        return -1;
    }
    
    log_info("APDU: CONFIRM_DOWNLOAD success");
    return 0;
}
