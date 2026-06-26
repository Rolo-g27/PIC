import javax.smartcardio.*;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Scanner;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.io.IOException;

/**
 * SmartcardTool.java
 * Versão Java da ferramenta de gestão para o projeto PIC.
 * Comunica com SecureFileTransferApplet via javax.smartcardio.
 */
public class SmartcardTool {

    // Configurações Gerais (podem ser alteradas conforme necessário)
    private static final String APP_AID = "A00000006203010D0301"; // AID único do applet 
    private static final byte CLA = (byte) 0x80; // Define a classe do APDU
    private static final int PIN_SIZE = 4; // Tamanho do PIN em bytes
    private static final int CHUNK_SIZE = 200; // Tamanho máximo de cada bloco de dados enviados 
    private static final int MAX_NAME_SIZE = 16; // Limita nome do ficheiro a 16 caracteres 
    private static final int AES_KEY_SIZE = 16; // Tamanho da chave AES em bytes
    private static final int AES_IV_SIZE = 16; // Tamanho do IV para AES (16 bytes para AES-128)
    private static final Path AES_KEY_FILE = Paths.get(System.getProperty("user.dir"), "pic_aes_key.hex"); 
    // Ficheiro local para armazenar a chave AES, para persistência entre execuções e compartilhamento entre PC e Raspberry

    // INS Bytes (Coincidem com SecureFileTransferApplet.java) + decide que função usar
    private static final byte INS_VERIFY_PIN       = (byte) 0x20;
    private static final byte INS_INIT_STORE       = (byte) 0x30;
    private static final byte INS_ADD_FILE_HEADER  = (byte) 0x31;
    private static final byte INS_WRITE_CHUNK      = (byte) 0x32;
    private static final byte INS_FINALIZE_FILE    = (byte) 0x33;
    private static final byte INS_FINALIZE_STORE   = (byte) 0x34;
    private static final byte INS_WIPE_CARD        = (byte) 0x70;

    private Card card; // Representa o cartão conectado, usado para enviar comandos e receber respostas
    private CardChannel channel; // Canal de comunicação com o cartão
    private boolean pinValidated = false; // Estado local do PIN 
    private Scanner scanner = new Scanner(System.in); // Para leitura de input do usuário
    private boolean connected = false; // Inicialmente falso, é atualizado para true após uma conexão bem-sucedida e seleção da applet
    private final SecureRandom secureRandom = new SecureRandom(); // Para gerar chaves AES e IVs de forma segura
    private SecretKeySpec cachedAesKey; // Cache da chave AES para evitar recarregamentos desnecessários durante a execução do programa

    public SmartcardTool() { // Construtor que prepara o ambiente, incluindo a geração ou carregamento da chave AES necessária 
    // para o processo de upload de ficheiros, garantindo que a ferramenta esteja pronta para uso imediato após a inicialização
        try {
            loadAesKey();
        } catch (Exception e) {
            System.out.println("[ERRO] Falha ao preparar a chave AES: " + e.getMessage());
        }
    }

    public void disconnect() { // Desconecta do cartão, garantindo que os recursos sejam libertados corretamente,  
    // e atualiza o estado de conexão para refletir a desconexão
        try {
            if (card != null) card.disconnect(false);
        } catch (CardException e) {
        }
        connected = false;
    }

    public boolean connect() {
        return connect(true);
    }

    public boolean connect(boolean interactive) {
        try {
            TerminalFactory factory = TerminalFactory.getDefault(); // Inicia a infraestrutura de SmartCard para procurar leitores PC/SC instalados no sistema
            List<CardTerminal> terminals = factory.terminals().list(); // Lista os leitores disponíveis

            if (terminals.isEmpty()) {
                System.out.println("[ERRO] Nenhum leitor encontrado.");
                return false;
            }

            CardTerminal terminal = terminals.get(0); // Define o primeiro leitor como padrão
            if (interactive && terminals.size() > 1) { // Se houver mais de um leitor, permite ao usuário escolher
                System.out.println("Leitores disponíveis:");
                for (int i = 0; i < terminals.size(); i++) {
                    System.out.println(i + ". " + terminals.get(i));
                }
                System.out.print("Escolha o leitor: ");
                int idx = Integer.parseInt(scanner.nextLine());
                terminal = terminals.get(idx);
            }

            card = terminal.connect("*"); // Conecta ao cartão usando o protocolo mais adequado (T=0 ou T=1)
            channel = card.getBasicChannel(); // Abre o canal de comunicação básico para enviar APDUs
            System.out.println("[OK] Ligado a: " + terminal.getName());
            connected = true;

            return selectApplet(); // Chama imediatamente a função lógica que envia o comando SELECT para ativar a sua SecureFileTransferApplet no cartão
        } catch (Exception e) {
            System.out.println("[ERRO] Erro na conexão: " + e.getMessage());
            connected = false;
            return false;
        }
    }
 
