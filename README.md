# Calçada Segura: robô inspetor de calçadas

FIAP · Global Solution 2026.2 · CitizenTech · **Project-Based Maker Lab**

> **Vídeo (até 6 min):** _a completar com o link do YouTube_

## Integrantes

| Nome completo | RM |
|---|---|
| Victor Eid Carbutti Nicolas | RM98668 |
| João Pedro Cruz | RM98650 |
| Enzo Luiz Goulart | RM99666 |
| Tiago Paulino | RM551169 |

## O problema (CitizenTech)

Buracos, degraus e rampas fora da norma tornam as calçadas de São Paulo perigosas, principalmente para quem usa cadeira de rodas, anda com dificuldade ou empurra um carrinho de bebê. A **NBR 9050** limita a inclinação das rampas a **8,33%**. O **Calçada Segura** mapeia esses problemas. Este robô percorre a calçada, mede o que sente e **para sozinho** diante do perigo.

## A solução

Evoluímos o robô que já tínhamos (Arduino Uno, chassi 4WD, HC-SR04 e Bluetooth):

- **Sensor novo: MPU-6050** (acelerômetro e giroscópio). Mede a **inclinação** do piso (em %, comparada com o limite de 8,33%) e a **trepidação** (piso irregular).
- **2º sonar (HC-SR04) apontado para o chão a 45°:** detecta **buraco ou degrau para baixo**.
- **Modo automático:** o robô anda sozinho, **para** antes de obstáculo ou buraco, **reduz** a velocidade em piso irregular e **avisa** quando a rampa passa da norma.
- **App Android** que controla o robô pelo Bluetooth e mostra os sensores.
- **Segurança:** se o Bluetooth cair, o robô para sozinho em 1,5 s.
- **Integração com IA (Sakura):** a assistente de voz do grupo recebe os sensores pelo app, decide **seguir, reduzir ou parar**, fala os alertas e aceita comandos de voz ("Sakura, robô modo automático").

## Estrutura do repositório

| Pasta | O que tem |
|---|---|
| [`firmware/robo_completo/`](firmware/robo_completo/robo_completo.ino) | Programa do Arduino: controle, modo automático, sonares, MPU-6050, buzzer e telemetria |
| [`app_android/`](app_android/README.md) | App Android (Kotlin) que controla o robô pelo Bluetooth e conversa com a Sakura pelo Wi-Fi |
| [`sakura/`](sakura/README.md) | Sakura, a assistente de voz do grupo (Python), com a ponte para o robô em [`robo.py`](sakura/robo.py) |

## Componentes

| Peça | Função |
|---|---|
| Arduino Uno R3 (CH340) | Cérebro do robô |
| Ponte H L298N + 4 motores DC + chassi 4WD | Movimento |
| 4 pilhas AA (6 V) | Energia dos motores |
| Power bank (USB) | Energia do Arduino |
| HC-SR04 (frente) | Obstáculo à frente |
| HC-SR04 (chão, a 45°) | Buraco ou degrau para baixo |
| **MPU-6050 (GY-521)** | **Sensor novo:** inclinação e trepidação |
| Buzzer passivo | Alertas sonoros (melodia ao achar buraco) |
| HC-05 | Bluetooth clássico (celular ou notebook) |
| Resistores de 1 kΩ e 2 kΩ | Divisor de tensão no RXD do HC-05 (5 V → 3,3 V) |

## Ligações

| Peça | Pino da peça | Pino do Arduino |
|---|---|---|
| L298N | ENA / ENB | D5 / D6 (PWM) |
| | IN1, IN2, IN3, IN4 | D7, D8, D9, D12 |
| | GND | GND (comum com as pilhas) |
| HC-SR04 frente | TRIG / ECHO | D2 / D3 |
| HC-SR04 chão | TRIG / ECHO | D4 / A0 |
| MPU-6050 | SDA / SCL | A4 / A5 |
| Buzzer | I/O | A1 |
| HC-05 | TXD | D0 (RX) |
| | RXD | D1 (TX), pelo divisor: 1 kΩ em série e 2 kΩ para o GND |

## Como gravar o firmware

1. Abra `firmware/robo_completo/robo_completo.ino` na Arduino IDE.
2. **Tire o fio do TXD do HC-05 do D0.** O D0 e o D1 são os mesmos pinos do cabo USB.
3. Escolha **Ferramentas → Placa: Arduino Uno** e a porta COM do Arduino. Clique em **Carregar**.
4. Recoloque o fio no D0. Ligue o robô **no chão** e deixe parado 2 s, até o bipe (calibração).

## Como usar o app Android

Veja [`app_android/README.md`](app_android/README.md). Resumo: instale o APK, pareie o HC-05 no celular (PIN 1234), abra o app e toque em **Conectar ao robô**.

## Protocolo (Bluetooth, 9600 baud, 1 letra por comando)

