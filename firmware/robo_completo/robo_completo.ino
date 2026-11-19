// =============================================================================
// robo_completo.ino: firmware ÚNICO do robô inspetor de calçada (Calçada Segura)
// FIAP Global Solution 2026.2 · CitizenTech
//
// Junta os dois firmwares que já funcionaram no robô de verdade:
//   - robo_app:      controle pelo app, 2 sonares, bloqueio de perigo e buzzer;
//   - robo_inspetor: modo AUTO, MPU-6050, inclinação, vibração e telemetria JSON.
// Os dois continuam na pasta firmware/ como reserva.
//
// ------------------------ PROTOCOLO (1 letra por comando) ---------------------
// Aceita maiúscula ou minúscula. \r, \n e letras desconhecidas são ignorados.
//
//   Letra  O que faz
//   W      frente
//   S      ré                      (ATENÇÃO: S é RÉ. Para PARAR, use 0.)
//   A      girar para a esquerda
//   D      girar para a direita
//   Q      frente-esquerda
//   E      frente-direita
//   Z      ré-esquerda
//   C      ré-direita
//          (W S A D Q E Z C são o movimento manual. Se o robô estiver em AUTO,
//           ele sai do AUTO, a pessoa assume o controle, e já faz o movimento.
//           No AUTO, a MESMA letra precisa chegar 2 vezes em até 0,7 s: o app
//           repete o comando a cada 300 ms, e assim um byte de ruído sozinho
//           não tira o robô do AUTO.)
//   0      PARAR tudo e sair do AUTO (botão STOP do app; o app repete parado)
//   I      entrar no modo AUTO (inspeção). Repetir I em AUTO só renova o watchdog
//   M      voltar ao MANUAL, parado
//   H      heartbeat: só renova o watchdog
//   G      IA: seguir (velocidade normal)
//   R      IA: reduzir (velocidade lenta)
//   P      IA: parar (fica parado até chegar G ou R)
//          (G R P só valem em AUTO. Fora do AUTO só renovam o watchdog.)
//   K      recalibrar chão e MPU. Só em MANUAL, parado e sem buraco ou
//          obstáculo (sem linha de base do chão, vale sempre). Leva ~2 s; o que
//          chegar durante a calibração é descartado. 1 bipe = calibrou.
//          3 bipes = recusado (perigo por perto, ou o chão variou demais, ou ficou
//          fora de 3 a 40 cm): a calibração anterior continua valendo.
//   1..9   nível da velocidade manual de W/S (começa no 7). PWM = 60 + 20 x nível.
//          Giros e curvas usam sempre força máxima (veja PWM_GIRO).
//   B      buzina: um bipe curto
//
// WATCHDOG: 1,5 s sem receber nenhum comando VÁLIDO da lista acima
// (\r, \n e letras desconhecidas NÃO contam: ruído não mantém o robô andando):
//   MANUAL: o robô para.
//   AUTO:   o robô para, avisa "bt_perdido" e apita, mas CONTINUA em AUTO.
//           Quando os comandos voltam, ele volta a andar sozinho.
//
// O ROBÔ ENVIA (uma linha por vez, terminada em \r\n):
//   DIST:<cm>          a cada ~300 ms: distância à frente (400 = nada à frente)
//   COLLISION:0|1      1 = obstáculo à frente ou sonar da         quando muda
//                          frente sem resposta                    e pelo menos
//   HOLE:0|1           1 = chão MAIS LONGE que o normal (+5 cm:   1 vez por s
//                          buraco ou degrau para baixo), chão sem eco ou
//                          sem linha de base. Chão MAIS PERTO que o normal não é
//                          perigo: chão estável mais perto, visto por ~1,2 s com
//                          o robô andando para a frente (ou parado, se ainda não
//                          há base), vira o novo normal (veja ajustarBase).
//   {"t":..,"m":"M","df":..,"dc":..,"inc":..,"vib":..,"ev":".."}   a cada 250 ms
//        t   = ms desde que ligou      m   = M (manual) ou A (auto)
//        df  = cm até a frente         dc  = desvio do chão em cm (0 = normal)
//        inc = inclinação em %         vib = trepidação em g (RMS)
//        ev  = ok, obstaculo, buraco, rampa, irregular, falha_mpu,
//              falha_sonar (qualquer um dos 2 sonares) ou bt_perdido
//   FW:robo_completo 1.0   ao ligar e a cada ~5 s (o app mostra qual firmware é)
//
// --------------------------------- LIGAÇÕES -----------------------------------
//   L298N:                           ENA D5, IN1 D7, IN2 D8, IN3 D9, IN4 D12, ENB D6
//   Sonar da frente (HC-SR04):       TRIG D2, ECHO D3
//   Sonar do chão (HC-SR04 a 45°):   TRIG D4, ECHO A0
//   Buzzer (pino I/O do módulo):     A1
//   MPU-6050 (GY-521):               SDA A4, SCL A5 (endereço 0x68)
//   HC-05 com BT_HARDWARE 1:         TXD -> D0, RXD <- D1 pelo divisor 1k/2k
//   HC-05 com BT_HARDWARE 0:         TXD -> D10, RXD <- D11 pelo divisor 1k/2k
//
// ATENÇÃO: para gravar, tire o fio do TXD do HC-05 do D0 (D0/D1 são os mesmos
// pinos do cabo USB). Depois de gravar, ponha o fio de volta e aperte o RESET
// do Arduino. (Com BT_HARDWARE 0 não precisa tirar nada.)
// =============================================================================

// ======================= AJUSTES (mexa aqui) =======================
// 1 = HC-05 na serial de HARDWARE (D0/D1) a 9600. Sem mensagens de debug.
// 0 = HC-05 no SoftwareSerial (D10/D11) a 9600 e o cabo USB livre a 115200:
//     o USB recebe os mesmos comandos e mostra as mesmas linhas (bom na bancada).
//     Atenção: o SoftwareSerial não envia e recebe ao mesmo tempo. Letras que
//     chegam enquanto o robô envia podem se perder. Por isso, neste modo, a
//     telemetria JSON vai ao Bluetooth só 1 vez por segundo (no USB, a cada
//     250 ms). Para usar o Python neste modo, ligue-o pelo cabo USB
//     (--baud 115200). Com o Python pelo Bluetooth, use BT_HARDWARE 1.
#define BT_HARDWARE 1

#define INVERTE_ESQ 1            // valores confirmados no robô de verdade
#define INVERTE_DIR 0
#define INVERTE_INC 0            // 1 se subir a rampa der inclinação negativa
#define BUZZER_ATIVO_EM_LOW 0    // 1 se o módulo do buzzer liga com LOW (transistor PNP
                                 // 8550/9012) ou se ele esquentar com o robô parado
#define BUZZER_PASSIVO 1         // 1 = buzzer PASSIVO (o do robô): precisa de frequência (tone).
                                 // 0 = buzzer ATIVO: só liga e desliga o pino.
