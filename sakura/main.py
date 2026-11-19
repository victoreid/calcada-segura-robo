# -*- coding: utf-8 -*-
"""
SAKURA - Assistente Virtual JDM
Checkpoint 4 - 2º semestre

REGRA DE OURO (enunciado):
Nenhum comando e executado sem que a palavra de ativacao seja falada
antes. Caso contrario, o texto reconhecido e apenas IMPRESSO na tela.
"""

import agenda
import extras
import ferramentas
import ia
import jdm
import robo
from config import NOME_ASSISTENTE, PALAVRAS_ATIVACAO
from voz import falar, ouvir, normalizar


# ============================================================ ATIVACAO
def extrair_comando(texto):
    """
    Verifica se a palavra de ativacao foi dita.
    Aceita gatilhos com mais de uma palavra.
    Retorna (True, resto_do_comando) ou (False, None).
    """
    limpo = normalizar(texto)
    palavras = [p.strip(",.!?") for p in limpo.split()]

    gatilhos = sorted(PALAVRAS_ATIVACAO, key=lambda g: -len(g.split()))

    for i in range(len(palavras)):
        for gatilho in gatilhos:
            tokens = gatilho.split()
            if palavras[i:i + len(tokens)] == tokens:
                resto = " ".join(palavras[i + len(tokens):]).strip(" ,.!?")
                return True, resto

    return False, None


def contem(texto, *chaves):
    """Atalho: verifica se alguma das chaves aparece no texto."""
    return any(c in texto for c in chaves)


def depois_de(texto, *marcadores):
    """Pega o que vem depois de um marcador. Ex.: 'sobre gatos' -> 'gatos'."""
    for m in marcadores:
        if m in texto:
            return texto.split(m, 1)[1].strip(" ,.!?")
    return ""


