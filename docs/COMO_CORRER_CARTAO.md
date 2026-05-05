# Manual de utilização da applet no smart card

Este ficheiro explica como correr a applet atualmente implementada no cartão, como testar as funcionalidades já feitas e como demonstrar o projeto ao professor.

## 1. Funcionalidades já implementadas

A applet atualmente implementada chama-se:

    KeyManagerBaseApplet

AID da applet:

    A00000006203010D0201

Ficheiro principal:

    keymanager-base/src/com/pic/keymanager/KeyManagerBaseApplet.java

Funcionalidades já existentes:

| Função | INS | Descrição |
|---|---:|---|
| GET_STATUS | 0x10 | Consulta se o PIN está validado e se existe segredo carregado |
| VERIFY_PIN | 0x20 | Valida o PIN do utilizador |
| CHANGE_PIN | 0x22 | Permite alterar o PIN após autenticação |
| LOAD_SECRET | 0x30 | Carrega um segredo de 16 bytes no cartão |
| CLEAR_SECRET | 0x40 | Apaga o segredo guardado |
| GET_SECRET_INFO | 0x50 | Consulta se há segredo carregado e qual o tamanho esperado |

PIN inicial de demonstração:

    01 02 03 04

## 2. Estrutura relevante do projeto

    PIC/
    ├── keymanager-base/
    │   ├── src/com/pic/keymanager/KeyManagerBaseApplet.java
    │   ├── scripts/test_keymanager_base.sh
    │   └── build/
    ├── GlobalPlatformPro/
    ├── java_card_tools/
    └── docs/COMO_CORRER_CARTAO.md

O ficheiro que contém o código da applet é:

    keymanager-base/src/com/pic/keymanager/KeyManagerBaseApplet.java

A pasta `build/` contém ficheiros gerados automaticamente pela compilação/conversão e não deve ser editada manualmente.

## 3. Preparar terminal

Entrar na pasta da applet:

    cd ~/IST/PIC/keymanager-base

Definir variáveis principais:

    export GP="/home/rolo/IST/PIC/GlobalPlatformPro/tool/target/gp.jar"
    export APPLET_AID="A00000006203010D0201"

## 4. Verificar se o leitor está detetado

Listar leitores disponíveis:

    java -jar "$GP" -r

Se estiver tudo correto, deverá aparecer algo como:

    Available readers:
    - Gemalto PC Twin Reader 00 00

Definir o leitor:

    export GP_READER="Gemalto PC Twin Reader 00 00"

Se o leitor não aparecer, reiniciar o serviço PC/SC:

    sudo systemctl restart pcscd

Depois correr:

    pcsc_scan

Quando o cartão aparecer, sair com:

    CTRL + C

Depois voltar a testar:

    java -jar "$GP" -r

## 5. Confirmar que a applet está instalada no cartão

Correr:

    java -jar "$GP" -r "$GP_READER" -l

No output deverá aparecer:

    APP: A00000006203010D0201 (SELECTABLE)

    PKG: A00000006203010D02 (LOADED)
         Applet: A00000006203010D0201

Isto confirma que a applet do projeto está instalada e pronta a ser selecionada.

## 6. Demo principal para mostrar ao professor

### 6.1. Consultar estado inicial

Comando:

    java -jar "$GP" -r "$GP_READER" -d -v \
      --apdu "00A404000A${APPLET_AID}" \
      --apdu "8010000002"

Resposta esperada:

    A<< 0000 9000

Interpretação:

    00 = PIN não validado
    00 = segredo não carregado
    9000 = comando executado com sucesso

Se aparecer:

    A<< 0001 9000

significa que o PIN não está validado, mas já existe segredo carregado de uma execução anterior.

### 6.2. Tentar carregar segredo sem PIN

Comando:

    java -jar "$GP" -r "$GP_READER" -d -v \
      --apdu "00A404000A${APPLET_AID}" \
      --apdu "803000001000112233445566778899AABBCCDDEEFF"

Resposta esperada:

    A<< 6982

Interpretação:

    6982 = operação recusada por falta de autenticação

Este teste mostra que o cartão não permite carregar material sensível sem PIN.

### 6.3. Autenticar com PIN

