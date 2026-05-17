package com.pic.transfer;

import javacard.framework.APDU;
import javacard.framework.Applet;
import javacard.framework.ISO7816;
import javacard.framework.ISOException;
import javacard.framework.OwnerPIN;
import javacard.framework.Util;

public final class SecureFileTransferApplet extends Applet {

    private static final byte APP_CLA = (byte) 0x80;

    // 0x10 GET_STATUS: returns [pinValidated, state, fileCount, maxFiles]
    // 0x11 GET_VERSION: returns [major, minor, maxFiles, maxPages, pinSupported]
    // 0x20 VERIFY_PIN: data = 4-byte PIN
    // 0x22 CHANGE_PIN: data = new 4-byte PIN, requires validated PIN
    // 0x30 INIT_STORE: starts a new upload session, clears previous data
    // 0x31 ADD_FILE_HEADER: data = [nameLen][name][fileSize:2]
    // 0x32 WRITE_CHUNK: data = up to CHUNK_SIZE bytes
    // 0x33 FINALIZE_FILE: closes current file
    // 0x34 FINALIZE_STORE: moves state to READY
    // 0x35 ABORT_STORE: cancels current upload and clears partial data
    // 0x40 GET_FILE_INFO: P1 = file index
    // 0x50 READ_CHUNK: P1 = file index, P2 = chunk index
    // 0x60 CONFIRM_DOWNLOAD: clears files after successful download
    // 0x61 DELETE_FILE: P1 = file index, P2 = 0x00
    // 0x70 WIPE_CARD: manual full clear
    private static final byte INS_GET_STATUS       = (byte) 0x10;
    private static final byte INS_GET_VERSION      = (byte) 0x11;
    private static final byte INS_VERIFY_PIN       = (byte) 0x20;
    private static final byte INS_CHANGE_PIN       = (byte) 0x22;

    private static final byte INS_INIT_STORE       = (byte) 0x30;
    private static final byte INS_ADD_FILE_HEADER  = (byte) 0x31;
    private static final byte INS_WRITE_CHUNK      = (byte) 0x32;
    private static final byte INS_FINALIZE_FILE    = (byte) 0x33;
    private static final byte INS_FINALIZE_STORE   = (byte) 0x34;
    private static final byte INS_ABORT_STORE      = (byte) 0x35;

    private static final byte INS_GET_FILE_INFO    = (byte) 0x40;
    private static final byte INS_READ_CHUNK       = (byte) 0x50;
    private static final byte INS_CONFIRM_DOWNLOAD = (byte) 0x60;
    private static final byte INS_DELETE_FILE      = (byte) 0x61;
    private static final byte INS_WIPE_CARD        = (byte) 0x70;

    private static final byte PIN_TRY_LIMIT = (byte) 3;
    private static final byte PIN_SIZE = (byte) 4;

    private static final byte STATE_EMPTY   = (byte) 0x00;
    private static final byte STATE_LOADING = (byte) 0x01;
    private static final byte STATE_READY   = (byte) 0x02;

    private static final byte MAX_FILES = (byte) 7;
    private static final byte MAX_NAME_SIZE = (byte) 16;

    private static final short PAGE_SIZE = (short) 4096;
    private static final byte MAX_PAGES = (byte) 18; // 18 * 4096 = 73728 bytes

    private static final short MAX_FILE_SIZE = (short) 10240;
    private static final short CHUNK_SIZE = (short) 200;

    private static final short SW_PIN_REQUIRED = (short) 0x6982;
    private static final short SW_PIN_BLOCKED = (short) 0x6983;
    private static final short SW_NOT_ENOUGH_MEMORY = (short) 0x6A84;

    private final OwnerPIN userPin;

    private final byte[] page0;
    private final byte[] page1;
    private final byte[] page2;
    private final byte[] page3;
    private final byte[] page4;
    private final byte[] page5;
    private final byte[] page6;
    private final byte[] page7;
    private final byte[] page8;
    private final byte[] page9;
    private final byte[] page10;
    private final byte[] page11;
    private final byte[] page12;
    private final byte[] page13;
    private final byte[] page14;
    private final byte[] page15;
    private final byte[] page16;
    private final byte[] page17;

    private final byte[] fileNameLengths;
    private final byte[] fileNames;
    private final byte[] fileStartPages;
    private final short[] fileStartOffsets;
    private final short[] fileSizes;

