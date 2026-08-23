# Relatório de vistoria de código — 22/08/2026

## Escopo e método

Vistoria somente de leitura do projeto SAUR. Após a primeira análise, foi feita
uma segunda passagem sistemática pelos **116 arquivos Java de produção**
(**21.739 linhas**), distribuídos entre web (26), service (46), domain (21),
repository (13), bootstrap (5), config (4) e a classe principal. A passagem
cobriu mapeamento de rotas e autorização, transações, persistência/queries,
operações de arquivo, integrações externas, tratamento de erros e modelos de
domínio. Nenhuma alteração funcional foi feita durante a vistoria.

Foram executados `test.ps1` e verificações estáticas locais. A suíte Maven
concluiu com **1.088 testes em 125 classes**, sem falhas, erros ou testes
ignorados. Os testes E2E de navegador e uma auditoria externa de CVEs de
dependências não foram executados nesta vistoria.

## Achados

### P0 — Rotacionar credencial de banco e impedir vazamento em logs

O arquivo local de configuração não está versionado, o que é correto. Porém, durante `test.ps1`, o log de depuração do Spring imprimiu as propriedades carregadas de `application-local.yml`, incluindo a credencial de banco. Isso expõe o segredo a qualquer destino que capture a saída do build, como console, CI ou logs arquivados.

**Ação recomendada:** rotacionar a credencial já exposta e configurar os logs/testes para mascarar propriedades sensíveis ou não carregar esse arquivo em testes.

### P1 — Schema de produção é alterado automaticamente no boot

[application-prod.yml](src/main/resources/application-prod.yml) usa `spring.jpa.hibernate.ddl-auto: update`. Em produção, alterações implícitas de schema são difíceis de revisar, podem falhar parcialmente e não oferecem trilha de migração reproduzível para dados clínicos.

**Ação recomendada:** substituir por migrações versionadas e revisadas; deixar o Hibernate apenas validar o schema em produção.

### P1 — Migração de schema no boot pode destruir estruturas e ignora falhas

[SchemaMigration.java](src/main/java/br/gov/saude/sgpur/bootstrap/SchemaMigration.java) executa em toda inicialização. Ela remove tabelas cujo nome corresponde a `_COPY_` e remove constraints `CHECK` de tabelas de domínio. Diversos erros são apenas registrados em DEBUG e o boot continua. Isso pode apagar uma estrutura inesperada, remover uma constraint de negócio ou deixar o banco parcialmente migrado sem falhar visivelmente.

**Ação recomendada:** eliminar operações destrutivas automáticas do boot; tornar cada migração explícita, idempotente, versionada e com falha bloqueante quando necessária.

**Corrigido em 2026-08-23:** o comportamento de "nunca bloquear o boot" foi **mantido de propósito** — corrigi-lo contrariaria uma decisão de projeto já documentada em várias partes do `CLAUDE.md` (nunca travar o usuário/deploy; mesmo padrão usado em `EnumCheckConstraintValidator`, "avisa sem derrubar a aplicação"). O que foi corrigido foi a parte real do achado: falhas **inesperadas** (erro de conexão, permissão negada, SQL malformado etc.) caíam em `log.debug`, nível que tipicamente não aparece em log de produção, deixando um erro real invisível para sempre. Agora cada bloco de `SchemaMigration` classifica a falha via uma heurística conservadora por substring de mensagem (`already exists`, `does not exist`, `duplicate`, `syntax error` etc. = esperada/idempotente → DEBUG; qualquer coisa que não bata com um padrão conhecido = inesperada → WARN **com stacktrace completo**, nunca só `e.getMessage()`). Ao final de `run()`, um resumo agregado único em WARN (“N de 4 etapa(s) tiveram falha inesperada”) avisa mesmo que ninguém repare nos WARNs individuais. `run()` continua nunca lançando exceção. Coberto por `SchemaMigrationTest` (Mockito puro + `ListAppender` do Logback capturando o nível real do log), incluindo um teste do caminho de falha esperada (nível DEBUG, sem WARN) e um do caminho de falha inesperada (WARN com stacktrace + resumo agregado, `run()` não lança). Ver também `docs/CATALOGO-BUGS-CONHECIDOS.md`, item 1.4.

