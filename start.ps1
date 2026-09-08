# Sobe o SAUR (Windows / PowerShell).
# Uso:  .\start.ps1            -> perfil dev (H2)
#       .\start.ps1 prod       -> perfil prod (PostgreSQL via application-local.yml ou env vars;
#                                  producao real roda em Postgres local na VM Oracle desde 2026-07-25,
#                                  nao mais no Neon - ver CLAUDE.md)
param([string]$Perfil = "dev")

# --- Java 21 (forca o JDK 21, mesmo que JAVA_HOME aponte para outra versao) ---
$jdk21 = "C:\Users\rafael-ioppi\Tools\jdk-21"
if (Test-Path "$jdk21\bin\java.exe") {
    $env:JAVA_HOME = $jdk21
} elseif (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    Write-Host "JDK 21 nao encontrado. Instale o Temurin 21 ou defina JAVA_HOME para um JDK 21." -ForegroundColor Red
    exit 1
}
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path

# --- Maven (PATH ou caminho conhecido) ---
$mvn = (Get-Command mvn -ErrorAction SilentlyContinue).Source
if (-not $mvn) {
    $cand = "C:\Users\rafael-ioppi\apache-maven-3.9.9\bin\mvn.cmd"
    if (Test-Path $cand) { $mvn = $cand }
}
if (-not $mvn) {
    Write-Host "Maven nao encontrado. Instale o Maven ou ajuste o caminho em start.ps1." -ForegroundColor Red
    exit 1
}

# --- Libera a porta 3000 (encerra qualquer processo que esteja escutando) ---
$portas = Get-NetTCPConnection -LocalPort 3000 -State Listen -ErrorAction SilentlyContinue
if ($portas) {
    $pids = $portas | Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($processo in $pids) {
        try {
            $nome = (Get-Process -Id $processo -ErrorAction Stop).ProcessName
            Write-Host "==> Liberando a porta 3000 (encerrando $nome PID $processo)..." -ForegroundColor Yellow
            Stop-Process -Id $processo -Force -ErrorAction Stop
        } catch {
            Write-Host "    Nao foi possivel encerrar o PID ${processo}: $($_.Exception.Message)" -ForegroundColor Red
        }
    }
    Start-Sleep -Seconds 1
}

Write-Host "==> Subindo SAUR | perfil: $Perfil | JAVA_HOME: $env:JAVA_HOME" -ForegroundColor Cyan
& $mvn -DskipTests "-Dspring-boot.run.profiles=$Perfil" spring-boot:run
