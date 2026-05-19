# PIC Raspberry Pi - File Transfer & Decryption (C Implementation)

Download and decrypt files from Java Card smartcard via APDU communication.

## Quick Tests (Windows or Linux)

### Test 1: Simple Local Test (NO smartcard required)
Tests core components without PCSC or smartcard.

```bash
make test-simple
```

✓ Validates: Logging, HEX parsing, XOR, Config loading, File I/O, Crypto context

### Test 2: Decryption Pipeline Test (NO smartcard required)
Tests the full decryption flow with simulated data.

```bash
make test-decrypt
```

✓ Validates: Key setup, Crypto initialization, File operations, Config validation

### Test 3: APDU Test (Linux/RPi with smartcard)
Tests communication with real smartcard.

```bash
make test-apdu
```

✓ Validates: PC/SC connection, APDU commands, File download, Card auto-wipe

---

## Quick Start (Raspberry Pi with Smartcard)

### 1. Install Dependencies (Raspberry Pi / Linux)

```bash
sudo apt-get update
sudo apt-get install -y libpcsclite-dev libssl-dev build-essential
```

### 2. Build

```bash
make clean && make
```

### 3. Configure

Edit `config/config.ini`:
- Set PIN (must match card PIN)
- Set symmetric key (from Inês - same used for encryption)
- Set IV (initialization vector for AES)
- Set crypto mode (CBC or CTR - confirm with Inês)
- Set output directory

### 4. Run

```bash
./pic-cli config/config.ini
```

## Configuration

### PIN
4 bytes in hexadecimal (8 characters).
Default: `01020304` (01 02 03 04)

### Symmetric Key
16 bytes (AES-128) in hexadecimal (32 characters).
**CRITICAL**: Must match the key used by Inês for file encryption.

### IV (Initialization Vector)
16 bytes in hexadecimal (32 characters).
Used for CBC and CTR modes.
**Confirm with Inês** if it's included in encrypted data or hardcoded.

### Crypto Mode
- `CBC` - Cipher Block Chaining
- `CTR` - Counter mode

**Confirm with Inês** which mode is used.

### Output Directory
Where decrypted files will be saved.
Default: `./downloads`

## What It Does

1. **Load Configuration**: PIN, encryption key, IV, output directory
2. **Connect to Card**: Via PC/SC reader
3. **Authenticate**: Verify PIN with card
4. **List Files**: Get count and metadata from card
5. **Download Files**: Read encrypted data in 200-byte chunks
6. **Decrypt Files**: Use AES-128 with configured key/IV
7. **Save Files**: Write decrypted files to disk
8. **Confirm Download**: Card auto-wipes after confirmation

## Project Structure

```
raspberry-pi-transfer/
├── include/          # Header files
│   ├── apdu.h       # APDU communication
│   ├── crypto.h     # AES-128 decryption
│   ├── files.h      # File I/O
│   ├── config.h     # Configuration parsing
│   └── utils.h      # Logging & utilities
├── src/             # Implementation
│   ├── main.c       # Main program flow
│   ├── apdu.c       # APDU client (libpcsclite)
│   ├── crypto.c     # AES decryption (libcrypto)
│   ├── files.c      # File operations
│   ├── config.c     # Config parser
│   └── utils.c      # Logging
├── config/          # Configuration files
│   └── config.ini   # Runtime configuration
├── build/           # Build artifacts (generated)
├── Makefile         # Build system
└── README.md        # This file
```

## APDU Commands Used

### Applet Information
- **AID**: `A00000006203010D0301`
- **CLA**: `0x80`

### Commands
- `SELECT APPLET` (0x00 0xA4 0x04 0x00) - Select applet
- `VERIFY_PIN` (0x80 0x20 0x00 0x00) - Authenticate
- `GET_STATUS` (0x80 0x10 0x00 0x00) - Get file count
- `GET_FILE_INFO` (0x80 0x40) - Get filename & size
- `READ_CHUNK` (0x80 0x50) - Read 200-byte chunk
- `CONFIRM_DOWNLOAD` (0x80 0x60) - Confirm & auto-wipe

For details, see: `/docs/APDU_PROTOCOL.md`

## Troubleshooting

### "Failed to establish PC/SC context"
- Check PC/SC daemon is running: `sudo systemctl status pcscd`
- Restart if needed: `sudo systemctl restart pcscd`

### "Card not found"
- Insert card and wait a few seconds
- Check reader is detected: `pcsc_scan`

### "PIN verification failed"
- Verify PIN in config matches card PIN
- Card may be blocked after 3 wrong attempts (requires restart)

### "Decryption failed"
- Verify symmetric key is correct (from Inês)
- Verify IV is correct
- Verify crypto mode matches (CBC vs CTR)
- Encrypted data may be corrupted

### "Permission denied when writing files"
- Check output directory is writable
- Check file permissions
- Try different output directory

## Dependencies

- **libpcsclite**: PC/SC reader communication
- **libssl-dev**: OpenSSL for AES-128 decryption
- **gcc/clang**: C compiler
- **make**: Build automation

## Manual Build (without Make)

```bash
gcc -Wall -Wextra -pedantic -std=c99 -g \
    -Iinclude \
    src/main.c src/apdu.c src/crypto.c src/files.c src/config.c src/utils.c \
    $(pkg-config --cflags --libs openssl) \
    $(pkg-config --cflags --libs libpcsclite) \
    -o pic-cli
```

## Important Notes

1. **Security**: Keep PIN and symmetric key confidential
2. **Configuration**: Config file contains sensitive data - protect it
3. **Smartcard**: Platform supports ISO 7816-4 smartcards with PC/SC readers
4. **Compatibility**: Tested on Linux/Raspberry Pi OS

## Related Documentation

- **APDU Protocol**: See `/docs/APDU_PROTOCOL.md` for command details
- **Java Card Applet**: See `/secure-transfer-card/src/com/pic/transfer/SecureFileTransferApplet.java`
- **Project Overview**: See `/README.md`

## Author

Implementação em C - Componente Raspberry Pi do Projeto PIC

## License

Part of PIC Project - Academic Project

---

**Questions?** Check the configuration and ensure:
1. PIN matches card PIN
2. Symmetric key matches Inês's encryption key
3. Crypto mode (CBC/CTR) is correct
4. IV is configured correctly
