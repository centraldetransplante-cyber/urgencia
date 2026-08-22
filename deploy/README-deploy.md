# Deploy do SAUR — Oracle Cloud "Always Free" (São Paulo)

> **Desatualizado em relação à produção atual (mantido como histórico do
> setup inicial).** Este guia descreve a configuração original, com o banco
> no **Neon** (externo). **Desde 2026-07-25 a produção real roda com
> PostgreSQL local na própria VM** (`localhost:5432`, db `sgpur`, usuário
> `sgpur`) — ver seção "Deploy" do `CLAUDE.md` para o estado atual (IP da VM,
> domínio, deploy automático via GitHub Actions, backup do Postgres local).
> Em particular, a frase "**Não há perda de dados do banco ao recriar a
> VM**" mais abaixo **não é mais verdadeira**: hoje o banco mora na própria
> VM, então recriá-la sem restaurar o Postgres a partir de backup **perde os
> dados**.

VM gratuita (não dorme), disco persistente. A aplicação roda como serviço
`systemd` e o Nginx faz o proxy na porta 80.

> Stack alvo: Ubuntu 22.04 · Java 21 (Temurin) · JAR `sgpur` · Nginx.

---

## 1) Criar a VM no Oracle Cloud

1. Crie a conta em https://www.oracle.com/cloud/free/ (pede cartão **só para
   verificação** — recursos "Always Free" não são cobrados).
2. **Compute → Instances → Create instance**:
   - **Name:** `sgpur`
   - **Region:** `Brazil East (São Paulo)` / `sa-saopaulo-1`
   - **Image:** Canonical **Ubuntu 22.04**
   - **Shape:** `VM.Standard.A1.Flex` (Ampere/ARM — Always Free), **1 OCPU / 6 GB**.
     *(Se não houver capacidade ARM, use `VM.Standard.E2.1.Micro`.)*
   - **SSH keys:** envie sua chave pública (ou gere e baixe a privada).
   - **Create.** Anote o **IP público**.

## 2) Abrir as portas (80 e 443)

**a) No console OCI** (rede virtual):
- Networking → sua **VCN** → **Security List** (ou NSG) → **Add Ingress Rules**:
  - Source `0.0.0.0/0`, IP Protocol `TCP`, Destination port `80`
  - (e outra para `443`, se for usar HTTPS)

**b) No sistema operacional** (a imagem Ubuntu da Oracle usa iptables):
```bash
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
sudo netfilter-persistent save
```

## 3) Instalar o Java 21 (Temurin)

```bash
ssh ubuntu@<IP_PUBLICO>

sudo apt update
sudo apt install -y wget gnupg apt-transport-https
sudo mkdir -p /etc/apt/keyrings
wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public \
  | sudo gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg
echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release; echo $VERSION_CODENAME) main" \
  | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt update
sudo apt install -y temurin-21-jre
java -version   # deve mostrar 21
```

## 4) Preparar usuário e diretórios

```bash
sudo useradd -r -m -d /opt/sgpur -s /usr/sbin/nologin sgpur || true
sudo mkdir -p /opt/sgpur/data/anexos
```

## 5) Enviar os arquivos (rode na SUA máquina, em `c:\Users\rafae\projetos\urgencia`)

Primeiro crie o `deploy/sgpur.env` a partir do `deploy/sgpur.env.example`,
preenchendo a senha do Neon e uma `SGPUR_ADMIN_PASSWORD` forte. Depois:

```bash
scp target/saur-0.0.1-SNAPSHOT.jar ubuntu@<IP>:/tmp/sgpur.jar
scp deploy/sgpur.env                ubuntu@<IP>:/tmp/sgpur.env
scp deploy/sgpur.service            ubuntu@<IP>:/tmp/sgpur.service
```

No servidor:
```bash
sudo mv /tmp/sgpur.jar /opt/sgpur/sgpur.jar
sudo mv /tmp/sgpur.env /opt/sgpur/sgpur.env
sudo mv /tmp/sgpur.service /etc/systemd/system/sgpur.service
sudo chown -R sgpur:sgpur /opt/sgpur
sudo chmod 600 /opt/sgpur/sgpur.env
```

