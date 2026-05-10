"""
smartcard_tool.py
Ferramenta de gestão de smartcard para o projeto PIC.
Comunicação com SecureFileTransferApplet via APDU.

Uso: 
    python smartcard_tool.py

Dependências:
    pyscard (instalar via pip: pip install pyscard)
"""

import logging
import json
import os
from pathlib import Path
from typing import Optional, Tuple, List
from smartcard.System import readers
from smartcard.Exceptions import CardConnectionException, NoCardException, CardRequestTimeoutException
from smartcard import util

# Configuração de logging
# O logging regista informações detalhadas sobre as operações, erros e status do cartão.
# Escreve em dois sítios: no terminal e num ficheiro 'smartcard_tool.log' para análise/debug posterior.

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler('smartcard_tool.log'),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger(__name__)

# Configuração
# O ficheiro smartcard_config.json permite alterar parâmetros sem tocar no código. 
# Se o ficheiro não existir ou tiver erros, os defaults (DEFAULT_CONFIG) são usados.    

CONFIG_FILE = Path(__file__).parent / "smartcard_config.json"
DEFAULT_CONFIG = {
    "app_aid": "A00000006203010D0301",  # Identificador único do applet no cartão (AID)
    "cla": 0x80,    # Byte CLA: identifica comandos da nossa aplicação
    "pin_size": 4,  # Tamanho do PIN em bytes 
    "chunk_size": 200,  # Tamanho máximo de cada bloco de dados enviado (bytes)
    "max_name_size": 16,    # Tamanho máximo do nome de ficheiro (bytes)
    "max_retries": 3,   # Nº de tentativas antes de desistir num erro de comunicação
    "timeout_seconds": 5    # Timeout de ligação 
}

def load_config() -> dict:
    """Carrega configuração do ficheiro JSON ou usa defaults."""
    if CONFIG_FILE.exists():
        try:
            with open(CONFIG_FILE) as f:
                config = json.load(f)
                logger.info("Configuração carregada de %s", CONFIG_FILE)
                return config
        except Exception as e:
            logger.warning("Erro ao ler configuração: %s. Usando defaults.", e)
    return DEFAULT_CONFIG

config = load_config()  # Carrega a configuração uma vez ao iniciar o programa

APP_AID = config["app_aid"]
CLA = config["cla"]
PIN_SIZE = config["pin_size"]
CHUNK_SIZE = config["chunk_size"]
MAX_NAME_SIZE = config["max_name_size"]
MAX_RETRIES = config["max_retries"]
TIMEOUT_SECONDS = config["timeout_seconds"]

# INS bytes
# Cada INS identifica um comando específico dentro do applet.
# Estes valores têm de coincidir exatamente com os definidos em SecureFileTransferApplet.java.

INS_GET_STATUS       = 0x10
INS_GET_VERSION      = 0x11
INS_VERIFY_PIN       = 0x20
INS_CHANGE_PIN       = 0x22
INS_INIT_STORE       = 0x30
INS_ADD_FILE_HEADER  = 0x31
INS_WRITE_CHUNK      = 0x32
INS_FINALIZE_FILE    = 0x33
INS_FINALIZE_STORE   = 0x34
INS_GET_FILE_INFO    = 0x40
INS_READ_CHUNK       = 0x50
INS_CONFIRM_DOWNLOAD = 0x60
INS_WIPE_CARD        = 0x70

# Status Words
# O cartão responde sempre com dois bytes de status (SW1, SW2) no final de cada resposta.
# SW 9000 significa sucesso. Os outros indicam erros específicos.

SW_OK               = (0x90, 0x00)  # Sucesso
SW_PIN_REQUIRED     = (0x69, 0x82)  # Operação requer PIN validado
SW_PIN_BLOCKED      = (0x69, 0x83)  # PIN bloqueado (3 tentativas falhadas)

# Mapeamento do byte de estado do cartão para texto legível
STATE_LABELS = {
    0x00: "VAZIO",  # Cartão sem ficheiros
    0x01: "A CARREGAR",     # Sessão de carregamento em curso
    0x02: "PRONTO",     # Ficheiros carregados, pronto para transporte
}