    private boolean selectApplet() { // Envia o comando SELECT para o cartão, usando o AID definido, para ativar a applet específica que implementamos. 
    // Se a resposta for 0x9000, a applet foi selecionada com sucesso e podemos prosseguir com as operações. 
    // Caso contrário, exibe o código de status retornado para diagnóstico.
        try {
            byte[] aid = hexStringToByteArray(APP_AID);
            // SELECT APDU: CLA=00, INS=A4, P1=04, P2=00, Data=AID
            CommandAPDU select = new CommandAPDU(0x00, 0xA4, 0x04, 0x00, aid, 0x7F); 
            ResponseAPDU response = channel.transmit(select); 

            if (response.getSW() == 0x9000) {
                System.out.println("[OK] Applet selecionado (" + APP_AID + ")");
                return true;
            }
            System.out.printf("[ERRO] Falha ao selecionar applet: %04X\n", response.getSW());
            connected = false;
            return false;
        } catch (CardException e) {
            connected = false;
            return false;
        }
    }

    public boolean verifyPin() { // Solicita ao usuário que insira o PIN em formato hexadecimal, 
    // converte para bytes e envia um comando VERIFY_PIN para o cartão.
        System.out.print("PIN (4 dígitos hex, ex: 01020304): ");
        String pinHex = scanner.nextLine().trim();
        return verifyPinFromGui(pinHex);
    }

    public boolean verifyPinFromGui(String pinHex) { // Versão da função verifyPin que recebe o PIN como argumento, para ser usada pela interface gráfica, 
    // permitindo que o processo de validação do PIN seja acionado tanto pelo menu de texto quanto pela interface gráfica, 
    // mantendo a lógica de validação centralizada e consistente
        if (pinHex.length() != PIN_SIZE * 2) {
            System.out.println("[ERRO] PIN inválido.");
            return false;
        }

        try { 
            byte[] pin = hexStringToByteArray(pinHex);
            CommandAPDU adpu = new CommandAPDU(CLA, INS_VERIFY_PIN, 0x00, 0x00, pin);
            ResponseAPDU resp = channel.transmit(adpu);

            if (resp.getSW() == 0x9000) {
                pinValidated = true;
                System.out.println("[OK] PIN validado.");
                return true;
            }
            checkSW(resp.getSW(), "VERIFY_PIN");
            return false;
        } catch (Exception e) {
            System.out.println("[ERRO] " + e.getMessage());
            return false;
        }
    }

    public boolean uploadFiles(String input) { // Recebe uma string de caminhos de ficheiros separados por vírgula, 
    // lê cada ficheiro, e envia para o cartão em blocos usando os comandos definidos.
        if (!pinValidated) { // Verifica se o PIN foi validado antes de permitir o upload
            System.out.println("[ERRO] Valide o PIN primeiro.");
            return false;
        }

        try {
            String[] paths = input.split(",");
            // 1. Init Store - Informa o cartão que vamos iniciar um processo de upload, 
            // para que ele possa preparar a memória e resetar estados internos se necessário
            checkSW(channel.transmit(new CommandAPDU(CLA, INS_INIT_STORE, 0, 0)).getSW(), "INIT_STORE");

            SecretKeySpec aesKey = loadAesKey(); // Carrega a chave AES para criptografar os ficheiros antes de enviar, 
            // garantindo que os dados sejam protegidos durante a transferência para o cartão

            for (String pathStr : paths) {
                byte[] data = Files.readAllBytes(Paths.get(pathStr.trim()));
                String name = Paths.get(pathStr.trim()).getFileName().toString();
                if (name.length() > MAX_NAME_SIZE) name = name.substring(0, MAX_NAME_SIZE);

                byte[] encryptedFile = encryptFile(data, aesKey); // Criptografa o conteúdo do ficheiro usando AES-CBC com a chave carregada, 
                // para garantir a confidencialidade dos dados armazenados no cartão

                // 2. Header: [len_nome][nome][tamanho_short] - Envia um bloco inicial com o nome do ficheiro e o seu tamanho total, 
                // para que o cartão possa criar a estrutura de armazenamento adequada
                byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
                byte[] header = new byte[1 + nameBytes.length + 2];
                header[0] = (byte) nameBytes.length;
                System.arraycopy(nameBytes, 0, header, 1, nameBytes.length);
                header[header.length - 2] = (byte) (encryptedFile.length >> 8);
                header[header.length - 1] = (byte) (encryptedFile.length & 0xFF);

                checkSW(channel.transmit(new CommandAPDU(CLA, INS_ADD_FILE_HEADER, 0, 0, header)).getSW(), "ADD_FILE_HEADER");

                // 3. Chunks - Envia o conteúdo do ficheiro em blocos de tamanho definido por CHUNK_SIZE, 
                // para evitar exceder os limites de APDU e permitir que o cartão processe os dados em partes 
                int offset = 0;
                while (offset < encryptedFile.length) {
                    int len = Math.min(CHUNK_SIZE, encryptedFile.length - offset);
                    byte[] chunk = Arrays.copyOfRange(encryptedFile, offset, offset + len);
                    checkSW(channel.transmit(new CommandAPDU(CLA, INS_WRITE_CHUNK, 0, 0, chunk)).getSW(), "WRITE_CHUNK");
                    offset += len;
                    System.out.print(" Carregando " + name + ": " + offset + "/" + encryptedFile.length + "\r");
                }
                System.out.println("\n[OK] " + name + " enviado.");
                
                // 4. Finalize File - Após enviar todos os blocos de um ficheiro, envia um comando para indicar que o upload daquele ficheiro específico 
                // foi concluído, para que o cartão possa realizar validações finais, como verificar o tamanho total recebido
                checkSW(channel.transmit(new CommandAPDU(CLA, INS_FINALIZE_FILE, 0, 0)).getSW(), "FINALIZE_FILE");
            }

            // 5. Finalize Store - Depois de enviar todos os ficheiros, envia um comando final para indicar que o processo de upload está completo, 
            // para que o cartão possa realizar validações finais, como verificar o número total de ficheiros e o espaço utilizado, 
            // e então libertar recursos ou atualizar estados internos conforme necessário
            checkSW(channel.transmit(new CommandAPDU(CLA, INS_FINALIZE_STORE, 0, 0)).getSW(), "FINALIZE_STORE");
            pinValidated = false; // Reset local como no Python
            System.out.println("[OK] Sessão finalizada. PIN resetado.");
            return true;

        } catch (Exception e) {
            System.out.println("[ERRO] Falha no upload: " + e.getMessage());
            return false;
        }
    }