const uint16_t FREQ_BIP = 2700;  // Hz dos bipes: perto da ressonância do buzzer = mais alto

#include <Wire.h>
#include <math.h>
#include <string.h>
#if !BT_HARDWARE
#include <SoftwareSerial.h>
#endif

const char LINHA_FW[] PROGMEM = "FW:robo_completo 1.0";

const uint8_t NIVEL_PADRAO = 7;      // velocidade manual inicial (1 a 9): PWM 200
// Para VIRAR, um 4WD precisa arrastar as rodas de lado: exige muita força, ainda
// mais com 4 pilhas AA (a L298N come ~2 V). Por isso giros e curvas usam força
// máxima, independente do nível de velocidade.
const int PWM_GIRO         = 255;    // girar no lugar (A/D): os dois lados no máximo
const int PWM_CURVA_FORA   = 255;    // curva (Q/E/Z/C): lado de fora no máximo
const int PWM_CURVA_DENTRO = -128;   // curva: lado de dentro gira PARA TRÁS a 50%
                                     // (só "mais devagar" não vira num 4WD)
const uint8_t VEL_AUTO     = 130;    // PWM no modo AUTO
const uint8_t VEL_LENTA    = 95;     // PWM no AUTO com piso irregular ou comando R

const float DIST_OBST_MAN  = 15.0f;  // cm: obstáculo à frente no MANUAL (robo_app)
const float DIST_OBST_AUTO = 20.0f;  // cm: obstáculo à frente no AUTO (robo_inspetor)
const float DIST_HIST      = 5.0f;   // cm a mais para liberar (histerese)
const float BURACO_CM      = 5.0f;   // chão mais longe que a base + 5 cm = buraco
const float BURACO_SAI     = 3.5f;   // histerese para liberar
const uint8_t BASE_AUTO_N  = 20;     // leituras estáveis (~1,2 s) para a base se ajustar
const float BASE_AUTO_DIF  = 2.0f;   // cm: chão mais perto que a base por mais que isso
const float RAMPA_LIM      = 8.33f;  // % (limite da NBR 9050)
const float RAMPA_SAI      = 7.5f;   // histerese
const float VIB_LIM        = 0.12f;  // g (RMS): piso irregular. Calibrar no piso liso.
const float VIB_SAI        = 0.10f;  // histerese

// Linha de base do chão (calibração): faixa aceitável da montagem do sonar a 45°.
const float BASE_MIN       = 3.0f;   // cm
const float BASE_MAX       = 40.0f;  // cm (robô na mão ao ligar mede bem mais que isso)
const int   BASE_FAIXA_MM  = 20;     // 80% das leituras a até 2 cm da mediana

const uint8_t CHAO_SEM_ECO_BURACO = 3;   // leituras seguidas sem eco no chão = buraco
const uint8_t CHAO_SEM_ECO_FALHA  = 20;  // leituras seguidas sem eco = falha do sonar
const uint8_t FRENTE_FALHAS_MAX   = 5;   // sonar da frente sem responder 5 vezes = falha
const uint8_t FRENTE_SEM_ECO_LIVRE = 5;  // sem eco na frente: vale como "livre" da 5ª em diante
const uint8_t LIMPAS_LIBERAR      = 3;   // leituras limpas seguidas para desligar um perigo
const uint8_t MPU_FALHAS_MAX      = 5;   // leituras seguidas com erro = falha do MPU
const uint8_t RAMPA_CONFIRMA      = 30;  // ~0,9 s seguidos acima do limite = rampa

const unsigned LOOP_MS       = 30;    // período do laço (~33 vezes por segundo)
const unsigned WATCHDOG_MS   = 1500;  // sem comando por 1,5 s: para
const unsigned RETOMA_MS     = 500;   // AUTO: espera 0,5 s sem perigo antes de andar
const unsigned RETOMA_MAN_MS = 300;   // MANUAL: espera 0,3 s sem perigo para liberar a frente
const unsigned CONFIRMA_MS   = 700;   // AUTO: mesma letra de movimento 2 vezes neste tempo
const unsigned FREIO_MS      = 300;   // freio forte nas paradas de segurança
const unsigned MPU_ACOMODA_MS = 500;  // inclinação congelada logo depois de arrancar/frear
const unsigned BIP_MS        = 150;   // duração da buzina (comando B)
const unsigned DIST_MS       = 300;   // período da linha DIST
const unsigned FLAGS_MS      = 1000;  // período mínimo de COLLISION/HOLE
const unsigned TELEM_MS      = 250;   // período da telemetria JSON
const unsigned FW_MS         = 5000;  // período da linha FW

const unsigned long SUBIDA_US  = 6000;   // o ECHO tem que subir até 6 ms depois do TRIG
const unsigned long ECO_MAX_US = 25000;  // eco mais longo que 25 ms = nada perto (~4 m)

// ======================= PINOS =======================
const uint8_t ENA = 5, IN1 = 7, IN2 = 8;     // motores da esquerda
const uint8_t IN3 = 9, IN4 = 12, ENB = 6;    // motores da direita
const uint8_t TRIG_F = 2, ECHO_F = 3;        // sonar da frente
const uint8_t TRIG_C = 4, ECHO_C = A0;       // sonar do chão (A0 como digital)
const uint8_t BUZ = A1;                      // buzzer
const uint8_t MPU_END = 0x68;                // endereço I2C do MPU-6050
#if !BT_HARDWARE
SoftwareSerial btSoft(10, 11);               // RX = D10, TX = D11
#endif

// ======================= EVENTOS =======================
// Ordem de prioridade do campo "ev" (do mais importante para o menos):
// bt_perdido > falha_mpu > falha_sonar > obstaculo > buraco > rampa > irregular > ok
enum Ev { EV_OK, EV_OBST, EV_BURACO, EV_RAMPA, EV_IRREG, EV_FALHA_MPU, EV_FALHA_SONAR, EV_BT };
const char NOME_EV[][12] PROGMEM = { "ok", "obstaculo", "buraco", "rampa", "irregular",
                                     "falha_mpu", "falha_sonar", "bt_perdido" };

// Resultado de uma medida do HC-SR04 (fica aqui em cima porque a IDE do
// Arduino precisa conhecer o tipo antes da primeira função).
enum Medida : uint8_t {
  MED_OK,        // voltou eco: distância em cm
  MED_SEM_ECO,   // o sensor respondeu, mas nada voltou em 25 ms (nada perto)
  MED_NAO_RESP,  // o sensor NÃO respondeu: fio solto, sem energia, ECHO travado
  MED_RUIDO      // (só BT_HARDWARE 0) chegou letra no meio: medida não confiável
};

