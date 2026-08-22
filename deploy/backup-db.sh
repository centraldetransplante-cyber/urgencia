#!/bin/bash
# Backup diario do banco sgpur (PostgreSQL local na VM) + anexos.
# Roda como usuario 'postgres' via /etc/cron.d/sgpur-backup (autenticacao peer,
# sem senha em texto claro), com a saida em /var/log/sgpur-backup.log.
#
# TRAVA DE EXECUCAO UNICA (flock, 2026-08-05): existiam DUAS entradas de cron
# para este script (crontab do postgres + /etc/cron.d/sgpur-backup), as duas as
# 03:00. Os dois processos calculavam quase o mesmo TIMESTAMP, disputavam o
# mesmo arquivo .tmp, um vencia e o outro abortava com "mv: cannot stat" - erro
# diario no log que mascarava qualquer falha de verdade, alem de dois pg_dump
# simultaneos numa VM de 1 GB de RAM. A entrada duplicada foi removida; o flock
# garante que nem um cron novo, nem uma execucao manual sobreposta, repitam o
# problema.
set -euo pipefail

# Re-executa a si mesmo sob flock. -n = nao espera: se ja ha um backup rodando,
# sai avisando em vez de enfileirar mais um pg_dump.
# Lock dentro de BACKUP_DIR (dono postgres), NAO em /tmp: /tmp e compartilhado
# com as outras 3 aplicacoes desta VM (world-writable) - um arquivo de lock ali
# e alvo facil de colisao de nome ou remocao por outro processo/limpeza de tmp.
LOCK="/opt/sgpur/backups/.backup.lock"
mkdir -p "$(dirname "${LOCK}")"   # precisa existir ANTES do flock; BACKUP_DIR so e definido mais abaixo
if [ "${SGPUR_BACKUP_LOCKED:-}" != "1" ]; then
    export SGPUR_BACKUP_LOCKED=1
    # -E 99: codigo de saida proprio para "nao consegui o lock", que separa
    # esse caso (benigno) de uma falha real do backup, que tambem sairia 1.
    # Sem "exec": com exec o processo e substituido pelo flock e o tratamento
    # abaixo nunca rodaria - a execucao ignorada saia com 1 e SEM mensagem
    # nenhuma, que e justamente o silencio que este script tenta eliminar.
    # "|| rc=$?" e obrigatorio: com "set -e" ativo, um comando que retorna != 0
    # fora de um contexto condicional derruba o script na hora - o rc=$? da
    # linha seguinte nunca chegava a ser avaliado, e a execucao ignorada saia
    # com 99 e sem a mensagem, repetindo o silencio que se queria eliminar.
    rc=0
    flock -n -E 99 "${LOCK}" "$0" "$@" || rc=$?
    if [ "${rc}" -eq 99 ]; then
        echo "[$(date +%F\ %T)] AVISO: outro backup ja esta em execucao - esta execucao foi ignorada."
        exit 0
    fi
    exit "${rc}"
fi

DB_NAME="sgpur"
BACKUP_DIR="/opt/sgpur/backups"
RETENCAO_DIAS=14
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
ARQUIVO="${BACKUP_DIR}/sgpur-${TIMESTAMP}.sql.gz"
NOME_ARQUIVO="sgpur-${TIMESTAMP}.sql.gz"
REMOTO="gdrive:sgpur-backups"
# Marcador do ultimo backup OFFSITE confirmado. E o que permite descobrir que a
# copia remota parou, mesmo que o backup local continue funcionando.
MARCADOR="${BACKUP_DIR}/.ultimo-offsite-ok"

log() { echo "[$(date +%F\ %T)] $*"; }

# Alerta por e-mail. O notificador roda como 'sgpur' (via sudoers pontual), o
# unico usuario que le a senha SMTP - o postgres nunca ve a credencial.
#
# TUDO AQUI E BEST-EFFORT, DE PROPOSITO: se o notificador nao estiver
# instalado, se a regra de sudo nao existir ou se o proprio SMTP estiver fora
# do ar, o backup NAO pode falhar por causa disso. A falha do alerta vira uma
# linha no log e nada mais - o oposto seria um backup que para de rodar porque
# o Gmail recusou uma conexao.
NOTIFICADOR="/opt/sgpur/notificar-falha-backup.sh"
alertar() {
    local assunto="$1"
    local corpo="$2"
    if [ ! -x "${NOTIFICADOR}" ]; then
        log "aviso: ${NOTIFICADOR} nao instalado - alerta por e-mail nao enviado."
        return 0
    fi
    if printf '%s\n' "${corpo}" | sudo -n -u sgpur "${NOTIFICADOR}" "${assunto}" >/dev/null 2>&1; then
        log "Alerta por e-mail enviado."
    else
        log "aviso: nao foi possivel enviar o alerta por e-mail (sudoers ou SMTP)."
    fi
    return 0
}

