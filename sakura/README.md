# 🌸 SAKURA — Assistente Virtual por Voz

**Checkpoint 4 — Projeto F.R.I.D.A.Y. — 2º semestre**

Assistente virtual controlada por voz, desenvolvida em Python, no estilo Alexa/Siri.
Reconhece comandos falados, responde por síntese de voz, gerencia uma agenda em
arquivo, identifica rostos pela webcam e integra IA generativa.

**Diferencial:** a Sakura é especialista em **drift e cultura automotiva japonesa (JDM)**.
Além da personalidade aplicada à IA generativa, o projeto conta com uma **base de
conhecimento local** que responde sobre carros e técnicas de drift instantaneamente,
sem consumir API e **sem depender de internet**.

---

## 👥 Integrantes

| Nome | RM |
|------|-----|
| Enzo Goulart | RM99666 |
| João Pedro Cruz | RM98650 |
| Tiago Paulino | RM551169 |
| Victor Eid | RM98668 |

---

## 📋 Requisitos do enunciado

| # | Requisito | Implementação |
|---|-----------|---------------|
| 1 | Palavra de ativação | `main.py` → `extrair_comando()` |
| 2 | Cadastrar evento na agenda | `agenda.py` → `cadastrar_evento()` |
| 3 | Ler agenda | `agenda.py` → `ler_agenda()` |
| 4 | "Que horas são?" | `ferramentas.py` → `que_horas_sao()` |
| 5 | "Que dia é hoje?" | `ferramentas.py` → `que_dia_e_hoje()` |
| 6 | Calcular (+ − × ÷) | `ferramentas.py` → `calcular()` |
| 7 | Reconhecer face / "Quem sou eu?" | `face.py` (Haar Cascade + LBPH) |
| 8 | Limpar agenda sem excluir o arquivo | `agenda.py` → `limpar_agenda()` |
| 9 | Integração com IA generativa | `ia.py` (Google Gemini) |
| 10 | Comandos extras | `extras.py` e `jdm.py` |

### ⚠️ Regra de ouro

> Nenhum comando é executado sem que a palavra de ativação seja falada antes.
> Caso contrário, o texto reconhecido é apenas **impresso na tela**, sem ação alguma.

Implementado no loop principal do `main.py`:

```python
if not ativou:
    print(f"[ignorado] {texto}   (sem palavra de ativação)")
    continue
```

---

## 🛠️ Tecnologias

| Biblioteca | Função |
|---|---|
| SpeechRecognition | Reconhecimento de fala (STT) |
| pyttsx3 | Síntese de voz (TTS) |
| PyAudio | Captura do microfone |
| OpenCV (contrib) | Detecção e reconhecimento facial |
| NumPy | Processamento das imagens |
| Requests | Chamadas às APIs |
| PyAutoGUI | Controle de volume e screenshot |
| PyWhatKit | Integração com o YouTube |
| Google Gemini API | IA generativa |

---

## 📦 Instalação

> **⚠️ Use Python 3.12.** O PyAudio e o OpenCV ainda não têm build para o
> Python 3.14 e falham na compilação.

```bash
# clonar o repositório
git clone https://github.com/victoreid/calcada-segura-robo.git
cd calcada-segura-robo/sakura

# ambiente virtual (força a versão 3.12)
py -3.12 -m venv .venv
.venv\Scripts\activate

# dependências
pip install -r requirements.txt
```

> **⚠️ O OpenCV precisa ser o `contrib` e versão 4.x.** O pacote comum não traz
> o módulo `cv2.face` (LBPH), e a versão 5.0 removeu os arquivos Haar Cascade.
> Isso já está travado no `requirements.txt`.

### Configurar a chave da IA

1. Gere uma chave gratuita em <https://aistudio.google.com/app/apikey>
2. Defina a variável de ambiente `GEMINI_API_KEY` (PowerShell: `$env:GEMINI_API_KEY = "sua_chave"`)
   ou cole no `config.py` (sem enviar para o GitHub)

Se o modelo retornar erro **404** (descontinuado) ou **503** (sobrecarregado),
troque o valor de `GEMINI_MODELO`. Modelos testados e funcionais:

```
gemini-3.5-flash-lite     ← recomendado (menor fila)
gemini-3.1-flash-lite
gemini-flash-latest
```

---

## 👤 Preparar o reconhecimento facial

O modelo `.yml` precisa ser treinado antes do primeiro uso:

```bash
python treinar_face.py capturar "Seu Nome"       # ~40 fotos suas
python treinar_face.py capturar "desconhecido"   # fotos de OUTRAS pessoas
python treinar_face.py treinar                   # gera modelo_lbph.yml
python treinar_face.py testar                    # valida
```

**A classe "desconhecido" é obrigatória.** O LBPH sempre escolhe a classe mais
próxima entre as que conhece — sem uma classe de contraste, ele identifica
qualquer pessoa como o usuário cadastrado.

Dicas para melhorar a precisão:

- Varie ângulo, expressão e iluminação durante a captura
- Mantenha as duas classes com quantidade parecida de fotos
- Capture na **mesma iluminação** em que a apresentação vai ocorrer
- No LBPH, **quanto menor a confiança, melhor o acerto**. Ajuste
  `LIMITE_CONFIANCA_FACE` no `config.py` conforme os valores obtidos

---

## ▶️ Como usar

```bash
python main.py
```

Fale sempre o nome da assistente **antes** do comando:

> "**Sakura**, que horas são?"

---

## 🎙️ Comandos disponíveis

### Agenda
- "Sakura, cadastrar evento na agenda"
- "Sakura, ler agenda"
- "Sakura, limpar agenda"