Comando:

    java -jar "$GP" -r "$GP_READER" -d -v \
      --apdu "00A404000A${APPLET_AID}" \
      --apdu "802000000401020304" \
      --apdu "8010000002"

Resposta esperada:

    A<< 9000
    A<< 0100 9000

Interpretação:

    9000 = PIN correto
    01 = PIN validado
    00 = segredo ainda não carregado

Se aparecer:

    A<< 0101 9000

significa que o PIN está validado e que já existe segredo carregado.

### 6.4. Carregar segredo depois da autenticação

Comando:

    java -jar "$GP" -r "$GP_READER" -d -v \
      --apdu "00A404000A${APPLET_AID}" \
      --apdu "802000000401020304" \
      --apdu "803000001000112233445566778899AABBCCDDEEFF" \
      --apdu "8050000002"

Resposta esperada:

    A<< 9000
    A<< 9000
    A<< 0110 9000

Interpretação:

    primeiro 9000 = PIN correto
    segundo 9000 = segredo carregado com sucesso
    01 = existe segredo carregado
    10 = tamanho do segredo: 0x10 = 16 bytes

### 6.5. Confirmar persistência

Retirar e voltar a inserir o cartão.

Depois correr:

    java -jar "$GP" -r "$GP_READER" -d -v \
      --apdu "00A404000A${APPLET_AID}" \
      --apdu "8010000002" \
      --apdu "8050000002"

Resposta esperada:

    A<< 0001 9000
    A<< 0110 9000

Interpretação:

    00 = PIN não está validado na nova sessão
    01 = segredo continua carregado no cartão
    10 = tamanho do segredo: 16 bytes

Este teste demonstra que o segredo fica guardado no cartão de forma persistente, mas que a autenticação por PIN não se mantém ativa entre sessões.

### 6.6. Apagar segredo

Comando:

    java -jar "$GP" -r "$GP_READER" -d -v \
      --apdu "00A404000A${APPLET_AID}" \
      --apdu "802000000401020304" \
      --apdu "80400000" \
      --apdu "8050000002"

Resposta esperada:

    A<< 9000
    A<< 9000
    A<< 0010 9000

Interpretação:

    primeiro 9000 = PIN correto
    segundo 9000 = segredo apagado
    00 = já não há segredo carregado
    10 = tamanho esperado do segredo continua a ser 16 bytes

## 7. Demo de alteração de PIN

### 7.1. Tentar alterar PIN sem autenticação

Comando:

    java -jar "$GP" -r "$GP_READER" -d -v \
      --apdu "00A404000A${APPLET_AID}" \
      --apdu "802200000405060708"

Resposta esperada:

    A<< 6982

Interpretação:

    Não é possível alterar o PIN sem autenticação prévia.

### 7.2. Alterar PIN de 01020304 para 05060708

Comando:

    java -jar "$GP" -r "$GP_READER" -d -v \
      --apdu "00A404000A${APPLET_AID}" \
      --apdu "802000000401020304" \
      --apdu "802200000405060708" \
      --apdu "8010000002"

Resposta esperada:

    A<< 9000
    A<< 9000
    A<< 0000 9000

Interpretação:

    primeiro 9000 = PIN antigo validado
    segundo 9000 = PIN alterado
    0000 = sessão deixou de estar autenticada após alteração do PIN

### 7.3. Confirmar que o PIN antigo já não funciona

Comando:

    java -jar "$GP" -r "$GP_READER" -d -v \
      --apdu "00A404000A${APPLET_AID}" \
      --apdu "802000000401020304"

Resposta esperada:

    A<< 63C2

ou outro valor `63Cx`.

Interpretação:

    PIN errado; x indica o número de tentativas restantes.

### 7.4. Confirmar que o novo PIN funciona

Comando:

    java -jar "$GP" -r "$GP_READER" -d -v \
      --apdu "00A404000A${APPLET_AID}" \
      --apdu "802000000405060708" \
      --apdu "8010000002"

Resposta esperada:

    A<< 9000
    A<< 0100 9000

Interpretação:

    O novo PIN foi aceite e a sessão está autenticada.

### 7.5. Repor o PIN original