# Alerta de falha "pega-tudo": com pipefail, qualquer comando que falhe daqui
# pra baixo (pg_dump fora do ar, disco cheio, mv, etc.) aborta o script na
# hora via "set -e" - sem este trap, o unico caminho que de fato mandava
# e-mail de alerta era o dump vazio/truncado (mais abaixo), que com pipefail
# e quase impossivel de ocorrer sozinho (a falha real ja aborta antes de
# chegar la). Confirmado experimentalmente que "exit N" explicito (usado
# pelos blocos de alerta abaixo, que ja chamam alertar() e saem por conta
# propria) NAO dispara este trap - o ERR trap so reage a comando que falha
# "organicamente" -, entao nao ha risco de alerta duplicado.
trap 'alertar "[SAUR] FALHA no backup do banco" \
"O script de backup falhou em $(hostname) em $(date +%F\ %T), na linha ${LINENO}.
Verifique o log em /var/log/sgpur-backup.log."' ERR

mkdir -p "${BACKUP_DIR}"
cd "${BACKUP_DIR}"   # cwd previsivel: evita erro de permissao quando chamado de outro diretorio

pg_dump -U postgres -d "${DB_NAME}" | gzip > "${ARQUIVO}.tmp"
mv "${ARQUIVO}.tmp" "${ARQUIVO}"
chmod 600 "${ARQUIVO}"

# Dump vazio/truncado e falha, nao sucesso: sem esta checagem um pg_dump que
# morresse no meio deixaria um .gz minusculo e o script diria "concluido".
TAMANHO=$(stat -c%s "${ARQUIVO}")
if [ "${TAMANHO}" -lt 1000 ]; then
    log "ERRO: dump gerado tem apenas ${TAMANHO} bytes - suspeito de falha do pg_dump. Arquivo mantido para inspecao."
    alertar "[SAUR] FALHA no backup do banco" \
"O backup diario do SAUR falhou em $(hostname) em $(date +%F\ %T).

O dump gerado tem apenas ${TAMANHO} bytes, o que indica que o pg_dump nao
terminou. O arquivo foi mantido em ${ARQUIVO} para inspecao.

Nenhuma copia foi enviada ao Google Drive nesta execucao.

Verifique: sudo tail -30 /var/log/sgpur-backup.log"
    exit 1
fi

# Remove backups mais antigos que RETENCAO_DIAS
find "${BACKUP_DIR}" -name 'sgpur-*.sql.gz' -type f -mtime +${RETENCAO_DIAS} -delete

# ---------- Copia offsite (Google Drive) ----------
# O rclone pode falhar sem derrubar o backup LOCAL, e e exatamente por isso que
# a falha precisa ser gritada: perder a VM sem copia remota e o cenario
# catastrofico. O client_id compartilhado do rclone sera desativado durante
# 2026 - quando isso acontecer, e AQUI que vai aparecer.
OFFSITE_OK=0
# Toda saida (stdout+stderr) das 3 chamadas de rclone desta secao e acumulada
# aqui, alem de continuar aparecendo ao vivo no log de cron (via tee) como
# sempre apareceu - serve so para permitir detectar, depois, um padrao
# especifico de erro (token OAuth expirado) sem mudar nenhum comportamento de
# quem le o log manualmente. Truncado a cada execucao; nao contem segredo
# nenhum (o rclone nunca imprime client_secret/token nessas mensagens).
RCLONE_ERRO_LOG="${BACKUP_DIR}/.ultimo-erro-rclone.log"
: > "${RCLONE_ERRO_LOG}"

if rclone copy "${ARQUIVO}" "${REMOTO}/" 2>&1 | tee -a "${RCLONE_ERRO_LOG}"; then
    # Confirma que o arquivo REALMENTE chegou: "rclone copy" pode retornar 0 e
    # ainda assim nada estar la (credencial expirada em alguns modos, quota).
    if rclone lsf "${REMOTO}/" 2>&1 | tee -a "${RCLONE_ERRO_LOG}" | grep -qx "${NOME_ARQUIVO}"; then
        OFFSITE_OK=1
        date +%F > "${MARCADOR}"
        log "Copia offsite confirmada no Drive: ${NOME_ARQUIVO}"
    else
        log "ERRO: rclone terminou sem erro mas ${NOME_ARQUIVO} NAO esta em ${REMOTO}. Backup offsite FALHOU."
    fi
else
    log "ERRO: falha ao enviar ${NOME_ARQUIVO} para ${REMOTO}. Backup offsite FALHOU."
fi

# Backup dos anexos (so faz sentido se a conexao com o Drive esta boa)
ANEXOS_DIR="/opt/sgpur/data/anexos"
if [ "${OFFSITE_OK}" = "1" ] && [ -d "${ANEXOS_DIR}" ]; then
    if rclone sync "${ANEXOS_DIR}" "${REMOTO}/anexos/" \
            --backup-dir "${REMOTO}/anexos-archive/${TIMESTAMP}/" 2>&1 | tee -a "${RCLONE_ERRO_LOG}"; then
        log "Anexos sincronizados: ${ANEXOS_DIR}"
    else
        log "ERRO: falha ao sincronizar os anexos para o Drive."
        OFFSITE_OK=0
    fi
