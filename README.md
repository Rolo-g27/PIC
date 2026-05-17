# PIC – Java Card Smartcard Project

Projeto desenvolvido no âmbito da cadeira de PIC, com foco em Java Card, smartcards, comunicação APDU e transporte seguro de ficheiros.

## Estado atual

O projeto tem atualmente duas applets principais:

### 1. KeyManagerBaseApplet

Applet inicial para gestão de segredo/chave.

- AID: `A00000006203010D0201`
- Ficheiro principal: `keymanager-base/src/com/pic/keymanager/KeyManagerBaseApplet.java`

Funcionalidades:

- autenticação por PIN;
- alteração de PIN;
- carregamento de segredo de 16 bytes;
- armazenamento persistente;
- consulta de estado;
- eliminação do segredo.

### 2. SecureFileTransferApplet

Applet mais recente, alinhada com a ideia atual do projeto: usar o smartcard como meio de transporte de ficheiros encriptados.

- AID: `A00000006203010D0301`
- Ficheiro principal: `secure-transfer-card/src/com/pic/transfer/SecureFileTransferApplet.java`
- Protocolo APDU: `docs/APDU_PROTOCOL.md`

Funcionalidades já implementadas:

- autenticação por PIN;
- alteração de PIN;
- inicialização do armazenamento;
- carregamento de vários ficheiros pequenos;
- escrita de ficheiros por chunks/APDUs;
- cancelamento de upload parcial;
- armazenamento persistente no cartão;
- consulta de metadados dos ficheiros;
- leitura/download dos ficheiros por chunks;
- eliminação seletiva de ficheiros;
- confirmação de download;
- limpeza automática do cartão após confirmação;
- reset manual do cartão.

## Ideia atual do projeto

A aplicação externa encripta os ficheiros e envia os bytes já encriptados para o cartão.

O cartão:

1. recebe os ficheiros;
2. guarda-os de forma persistente;
3. protege o acesso por PIN;
4. permite o download no destino;
5. apaga os ficheiros após confirmação de download.

## Estrutura principal

```text
PIC/
├── keymanager-base/
│   └── src/com/pic/keymanager/KeyManagerBaseApplet.java
├── secure-transfer-card/
│   └── src/com/pic/transfer/SecureFileTransferApplet.java
├── docs/
│   └── COMO_CORRER_CARTAO.md
└── README.md