### P1 — Reset público de senha permite indisponibilizar contas conhecidas

O endpoint público `POST /usuarios/esqueci-senha` recebe apenas o login e chama `UsuarioService.resetarSenha`. Para uma conta existente com e-mail, ele gera e grava uma nova senha temporária. Assim, alguém que conheça um username pode invalidar a senha vigente até três vezes por janela de 15 minutos. O invasor não recebe a nova senha, mas consegue causar negação de serviço à conta.

Arquivos: [UsuarioController.java](src/main/java/br/gov/saude/sgpur/web/UsuarioController.java) e [UsuarioService.java](src/main/java/br/gov/saude/sgpur/service/UsuarioService.java).

**Ação recomendada:** usar token de recuperação de uso único, com expiração, em vez de trocar a senha imediatamente. Adicionar limitação também por IP e uma proteção contra automação apropriada ao ambiente.

### P2 — Rate limits em memória não removem usernames inéditos

[LoginAttemptService.java](src/main/java/br/gov/saude/sgpur/service/LoginAttemptService.java) e [PasswordResetAttemptService.java](src/main/java/br/gov/saude/sgpur/service/PasswordResetAttemptService.java) mantêm mapas concorrentes indexados por username. Entradas de usernames aleatórios não são removidas por expiração em segundo plano. Um fluxo contínuo de nomes distintos pode causar crescimento de memória do processo.

**Ação recomendada:** usar cache com TTL e tamanho máximo, ou armazenamento externo com expiração; incluir limite por IP.

**Corrigido em 2026-08-23:** ambas as classes ganharam `limparExpirados()`, varrido periodicamente por `RateLimitLimpezaScheduler` (ligado por padrão em produção via `AgendamentoRateLimitConfig`/`app.rate-limit.limpeza.varredura.habilitado`, desligado em dev/teste — mesma convenção de `DecisaoAutomaticaScheduler`/`ComprovanteSntLembreteScheduler`). A cada `app.rate-limit.limpeza.varredura.intervalo-ms` (default 5 min), remove do mapa em memória toda entrada cuja janela já expirou — usa a mesma noção de janela que cada classe já tinha (`janelaMinutos` em `LoginAttemptService`, `JANELA` em `PasswordResetAttemptService`), sem mudar limiar/janela/atraso do rate-limit em si. Coberto por testes que chamam `limparExpirados()` diretamente (sem esperar o scheduler de verdade), usando o mesmo hook de relógio injetável de teste que `LoginAttemptService` já tinha (`usarRelogioParaTeste`, replicado em `PasswordResetAttemptService`).

### P2 — `Thread.sleep` no fluxo de autenticação ocupa threads HTTP

Após erros de login, `LoginAttemptService` aplica atraso progressivo por `Thread.sleep`, chegando a 5 segundos. Requisições simultâneas podem ocupar as threads do servidor e degradar o serviço.

**Ação recomendada:** preferir rate limit antes de alocar trabalho de autenticação, com resposta controlada; não bloquear a thread de requisição para impor atraso.

**Corrigido em 2026-08-23:** o atraso em si foi MANTIDO de propósito (decisão de produto já aprovada — precisa acontecer antes da resposta, é o ponto da mitigação). O que foi corrigido foi o risco real: um `Semaphore` (`LoginAttemptService.permissoesAtraso`, capacidade configurável via `app.login.rate-limit.max-threads-atraso-simultaneas`, default 20) agora limita quantas threads HTTP podem estar dormindo por causa deste atraso ao mesmo tempo. `tryAcquire()` não-bloqueante: se o limite já foi atingido, a tentativa atual PULA o atraso (loga em DEBUG) em vez de esperar a vez — nunca bloqueia esperando o semáforo, nunca impede o login de prosseguir, e não muda o comportamento em uso normal. Coberto por `LoginAttemptServiceTest.aplicarAtrasoComSemaforoSaturadoPulaOAtrasoSemLancarNemTravar`/`aplicarAtrasoComSemaforoLivreDormeEDevolveAPermissao`.

### P2 — E-mail é enviado antes do commit da troca de senha

`UsuarioService.resetarSenha` chama o SMTP dentro da transação e antes de a alteração da senha estar confirmada no banco. Se o commit falhar após o envio, o usuário recebe uma senha temporária inválida. Uma falha ou lentidão SMTP também mantém a transação aberta por mais tempo.

