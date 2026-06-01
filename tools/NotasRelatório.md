# Notas para o relatório - smartcard_tool.py (Implementação Inicial)
## Objetivo
O smartcard_tool.py é o componente do projeto responsável pela interface entre o utilizador (no PC) e o smartcard. A sua função é gerir o ciclo de vida do cartão: configuração, carregamento de ficheiros e limpeza após descarga. Foi desenvolvido em Python pela sua simplicidade, compatibilidade com Windows e Linux, e pela disponibilidade da biblioteca pyscard para comunicação PC/SC.

Para além da utilização manual através de menus, o smartcard_tool.py foi também feito como biblioteca reutilizável. Esta decisão permitiu reutilizar exatamente a mesma lógica de comunicação APDU no Raspberry Pi, evitando duplicação de código entre o lado emissor (PC) e o lado receptor (Raspberry).

## Estrutura Geral
Optou-se por organizar o código em duas camadas distintas:

A primeira é a classe SmartcardClient, que encapsula toda a lógica de comunicação com o cartão. Esta abordagem orientada a objetos foi escolhida deliberadamente para separar a lógica de baixo nível (envio de APDUs, verificação de status words, gestão da ligação) da lógica de alto nível (menus, interação com o utilizador). Desta forma, se no futuro fôr necessário integrar o cliente noutro contexto (por exemplo, num script automatizado de testes), pode fazê-lo sem depender dos menus.

A segunda camada são as funções de menu (menu_configurar, menu_carregar, menu_ver_estado, menu_limpar, menu_principal), que constituem a interface com o utilizador. Cada função de menu trata os seus próprios erros localmente, evitando que uma exceção numa operação quebre todo o programa.

Foi ainda criada uma terceira utilização do mesmo módulo: o executável raspberry_receiver.py. Em vez de duplicar a lógica APDU, este programa importa diretamente a classe SmartcardClient e utiliza-a para automatizar o processo de receção de ficheiros no Raspberry Pi.

## Decisões de engenharia
### Ficheiro de configuração (smartcard_config.json)
Criou-se um ficheiro de configuração externo para evitar que parâmetros como o AID do applet, o tamanho do PIN, o tamanho dos chunks ou o número de retries estivessem hardcoded no código. Esta decisão facilita a adaptação do executável a versões futuras do applet sem necessidade de alterar o código fonte, basta editar o JSON. O ficheiro é carregado uma única vez no arranque através da função load_config(). Se o ficheiro não existir ou estiver corrompido, o programa recorre automaticamente aos valores de DEFAULT_CONFIG (que também podem ser alterados eventualmente), garantindo que o executável funciona sempre.

### Exceções personalizadas
Foram criadas quatro exceções (SmartcardException, PINBlockedException, PINRequiredException, InsufficientMemoryException) em vez de usar exceções genéricas do Python. Esta decisão permite que os menus reajam de forma diferente consoante o tipo de erro. Por exemplo, distinguir entre "PIN errado" e "PIN bloqueado", que têm consequências muito diferentes para o utilizador.

### Retry logic em _send_apdu
A função central de envio de APDUs implementa uma lógica de retry: se o cartão não responder, o comando é repetido até MAX_RETRIES vezes antes de lançar uma exceção. Esta decisão foi tomada para tornar o executável mais robusto a falhas transitórias de comunicação, que podem ocorrer nomeadamente com leitores USB em Linux.

### Gestão do estado pin_validated
O estado de autenticação PIN é mantido em dois sítios: no cartão (internamente, pelo applet Java) e localmente na variável self.pin_validated. Esta duplicação é necessária porque o Python não tem forma de saber automaticamente quando o cartão invalida a sessão. Identificaram-se os momentos em que o applet faz userPin.reset(), nomeadamente em finalize_store, confirm_download, change_pin e deselect, e garantiu-se que pin_validated é posto a False nesses momentos, mantendo consistência entre os dois estados.

