# Roda TODOS os testes do SAUR (Windows / PowerShell), forcando o JDK 21.
# Uso:  .\test.ps1                 -> roda todos os testes
#       .\test.ps1 ProcessoServiceTest   -> roda so uma classe de teste

param([string]$Classe = "")

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
    Write-Host "Maven nao encontrado. Instale o Maven ou ajuste o caminho em test.ps1." -ForegroundColor Red
    exit 1
}

Write-Host "==> Rodando testes | JAVA_HOME: $env:JAVA_HOME" -ForegroundColor Cyan
if ($Classe) {
    & $mvn test "-Dtest=$Classe"
} else {
    & $mvn test
}
