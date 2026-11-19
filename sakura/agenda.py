# -*- coding: utf-8 -*-
"""
Requisitos 2, 3 e 8 do checkpoint:
- Cadastrar evento na agenda (grava em agenda.txt no HD/SSD)
- Ler agenda
- Limpar agenda SEM excluir o arquivo
"""

import os
from datetime import datetime

from config import ARQUIVO_AGENDA


def _garantir_arquivo():
    """Cria o agenda.txt caso ainda nao exista."""
    if not os.path.exists(ARQUIVO_AGENDA):
        open(ARQUIVO_AGENDA, "w", encoding="utf-8").close()


def cadastrar_evento(descricao):
    """Grava um evento no fim do arquivo, com data e hora do cadastro."""
    descricao = (descricao or "").strip()
    if not descricao:
        return "Nao entendi o evento, entao nao cadastrei nada."

    _garantir_arquivo()
    carimbo = datetime.now().strftime("%d/%m/%Y %H:%M")

    # modo "a" = append, escreve no FIM sem apagar o que ja existe
    with open(ARQUIVO_AGENDA, "a", encoding="utf-8") as f:
        f.write(f"[{carimbo}] {descricao}\n")

    return f"Pronto! Evento cadastrado: {descricao}"


def ler_agenda():
    """Devolve todos os eventos num texto pronto para ser falado."""
    _garantir_arquivo()

    with open(ARQUIVO_AGENDA, "r", encoding="utf-8") as f:
        linhas = [l.strip() for l in f if l.strip()]

    if not linhas:
        return "Sua agenda esta vazia. Nenhum evento cadastrado."

    partes = [f"Voce tem {len(linhas)} evento(s) na agenda."]
    for i, linha in enumerate(linhas, start=1):
        # separa o carimbo do texto para a fala ficar natural
        if linha.startswith("[") and "]" in linha:
            carimbo, texto = linha[1:].split("]", 1)
            partes.append(f"Evento {i}, cadastrado em {carimbo}: {texto.strip()}.")
        else:
            partes.append(f"Evento {i}: {linha}.")

    return " ".join(partes)


def limpar_agenda():
    """
    Esvazia o conteudo MANTENDO o arquivo no disco.
    O modo "w" zera o arquivo -- nada de os.remove() aqui!
    """
    _garantir_arquivo()
    with open(ARQUIVO_AGENDA, "w", encoding="utf-8") as f:
        f.write("")
    return "Agenda limpa! O arquivo continua existindo, mas esta vazio agora."