// ======================= ESTADO =======================
bool modoAuto = false;
char cmd = '0';                  // comando manual atual
char cmdIA = 'G';                // última decisão da IA no AUTO (G, R ou P)
uint8_t nivel = NIVEL_PADRAO;    // velocidade manual (1 a 9)
unsigned long ultimoRx = 0;      // hora do último comando recebido (watchdog)
bool btPerdido = false;          // AUTO sem comandos há mais de 1,5 s
bool querCalibrar = false;       // chegou K: calibra na próxima passada
unsigned long tPerigo = 0;       // AUTO: última hora em que havia perigo
unsigned long tPerigoMan = 0;    // MANUAL: última hora em que havia perigo à frente
unsigned long tFw = 0;           // última linha FW enviada

// sonares
float df = 400, dc = 15, baseC = 15;   // cm: frente, chão agora, chão normal (base)
bool baseOk = false;
bool obst = false, buraco = false;
// Cada perigo liga com 2 das últimas 3 leituras (bits de "jan") e só desliga
// depois de LIMPAS_LIBERAR leituras limpas seguidas ("limpo").
uint8_t janObst = 0, janBur = 0;
uint8_t limpoObst = 0, limpoBur = 0;
bool baseAuto = false;                    // a base atual veio do ajuste automático
uint8_t nBaseNova = 0;                    // base automática do chão (veja ajustarBase)
float somaBaseNova = 0, primeiraBaseNova = 0;
uint8_t chaoSemEco = 0;                // leituras seguidas sem eco no chão
uint8_t frenteSemEco = 0;              // leituras seguidas sem eco na frente
uint8_t frenteFalhas = 0;              // leituras seguidas em que a frente não respondeu
bool vezDaFrente = false;              // alterna os sonares: um por passada

// MPU-6050
uint8_t mpuFalhas = 0;
float angOff = 0, angF = 0, inc = 0, azM = 1.0f, vq = 0, vib = 0;
bool rampa = false, irregular = false;
uint8_t nRampa = 0;                    // leituras seguidas acima do limite da rampa
unsigned long tAcordarMPU = 0;

// ======================= MOTORES =======================
void lado(uint8_t en, uint8_t a, uint8_t b, int v) {
  if (v > 255) v = 255;
  if (v < -255) v = -255;
  digitalWrite(a, v > 0);
  digitalWrite(b, v < 0);
  analogWrite(en, v < 0 ? -v : v);
}

bool freando = false;              // freio forte em andamento (ou já terminado, parado)
unsigned long tFreio = 0;          // quando o freio começou
int motorE = 0, motorD = 0;        // último pedido aos motores
unsigned long tMudancaMotor = 0;   // quando o pedido aos motores mudou (arranque/freada)

// Guarda a hora em que o pedido aos motores mudou: a aceleração do próprio
// robô atrapalha a medida da inclinação logo depois disso.
void anotarMotor(int e, int d) {
  if (e != motorE || d != motorD) {
    motorE = e; motorD = d;
    tMudancaMotor = millis();
  }
}

// Parada de SEGURANÇA. Na L298N, com EN ligado e as duas entradas em LOW, o
// motor fica em curto e para na hora (freio). Com EN desligado ele só "solta" e
// o robô ainda desliza alguns cm. Freia por FREIO_MS e depois solta (roda livre).
void frear() {
  unsigned long agora = millis();
  anotarMotor(0, 0);
  if (!freando) { freando = true; tFreio = agora; }
  if (agora - tFreio < FREIO_MS) {
    digitalWrite(IN1, LOW); digitalWrite(IN2, LOW);
    digitalWrite(IN3, LOW); digitalWrite(IN4, LOW);
    analogWrite(ENA, 255);  analogWrite(ENB, 255);
  } else {
    lado(ENA, IN1, IN2, 0);
    lado(ENB, IN3, IN4, 0);
  }
}

void mover(int e, int d) {
  if (e == 0 && d == 0 && freando) { frear(); return; }   // deixa o freio terminar
  freando = false;
  anotarMotor(e, d);
  if (INVERTE_ESQ) e = -e;
  if (INVERTE_DIR) d = -d;
  lado(ENA, IN1, IN2, e);
  lado(ENB, IN3, IN4, d);
}

// ======================= BUZZER =======================
// Padrões: tempos em ms, alternando ligado/desligado (quantidade PAR), terminado em 0.
// Ficam na memória flash (PROGMEM) para economizar a RAM.
const uint16_t P_OBST[]   PROGMEM = {150, 100, 150, 600, 0};              // dois bipes
const uint16_t P_RAMPA[]  PROGMEM = {100, 150, 100, 150, 100, 700, 0};    // três bipes
const uint16_t P_FALHA[]  PROGMEM = {80, 80, 0};                          // bipe rápido
const uint16_t P_SOS[]    PROGMEM = {150, 150, 150, 150, 150, 300,
                                     400, 150, 400, 150, 400, 300,
                                     150, 150, 150, 150, 150, 900, 0};    // ... --- ...

const uint16_t* pAtual = nullptr;   // padrão tocando agora (nullptr = nenhum)
uint8_t pIdx = 0;
bool pLig = false;                  // o padrão manda o buzzer ficar ligado?
unsigned long pT = 0;
bool bipLig = false;                // buzina (comando B) tocando?
unsigned long bipT = 0;

// Melodia do buraco: o trechinho de abertura do tema do Super Mario Bros.
// (Nintendo), só para a demonstração deste projeto acadêmico, sem fins comerciais.
// Notas na 7ª oitava: perto da ressonância do buzzer, onde ele soa mais alto.
// {frequência em Hz, duração em ms}; 0 Hz = pausa; {0, 0} = fim.
struct Nota { uint16_t f; uint16_t ms; };
const Nota MELODIA_BURACO[] PROGMEM = {
  {2637, 100}, {2637, 100}, {0, 100}, {2637, 100}, {0, 100},   // E E . E .
  {2093, 100}, {2637, 100}, {0, 100},                          // C E .
  {3136, 100}, {0, 300},                                       // G ...
  {1568, 100}, {0, 0}                                          // G (grave)
};
const unsigned MELODIA_PAUSA_MS = 3000;   // não repete a melodia antes de 3 s

bool melodia = false;               // melodia tocando agora?
uint8_t mIdx = 0;                   // nota atual
unsigned long mT = 0;               // quando a nota atual começou
bool jaTocou = false;               // a melodia já tocou alguma vez?
unsigned long fimMelodia = 0;       // quando a última melodia terminou
bool buracoAntes = false;           // para tocar só quando o buraco APARECE

bool somLigado = false;
uint16_t somFreq = 0;

// Liga o som. Buzzer passivo precisa de uma frequência (tone); ativo só de HIGH/LOW.
void somLigar(uint16_t f) {
  if (somLigado && somFreq == f) return;           // já está tocando essa nota
  somLigado = true;
  somFreq = f;
#if BUZZER_PASSIVO
  tone(BUZ, f);
#else
  digitalWrite(BUZ, BUZZER_ATIVO_EM_LOW ? LOW : HIGH);
#endif
}

void somDesligar() {
  somLigado = false;
#if BUZZER_PASSIVO
  noTone(BUZ);
#endif
  digitalWrite(BUZ, BUZZER_ATIVO_EM_LOW ? HIGH : LOW);
}

