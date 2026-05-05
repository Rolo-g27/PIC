#!/usr/bin/env bash

set -u

GP="/home/rolo/IST/PIC/GlobalPlatformPro/tool/target/gp.jar"
APPLET_AID="A00000006203010D0201"

echo
echo "========================================"
echo "[1] SELECT + GET_STATUS inicial"
echo "Esperado: 0000 9000"
echo "========================================"
java -jar "$GP" -d -v \
  --apdu "00A404000A${APPLET_AID}" \
  --apdu "8010000002"

echo
echo "========================================"
echo "[2] LOAD_SECRET sem PIN"
echo "Esperado: 6982"
echo "========================================"
java -jar "$GP" -d -v \
  --apdu "00A404000A${APPLET_AID}" \
  --apdu "803000001000112233445566778899AABBCCDDEEFF" || true

echo
echo "========================================"
echo "[3] VERIFY_PIN + GET_STATUS"
echo "Esperado: 9000 / 0100 9000"
echo "========================================"
java -jar "$GP" -d -v \
  --apdu "00A404000A${APPLET_AID}" \
  --apdu "802000000401020304" \
  --apdu "8010000002"

echo
echo "========================================"
echo "[4] VERIFY_PIN + LOAD_SECRET + GET_SECRET_INFO"
echo "Esperado: 9000 / 9000 / 0110 9000"
echo "========================================"
java -jar "$GP" -d -v \
  --apdu "00A404000A${APPLET_AID}" \
  --apdu "802000000401020304" \
  --apdu "803000001000112233445566778899AABBCCDDEEFF" \
  --apdu "8050000002"

echo
echo "========================================"
echo "[5] GET_STATUS + GET_SECRET_INFO"
echo "Esperado: 0001 9000 / 0110 9000"
echo "========================================"
java -jar "$GP" -d -v \
  --apdu "00A404000A${APPLET_AID}" \
  --apdu "8010000002" \
  --apdu "8050000002"

echo
echo "========================================"
echo "[6] VERIFY_PIN + CLEAR_SECRET + GET_SECRET_INFO"
echo "Esperado: 9000 / 9000 / 0010 9000"
echo "========================================"
java -jar "$GP" -d -v \
  --apdu "00A404000A${APPLET_AID}" \
  --apdu "802000000401020304" \
  --apdu "80400000" \
  --apdu "8050000002"
