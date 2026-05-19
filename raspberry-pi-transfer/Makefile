# Makefile for PIC Raspberry Pi File Transfer & Decryption
# Works on Windows (MinGW), Linux, and Raspberry Pi
# Compile with: make
# On Windows: mingw32-make or make

CC = gcc
CFLAGS = -Wall -Wextra -pedantic -std=c99 -g -Iinclude -I/usr/include -I/usr/include/PCSC

LDFLAGS = -lssl -lcrypto -lpcsclite

SRCS = src/main.c src/apdu.c src/crypto.c src/files.c src/config.c src/utils.c
OBJS = $(SRCS:src/%.c=build/%.o)
EXECUTABLE = pic-cli$(EXESUFFIX)

# Detect OS and set appropriate suffix
ifeq ($(OS),Windows_NT)
    EXESUFFIX = .exe
    RMCMD = del /f /q
    RMDCMD = rmdir /s /q
    CRYPTO_SRC = src/crypto_mock.c
else
    EXESUFFIX =
    RMCMD = rm -f
    RMDCMD = rm -rf
    CRYPTO_SRC = src/crypto.c
endif

.PHONY: all clean run help test-apdu test-simple test-decrypt

all: $(EXECUTABLE)

$(EXECUTABLE): $(OBJS) build
	@echo "[LINK] Creating executable..."
	$(CC) $(CFLAGS) -o $@ $^ $(LDFLAGS)
	@echo "[OK] Executable created: $@"

build/%.o: src/%.c build
	@echo "[CC] Compiling $<..."
	$(CC) $(CFLAGS) -c $< -o $@

build:
	@if not exist build mkdir build 2>nul || mkdir -p build 2>/dev/null || true
	@echo "[MKDIR] Created build directory"

clean:
	@echo "[CLEAN] Removing build artifacts..."
	@-if exist build $(RMDCMD) build 2>nul || $(RMDCMD) build 2>/dev/null || true
	@-$(RMCMD) $(EXECUTABLE) 2>nul || $(RMCMD) $(EXECUTABLE) 2>/dev/null || true
	@-$(RMCMD) test_apdu.exe 2>nul || $(RMCMD) test_apdu 2>/dev/null || true
	@echo "[OK] Clean complete"

run: all
	@echo "[RUN] Starting application..."
	./$(EXECUTABLE) config/config.ini

help:
	@echo "PIC Raspberry Pi - File Transfer & Decryption"
	@echo ""
	@echo "Targets:"
	@echo "  make              - Build the main executable"
	@echo "  make clean        - Remove build artifacts"
	@echo "  make run          - Build and run main app with default config"
	@echo "  make test-simple  - Build and run simple test (NO PCSC required)"
	@echo "  make test-decrypt - Build and run decryption test (NO PCSC required)"
	@echo "  make test-apdu    - Build and run APDU test (Linux/Raspberry Pi only)"
	@echo "  make help         - Show this help message"
	@echo ""
	@echo "On Windows:"
	@echo "  make test-simple  - Works! (tests crypto, files, config)"
	@echo "  make test-decrypt - Works! (tests decryption pipeline)"
	@echo "  make test-apdu    - Requires WSL or Linux environment"
	@echo ""
	@echo "Manual run:"
	@echo "  ./pic-cli config/config.ini         (main program)"
	@echo "  ./test_simple                       (Windows-compatible test)"
	@echo "  ./test_decrypt                      (decryption test)"
	@echo "  ./test_apdu                         (Linux/Raspberry Pi test)"

# Test targets
test-simple: test_simple$(EXESUFFIX)
	@echo "[RUN] Starting simple local test..."
	./test_simple$(EXESUFFIX)

test_simple$(EXESUFFIX): test_simple.c src/utils.c $(CRYPTO_SRC) src/files.c src/config.c build
	@echo "[LINK] Creating test executable..."
	$(CC) $(CFLAGS) -D_CRT_SECURE_NO_WARNINGS -o $@ test_simple.c src/utils.c $(CRYPTO_SRC) src/files.c src/config.c
	@echo "[OK] Test executable created: $@"

# Decryption test
test-decrypt: test_decrypt$(EXESUFFIX)
	@echo "[RUN] Starting decryption test..."
	./test_decrypt$(EXESUFFIX)

test_decrypt$(EXESUFFIX): test_decrypt.c src/utils.c $(CRYPTO_SRC) src/files.c src/config.c build
	@echo "[LINK] Creating decryption test executable..."
	$(CC) $(CFLAGS) -D_CRT_SECURE_NO_WARNINGS -o $@ test_decrypt.c src/utils.c $(CRYPTO_SRC) src/files.c src/config.c
	@echo "[OK] Decryption test executable created: $@"

# Test target - APDU only (Linux/Raspberry Pi only)
test-apdu: test_apdu
	@echo "[RUN] Starting APDU test..."
	./test_apdu

test_apdu: test_apdu.c build/apdu.o build/utils.o build
	@echo "[LINK] Creating APDU test executable (Linux/Raspberry Pi)..."
	$(CC) $(CFLAGS) -o $@ test_apdu.c build/apdu.o build/utils.o $(LDFLAGS)
	@echo "[OK] APDU test executable created: $@"