# Exceções personalizadas
# Criamos as nossas próprias exceções para distinguir diferentes tipos de erros
# e tratá-los de forma adequada no menu.

class SmartcardException(Exception):
    """Exceção base para qualquer erro de smartcard."""
    pass

class PINBlockedException(SmartcardException):
    """PIN está bloqueado (tentativas esgotadas)."""
    pass

class PINRequiredException(SmartcardException):
    """PIN necessário mas não foi validado."""
    pass

class InsufficientMemoryException(SmartcardException):
    """Memória insuficiente no cartão para armazenar ficheiro."""
    pass

# Cliente Smartcard

class SmartcardClient:
    """
    Cliente para comunicação com o SecureFileTransferApplet.
    Encapsula toda a lógica de comunicação APDU, mantendo o estado da ligação e da autenticação PIN.
    """

    def __init__(self):
        self.conn = None    # Objeto de ligação ao cartão (criado em connect())
        self.pin_validated = False      # True se o PIN foi verificado nesta sessão
        self.reader = None      # Leitor de cartões selecionado
        logger.info("SmartcardClient inicializado")

    # Ligação

    def connect(self) -> bool:
        """
        Liga ao leitor e seleciona o applet no cartão.
        Se houver múltiplos leitores, pede ao utilizador para escolher.
        Retorna True se a ligação e seleção do applet forem bem sucedidas.
        """
        try:
            available = readers()
            if not available:
                logger.error("Nenhum leitor encontrado")
                print("[ERRO] Nenhum leitor encontrado. Verifique a ligação.")
                return False

            # Se há mais de um leitor, pede ao utilizador para escolher
            if len(available) > 1:
                print("Leitores disponíveis:")
                for i, r in enumerate(available):
                    print(f"  {i}. {r}")
                try:
                    idx = int(input("Escolha o leitor: "))
                    self.reader = available[idx]
                except (ValueError, IndexError):
                    logger.error("Seleção de leitor inválida")
                    print("[ERRO] Seleção inválida.")
                    return False
            else:
                # Só há um leitor, usa-o automaticamente
                self.reader = available[0]

            self.conn = self.reader.createConnection()
            self.conn.connect()
            logger.info("Ligado a: %s", self.reader)
            print(f"[OK] Ligado a: {self.reader}")

            # Após ligar ao leitor, seleciona o applet no cartão
            return self._select_applet()

        except (CardConnectionException, NoCardException, CardRequestTimeoutException) as e:
            logger.error("Erro de conexão: %s", e)
            print(f"[ERRO] Não foi possível ligar ao cartão: {e}")
            return False

    def _select_applet(self) -> bool:
        """
        Envia o comando SELECT para ativar o nosso applet no cartão.
 
        O SELECT usa CLA=0x00 (ISO standard), não o nosso CLA=0x80.
        O byte Le=0x7F indica ao cartão que pode devolver até 127 bytes de resposta.
        Sem este SELECT, o cartão não sabe qual applet deve processar os comandos seguintes.
        """
        try:
            aid_bytes = util.toBytes(APP_AID)
            # 0x7F como Le: indica ao cartão que pode devolver dados na resposta SELECT
            apdu = [0x00, 0xA4, 0x04, 0x00, len(aid_bytes)] + aid_bytes + [0x7F]
            data, sw1, sw2 = self.conn.transmit(apdu)

            if (sw1, sw2) == SW_OK:
                logger.info("Applet selecionado: %s", APP_AID)
                print(f"[OK] Applet selecionado ({APP_AID})")
                return True
            else:
                logger.error("Falha ao selecionar applet: %02X %02X", sw1, sw2)
                print(f"[ERRO] Falha ao selecionar applet: {sw1:02X} {sw2:02X}")
                return False
        except Exception as e:
            logger.error("Erro ao selecionar applet: %s", e)
            print(f"[ERRO] Erro ao selecionar applet: {e}")
            return False

    def disconnect(self) -> None:
        """Termina a ligação ao cartão de forma limpa."""
        if self.conn:
            try:
                self.conn.disconnect()
                logger.info("Desligado do cartão")
                print("[OK] Desligado do cartão")
            except Exception as e:
                logger.warning("Erro ao desligar: %s", e)

    # Comunicação APDU

    def _ensure_connected(self) -> None:
        """Garante que há uma ligação ativa antes de enviar qualquer comando."""
        if not self.conn:
            raise SmartcardException("Sem ligação ao cartão. Execute connect() primeiro.")

    def _send_apdu(self, ins: int, p1: int = 0x00, p2: int = 0x00,
                   data: Optional[bytes] = None, le: Optional[int] = None,
                   context: str = "") -> Tuple[List[int], int, int]:
        """
        Envia um comando APDU ao cartão e retorna a resposta.
 
        Estrutura de um Command-APDU:
            [CLA] [INS] [P1] [P2] ([Lc] [Data]) ([Le])
            - CLA: classe do comando (0x80 para a nossa aplicação)
            - INS: instrução (identifica o comando)
            - P1, P2: parâmetros do comando (normalmente 0x00)
            - Lc: tamanho dos dados enviados (adicionado automaticamente)
            - Data: dados do comando (opcional)
            - Le: tamanho máximo esperado na resposta (opcional)
 
        Tem retry logic: se o cartão não responder, tenta MAX_RETRIES antes de desistir.
        Retorna (dados_resposta, sw1, sw2).
        """
        self._ensure_connected()

        for attempt in range(MAX_RETRIES):
            try:
                apdu = [CLA, ins, p1, p2]
                if data:
                    # Lc é adicionado automaticamente como len(data)
                    apdu += [len(data)] + list(data)
                if le is not None:
                    apdu += [le]

                response_data, sw1, sw2 = self.conn.transmit(apdu)
                logger.debug("APDU %s sent (attempt %d/%d): %02X %02X",
                            context, attempt + 1, MAX_RETRIES, sw1, sw2)
                return response_data, sw1, sw2

            except Exception as e:
                logger.warning("APDU falhou (attempt %d/%d): %s", attempt + 1, MAX_RETRIES, e)
                if attempt < MAX_RETRIES - 1:
                    continue # Tenta novamente
                raise SmartcardException(f"APDU falhou após {MAX_RETRIES} tentativas: {e}")

    def _check_sw(self, sw1: int, sw2: int, context: str = "") -> bool:
        """
        Verifica os bytes de status da resposta do cartão.
 
        O cartão responde sempre com SW1 e SW2 no final de cada resposta.
        SW 9000 = sucesso. Qualquer outro valor indica um erro específico.
        Lança exceções personalizadas para que o menu possa tratá-las adequadamente.
        """
        if (sw1, sw2) == SW_OK:
            return True     # Tudo bem, continua.

        if (sw1, sw2) == SW_PIN_REQUIRED:
            # O applet recusou a operação porque o PIN não está validado.
            logger.error("PIN necessário: %s", context)
            raise PINRequiredException(f"PIN necessário ({context})")

        if (sw1, sw2) == SW_PIN_BLOCKED:
            # 3 tentativas de PIN falhadas. O cartão bloqueou.
            logger.error("PIN bloqueado")
            raise PINBlockedException("PIN bloqueado. O cartão não pode ser utilizado.")

        if sw1 == 0x63:
            # PIN incorreto. O nibble inferior de SW2 indica tentativas restantes.
            tentativas = sw2 & 0x0F
            logger.warning("PIN incorreto. Tentativas restantes: %d", tentativas)
            raise SmartcardException(f"PIN incorreto. Tentativas restantes: {tentativas}")

        if sw1 == 0x6A and sw2 == 0x84:
            # Memória do cartão insuficiente para o ficheiro.
            logger.error("Memória insuficiente")
            raise InsufficientMemoryException("Memória insuficiente no cartão")

        # Erro genérico não mapeado
        logger.error("Erro: %02X %02X (%s)", sw1, sw2, context)
        raise SmartcardException(f"Erro no cartão ({context}): {sw1:02X} {sw2:02X}")
    
    # PIN

    def ask_pin(self, prompt: str = "PIN (4 dígitos hex, ex: 01020304): ") -> Optional[bytes]:
        """
        Pede o PIN ao utilizador no terminal e converte para bytes.
 
        O PIN é inserido em hexadecimal.
        Retorna None se o formato for inválido.
        """
        raw = input(prompt).strip().replace(" ", "")
        if len(raw) != PIN_SIZE * 2:
            logger.warning("PIN com tamanho inválido: %d caracteres", len(raw))
            print(f"[ERRO] O PIN deve ter exatamente {PIN_SIZE * 2} caracteres hex.")
            return None
        try:
            return util.toBytes(raw)    # Converte string hex para lista de bytes
        except ValueError:
            logger.warning("PIN com formato inválido")
            print("[ERRO] Formato inválido. Use hex, ex: 01020304")
            return None

    def verify_pin(self) -> bool:
        """
        Pede o PIN ao utilizador e envia-o ao cartão para validação.
 
        Se o PIN estiver correto, o cartão mantém o estado "validado" internamente até ser deselecionado ou 
        até certas operações (como finalize_store) o resetarem. Atualiza self.pin_validated para refletir o estado local.
        """
        try:
            pin = self.ask_pin()
            if pin is None:
                return False

            _, sw1, sw2 = self._send_apdu(INS_VERIFY_PIN, data=pin, context="VERIFY_PIN")
            self._check_sw(sw1, sw2, "VERIFY_PIN")
            self.pin_validated = True
            logger.info("PIN validado com sucesso")
            print("[OK] PIN validado.")
            return True

        except SmartcardException as e:
            self.pin_validated = False
            logger.error("Erro na verificação do PIN: %s", e)
            print(f"[ERRO] {e}")
            return False

    def _require_pin(self) -> None:
        """
        Verifica se o PIN está validado antes de executar uma operação protegida.
        Lança PINRequiredException se não estiver.
        """
        if not self.pin_validated:
            raise PINRequiredException("PIN necessário")
        
    # Operações do cartão

    def get_status(self) -> dict:
        """
        Obtém o estado atual do cartão (operação livre, não requer PIN).
 
        Retorna um dicionário com:
            - pin_validated: se o PIN está atualmente validado no cartão
            - state: estado do armazenamento (VAZIO / A CARREGAR / PRONTO)
            - files: número de ficheiros carregados
            - max_files: capacidade máxima de ficheiros
        """
        try:
            # Le=4: esperamos 4 bytes de resposta
            data, sw1, sw2 = self._send_apdu(INS_GET_STATUS, le=4, context="GET_STATUS")
            self._check_sw(sw1, sw2, "GET_STATUS")

            return {
                "pin_validated": data[0] == 0x01,
                "state": STATE_LABELS.get(data[1], f"DESCONHECIDO ({data[1]:02X})"),
                "files": data[2],
                "max_files": data[3]
            }
        except SmartcardException as e:
            logger.error("Erro ao obter status: %s", e)
            raise

    def change_pin(self, new_pin: bytes) -> bool:
        """
        Altera o PIN do cartão.
 
        Após alterar o PIN, o cartão faz reset ao estado de autenticação (como medida de segurança), 
        pelo que é necessário re-validar com o novo PIN.
        """
        try:
            self._require_pin()
            if len(new_pin) != PIN_SIZE:
                raise SmartcardException(f"PIN deve ter {PIN_SIZE} bytes")

            _, sw1, sw2 = self._send_apdu(INS_CHANGE_PIN, data=new_pin, context="CHANGE_PIN")
            self._check_sw(sw1, sw2, "CHANGE_PIN")
            self.pin_validated = False  # PIN alterado, necessário re-validar
            logger.info("PIN alterado com sucesso")
            print("[OK] PIN alterado com sucesso. Necessário re-validar.")
            return True
        except SmartcardException as e:
            logger.error("Erro ao alterar PIN: %s", e)
            print(f"[ERRO] {e}")
            return False

    def init_store(self) -> bool:
        """
        Inicializa o armazenamento do cartão para uma nova sessão de carregamento.
 
        NOTA: Este comando apaga todos os ficheiros existentes no cartão e coloca-o no estado STATE_LOADING. 
        Só depois é possível enviar ficheiros.
        Requer PIN validado.
        """
        try:
            self._require_pin()
            _, sw1, sw2 = self._send_apdu(INS_INIT_STORE, context="INIT_STORE")
            self._check_sw(sw1, sw2, "INIT_STORE")
            logger.info("Armazenamento inicializado")
            return True
        except SmartcardException as e:
            logger.error("Erro ao inicializar armazenamento: %s", e)
            raise

    def add_file_header(self, filename: str, filesize: int) -> bool:
        """
        Declara um novo ficheiro no cartão antes de enviar os seus dados.
 
        O cartão precisa de saber antecipadamente o nome e tamanho do ficheiro
        para reservar espaço e validar que todos os chunks são recebidos corretamente.
 
        Formato do header enviado: [len_nome (1 byte)] [nome (N bytes)] [tamanho (2 bytes big-endian)]
        """
        try:
            self._require_pin()
            if len(filename) > MAX_NAME_SIZE:
                filename = filename[:MAX_NAME_SIZE]
                logger.warning("Nome do ficheiro truncado para %d caracteres", MAX_NAME_SIZE)

            if filesize <= 0:
                raise SmartcardException("Tamanho do ficheiro deve ser > 0")

            nome_bytes = filename.encode("ascii")
            # Constrói o payload 
            header = (
                bytes([len(nome_bytes)])
                + nome_bytes
                + bytes([filesize >> 8, filesize & 0xFF])   # tamanho em big-endian
            )
            _, sw1, sw2 = self._send_apdu(INS_ADD_FILE_HEADER, data=header,
                                          context=f"ADD_FILE_HEADER({filename})")
            self._check_sw(sw1, sw2, "ADD_FILE_HEADER")
            logger.info("Cabeçalho adicionado: %s (%d bytes)", filename, filesize)
            return True
        except SmartcardException as e:
            logger.error("Erro ao adicionar cabeçalho: %s", e)
            raise

    def write_chunk(self, data: bytes) -> bool:
        """
        Envia um bloco de dados (chunk) do ficheiro para o cartão.
 
        Como o APDU tem limite de 260 bytes, o ficheiro é dividido em blocos
        de CHUNK_SIZE bytes (200 bytes) e enviado em múltiplas chamadas.
        O cartão vai acumulando os bytes até receber o total declarado no header.
        """
        try:
            if len(data) > CHUNK_SIZE:
                raise SmartcardException(f"Chunk maior que {CHUNK_SIZE} bytes")
            _, sw1, sw2 = self._send_apdu(INS_WRITE_CHUNK, data=data, context="WRITE_CHUNK")
            self._check_sw(sw1, sw2, "WRITE_CHUNK")
            return True
        except SmartcardException as e:
            logger.error("Erro ao escrever chunk: %s", e)
            raise

    def finalize_file(self) -> bool:
        """
        Confirma que o ficheiro atual foi completamente enviado.
 
        O cartão verifica se o número de bytes recebidos coincide com o tamanho declarado no header. 
        Se não coincidir, lança um erro.
        Após este comando, é possível declarar o ficheiro seguinte com add_file_header.
        """
        try:
            _, sw1, sw2 = self._send_apdu(INS_FINALIZE_FILE, context="FINALIZE_FILE")
            self._check_sw(sw1, sw2, "FINALIZE_FILE")
            logger.info("Ficheiro finalizado")
            return True
        except SmartcardException as e:
            logger.error("Erro ao finalizar ficheiro: %s", e)
            raise

    def finalize_store(self) -> bool:
        """
        Fecha a sessão de carregamento e coloca o cartão em modo PRONTO (STATE_READY).
 
        Após este comando:
            - Não é possível carregar mais ficheiros sem fazer init_store novamente
            - O cartão está pronto para ser transportado e lido pelo Raspberry Pi
            - O applet faz reset ao PIN internamente (userPin.reset()), por isso
            self.pin_validated também é posto a False para manter consistência
        """
        try:
            _, sw1, sw2 = self._send_apdu(INS_FINALIZE_STORE, context="FINALIZE_STORE")
            self._check_sw(sw1, sw2, "FINALIZE_STORE")
            # O applet chama userPin.reset() em finalizeStore(),
            # por isso o estado local também tem de ser atualizado.
            self.pin_validated = False
            logger.info("Armazenamento finalizado — PIN resetado pelo cartão")
            return True
        except SmartcardException as e:
            logger.error("Erro ao finalizar armazenamento: %s", e)
            raise

    def get_file_info(self, file_index: int) -> dict:
        """
        Obtém metadados de um ficheiro pelo seu índice.
 
        Retorna o nome e tamanho do ficheiro sem revelar o seu conteúdo.
        O índice é passado no byte P1 do APDU (não como dados).
        Requer PIN validado.
        """
        try:
            self._require_pin()
            data, sw1, sw2 = self._send_apdu(INS_GET_FILE_INFO, p1=file_index,
                                             context=f"GET_FILE_INFO({file_index})")
            self._check_sw(sw1, sw2, "GET_FILE_INFO")

            name_len = data[0]
            name = bytes(data[1:1 + name_len]).decode("ascii", errors="replace")
            size = (data[1 + name_len] << 8) | data[2 + name_len]

            return {"name": name, "size": size}
        except SmartcardException as e:
            logger.error("Erro ao obter informações do ficheiro: %s", e)
            raise

    def confirm_download(self) -> bool:
        """
        Confirma que os ficheiros foram descarregados com sucesso e apaga o cartão.
 
        Este comando é chamado pelo Raspberry Pi após receber todos os ficheiros.
        O cartão apaga todos os dados e volta ao estado VAZIO.
        Requer PIN validado.
        """
        try:
            self._require_pin()
            _, sw1, sw2 = self._send_apdu(INS_CONFIRM_DOWNLOAD, context="CONFIRM_DOWNLOAD")
            self._check_sw(sw1, sw2, "CONFIRM_DOWNLOAD")
            self.pin_validated = False  # Cartão resetou após limpeza
            logger.info("Cartão limpo após confirmação de download")
            return True
        except SmartcardException as e:
            logger.error("Erro ao confirmar download: %s", e)
            raise

    def upload_file(self, filepaths: List[str]) -> bool:
        """
        Carrega múltiplos ficheiros completos no cartão numa única sessão.
    
        Sequência de operações:
            1. init_store       → apaga tudo e prepara para carregar
            2. add_file_header  → declara cada ficheiro (nome + tamanho)
            3. write_chunk      → envia os dados em blocos de CHUNK_SIZE bytes
            4. finalize_file    → confirma que o ficheiro está completo
            5. finalize_store   → fecha a sessão e coloca o cartão em modo PRONTO
    
        Os ficheiros já devem vir cifrados. Esta função não faz cifra.
        """
        try:
            self._require_pin()

            if not filepaths:
                raise SmartcardException("Nenhum ficheiro fornecido.")

            # Inicializar armazenamento uma única vez
            self.init_store()

            for filepath in filepaths:

                path = Path(filepath)
                if not path.exists():
                    raise FileNotFoundError(f"Ficheiro não encontrado: {filepath}")

                with open(path, "rb") as f:
                    dados = f.read()

                nome = path.name
                if len(nome) > MAX_NAME_SIZE:
                    nome = nome[:MAX_NAME_SIZE]

                tamanho = len(dados)
                logger.info("Carregando ficheiro: %s (%d bytes)", nome, tamanho)

                # Declarar ficheiro
                self.add_file_header(nome, tamanho)

                # Enviar dados em chunks
                offset = 0
                chunk_num = 1
                total_chunks = (tamanho + CHUNK_SIZE - 1) // CHUNK_SIZE

                while offset < tamanho:
                    chunk = dados[offset:offset + CHUNK_SIZE]
                    self.write_chunk(chunk)
                    offset += len(chunk)

                    print(
                        f"  [{nome}] Chunk {chunk_num}/{total_chunks} enviado "
                        f"({offset}/{tamanho} bytes)",
                        end="\r"
                    )

                    chunk_num += 1

                print(f"\n[OK] Todos os chunks enviados para {nome}.")

                # Finalizar ficheiro atual
                self.finalize_file()

            # Finalizar armazenamento no fim de todos os ficheiros
            self.finalize_store()

            # Nota: finalize_store faz reset ao PIN no cartão. É necessário re-validar. 
            logger.info("Todos os ficheiros carregados com sucesso")
            print("[OK] Todos os ficheiros carregados. Cartão pronto para transporte.")
            print("[INFO] O PIN foi resetado pelo cartão — será necessário re-validar.")

            return True

        except (FileNotFoundError, SmartcardException) as e:
            logger.error("Erro ao carregar ficheiros: %s", e)
            print(f"[ERRO] {e}")
            return False
        
    def read_chunk(self, file_index: int, chunk_index: int) -> bytes:
        """
        Lê um chunk de um ficheiro do cartão.

        P1 = índice do ficheiro
        P2 = índice do chunk
        """
        try:
            data, sw1, sw2 = self._send_apdu(
                INS_READ_CHUNK,
                p1=file_index,
                p2=chunk_index,
                le=CHUNK_SIZE,
                context=f"READ_CHUNK({file_index}, {chunk_index})"
            )

            self._check_sw(sw1, sw2, "READ_CHUNK")

            return bytes(data)

        except SmartcardException as e:
            logger.error("Erro ao ler chunk: %s", e)
            raise
        
    def download_all_files(self, output_dir: str) -> bool:
        """
        Descarrega todos os ficheiros do cartão.
        """
        try:
            self._require_pin()

            status = self.get_status()

            total = status["files"]

            if total == 0:
                print("[INFO] Cartão sem ficheiros.")
                return True

            print(f"[INFO] {total} ficheiro(s) encontrados.")

            for i in range(total):
                self.download_file(i, output_dir)

            logger.info("Todos os ficheiros descarregados")

            return True

        except SmartcardException as e:
            logger.error("Erro ao descarregar ficheiros: %s", e)
            print(f"[ERRO] {e}")
            return False

