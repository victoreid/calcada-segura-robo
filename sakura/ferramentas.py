# -*- coding: utf-8 -*-
"""
Requisitos 4, 5 e 6 do checkpoint:
- Que horas sao?
- Que dia e hoje?
- Calcular ... (adicao, subtracao, multiplicacao e divisao)
"""

import re
from datetime import datetime

DIAS = ["segunda-feira", "terca-feira", "quarta-feira", "quinta-feira",
        "sexta-feira", "sabado", "domingo"]

MESES = ["janeiro", "fevereiro", "marco", "abril", "maio", "junho", "julho",
         "agosto", "setembro", "outubro", "novembro", "dezembro"]


# ============================================================ HORA E DATA
def que_horas_sao():
    agora = datetime.now()
    return f"Agora sao {agora.hour} horas e {agora.minute} minutos."


def que_dia_e_hoje():
    hoje = datetime.now()
    return (f"Hoje e {DIAS[hoje.weekday()]}, "
            f"dia {hoje.day} de {MESES[hoje.month - 1]} de {hoje.year}.")


# =========================================================== CALCULADORA
NUMEROS_EXTENSO = {
    "zero": "0", "um": "1", "uma": "1", "dois": "2", "duas": "2", "tres": "3",
    "quatro": "4", "cinco": "5", "seis": "6", "sete": "7", "oito": "8",
    "nove": "9", "dez": "10", "onze": "11", "doze": "12", "treze": "13",
    "quatorze": "14", "catorze": "14", "quinze": "15", "dezesseis": "16",
    "dezessete": "17", "dezoito": "18", "dezenove": "19", "vinte": "20",
    "trinta": "30", "quarenta": "40", "cinquenta": "50", "sessenta": "60",
    "setenta": "70", "oitenta": "80", "noventa": "90", "cem": "100",
    "mil": "1000", "meio": "0.5",
}

# Expressoes de duas palavras: tratadas ANTES das simples
OPERADORES_COMPOSTOS = [
    ("dividido por", "/"), ("dividido pelo", "/"), ("dividir por", "/"),
    ("multiplicado por", "*"), ("multiplicar por", "*"),
    ("elevado a", "**"), ("por cento de", "/100*"),
]

OPERADORES_SIMPLES = {
    "mais": "+", "soma": "+", "somar": "+", "adicionar": "+", "com": "+",
    "menos": "-", "subtrair": "-",
    "vezes": "*", "multiplicar": "*", "x": "*", "por": "*",
    "dividido": "/", "dividir": "/", "sobre": "/",
    "elevado": "**",
}


def calcular(frase):
    """
    Converte uma frase falada em expressao matematica e resolve.
    Aceita palavras ('vezes') e simbolos ('x', '/'), porque o Google
    transcreve de um jeito diferente a cada vez.
    """
    expr = frase.lower()

    # --- raiz quadrada tem tratamento proprio ---
    raiz = re.search(r"raiz quadrada de\s+([\d\.,]+)", expr)
    if raiz:
        valor = float(raiz.group(1).replace(",", "."))
        if valor < 0:
            return "Nao existe raiz quadrada real de numero negativo."
        return f"A raiz quadrada de {_fmt(valor)} e {_fmt(valor ** 0.5)}."

    # --- operadores de duas palavras primeiro ---
    for texto, simbolo in OPERADORES_COMPOSTOS:
        expr = expr.replace(texto, f" {simbolo} ")

    # --- separa simbolos grudados: '15+27' -> '15 + 27' ---
    expr = re.sub(r"([+\-*/()])", r" \1 ", expr)
    expr = re.sub(r"(?<=\d)\s*x\s*(?=\d)", " * ", expr)

    # --- monta a conta token a token, IGNORANDO palavras desconhecidas ---
    partes = []
    for token in expr.split():
        token = token.strip(",.!?").strip()
        if not token:
            continue
        if re.fullmatch(r"\d+([.,]\d+)?", token):        # numero
            partes.append(token.replace(",", "."))
            continue
        if token in NUMEROS_EXTENSO:                     # numero por extenso
            partes.append(NUMEROS_EXTENSO[token])
            continue
        if token in OPERADORES_SIMPLES:                  # operador em palavra
            partes.append(OPERADORES_SIMPLES[token])
            continue
        if re.fullmatch(r"[+\-*/()]+", token):           # operador em simbolo
            partes.append(token)
            continue
        # qualquer outra palavra ('calcular', 'como', 'quanto') e descartada

    expr = "".join(partes)

    if not expr or not re.search(r"\d", expr):
        return "Nao entendi a conta. Tente: calcular 8 vezes 4."

    # TRAVA DE SEGURANCA: nunca rode eval() em texto livre!
    if not re.fullmatch(r"[0-9\.\+\-\*/\(\)]+", expr):
        return "Nao consegui montar a conta."

    try:
        resultado = eval(expr, {"__builtins__": {}}, {})
    except ZeroDivisionError:
        return "Nao e possivel dividir por zero."
    except Exception:
        return "Nao consegui resolver essa conta. Pode repetir?"

    return f"O resultado de {_falar_expr(expr)} e {_fmt(resultado)}."


def _fmt(n):
    """Formata o numero para a fala (evita '4.0')."""
    if isinstance(n, float):
        if n.is_integer():
            return str(int(n))
        return str(round(n, 4)).replace(".", " virgula ")
    return str(n)


def _falar_expr(expr):
    """Transforma '12*3' em '12 vezes 3' para ela repetir a conta."""
    for k, v in {"**": " elevado a ", "+": " mais ", "-": " menos ",
                 "*": " vezes ", "/": " dividido por "}.items():
        expr = expr.replace(k, v)
    return expr