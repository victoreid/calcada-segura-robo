# -*- coding: utf-8 -*-
"""
Ponte Sakura <-> robo inspetor de calcadas (Calcada Segura).

Caminho dos dados:

    sensores do robo --Bluetooth--> app Android --Wi-Fi (HTTP)--> Sakura
    (MPU-6050, sonares)                                           |
                                                                  v
    motores do robo <--Bluetooth-- app Android <-- decisao G / R / P
                                                   + comandos de voz

O app manda cada linha JSON de telemetria do robo:
    {"t":12345,"m":"A","df":32,"dc":0.4,"inc":11.2,"vib":0.42,"ev":"rampa"}
e a Sakura responde:
    {"decisao":"R","motivo":"Rampa de 11 %","fala":"...","comandos":""}

    decisao  = G (seguir), R (reduzir) ou P (parar). Vale no modo AUTO.
    motivo   = texto curto que o app mostra na tela.
    fala     = o que a Sakura acabou de falar ("" se nao falou nada).
    comandos = letras pedidas por voz ("Sakura, robo modo automatico" = "I").

Tambem roda sozinho, sem microfone (so a ponte):  python robo.py
"""

import json
import os
import socket
import threading
import time
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from config import PASTA_INSPECOES, PORTA_ROBO, ROBO_FALAR_ALERTAS

# Limites (os mesmos do firmware e do app)
RAMPA_LIMITE = 8.33     # % - NBR 9050
VIB_IRREGULAR = 0.12    # g RMS - piso irregular
VIB_FORTE = 0.30        # g RMS - piso muito irregular

SEGURA_PARADA_S = 1.5   # depois de um perigo sumir, ainda espera 1,5 s parado
APP_OFFLINE_S = 3.0     # sem telemetria ha 3 s = app desconectado
COMANDO_VALIDADE_S = 10 # comando de voz que o app nao buscou em 10 s e descartado
PAUSA_MESMA_FALA_S = 6  # nao repete o mesmo alerta antes de 6 s

# Motivo -> (decisao, texto curto para o app, frase falada)
MOTIVOS = {
    "buraco":      ("P", "Buraco ou degrau",
                    "Atencao: buraco ou degrau na calcada. Parei o robo."),
    "obstaculo":   ("P", "Obstaculo a frente",
                    "Obstaculo a frente. Robo parado."),
    "falha_mpu":   ("P", "MPU-6050 sem resposta",
                    "O sensor de inclinacao parou de responder. Confira os fios do MPU."),
    "falha_sonar": ("P", "Sonar com defeito",
                    "Um dos sonares nao esta respondendo. Parei por seguranca."),
    "bt_perdido":  ("P", "Bluetooth perdido",
                    "O robo perdeu a conexao Bluetooth."),
    "rampa":       ("R", "Rampa de {inc:.0f} % (limite 8,33 %)",
                    "Rampa com {inc:.0f} por cento de inclinacao, acima do limite "
                    "da norma. Reduzindo."),
    "trepidacao":  ("R", "Piso muito irregular ({vib} g)",
                    "Piso muito irregular, trepidacao de {vib} g. Indo devagar."),
    "irregular":   ("R", "Piso irregular ({vib} g)",
                    "Piso irregular. Reduzindo a velocidade."),
    "segurando":   ("P", "Confirmando que o caminho liberou", ""),
    "ok":          ("G", "Caminho livre", ""),
}

# Ocorrencias que entram no relatorio da inspecao
OCORRENCIAS = ("buraco", "obstaculo", "rampa", "trepidacao", "irregular")


def _num(valor, padrao=0.0):
    try:
        v = float(valor)
    except (TypeError, ValueError):
        return padrao
    return padrao if v != v else v          # NaN vira o padrao


def classificar(t):
    """
    Olha UMA leitura do robo e devolve o motivo mais importante.
    Ordem: perigo (para) > defeito de sensor (para) > rampa e piso (reduz) > ok.
    """
    ev = str(t.get("ev", "ok"))
    if ev in ("buraco", "obstaculo", "falha_mpu", "falha_sonar", "bt_perdido"):
        return ev
    if abs(_num(t.get("inc"))) > RAMPA_LIMITE:
        return "rampa"
    vib = _num(t.get("vib"))
    if vib > VIB_FORTE:
        return "trepidacao"
    if vib > VIB_IRREGULAR or ev == "irregular":
        return "irregular"
    if ev == "rampa":                       # o firmware confirmou a rampa
        return "rampa"
    return "ok"


