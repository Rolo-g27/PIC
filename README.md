# PIC – Java Card Smartcard Project

Projeto desenvolvido no âmbito da cadeira de PIC, com foco em Java Card, smartcards e comunicação APDU.

## Estado atual

Atualmente existe uma applet própria instalada e funcional no cartão:

- `KeyManagerBaseApplet`
- AID: `A00000006203010D0201`

A applet já suporta:

- seleção da applet por APDU;
- autenticação por PIN;
- alteração de PIN;
- carregamento de um segredo de 16 bytes;
- armazenamento persistente do segredo;
- consulta do estado da applet;
- eliminação do segredo;
- validação de operações protegidas por PIN.

## Estrutura principal

```text
keymanager-base/
├── src/com/pic/keymanager/KeyManagerBaseApplet.java
├── build/
└── scripts/
EOD
