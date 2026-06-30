# PIC – Leitura de Smartcards usando Raspberry Pi 5

Projeto desenvolvido no âmbito da unidade curricular de PIC, com o objetivo de demonstrar um sistema de transporte seguro de ficheiros recorrendo a smartcards, Java Card, comunicação APDU e Raspberry Pi 5.

## Objetivo do Projeto

O sistema permite carregar ficheiros num smartcard, transportar fisicamente o cartão e descarregar posteriormente os ficheiros no destino.

A solução final assenta em três componentes principais:

1. Smartcard / Java Card  
   Responsável pelo armazenamento persistente dos ficheiros cifrados, controlo de acesso por PIN, gestão de metadados e comunicação por APDU.

2. PC Emissor  
   Responsável por selecionar ficheiros, cifrá-los localmente, enviar os dados cifrados para o cartão e finalizar a sessão de carregamento.

3. Raspberry Pi 5 / Recetor  
   Responsável por ler os ficheiros armazenados no cartão, descarregar os dados, decifrá-los e limpar o cartão após a receção.

## Arquitetura Geral

O fluxo funcional do sistema é o seguinte:

1. O utilizador seleciona ficheiros no PC emissor.
2. O executável Java cifra os ficheiros localmente com AES-CBC.
3. O payload enviado para o cartão segue o formato:

    [IV (16 bytes)][dados cifrados]

4. O smartcard recebe os dados já cifrados e guarda-os de forma persistente.
5. O acesso às operações sensíveis é protegido por PIN.
6. Os ficheiros são enviados e lidos através de comandos APDU fragmentados em chunks de 200 bytes.
7. No destino, o Raspberry Pi lê os ficheiros cifrados do cartão, decifra-os e confirma a descarga.
8. Após confirmação, o cartão é limpo e fica pronto para nova utilização.

## Applet Principal do Smartcard

A applet principal da versão final é:

    SecureFileTransferApplet

Localização:

    secure-transfer-card/src/com/pic/transfer/SecureFileTransferApplet.java

AID da applet:

    A00000006203010D0301

PIN default usado em testes e demonstração:

    01020304

## Funcionalidades da SecureFileTransferApplet

A applet implementa:

- seleção por AID;
- autenticação por PIN;
- alteração de PIN;
- controlo de tentativas de PIN;
- estados internos do cartão;
- inicialização de sessão de armazenamento;
- carregamento de metadados dos ficheiros;
- escrita de ficheiros em chunks;
- finalização individual de ficheiros;
- finalização da sessão de carregamento;
- consulta de estado;
- consulta de metadados;
- leitura de dados por chunks;
- confirmação de download;
- limpeza lógica do cartão;
- remoção lógica individual de ficheiros;
- cancelamento de carregamento em curso.

## Limites Técnicos Principais

A versão final da applet foi desenhada com os seguintes limites:

    MAX_FILES      = 7
    MAX_NAME_SIZE  = 16 bytes
    MAX_FILE_SIZE  = 10240 bytes
    CHUNK_SIZE     = 200 bytes
    PAGE_SIZE      = 4096 bytes
    MAX_PAGES      = 18

O limite de 200 bytes por chunk foi escolhido para respeitar os limites do APDU standard e manter margem para cabeçalhos e bytes de controlo.

## Protocolo APDU

A documentação do protocolo encontra-se em:

    docs/APDU_PROTOCOL.md

Resumo dos principais comandos:

| Comando | INS | Função |
|---|---:|---|
| GET_STATUS | 0x10 | Consulta estado do cartão |
| GET_VERSION | 0x11 | Consulta versão/capacidades |
| VERIFY_PIN | 0x20 | Valida PIN |
| CHANGE_PIN | 0x22 | Altera PIN |
| INIT_STORE | 0x30 | Inicia sessão de armazenamento |
| ADD_FILE_HEADER | 0x31 | Envia metadados do ficheiro |
| WRITE_CHUNK | 0x32 | Escreve chunk de dados |
| FINALIZE_FILE | 0x33 | Finaliza ficheiro atual |
| FINALIZE_STORE | 0x34 | Finaliza sessão de carregamento |
| ABORT_STORE | 0x35 | Cancela carregamento em curso |
| GET_FILE_INFO | 0x40 | Consulta metadados de ficheiro |
| READ_CHUNK | 0x50 | Lê chunk de ficheiro |
| CONFIRM_DOWNLOAD | 0x60 | Confirma descarga e limpa cartão |
| DELETE_FILE | 0x61 | Remove logicamente um ficheiro |
| WIPE_CARD | 0x70 | Limpa manualmente o cartão |

