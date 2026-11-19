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
- **Integração com IA:** _a completar_

## Estrutura do repositório

| Pasta | O que tem |
|---|---|
| [`firmware/robo_completo/`](firmware/robo_completo/robo_completo.ino) | Programa do Arduino: controle, modo automático, sonares, MPU-6050, buzzer e telemetria |
| [`app_android/`](app_android/README.md) | App Android (Kotlin) que controla o robô pelo Bluetooth |

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

_A completar._

## Testes

_A completar._