    private SecretKeySpec loadAesKey() throws Exception { // Carrega a chave AES de uma fonte configurada (propriedade do sistema, variável de ambiente ou ficheiro), 
    // e prepara para uso em operações de criptografia, garantindo que a chave seja do tamanho correto e esteja disponível para as funções de upload de ficheiros
        if (cachedAesKey != null) {
            return cachedAesKey;
        }
        
        String keyHex = System.getProperty("pic.aes.key");  
        // Primeiro tenta obter a chave de uma propriedade do sistema, 
        // permitindo que seja passada como argumento na linha de comando com -Dpic.aes.key=<hex16bytes>
        if (keyHex == null || keyHex.isBlank()) {
            keyHex = System.getenv("PIC_AES_KEY_HEX");
        }

        if (keyHex == null || keyHex.isBlank()) {
            keyHex = loadOrCreateKeyFile(); // Se não estiver definida em propriedades ou variáveis de ambiente, 
            // tenta carregar num ficheiro local, ou criar um novo se o ficheiro não existir
        }

        if (keyHex == null || keyHex.isBlank()) {
            throw new Exception("Chave AES não definida. Use -Dpic.aes.key=<hex16bytes>, a variável PIC_AES_KEY_HEX ou o ficheiro " + AES_KEY_FILE);
        }

        byte[] keyBytes = hexStringToByteArray(keyHex.trim());
        if (keyBytes.length != AES_KEY_SIZE) {
            throw new Exception("A chave AES deve ter 16 bytes (32 caracteres hex).");
        }

        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES"); // Prepara o objeto de chave AES para uso em criptografia
        Arrays.fill(keyBytes, (byte) 0);
        cachedAesKey = keySpec;
        return cachedAesKey;
    }

