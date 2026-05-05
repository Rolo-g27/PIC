package com.pic.keymanager;

import javacard.framework.APDU;
import javacard.framework.Applet;
import javacard.framework.ISO7816;
import javacard.framework.ISOException;
import javacard.framework.JCSystem;
import javacard.framework.OwnerPIN;
import javacard.framework.Util;

public final class KeyManagerBaseApplet extends Applet {

    private static final byte APP_CLA = (byte) 0x80;

    private static final byte INS_GET_STATUS      = (byte) 0x10;
    private static final byte INS_GET_VERSION     = (byte) 0x11;
    private static final byte INS_VERIFY_PIN      = (byte) 0x20;
    private static final byte INS_GET_PIN_INFO    = (byte) 0x21;
    private static final byte INS_CHANGE_PIN      = (byte) 0x22;
    private static final byte INS_LOGOUT          = (byte) 0x23;
    private static final byte INS_LOAD_SECRET     = (byte) 0x30;
    private static final byte INS_CLEAR_SECRET    = (byte) 0x40;
    private static final byte INS_GET_SECRET_INFO = (byte) 0x50;

    private static final byte PIN_TRY_LIMIT = (byte) 3;
    private static final byte PIN_SIZE = (byte) 4;

    private static final short SECRET_SIZE = (short) 16;

    private static final short SW_PIN_REQUIRED =
            ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED; // 6982

    private static final short SW_PIN_BLOCKED = (short) 0x6983; // PIN blocked

    private final OwnerPIN userPin;

    /*
     * Persistent array.
     * This will survive card reset/removal.
     */
    private final byte[] secret;

    /*
     * Persistent flag indicating whether a secret has been loaded.
     */
    private boolean secretLoaded;

    public static void install(byte[] bArray, short bOffset, byte bLength) {
        new KeyManagerBaseApplet();
    }

    private KeyManagerBaseApplet() {
        userPin = new OwnerPIN(PIN_TRY_LIMIT, PIN_SIZE);

        byte[] defaultPin = new byte[] {
                (byte) 0x01,
                (byte) 0x02,
                (byte) 0x03,
                (byte) 0x04
        };

        userPin.update(defaultPin, (short) 0, PIN_SIZE);

        secret = new byte[SECRET_SIZE];
        secretLoaded = false;

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

        if (buffer[ISO7816.OFFSET_P1] != (byte) 0x00 ||
            buffer[ISO7816.OFFSET_P2] != (byte) 0x00) {
            ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
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

            case INS_GET_PIN_INFO:
                getPinInfo(apdu);
                return;

            case INS_CHANGE_PIN:
                requirePin();
                changePin(apdu);
                return;

            case INS_LOGOUT:
                logout();
                return;

            case INS_LOAD_SECRET:
                requirePin();
                loadSecret(apdu);
                return;

            case INS_CLEAR_SECRET:
                requirePin();
                clearSecret();
                return;

            case INS_GET_SECRET_INFO:
                getSecretInfo(apdu);
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
        buffer[1] = secretLoaded ? (byte) 0x01 : (byte) 0x00;

        apdu.setOutgoingAndSend((short) 0, (short) 2);
    }

    private void getVersion(APDU apdu) {
        byte[] buffer = apdu.getBuffer();

        /*
         * Response:
         * byte 0 = major version
         * byte 1 = minor version
         * byte 2 = expected secret size
         * byte 3 = capabilities flags
         *
         * capabilities:
         * bit 0 = PIN supported
         * bit 1 = CHANGE_PIN supported
         */
        buffer[0] = (byte) 0x01;
        buffer[1] = (byte) 0x01;
        buffer[2] = (byte) SECRET_SIZE;
        buffer[3] = (byte) 0x03;

        apdu.setOutgoingAndSend((short) 0, (short) 4);
    }

    private void getPinInfo(APDU apdu) {
        byte[] buffer = apdu.getBuffer();

        buffer[0] = userPin.isValidated() ? (byte) 0x01 : (byte) 0x00;
        buffer[1] = userPin.getTriesRemaining();
        buffer[2] = PIN_TRY_LIMIT;

        apdu.setOutgoingAndSend((short) 0, (short) 3);
    }

    private void verifyPin(APDU apdu) {
        byte[] buffer = apdu.getBuffer();

        if (userPin.getTriesRemaining() == (byte) 0) {
            ISOException.throwIt(SW_PIN_BLOCKED);
        }

        byte lc = buffer[ISO7816.OFFSET_LC];

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

    private void logout() {
        userPin.reset();
    }

    private void changePin(APDU apdu) {
        byte[] buffer = apdu.getBuffer();

        byte lc = buffer[ISO7816.OFFSET_LC];

        if (lc != PIN_SIZE) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        short bytesRead = apdu.setIncomingAndReceive();

        if (bytesRead != PIN_SIZE) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        userPin.update(buffer, ISO7816.OFFSET_CDATA, PIN_SIZE);

        /*
         * Depois de alterar o PIN, a sessão deixa de estar autenticada.
         * O utilizador tem de voltar a autenticar-se com o novo PIN.
         */
        userPin.reset();
    }

    private void loadSecret(APDU apdu) {
        byte[] buffer = apdu.getBuffer();

        byte lc = buffer[ISO7816.OFFSET_LC];

        if (lc != (byte) SECRET_SIZE) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        short bytesRead = apdu.setIncomingAndReceive();

        if (bytesRead != SECRET_SIZE) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        if (secretLoaded) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }

        JCSystem.beginTransaction();
        Util.arrayCopyNonAtomic(
                buffer,
                ISO7816.OFFSET_CDATA,
                secret,
                (short) 0,
                SECRET_SIZE
        );
        secretLoaded = true;
        JCSystem.commitTransaction();
    }

    private void clearSecret() {
        JCSystem.beginTransaction();
        Util.arrayFillNonAtomic(secret, (short) 0, SECRET_SIZE, (byte) 0x00);
        secretLoaded = false;
        JCSystem.commitTransaction();
    }

    private void getSecretInfo(APDU apdu) {
        byte[] buffer = apdu.getBuffer();

        buffer[0] = secretLoaded ? (byte) 0x01 : (byte) 0x00;
        buffer[1] = (byte) SECRET_SIZE;

        apdu.setOutgoingAndSend((short) 0, (short) 2);
    }

    private void requirePin() {
        if (!userPin.isValidated()) {
            ISOException.throwIt(SW_PIN_REQUIRED);
        }
    }
}
