# Calçada Segura · Robô (app Android)

App de celular Android que controla o robô do Calçada Segura pelo **Bluetooth** (módulo **HC-05**).
Ele faz o mesmo que o app do computador (`app_controle`), só que no celular.

## O que o app faz

- Conecta no HC-05 do robô (Bluetooth clássico, porta serial).
- Tem 9 botões, igual ao app do computador:

  | | | |
  |---|---|---|
  | ↖ Frente esq (Q) | ↑ Frente (W) | ↗ Frente dir (E) |
  | ← Girar esq (A) | ■ Parar (0) | → Girar dir (D) |
  | ↙ Ré esq (Z) | ↓ Ré (S) | ↘ Ré dir (C) |

- **Segure** o botão para andar. Soltou, o robô para.
- **Toque duas vezes** rápido no mesmo botão: o robô continua andando sem você segurar (**modo contínuo**).
  Para sair do modo contínuo, toque em outro botão ou no ■.
- **■ para tudo.**
- Mostra a **distância** do sonar da frente, e se tem **obstáculo** ou **buraco/degrau**.
- Quando aparece obstáculo ou buraco e o robô ia para a frente, o app **para tudo** e o celular vibra.
- Quando o app sai da tela (ou algo cobre a tela, como a cortina de notificações), ele manda o robô **parar**.
- Se o app ficar **15 s fora da tela**, ele desconecta e solta o HC-05.

## Como o app conversa com o robô (protocolo)

O programa do Arduino é o `firmware/robo_app/robo_app.ino`.

**Do celular para o robô** (uma letra, sem Enter):

| Letra | O que faz |
|---|---|
| `W` | Frente |
| `S` | Ré |
| `A` | Girar à esquerda |
| `D` | Girar à direita |
| `Q` | Frente à esquerda |
| `E` | Frente à direita |
| `Z` | Ré à esquerda |
| `C` | Ré à direita |
| `0` | Parar |

O app **reenvia a letra a cada 300 ms**. Se o robô ficar **1,5 s sem receber nada**, ele para sozinho.
Isso é uma proteção: se o Bluetooth cair, o robô não sai andando sem controle.

**Do robô para o celular** (uma linha por vez):

| Linha | O que quer dizer |
|---|---|
| `DIST:32` | Tem algo a 32 cm na frente. `DIST:400` = nada na frente (livre). |
| `COLLISION:1` | Obstáculo na frente (ou degrau alto). `COLLISION:0` = caminho livre. |
| `HOLE:1` | Buraco, degrau para baixo, ou o sonar do chão sem eco (fio solto). `HOLE:0` = chão normal. |

O próprio robô bloqueia W, Q e E quando tem obstáculo ou buraco. Ré e giros continuam liberados.

## Como gerar o APK (o "instalador" do app)

Você precisa, uma vez só:

- **JDK 17** numa pasta `C:\dev\android-ferramentas\jdk-17...` (ou a variável `JAVA_HOME` apontando para ele).
- **Android SDK** em `C:\dev\android-ferramentas\sdk`, com `platforms;android-34` e `build-tools;34.0.0`.
- Na primeira vez, se não existir o `gradlew.bat`, o script usa o **Gradle 8.7** de
  `C:\dev\android-ferramentas\gradle-8.7` para criar o `gradlew.bat`.
- Internet (na primeira vez o Gradle baixa muita coisa).

Depois, abra o PowerShell **dentro** da pasta `app_android`:

1. Abra a pasta `app_android` no **Explorador de Arquivos**.
2. Clique na **barra de endereço** (lá em cima), apague o que estiver escrito, digite `powershell` e aperte **Enter**.
3. Na janela do PowerShell que abrir, rode:

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

Se preferir usar o `cd`, coloque o caminho **entre aspas** (ele tem espaços e acento). Exemplo:
`cd "C:\Users\<seu usuário>\OneDrive - fr-instalacoes\Área de Trabalho\calcada-segura-robo\app_android"`

O script:

1. Copia o projeto para `C:\dev\robo-android-build` (fora do OneDrive, que trava arquivos).
2. Acha o JDK e o SDK.
3. Roda os testes e gera o APK.
4. Copia o APK para **`C:\dev\robo-android-dist\CalcadaSeguraRobo.apk`**.

O `-ExecutionPolicy Bypass` libera o script só desta vez, sem pergunta e sem mudar nada no Windows.

## Como instalar no celular

Precisa de **Android 8.0 ou mais novo**. Em celular mais antigo, a instalação falha com
"Problema ao analisar o pacote".

1. Passe o arquivo `CalcadaSeguraRobo.apk` para o celular. Dois jeitos:
   - **Cabo USB:** ligue o celular no PC, escolha "Transferência de arquivos" no celular e copie o APK
     para a pasta **Download**.
   - **Mandar para você mesmo:** pelo Google Drive ou pelo WhatsApp (como **Documento**), e baixe no
     celular. O Gmail bloqueia arquivos `.apk`.
2. No celular, abra o app **Arquivos** (ou **Downloads**) e toque no `CalcadaSeguraRobo.apk`.
3. Se aparecer "instalar apps desconhecidos": toque em **Configurações** e ligue
   **Permitir desta fonte**. Volte e toque em **Instalar**.