### Carregamento de múltiplos ficheiros
A função upload_file foi desenhada para aceitar uma lista de caminhos em vez de um único ficheiro. O init_store é chamado apenas uma vez por sessão (não por ficheiro), o que é obrigatório dado que chamá-lo novamente apagaria os ficheiros já carregados. O loop itera pelos ficheiros, chamando add_file_header, write_chunk em loop, e finalize_file para cada um, e só no final chama finalize_store. A divisão em chunks de 200 bytes deve-se ao limite do APDU standard (260 bytes), sendo necessário deixar margem para o cabeçalho.

### Verificação de status words em _check_sw
Em vez de verificar o SW diretamente nos menus, centralizou-se essa lógica numa única função. No caso 0x63CX (PIN incorreto com X tentativas restantes): o nibble inferior de SW2 codifica o número de tentativas restantes, o que é extraído com a máscara sw2 & 0x0F e apresentado ao utilizador de forma clara.

### SELECT com byte Le=0x7F
O comando SELECT usa CLA=0x00 (ISO standard) e não o CLA=0x80 da aplicação, porque é um comando de gestão do cartão e não um comando do applet. O byte Le=0x7F foi incluído para indicar ao cartão que pode devolver até 127 bytes de resposta no SELECT, o que alguns cartões exigem para responder com SW=9000.

### Download automático de todos os ficheiros
Implementamos a funcionalidade de descarregar todos os ficheiros do cartão de uma só vez. Esta decisão foi tomada porque o cartão representa uma única sessão de transferência completa: o PC carrega todos os ficheiros, o Raspberry Pi descarrega todos os ficheiros, e no final o cartão é limpo através de CONFIRM_DOWNLOAD. 
A função download_all_files utiliza GET_STATUS para descobrir quantos ficheiros existem, obtém os metadados de cada um através de GET_FILE_INFO e depois lê os dados em múltiplos chunks usando READ_CHUNK.

### Leitura chunk-by-chunk através de READ_CHUNK
O protocolo de leitura foi desenhado para utilizar:
P1 = índice do ficheiro
P2 = índice do chunk
O offset real é calculado internamente no applet Java Card através da multiplicação do índice do chunk pelo tamanho fixo READ_CHUNK_SIZE. Esta abordagem simplifica o parsing APDU no cartão e evita enviar offsets de 16 bits completos em cada comando, reduzindo a complexidade do protocolo.

## Logging
Optou-se por registar todas as operações num ficheiro smartcard_tool.log em paralelo com o output do terminal. Durante a fase de desenvolvimento e testes, este log revela-se útil para diagnosticar problemas sem ter de reproduzir os passos manualmente. O nível DEBUG está disponível mas desativado por defeito, podendo ser ativado alterando level=logging.INFO para level=logging.DEBUG para ver os APDUs enviados e recebidos em detalhe.

# raspberry_receiver.py
## Objetivo
O raspberry_receiver.py é o componente responsável pela receção automática dos ficheiros no Raspberry Pi. O programa utiliza a mesma classe SmartcardClient implementada em smartcard_tool.py, funcionando apenas como uma camada de automação específica para o Raspberry.

O seu fluxo de funcionamento é:
1. ligar ao cartão
2. validar o PIN
3. descarregar todos os ficheiros
4. guardar os ficheiros localmente
5. confirmar a descarga através de CONFIRM_DOWNLOAD
6. limpar automaticamente o cartão

## Decisão de reutilização da biblioteca
Em vez de criar uma segunda implementação APDU independente para o Raspberry Pi, optou-se por reutilizar integralmente a classe SmartcardClient. Esta decisão reduz duplicação de código, garante consistência entre o PC e o Raspberry, e facilita manutenção futura. Qualquer alteração ao protocolo APDU fica centralizada num único ficheiro.

## Automatização sem menus
Ao contrário do smartcard_tool.py, o raspberry_receiver.py não possui menus nem interface interativa complexa. Foi desenhado para execução quase automática no Raspberry Pi, minimizando interação do utilizador. Esta abordagem reduz complexidade e torna o fluxo de recepção mais robusto.