# Interface de utilizador (Menu)
# Cada função de menu trata os erros localmente para não quebrar o programa.

def menu_configurar(client: SmartcardClient):
    """1. Configurar cartão — alterar PIN."""
    print("\n── Configurar cartão ──")
    print("  1. Alterar PIN")
    print("  2. [TODO] Definir chave AES")
    print("  3. [TODO] Definir diretório")
    print("  0. Voltar")

    opcao = input("Opção: ").strip()

    if opcao == "1":
        try:
            # Para alterar o PIN é necessário primeiro validar o PIN atual
            if not client.pin_validated:
                print("É necessário validar o PIN atual primeiro.")
                if not client.verify_pin():
                    return

            novo_pin = client.ask_pin("Novo PIN (4 dígitos hex): ")
            if novo_pin is None:
                return

            if client.change_pin(novo_pin):
                # Após mudar o PIN, o cartão invalida a sessão. Pede re-validação.
                print("Por favor re-valide o PIN.")
                client.verify_pin()

        except SmartcardException as e:
            print(f"[ERRO] {e}")

    elif opcao == "2":
        print("[TODO] Funcionalidade de chave AES ainda não implementada.")

    elif opcao == "3":
        print("[TODO] Funcionalidade de diretório ainda não implementada.")


def menu_carregar(client: SmartcardClient):
    """
    Menu 2 - Carregar ficheiros no cartão.
    Pede os caminhos dos ficheiros, confirma com o utilizador e chama upload_file.
    """
    print("\n── Carregar ficheiros ──")

    try:
        if not client.pin_validated:
            print("É necessário validar o PIN.")
            if not client.verify_pin():
                return

        caminhos = input(
            "Caminhos dos ficheiros (separados por vírgula): "
        ).strip()

        filepaths = [p.strip() for p in caminhos.split(",") if p.strip()]

        if not filepaths:
            print("[ERRO] Nenhum ficheiro fornecido.")
            return

        confirmar = input("Confirmar carregamento? (s/n): ").strip().lower()
        if confirmar != "s":
            print("Cancelado.")
            return

        client.upload_file(filepaths)

    except SmartcardException as e:
        print(f"[ERRO] {e}")


