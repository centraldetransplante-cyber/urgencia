# ==============================================================================
# Executa o Robô E2E de Inspeção e Auditoria do SAUR em PRODUÇÃO (Playwright)
#
# Uso:
#   .\e2e-prod.ps1                       -> Pergunta a senha e roda com browser visível
#   .\e2e-prod.ps1 -Senha "SuaSenha"    -> Informa a senha direto (não gravar no histórico)
#   .\e2e-prod.ps1 -Headless             -> Roda sem abrir janela do Chromium (mais rápido)
#   .\e2e-prod.ps1 -AbrirRelatorio       -> Abre o relatório HTML visual ao concluir
#   .\e2e-prod.ps1 -SlowMo 500           -> Ajusta velocidade das ações (ms)
# ==============================================================================

param(
    [string]$Senha = "",
    [string]$Usuario = "admin",
    [string]$Url = "https://urgenciarenal.duckdns.org",
    [int]$SlowMo = 1000,
    [switch]$Headless,
    [switch]$AbrirRelatorio
)

$ErrorActionPreference = "Stop"

# --- Java 21 (força o JDK 21 do projeto) ---
$jdk21 = "C:\Users\rafae\Tools\jdk-21.0.11+10"
if (Test-Path "$jdk21\bin\java.exe") {
    $env:JAVA_HOME = $jdk21
} elseif (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    Write-Host "ERRO: JDK 21 não encontrado. Defina JAVA_HOME para um JDK 21." -ForegroundColor Red
    exit 1
}
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path

# --- Maven ---
$mvn = (Get-Command mvn -ErrorAction SilentlyContinue).Source
if (-not $mvn) {
    $cand = "C:\Users\rafae\Tools\apache-maven-3.9.6\bin\mvn.cmd"
    if (Test-Path $cand) { $mvn = $cand }
}
if (-not $mvn) {
    Write-Host "ERRO: Maven não encontrado. Instale o Maven ou ajuste o caminho." -ForegroundColor Red
    exit 1
}

# --- Resolução segura da Senha do Administrador ---
$senhaFinal = $Senha
if (-not $senhaFinal) {
    if ($env:SAUR_PROD_PASSWORD) {
        $senhaFinal = $env:SAUR_PROD_PASSWORD
    } else {
        Write-Host ""
        Write-Host "=== Autenticação de Produção (SAUR) ===" -ForegroundColor Yellow
        Write-Host "Alvo:    $Url" -ForegroundColor Cyan
        Write-Host "Usuário: $Usuario" -ForegroundColor Cyan
        $securePass = Read-Host "Digite a senha do admin de produção" -AsSecureString
        $bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePass)
        $senhaFinal = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr)
        [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }
}

if (-not $senhaFinal) {
    Write-Host "ERRO: A senha de administrador é obrigatória para executar o robô em produção." -ForegroundColor Red
    exit 1
}

$headed = -not $Headless.IsPresent

Write-Host ""
Write-Host "==========================================================================" -ForegroundColor Cyan
Write-Host "  ROBÔ E2E — INSPEÇÃO EM PRODUÇÃO (SAUR)                                 " -ForegroundColor Yellow
Write-Host "==========================================================================" -ForegroundColor Cyan
Write-Host "  Alvo:            $Url" -ForegroundColor White
Write-Host "  Usuário:         $Usuario" -ForegroundColor White
Write-Host "  Browser Visível: $headed" -ForegroundColor White
Write-Host "  Intervalo Ações: $SlowMo ms" -ForegroundColor White
Write-Host "  JAVA_HOME:       $env:JAVA_HOME" -ForegroundColor Gray
Write-Host "==========================================================================" -ForegroundColor Cyan
Write-Host ""

# Limpa screenshots de execuções anteriores se existirem
$dirScreenshots = "target\e2e-prod-screenshots"
if (Test-Path $dirScreenshots) {
    Remove-Item "$dirScreenshots\*.png" -ErrorAction SilentlyContinue
    Remove-Item "$dirScreenshots\relatorio-execucao.html" -ErrorAction SilentlyContinue
}

# Executa o Maven com o profile e2e-prod. O proprio profile ja pula a suite rapida
# de unidade (surefire skipTests) - nao passar "-Dtest=none" aqui: o surefire 3.x
# aborta o build com "No tests matching pattern" antes do robo sequer abrir.
& $mvn verify -Pe2e-prod `
    "-Dsaur.e2e.headed=$($headed.ToString().ToLower())" `
    "-Dsaur.e2e.baseUrl=$Url" `
    "-Dsaur.e2e.adminUser=$Usuario" `
    "-Dsaur.e2e.adminPassword=$senhaFinal" `
    "-Dsaur.e2e.slowMo=$SlowMo"

$codigoSaida = $LASTEXITCODE

Write-Host ""
Write-Host "==========================================================================" -ForegroundColor Cyan
if ($codigoSaida -eq 0) {
    Write-Host "  RESULTADO: SUCESSO! O robô inspecionou todas as áreas com sucesso.      " -ForegroundColor Green
} else {
    Write-Host "  RESULTADO: FALHA OU ERRO DETECTADO (Código: $codigoSaida)               " -ForegroundColor Red
}
Write-Host "==========================================================================" -ForegroundColor Cyan

$relatorioHtml = "$dirScreenshots\relatorio-execucao.html"
if (Test-Path $relatorioHtml) {
    $fullPath = (Resolve-Path $relatorioHtml).Path
    Write-Host "  Relatório Visual HTML: $fullPath" -ForegroundColor Yellow
    Write-Host "  Evidências em:         $(Resolve-Path $dirScreenshots)" -ForegroundColor Gray

    if ($AbrirRelatorio) {
        Write-Host "  Abrindo relatório no navegador..." -ForegroundColor Cyan
        Start-Process $fullPath
    } else {
        Write-Host ""
        Write-Host "  Para abrir o relatório visual no navegador, execute:" -ForegroundColor White
        Write-Host "  Start-Process '$fullPath'" -ForegroundColor Gray
    }
}
Write-Host ""

exit $codigoSaida
