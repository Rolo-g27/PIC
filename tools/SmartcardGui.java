import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.Arrays;

/*
 * SmartcardGui.java
 * Interface Gráfica para o projeto PIC - Gestão de Smartcard.
 * 
 * COMO EXECUTAR:
 * 1. Garantir que o SmartcardTool.java está na mesma pasta.
 * 2. Compilar: javac SmartcardTool.java SmartcardGui.java
 * 3. Executar: java SmartcardGui (ou sudo java SmartcardGui em Linux)
 * 
 * NOTA: Este ficheiro utiliza Java Swing e a biblioteca javax.smartcardio.
 */

public class SmartcardGui extends JFrame {
    // Reutiliza a lógica do SmartcardTool
    private SmartcardTool core; 
    private JTextArea logArea;
    private JLabel statusLabel;
    private JButton btnConnect;
    private JButton btnPin;
    private JButton btnUpload;
    private JButton btnClear;

    public SmartcardGui() { // Inicializa a interface gráfica e a lógica do cartão
        core = new SmartcardTool();
        setupLayout();
    }

    private void setupLayout() {
        setTitle("PIC - Secure File Transfer");
        setSize(500, 400);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout(10, 10)); 

        // Painel Superior: Estado e Conexão
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        statusLabel = new JLabel("Estado: Desconectado");
        btnConnect = new JButton("Ligar ao Cartão");
        btnConnect.addActionListener(e -> handleConnect());
        topPanel.add(btnConnect);
        topPanel.add(statusLabel);

        // JTextArea para mostrar mensagens de estado e logs de operações, 
        // com scroll automático para facilitar a leitura de múltiplas mensagens
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setBackground(new Color(240, 240, 240));
        JScrollPane scroll = new JScrollPane(logArea);

        // Painel Inferior: Ações (Botões organizados)
        JPanel botPanel = new JPanel(new GridLayout(1, 3, 5, 5));
        btnPin = new JButton("Validar PIN");
        btnUpload = new JButton("Carregar Ficheiros");
        btnClear = new JButton("Limpar Cartão");

        btnPin.addActionListener(e -> handlePin());
        btnUpload.addActionListener(e -> handleUpload());
        btnClear.addActionListener(e -> handleWipe());

        botPanel.add(btnPin);
        botPanel.add(btnUpload);
        botPanel.add(btnClear);
        setActionButtonsEnabled(false);

        // Adicionar ao JFrame com espaçamento e organização clara entre os componentes
        add(topPanel, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(botPanel, BorderLayout.SOUTH);

        setLocationRelativeTo(null); // Centralizar na tela
    }

    private void handleConnect() { // Tenta conectar ao cartão usando a lógica do SmartcardTool e atualiza o estado na interface
        log("A ligar ao leitor e ao applet...");
        if (core.connect(false)) {
            statusLabel.setText("Estado: Conectado (Applet OK)");
            setActionButtonsEnabled(true);
            log("Conectado ao leitor e Applet selecionada.");
        } else {
            statusLabel.setText("Estado: Desconectado");
            log("Erro ao conectar.");
        }
    }

    private void handlePin() { // Abre um popup para pedir o PIN
        String pin = JOptionPane.showInputDialog(this, "Introduza o PIN (Hex):");
        if (pin != null && core.verifyPinFromGui(pin)) {
            log("PIN Validado com sucesso.");
        } else {
            log("Falha na validação do PIN.");
        }
    }

    private void handleUpload() { // JFileChooser permite escolher ficheiros de forma visual e organizada para upload, 
    // e depois chama a função de upload do SmartcardTool com os caminhos dos ficheiros selecionados
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        int returnVal = chooser.showOpenDialog(this);
        
        if(returnVal == JFileChooser.APPROVE_OPTION) {
            File[] files = chooser.getSelectedFiles();
            String[] selected = Arrays.stream(files).map(File::getAbsolutePath).toArray(String[]::new);
            String paths = String.join(",", selected);

            log("A carregar " + files.length + " ficheiros...");
            if (core.uploadFiles(paths)) {
                log("Operação de carga concluída.");
            } else {
                log("Falha na operação de carga.");
            }
        }
    }

    private void handleWipe() {
        int res = JOptionPane.showConfirmDialog(this, "Deseja apagar todos os dados?");
        if(res == JOptionPane.YES_OPTION) {
            if (core.wipeCard()) {
                log("Cartão limpo.");
            } else {
                log("Falha ao limpar o cartão.");
            }
        }
    }

    private void log(String msg) { // Adiciona mensagens ao JTextArea para feedback visual das operações realizadas, 
    // facilitando o acompanhamento do estado e resultados das ações do usuário
        logArea.append("> " + msg + "\n");
    }

    private void setActionButtonsEnabled(boolean enabled) {
        btnPin.setEnabled(enabled);
        btnUpload.setEnabled(enabled);
        btnClear.setEnabled(enabled);
    }

    public static void main(String[] args) { // Inicia a interface gráfica
        SwingUtilities.invokeLater(() -> new SmartcardGui().setVisible(true));
    }
}
