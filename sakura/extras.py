# -*- coding: utf-8 -*-
"""
Requisito 10: comandos extras.
Cada funcao devolve uma frase pronta para ser falada.
"""

import os
import webbrowser
from datetime import datetime

import requests

from config import CIDADE_PADRAO, URL_PORTAL_FACULDADE, PASTA_PRINTS


# ====================================================== PREVISAO DO TEMPO
def previsao_tempo(cidade=None):
    """API publica wttr.in - nao precisa de chave."""
    cidade = (cidade or CIDADE_PADRAO).strip()
    try:
        r = requests.get(f"https://wttr.in/{cidade}",
                         params={"format": "j1", "lang": "pt"},
                         headers={"User-Agent": "curl/8"}, timeout=20)
        r.raise_for_status()
        d = r.json()

        atual = d["current_condition"][0]
        desc = (atual.get("lang_pt", [{}])[0].get("value")
                or atual["weatherDesc"][0]["value"])
        hoje = d["weather"][0]

        return (f"Em {cidade} agora faz {atual['temp_C']} graus, "
                f"sensacao de {atual['FeelsLikeC']}, tempo {desc.lower()}, "
                f"umidade de {atual['humidity']} por cento. "
                f"Hoje a minima e {hoje['mintempC']} e a maxima "
                f"{hoje['maxtempC']} graus.")
    except Exception:
        return "Nao consegui obter a previsao do tempo agora."


# ============================================================== COTACOES
def _cotacao(par, nome):
    try:
        r = requests.get(f"https://economia.awesomeapi.com.br/last/{par}",
                         timeout=20)
        r.raise_for_status()
        info = list(r.json().values())[0]
        valor = float(info["bid"])
        var = float(info.get("pctChange", 0))
        sentido = "alta" if var >= 0 else "queda"
        return (f"{nome} esta cotado a {valor:.2f} reais, "
                f"em {sentido} de {abs(var):.2f} por cento hoje.")
    except Exception:
        return "Nao consegui consultar a cotacao agora."


def valor_dolar():
    return _cotacao("USD-BRL", "O dolar")


def valor_bitcoin():
    return _cotacao("BTC-BRL", "O bitcoin")


def valor_euro():
    return _cotacao("EUR-BRL", "O euro")


# ================================================== NAVEGADOR E MULTIMIDIA
def tocar_youtube(termo):
    termo = (termo or "").strip()
    if not termo:
        return "Me diga o que voce quer assistir."
    try:
        import pywhatkit
        pywhatkit.playonyt(termo)
    except Exception:
        webbrowser.open(
            f"https://www.youtube.com/results?search_query={termo}")
    return f"Abrindo no YouTube: {termo}."


def pesquisar_google(termo):
    termo = (termo or "").strip()
    if not termo:
        return "O que voce quer que eu pesquise?"
    webbrowser.open(f"https://www.google.com/search?q={termo}")
    return f"Pesquisando no Google por {termo}."


def tocar_spotify(musica):
    musica = (musica or "").strip()
    if not musica:
        return "Qual musica voce quer ouvir?"
    webbrowser.open(f"https://open.spotify.com/search/{musica}")
    return f"Procurando {musica} no Spotify."


def abrir_portal():
    webbrowser.open(URL_PORTAL_FACULDADE)
    return "Abrindo o portal da faculdade."


# ============================================================== SISTEMA
def ajustar_volume(direcao="aumentar", passos=5):
    try:
        import pyautogui
        tecla = "volumeup" if direcao == "aumentar" else "volumedown"
        pyautogui.press(tecla, presses=passos)
        acao = "aumentado" if direcao == "aumentar" else "diminuido"
        return f"Volume {acao}."
    except Exception:
        return "Nao consegui ajustar o volume."


def tirar_print():
    try:
        import pyautogui
        os.makedirs(PASTA_PRINTS, exist_ok=True)
        nome = datetime.now().strftime("print_%Y%m%d_%H%M%S.png")
        pyautogui.screenshot().save(os.path.join(PASTA_PRINTS, nome))
        return f"Print da tela salvo como {nome}."
    except Exception:
        return "Nao consegui tirar o print."