def menu_ver_estado(client: SmartcardClient):
    """
    Menu 3 - Ver estado do cartão.
    Sem PIN: mostra estado geral e número de ficheiros.
    Com PIN: mostra também o nome e tamanho de cada ficheiro.
    """
    print("\n── Estado do cartão ──")

    try:
        # GET_STATUS não requer PIN - qualquer pessoa pode ver o estado geral
        status = client.get_status()
        print(f"  Estado:     {status['state']}")
        print(f"  Ficheiros:  {status['files']}/{status['max_files']}")
        print(f"  PIN válido: {'Sim' if status['pin_validated'] else 'Não'}")

        if status['files'] == 0:
            return  # Sem ficheiros, não há mais detalhes para mostrar

        # Para ver os detalhes dos ficheiros é necessário PIN validado
        if not client.pin_validated:
            print("\nPara ver detalhes dos ficheiros é necessário validar o PIN.")
            if not client.verify_pin():
                return

        print("\n  Ficheiros carregados:")
        for i in range(status['files']):
            try:
                info = client.get_file_info(i)
                print(f"    [{i}] {info['name']} — {info['size']} bytes")
            except SmartcardException as e:
                logger.warning("Erro ao obter info do ficheiro %d: %s", i, e)

    except SmartcardException as e:
        print(f"[ERRO] {e}")