# O que falta nesta etapa (para mencionar no relatório)
As opções de definição de chave AES e diretório estão marcadas como [TODO] e correspondem à próxima fase do projeto, que envolverá cifrar os ficheiros antes de os carregar no cartão. A estrutura já está preparada para receber essa funcionalidade no menu_configurar sem alterações significativas ao resto do código.

Do lado do Raspberry Pi, falta ainda implementar a desencriptação automática dos ficheiros após o download. O raspberry_receiver.py já descarrega corretamente todos os ficheiros do cartão e guarda-os localmente, mas atualmente assume que os dados recebidos estão em formato utilizável diretamente. Na próxima fase do projeto, o fluxo previsto será:
1. cifrar os ficheiros no PC antes do upload
2. armazenar apenas dados cifrados no smartcard;
3. descarregar os ficheiros cifrados no Raspberry Pi;
4. desencriptar localmente após o download. 

Esta separação permite que o cartão funcione apenas como meio seguro de transporte, sem necessitar de executar operações criptográficas complexas no Java Card.



# Mudanças após reunião (20/05)
## Motivação da Mudança de Python para Java
Originalmente, a interface com o utilizador foi desenvolvida em Python (`smartcard_tool.py`) pela sua rapidez de prototipagem. No entanto, para garantir uma integração nativa e "direta" com a `SecureFileTransferApplet.java`, optou-se por transpor o executável do PC para Java utilizando a biblioteca **javax.smartcardio**.

## Detalhes e Vantagens
1. Ao utilizar Java em ambos os lados (PC e Smartcard), eliminamos problemas de conversão de tipos de dados (como a gestão de `short` e `byte`), garantindo que a lógica de processamento de ficheiros seja idêntica no Host e na Applet.
2. O executável Java comunica sem intermediários com o método `process(APDU apdu)` da Applet. Cada opção do menu Java aciona diretamente as funções internas programadas no cartão (ex: `INS_ADD_FILE_HEADER` no PC invoca `addFileHeader()` no chip).
3. A transição permitiu manter a estrutura de carregamento múltiplo de ficheiros e a segmentação em chunks de 200 bytes, respeitando os limites de hardware do cartão ACOSJ.
4. O controlo de estado do PIN e o ciclo de vida da sessão (Init -> Header -> Chunks -> Finalize) estão agora blindados por um contrato de comunicação (INS bytes) partilhado entre os dois ficheiros `.java`.

## Impacto na Arquitetura
O sistema passou de um modelo híbrido para um modelo de Sistema Distribuído em Java, onde o `SmartcardTool.java` funciona como o Front-end de gestão e a `SecureFileTransferApplet.java` como o Back-end de armazenamento seguro, conectados pelo protocolo padrão ISO 7816-4.


## Interface Gráfica (GUI) em Java Swing
### Objetivo e Design
Para melhorar a usabilidade e a organização do projeto no lado do PC, foi projetada uma interface gráfica utilizando a biblioteca **Java Swing**. Esta mudança visa substituir a interação via terminal (CLI) por uma janela centralizada que organiza as operações da `SecureFileTransferApplet` em botões e componentes visuais.

A interface inclui:
* **Área de Log:** Substitui o output do terminal, permitindo visualizar o histórico de APDUs e respostas do cartão em tempo real.
* **Diálogos Nativos:** Utilização de `JFileChooser` para a seleção múltipla de ficheiros e `JOptionPane` para a introdução segura do PIN, reduzindo erros de sintaxe por parte do utilizador.
* **Gestão de Estado:** Os botões são habilitados ou desabilitados dinamicamente com base no estado de ligação e autenticação (PIN) do smartcard.

### Estado da Implementação e Validação
Atualmente, a lógica da GUI foi desenvolvida de forma modular, mas **ainda não foi integrada no executável principal do projeto**. Esta decisão foi tomada para que:
1. A estrutura da interface e o fluxo de janelas possam ser **validados e verificados com o professor** antes da implementação final.
2. Seja possível confirmar se o layout e as funcionalidades apresentadas (como o carregamento múltiplo e a limpeza de memória) correspondem às expectativas do projeto.

