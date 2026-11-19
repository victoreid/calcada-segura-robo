# -*- coding: utf-8 -*-
import json
import os
import cv2
import numpy as np
from config import PASTA_DATASET, MODELO_FACE, ROTULOS_FACE

print("=" * 55)
print("1) O QUE TEM NO DATASET")
print("=" * 55)
if not os.path.isdir(PASTA_DATASET):
    print("  Pasta 'dataset' NAO EXISTE!")
else:
    for p in sorted(os.listdir(PASTA_DATASET)):
        caminho = os.path.join(PASTA_DATASET, p)
        if os.path.isdir(caminho):
            n = len([a for a in os.listdir(caminho) if a.endswith(".jpg")])
            print(f"  {p:25} -> {n} fotos")

print("\n" + "=" * 55)
print("2) ARQUIVOS DO MODELO")
print("=" * 55)
print(f"  {MODELO_FACE}: {'OK' if os.path.exists(MODELO_FACE) else 'NAO EXISTE'}")
print(f"  {ROTULOS_FACE}: {'OK' if os.path.exists(ROTULOS_FACE) else 'NAO EXISTE'}")
if os.path.exists(ROTULOS_FACE):
    with open(ROTULOS_FACE, encoding="utf-8") as f:
        print("  rotulos:", json.load(f))

print("\n" + "=" * 55)
print("3) TESTE AO VIVO - olhe para a camera. ESC para sair.")
print("=" * 55)

rec = cv2.face.LBPHFaceRecognizer_create()
rec.read(MODELO_FACE)
with open(ROTULOS_FACE, encoding="utf-8") as f:
    mapa = {int(k): v for k, v in json.load(f).items()}

CASCADE = cv2.CascadeClassifier(
    cv2.data.haarcascades + "haarcascade_frontalface_default.xml")

cam = cv2.VideoCapture(0)
leituras = []

while True:
    ok, frame = cam.read()
    if not ok:
        break
    cinza = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    faces = CASCADE.detectMultiScale(cinza, 1.2, 5, minSize=(100, 100))

    for (x, y, w, h) in faces:
        rosto = cv2.resize(cinza[y:y+h, x:x+w], (200, 200))
        idp, conf = rec.predict(rosto)
        nome = mapa.get(idp, "?")
        leituras.append((nome, conf))
        print(f"  previsto: {nome:20} confianca: {conf:6.1f}")
        cv2.rectangle(frame, (x, y), (x+w, y+h), (0, 255, 0), 2)
        cv2.putText(frame, f"{nome} {conf:.0f}", (x, y-10),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 255, 0), 2)

    cv2.imshow("Diagnostico - ESC para sair", frame)
    if cv2.waitKey(1) == 27:
        break

cam.release()
cv2.destroyAllWindows()

if leituras:
    confs = [c for _, c in leituras]
    print(f"\n  RESUMO: menor={min(confs):.1f}  media={sum(confs)/len(confs):.1f}  maior={max(confs):.1f}")