void buzzer(bool on) {
  if (on) somLigar(FREQ_BIP);
  else    somDesligar();
}

// O buzzer toca se o padrão OU a buzina pedirem (a melodia tem prioridade).
void tocar() {
  if (melodia) return;
  buzzer(pLig || bipLig);
}

void tocarNotaAtual() {
  uint16_t f = pgm_read_word(&MELODIA_BURACO[mIdx].f);
  if (f) somLigar(f);
  else   somDesligar();
}

void iniciarMelodia() {
  melodia = true;
  jaTocou = true;
  mIdx = 0;
  mT = millis();
  tocarNotaAtual();
}

// Toca a melodia sem travar o robô. Cada nota soa 80% do tempo e fica muda no
// resto, para as notas repetidas (E E E) não virarem um som só.
void atualizarMelodia(unsigned long agora) {
  uint16_t ms = pgm_read_word(&MELODIA_BURACO[mIdx].ms);
  if (somLigado && agora - mT >= (unsigned long)ms * 4 / 5) somDesligar();
  if (agora - mT < ms) return;
  mIdx++;
  mT = agora;
  if (pgm_read_word(&MELODIA_BURACO[mIdx].ms) == 0) {   // acabou
    melodia = false;
    fimMelodia = agora;
    somDesligar();
    tocar();                                         // volta o padrão/buzina, se houver
    return;
  }
  tocarNotaAtual();
}

void definirPadrao(const uint16_t* p) {
  if (p == pAtual) return;
  pAtual = p; pIdx = 0; pT = millis();
  pLig = (p != nullptr);
  tocar();
}

// Buzina: um bipe curto por cima do padrão, sem estragar o padrão depois.
void bipCurto() {
  bipLig = true;
  bipT = millis();
  tocar();
}

void atualizarBuzzer() {
  unsigned long agora = millis();
  if (bipLig && agora - bipT >= BIP_MS) { bipLig = false; tocar(); }
  if (melodia) { atualizarMelodia(agora); return; }
  if (!pAtual) return;
  if (agora - pT >= pgm_read_word(&pAtual[pIdx])) {
    pIdx++;
    if (pgm_read_word(&pAtual[pIdx]) == 0) pIdx = 0;
    pLig = !pLig;
    pT = agora;
    tocar();
  }
}

// ======================= FILA DE SAÍDA =======================
// A serial do Arduino só guarda 64 letras para enviar. Se escrevermos mais que
// isso de uma vez, o programa fica esperando. Por isso as linhas entram nesta
// fila e saem aos poucos, a cada volta do loop, sem travar o robô.
const uint8_t FILA_TAM = 128;      // tamanho (precisa ser potência de 2)
char fila[FILA_TAM];
uint8_t filaIni = 0, filaFim = 0;  // contadores que dão a volta sozinhos (0 a 255)

uint8_t filaLivre() { return FILA_TAM - (uint8_t)(filaFim - filaIni); }

void filaPor(char c) {
  fila[filaFim & (FILA_TAM - 1)] = c;
  filaFim++;
}

// Coloca a linha inteira na fila (com \r\n). Se não couber, descarta a linha
// toda (nunca meia linha) e devolve false.
// bt = false (só no BT_HARDWARE 0): a linha vai só para o cabo USB.
bool enviarLinhaPara(const char* s, bool bt) {
#if BT_HARDWARE
  (void)bt;                        // aqui o Bluetooth É a serial: tudo vai para ele
#else
  if (!bt) { Serial.println(s); return true; }
#endif
  size_t n = strlen(s);
  if (n + 2 > filaLivre()) return false;
  for (size_t i = 0; i < n; i++) filaPor(s[i]);
  filaPor('\r');
  filaPor('\n');
#if !BT_HARDWARE
  Serial.println(s);               // cópia no cabo USB (115200 é rápido)
#endif
  return true;
}

bool enviarLinha(const char* s) { return enviarLinhaPara(s, true); }

// Manda para o Bluetooth o que couber agora, sem esperar.
void bombearSaida() {
#if BT_HARDWARE
  while (filaIni != filaFim && Serial.availableForWrite() > 0) {
    Serial.write(fila[filaIni & (FILA_TAM - 1)]);
    filaIni++;
  }
#else
  // O SoftwareSerial trava ~1 ms por letra: manda só 2 por vez.
  for (uint8_t k = 0; k < 2 && filaIni != filaFim; k++) {
    btSoft.write(fila[filaIni & (FILA_TAM - 1)]);
    filaIni++;
  }
#endif
}

// Espera alguns ms, mas continua enviando a fila enquanto isso.
void esperar(unsigned ms) {
  unsigned long t0 = millis();
  while (millis() - t0 < ms) bombearSaida();
}

// 3 bipes rápidos: comando recusado (ex.: K com perigo por perto).
// Só é chamada com o robô PARADO, porque trava o laço por ~0,4 s.
void bipesFalha() {
  for (uint8_t i = 0; i < 3; i++) {
    buzzer(true);  esperar(70);
    buzzer(false); esperar(70);
  }
  tocar();                         // devolve o buzzer ao padrão que estava tocando
}

// ======================= SENSORES =======================
// Mede em 3 fases, para separar "nada à frente" de "sensor quebrado":
//   1) antes de disparar, o ECHO precisa estar em LOW (com o pull-up ligado,
//      um fio de ECHO solto fica em HIGH e cai aqui);
//   2) depois do disparo, o ECHO precisa subir (o HC-SR04 sobe em ~0,5 ms);
//   3) o tempo em HIGH é a ida e volta do som. Mais de 25 ms = nada perto.
// Usa micros() (tempo de verdade), não o pulseIn, que não conta o tempo
// gasto nas interrupções.
Medida medir(uint8_t trig, uint8_t echo, float &cm) {
  cm = 0;
  if (digitalRead(echo) == HIGH) return MED_NAO_RESP;
#if !BT_HARDWARE
  int antes = btSoft.available();
#endif
  digitalWrite(trig, LOW);  delayMicroseconds(2);
  digitalWrite(trig, HIGH); delayMicroseconds(10);
  digitalWrite(trig, LOW);
  unsigned long t0 = micros();
  while (digitalRead(echo) == LOW) {
    if (micros() - t0 > SUBIDA_US) return MED_NAO_RESP;
  }
  Medida r = MED_OK;
  unsigned long t1 = micros(), us = 0;
  while (digitalRead(echo) == HIGH) {
    us = micros() - t1;
    if (us > ECO_MAX_US) { r = MED_SEM_ECO; break; }
  }
#if !BT_HARDWARE
  // A recepção do SoftwareSerial prende o Arduino ~1 ms por letra: se chegou
  // letra durante a medida, o tempo medido não vale.
  if (btSoft.available() != antes) return MED_RUIDO;
#endif
  if (r == MED_OK) cm = us * 0.0343f / 2.0f;
  return r;
}

