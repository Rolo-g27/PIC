#ifndef APDU_H
#define APDU_H

#include <stdint.h>
#include <stddef.h>

/* APDU Client - Communicates with smartcard */

typedef struct APDUClient APDUClient;

/* Initialize/cleanup APDU communication */
APDUClient* apdu_init(void);
void apdu_cleanup(APDUClient *client);

/* Select applet and authenticate */
int apdu_select_app(APDUClient *client);
int apdu_verify_pin(APDUClient *client, const uint8_t *pin);

/* Get card status */
typedef struct {
    uint8_t pin_validated;
    uint8_t state;              /* 0=EMPTY, 1=LOADING, 2=READY */
    uint8_t file_count;
    uint8_t max_files;
} CardStatus;

int apdu_get_status(APDUClient *client, CardStatus *status);

/* File operations */
typedef struct {
    uint8_t name[16];
    uint8_t name_len;
    uint16_t file_size;
} FileInfo;

int apdu_get_file_info(APDUClient *client, uint8_t file_index, FileInfo *info);
int apdu_read_chunk(APDUClient *client, uint8_t file_index, uint8_t chunk_index,
                    uint8_t *buffer, uint16_t *bytes_read);
int apdu_confirm_download(APDUClient *client);

/* Helper function to read entire file from card */
int apdu_read_file(APDUClient *client, uint8_t file_index, 
                   uint8_t *buffer, uint16_t buffer_size, uint16_t *bytes_read);

#endif
