# Gera o APK do app Android do robô, FORA do OneDrive (o Gradle cria muitos arquivos).
# Uso (PowerShell, dentro da pasta app_android):  .\build.ps1
# Precisa do JDK 17 e do Android SDK (veja o README.md).
# O Gradle escreve avisos em stderr; por isso conferimos o código de saída em vez de parar no aviso.
$ErrorActionPreference = "Continue"

$origem = $PSScriptRoot
$build = "C:\dev\robo-android-build"
$saida = "C:\dev\robo-android-dist"
$ferramentas = "C:\dev\android-ferramentas"

# ---------------------------------------------------------------
# 1) Copia o projeto para C:\dev (sem as pastas geradas)
# ---------------------------------------------------------------
Write-Host "1/5 Copiando o projeto para $build ..."
New-Item -ItemType Directory -Force $build | Out-Null
robocopy $origem $build /MIR /XD build .gradle .idea /XF local.properties /R:2 /W:2 /NFL /NDL /NJH /NJS /NP | Out-Null
# O robocopy devolve 0 a 7 quando deu certo; 8 ou mais é erro.
if ($LASTEXITCODE -ge 8) {
    Write-Host "ERRO: a cópia com o robocopy falhou (código $LASTEXITCODE)."
    exit 1
}

# ---------------------------------------------------------------
# 2) Acha o JDK 17
# ---------------------------------------------------------------
Write-Host "2/5 Procurando o JDK 17 ..."
$javaHome = $null
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\javac.exe"))) {
    $javaHome = $env:JAVA_HOME
} else {
    $jdk = Get-ChildItem -Path $ferramentas -Directory -Filter "jdk-17*" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($jdk -and (Test-Path (Join-Path $jdk.FullName "bin\javac.exe"))) {
        $javaHome = $jdk.FullName
    }
}
if (-not $javaHome) {
    Write-Host "ERRO: não achei o JDK 17."
    Write-Host "Coloque o JDK 17 em $ferramentas\jdk-17... ou ajuste a variável JAVA_HOME."
    exit 1
}
$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$env:Path"
Write-Host "    JDK: $javaHome"

# ---------------------------------------------------------------
# 3) Acha o Android SDK e escreve o local.properties
# ---------------------------------------------------------------
Write-Host "3/5 Procurando o Android SDK ..."
$sdk = $null
if ($env:ANDROID_HOME -and (Test-Path $env:ANDROID_HOME)) {
    $sdk = $env:ANDROID_HOME
} elseif (Test-Path "$ferramentas\sdk") {
    $sdk = "$ferramentas\sdk"
} elseif (Test-Path "$env:LOCALAPPDATA\Android\Sdk") {
    $sdk = "$env:LOCALAPPDATA\Android\Sdk"
}
if (-not $sdk) {
    Write-Host "ERRO: não achei o Android SDK."
    Write-Host "Coloque o SDK em $ferramentas\sdk ou ajuste a variável ANDROID_HOME."
    exit 1
}
$env:ANDROID_HOME = $sdk
Write-Host "    SDK: $sdk"

# O Gradle lê o caminho do SDK deste arquivo. Barras "/" evitam problema com a "\".
$sdkComBarras = $sdk -replace '\\', '/'
Set-Content -Path (Join-Path $build "local.properties") -Value "sdk.dir=$sdkComBarras" -Encoding ASCII

# ---------------------------------------------------------------
# 4) Garante o gradlew.bat (o "Gradle Wrapper")
# ---------------------------------------------------------------
$gradlew = Join-Path $build "gradlew.bat"
if (-not (Test-Path $gradlew)) {
    Write-Host "4/5 Não achei o gradlew.bat. Vou criar com o Gradle instalado ..."
    $gradle = $null
    $pastaGradle = Get-ChildItem -Path $ferramentas -Directory -Filter "gradle-*" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($pastaGradle -and (Test-Path (Join-Path $pastaGradle.FullName "bin\gradle.bat"))) {
        $gradle = Join-Path $pastaGradle.FullName "bin\gradle.bat"
    }
    if (-not $gradle) {
        $comando = Get-Command gradle -ErrorAction SilentlyContinue
        if ($comando) { $gradle = $comando.Source }
    }
    if (-not $gradle) {
        Write-Host "ERRO: não achei o gradlew.bat nem o Gradle 8.7."
        Write-Host "Coloque o Gradle 8.7 em $ferramentas\gradle-8.7 e rode este script de novo."
        exit 1
    }
    & $gradle -p $build wrapper --gradle-version 8.7 --distribution-type bin --console=plain
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $gradlew)) {
        Write-Host "ERRO: não consegui criar o gradlew.bat."
        exit 1
    }
    # Guarda o wrapper no projeto original, para não precisar criar de novo.
    Copy-Item -Path (Join-Path $build "gradlew") -Destination $origem -Force
    Copy-Item -Path $gradlew -Destination $origem -Force
    Copy-Item -Path (Join-Path $build "gradle\wrapper\gradle-wrapper.jar") -Destination (Join-Path $origem "gradle\wrapper") -Force
} else {
    Write-Host "4/5 gradlew.bat encontrado."
}

# ---------------------------------------------------------------
# 5) Roda os testes e gera o APK
# ---------------------------------------------------------------
Write-Host "5/5 Rodando os testes e gerando o APK (a primeira vez demora: o Gradle baixa muita coisa) ..."
& $gradlew -p $build testDebugUnitTest assembleDebug --console=plain
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERRO: o build falhou (código $LASTEXITCODE). Leia as mensagens acima."
    exit 1
}

$apk = Join-Path $build "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apk)) {
    Write-Host "ERRO: o build terminou, mas não achei o APK em $apk"
    exit 1
}
New-Item -ItemType Directory -Force $saida | Out-Null
$destino = Join-Path $saida "CalcadaSeguraRobo.apk"
Copy-Item $apk $destino -Force

Write-Host ""
Write-Host "Pronto! O APK está em: $destino"
Write-Host "Copie esse arquivo para o celular (veja o README.md)."