// Acorda o MPU-6050 (ele liga dormindo) e escolhe a faixa de ±2 g.
void acordarMPU() {
  Wire.beginTransmission(MPU_END);
  Wire.write(0x6B); Wire.write(0);       // PWR_MGMT_1 = 0: acorda
  Wire.endTransmission();
  Wire.beginTransmission(MPU_END);
  Wire.write(0x1C); Wire.write(0);       // ACCEL_CONFIG = 0: ±2 g (16384 por g)
  Wire.endTransmission();
}

// Lê a aceleração (em g) nos 3 eixos. Devolve false se o I2C falhar.
bool lerMPU(float &ax, float &ay, float &az) {
  Wire.beginTransmission(MPU_END);
  Wire.write(0x3B);                      // primeiro registrador da aceleração
  if (Wire.endTransmission(false) != 0) return false;
  if (Wire.requestFrom(MPU_END, (uint8_t)6) != 6) return false;
  uint8_t b[6];
  for (uint8_t i = 0; i < 6; i++) b[i] = Wire.read();
  int16_t x = (int16_t)((b[0] << 8) | b[1]);
  int16_t y = (int16_t)((b[2] << 8) | b[3]);
  int16_t z = (int16_t)((b[4] << 8) | b[5]);
  if (x == 0 && y == 0 && z == 0) return false;   // dormindo ou desconectado
  ax = x / 16384.0f; ay = y / 16384.0f; az = z / 16384.0f;
  return true;
}

float anguloGraus(float ax, float ay, float az) {
  float a = atan2(ax, sqrt(ay * ay + az * az)) * 57.29578f;
  return INVERTE_INC ? -a : a;
}

bool mpuFalhou()       { return mpuFalhas >= MPU_FALHAS_MAX; }
bool sonarChaoFalhou() { return chaoSemEco >= CHAO_SEM_ECO_FALHA || !baseOk; }
bool frenteFalhou()    { return frenteFalhas >= FRENTE_FALHAS_MAX; }

// Inclinação (%) e vibração (RMS do eixo Z sem a gravidade), igual ao robo_inspetor.
void atualizarMPU(unsigned long agora) {
  float ax, ay, az;
  if (lerMPU(ax, ay, az)) {
    mpuFalhas = 0;
    float ang = anguloGraus(ax, ay, az) - angOff;
    // Ao arrancar ou frear, a aceleração do robô parece inclinação (0,15 g ~ 15%).
    // Nesse meio segundo a inclinação fica congelada no último valor.
    if (agora - tMudancaMotor >= MPU_ACOMODA_MS) {
      angF += 0.15f * (ang - angF);                  // filtro passa-baixa
    }
    float a = angF;
    if (a > 80) a = 80;
    if (a < -80) a = -80;
    inc = tan(a * 0.0174533f) * 100.0f;              // % = tan(ângulo) x 100
    if (inc > 999) inc = 999;
    if (inc < -999) inc = -999;
    azM += 0.05f * (az - azM);                       // média lenta = gravidade
    float d = az - azM;
    vq += 0.03f * (d * d - vq);                      // janela de ~1 s
    vib = sqrt(vq);
    if (vib > 9.99f) vib = 9.99f;
    // Rampa só depois de ~0,9 s seguidos acima do limite (um tranco não é rampa).
    if (fabs(inc) > RAMPA_LIM) { if (nRampa < 255) nRampa++; }
    else nRampa = 0;
    rampa     = rampa ? (fabs(inc) > RAMPA_SAI) : (nRampa >= RAMPA_CONFIRMA);
    irregular = vib > (irregular ? VIB_SAI : VIB_LIM);
    return;
  }
  if (mpuFalhas < 255) mpuFalhas++;
  if (mpuFalhou()) {
    // Sem MPU: zera os números para não mandar valor velho para a IA.
    angF = 0; inc = 0; vq = 0; vib = 0;
    rampa = false; irregular = false; nRampa = 0;
    // Tenta acordar de novo a cada 1 s (fio que voltou, sensor que reiniciou).
    if (agora - tAcordarMPU >= 1000) { tAcordarMPU = agora; acordarMPU(); }
  }
}

uint8_t mais1(uint8_t v) { return v < 250 ? v + 1 : v; }               // conta sem estourar
uint8_t conta3(uint8_t j) { return (j & 1) + ((j >> 1) & 1) + ((j >> 2) & 1); }  // bits das 3 últimas

// Base automática do chão (resolve o robô que ligou suspenso, na mão ou em cima
// de uma caixa, e depois foi posto no chão). O sonar do chão precisa ler ~1,2 s
// seguidos de chão ESTÁVEL (variação de até 2 cm), sem obstáculo na frente:
//   - ANDANDO PARA A FRENTE: se esse chão está mais de 2 cm MAIS PERTO que a base,
//     ele vira a base. (Um meio-fio ou objeto vai chegando mais perto e não fica
//     estável, então não engana.)
//   - PARADO: só quando ainda não existe base (ligou sem eco ou alto demais).
//     Nesse caso também zera a inclinação (o robô pode ter ligado torto na mão).
// A base nunca SOBE sozinha: chão mais longe é buraco, não "chão novo".
// Se a base automática ficar errada, o K (recalibrar) é aceito mesmo com buraco.
void ajustarBase(float cm) {
  bool parado  = (motorE == 0 && motorD == 0);
  bool andando = (motorE > 0 && motorD > 0);       // para a frente (reto ou curva)
  bool pode = !obst && (andando || (parado && !baseOk));
  bool candidato = pode && cm >= BASE_MIN && cm <= BASE_MAX &&
                   (!baseOk || cm < baseC - BASE_AUTO_DIF);
  bool instavel = (nBaseNova > 0) && (fabs(cm - primeiraBaseNova) > BASE_FAIXA_MM / 10.0f);
  if (!candidato || instavel) {
    nBaseNova = 0; somaBaseNova = 0;
    if (!candidato) return;
  }
  if (nBaseNova == 0) primeiraBaseNova = cm;
  somaBaseNova += cm;
  nBaseNova++;
  if (nBaseNova >= BASE_AUTO_N) {
    bool semBaseAntes = !baseOk;
    baseC = somaBaseNova / nBaseNova;
    baseOk = true;
    baseAuto = true;
    nBaseNova = 0; somaBaseNova = 0;
    janBur = 0; limpoBur = LIMPAS_LIBERAR; chaoSemEco = 0;   // libera o "buraco" da base velha
    // Parado há um tempo e sem base antes: o robô acabou de ser posto no chão.
    // A inclinação de agora vira o zero (sem o tranco de arrancar ou frear).
    if (semBaseAntes && parado && millis() - tMudancaMotor >= MPU_ACOMODA_MS) {
      angOff += angF; angF = 0; inc = 0;
      nRampa = 0; rampa = false;
    }
  }
}