Após o feedback do professor, a lógica contida na `SmartcardTool.java` será fundida com a `SmartcardGui.java` para criar o executável final consolidado.


## Recetor no Raspberry Pi: Transição para C (raspberry_receiver.c)
### Estratégia de Implementação e Conectividade
Para a fase final de integração no Raspberry Pi, o recetor foi mudado de Python para C, utilizando a biblioteca **PCSC-Lite** (`winscard.h`). Esta mudança é fundamental por dois motivos:
1. **Compatibilidade:** Garante que o código de comunicação com o smartcard possa ser integrado diretamente no software de baixo nível desenvolvido pelos restantes membros do grupo.
2. **Performance e Controlo:** O uso de C permite um maior controlo sobre o protocolo de transmissão APDU e a gestão de buffers de memória no Raspberry Pi.

### Decisões de Engenharia e Segurança
* **Camada de Transporte Mínima:** Seguindo a orientação do professor, a minha componente em C foca-se exclusivamente na segurança e no transporte APDU (Estabelecimento de contexto [registo da aplicação perante o serviço do sistema operativo (pcscd) para obter permissão de acesso ao hardware. Sem este "handle" de contexto, o programa não consegue listar leitores nem comunicar com o smartcard.], Conexão ao leitor, Seleção da Applet e Validação de PIN).
* **Comunicação:** Os comandos de instrução (`INS_VERIFY_PIN`, `INS_READ_CHUNK`, `INS_CONFIRM`) são idênticos aos definidos no emissor Java e na Applet, garantindo que o sistema é agnóstico à linguagem de programação do host.
* **Hierarquia de Operações:** O código garante que nenhuma leitura de dados ocorre sem a validação bem-sucedida do PIN pela Applet, protegendo o material criptográfico contra acessos não autorizados no Raspberry Pi.

### Estado de Integração (Ponto de Situação)
Atualmente, o esqueleto do recetor em C está funcional e validado na sua base de conectividade. A lógica de escrita em disco (`fwrite`) e a gestão de ficheiros no sistema operativo local foram delegadas para a fase de integração com os colegas. 

**Nota:** O comando final de limpeza do cartão (`INS_CONFIRM_DOWNLOAD`) apenas será disparado após a confirmação de que os colegas gravaram os dados com sucesso, fechando o ciclo de vida da transferência segura.


## Implementações Recentes + Encriptação 
### Integração da GUI com a tool Java
Nesta fase, a interface gráfica `SmartcardGui.java` passou a estar ligada diretamente à lógica de `SmartcardTool.java`. Os botões da janela já executam as operações reais do cartão: ligação ao leitor, validação do PIN, carregamento múltiplo de ficheiros e limpeza do cartão. A área de log foi mantida para mostrar ao utilizador o estado das operações sem depender da consola.

### Encriptação dos ficheiros no lado do PC
O fluxo de carregamento passou a incluir encriptação antes do envio para o smartcard. O ficheiro é lido em memória, é gerado um IV aleatório de 16 bytes por ficheiro, e os dados são cifrados com `AES-128-CBC` e padding `PKCS#7`. O payload enviado para o cartão passa a ter o formato `[IV][dados cifrados]`, permitindo que o Raspberry Pi faça a descodificação posterior com a mesma chave.

### Gestão da chave AES na fase de setup
Para evitar uma chave hardcoded no código, foi adicionada uma fase de setup no arranque da tool. A `SmartcardTool` cria automaticamente uma chave AES de 16 bytes na primeira execução, guarda-a num ficheiro partilhável (`pic_aes_key.hex`) e reutiliza-a nas execuções seguintes. Esse ficheiro pode depois ser lido no Raspberry Pi, garantindo que ambos os lados usam exatamente a mesma chave.

### Ajustes técnicos feitos nesta etapa
Também foi adaptada a tool para funcionar sem interação de terminal quando usada pela GUI, com tratamento de erros por APDU e verificação de status words no upload e na limpeza do cartão. A solução foi validada com compilação local dos ficheiros Java principais.