# ==================================================== ROTEADOR DE COMANDOS
def executar(comando):
    """Executa o comando. Retorna False para encerrar o programa."""

    if not comando:
        falar("Pois nao? Estou ouvindo.")
        comando = normalizar(ouvir(timeout=8, limite=10))
        if not comando:
            return True

    # ------------------------- ROBO INSPETOR (Calcada Segura) via app Android
    # "Sakura, robo modo automatico", "Sakura, robo parar",
    # "Sakura, como esta a calcada?", "Sakura, analisa a calcada do robo"...
    resposta_robo = robo.comando_de_voz(comando, robo.inspecao)
    if resposta_robo:
        falar(resposta_robo)
        return True

    # ---------------------------------------------------- ENCERRAR
    if contem(comando, "encerrar", "tchau", "desligar", "ate logo", "sair"):
        falar("Ate mais! Encerrando o sistema.")
        return False

    # ------------------------------------ (2) CADASTRAR EVENTO NA AGENDA
    if contem(comando, "cadastrar evento", "cadastrar na agenda", "agendar",
              "novo evento", "marcar evento", "anotar na agenda"):
        falar("Ok, qual evento devo cadastrar?")
        evento = ouvir(timeout=10, limite=15)
        if not evento:
            falar("Nao consegui ouvir o evento. Tente de novo.")
        else:
            falar(agenda.cadastrar_evento(evento))
        return True

    # ------------------------------------------------ (8) LIMPAR AGENDA
    if contem(comando, "limpar agenda", "limpar a agenda", "apagar agenda",
              "esvaziar agenda", "apagar os eventos"):
        falar(agenda.limpar_agenda())
        return True

    # --------------------------------------------------- (3) LER AGENDA
    if contem(comando, "ler agenda", "ler a agenda", "minha agenda",
              "ver agenda", "mostrar agenda", "quais eventos"):
        falar(agenda.ler_agenda())
        return True

    # ---------------------------------------------- (4) QUE HORAS SAO
    if contem(comando, "que horas", "horas sao", "hora certa", "me diz a hora"):
        falar(ferramentas.que_horas_sao())
        return True

    # ----------------------------------------------- (5) QUE DIA E HOJE
    if contem(comando, "que dia", "data de hoje", "dia e hoje", "qual a data"):
        falar(ferramentas.que_dia_e_hoje())
        return True

    # ------------------------------------------------- (6) CALCULADORA
    if contem(comando, "calcular", "calcule", "calcula",
              "quanto e", "quanto sao", "raiz quadrada"):
        falar(ferramentas.calcular(comando))
        return True

    # -------------------------------------- (7) RECONHECIMENTO FACIAL
    if contem(comando, "reconhecer face", "reconhecer rosto", "quem sou eu",
              "me reconhece", "reconhecimento facial"):
        falar("Olhe para a camera, por favor.")
        import face
        falar(face.reconhecer())
        return True

    # ==================== (10) COMANDOS EXTRAS ====================
    # ATENCAO: YouTube vem ANTES das cotacoes, senao um pedido como
    # "video sobre dolar no YouTube" cairia no bloco de cotacao.

    # ------------------------------------------------- YOUTUBE
    if contem(comando, "youtube", "you tube"):
        termo = depois_de(comando, "video sobre ", "sobre ",
                          "youtube ", "you tube ") or comando
        falar(extras.tocar_youtube(termo))
        return True

    # ------------------------------------------------- SPOTIFY
    if contem(comando, "spotify", "tocar a musica", "tocar musica"):
        termo = depois_de(comando, "musica ", "spotify ") or comando
        falar(extras.tocar_spotify(termo.replace("no spotify", "").strip()))
        return True

    # ------------------------------------------- PREVISAO DO TEMPO
    if contem(comando, "previsao do tempo", "vai chover", "temperatura",
              "como esta o tempo", "previsao"):
        cidade = depois_de(comando, "para ", "em ", "de ")
        falar(extras.previsao_tempo(cidade or None))
        return True

    # ------------------------------------------------- COTACOES
    if contem(comando, "dolar"):
        falar(extras.valor_dolar())
        return True
    if contem(comando, "bitcoin", "bit coin"):
        falar(extras.valor_bitcoin())
        return True
    if contem(comando, "euro"):
        falar(extras.valor_euro())
        return True

    # ------------------------------------------------- NAVEGADOR
    if contem(comando, "portal da faculdade", "abrir o portal"):
        falar(extras.abrir_portal())
        return True

    if contem(comando, "pesquisar no google", "pesquisa no google",
              "buscar no google"):
        termo = depois_de(comando, "google ", "sobre ", "por ")
        if not termo:
            falar("O que voce quer pesquisar?")
            termo = ouvir(timeout=8, limite=10)
        falar(extras.pesquisar_google(termo))
        return True

    # ------------------------------------------------- SISTEMA
    if contem(comando, "aumentar volume", "aumenta o volume",
              "volume mais alto"):
        falar(extras.ajustar_volume("aumentar"))
        return True

    if contem(comando, "diminuir volume", "abaixar volume",
              "diminui o volume"):
        falar(extras.ajustar_volume("diminuir"))
        return True

    if contem(comando, "print da tela", "printar", "captura de tela",
              "tirar um print"):
        falar(extras.tirar_print())
        return True

    # ---------------------------------------------------- AJUDA
    if contem(comando, "o que voce faz", "seus comandos", "ajuda"):
        falar("Sei cadastrar, ler e limpar sua agenda, dizer as horas e a "
              "data, fazer calculos, reconhecer seu rosto, consultar clima, "
              "dolar e bitcoin, abrir o YouTube, tirar prints e responder "
              "qualquer pergunta com inteligencia artificial. "
              "Tambem comando o robo inspetor de calcadas: posso iniciar a "
              "inspecao automatica, parar o robo e dar o relatorio da calcada. "
              "Sou especialista em drift e carros japoneses, pode testar.")
        return True

    # ============ DIFERENCIAL: BASE DE CONHECIMENTO JDM LOCAL ============
    if contem(comando, "modo garagem", "quais carros voce conhece",
              "sua garagem"):
        falar(jdm.listar_carros())
        return True

    # Consulta a base local ANTES de gastar chamada de API.
    # Responde instantaneamente e funciona ate sem internet.
    resposta_jdm = jdm.consultar(comando)
    if resposta_jdm:
        falar(resposta_jdm)
        return True

    # ------------------------------------- (9) FALLBACK: IA GENERATIVA
    # Qualquer comando que nao casou acima vira pergunta pra IA.
    # Assim a assistente NUNCA responde "nao sei fazer isso".
    falar(ia.perguntar(comando))
    return True


# ================================================================= LOOP
def main():
    print("=" * 58)
    print(f"  {NOME_ASSISTENTE.upper()} - Assistente Virtual JDM")
    print("=" * 58)
    print(f"  Diga '{NOME_ASSISTENTE}' antes de qualquer comando.")
    print(f"  Ex.: '{NOME_ASSISTENTE}, o que e um AE86?'")
    print("  CTRL + C para encerrar.")
    try:
        endereco = robo.iniciar_servidor(falar)
        print(f"  Robo inspetor: no app, toque em Sakura e digite {endereco}")
    except OSError as e:
        print(f"  (ponte com o robo desligada: {e})")
    print("=" * 58)

    falar(f"{NOME_ASSISTENTE} online. Sou uma Iá feita "
          "especialista em drift e carros japoneses, mas resolvo "
          "o seu dia a dia tambem. Fale meu nome antes de qualquer comando.")

    while True:
        try:
            texto = ouvir(timeout=None, limite=10)
            if not texto:
                continue

            ativou, comando = extrair_comando(texto)

            if not ativou:
                # REGRA DO ENUNCIADO: sem ativacao, apenas imprime
                print(f"[ignorado] {texto}   (sem palavra de ativacao)")
                continue

            if not executar(comando):
                break

        except KeyboardInterrupt:
            print("\nEncerrando...")
            break
        except Exception as e:
            print(f"[ERRO] {e}")
            continue


if __name__ == "__main__":
    main()