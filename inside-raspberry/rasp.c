#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <winscard.h>

#define CLA_APP 0x80
#define CHUNK_SIZE 200
#define MAX_NAME_SIZE 16

typedef struct {
    BYTE data[260];
    DWORD len;
    WORD sw;
} APDU_RESP;

LONG send_apdu(SCARDHANDLE hCard, DWORD active_protocol, BYTE cla, BYTE ins, BYTE p1, BYTE p2, BYTE lc, BYTE *cdata, int le, APDU_RESP *resp) {
    BYTE send_buf[300];
    DWORD send_len;

    send_buf[0] = cla;
    send_buf[1] = ins;
    send_buf[2] = p1;
    send_buf[3] = p2;
    
    if (lc > 0 && cdata != NULL) {
        send_buf[4] = lc;
        memcpy(&send_buf[5], cdata, lc);
        send_len = 5 + lc; 
    } else if (le >= 0) {
        send_buf[4] = (BYTE)le;
        send_len = 5;      
    } else {
        send_len = 4;      
    }

    resp->len = sizeof(resp->data);
    
    const SCARD_IO_REQUEST *pioSendPci;
    switch(active_protocol) {
        case SCARD_PROTOCOL_T0: 
            pioSendPci = SCARD_PCI_T0; break;
        case SCARD_PROTOCOL_T1: 
            pioSendPci = SCARD_PCI_T1; break;
        default: 
            return SCARD_E_PROTO_MISMATCH; 
    }

    LONG rv = SCardTransmit(hCard, pioSendPci, send_buf, send_len, NULL, resp->data, &resp->len);
    if (rv != SCARD_S_SUCCESS) {
        resp->sw = 0x0000;
        return rv;
    }

    if (resp->len >= 2) {
        resp->sw = (resp->data[resp->len - 2] << 8) | resp->data[resp->len - 1];
        resp->len -= 2; 
    } else {
        resp->sw = 0x0000; 
        resp->len = 0; 
    }
    
    return SCARD_S_SUCCESS;
}


int select_applet(SCARDHANDLE hCard, DWORD active_protocol) {
    BYTE aid[] = {0xA0, 0x00, 0x00, 0x00, 0x62, 0x03, 0x01, 0x0D, 0x03, 0x01};
    APDU_RESP resp;
    LONG rv = send_apdu(hCard, active_protocol, 0x00, 0xA4, 0x04, 0x00, sizeof(aid), aid, -1, &resp);
    if (rv == SCARD_S_SUCCESS && resp.sw == 0x9000) return 1;
    return 0;
}

int verify_pin(SCARDHANDLE hCard, DWORD active_protocol, BYTE *pin) {
    APDU_RESP resp;
    LONG rv = send_apdu(hCard, active_protocol, CLA_APP, 0x20, 0x00, 0x00, 0x04, pin, -1, &resp);
    if (rv == SCARD_S_SUCCESS && resp.sw == 0x9000) return 1;
    return 0;
}

int download_file(SCARDHANDLE hCard, DWORD active_protocol, BYTE file_index, const char *output_path) {
    APDU_RESP resp;

    LONG rv = send_apdu(hCard, active_protocol, CLA_APP, 0x40, file_index, 0x00, 0x00, NULL, 0xFF, &resp);
    if (rv != SCARD_S_SUCCESS || resp.sw != 0x9000) return 0;

    if (resp.len < 3) return 0;

    BYTE name_len = resp.data[0];
    
    if (name_len == 0 || name_len > MAX_NAME_SIZE || (DWORD)(1 + name_len + 2) > resp.len) {
        return 0;
    }

    char name[MAX_NAME_SIZE + 1];
    memcpy(name, &resp.data[1], name_len);
    name[name_len] = '\0';
    
    WORD file_size = (resp.data[1 + name_len] << 8) | resp.data[1 + name_len + 1];

    FILE *f = fopen(output_path, "wb");
    if (!f) return 0;

    WORD bytes_received = 0;
    BYTE chunk_buf_idx = 0;

    while (bytes_received < file_size) {
        rv = send_apdu(hCard, active_protocol, CLA_APP, 0x50, file_index, chunk_buf_idx++, 0x00, NULL, CHUNK_SIZE, &resp);
        if (rv != SCARD_S_SUCCESS || resp.sw != 0x9000) { fclose(f); return 0; }

        if (resp.len == 0) { fclose(f); return 0; }

        size_t written = fwrite(resp.data, 1, resp.len, f);
        if (written != resp.len) {
            fclose(f);
            return 0;
        }
        
        bytes_received += resp.len;
    }

    fclose(f);
    return 1;
}

int delete_file(SCARDHANDLE hCard, DWORD active_protocol, BYTE file_index) {
    APDU_RESP resp;
    LONG rv = send_apdu(hCard, active_protocol, CLA_APP, 0x61, file_index, 0x00, 0x00, NULL, -1, &resp);
    if (rv == SCARD_S_SUCCESS && resp.sw == 0x9000) return 1;
    return 0;
}