def menu_limpar(client: SmartcardClient):
    """
    Menu 4 - Limpar cartão após confirmação de download.
    Envia CONFIRM_DOWNLOAD que apaga todos os ficheiros.
    Pede confirmação explícita antes de executar (operação irreversível).
    """
    print("\n── Limpar cartão ──")

    try:
        if not client.pin_validated:
            print("É necessário validar o PIN.")
            if not client.verify_pin():
                return

        print("[AVISO] Esta operação confirma o download e apaga todos os ficheiros do cartão.")
        confirmar = input("Tem a certeza? (s/n): ").strip().lower()
        if confirmar != "s":
            print("Cancelado.")
            return

        if client.confirm_download():
            print("[OK] Cartão limpo com sucesso.")

    except SmartcardException as e:
        print(f"[ERRO] {e}")


def menu_principal(client: SmartcardClient):
    """Menu principal - loop até o utilizador escolher sair."""
    while True:
        print("\n══════════════════════════════")
        print("   PIC — Gestão de Smartcard  ")
        print("══════════════════════════════")
        print("  1. Configurar cartão")
        print("  2. Carregar ficheiro")
        print("  3. Ver estado")
        print("  4. Limpar cartão")
        print("  0. Sair")
        print("──────────────────────────────")

        opcao = input("Opção: ").strip()

        if opcao == "1":
            menu_configurar(client)
        elif opcao == "2":
            menu_carregar(client)
        elif opcao == "3":
            menu_ver_estado(client)
        elif opcao == "4":
            menu_limpar(client)
        elif opcao == "0":
            print("A sair...")
            client.disconnect()
            logger.info("Aplicação encerrada")
            break
        else:
            print("Opção inválida.")


# Entrada principal

if __name__ == "__main__":
    logger.info("Aplicação iniciada")
    client = SmartcardClient()

    if client.connect():
        menu_principal(client)
    else:
        logger.error("Falha ao ligar ao cartão")
        print("[ERRO] Não foi possível ligar ao cartão.")