def _br(valor, casas):
    """0.18 -> '0,18' (a voz em portugues le "zero virgula dezoito")."""
    return f"{valor:.{casas}f}".replace(".", ",")


def _texto(modelo, t):
    return modelo.format(inc=abs(_num(t.get("inc"))), vib=_br(_num(t.get("vib")), 2))


class Inspecao:
    """Estado da inspecao: decide, lembra o que aconteceu e guarda os comandos."""

    def __init__(self, falar=None, pasta=PASTA_INSPECOES, relogio=time.monotonic):
        self._falar = falar
        self._pasta = pasta
        self._relogio = relogio
        self._lock = threading.Lock()
        self._comandos = []                 # [(letra, hora)]
        self._ultima_fala = {}              # motivo -> hora
        self._zerar()

    def reiniciar(self):
        """Comeca uma inspecao nova (zera o relatorio)."""
        with self._lock:
            self._zerar()

    def _zerar(self):
        self._motivo = "ok"
        self._perigo_ate = 0.0
        self._visto_em = None
        self.leituras = 0
        self.contagem = {m: 0 for m in OCORRENCIAS}
        self.vib_max = 0.0
        self.vib_soma = 0.0
        self.inc_max = 0.0
        self.modo = "M"

    # ---------------------------------------------------------- telemetria
    def receber(self, t):
        """Recebe uma leitura (dict) e devolve a resposta para o app (dict)."""
        agora = self._relogio()
        fala = ""
        with self._lock:
            self._visto_em = agora
            self.leituras += 1
            self.modo = str(t.get("m", "M"))[:1].upper() or "M"
            vib = _num(t.get("vib"))
            inc = abs(_num(t.get("inc")))
            if t.get("ev") != "falha_mpu":
                self.vib_max = max(self.vib_max, vib)
                self.vib_soma += vib
                self.inc_max = max(self.inc_max, inc)

            motivo = classificar(t)
            if MOTIVOS[motivo][0] == "P" and motivo in ("buraco", "obstaculo"):
                self._perigo_ate = agora + SEGURA_PARADA_S
            elif MOTIVOS[motivo][0] != "P" and agora < self._perigo_ate:
                # O perigo acabou de sumir: segura parado mais um pouco
                # (um sonar que oscila nao pode fazer o robo dar trancos).
                motivo = "segurando"

            mudou = motivo != self._motivo
            if mudou and motivo in OCORRENCIAS:
                self.contagem[motivo] += 1
                self._registrar(motivo, t)
            if mudou and MOTIVOS[motivo][2] and self._pode_falar(motivo, agora):
                fala = _texto(MOTIVOS[motivo][2], t)
            self._motivo = motivo

            decisao, rotulo, _ = MOTIVOS[motivo]
            comandos = self._tirar_comandos(agora)

        if fala and ROBO_FALAR_ALERTAS and self._falar:
            # Fala em outra thread: o app nao pode ficar esperando a voz.
            threading.Thread(target=self._falar, args=(fala,), daemon=True).start()
        return {
            "decisao": decisao,
            "motivo": _texto(rotulo, t),
            "fala": fala,
            "comandos": comandos,
        }

    def _pode_falar(self, motivo, agora):
        ultima = self._ultima_fala.get(motivo)
        if ultima is not None and agora - ultima < PAUSA_MESMA_FALA_S:
            return False
        self._ultima_fala[motivo] = agora
        return True

    def _registrar(self, motivo, t):
        """Guarda a ocorrencia em inspecoes/AAAA-MM-DD.jsonl (base do mapa da calcada)."""
        if not self._pasta:
            return
        try:
            os.makedirs(self._pasta, exist_ok=True)
            linha = {"quando": datetime.now().isoformat(timespec="seconds"),
                     "ocorrencia": motivo, "inc": _num(t.get("inc")),
                     "vib": _num(t.get("vib")), "df": _num(t.get("df")),
                     "dc": _num(t.get("dc"))}
            nome = datetime.now().strftime("%Y-%m-%d") + ".jsonl"
            with open(os.path.join(self._pasta, nome), "a", encoding="utf-8") as f:
                f.write(json.dumps(linha, ensure_ascii=False) + "\n")
        except OSError as e:
            print(f"  (nao consegui salvar a ocorrencia: {e})")

    # ---------------------------------------------------------- comandos
    def app_conectado(self):
        with self._lock:
            return (self._visto_em is not None and
                    self._relogio() - self._visto_em <= APP_OFFLINE_S)

    def pedir(self, letra):
        with self._lock:
            # Parar passa na frente de tudo e cancela o que estava na fila.
            if letra == "0":
                self._comandos.clear()
            self._comandos.append((letra, self._relogio()))

    def _tirar_comandos(self, agora):
        letras = "".join(c for c, hora in self._comandos
                         if agora - hora <= COMANDO_VALIDADE_S)
        self._comandos.clear()
        return letras

    # ---------------------------------------------------------- relatorio
    def relatorio(self):
        with self._lock:
            if self.leituras == 0:
                return "Ainda nao recebi nenhuma leitura do robo."
            c = self.contagem
            partes = []
            nomes = {"buraco": ("buraco ou degrau", "buracos ou degraus"),
                     "obstaculo": ("obstaculo", "obstaculos"),
                     "rampa": ("rampa fora da norma", "rampas fora da norma"),
                     "trepidacao": ("trecho muito irregular", "trechos muito irregulares"),
                     "irregular": ("trecho irregular", "trechos irregulares")}
            for m in OCORRENCIAS:
                if c[m]:
                    um, varios = nomes[m]
                    partes.append(f"{c[m]} {um if c[m] == 1 else varios}")
            media = self.vib_soma / self.leituras
            achados = (", ".join(partes) if partes
                       else "nenhum problema na calcada")
            return (f"Nesta inspecao encontrei {achados}. "
                    f"A trepidacao media foi de {_br(media, 2)} g, com pico de "
                    f"{_br(self.vib_max, 2)} g, e a maior inclinacao foi de "
                    f"{_br(self.inc_max, 1)} por cento.")

    def dados(self):
        """Resumo em numeros (para a IA generativa)."""
        with self._lock:
            media = self.vib_soma / self.leituras if self.leituras else 0.0
            return {"leituras": self.leituras, "ocorrencias": dict(self.contagem),
                    "trepidacao_media_g": round(media, 3),
                    "trepidacao_pico_g": round(self.vib_max, 3),
                    "inclinacao_max_pct": round(self.inc_max, 1),
                    "limite_rampa_pct": RAMPA_LIMITE}


