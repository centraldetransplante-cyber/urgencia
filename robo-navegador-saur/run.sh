#!/usr/bin/env bash
# Roda o robô navegador SAUR (bash / Git Bash).
#
#   ./run.sh                          navega usando ./robo.config (ou defaults)
#   ./run.sh --headed --max 80
#   ./run.sh --base-url http://localhost:3000
#   ./run.sh --install-browser        baixa o Chromium do Playwright (1ª vez)
#
# Precisa de JDK 21 + Maven.
set -euo pipefail
cd "$(dirname "$0")"

# --- JDK 21 ---
for j in "${JAVA_HOME:-}" \
         "/c/Users/rafael-ioppi/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64" \
         "/c/Users/rafae/Tools/jdk-21.0.11+10"; do
  if [ -n "$j" ] && [ -x "$j/bin/java" ]; then export JAVA_HOME="$j"; break; fi
done
[ -x "${JAVA_HOME:-/nao}/bin/java" ] || { echo "JDK 21 nao encontrado (defina JAVA_HOME)."; exit 1; }
export PATH="$JAVA_HOME/bin:$PATH"

# --- Maven ---
MVN="$(command -v mvn || true)"
[ -z "$MVN" ] && for m in "/c/Users/rafael-ioppi/apache-maven-3.9.9/bin/mvn" \
                          "/c/Users/rafae/Tools/apache-maven-3.9.6/bin/mvn"; do
  [ -x "$m" ] && MVN="$m" && break
done
[ -n "$MVN" ] || { echo "Maven nao encontrado."; exit 1; }

export MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT -Djdk.http.auth.tunneling.disabledSchemes= -Djdk.http.auth.proxying.disabledSchemes= -Dmaven.resolver.transport=wagon"

if [ "${1:-}" = "--install-browser" ]; then
  echo "==> Baixando o Chromium do Playwright..."
  # Proxy corporativo com MITM de TLS quebra o downloader (Node) do Playwright.
  export NODE_TLS_REJECT_UNAUTHORIZED=0
  exec "$MVN" -q compile exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"
fi

# Carrega robo.env (gitignored) se existir: um KEY=VALOR por linha.
# Coloque aqui  SAUR_PROD_ADMIN=suasenha  UMA vez e nunca mais digite nada.
if [ -f robo.env ]; then
  set -a; . ./robo.env; set +a
fi

# A aplicacao web fornece a mesma senha pelo EnvironmentFile do systemd.
# Nao grava nem imprime o valor.
if [ -z "${SAUR_PROD_ADMIN:-}" ] && [ -n "${SGPUR_ADMIN_PASSWORD:-}" ]; then
  export SAUR_PROD_ADMIN="$SGPUR_ADMIN_PASSWORD"
fi

# Em producao o workflow envia o fat JAR; a VM nao precisa ter Maven instalado.
if [ -f target/robo-navegador-saur-jar-with-dependencies.jar ]; then
  exec java -jar target/robo-navegador-saur-jar-with-dependencies.jar "$@"
fi

# Fallback: se o config precisa de ${SAUR_PROD_ADMIN} e ainda não veio de
# lugar nenhum, pergunta (oculto). Só cai aqui se você não criou robo.env.
if [ -f robo.config ] && grep -q 'SAUR_PROD_ADMIN' robo.config && [ -z "${SAUR_PROD_ADMIN:-}" ]; then
  printf 'Senha do admin de produção (crie robo.env pra não repetir): ' >&2
  read -rs SAUR_PROD_ADMIN || true
  echo >&2
  export SAUR_PROD_ADMIN
fi

echo "==> Rodando o robo..."
# Usa Chrome/Edge do sistema; não tenta baixar navegador (quebra atrás do proxy MITM).
export PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
set +e
"$MVN" -q compile exec:java -Dexec.args="$*"
code=$?
set -e
echo
echo "relatorio: $(pwd)/report/index.html"
exit $code
