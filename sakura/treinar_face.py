# -*- coding: utf-8 -*-
"""
Utilitario para preparar o reconhecimento facial.

  python treinar_face.py capturar "Joao Pedro"
  python treinar_face.py capturar "desconhecido"
  python treinar_face.py treinar
  python treinar_face.py testar
"""

import sys
import face

if len(sys.argv) < 2:
    print(__doc__)
    sys.exit()

acao = sys.argv[1].lower()

if acao == "capturar":
    if len(sys.argv) < 3:
        print('Uso: python treinar_face.py capturar "Nome da Pessoa"')
        sys.exit()
    qtd = int(sys.argv[3]) if len(sys.argv) > 3 else 40
    print(face.capturar_rostos(sys.argv[2], qtd))

elif acao == "treinar":
    print(face.treinar())

elif acao == "testar":
    print(face.reconhecer())

else:
    print(__doc__)