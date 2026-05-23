/**
 * raspberry_receiver.c
 * Ferramenta mínima de recepção em C para o Raspberry Pi.
 * Requer biblioteca PCSC-Lite (sudo apt-get install libpcsclite-dev)
 * Compilação: gcc raspberry_receiver.c -o receiver -I/usr/include/PCSC -lpcsclite
 */

#include <stdio.h>
#include <stdlib.h>
#include <winscard.h>

// Definições do Protocolo (Devem coincidir com a Applet e o Tool Java)
#define CLA 0x80
#define INS_VERIFY_PIN 0x20
#define INS_DOWNLOAD_ALL 0x50 // Exemplo de instrução de leitura
#define INS_CONFIRM 0x60

// AID da SecureFileTransferApplet
BYTE APP_AID[] = {0xA0, 0x00, 0x00, 0x00, 0x62, 0x03, 0x01, 0x0D, 0x03, 0x01};

void check_rv(LONG rv, const char* msg) { // Função auxiliar para verificar o código de retorno das funções PCSC e imprimir mensagens de erro
    if (rv != SCARD_S_SUCCESS) {
        printf("[ERRO] %s: %lX\n", msg, rv);
        exit(1);
    }
}

int main() {
    SCARDCONTEXT hContext;
    SCARDHANDLE hCard;
    DWORD dwActiveProtocol;
    LONG rv;

    // 1. Iniciar contexto PCSC
    rv = SCardEstablishContext(SCARD_SCOPE_SYSTEM, NULL, NULL, &hContext); // Estabelece um contexto de comunicação com o serviço PCSC, 
    // permitindo que o programa interaja com os leitores de smartcard disponíveis no sistema
    check_rv(rv, "Estabelecer Contexto"); 

    // 2. Ligar ao primeiro leitor disponível 
    // Nota: Simplificado para usar o primeiro leitor
    char mszReaders[1024];
    DWORD dwReaders = sizeof(mszReaders);
    rv = SCardListReaders(hContext, NULL, mszReaders, &dwReaders);
    check_rv(rv, "Listar Leitores");

    rv = SCardConnect(hContext, mszReaders, SCARD_SHARE_SHARED,
                      SCARD_PROTOCOL_T0 | SCARD_PROTOCOL_T1, &hCard, &dwActiveProtocol);
    check_rv(rv, "Conectar ao Cartão");
    printf("[OK] Ligado ao Smartcard.\n");

    // 3. SELECT APPLET
    BYTE select_apdu[] = {0x00, 0xA4, 0x04, 0x00, sizeof(APP_AID), 0xA0, 0x00, 0x00, 0x00, 0x62, 0x03, 0x01, 0x0D, 0x03, 0x01};
    BYTE pbRecvBuffer[258];
    DWORD dwRecvLength = sizeof(pbRecvBuffer);
    
    rv = SCardTransmit(hCard, SCARD_PCI_T1, select_apdu, sizeof(select_apdu), NULL, pbRecvBuffer, &dwRecvLength);
    if (pbRecvBuffer[dwRecvLength-2] == 0x90) {
        printf("[OK] Applet Selecionada.\n");
    }

    // 4. VERIFY PIN (Exemplo: 01020304)
    BYTE pin_apdu[] = {CLA, INS_VERIFY_PIN, 0x00, 0x00, 0x04, 0x01, 0x02, 0x03, 0x04};
    dwRecvLength = sizeof(pbRecvBuffer);
    rv = SCardTransmit(hCard, SCARD_PCI_T1, pin_apdu, sizeof(pin_apdu), NULL, pbRecvBuffer, &dwRecvLength);
    
    if (pbRecvBuffer[dwRecvLength-2] == 0x90) {
        printf("[OK] PIN Validado. A iniciar download...\n");
        
        // [TODO]:Implementar loop de download e escrita de ficheiros
        
        // 5. CONFIRM DOWNLOAD
        BYTE confirm_apdu[] = {CLA, INS_CONFIRM, 0x00, 0x00};
        rv = SCardTransmit(hCard, SCARD_PCI_T1, confirm_apdu, sizeof(confirm_apdu), NULL, pbRecvBuffer, &dwRecvLength);
        printf("[OK] Download confirmado e cartão limpo.\n");
    }

    // Limpeza
    SCardDisconnect(hCard, SCARD_LEAVE_CARD);
    SCardReleaseContext(hContext);

    return 0;
}