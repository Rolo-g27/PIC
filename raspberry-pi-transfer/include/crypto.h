#ifndef CRYPTO_H
#define CRYPTO_H

#include <stdint.h>
#include <stddef.h>

/* Cryptography - AES-128 Decryption */

typedef struct {
    uint8_t key[16];        /* AES-128 key (16 bytes) */
    uint8_t iv[16];         /* Initialization vector (for CBC/CTR) */
    int mode;               /* 0=CBC, 1=CTR */
} CryptoContext;

/* Initialize crypto with key and IV */
int crypto_init(CryptoContext *ctx, const uint8_t *key, const uint8_t *iv, int mode);

/* Decrypt data using AES-128 */
int crypto_decrypt(CryptoContext *ctx, const uint8_t *ciphertext, size_t ciphertext_len,
                   uint8_t *plaintext, size_t plaintext_size, size_t *plaintext_len);

/* Helper: decrypt entire buffer */
int crypto_decrypt_buffer(const uint8_t *key, const uint8_t *iv, int mode,
                          const uint8_t *ciphertext, size_t ciphertext_len,
                          uint8_t *plaintext, size_t plaintext_size);

#endif