## 6) Subir o serviço

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now sgpur
sudo systemctl status sgpur --no-pager
curl -I http://localhost:8080/login    # espera HTTP 200
```
Logs: `journalctl -u sgpur -f`

## 7) Nginx (porta 80)

```bash
sudo apt install -y nginx
sudo cp /caminho/nginx-sgpur.conf /etc/nginx/sites-available/sgpur
# (ou crie o arquivo com o conteudo de deploy/nginx-sgpur.conf)
sudo ln -sf /etc/nginx/sites-available/sgpur /etc/nginx/sites-enabled/sgpur
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx
```
Acesse **http://<IP_PUBLICO>/** — login `admin` e a senha definida no env.

## 8) (Opcional) HTTPS com domínio

Com um domínio apontando para o IP:
```bash
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d seu.dominio.gov.br
```

---

## Atualizar a aplicação (novas versões)

Na sua máquina: `mvn -DskipTests clean package`. Depois:
```bash
scp target/saur-0.0.1-SNAPSHOT.jar ubuntu@<IP>:/tmp/sgpur.jar
ssh ubuntu@<IP> 'sudo mv /tmp/sgpur.jar /opt/sgpur/sgpur.jar && sudo chown sgpur:sgpur /opt/sgpur/sgpur.jar && sudo systemctl restart sgpur'
```

## Observações
- Os **anexos** ficam em `/opt/sgpur/data/anexos` (disco persistente da VM) — faça
  backup periódico junto com o banco.
- **Desatualizado:** esta seção descrevia o setup original com o banco no
  Neon (externo à VM, sem risco ao recriá-la). **Não é mais o caso** — desde
  2026-07-25 o Postgres roda localmente na própria VM (ver banner no topo
  deste arquivo e a seção "Deploy" do `CLAUDE.md`), então o banco **também**
  precisa de backup próprio (pg_dump) e recriar a VM sem restaurar esse
  backup perde os dados.
- O `sgpur.env` contém segredos: está no `.gitignore` e tem permissão `600`.

## Backup (banco + anexos) — o que realmente roda na VM

O backup de produção é **um script só**, `deploy/backup-db.sh`, que cobre o
banco **e** os anexos:

1. `pg_dump` do banco `sgpur` comprimido em `/opt/sgpur/backups/`
   (retenção local de 14 dias);
2. cópia **offsite** do dump para o Google Drive via `rclone`
   (`gdrive:sgpur-backups/`);
3. `rclone sync` dos anexos (`/opt/sgpur/data/anexos`) para o mesmo destino,
   com versionamento em `anexos-archive/<timestamp>/`.

Instalação na VM (é assim que está hoje):

```bash
sudo install -o postgres -g postgres -m 750 deploy/backup-db.sh /opt/sgpur/backup-db.sh
sudo cp deploy/cron/sgpur-backup.cron        /etc/cron.d/sgpur-backup
sudo cp deploy/cron/logrotate-sgpur-backup   /etc/logrotate.d/sgpur-backup
```

**Agende em UM lugar só.** Até 2026-08-05 existiam duas entradas para o mesmo
script (o crontab do usuário `postgres` **e** `/etc/cron.d/sgpur-backup`),
ambas às 03:00: os dois processos disputavam o mesmo arquivo `.tmp` e um
abortava com `mv: cannot stat`, todo dia, numa VM de 1 GB de RAM rodando dois
`pg_dump` ao mesmo tempo. A entrada duplicada foi removida e o script agora se
protege sozinho com `flock` — uma segunda execução simultânea registra
`AVISO: outro backup ja esta em execucao` e sai com 0, sem tocar em nada.

### Como saber que o backup offsite parou

Esta é a falha que mais importa: o backup **local** continua funcionando e a
cópia remota morre em silêncio. O script foi endurecido para isso:

- confirma que o arquivo **realmente chegou** ao Drive (`rclone lsf`), em vez
  de confiar no código de saída do `rclone copy`;
- em caso de falha, loga `ERRO: ... Backup offsite FALHOU.`, termina o resumo
  com `offsite=0` e **sai com código diferente de zero**;
- grava a data da última cópia confirmada em
  `/opt/sgpur/backups/.ultimo-offsite-ok` e, se ela passar de 3 dias, loga
  `ALERTA: ultimo backup offsite confirmado foi ha N dias`.

Verificação rápida a qualquer momento:
```bash
sudo cat /opt/sgpur/backups/.ultimo-offsite-ok     # deve ser a data de hoje
sudo tail -5 /var/log/sgpur-backup.log             # deve terminar em offsite=1
```

### Alerta por e-mail quando o backup falha

Sem isto, a falha do backup offsite fica só numa linha de log que ninguém lê.
Instalação (3 comandos, na VM):

```bash
sudo install -o sgpur -g sgpur -m 750 deploy/notificar-falha-backup.sh /opt/sgpur/notificar-falha-backup.sh
sudo visudo -c -f deploy/cron/sudoers-sgpur-backup-alerta        # valida ANTES de ativar
sudo install -o root -g root -m 440 deploy/cron/sudoers-sgpur-backup-alerta /etc/sudoers.d/sgpur-backup-alerta
```

E reinstale o `backup-db.sh` (a chamada do alerta vive nele):
```bash
sudo install -o postgres -g postgres -m 750 deploy/backup-db.sh /opt/sgpur/backup-db.sh
```

Teste sem esperar o cron — simula um `rclone` quebrado e deve chegar um e-mail:
```bash
sudo -u postgres mkdir -p /tmp/fakebin
printf '#!/bin/bash\nexit 1\n' | sudo -u postgres tee /tmp/fakebin/rclone >/dev/null
sudo -u postgres chmod 755 /tmp/fakebin/rclone
cd /tmp && sudo -u postgres env PATH=/tmp/fakebin:$PATH /bin/bash /opt/sgpur/backup-db.sh
sudo rm -rf /tmp/fakebin
```
Espere ver `Alerta por e-mail enviado.` e `offsite=0`, e o e-mail na caixa de
entrada. Depois rode uma vez normalmente para restaurar o estado:
`cd /tmp && sudo -u postgres /bin/bash /opt/sgpur/backup-db.sh` (deve terminar
em `offsite=1`).

**Por que o notificador roda como outro usuário.** O backup roda como
`postgres`, que não lê (nem deve ler) o `sgpur.env` com a senha SMTP
institucional. Em vez de copiar a senha para um segundo arquivo, a regra de
sudo deixa o `postgres` **executar** o notificador como `sgpur` — e só isso. A
credencial continua num único arquivo, `600`, dono `sgpur`.

**O alerta nunca derruba o backup**: notificador ausente, sudo negado ou SMTP
fora do ar viram uma linha de aviso no log e o backup segue. Coberto por teste
dos três caminhos (notificador ok / ausente / quebrado).

Destinatário: `SGPUR_BACKUP_ALERTA_EMAIL` no `sgpur.env`, se definido; senão
`SGPUR_MAIL_FROM`.

### Criar um `client_id` próprio para o rclone (PENDENTE, prazo: durante 2026)

Enquanto isto não for feito, o backup offsite **vai parar** quando o Google
desativar o `client_id` compartilhado do rclone. Exige navegador — não dá para
fazer por SSH:

1. Google Cloud Console (https://console.cloud.google.com) → criar/selecionar
   um projeto.
2. **APIs e serviços → Biblioteca** → habilitar a **Google Drive API**.
3. **APIs e serviços → Tela de permissão OAuth** → tipo **Externo** →
   preencher nome do app e e-mail → em *Usuários de teste*, adicionar a conta
   Google que hospeda os backups.
4. **APIs e serviços → Credenciais → Criar credenciais → ID do cliente OAuth**
   → tipo **App para computador**. Guarde o **Client ID** e o **Client secret**.
5. Na VM:
   ```bash
   sudo -u postgres rclone config
   #   e (edit existing) → gdrive → informar client_id e client_secret
   #   → manter scope drive.file → "Use auto config? n" (headless)
   #   → abrir a URL exibida no seu navegador, autorizar, colar o código
   ```
6. Confirmar que voltou a funcionar:
   ```bash
   cd /tmp && sudo -u postgres /bin/bash /opt/sgpur/backup-db.sh   # espera offsite=1
   sudo tail -3 /var/log/sgpur-backup.log                          # sem o NOTICE do client_id
   ```

### Reservar o IP público (PENDENTE — console Oracle)

Se o IP for **efêmero**, parar a instância troca o endereço e derruba de uma
vez o DuckDNS e o certificado. O `oci` CLI não está instalado na VM, então
isto é feito no console:

1. https://cloud.oracle.com → **Compute → Instances** → a instância do SAUR.
2. **Resources → Attached VNICs** → clicar na VNIC principal.
3. **IPv4 Addresses** → no IP público, **Edit** → tipo **Reserved**
   (*"Reserve this public IP"*) → confirmar.
4. Conferir que o endereço continua `163.176.30.222` (mudou de
   `163.176.163.213` — ver incidente "IP público efêmero mudou" no
   CLAUDE.md, corrigido em 2026-08-21). Se mudar de novo, atualize o
   DuckDNS, `sudo certbot renew --force-renewal` e as 3 ocorrências do IP em
   `.github/workflows/deploy.yml`.

Um IP reservado continua dentro do Always Free. **Esta pendência é a causa
raiz confirmada do incidente de 2026-08-21** — sem reservar o IP, o mesmo
problema volta a acontecer na próxima vez que a instância for
parada/reiniciada pelo console Oracle.

> **Pendência conhecida (prazo: durante 2026):** o `rclone` desta VM usa o
> `client_id` compartilhado do projeto rclone, que **será desativado**. O
> aviso aparece em toda execução no log. Quando isso acontecer, o backup
> offsite passa a falhar — hoje de forma barulhenta, graças às checagens
> acima. Correção: criar um `client_id` próprio no Google Cloud Console
> (https://rclone.org/drive/#making-your-own-client-id) e informá-lo em
> `rclone config` (exige navegador, não dá para fazer por SSH).