    public String getAesKeyHex() throws Exception { // Função auxiliar para obter a chave AES em formato hexadecimal, útil para exibir ou compartilhar a chave de forma legível, 
    // especialmente para configurar a mesma chave no Raspberry Pi, garantindo que o processo de configuração seja mais fácil e menos propenso a erros de formatação
        byte[] keyBytes = loadAesKey().getEncoded();
        StringBuilder builder = new StringBuilder(keyBytes.length * 2);
        for (byte value : keyBytes) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    public String getAesKeyFilePath() { // Retorna o caminho absoluto do ficheiro onde a chave AES é armazenada, 
    // para que o usuário possa localizar facilmente o ficheiro e copiá-lo para o Raspberry Pi, 
    // garantindo que a mesma chave seja usada em ambos os lados para a criptografia dos ficheiros
        return AES_KEY_FILE.toAbsolutePath().toString();
    }

    private String loadOrCreateKeyFile() throws Exception { // Verifica se o ficheiro de chave AES existe. 
    // Se existir, lê e retorna a chave. Se não existir, gera uma nova chave, salva no ficheiro e retorna a chave gerada, 
    // garantindo que a chave seja persistente entre execuções e possa ser compartilhada entre o PC e o Raspberry Pi de forma segura
        if (Files.exists(AES_KEY_FILE)) {
            return Files.readString(AES_KEY_FILE, StandardCharsets.US_ASCII).trim();
        }

        byte[] keyBytes = new byte[AES_KEY_SIZE];
        secureRandom.nextBytes(keyBytes);

        StringBuilder builder = new StringBuilder(AES_KEY_SIZE * 2);
        for (byte value : keyBytes) {
            builder.append(String.format("%02x", value));
        }

        Files.writeString(
            AES_KEY_FILE,
            builder.toString(),
            StandardCharsets.US_ASCII,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        );

        try {
            Files.setPosixFilePermissions(AES_KEY_FILE, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // ignore on platforms without POSIX
        }

        System.out.println("[SETUP] Nova chave AES criada em: " + AES_KEY_FILE.toAbsolutePath());
        System.out.println("[SETUP] Copia este ficheiro para o Raspberry para usar a mesma chave.");
        Arrays.fill(keyBytes, (byte)0);
        return builder.toString();
    }
    
    private byte[] encryptFile(byte[] plainData, SecretKeySpec aesKey) throws Exception { // Criptografa os dados do ficheiro usando AES-CBC com a chave fornecida,
    // gerando um IV aleatório para cada ficheiro, e retornando um payload que inclui o IV seguido dos dados criptografados, 
    // garantindo a confidencialidade dos dados armazenados no cartão
        byte[] iv = new byte[AES_IV_SIZE];
        secureRandom.nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, new IvParameterSpec(iv));
        byte[] encrypted = cipher.doFinal(plainData);

        byte[] payload = new byte[AES_IV_SIZE + encrypted.length];
        System.arraycopy(iv, 0, payload, 0, AES_IV_SIZE);
        System.arraycopy(encrypted, 0, payload, AES_IV_SIZE, encrypted.length);
        return payload;
    }

    public boolean wipeCard() { // Envia um comando para limpar o cartão, mas somente se o PIN tiver sido validado, para evitar que alguém apague os dados do cartão sem autorização. 
    // Se o comando for bem-sucedido, reseta o estado local do PIN para false, 
    // exigindo que o user valide o PIN novamente para realizar outras operações, 
    // garantindo uma camada adicional de segurança após a limpeza do cartão.
        if (!pinValidated) {
            System.out.println("[ERRO] Valide o PIN primeiro.");
            return false;
        }

        try {
            checkSW(channel.transmit(new CommandAPDU(CLA, INS_WIPE_CARD, 0, 0)).getSW(), "WIPE_CARD");
            pinValidated = false;
            System.out.println("[OK] Cartão limpo.");
            return true;
        } catch (Exception e) {
            System.out.println("[ERRO] Falha ao limpar o cartão: " + e.getMessage());
            return false;
        }
    }

    public boolean isConnected() {
        return connected;
    }

    // Funções Auxiliares e Menu

    private void checkSW(int sw, String context) throws Exception { // Interpreta os códigos de status retornados pelo cartão após cada comando, 
        // para fornecer mensagens de erro mais específicas, especialmente relacionadas ao estado do PIN ou outros erros comuns
        if (sw == 0x9000) return;
        if (sw == 0x6982) throw new Exception("PIN necessário (" + context + ")");
        if (sw == 0x6983) throw new Exception("PIN bloqueado!");
        if ((sw & 0xFFF0) == 0x63C0) throw new Exception("PIN incorreto! Tentativas: " + (sw & 0x0F));
        throw new Exception(String.format("Erro no cartão (%s): %04X", context, sw));
    }

    private byte[] hexStringToByteArray(String s) { // Converte uma string hexadecimal em um array de bytes correspondente
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                                 + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    public void run() { // Função principal que gerencia o fluxo do programa, apresentando um menu para o user e 
    // chamando as funções correspondentes com base na escolha do user
        if (!connect()) return;

        while (true) {
            System.out.println("\n--- PIC: Gestão de Smartcard (Java) ---");
            System.out.println("1. Validar PIN");
            System.out.println("2. Carregar Ficheiros");
            System.out.println("3. Limpar Cartão");
            System.out.println("0. Sair");
            System.out.print("Opção: ");
            
            String op = scanner.nextLine();
            if (op.equals("1")) verifyPin();
            else if (op.equals("2")) {
                System.out.print("Caminhos (separados por vírgula): ");
                uploadFiles(scanner.nextLine());
            }
            else if (op.equals("3")) {
                wipeCard();
            }
            else if (op.equals("0")) break;
        }
    }

    public static void main(String[] args) {
        new SmartcardTool().run();
    }
}