Para manter a demo sempre igual, voltar ao PIN original:

    java -jar "$GP" -r "$GP_READER" -d -v \
      --apdu "00A404000A${APPLET_AID}" \
      --apdu "802000000405060708" \
      --apdu "802200000401020304"

Confirmar que o PIN original voltou a funcionar:

    java -jar "$GP" -r "$GP_READER" -d -v \
      --apdu "00A404000A${APPLET_AID}" \
      --apdu "802000000401020304" \
      --apdu "8010000002"

Resposta esperada:

    A<< 9000
    A<< 0100 9000

## 8. Script de testes

Se existir o script:

    keymanager-base/scripts/test_keymanager_base.sh

pode ser executado com:

    cd ~/IST/PIC/keymanager-base
    chmod +x scripts/test_keymanager_base.sh
    ./scripts/test_keymanager_base.sh

Este script corre uma sequência de testes automáticos contra o cartão.

Se o script não estiver atualizado com `-r "$GP_READER"`, pode ser necessário adaptar os comandos manualmente.

## 9. Recompilar e reinstalar a applet

Caso seja necessário recompilar a applet a partir do código-fonte:

    cd ~/IST/PIC/keymanager-base

    export GP="/home/rolo/IST/PIC/GlobalPlatformPro/tool/target/gp.jar"
    export JC_HOME_TOOLS="/home/rolo/IST/PIC/java_card_tools"
    export API_JAR="/home/rolo/IST/PIC/java_card_tools/lib/api_classic-3.0.4.jar"

    export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(which javac)")")")"
    export PATH="$JAVA_HOME/bin:$PATH"

    export PACKAGE_AID_CONV="0xA0:0x00:0x00:0x00:0x62:0x03:0x01:0x0D:0x02"
    export APPLET_AID_CONV="0xA0:0x00:0x00:0x00:0x62:0x03:0x01:0x0D:0x02:0x01"
    export APPLET_AID="A00000006203010D0201"

    rm -rf build/classes build/cap
    mkdir -p build/classes build/cap

    "$JAVA_HOME/bin/javac" \
      -g:none \
      -source 8 \
      -target 8 \
      -bootclasspath "$API_JAR" \
      -classpath "$API_JAR" \
      -d build/classes \
      src/com/pic/keymanager/KeyManagerBaseApplet.java

    "$JC_HOME_TOOLS/bin/converter.sh" \
      -target 3.0.4 \
      -classdir build/classes \
      -d build/cap \
      -applet "$APPLET_AID_CONV" com.pic.keymanager.KeyManagerBaseApplet \
      com.pic.keymanager "$PACKAGE_AID_CONV" 1.0

    export CAP="$(find build/cap -name "*.cap" | head -n 1)"

    "$JC_HOME_TOOLS/bin/capdump.sh" "$CAP"

Se o `capdump` terminar com:

    Process completed with 0 errors

então o CAP foi gerado corretamente.

Para reinstalar no cartão:

    java -jar "$GP" -r "$GP_READER" --uninstall "$CAP" || true
    java -jar "$GP" -r "$GP_READER" --install "$CAP"
    java -jar "$GP" -r "$GP_READER" -l

Confirmar novamente que aparece:

    APP: A00000006203010D0201 (SELECTABLE)

## 10. O que dizer na demo

Resumo simples:

    Temos uma applet própria instalada no cartão.
    A applet já permite autenticação por PIN, alteração de PIN, carregamento de um segredo de 16 bytes, persistência desse segredo, consulta de estado e eliminação do segredo.
    As operações sensíveis exigem autenticação: se tentarmos carregar o segredo sem PIN, o cartão responde 6982.
    O segredo não é exportado pela applet; apenas conseguimos consultar se existe segredo carregado e qual o seu tamanho.
    O próximo passo será transformar este segredo numa chave criptográfica real e acrescentar operações como cifrar/decifrar dentro do cartão.

## 11. Próximos passos previstos

Ainda falta implementar:

- separação entre perfil de administrador e perfil de utilizador;
- transformação do segredo de 16 bytes numa chave criptográfica real;
- operações criptográficas no cartão, por exemplo AES;
- interface em Python no Raspberry Pi;
- testes automatizados mais completos;
- documentação final da arquitetura.