// Lê UM sonar por passada, alternando, para um não atrapalhar o outro.
void lerSonar() {
  float cm;
  if (vezDaFrente) {
    Medida r = medir(TRIG_F, ECHO_F, cm);
    if (r == MED_RUIDO) return;                      // mede a frente de novo na próxima
    if (r == MED_NAO_RESP) {
      frenteFalhas = mais1(frenteFalhas);            // sem informação: não mexe no obst
    } else {
      frenteFalhas = 0;
      bool perto = false, livre;
      if (r == MED_SEM_ECO) {
        // Um eco perdido não quer dizer "livre" (alvo fino ou inclinado falha às vezes).
        frenteSemEco = mais1(frenteSemEco);
        livre = (frenteSemEco >= FRENTE_SEM_ECO_LIVRE);
        if (livre) df = 400;                         // nada à frente
      } else {
        frenteSemEco = 0;
        df = (cm > 400) ? 400 : cm;
        float lim = modoAuto ? DIST_OBST_AUTO : DIST_OBST_MAN;
        if (obst) lim += DIST_HIST;
        perto = (df < lim);
        livre = !perto;
      }
      janObst = (uint8_t)((janObst << 1) | (perto ? 1 : 0));
      if (perto) limpoObst = 0;
      else if (livre) limpoObst = mais1(limpoObst);
      if (conta3(janObst) >= 2) obst = true;                 // 2 das 3 últimas: perto
      else if (limpoObst >= LIMPAS_LIBERAR) obst = false;    // 3 limpas seguidas: libera
    }
  } else {
    Medida r = medir(TRIG_C, ECHO_C, cm);
    if (r == MED_RUIDO) return;                      // mede o chão de novo na próxima
    if (r != MED_OK) {
      // Sem eco ou sem resposta: buraco fundo ou sensor solto.
      chaoSemEco = mais1(chaoSemEco);
      janBur = (uint8_t)(janBur << 1);
      limpoBur = 0;                                  // isso não é chão normal
      nBaseNova = 0; somaBaseNova = 0;
    } else {
      chaoSemEco = 0;
      dc = cm;
      ajustarBase(cm);                               // chão mais perto e estável vira a base
      // Só "chão MAIS LONGE que o normal" é buraco. Chão mais perto não é perigo
      // (obstáculo quem vê é o sonar da frente).
      float limB = baseC + (buraco ? BURACO_SAI : BURACO_CM);
      bool fundo = (dc > limB);
      janBur = (uint8_t)((janBur << 1) | (fundo ? 1 : 0));
      limpoBur = fundo ? 0 : mais1(limpoBur);
    }
    // Sem eco 3 vezes ou sem linha de base: trata como buraco (mais seguro).
    bool sinalBur = conta3(janBur) >= 2 || chaoSemEco >= CHAO_SEM_ECO_BURACO || !baseOk;
    if (sinalBur) buraco = true;
    else if (limpoBur >= LIMPAS_LIBERAR) buraco = false;
  }
  vezDaFrente = !vezDaFrente;
}

// Ordena do menor para o maior (poucos números: o método simples basta).
void ordenar(uint16_t* v, uint8_t n) {
  for (uint8_t i = 1; i < n; i++) {
    uint16_t x = v[i];
    uint8_t j = i;
    while (j > 0 && v[j - 1] > x) { v[j] = v[j - 1]; j--; }
    v[j] = x;
  }
}

// Robô PARADO e no plano (~2 s): mede a linha de base do chão e o zero do MPU.
// Devolve true se a base do chão ficou boa. Se já havia uma base boa (comando K)
// e as leituras agora estão ruins, RECUSA: tudo continua como estava.
bool calibrar() {
  mover(0, 0);
  melodia = false;
  definirPadrao(nullptr);
  bipLig = false;
  buzzer(false);
  acordarMPU();
  esperar(100);
  const uint8_t NMAX = 60;
  uint16_t mm[NMAX];                               // leituras do chão, em mm
  float somaAng = 0, somaZ = 0;
  uint8_t n = 0, nc = 0;
  unsigned long t0 = millis();
  while (millis() - t0 < 2000) {
    float ax, ay, az, c;
    if (lerMPU(ax, ay, az)) { somaAng += anguloGraus(ax, ay, az); somaZ += az; n++; }
    if (medir(TRIG_C, ECHO_C, c) == MED_OK && nc < NMAX) {
      mm[nc++] = (uint16_t)(c * 10.0f + 0.5f);
    }
    esperar(40);
  }
  // Base do chão: pelo menos 80% das leituras a até 2 cm da mediana (o robô
  // está todo no mesmo plano, não meio na borda de um buraco) e entre 3 e 40 cm.
  bool chaoOk = false;
  float base = 0;
  if (nc >= 10) {
    ordenar(mm, nc);
    int med = mm[nc / 2];
    uint32_t soma = 0;
    uint8_t perto = 0;
    for (uint8_t i = 0; i < nc; i++) {
      int dif = (int)mm[i] - med;
      if (dif < 0) dif = -dif;
      if (dif <= BASE_FAIXA_MM) { soma += mm[i]; perto++; }
    }
    base = soma / (10.0f * perto);                 // perto >= 1 (a própria mediana)
    chaoOk = (perto * 5 >= nc * 4) && base >= BASE_MIN && base <= BASE_MAX;
  }
  if (!chaoOk && baseOk) return false;             // K com leitura ruim: mantém tudo

  if (n > 10) { angOff = somaAng / n; azM = somaZ / n; mpuFalhas = 0; }
  else        { mpuFalhas = MPU_FALHAS_MAX; }      // sem MPU: o robô segue sem ele
  baseOk = chaoOk;                                 // sem base: vira "buraco" (seguro)
  if (chaoOk) { baseC = base; dc = baseC; baseAuto = false; }
  chaoSemEco = 0;
  angF = 0; inc = 0; vq = 0; vib = 0;
  rampa = false; irregular = false; nRampa = 0;
  janObst = 0; janBur = 0;
  limpoObst = 0; limpoBur = 0;
  nBaseNova = 0; somaBaseNova = 0;
  frenteSemEco = 0;
  obst = false;
  buraco = !baseOk;
  return chaoOk;
}

// K só vale em MANUAL, parado e sem perigo por perto (calibrar na borda de um
// buraco gravaria o fundo como "chão normal"). Sem base ainda, vale sempre.
// Se a base veio do ajuste automático, o K vale mesmo com "buraco": é a saída
// para uma base automática que ficou baixa demais.
bool podeCalibrar() {
  if (modoAuto || cmd != '0') return false;
  if (!baseOk) return true;
  return (!buraco || baseAuto) && !obst;
}

// ======================= DECISÃO =======================
Ev calcularEvento() {
  if (btPerdido)                          return EV_BT;
  if (mpuFalhou())                        return EV_FALHA_MPU;
  if (sonarChaoFalhou() || frenteFalhou()) return EV_FALHA_SONAR;
  if (obst)                     return EV_OBST;
  if (buraco)                             return EV_BURACO;
  if (rampa)                              return EV_RAMPA;
  if (irregular)                          return EV_IRREG;
  return EV_OK;
}

