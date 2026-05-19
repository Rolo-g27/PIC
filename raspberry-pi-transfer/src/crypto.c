/*
 * crypto.c - AES-128 Decryption using OpenSSL libcrypto
 * 
 * Supports CBC (Cipher Block Chaining) and CTR (Counter) modes
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <openssl/aes.h>
#include <openssl/err.h>

#include "crypto.h"
#include "utils.h"

/**
 * Initialize crypto context
 */
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
    
    ctx->mode = mode;  /* 0=CBC, 1=CTR */
    
    log_info("Crypto initialized (mode=%s)", mode == 0 ? "CBC" : "CTR");
    return 0;
}

/**
 * Decrypt using AES-128-CBC
 */
static int crypto_decrypt_cbc(const uint8_t *key, const uint8_t *iv,
                              const uint8_t *ciphertext, size_t ciphertext_len,
                              uint8_t *plaintext, size_t plaintext_size) {
    AES_KEY aes_key;
    unsigned char local_iv[16];
    
    if (ciphertext_len % 16 != 0) {
        log_error("Ciphertext length not multiple of 16");
        return -1;
    }
    
    if (plaintext_size < ciphertext_len) {
        log_error("Plaintext buffer too small");
        return -1;
    }
    
    /* Prepare IV (make local copy since AES_cbc_encrypt modifies it) */
    memcpy(local_iv, iv, 16);
    
    /* Set decryption key */
    if (AES_set_decrypt_key(key, 128, &aes_key) < 0) {
        log_error("AES_set_decrypt_key failed");
        return -1;
    }
    
    /* Decrypt */
    AES_cbc_encrypt(ciphertext, plaintext, ciphertext_len, &aes_key, local_iv, AES_DECRYPT);
    
    return 0;
}

/**
 * Decrypt using AES-128-CTR
 */
static int crypto_decrypt_ctr(const uint8_t *key, const uint8_t *nonce,
                              const uint8_t *ciphertext, size_t ciphertext_len,
                              uint8_t *plaintext, size_t plaintext_size) {
    AES_KEY aes_key;
    unsigned char iv[16];
    unsigned char ecount[16];
    unsigned int num = 0;
    
    if (plaintext_size < ciphertext_len) {
        log_error("Plaintext buffer too small");
        return -1;
    }
    
    /* Prepare IV/nonce */
    memcpy(iv, nonce, 16);
    memset(ecount, 0, 16);
    
    /* Set encryption key (for CTR mode) */
    if (AES_set_encrypt_key(key, 128, &aes_key) < 0) {
        log_error("AES_set_encrypt_key failed");
        return -1;
    }
    
    /* Decrypt (CTR is symmetric) */
    AES_ctr128_encrypt(ciphertext, plaintext, ciphertext_len, &aes_key, iv, ecount, &num);
    
    return 0;
}

/**
 * Decrypt data using context
 */
int crypto_decrypt(CryptoContext *ctx, const uint8_t *ciphertext, size_t ciphertext_len,
                   uint8_t *plaintext, size_t plaintext_size, size_t *plaintext_len) {
    int ret;
    
    if (ctx == NULL || ciphertext == NULL || plaintext == NULL) {
        log_error("crypto_decrypt: Invalid parameters");
        return -1;
    }
    
    if (ctx->mode == 0) {
        /* CBC mode */
        ret = crypto_decrypt_cbc(ctx->key, ctx->iv, ciphertext, ciphertext_len, 
                                 plaintext, plaintext_size);
    } else {
        /* CTR mode */
        ret = crypto_decrypt_ctr(ctx->key, ctx->iv, ciphertext, ciphertext_len,
                                 plaintext, plaintext_size);
    }
    
    if (ret == 0) {
        *plaintext_len = ciphertext_len;
    }
    
    return ret;
}

/**
 * Helper: Decrypt entire buffer with one call
 */
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
    
    log_info("Decrypted %zu bytes", plaintext_len);
    return 0;
}