4. Se o **Play Protect** avisar que o app é desconhecido: toque em **Mais detalhes** e depois em
   **Instalar mesmo assim**. (É normal: o app não veio da Play Store.)

## Como parear o robô (uma vez só)

1. Ligue o robô **já no chão plano** e não mexa nele por uns 2 s, até o **bipe curto**: nessa hora ele
   mede o chão. O LED do HC-05 pisca **rápido**.
2. No celular: **Configurações → Bluetooth** (ou **Dispositivos conectados**) → **Parear novo dispositivo**.
3. Escolha **FB21_083** (é o nosso HC-05).
4. PIN: **1234** (se não der, tente **0000**).

O app **não procura** aparelhos sozinho: ele só mostra os que já estão pareados.

## Como usar

1. **Feche o app do computador** (ou desconecte a COM8). O HC-05 aceita **só uma conexão por vez**.
   Ligue o robô **já no chão plano** e não mexa nele por uns 2 s, até o **bipe curto**: ele mede o chão nessa hora.
2. Abra o app **Calçada Segura · Robô** no celular.
3. Toque em **Conectar ao robô**.
   - No Android 12 ou mais novo, o app pede a permissão **"Dispositivos próximos"**. Toque em **Permitir**.
   - Se o Bluetooth estiver desligado, o app pede para ligar.
   - O app lembra o último robô usado. Na primeira vez, se tiver mais de um aparelho pareado, ele mostra uma lista.
4. Quando aparecer **Conectado: FB21_083** (bolinha verde), use os botões.
5. Para usar outro robô, toque em **Trocar robô**.
6. Para ver as linhas que o robô manda (ajuda a achar problemas), toque em **Mostrar mensagens do robô**.
7. Antes de usar o robô pelo PC (COM8), toque em **Desconectar** no app (ou feche o app com o botão
   **Voltar**). Se você só apertar **Home**, o app continua com o HC-05 por 15 s e só depois solta.

## Problemas comuns

| O que acontece | O que fazer |
|---|---|
| LED do HC-05 piscando **rápido** | O HC-05 não está conectado em ninguém. Toque em **Conectar ao robô**. |
| LED piscando **devagar** (2 piscadas e pausa) | Está conectado. Se não for no celular, é o PC que está conectado. |
| "Erro: o robô não respondeu" | Confira se o robô está ligado e perto. **Feche o app do PC / a COM8**: o HC-05 aceita uma conexão só. |
| O PC não conecta na COM8 | O celular ainda está conectado: abra o app e toque em **Desconectar** (ou feche o app com **Voltar**). |
| "O robô não está mandando dados" | Confira se o programa **robo_app** está gravado no Arduino e se o fio **RXD do HC-05 está no D1** (pelo divisor 1k/2k). Os dados do robô saem pelo D1. |
| O robô manda dados, mas não obedece aos botões | Confira o fio **TXD do HC-05 no D0** (ele leva os comandos do celular para o Arduino). |
| Não consigo enviar o programa para o Arduino | **Tire o fio do D0** (TXD do HC-05) antes de enviar. O D0 e o D1 são os mesmos pinos do cabo USB. Depois coloque de volta. |
| O robô para sozinho depois de 1,5 s | É a proteção (watchdog): o robô ficou 1,5 s sem receber comando. Veja se o celular continua conectado. |
| O robô não anda para a frente | Tem obstáculo ou buraco na frente (veja os avisos vermelhos). Ré e giros funcionam. |
| Chão fica sempre em **BURACO OU DEGRAU**, ou aparece **OBSTÁCULO** com a distância "livre" | O robô mediu o chão errado ao ligar. Desligue e ligue o robô **parado no chão plano**. Se continuar, confira os fios do sonar de baixo (TRIG D4, ECHO A0). |
| O robô não aparece na lista | Pareie o robô primeiro (veja acima). |
| Negou a permissão sem querer | Toque de novo em **Conectar ao robô** e escolha **Permitir**. Se o Android não perguntar mais, o app mostra um botão para abrir as configurações. Lá: **Permissões → Dispositivos próximos → Permitir**. |

## Arquivos do projeto

| Arquivo | Para quê |
|---|---|
| `app/src/main/java/.../Protocolo.kt` | Junta os pedaços de texto em linhas e entende `DIST`, `COLLISION` e `HOLE`. |
| `app/src/main/java/.../ControleDeComandos.kt` | Regras dos botões: segurar, toque duplo, parar, perigo. |
| `app/src/main/java/.../ConexaoBluetooth.kt` | Conecta no HC-05, lê e envia (sem travar a tela). |
| `app/src/main/java/.../MainActivity.kt` | A tela: botões, sensores, permissões e batimento de 300 ms. |
| `app/src/main/res/layout/activity_main.xml` | O desenho da tela. |
| `app/src/test/java/...` | Testes que rodam no computador (JUnit). |
| `build.ps1` | Gera o APK. |

Os testes rodam junto com o `build.ps1`. Se algum teste falhar, o APK não é gerado.
