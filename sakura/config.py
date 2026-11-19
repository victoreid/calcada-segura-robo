# -*- coding: utf-8 -*-
"""Configurações da Sakura - Assistente Virtual JDM"""

import os

NOME_ASSISTENTE = "Sakura"


PALAVRAS_ATIVACAO = [
    "sakura", "sacura", "sakuras", "sakur", "socorro",
    "sakura", "zakura", "sacola",
]

# ---- VOZ ----
IDIOMA = "pt-BR"
VOZ_TAXA = 165
VOZ_VOLUME = 1.0

# ---- MICROFONE ----
INDICE_MICROFONE = None
ENERGIA_MIC = 300
PAUSA_MIC = 0.8


# ---- ARQUIVOS ----
ARQUIVO_AGENDA = "agenda.txt"


# ---- RECONHECIMENTO FACIAL ----
PASTA_DATASET = "dataset"            # fotos para treinar
MODELO_FACE = "modelo_lbph.yml"      # modelo treinado (dica do professor)
ROTULOS_FACE = "rotulos_face.json"   # mapeia id -> nome da pessoa

# LBPH: quanto MENOR a confianca, MELHOR o acerto.
# Acima deste limite a pessoa vira "desconhecido".
LIMITE_CONFIANCA_FACE = 70


# ---- IA GENERATIVA ----
# A chave NUNCA vai para o GitHub. Prefira a variavel de ambiente:
#   Windows (PowerShell):  $env:GEMINI_API_KEY = "sua_chave"
GEMINI_API_KEY = os.environ.get("GEMINI_API_KEY", "COLE_SUA_CHAVE_AQUI")
GEMINI_MODELO = "gemini-3.5-flash-lite"
MAX_TOKENS_IA = 2000

PROMPT_SISTEMA = (
    "Voce e a Sakura, uma assistente virtual brasileira especialista em "
    "drift e cultura automotiva japonesa (JDM). Voce conhece a fundo modelos "
    "como AE86, RX-7 FD, Silvia S15, Supra JZA80, GT-R R34 e Miata NA, "
    "motores como o 2JZ-GTE, SR20DET, 4A-GE e RB26DETT, tecnicas de drift "
    "(clutch kick, feint, powerslide, contraesterco) e a cultura das montanhas "
    "de Gunma e da Wangan. "
    "Responda SEMPRE em portugues do Brasil, de forma curta e direta, no maximo "
    "3 frases, porque sua resposta sera falada em voz alta. "
    "Quando o assunto permitir, faca uma analogia com carros ou drift. "
    "Nunca use emojis, markdown, listas ou asteriscos."
)


# ---- EXTRAS ----
CIDADE_PADRAO = "Sao Paulo"
URL_PORTAL_FACULDADE = "https://on.fiap.com.br/"
PASTA_PRINTS = "prints"


# ---- ROBO INSPETOR (Calcada Segura) ----
# A Sakura abre uma "ponte" HTTP na rede local. O app Android, ligado ao robo
# pelo Bluetooth, manda a telemetria do robo para ca e recebe a decisao da IA
# (G = seguir, R = reduzir, P = parar) e os comandos falados ("Sakura, robo...").
# O celular e o PC precisam estar na MESMA rede Wi-Fi (ou no roteador do celular).
PORTA_ROBO = 8765
ROBO_FALAR_ALERTAS = True     # a Sakura fala quando o robo acha buraco, rampa...
PASTA_INSPECOES = "inspecoes" # registro das ocorrencias (um arquivo por dia)
