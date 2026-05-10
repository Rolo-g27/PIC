"""
raspberry_receiver.py
Ferramenta de recepção de ficheiros para o Raspberry Pi no projeto PIC.
Comunicação com SecureFileTransferApplet via APDU.

O programa:
    - Liga ao smartcard
    - Valida o PIN
    - Descarrega todos os ficheiros do cartão
    - Guarda os ficheiros localmente
    - Confirma o download e limpa o cartão

Uso:
    python raspberry_receiver.py

Dependências:
    pyscard (instalar via pip: pip install pyscard)
"""

from smartcard_tool import SmartcardClient

DOWNLOAD_DIR = "./downloads"

client = SmartcardClient()

try:

    if client.connect():

        print("Validar PIN")

        if client.verify_pin():

            if client.download_all_files(DOWNLOAD_DIR):

                client.confirm_download()

                print("[OK] Download concluído.")

finally:
    client.disconnect()