    private byte state;
    private byte fileCount;

    private byte writePage;
    private short writeOffset;

    private boolean currentFileActive;
    private byte currentFileIndex;
    private short currentExpectedSize;
    private short currentWritten;

    public static void install(byte[] bArray, short bOffset, byte bLength) {
        new SecureFileTransferApplet();
    }

    private SecureFileTransferApplet() {
        userPin = new OwnerPIN(PIN_TRY_LIMIT, PIN_SIZE);

        byte[] defaultPin = new byte[] {
                (byte) 0x01,
                (byte) 0x02,
                (byte) 0x03,
                (byte) 0x04
        };

        userPin.update(defaultPin, (short) 0, PIN_SIZE);

        page0 = new byte[PAGE_SIZE];
        page1 = new byte[PAGE_SIZE];
        page2 = new byte[PAGE_SIZE];
        page3 = new byte[PAGE_SIZE];
        page4 = new byte[PAGE_SIZE];
        page5 = new byte[PAGE_SIZE];
        page6 = new byte[PAGE_SIZE];
        page7 = new byte[PAGE_SIZE];
        page8 = new byte[PAGE_SIZE];
        page9 = new byte[PAGE_SIZE];
        page10 = new byte[PAGE_SIZE];
        page11 = new byte[PAGE_SIZE];
        page12 = new byte[PAGE_SIZE];
        page13 = new byte[PAGE_SIZE];
        page14 = new byte[PAGE_SIZE];
        page15 = new byte[PAGE_SIZE];
        page16 = new byte[PAGE_SIZE];
        page17 = new byte[PAGE_SIZE];

        fileNameLengths = new byte[MAX_FILES];
        fileNames = new byte[(short) (MAX_FILES * MAX_NAME_SIZE)];
        fileStartPages = new byte[MAX_FILES];
        fileStartOffsets = new short[MAX_FILES];
        fileSizes = new short[MAX_FILES];

        resetMetadata();

        register();
    }

    public void process(APDU apdu) {
        if (selectingApplet()) {
            return;
        }

        byte[] buffer = apdu.getBuffer();

        if (buffer[ISO7816.OFFSET_CLA] != APP_CLA) {
            ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);
        }