## Applet Inicial de Validação

Durante o desenvolvimento foi também criada uma applet inicial:

    KeyManagerBaseApplet

Localização:

    keymanager-base/src/com/pic/keymanager/KeyManagerBaseApplet.java

AID:

    A00000006203010D0201

Esta applet serviu como protótipo técnico para validar:

- autenticação por PIN;
- alteração de PIN;
- armazenamento persistente de um segredo simples;
- consulta de estado;
- eliminação do segredo;
- comunicação APDU com o cartão.

A solução final de transporte de ficheiros ficou assente na SecureFileTransferApplet.

## Branches do Projeto

O projeto foi desenvolvido em vários ramos.

### main

Contém a applet Java Card principal, documentação técnica e estrutura base do projeto.

Conteúdo principal:

    secure-transfer-card/
    keymanager-base/
    docs/
    README.md

### exec

Contém o executável Java do lado do PC emissor.

Conteúdo principal:

    tools/SmartcardTool.java
    tools/SmartcardGui.java

Esta branch implementa a interface gráfica Java Swing e a lógica de comunicação com o smartcard para:

- ligar ao leitor;
- selecionar a applet;
- validar PIN;
- selecionar ficheiros;
- cifrar ficheiros localmente;
- enviar ficheiros por APDU;
- limpar o cartão.

### Branches de investigação

Existem ainda branches de investigação e desenvolvimento usadas durante o projeto, nomeadamente para pesquisa e testes relacionados com Java Card e comunicação com smartcards.

## Executável Java do PC Emissor

Na branch exec, a aplicação pode ser compilada com:

    cd tools
    javac SmartcardTool.java SmartcardGui.java

Execução da interface gráfica:

    java SmartcardGui

Fluxo normal:

1. Ligar ao cartão.
2. Validar PIN.
3. Selecionar um ou mais ficheiros.
4. Carregar os ficheiros.
5. Finalizar sessão.
6. Limpar cartão quando necessário.

Quando vários ficheiros são carregados, devem ser selecionados na mesma operação de carregamento. Após FINALIZE_STORE, o estado local de autenticação é reinicializado e é necessário validar novamente o PIN para executar novas operações protegidas.

## Demonstração da Parte do Cartão

Para validar a applet instalada no cartão com GlobalPlatformPro:

    export GP="/home/rolo/IST/PIC/GlobalPlatformPro/tool/target/gp.jar"
    export GP_READER="Gemalto PC Twin Reader 00 00"
    export APPLET_AID="A00000006203010D0301"
    export PIN="01020304"

    java -jar "$GP" -r "$GP_READER" -l

Validação do estado do cartão:

    java -jar "$GP" -r "$GP_READER" -d -v \
      --apdu "00A404000A${APPLET_AID}" \
      --apdu "8020000004${PIN}" \
      --apdu "8010000004"

Exemplo de resposta esperada após carregar dois ficheiros:

    01020207 9000

Interpretação:

    01 = PIN validado
    02 = estado READY
    02 = dois ficheiros armazenados
    07 = máximo de sete ficheiros

## Segurança

A solução implementa:

- controlo de acesso por PIN;
- limite de tentativas de PIN;
- recusa de operações protegidas sem autenticação;
- armazenamento apenas de ficheiros já cifrados;
- divisão dos dados em chunks;
- limpeza do cartão após confirmação de download.

Notas importantes:

- O smartcard não cifra nem decifra os ficheiros; essa operação é feita fora do cartão.
- A chave AES é gerida fora do cartão e deve ser partilhada por canal seguro entre emissor e recetor.
- AES-CBC garante confidencialidade, mas não garante, por si só, autenticação ou integridade criptográfica.
- Como melhoria futura, poderão ser adicionados MAC, HMAC, assinaturas digitais ou outro mecanismo de autenticação dos dados.

## Estrutura Principal

Estrutura principal da branch main:

    .
    ├── docs/
    │   └── APDU_PROTOCOL.md
    ├── keymanager-base/
    │   └── src/com/pic/keymanager/KeyManagerBaseApplet.java
    ├── secure-transfer-card/
    │   └── src/com/pic/transfer/SecureFileTransferApplet.java
    └── README.md

Na branch exec:

    tools/
    ├── SmartcardTool.java
    └── SmartcardGui.java

## Estado Final

O projeto demonstra uma arquitetura funcional para transporte seguro de ficheiros com smartcards, combinando:

- Java Card;
- APDU;
- autenticação por PIN;
- armazenamento persistente;
- cifragem simétrica no emissor;
- leitura fragmentada no recetor;
- integração com Raspberry Pi 5.