int main(int argc, char *argv[]) {
    if (argc < 2) {
        printf("{\"estado\": \"erro\", \"msg\": \"Nenhum comando fornecido.\"}\n      Uso: \n ./rasp --download <PIN>\"\n ./rasp --verify-pin <PIN>");
        return 1;
    }

    SCARDCONTEXT hContext;
    SCARDHANDLE hCard;
    DWORD dwActiveProtocol;
    LONG rv;
    LPSTR mszReaders = NULL;
    DWORD dwReaders = SCARD_AUTOALLOCATE;

    rv = SCardEstablishContext(SCARD_SCOPE_SYSTEM, NULL, NULL, &hContext);
    if (rv != SCARD_S_SUCCESS) {
        printf("{\"estado\": \"erro\", \"msg\": \"Servico PC/SC falhou.\"}\n");
        return 1;
    }

    rv = SCardListReaders(hContext, NULL, (LPSTR)&mszReaders, &dwReaders);
    if (rv != SCARD_S_SUCCESS || mszReaders == NULL) {
        printf("{\"estado\": \"erro\", \"msg\": \"Nenhum leitor de cartões encontrado.\"}\n");
        SCardReleaseContext(hContext);
        return 1;
    }

    rv = SCardConnect(hContext, mszReaders, SCARD_SHARE_SHARED, SCARD_PROTOCOL_T0 | SCARD_PROTOCOL_T1, &hCard, &dwActiveProtocol);
    if (rv != SCARD_S_SUCCESS) {
        printf("{\"estado\": \"erro\", \"msg\": \"Falha ao tentar conectar ao cartão. Está inserido?\"}\n");
        SCardFreeMemory(hContext, mszReaders);
        SCardReleaseContext(hContext);
        return 1;
    }

    if (!select_applet(hCard, dwActiveProtocol)) {
        printf("{\"estado\": \"erro\", \"msg\": \"Falha ao selecionar a Applet no cartão.\"}\n");
        goto cleanup;
    }

    // Comando: ./rasp --verify-pin 1234
    if (strcmp(argv[1], "--verify-pin") == 0) {
        if (argc != 3 || strlen(argv[2]) != 4) {
            printf("{\"estado\": \"erro\", \"msg\": \"PIN inválido ou não fornecido (4 dígitos requeridos).\"}\n");
            goto cleanup;
        }

        BYTE pin[4];
        for (int i = 0; i < 4; i++) pin[i] = (BYTE)(argv[2][i] - '0');

        if (verify_pin(hCard, dwActiveProtocol, pin)) {
            printf("{\"estado\": \"êxito\", \"ação\": \"verify_pin\", \"msg\": \"PIN aceite!\"}\n");
        } else {
            printf("{\"estado\": \"erro\", \"ação\": \"verify_pin\", \"msg\": \"PIN incorreto.\"}\n");
        }
    }
    
    // Comando: ./rasp --download 1234
    else if (strcmp(argv[1], "--download") == 0) {
        if (argc != 3 || strlen(argv[2]) != 4) {
            printf("{\"estado\": \"erro\", \"msg\": \"PIN obrigatorio para autorizar o download.\"}\n");
            goto cleanup;
        }

        BYTE pin[4];
        for (int i = 0; i < 4; i++) pin[i] = (BYTE)(argv[2][i] - '0');

        if (!verify_pin(hCard, dwActiveProtocol, pin)) {
            printf("{\"estado\": \"erro\", \"msg\": \"Acesso negado. PIN incorreto.\"}\n");
            goto cleanup;
        }

        int ficheiros_extraidos = 0;
        char nome_ficheiro[50];

        while (1) {
            sprintf(nome_ficheiro, "ficheiro_%d.txt", ficheiros_extraidos);

            if (download_file(hCard, dwActiveProtocol, 0, nome_ficheiro)) {
                delete_file(hCard, dwActiveProtocol, 0);
                ficheiros_extraidos++;
            } else {
                break; 
            }
        }

        if (ficheiros_extraidos > 0) {
            printf("{\"estado\": \"êxito\", \"ação\": \"download\", \"msg\": \"Sucesso! %d ficheiros descarregados e apagados.\"}\n", ficheiros_extraidos);
        } else {
            printf("{\"estado\": \"erro\", \"ação\": \"download\", \"msg\": \"O cartão não tinha ficheiros para descarregar.\"}\n");
        }
    }
    
    else {
        printf("{\"estado\": \"erro\", \"msg\": \"Comando não reconhecido.\"}\n");
    }

cleanup:
    SCardDisconnect(hCard, SCARD_UNPOWER_CARD);
    SCardFreeMemory(hContext, mszReaders);
    SCardReleaseContext(hContext);
    
    return 0;
}