        switch (buffer[ISO7816.OFFSET_INS]) {
            case INS_GET_STATUS:
                getStatus(apdu);
                return;

            case INS_GET_VERSION:
                getVersion(apdu);
                return;

            case INS_VERIFY_PIN:
                verifyPin(apdu);
                return;

            case INS_CHANGE_PIN:
                requirePin();
                changePin(apdu);
                return;

            case INS_INIT_STORE:
                requirePin();
                initStore();
                return;

            case INS_ADD_FILE_HEADER:
                requirePin();
                addFileHeader(apdu);
                return;

            case INS_WRITE_CHUNK:
                requirePin();
                writeChunk(apdu);
                return;

            case INS_FINALIZE_FILE:
                requirePin();
                finalizeFile();
                return;

            case INS_FINALIZE_STORE:
                requirePin();
                finalizeStore();
                return;

            case INS_ABORT_STORE:
                requirePin();
                abortStore();
                return;

            case INS_GET_FILE_INFO:
                requirePin();
                getFileInfo(apdu);
                return;

            case INS_READ_CHUNK:
                requirePin();
                readChunk(apdu);
                return;

            case INS_CONFIRM_DOWNLOAD:
                requirePin();
                wipeCard();
                userPin.reset();
                return;

            case INS_DELETE_FILE:
                requirePin();
                deleteFile(apdu);
                return;

            case INS_WIPE_CARD:
                requirePin();
                wipeCard();
                userPin.reset();
                return;

            default:
                ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
        }
    }

    public void deselect() {
        userPin.reset();
    }

    private void getStatus(APDU apdu) {
        byte[] buffer = apdu.getBuffer();

        buffer[0] = userPin.isValidated() ? (byte) 0x01 : (byte) 0x00;
        buffer[1] = state;
        buffer[2] = fileCount;
        buffer[3] = MAX_FILES;

        apdu.setOutgoingAndSend((short) 0, (short) 4);
    }

    private void getVersion(APDU apdu) {
        byte[] buffer = apdu.getBuffer();

        buffer[0] = (byte) 0x01; // major
        buffer[1] = (byte) 0x00; // minor
        buffer[2] = MAX_FILES;
        buffer[3] = MAX_PAGES;
        buffer[4] = (byte) 0x01; // PIN supported

        apdu.setOutgoingAndSend((short) 0, (short) 5);
    }

    private void verifyPin(APDU apdu) {
        byte[] buffer = apdu.getBuffer();

        if (userPin.getTriesRemaining() == (byte) 0) {
            ISOException.throwIt(SW_PIN_BLOCKED);
        }

        short lc = unsigned(buffer[ISO7816.OFFSET_LC]);

        if (lc != PIN_SIZE) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        short bytesRead = apdu.setIncomingAndReceive();

        if (bytesRead != PIN_SIZE) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        boolean valid = userPin.check(buffer, ISO7816.OFFSET_CDATA, PIN_SIZE);

        if (!valid) {
            byte triesRemaining = userPin.getTriesRemaining();

            if (triesRemaining == (byte) 0) {
                ISOException.throwIt(SW_PIN_BLOCKED);
            }

            ISOException.throwIt((short) (0x63C0 | triesRemaining));
        }
    }

    private void changePin(APDU apdu) {
        byte[] buffer = apdu.getBuffer();

        short lc = unsigned(buffer[ISO7816.OFFSET_LC]);

        if (lc != PIN_SIZE) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        short bytesRead = apdu.setIncomingAndReceive();

        if (bytesRead != PIN_SIZE) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        userPin.update(buffer, ISO7816.OFFSET_CDATA, PIN_SIZE);
        userPin.reset();
    }

    private void initStore() {
        wipeUsedStorage();
        resetMetadata();
        state = STATE_LOADING;
    }

    private void addFileHeader(APDU apdu) {
        if (state != STATE_LOADING || currentFileActive) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }

        if (fileCount >= MAX_FILES) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }

        byte[] buffer = apdu.getBuffer();

        short lc = unsigned(buffer[ISO7816.OFFSET_LC]);
        short bytesRead = apdu.setIncomingAndReceive();

        if (bytesRead != lc) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        short cdata = ISO7816.OFFSET_CDATA;
        short nameLen = unsigned(buffer[cdata]);

        if (nameLen <= (short) 0 || nameLen > MAX_NAME_SIZE) {
            ISOException.throwIt(ISO7816.SW_WRONG_DATA);
        }

        short expectedLc = (short) (1 + nameLen + 2);

        if (lc != expectedLc) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        short sizeOffset = (short) (cdata + 1 + nameLen);
        short fileSize = Util.getShort(buffer, sizeOffset);

        if (fileSize <= (short) 0 || fileSize > MAX_FILE_SIZE) {
            ISOException.throwIt(ISO7816.SW_WRONG_DATA);
        }

        if (!hasCapacity(fileSize)) {
            ISOException.throwIt(SW_NOT_ENOUGH_MEMORY);
        }

        byte index = fileCount;

        fileNameLengths[index] = (byte) nameLen;
        Util.arrayCopyNonAtomic(
                buffer,
                (short) (cdata + 1),
                fileNames,
                nameOffset(index),
                nameLen
        );

        fileStartPages[index] = writePage;
        fileStartOffsets[index] = writeOffset;
        fileSizes[index] = fileSize;

        currentFileActive = true;
        currentFileIndex = index;
        currentExpectedSize = fileSize;
        currentWritten = (short) 0;
    }

    private void writeChunk(APDU apdu) {
        if (state != STATE_LOADING || !currentFileActive) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }

        byte[] buffer = apdu.getBuffer();

        short lc = unsigned(buffer[ISO7816.OFFSET_LC]);

        if (lc <= (short) 0 || lc > CHUNK_SIZE) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        short bytesRead = apdu.setIncomingAndReceive();

        if (bytesRead != lc) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        if (currentWritten > (short) (currentExpectedSize - lc)) {
            ISOException.throwIt(ISO7816.SW_WRONG_DATA);
        }

        writeToStorage(buffer, ISO7816.OFFSET_CDATA, lc);

        currentWritten = (short) (currentWritten + lc);
    }

    private void finalizeFile() {
        if (state != STATE_LOADING || !currentFileActive) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }

        if (currentWritten != currentExpectedSize) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }

        fileCount++;
        currentFileActive = false;
        currentFileIndex = (byte) 0;
        currentExpectedSize = (short) 0;
        currentWritten = (short) 0;
    }

    private void finalizeStore() {
        if (state != STATE_LOADING || currentFileActive || fileCount == (byte) 0) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }

        state = STATE_READY;
        userPin.reset();
    }

    private void abortStore() {
        wipeCard();
        userPin.reset();
    }

    private void getFileInfo(APDU apdu) {
        byte[] buffer = apdu.getBuffer();

        byte index = buffer[ISO7816.OFFSET_P1];

        if (!validFileIndex(index)) {
            ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
        }

        short nameLen = unsigned(fileNameLengths[index]);
        short out = (short) 0;

        buffer[out++] = (byte) nameLen;

        Util.arrayCopyNonAtomic(
                fileNames,
                nameOffset(index),
                buffer,
                out,
                nameLen
        );

        out = (short) (out + nameLen);

        Util.setShort(buffer, out, fileSizes[index]);
        out = (short) (out + 2);

        apdu.setOutgoingAndSend((short) 0, out);
    }

    private void readChunk(APDU apdu) {
        if (state != STATE_READY) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }

        byte[] buffer = apdu.getBuffer();

        byte index = buffer[ISO7816.OFFSET_P1];

        if (!validFileIndex(index)) {
            ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
        }

        short chunkIndex = unsigned(buffer[ISO7816.OFFSET_P2]);
        short offset = chunkOffset(chunkIndex);
        short fileSize = fileSizes[index];

        if (offset >= fileSize) {
            ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
        }

        short remaining = (short) (fileSize - offset);
        short len = remaining > CHUNK_SIZE ? CHUNK_SIZE : remaining;

        readFromStorage(index, offset, buffer, (short) 0, len);

        apdu.setOutgoingAndSend((short) 0, len);
    }

    private void deleteFile(APDU apdu) {
        if (state != STATE_READY) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }

        byte[] buffer = apdu.getBuffer();

        if (buffer[ISO7816.OFFSET_P2] != (byte) 0x00) {
            ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
        }

        if (unsigned(buffer[ISO7816.OFFSET_LC]) != (short) 0) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        byte index = buffer[ISO7816.OFFSET_P1];

        if (!validFileIndex(index)) {
            ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
        }

        byte lastIndex = (byte) (fileCount - 1);
        byte current = index;

        while (current < lastIndex) {
            byte next = (byte) (current + 1);

            fileNameLengths[current] = fileNameLengths[next];
            Util.arrayCopyNonAtomic(
                    fileNames,
                    nameOffset(next),
                    fileNames,
                    nameOffset(current),
                    MAX_NAME_SIZE
            );
            fileStartPages[current] = fileStartPages[next];
            fileStartOffsets[current] = fileStartOffsets[next];
            fileSizes[current] = fileSizes[next];

            current = next;
        }

        fileCount = lastIndex;
        clearFileMetadata(fileCount);

        if (fileCount == (byte) 0) {
            state = STATE_EMPTY;
        }
    }

    private void requirePin() {
        if (!userPin.isValidated()) {
            ISOException.throwIt(SW_PIN_REQUIRED);
        }
    }

    private boolean validFileIndex(byte index) {
        return index >= (byte) 0 && index < fileCount;
    }

    private short chunkOffset(short chunkIndex) {
        short offset = (short) 0;
        short i = (short) 0;

        while (i < chunkIndex) {
            offset = (short) (offset + CHUNK_SIZE);
            i++;
        }

        return offset;
    }

    private void writeToStorage(byte[] src, short srcOff, short len) {
        short remaining = len;
        short currentSrc = srcOff;

        while (remaining > (short) 0) {
            if (writePage >= MAX_PAGES) {
                ISOException.throwIt(SW_NOT_ENOUGH_MEMORY);
            }

            byte[] page = getPage(writePage);
            short space = (short) (PAGE_SIZE - writeOffset);
            short toCopy = remaining > space ? space : remaining;

            Util.arrayCopyNonAtomic(src, currentSrc, page, writeOffset, toCopy);

            currentSrc = (short) (currentSrc + toCopy);
            remaining = (short) (remaining - toCopy);
            writeOffset = (short) (writeOffset + toCopy);

            if (writeOffset == PAGE_SIZE) {
                writePage++;
                writeOffset = (short) 0;
            }
        }
    }

    private void readFromStorage(byte fileIndex, short fileOffset, byte[] dest, short destOff, short len) {
        byte p = fileStartPages[fileIndex];
        short off = fileStartOffsets[fileIndex];
        short skip = fileOffset;

        while (skip > (short) 0) {
            short space = (short) (PAGE_SIZE - off);

            if (skip < space) {
                off = (short) (off + skip);
                skip = (short) 0;
            } else {
                skip = (short) (skip - space);
                p++;
                off = (short) 0;
            }
        }

        short remaining = len;
        short currentDest = destOff;

        while (remaining > (short) 0) {
            byte[] page = getPage(p);
            short space = (short) (PAGE_SIZE - off);
            short toCopy = remaining > space ? space : remaining;

            Util.arrayCopyNonAtomic(page, off, dest, currentDest, toCopy);

            currentDest = (short) (currentDest + toCopy);
            remaining = (short) (remaining - toCopy);
            off = (short) (off + toCopy);

            if (off == PAGE_SIZE) {
                p++;
                off = (short) 0;
            }
        }
    }

    private boolean hasCapacity(short size) {
        byte p = writePage;
        short off = writeOffset;
        short remaining = size;

        while (remaining > (short) 0) {
            if (p >= MAX_PAGES) {
                return false;
            }

            short space = (short) (PAGE_SIZE - off);

            if (remaining <= space) {
                return true;
            }

            remaining = (short) (remaining - space);
            p++;
            off = (short) 0;
        }

        return true;
    }

    private void clearFileMetadata(byte index) {
        fileNameLengths[index] = (byte) 0;
        Util.arrayFillNonAtomic(
                fileNames,
                nameOffset(index),
                MAX_NAME_SIZE,
                (byte) 0x00
        );
        fileStartPages[index] = (byte) 0;
        fileStartOffsets[index] = (short) 0;
        fileSizes[index] = (short) 0;
    }

    private void wipeCard() {
        wipeUsedStorage();
        resetMetadata();
    }

    private void wipeUsedStorage() {
        byte p = (byte) 0;

        while (p < writePage && p < MAX_PAGES) {
            Util.arrayFillNonAtomic(getPage(p), (short) 0, PAGE_SIZE, (byte) 0x00);
            p++;
        }

        if (writePage < MAX_PAGES && writeOffset > (short) 0) {
            Util.arrayFillNonAtomic(getPage(writePage), (short) 0, writeOffset, (byte) 0x00);
        }
    }

    private void resetMetadata() {
        state = STATE_EMPTY;
        fileCount = (byte) 0;

        writePage = (byte) 0;
        writeOffset = (short) 0;

        currentFileActive = false;
        currentFileIndex = (byte) 0;
        currentExpectedSize = (short) 0;
        currentWritten = (short) 0;

        Util.arrayFillNonAtomic(fileNameLengths, (short) 0, (short) fileNameLengths.length, (byte) 0x00);
        Util.arrayFillNonAtomic(fileNames, (short) 0, (short) fileNames.length, (byte) 0x00);
        Util.arrayFillNonAtomic(fileStartPages, (short) 0, (short) fileStartPages.length, (byte) 0x00);

        byte i = (byte) 0;
        while (i < MAX_FILES) {
            fileStartOffsets[i] = (short) 0;
            fileSizes[i] = (short) 0;
            i++;
        }
    }

    private short nameOffset(byte fileIndex) {
        return (short) (unsigned(fileIndex) * MAX_NAME_SIZE);
    }

    private short unsigned(byte b) {
        return (short) (b & 0x00FF);
    }

    private byte[] getPage(byte page) {
        switch (page) {
            case 0: return page0;
            case 1: return page1;
            case 2: return page2;
            case 3: return page3;
            case 4: return page4;
            case 5: return page5;
            case 6: return page6;
            case 7: return page7;
            case 8: return page8;
            case 9: return page9;
            case 10: return page10;
            case 11: return page11;
            case 12: return page12;
            case 13: return page13;
            case 14: return page14;
            case 15: return page15;
            case 16: return page16;
            case 17: return page17;
            default:
                ISOException.throwIt(SW_NOT_ENOUGH_MEMORY);
                return page0;
        }
    }
}