### Informações
- "Sakura, que horas são?"
- "Sakura, que dia é hoje?"
- "Sakura, calcular 25 vezes 4 mais 10"
- "Sakura, qual a previsão do tempo para São Paulo?"
- "Sakura, qual o valor do dólar hoje?"
- "Sakura, quanto vale um bitcoin hoje?"

### Visão computacional
- "Sakura, quem sou eu?"
- "Sakura, reconhecer face"

### Sistema e web
- "Sakura, abra o YouTube e rode um vídeo sobre drift"
- "Sakura, tocar a música Tokyo Drift no Spotify"
- "Sakura, pesquisar no Google sobre redes neurais"
- "Sakura, abrir o portal da faculdade"
- "Sakura, aumentar volume" / "diminuir volume"
- "Sakura, tirar um print da tela"

### 🏁 Base JDM (funciona offline)
- "Sakura, o que é um AE86?"
- "Sakura, me explica o clutch kick"
- "Sakura, o que significa touge?"
- "Sakura, qual carro é bom para começar no drift?"
- "Sakura, modo garagem"

### 🤖 Robô inspetor de calçadas (Calçada Segura)
A Sakura abre uma ponte na rede local (`robo.py`, porta 8765) e conversa com o app
Android do robô. Ela recebe os sensores (trepidação e inclinação do MPU-6050, sonares),
decide **seguir / reduzir / parar** e fala os alertas.
- "Sakura, robô modo automático" / "Sakura, robô parar" / "Sakura, robô modo manual"
- "Sakura, robô buzina" / "Sakura, recalibrar o robô"
- "Sakura, como está a calçada?" (relatório da inspeção)
- "Sakura, analisa a calçada do robô" (avaliação pelo Gemini)
- "Sakura, robô nova inspeção" (zera o relatório)

Só a ponte, sem microfone: `python robo.py`. Testes: `python -m unittest test_robo -v`.

### IA generativa
Qualquer pergunta que não corresponda aos comandos acima é encaminhada
automaticamente ao Gemini — a assistente **nunca** responde "não sei fazer isso".

Para encerrar: "Sakura, tchau" ou `CTRL + C`.

---

## 📁 Estrutura

```
sakura/
├── main.py                  # loop principal e roteador de comandos
├── config.py                # configurações centrais
├── voz.py                   # camada de voz (STT + TTS)
├── agenda.py                # requisitos 2, 3 e 8
├── ferramentas.py           # requisitos 4, 5 e 6
├── face.py                  # requisito 7
├── ia.py                    # requisito 9
├── extras.py                # requisito 10
├── jdm.py                   # base de conhecimento local
├── robo.py                  # ponte com o robô inspetor (app Android)
├── test_robo.py             # testes da ponte
├── conhecimento_jdm.json    # dados de carros, técnicas e termos
├── treinar_face.py          # utilitário de captura e treino
├── diagnostico_face.py      # diagnóstico do reconhecimento facial
└── requirements.txt
```

Gerados em execução: `agenda.txt`, `modelo_lbph.yml`, `rotulos_face.json`,
`dataset/`, `prints/`.

---

## 🧠 Decisões de projeto

**Fallback para IA generativa.** Comandos não reconhecidos vão para o Gemini em
vez de retornar erro. Mesmo quando o reconhecimento de fala transcreve algo
inesperado, a assistente responde de forma coerente.

**Base de conhecimento local.** Perguntas sobre JDM são resolvidas por um JSON
local, consultado *antes* da API. Resposta instantânea, custo zero e
funcionamento garantido mesmo sem conexão.

**Segurança na calculadora.** A expressão passa por uma validação por regex
antes do `eval()`, aceitando exclusivamente caracteres matemáticos — o que
impede execução de código arbitrário via comando de voz.

**Parser tolerante a variações.** O reconhecedor transcreve a mesma frase de
formas diferentes ("vezes" ou "x", "dividido por" ou "/"). O parser monta a
expressão token a token e descarta palavras desconhecidas, em vez de rejeitar
a entrada inteira.

**Votação por frames no reconhecimento facial.** A identificação considera
dezenas de quadros e escolhe o resultado majoritário, evitando erro por um
único frame ruim.

**Retry com backoff na API.** Erros 429, 500 e 503 são reprocessados com espera
crescente (2s, 4s), tratando a instabilidade do servidor sem quebrar o fluxo.

---

## ⚠️ Solução de problemas

| Sintoma | Causa e solução |
|---|---|
| `module 'cv2' has no attribute 'face'` | Instale `opencv-contrib-python`, não o `opencv-python` |
| `Can't open file: haarcascade_*.xml` | OpenCV 5.0 removeu os arquivos. Use `opencv-contrib-python<5` |
| `Microsoft Visual C++ 14.0 is required` | Python 3.14 sem build do PyAudio. Use o 3.12 |
| A voz só fala na primeira vez | Bug do `runAndWait()`. O `voz.py` cria um motor novo a cada fala |
| Erro 503 na IA | Servidor sobrecarregado. Troque para um modelo `-lite` |
| Erro 404 na IA | Modelo descontinuado. Verifique `GEMINI_MODELO` |
| Reconhece a pessoa errada | Reequilibre o dataset e reduza `LIMITE_CONFIANCA_FACE` |
| Não escuta nada | Verifique o microfone padrão e aumente `ENERGIA_MIC` |
| Ela ouve a si mesma | Use fone de ouvido — o microfone capta a própria saída |

---

## 📌 Observações

- O reconhecimento de fala e a IA generativa requerem conexão com a internet.
- A chave da API **não está versionada**. Cada usuário deve gerar a sua.
- O LBPH é um algoritmo de 2006 e tem precisão limitada com poucas amostras.
  A classe "desconhecido" e o limiar de confiança mitigam falsos positivos.