const uint16_t* padraoAuto(Ev ev) {
  switch (ev) {
    case EV_OBST:        return P_OBST;
    case EV_BURACO:      return nullptr;       // a melodia do buraco já avisou
    case EV_RAMPA:       return P_RAMPA;
    case EV_BT:          return P_FALHA;
    case EV_FALHA_SONAR: return P_FALHA;
    case EV_FALHA_MPU:   return P_SOS;
    default:             return nullptr;       // ok e irregular: silêncio
  }
}

void escolherSom(Ev ev) {
  // Buraco de verdade (com linha de base do chão): o robô para e toca a melodia
  // UMA vez quando o buraco aparece. Só repete se o buraco sumir e voltar, e com
  // pelo menos MELODIA_PAUSA_MS entre uma e outra.
  bool buracoReal = buraco && baseOk;
  if (buracoReal && !buracoAntes && !melodia &&
      (!jaTocou || millis() - fimMelodia >= MELODIA_PAUSA_MS)) {
    iniciarMelodia();
  }
  // Se a pausa de 3 s segurou a melodia, a "subida" fica pendente: ela toca
  // quando a pausa acabar, se o buraco ainda estiver lá (não fica mudo).
  if (!buracoReal || melodia) buracoAntes = buracoReal;

  const uint16_t* p;
  if (modoAuto) {
    p = padraoAuto(ev);
    // Buraco fundo (chão sem eco por muito tempo) também conta como buraco aqui.
    if (buracoReal && ev == EV_FALHA_SONAR && !frenteFalhou()) p = nullptr;
  }
  else if (buraco && !baseOk) p = P_FALHA;    // sem linha de base do chão
  else if (frenteFalhou()) p = P_FALHA;       // sonar da frente sem resposta
  else if (obst) p = P_OBST;
  else                     p = nullptr;
  definirPadrao(p);
}

// MANUAL: igual ao robo_app. Frente (W, Q, E) é bloqueada com perigo e por mais
// RETOMA_MAN_MS depois que ele some; ré e giros continuam liberados.
// Falha do MPU NÃO bloqueia o manual.
void aplicarManual(unsigned long agora) {
  bool perigoFrente = obst || buraco || frenteFalhou();
  if (perigoFrente) tPerigoMan = agora;
  bool vaiFrente = (cmd == 'W' || cmd == 'Q' || cmd == 'E');
  if (vaiFrente && (perigoFrente || agora - tPerigoMan < RETOMA_MAN_MS)) {
    cmd = '0';                               // bloqueado: freia e para
    frear();
    return;
  }
  int v = 60 + 20 * nivel;                   // nível 7 = 200, nível 9 = 240
  const int g = PWM_GIRO, fo = PWM_CURVA_FORA, de = PWM_CURVA_DENTRO;
  switch (cmd) {
    case 'W': mover(v, v);             break;
    case 'S': mover(-v, -v);           break;
    case 'A': mover(-g, g);            break;   // girar para a esquerda
    case 'D': mover(g, -g);            break;   // girar para a direita
    case 'Q': mover(de, fo);           break;   // frente-esquerda
    case 'E': mover(fo, de);           break;   // frente-direita
    case 'Z': mover(-de, -fo);         break;   // ré-esquerda
    case 'C': mover(-fo, -de);         break;   // ré-direita
    default:  mover(0, 0);             break;
  }
}

// AUTO: anda sozinho para a frente e para (freando) em qualquer perigo.
// Quando o perigo some, espera RETOMA_MS e volta a andar.
void controlarAuto(unsigned long agora) {
  bool parar = obst || buraco || cmdIA == 'P' || mpuFalhou() ||
               sonarChaoFalhou() || frenteFalhou() || btPerdido;
  if (parar) { tPerigo = agora; frear(); return; }
  if (agora - tPerigo < RETOMA_MS) { mover(0, 0); return; }
  uint8_t v = (cmdIA == 'R' || irregular) ? VEL_LENTA : VEL_AUTO;
  mover(v, v);                               // rampa: continua andando (só apita)
}

// ======================= COMANDOS =======================
void tratar(char c) {
  if (c >= 'a' && c <= 'z') c = c - 'a' + 'A';     // aceita minúsculas
  switch (c) {
    case 'W': case 'S': case 'A': case 'D':
    case 'Q': case 'E': case 'Z': case 'C':
      if (modoAuto) {
        // No AUTO, só sai com a MESMA letra 2 vezes em até CONFIRMA_MS (o app
        // repete o comando a cada 300 ms). Um byte de ruído sozinho não basta.
        static char movPedido = 0;
        static unsigned long tPedido = 0;
        unsigned long agora = millis();
        if (c != movPedido || agora - tPedido > CONFIRMA_MS) {
          movPedido = c;
          tPedido = agora;
          break;                                   // 1ª vez: só anota
        }
        movPedido = 0;
      }
      modoAuto = false;                            // a pessoa assume o controle
      cmd = c;
      break;
    case '0': case 'M':
      modoAuto = false;
      cmd = '0';
      mover(0, 0);
      break;
    case 'I':
      if (!modoAuto) { modoAuto = true; cmdIA = 'G'; cmd = '0'; tPerigo = millis(); }
      break;                                       // já em AUTO: só renova o watchdog
    case 'H':
      break;
    case 'G': case 'R': case 'P':
      if (modoAuto) cmdIA = c;
      break;
    case 'K':
      if (!modoAuto && cmd == '0') querCalibrar = true;   // o loop confere o resto
      break;
    case 'B':
      bipCurto();
      break;
    default:
      if (c >= '1' && c <= '9') { nivel = c - '0'; break; }
      return;                                      // \r, \n e outros: ignora
  }
  ultimoRx = millis();                             // comando válido: renova o watchdog
}

void lerEntrada() {
#if BT_HARDWARE
  while (Serial.available()) tratar((char)Serial.read());
#else
  while (btSoft.available()) tratar((char)btSoft.read());
  while (Serial.available()) tratar((char)Serial.read());   // cabo USB
#endif
}

// Joga fora o que chegou enquanto o robô calibrava (ruído do HC-05 ao ligar
// ou comando velho). O app repete o comando a cada 300 ms, e o Python manda
// "I" de novo quando a telemetria mostra "m":"M"; por isso nada importante se perde.
void descartarEntrada() {
  while (Serial.available()) Serial.read();
#if !BT_HARDWARE
  while (btSoft.available()) btSoft.read();
#endif
}

// ======================= ENVIO AO APP E AO PC =======================
// Número com casas decimais (o snprintf do Arduino não aceita %f).
void formatarNum(char* saida, float v, uint8_t casas) {
  if (isnan(v) || isinf(v)) v = 0;
  if (v > 999) v = 999;
  if (v < -999) v = -999;
  dtostrf(v, 1, casas, saida);
}

// Linha curta com um número, montada a partir de um texto da flash (PSTR).
bool enviarTextoNum(const char* formato, int valor) {
  char linha[20];
  snprintf_P(linha, sizeof(linha), formato, valor);
  return enviarLinha(linha);
}

