import javax.smartcardio.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Scanner;
import java.util.Arrays;

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

    // INS Bytes (Coincidem com SecureFileTransferApplet.java) + decide que função usar
    private static final byte INS_GET_STATUS       = (byte) 0x10; 
    private static final byte INS_VERIFY_PIN       = (byte) 0x20;
    private static final byte INS_CHANGE_PIN       = (byte) 0x22;
    private static final byte INS_INIT_STORE       = (byte) 0x30;
    private static final byte INS_ADD_FILE_HEADER  = (byte) 0x31;
    private static final byte INS_WRITE_CHUNK      = (byte) 0x32;
    private static final byte INS_FINALIZE_FILE    = (byte) 0x33;
    private static final byte INS_FINALIZE_STORE   = (byte) 0x34;
    private static final byte INS_GET_FILE_INFO    = (byte) 0x40;
    private static final byte INS_CONFIRM_DOWNLOAD = (byte) 0x60;

    private CardChannel channel; // Canal de comunicação com o cartão
    private boolean pinValidated = false; // Estado local do PIN 
    private Scanner scanner = new Scanner(System.in); // Para leitura de input do usuário

    public boolean connect() {
        try {
            TerminalFactory factory = TerminalFactory.getDefault(); // Inicia a infraestrutura de SmartCard para procurar leitores PC/SC instalados no sistema
            List<CardTerminal> terminals = factory.terminals().list(); // Lista os leitores disponíveis

            if (terminals.isEmpty()) {
                System.out.println("[ERRO] Nenhum leitor encontrado.");
                return false;
            }

            CardTerminal terminal = terminals.get(0); // Define o primeiro leitor como padrão
            if (terminals.size() > 1) { // Se houver mais de um leitor, permite ao usuário escolher
                System.out.println("Leitores disponíveis:");
                for (int i = 0; i < terminals.size(); i++) {
                    System.out.println(i + ". " + terminals.get(i));
                }
                System.out.print("Escolha o leitor: ");
                int idx = Integer.parseInt(scanner.nextLine());
                terminal = terminals.get(idx);
            }

            Card card = terminal.connect("*"); // Conecta ao cartão usando o protocolo mais adequado (T=0 ou T=1)
            channel = card.getBasicChannel(); // Abre o canal de comunicação básico para enviar APDUs
            System.out.println("[OK] Ligado a: " + terminal.getName());

            return selectApplet(); // Chama imediatamente a função lógica que envia o comando SELECT para ativar a sua SecureFileTransferApplet no cartão
        } catch (Exception e) {
            System.out.println("[ERRO] Erro na conexão: " + e.getMessage());
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
            return false;
        } catch (CardException e) {
            return false;
        }
    }

    public boolean verifyPin() { // Solicita ao usuário que insira o PIN em formato hexadecimal, 
    // converte para bytes e envia um comando VERIFY_PIN para o cartão.
        System.out.print("PIN (4 dígitos hex, ex: 01020304): ");
        String pinHex = scanner.nextLine().trim();
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

    public void uploadFiles(String input) { // Recebe uma string de caminhos de ficheiros separados por vírgula, 
    // lê cada ficheiro, e envia para o cartão em blocos usando os comandos definidos.
        if (!pinValidated) { // Verifica se o PIN foi validado antes de permitir o upload
            System.out.println("[ERRO] Valide o PIN primeiro.");
            return;
        }

        try {
            String[] paths = input.split(",");
            // 1. Init Store - Informa o cartão que vamos iniciar um processo de upload, 
            // para que ele possa preparar a memória e resetar estados internos se necessário
            channel.transmit(new CommandAPDU(CLA, INS_INIT_STORE, 0, 0));

            for (String pathStr : paths) {
                byte[] data = Files.readAllBytes(Paths.get(pathStr.trim()));
                String name = Paths.get(pathStr.trim()).getFileName().toString();
                if (name.length() > MAX_NAME_SIZE) name = name.substring(0, MAX_NAME_SIZE);

                // 2. Header: [len_nome][nome][tamanho_short] - Envia um bloco inicial com o nome do ficheiro e o seu tamanho total, 
                // para que o cartão possa criar a estrutura de armazenamento adequada
                byte[] nameBytes = name.getBytes("ASCII");
                byte[] header = new byte[1 + nameBytes.length + 2];
                header[0] = (byte) nameBytes.length;
                System.arraycopy(nameBytes, 0, header, 1, nameBytes.length);
                header[header.length - 2] = (byte) (data.length >> 8);
                header[header.length - 1] = (byte) (data.length & 0xFF);

                channel.transmit(new CommandAPDU(CLA, INS_ADD_FILE_HEADER, 0, 0, header));

                // 3. Chunks - Envia o conteúdo do ficheiro em blocos de tamanho definido por CHUNK_SIZE, 
                // para evitar exceder os limites de APDU e permitir que o cartão processe os dados em partes 
                int offset = 0;
                while (offset < data.length) {
                    int len = Math.min(CHUNK_SIZE, data.length - offset);
                    byte[] chunk = Arrays.copyOfRange(data, offset, offset + len);
                    channel.transmit(new CommandAPDU(CLA, INS_WRITE_CHUNK, 0, 0, chunk));
                    offset += len;
                    System.out.print(" Carregando " + name + ": " + offset + "/" + data.length + "\r");
                }
                System.out.println("\n[OK] " + name + " enviado.");
                
                // 4. Finalize File - Após enviar todos os blocos de um ficheiro, envia um comando para indicar que o upload daquele ficheiro específico 
                // foi concluído, para que o cartão possa realizar validações finais, como verificar o tamanho total recebido
                channel.transmit(new CommandAPDU(CLA, INS_FINALIZE_FILE, 0, 0));
            }

            // 5. Finalize Store - Depois de enviar todos os ficheiros, envia um comando final para indicar que o processo de upload está completo, 
            // para que o cartão possa realizar validações finais, como verificar o número total de ficheiros e o espaço utilizado, 
            // e então libertar recursos ou atualizar estados internos conforme necessário
            channel.transmit(new CommandAPDU(CLA, INS_FINALIZE_STORE, 0, 0));
            pinValidated = false; // Reset local como no Python
            System.out.println("[OK] Sessão finalizada. PIN resetado.");

        } catch (Exception e) {
            System.out.println("[ERRO] Falha no upload: " + e.getMessage());
        }
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
            System.out.println("0. Sair");
            System.out.print("Opção: ");
            
            String op = scanner.nextLine();
            if (op.equals("1")) verifyPin();
            else if (op.equals("2")) {
                System.out.print("Caminhos (separados por vírgula): ");
                uploadFiles(scanner.nextLine());
            }
            else if (op.equals("0")) break;
        }
    }

    public static void main(String[] args) {
        new SmartcardTool().run();
    }
}