# ============================================================ COMANDOS DE VOZ
def _tem(texto, *chaves):
    return any(c in texto for c in chaves)


def comando_de_voz(texto, inspecao):
    """
    Trata "Sakura, robo ..." (texto ja normalizado, sem acento).
    Devolve a frase de resposta, ou None se nao for um comando do robo.
    """
    palavras = set(texto.replace(",", " ").split())
    if not (palavras & {"robo", "robos", "inspecao", "calcada"}):
        return None

    if _tem(texto, "nova inspecao", "zerar", "zera o relatorio"):
        inspecao.reiniciar()
        return "Pronto, comecei uma inspecao nova. O relatorio foi zerado."

    if _tem(texto, "relatorio", "resumo", "como esta a calcada",
            "como foi a inspecao", "o que voce encontrou"):
        return inspecao.relatorio()

    if _tem(texto, "analisa", "analise", "avalia", "avaliacao"):
        return analisar_com_ia(inspecao)

    if _tem(texto, "status", "conectado", "esta ligado", "esta online"):
        if inspecao.app_conectado():
            modo = "automatico" if inspecao.modo == "A" else "manual"
            return f"O robo esta conectado, no modo {modo}."
        return "O robo nao esta conectado. Abra o app e ligue a Sakura nele."

    if palavras & {"parar", "pare", "para", "stop", "freia", "frear", "parado",
                   "desligar", "desliga"}:
        letra, frase = "0", "Parando o robo."
    elif _tem(texto, "automatico", "inspecionar", "comecar a inspecao",
              "iniciar inspecao", "iniciar a inspecao", "inspecao automatica"):
        letra, frase = "I", "Iniciando a inspecao automatica. Eu cuido das decisoes."
    elif _tem(texto, "manual"):
        letra, frase = "M", "Voltando para o modo manual."
    elif _tem(texto, "buzina", "buzinar", "apita", "apitar", "bipe"):
        letra, frase = "B", "Bi bi!"
    elif _tem(texto, "calibrar", "calibra", "recalibrar"):
        letra, frase = "K", "Recalibrando o chao e a inclinacao. Deixe o robo parado."
    else:
        return ("Posso parar o robo, iniciar a inspecao automatica, voltar ao "
                "manual, buzinar, recalibrar ou dar o relatorio da calcada.")

    if not inspecao.app_conectado():
        return "O robo nao esta conectado. Abra o app e ligue a Sakura nele."
    inspecao.pedir(letra)
    return frase


