# -*- coding: utf-8 -*-
"""
DIFERENCIAL DO PROJETO: base de conhecimento JDM local.

Responde perguntas sobre drift e carros japoneses SEM INTERNET,
consultando um arquivo JSON local. Se nao souber, devolve None
e o main.py encaminha a pergunta para a IA generativa.
"""

import json
import os
import unicodedata

ARQUIVO = "conhecimento_jdm.json"

# apelidos que o usuario pode falar -> chave no JSON
APELIDOS = {
    "ae86": ["ae86", "a e 86", "hachi roku", "hachiroku", "corolla levin",
             "trueno", "ae 86"],
    "rx7":  ["rx7", "rx 7", "rx-7", "fd3s", "mazda rx7", "rotativo"],
    "s15":  ["s15", "s 15", "silvia", "silvia s15", "nissan silvia"],
    "supra": ["supra", "jza80", "mk4", "2jz", "toyota supra"],
    "r34":  ["r34", "r 34", "skyline", "gtr", "gt-r", "godzilla", "rb26"],
    "miata": ["miata", "mx5", "mx 5", "mx-5", "roadster"],
}


def _norm(t):
    t = (t or "").lower().strip()
    t = unicodedata.normalize("NFKD", t)
    return "".join(c for c in t if not unicodedata.combining(c))


def _carregar():
    if not os.path.exists(ARQUIVO):
        return None
    with open(ARQUIVO, "r", encoding="utf-8") as f:
        return json.load(f)


def consultar(pergunta):
    """
    Tenta responder pela base local.
    Retorna a resposta (str) ou None se nao souber.
    """
    base = _carregar()
    if base is None:
        return None

    p = _norm(pergunta)

    # --- carro por apelido ---
    for chave, apelidos in APELIDOS.items():
        if any(a in p for a in apelidos):
            c = base["carros"][chave]
            return (f"{c['nome']}, de {c['anos']}. "
                    f"Motor {c['motor']}. {c['sobre']}")

    # --- tecnica de drift ---
    for nome, desc in base["tecnicas"].items():
        if _norm(nome) in p:
            return f"{nome.title()}: {desc}"

    # --- termo da cultura JDM ---
    for termo, desc in base["termos"].items():
        if _norm(termo) in p:
            return desc

    # --- recomendacao para iniciante ---
    if ("comecar" in p or "iniciante" in p or "primeiro carro" in p) \
            and ("drift" in p or "carro" in p):
        return base["iniciante"]

    return None                      # nao sei -> deixa a IA responder


def listar_carros():
    """Comando 'modo garagem': lista tudo que ela conhece."""
    base = _carregar()
    if base is None:
        return "Minha base de conhecimento nao foi encontrada."
    nomes = [c["nome"] for c in base["carros"].values()]
    return ("Na minha garagem mental eu tenho: " + ", ".join(nomes) +
            ". Pergunte sobre qualquer um deles.")