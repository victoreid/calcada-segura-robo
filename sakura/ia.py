# -*- coding: utf-8 -*-
"""
Requisito 9: integracao com IA generativa (Google Gemini).

A Sakura responde com personalidade JDM: e especialista em drift
e carros japoneses. A persona vem do PROMPT_SISTEMA no config.py.
"""

import time

import requests

from config import (GEMINI_API_KEY, GEMINI_MODELO,
                    PROMPT_SISTEMA, MAX_TOKENS_IA)


def perguntar(pergunta, tentativas=3):
    """Envia a pergunta para o Gemini e devolve a resposta em texto."""
    pergunta = (pergunta or "").strip()
    if not pergunta:
        return "Nao entendi a pergunta."

    if not GEMINI_API_KEY or "COLE_SUA" in GEMINI_API_KEY:
        return ("A chave da API do Gemini nao foi configurada. "
                "Coloque sua chave no arquivo config ponto p y.")

    url = (f"https://generativelanguage.googleapis.com/v1beta/models/"
           f"{GEMINI_MODELO}:generateContent")

    corpo = {
        "system_instruction": {"parts": [{"text": PROMPT_SISTEMA}]},
        "contents": [{"role": "user", "parts": [{"text": pergunta}]}],
        "generationConfig": {
            "temperature": 0.8,
            "maxOutputTokens": MAX_TOKENS_IA,
        },
    }

    ultimo_erro = ""

    # O servidor as vezes devolve 503 (sobrecarregado) ou corta a resposta.
    # Aqui aplicamos "backoff": tenta de novo esperando cada vez mais.
    for tentativa in range(1, tentativas + 1):
        try:
            r = requests.post(url, json=corpo, timeout=60,
                              headers={"x-goog-api-key": GEMINI_API_KEY})
            r.raise_for_status()

            partes = r.json()["candidates"][0]["content"]["parts"]
            texto = "".join(p.get("text", "") for p in partes)

            # resposta muito curta = truncada, vale tentar de novo
            if len(texto.strip()) > 15:
                return _limpar(texto)
            ultimo_erro = "resposta truncada"

        except requests.exceptions.Timeout:
            ultimo_erro = "tempo esgotado"

        except requests.exceptions.ConnectionError:
            return "Nao consegui conectar na IA. Verifique sua internet."

        except requests.exceptions.HTTPError as e:
            codigo = e.response.status_code
            if codigo in (429, 500, 503):            # vale tentar de novo
                ultimo_erro = f"servidor ocupado ({codigo})"
            elif codigo in (400, 401, 403):
                return (f"A IA recusou a requisicao. "
                        f"Verifique sua chave. Erro {codigo}.")
            elif codigo == 404:
                return "Modelo nao encontrado. Verifique o nome no config."
            else:
                return f"Erro inesperado da IA: {codigo}."

        except Exception as e:
            ultimo_erro = str(e)

        if tentativa < tentativas:
            print(f"  (tentativa {tentativa} falhou: {ultimo_erro}, "
                  f"tentando de novo...)")
            time.sleep(2 * tentativa)                # 2s, depois 4s

    return f"A IA nao respondeu agora ({ultimo_erro}). Tente novamente."


def _limpar(texto):
    """Remove markdown para a fala nao sair com asteriscos."""
    for c in ("**", "*", "#", "`", "_"):
        texto = texto.replace(c, "")
    return " ".join(texto.split())