def analisar_com_ia(inspecao):
    """Pede ao Gemini uma avaliacao da calcada a partir dos numeros da inspecao."""
    if inspecao.leituras == 0:
        return inspecao.relatorio()
    try:
        import ia
        from config import GEMINI_API_KEY
    except Exception:
        return inspecao.relatorio()
    if not GEMINI_API_KEY or "COLE_SUA" in GEMINI_API_KEY:
        return inspecao.relatorio()
    pergunta = (
        "Voce recebeu os dados de um robo que inspecionou uma calcada de Sao Paulo. "
        "Avalie em no maximo 3 frases se ela e acessivel para cadeirantes, "
        "considerando a NBR 9050 (rampa ate 8,33 por cento) e a trepidacao "
        "(acima de 0,12 g e piso irregular). Dados: "
        + json.dumps(inspecao.dados(), ensure_ascii=False))
    return ia.perguntar(pergunta)


# ============================================================ SERVIDOR HTTP
inspecao = Inspecao()
_servidor = None


def _sem_aspas(valor):
    # O app le a resposta sem biblioteca de JSON: nada de aspas ou barras no texto.
    return str(valor).replace('"', "'").replace("\\", "/")


class _Ponte(BaseHTTPRequestHandler):
    server_version = "SakuraPonte/1.0"

    def _responder(self, codigo, corpo):
        dados = json.dumps(corpo, ensure_ascii=False).encode("utf-8")
        self.send_response(codigo)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(dados)))
        self.end_headers()
        self.wfile.write(dados)

    def do_GET(self):
        if self.path.rstrip("/") in ("", "/status"):
            self._responder(200, {"ok": True, "nome": "Sakura",
                                  "app_conectado": inspecao.app_conectado()})
        else:
            self._responder(404, {"erro": "caminho desconhecido"})

    def do_POST(self):
        if self.path.rstrip("/") != "/robo/telemetria":
            self._responder(404, {"erro": "caminho desconhecido"})
            return
        try:
            tamanho = min(int(self.headers.get("Content-Length", 0)), 2048)
            t = json.loads(self.rfile.read(tamanho).decode("utf-8") or "{}")
            if not isinstance(t, dict):
                raise ValueError("esperava um objeto JSON")
        except (ValueError, UnicodeDecodeError) as e:
            self._responder(400, {"erro": _sem_aspas(e)})
            return
        r = inspecao.receber(t)
        self._responder(200, {k: _sem_aspas(v) for k, v in r.items()})

    def log_message(self, *args):
        pass                                # 4 pedidos por segundo: nao polui o terminal


def ip_local():
    """IP do PC na rede local (o que se digita no app). Nao envia nada."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def iniciar_servidor(falar=None, porta=PORTA_ROBO):
    """Abre a ponte em segundo plano. Devolve o endereco para digitar no app."""
    global _servidor
    if falar is not None:
        inspecao._falar = falar
    if _servidor is None:
        _servidor = ThreadingHTTPServer(("0.0.0.0", porta), _Ponte)
        threading.Thread(target=_servidor.serve_forever, daemon=True).start()
    return f"{ip_local()}:{porta}"


def parar_servidor():
    global _servidor
    if _servidor is not None:
        _servidor.shutdown()
        _servidor.server_close()
        _servidor = None


if __name__ == "__main__":
    try:
        from voz import falar as _f
    except Exception:                       # sem pyttsx3/microfone: so imprime
        def _f(texto):
            print(f"[SAKURA]  {texto}")
    endereco = iniciar_servidor(_f)
    print("=" * 58)
    print("  SAKURA - ponte com o robo inspetor (sem microfone)")
    print(f"  No app, toque em Sakura e digite:  {endereco}")
    print("  CTRL + C para encerrar.")
    print("=" * 58)
    try:
        while True:
            time.sleep(5)
            if inspecao.leituras:
                estado = "conectado" if inspecao.app_conectado() else "sem dados"
                print(f"[robo] {estado} | leituras: {inspecao.leituras} | "
                      f"ocorrencias: {inspecao.contagem}")
    except KeyboardInterrupt:
        print("\n" + inspecao.relatorio())
        parar_servidor()
