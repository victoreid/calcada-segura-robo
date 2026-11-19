# -*- coding: utf-8 -*-
"""
Camada de voz da Sakura: ouvir (STT) e falar (TTS).
"""

import threading
import unicodedata
import pyttsx3
import speech_recognition as sr

from config import (IDIOMA, VOZ_TAXA, VOZ_VOLUME,
                    ENERGIA_MIC, PAUSA_MIC, INDICE_MICROFONE)


# ============================================================ FALAR (TTS)
# A ponte do robo (robo.py) fala de outra thread. O lock garante que duas
# falas nunca usem o pyttsx3 ao mesmo tempo (ele trava se isso acontecer).
_lock_fala = threading.Lock()


def falar(texto):
    """Fala o texto em voz alta e imprime no terminal."""
    with _lock_fala:
        _falar(texto)


def _falar(texto):
    print(f"[SAKURA]  {texto}\n")
    try:
        # Motor NOVO a cada fala: evita o bug do runAndWait()
        # que faz o pyttsx3 emudecer depois da primeira chamada.
        eng = pyttsx3.init()
        eng.setProperty("rate", VOZ_TAXA)
        eng.setProperty("volume", VOZ_VOLUME)

        # Procura voz masculina em portugues (Microsoft Maria).
        # Se nao achar, cai para qualquer voz em portugues.
        vozes = eng.getProperty("voices")
        escolhida = None

        for v in vozes:
            if "maria" in v.name.lower():
                escolhida = v.id
                break

        if escolhida is None:
            for v in vozes:
                if "brazil" in v.name.lower() or "portug" in v.name.lower():
                    escolhida = v.id
                    break

        if escolhida:
            eng.setProperty("voice", escolhida)

        eng.say(texto)
        eng.runAndWait()
        eng.stop()
        del eng                      # libera o objeto COM do Windows
    except Exception as e:
        print(f"  (falha no TTS: {e})")


# ============================================================ OUVIR (STT)
_r = sr.Recognizer()
_r.energy_threshold = ENERGIA_MIC
_r.dynamic_energy_threshold = True
_r.pause_threshold = PAUSA_MIC


def ouvir(timeout=None, limite=8):
    """Escuta o microfone e devolve o texto reconhecido ('' se falhar)."""
    try:
        with sr.Microphone(device_index=INDICE_MICROFONE) as fonte:
            _r.adjust_for_ambient_noise(fonte, duration=0.4)
            print("[ouvindo...]", end="\r")
            audio = _r.listen(fonte, timeout=timeout, phrase_time_limit=limite)
    except sr.WaitTimeoutError:
        return ""
    except OSError as e:
        print(f"[ERRO] Microfone: {e}")
        return ""

    try:
        texto = _r.recognize_google(audio, language=IDIOMA)
    except sr.UnknownValueError:
        return ""                    # captou audio, mas nao entendeu
    except sr.RequestError as e:
        print(f"[ERRO] Sem conexao com o Google: {e}")
        return ""

    print(f"[VOCÊ] {texto}")
    return texto


def ouvir_resposta(pergunta, timeout=8, limite=15):
    """Faz uma pergunta em voz alta e aguarda a resposta do usuario."""
    falar(pergunta)
    return ouvir(timeout=timeout, limite=limite)


# ============================================================ UTILIDADE
def normalizar(texto):
    """'Que Horas São?' -> 'que horas sao'"""
    if not texto:
        return ""
    texto = texto.lower().strip()
    texto = unicodedata.normalize("NFKD", texto)
    texto = "".join(c for c in texto if not unicodedata.combining(c))
    return " ".join(texto.split())