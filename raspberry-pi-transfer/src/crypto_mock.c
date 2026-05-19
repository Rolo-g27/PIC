/*
 * crypto_mock.c - Mock AES implementation for testing (Windows)
 * 
 * This is a stub for Windows testing without OpenSSL.
 * On Linux/Raspberry Pi, este ficheiro não é usado.
 * 
 * For real encryption, use crypto.c (which requires libcrypto)
 */

#include <stdio.h>
#include <string.h>
#include "crypto.h"
#include "utils.h"

int crypto_init(CryptoContext *ctx, const uint8_t *key, const uint8_t *iv, int mode) {
    if (ctx == NULL || key == NULL) {
        log_error("crypto_init: Invalid parameters");
        return -1;
    }
    
    memcpy(ctx->key, key, 16);
    
    if (iv != NULL) {
        memcpy(ctx->iv, iv, 16);
    } else {
        memset(ctx->iv, 0, 16);
    }
    
    ctx->mode = mode;
    
    log_info("Crypto context initialized (mode=%s) [MOCK - no decryption]",
             mode == 0 ? "CBC" : "CTR");
    return 0;
}

int crypto_decrypt(CryptoContext *ctx, const uint8_t *ciphertext, size_t ciphertext_len,
                   uint8_t *plaintext, size_t plaintext_size, size_t *plaintext_len) {
    if (ctx == NULL || ciphertext == NULL || plaintext == NULL) {
        log_error("crypto_decrypt: Invalid parameters");
        return -1;
    }
    
    if (plaintext_size < ciphertext_len) {
        log_error("Plaintext buffer too small");
        return -1;
    }
    
    log_warn("MOCK DECRYPTION: Copying ciphertext as-is (real decryption requires OpenSSL)");
    memcpy(plaintext, ciphertext, ciphertext_len);
    *plaintext_len = ciphertext_len;
    
    return 0;
}

int crypto_decrypt_buffer(const uint8_t *key, const uint8_t *iv, int mode,
                          const uint8_t *ciphertext, size_t ciphertext_len,
                          uint8_t *plaintext, size_t plaintext_size) {
    CryptoContext ctx;
    size_t plaintext_len = 0;
    
    if (crypto_init(&ctx, key, iv, mode) != 0) {
        return -1;
    }
    
    if (crypto_decrypt(&ctx, ciphertext, ciphertext_len, plaintext, plaintext_size, &plaintext_len) != 0) {
        return -1;
    }
    
    log_info("Decrypted %zu bytes (MOCK)", plaintext_len);
    return 0;
}