fi

# ---------- Alerta por e-mail quando a copia offsite falhou ----------
# Este e o unico caminho em que o backup LOCAL funciona e mesmo assim ha um
# problema serio: sem copia remota, perder a VM e perder tudo. Antes disso, a
# falha so aparecia numa linha de log que ninguem le.
if [ "${OFFSITE_OK}" != "1" ]; then
    ULTIMO_OK="(nunca confirmado)"
    [ -f "${MARCADOR}" ] && ULTIMO_OK=$(cat "${MARCADOR}")

    # Deteccao de um padrao especifico: token OAuth do rclone expirado/revogado
    # (achado real em producao em 2026-08, ver CLAUDE.md). Quando reconhecido,
    # o e-mail ganha um paragrafo extra com o comando exato de correcao - sem
    # isso, cada ocorrencia exige reconstruir a investigacao do zero.
    PARAGRAFO_OAUTH=""
    if grep -qiE "invalid_grant|couldn.t fetch token" "${RCLONE_ERRO_LOG}" 2>/dev/null; then
        PARAGRAFO_OAUTH="

ISTO PARECE SER TOKEN OAUTH DO RCLONE EXPIRADO/REVOGADO (nao e cota nem rede):
o erro 'invalid_grant' / 'couldn't fetch token' significa que o refresh_token
salvo em rclone.conf nao vale mais - precisa reautorizar.

Como corrigir:
1. Na VM, como root/ubuntu: sudo -u postgres rclone config reconnect gdrive:
   Responda 'y' (renovar), depois 'y' (usar navegador) as duas primeiras
   perguntas. O comando vai imprimir uma URL do tipo
   http://127.0.0.1:53682/auth?state=... e ficar esperando o codigo.
2. Essa URL so funciona atraves de um tunel SSH ate a porta 53682 da VM
   (o proprio rclone escuta so em 127.0.0.1:53682 na VM). Abra um tunel de
   uma maquina com navegador, por exemplo:
     ssh -L 53682:127.0.0.1:53682 ubuntu@<ip-da-vm>
   e, com o tunel aberto, acesse a URL impressa no passo 1 no navegador
   dessa mesma maquina, logando com a conta Google dona do Drive de backup
   e clicando em Permitir (ignore o erro de 'pagina nao encontrada' no final
   do fluxo - e esperado).
3. Depois de autorizar, o comando do passo 1 ainda vai perguntar 'Configure
   this as a Shared Drive?' - responda 'n' (padrao) para ele terminar e
   salvar o token novo.
4. Confirme com: sudo -u postgres rclone lsf gdrive:sgpur-backups/ --max-depth 1

CAUSA RAIZ PROVAVEL, para nao se repetir: o app OAuth (client_id proprio do
rclone) provavelmente esta em modo 'Testing' no Google Cloud Console, e
refresh tokens de app em Testing expiram sozinhos a cada 7 dias. Para
resolver de vez (nao so remendar de novo), publique o app: no Google Cloud
Console, va em APIs & Services > OAuth consent screen > Publish App. Isso e
uma acao humana no navegador do dono da conta Google, nao pode ser feita
por SSH/script."
    fi

    alertar "[SAUR] FALHA no backup offsite (Google Drive)" \
"O backup diario do SAUR rodou em $(hostname) em $(date +%F\ %T), mas a copia
para o Google Drive FALHOU.

O backup LOCAL esta ok: ${ARQUIVO} ($((TAMANHO / 1024)) KB).
Ultima copia offsite confirmada: ${ULTIMO_OK}

Enquanto isso nao for resolvido, perder a VM significa perder os dados.

Causa mais provavel (generica): o rclone desta VM usa o client_id
compartilhado do projeto rclone, que esta sendo desativado durante 2026. A
correcao e criar um client_id proprio - ver deploy/README-deploy.md, secao
de backup.${PARAGRAFO_OAUTH}

Verifique: sudo tail -30 /var/log/sgpur-backup.log
Ultimo erro do rclone capturado em: ${RCLONE_ERRO_LOG}"
fi

# ---------- Alerta de copia offsite parada ----------
# Se a ultima copia confirmada tem mais de 3 dias, algo esta quebrado ha tempo
# suficiente para merecer destaque no log (e nao passar batido em meio ao ruido).
if [ -f "${MARCADOR}" ]; then
    DIAS=$(( ( $(date +%s) - $(date -d "$(cat "${MARCADOR}")" +%s) ) / 86400 ))
    if [ "${DIAS}" -gt 3 ]; then
        log "ALERTA: ultimo backup offsite confirmado foi ha ${DIAS} dias ($(cat "${MARCADOR}")). Verifique o rclone."
    fi
fi

log "Backup local concluido: ${ARQUIVO} ($((TAMANHO / 1024)) KB) | offsite=${OFFSITE_OK}"
[ "${OFFSITE_OK}" = "1" ]
