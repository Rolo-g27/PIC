# Protocolo APDU - SecureFileTransferApplet

Este ficheiro documenta o protocolo usado pela applet:

    secure-transfer-card/src/com/pic/transfer/SecureFileTransferApplet.java

AID da applet:

    A00000006203010D0301

CLA da applet:

    0x80

PIN inicial de demonstracao:

    01 02 03 04

## Modelo de cifra

A aplicacao externa cifra os ficheiros com uma chave simetrica e envia para o cartao os bytes ja cifrados.

O cartao nao calcula a cifra nesta versao. A applet guarda bytes cifrados, devolve bytes cifrados no download e apaga os dados depois de `CONFIRM_DOWNLOAD`.

## Limites

| Campo | Valor |
|---|---:|
| PIN_SIZE | 4 bytes |
| MAX_FILES | 7 ficheiros |
| MAX_NAME_SIZE | 16 bytes |
| MAX_FILE_SIZE | 10240 bytes |
| CHUNK_SIZE | 200 bytes |
| PAGE_SIZE | 4096 bytes |
| MAX_PAGES | 18 paginas |
| Capacidade total | 73728 bytes |
| Tamanho por ficheiro | 1 a 10240 bytes |

## Estados

| Estado | Valor | Significado |
|---|---:|---|
| STATE_EMPTY | 0x00 | Sem ficheiros guardados |
| STATE_LOADING | 0x01 | Upload em curso |
| STATE_READY | 0x02 | Ficheiros prontos para download |

## Comandos

| Comando | INS | P1 | P2 | Dados | Resposta |
|---|---:|---:|---:|---|---|
| GET_STATUS | 0x10 | 0x00 | 0x00 | nenhum | `[pinValidated][state][fileCount][maxFiles]` |
| GET_VERSION | 0x11 | 0x00 | 0x00 | nenhum | `[major][minor][maxFiles][maxPages][pinSupported]` |
| VERIFY_PIN | 0x20 | 0x00 | 0x00 | PIN de 4 bytes | sem dados |
| CHANGE_PIN | 0x22 | 0x00 | 0x00 | novo PIN de 4 bytes | sem dados |
| INIT_STORE | 0x30 | 0x00 | 0x00 | nenhum | sem dados |
| ADD_FILE_HEADER | 0x31 | 0x00 | 0x00 | `[nameLen][name][fileSize:2]` | sem dados |
| WRITE_CHUNK | 0x32 | 0x00 | 0x00 | 1 a 200 bytes | sem dados |
| FINALIZE_FILE | 0x33 | 0x00 | 0x00 | nenhum | sem dados |
| FINALIZE_STORE | 0x34 | 0x00 | 0x00 | nenhum | sem dados |
| ABORT_STORE | 0x35 | 0x00 | 0x00 | nenhum | sem dados |
| GET_FILE_INFO | 0x40 | indice | 0x00 | nenhum | `[nameLen][name][fileSize:2]` |
| READ_CHUNK | 0x50 | indice | chunk | nenhum | ate 200 bytes |
| CONFIRM_DOWNLOAD | 0x60 | 0x00 | 0x00 | nenhum | sem dados |
| DELETE_FILE | 0x61 | indice | 0x00 | nenhum | sem dados |
| WIPE_CARD | 0x70 | 0x00 | 0x00 | nenhum | sem dados |

Todos os comandos sensiveis exigem PIN validado, exceto `GET_STATUS`, `GET_VERSION` e `VERIFY_PIN`.

Na configuracao atual, `GET_VERSION` deve devolver:

    01 00 07 12 01

## Fluxo de upload

1. Selecionar a applet.
2. Enviar `VERIFY_PIN`.
3. Enviar `INIT_STORE`.
4. Para cada ficheiro:
   - enviar `ADD_FILE_HEADER`;
   - enviar um ou mais `WRITE_CHUNK`;
   - enviar `FINALIZE_FILE`.
5. Enviar `FINALIZE_STORE`. 

Depois de `FINALIZE_STORE`, a applet faz reset da sessão PIN. Para ler metadados ou conteúdo de ficheiros, a aplicação externa deve enviar novamente `VERIFY_PIN`.

Se algum passo do upload falhar, a aplicacao externa deve enviar `ABORT_STORE` e pedir nova tentativa ao utilizador.

## Fluxo de download

1. Selecionar a applet.
2. Enviar `VERIFY_PIN`.
3. Enviar `GET_STATUS`.
4. Para cada ficheiro:
   - enviar `GET_FILE_INFO`;
   - enviar `READ_CHUNK` com `P2 = 0, 1, 2, ...` ate ler o tamanho total do ficheiro.
5. Depois de confirmar que o download foi guardado corretamente, enviar `CONFIRM_DOWNLOAD`.

## Delete seletivo

O comando `DELETE_FILE` apaga apenas o ficheiro indicado em `P1`.

Formato:

    80 61 P1 00 00

Exemplo para apagar o ficheiro de indice 1:

    80 61 01 00 00

Comportamento:

- exige PIN validado;
- so funciona em `STATE_READY`;
- exige `P2 = 0x00`;
- se estiver fora de `STATE_READY`, devolve `6985`;
- se estiver em `STATE_READY` e o indice nao existir, devolve `6A86`;
- se `P2 != 0x00`, devolve `6A86`;
- depois de apagar, compacta os metadados para nao deixar buracos na lista;
- se apagar o ultimo ficheiro existente, o estado passa para `STATE_EMPTY`.

## Status words

| SW | Significado |
|---:|---|
| 9000 | Comando executado com sucesso |
| 63Cx | PIN errado; `x` indica tentativas restantes |
| 63C2 | PIN errado; 2 tentativas restantes |
| 63C1 | PIN errado; 1 tentativa restante |
| 6700 | Lc invalido, por exemplo chunk com mais de 200 bytes ou `DELETE_FILE` com dados |
| 6982 | PIN necessario |
| 6983 | PIN bloqueado |
| 6985 | Estado invalido, por exemplo `FINALIZE_FILE` antes de escrever tudo ou `DELETE_FILE` fora de `STATE_READY` |
| 6A80 | Dados invalidos, por exemplo nome demasiado grande ou ficheiro com mais de 10240 bytes |
| 6A84 | Memoria insuficiente |
| 6A86 | P1/P2 invalido, por exemplo indice de ficheiro inexistente ou `P2` diferente de zero |
| 6D00 | INS nao suportado |
| 6E00 | CLA nao suportado |


### Comportamento observado com PIN errado

A applet permite 3 tentativas de PIN.

| Tentativa | PIN enviado | Resposta | Significado |
|---:|---|---|---|
| 1.ª errada | `200000000` | `63C2` | PIN errado, 2 tentativas restantes |
| 2.ª errada | `00000000` | `63C1` | PIN errado, 1 tentativa restante |
| 3.ª errada | `00000000` | `6983` | PIN bloqueado |
| PIN correto após bloqueio | `01020304` | `6983` | PIN continua bloqueado |

Na versão atual da applet não existe comando administrativo para desbloquear o PIN. Em testes, a recuperação é feita apagando e reinstalando a applet.


## Exemplos APDU

Selecionar applet:

    00 A4 04 00 0A A00000006203010D0301

Verificar PIN `01 02 03 04`:

    80 20 00 00 04 01 02 03 04

Iniciar upload:

    80 30 00 00 00

Cancelar upload parcial:

    80 35 00 00 00

Apagar ficheiro de indice 1:

    80 61 01 00 00

Consultar estado:

    80 10 00 00 04
