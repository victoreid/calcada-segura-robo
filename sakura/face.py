# -*- coding: utf-8 -*-
"""
Requisito 7: "Reconhecer face" / "Quem sou eu?"

Haar Cascade (detecta ONDE esta o rosto)
   + LBPH (identifica DE QUEM e o rosto, gerando o modelo .yml)
"""

import json
import os

import cv2
import numpy as np

from config import (PASTA_DATASET, MODELO_FACE, ROTULOS_FACE,
                    LIMITE_CONFIANCA_FACE)

# classificador que ja vem junto com o OpenCV
CASCADE = cv2.CascadeClassifier(
    cv2.data.haarcascades + "haarcascade_frontalface_default.xml"
)


if CASCADE.empty():
    raise RuntimeError(
        "Haar Cascade nao carregou. Instale a versao 4 do OpenCV:\n"
        "  pip uninstall opencv-contrib-python -y\n"
        '  pip install "opencv-contrib-python<5"'
    )


# ============================================================== CAPTURA
def capturar_rostos(nome, quantidade=40):
    """Abre a webcam e salva N recortes do rosto em dataset/<nome>/."""
    pasta = os.path.join(PASTA_DATASET, nome.strip().lower().replace(" ", "_"))
    os.makedirs(pasta, exist_ok=True)

    cam = cv2.VideoCapture(0)
    if not cam.isOpened():
        return "Nao consegui abrir a webcam."

    salvas = 0
    print("Olhe para a camera e mexa levemente a cabeca. ESC para sair.")

    while salvas < quantidade:
        ok, frame = cam.read()
        if not ok:
            break

        cinza = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        faces = CASCADE.detectMultiScale(cinza, scaleFactor=1.2,
                                         minNeighbors=5, minSize=(100, 100))

        for (x, y, w, h) in faces:
            rosto = cv2.resize(cinza[y:y + h, x:x + w], (200, 200))
            cv2.imwrite(os.path.join(pasta, f"{salvas:03d}.jpg"), rosto)
            salvas += 1
            cv2.rectangle(frame, (x, y), (x + w, y + h), (0, 255, 0), 2)
            break                      # uma face por frame

        cv2.putText(frame, f"{salvas}/{quantidade}", (10, 30),
                    cv2.FONT_HERSHEY_SIMPLEX, 1, (0, 255, 0), 2)
        cv2.imshow("Capturando rostos - ESC para sair", frame)

        if cv2.waitKey(1) == 27:
            break

    cam.release()
    cv2.destroyAllWindows()
    return f"{salvas} imagens salvas em {pasta}"


# =========================================================== TREINAMENTO
def treinar():
    """Le a pasta dataset/ e gera o modelo LBPH (.yml) + mapa de rotulos."""
    if not os.path.isdir(PASTA_DATASET):
        return "Pasta 'dataset' nao encontrada. Capture rostos primeiro."

    amostras, rotulos, mapa = [], [], {}
    proximo_id = 0

    for pessoa in sorted(os.listdir(PASTA_DATASET)):
        caminho = os.path.join(PASTA_DATASET, pessoa)
        if not os.path.isdir(caminho):
            continue

        mapa[proximo_id] = pessoa.replace("_", " ").title()

        for arq in os.listdir(caminho):
            if not arq.lower().endswith((".jpg", ".jpeg", ".png")):
                continue
            img = cv2.imread(os.path.join(caminho, arq), cv2.IMREAD_GRAYSCALE)
            if img is None:
                continue
            amostras.append(cv2.resize(img, (200, 200)))
            rotulos.append(proximo_id)

        proximo_id += 1

    if not amostras:
        return "Nao ha imagens suficientes para treinar."

    reconhecedor = cv2.face.LBPHFaceRecognizer_create(
        radius=1, neighbors=8, grid_x=8, grid_y=8
    )
    reconhecedor.train(amostras, np.array(rotulos))
    reconhecedor.write(MODELO_FACE)          # <-- gera o arquivo .yml

    with open(ROTULOS_FACE, "w", encoding="utf-8") as f:
        json.dump(mapa, f, ensure_ascii=False, indent=2)

    return (f"Modelo treinado com {len(amostras)} imagens de "
            f"{len(mapa)} pessoa(s) e salvo em {MODELO_FACE}.")


# ======================================================== RECONHECIMENTO
def _carregar():
    if not os.path.exists(MODELO_FACE) or not os.path.exists(ROTULOS_FACE):
        return None, None
    rec = cv2.face.LBPHFaceRecognizer_create()
    rec.read(MODELO_FACE)
    with open(ROTULOS_FACE, "r", encoding="utf-8") as f:
        mapa = {int(k): v for k, v in json.load(f).items()}
    return rec, mapa


def reconhecer(tempo_max=12):
    """Abre a webcam e identifica quem esta na frente."""
    reconhecedor, mapa = _carregar()
    if reconhecedor is None:
        return ("Ainda nao tenho o modelo treinado. "
                "Rode o arquivo treinar_face ponto p y antes.")

    cam = cv2.VideoCapture(0)
    if not cam.isOpened():
        return "Nao consegui abrir a webcam."

    votos = {}
    frames = 0
    limite_frames = tempo_max * 10

    while frames < limite_frames:
        ok, frame = cam.read()
        if not ok:
            break
        frames += 1

        cinza = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        faces = CASCADE.detectMultiScale(cinza, scaleFactor=1.2,
                                         minNeighbors=5, minSize=(100, 100))

        for (x, y, w, h) in faces:
            rosto = cv2.resize(cinza[y:y + h, x:x + w], (200, 200))
            id_previsto, confianca = reconhecedor.predict(rosto)

            # LBPH: quanto MENOR a confianca, MELHOR o acerto
            if confianca <= LIMITE_CONFIANCA_FACE:
                nome = mapa.get(id_previsto, "Desconhecido")
            else:
                nome = "Desconhecido"

            # votacao: o nome que mais aparecer nos frames vence
            votos[nome] = votos.get(nome, 0) + 1

            cor = (0, 255, 0) if nome.lower() != "desconhecido" else (0, 0, 255)
            cv2.rectangle(frame, (x, y), (x + w, y + h), cor, 2)
            cv2.putText(frame, f"{nome} ({int(confianca)})", (x, y - 10),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.8, cor, 2)

        cv2.imshow("Reconhecimento facial - ESC para sair", frame)
        if cv2.waitKey(1) == 27:
            break
        if votos and max(votos.values()) >= 15:
            break

    cam.release()
    cv2.destroyAllWindows()

    if not votos:
        return "Nao identifiquei nenhum rosto na frente da camera."

    nome = max(votos, key=votos.get)
    if nome.lower() == "desconhecido":
        return "Nao reconheci essa pessoa. Voce nao esta cadastrado no meu modelo."
    return f"Voce e {nome}. Que bom te ver de novo!"