**Ação recomendada:** confirmar a persistência antes de disparar o e-mail e implementar um mecanismo confiável de entrega, como outbox transacional.

### P2 — Upload valida extensão, mas não o conteúdo real

[AnexoStorageService.java](src/main/java/br/gov/saude/sgpur/service/AnexoStorageService.java) e [AnexoSolicitacaoOnlineStorageService.java](src/main/java/br/gov/saude/sgpur/service/AnexoSolicitacaoOnlineStorageService.java) usam allowlist de extensões e aceitam o MIME fornecido pelo cliente. Não há validação da assinatura do arquivo nem varredura antimalware.

**Ação recomendada:** verificar tipo real do conteúdo, tratar MIME como não confiável, adicionar varredura compatível com a infraestrutura e manter downloads como attachment.

**Corrigido em 2026-08-23:** novo utilitário `AssinaturaArquivoUtil` (sem dependência nova — o projeto não usa Apache Tika nem equivalente) verifica a assinatura (magic number) dos primeiros bytes do arquivo contra a extensão declarada: PDF (`%PDF-`), PNG (`89 50 4E 47 0D 0A 1A 0A`), JPEG (`FF D8 FF`) e MSG/OLE2 (`D0 CF 11 E0 A1 B1 1A E1`); para `.eml` (texto RFC822 puro, sem assinatura binária própria) rejeita apenas quando o conteúdo começa com a assinatura binária de outro formato conhecido, incluindo executáveis Windows (`MZ`). Chamado nos DOIS storage services (`AnexoStorageService`/`AnexoSolicitacaoOnlineStorageService`), logo depois da checagem de extensão já existente e ANTES de gravar em disco — rejeita com a mesma mensagem de negócio amigável de sempre, sem vazar detalhe técnico de "assinatura inválida". Varredura antimalware de verdade continua fora de escopo (infraestrutura), como a ação recomendada já observava. Coberto por `AssinaturaArquivoUtilTest` e por casos de rejeição/aceitação em `AnexoStorageServiceTest`/`AnexoSolicitacaoOnlineStorageServiceTest` (PDF/PNG genuínos aceitos; texto puro renomeado para `.pdf` e executável Windows renomeado para `.png`/`.pdf` rejeitados mesmo com extensão "correta").

### P3 — Compatibilidade futura dos testes com JDK

Durante os testes, Mockito carregou um agente dinamicamente. O JDK alertou que esse comportamento poderá ser bloqueado por padrão em versões futuras.

**Ação recomendada:** configurar explicitamente o agente Mockito/Byte Buddy no build antes de atualizar o JDK.

## Resultado da segunda passagem completa

Não foi confirmado um bypass adicional de autorização nas rotas revisadas. As
rotas de operador/admin dependem das regras centralizadas de `SecurityConfig`,
e os portais de solicitante e avaliador também conferem posse ou vínculo no
código antes de expor registros, conversas ou anexos. As queries customizadas
revisadas usam parâmetros nomeados; não foi encontrado SQL montado a partir de
entrada HTTP direta.

Os achados acima foram mantidos porque são riscos reais do código atual, não
apenas observações de estilo. Em particular, os quatro itens de maior impacto
para priorização são: rotação do segredo/logs, schema automático em produção,
migração destrutiva no boot e reset público de senha.

## Controles positivos confirmados

- Autorização de rotas por papel, CSRF e cabeçalhos de segurança em produção, incluindo CSP e HSTS: [SecurityConfig.java](src/main/java/br/gov/saude/sgpur/config/SecurityConfig.java).
- Verificação de posse antes de downloads do portal do solicitante e do avaliador.
- Proteção contra path traversal ao resolver anexos armazenados.
- Versionamento otimista em entidades críticas e testes específicos de concorrência.
- Consultas paginadas e fetch joins em trechos críticos para mitigar N+1.
- Boa cobertura de regressões transacionais, sessões órfãs, votação e fluxos de chat.

## Estado do repositório durante a vistoria

Foram encontrados dois arquivos não rastreados de chat na raiz. Eles não foram alterados:

- `chat_2026_08_22_23_11_37.md`
- `chat_2026_08_22_23_11_38.md`