| Letra | Ação |
|---|---|
| W / S | frente / ré |
| A / D | girar para a esquerda / direita |
| Q / E / Z / C | curvas (frente-esq., frente-dir., ré-esq., ré-dir.) |
| 0 | parar tudo (e sair do modo automático) |
| I | entrar no modo automático (inspeção) |
| M | voltar ao manual |
| G / R / P | decisão da IA: seguir / reduzir / parar |
| K | recalibrar chão e inclinação |
| 1 a 9 | velocidade |
| B | buzina |

O robô envia `DIST:<cm>`, `COLLISION:0|1`, `HOLE:0|1`, a linha `FW:robo_completo 1.0` e uma linha JSON a cada 250 ms:
`{"t":12345,"m":"A","df":32,"dc":0.4,"inc":11.2,"vib":0.42,"ev":"rampa"}`
(df = distância à frente, dc = desvio do chão, inc = inclinação %, vib = trepidação, ev = evento).

## Integração sensor ↔ IA

A IA é a **Sakura**, assistente de voz que o grupo fez em Python ([`sakura/`](sakura/README.md), também em [Jpvalebm607/alexa](https://github.com/Jpvalebm607/alexa)). Ela roda no PC; o celular faz a ponte entre o robô (Bluetooth) e a Sakura (Wi-Fi):

```
 MPU-6050 + 2 sonares ──► Arduino ──Bluetooth──► App Android ──Wi-Fi (HTTP)──► Sakura (PC)
                             ▲                        │                          │
                             └────── G / R / P ◄──────┘◄── decisão + comandos ◄──┘
                                                                    fala os alertas
```

1. **Sensor → app:** a cada 250 ms o robô manda `{"m":"A","df":32,"dc":0.4,"inc":11.2,"vib":0.42,"ev":"rampa"}`. O app mostra a **trepidação** (MPU-6050, em g RMS) e a **inclinação** (%) na tela.
2. **App → Sakura:** o app repassa essa linha para `POST http://<ip-do-pc>:8765/robo/telemetria`.
3. **Sakura decide** (`sakura/robo.py`, função `classificar`):

   | O que os sensores dizem | Decisão | O que a Sakura fala |
   |---|---|---|
   | `ev` = buraco ou obstáculo | **P** (parar). Depois que some, espera mais 1,5 s parada para confirmar | "Atenção: buraco ou degrau na calçada. Parei o robô." |
   | `ev` = falha_mpu, falha_sonar ou bt_perdido | **P** | qual sensor conferir |
   | inclinação acima de **8,33 %** (NBR 9050) | **R** (reduzir) | "Rampa com 11 por cento de inclinação, acima do limite da norma." |
   | trepidação acima de **0,30 g** | **R** | "Piso muito irregular…" |
   | trepidação acima de **0,12 g** | **R** | "Piso irregular. Reduzindo a velocidade." |
   | nada disso | **G** (seguir) | — |

4. **Sakura → app → robô:** a resposta `{"decisao":"R","motivo":"Rampa de 11 %","fala":"…","comandos":""}` volta para o app, que manda a letra ao robô no **modo automático** (a cada 300 ms, junto com o batimento). O firmware anda devagar com **R** e para com **P**.
5. **Voz → robô:** "Sakura, robô modo automático" (`I`), "Sakura, robô parar" (`0`), "modo manual" (`M`), "buzina" (`B`), "recalibrar" (`K`). Movimento (W, S, A, D…) **nunca** vem por voz: o app descarta.
6. **Relatório:** "Sakura, como está a calçada?" conta buracos, rampas e trechos irregulares, com a trepidação média e de pico. "Sakura, analisa a calçada do robô" pede ao Gemini uma avaliação de acessibilidade. Cada ocorrência fica salva em `sakura/inspecoes/AAAA-MM-DD.jsonl` (base para o mapa do Calçada Segura).

**Segurança em camadas:** o firmware sempre para sozinho diante de obstáculo ou buraco, com ou sem Sakura. Se a Sakura ficar 3 s sem responder, o app volta a decisão para **G** e o robô usa só as próprias regras. Qualquer botão do app, o ■ ou o app sair da tela tira o robô do automático e para.

### Como ligar

1. No PC: `cd sakura`, `pip install -r requirements.txt` e `python main.py` (Sakura completa, com voz) ou `python robo.py` (só a ponte, sem microfone). Ela mostra o endereço, por exemplo `192.168.0.10:8765`.
2. Celular e PC no **mesmo Wi-Fi** (ou o PC no roteador do celular). Se o Windows perguntar, libere o Python no firewall (rede privada).
3. No app: **Conectar ao robô**, depois **Ligar Sakura** e digite o endereço. Toque em **Iniciar inspeção automática** (ou fale "Sakura, robô modo automático").

## Testes

| O quê | Como rodar | Resultado |
|---|---|---|
| Ponte da Sakura (decisões, falas, comandos de voz, servidor HTTP) | `cd sakura` e `python -m unittest test_robo -v` | 22 testes |
| App: protocolo, telemetria do MPU, resposta da Sakura, botões | `cd app_android` e `gradlew test` | 53 testes |