bool enviarFW() {
  char linha[24];
  strncpy_P(linha, LINHA_FW, sizeof(linha) - 1);
  linha[sizeof(linha) - 1] = 0;
  return enviarLinha(linha);
}

// Telemetria JSON com as chaves que o Python lê: t, m, df, dc, inc, vib, ev.
bool enviarTelemetria(Ev ev) {
  char dcS[8], incS[8], vibS[8], evS[12], linha[100];
  formatarNum(dcS, dc - baseC, 1);                 // desvio do chão (0 = normal)
  formatarNum(incS, inc, 1);
  formatarNum(vibS, vib, 2);
  strcpy_P(evS, NOME_EV[ev]);
  int d = (int)df;
  if (d > 400) d = 400;
  snprintf_P(linha, sizeof(linha),
             PSTR("{\"t\":%lu,\"m\":\"%c\",\"df\":%d,\"dc\":%s,\"inc\":%s,\"vib\":%s,\"ev\":\"%s\"}"),
             millis(), modoAuto ? 'A' : 'M', d, dcS, incS, vibS, evS);
#if BT_HARDWARE
  return enviarLinha(linha);
#else
  // SoftwareSerial: no Bluetooth vai só 1 de cada 4 (1 por segundo); o USB recebe todas.
  static uint8_t vez = 0;
  vez++;
  return enviarLinhaPara(linha, (vez & 3) == 0);
#endif
}

// Marca o próximo envio pelo horário PREVISTO (não pelo real), para a média
// ficar no período certo mesmo com o laço de 30 ms. Se atrasou muito, recomeça.
void agendar(unsigned long &t, unsigned long agora, unsigned periodo) {
  t = (agora - t < 2UL * periodo) ? t + periodo : agora;
}

// No máximo UMA mensagem por passada do loop, para não lotar o Bluetooth
// (9600 baud = ~960 letras/s; aqui usamos ~360 letras/s).
void enviarAoApp(unsigned long agora, Ev ev) {
  static unsigned long tDist = 0, tFlags = 0, tTel = 0;
  static bool ultColl = false, ultHole = false;
  bool coll = obst || frenteFalhou();
  // Mudou: avisa logo (mas no máximo 10 vezes por segundo, se o sensor oscilar).
  bool mudou = (coll != ultColl || buraco != ultHole) && agora - tFlags >= 100;

  if (mudou || agora - tFlags >= FLAGS_MS) {
    if (filaLivre() >= 24) {                       // as duas linhas juntas
      enviarTextoNum(PSTR("COLLISION:%d"), coll ? 1 : 0);
      enviarTextoNum(PSTR("HOLE:%d"), buraco ? 1 : 0);
      ultColl = coll; ultHole = buraco; tFlags = agora;
    }
    return;
  }
  if (agora - tDist >= DIST_MS) {
    int d = (int)df;
    if (d > 400) d = 400;
    if (enviarTextoNum(PSTR("DIST:%d"), d)) agendar(tDist, agora, DIST_MS);
    return;
  }
  if (agora - tTel >= TELEM_MS) {
    if (enviarTelemetria(ev)) agendar(tTel, agora, TELEM_MS);
    return;
  }
  if (agora - tFw >= FW_MS) {
    if (enviarFW()) tFw = agora;
  }
}

// ======================= SETUP / LOOP =======================
void setup() {
  pinMode(BUZ, OUTPUT);
  buzzer(false);                                   // buzzer quieto desde o início
  pinMode(ENA, OUTPUT); pinMode(ENB, OUTPUT);
  pinMode(IN1, OUTPUT); pinMode(IN2, OUTPUT);
  pinMode(IN3, OUTPUT); pinMode(IN4, OUTPUT);
  // ECHO com pull-up: se o fio soltar, o pino fica em HIGH e o robô percebe.
  pinMode(TRIG_F, OUTPUT); pinMode(ECHO_F, INPUT_PULLUP);
  pinMode(TRIG_C, OUTPUT); pinMode(ECHO_C, INPUT_PULLUP);
  mover(0, 0);                                     // motores parados

#if BT_HARDWARE
  Serial.begin(9600);                              // a serial de hardware é o HC-05
#else
  Serial.begin(115200);                            // cabo USB
  btSoft.begin(9600);                              // HC-05 no D10/D11
#endif

  Wire.begin();
  // I2C travado não pode travar o robô: desiste depois de 3 ms e reinicia o I2C.
  // (O pacote Arduino AVR 1.8.3+ tem essa função, mas não define WIRE_HAS_TIMEOUT.
  //  Se der erro aqui, atualize o "Arduino AVR Boards" no Gerenciador de Placas.)
#if defined(WIRE_HAS_TIMEOUT) || defined(ARDUINO_ARCH_AVR)
  Wire.setWireTimeout(3000, true);
#endif

#if !BT_HARDWARE
  Serial.println(F("Calibrando: deixe o robo PARADO no plano por 2 s..."));
#endif
  calibrar();
#if !BT_HARDWARE
  Serial.print(F("Base do chao: "));
  Serial.print(baseC, 1);
  Serial.println(baseOk ? F(" cm") : F(" cm (FALHOU: sem eco ou leituras instaveis; mande K)"));
  Serial.println(mpuFalhou() ? F("MPU-6050: FALHOU") : F("MPU-6050: ok"));
#endif

  buzzer(true); delay(80); buzzer(false);          // bipe curto: pronto
  descartarEntrada();
  enviarFW();
  tFw = millis();
  ultimoRx = millis();
}

void loop() {
  lerEntrada();                                    // comandos que chegaram
  bombearSaida();                                  // envia a fila aos poucos
  atualizarBuzzer();

  static unsigned long tLoop = 0;
  unsigned long agora = millis();
  if (agora - tLoop < LOOP_MS) return;
  tLoop = agora;

  if (querCalibrar) {                              // comando K
    querCalibrar = false;
    if (podeCalibrar()) {
      bool ok = calibrar();
      descartarEntrada();
      if (ok) bipCurto();                          // 1 bipe: calibrou
      else    bipesFalha();                        // 3 bipes: base ruim
      ultimoRx = millis();
    } else if (!modoAuto && cmd == '0') {
      bipesFalha();                                // parado, mas com perigo: recusa
    }
    return;
  }

  atualizarMPU(agora);
  lerSonar();

  // Watchdog: sem comando há 1,5 s.
  bool semSinal = (agora - ultimoRx >= WATCHDOG_MS);
  if (modoAuto) {
    btPerdido = semSinal;                          // para, mas continua em AUTO
  } else {
    btPerdido = false;
    if (cmd != '0' && semSinal) cmd = '0';         // MANUAL: para
  }

  Ev ev = calcularEvento();
  if (modoAuto) controlarAuto(agora);
  else          aplicarManual(agora);
  escolherSom(ev);
  atualizarBuzzer();

  enviarAoApp(agora, ev);
}
