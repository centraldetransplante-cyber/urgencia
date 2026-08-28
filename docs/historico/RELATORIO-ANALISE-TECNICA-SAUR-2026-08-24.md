# Relatório de Análise Técnica — SAUR
**Sistema de Avaliação de Urgência Renal (SAUR)**  
*Data de referência: 24 de agosto de 2026*

Este relatório consolida a investigação profunda realizada sobre o repositório do sistema **SAUR** (código legado sob a nomenclatura/pacote base de **SGPUR**). A análise foi baseada em inspeção estática dos arquivos de configuração, código de produção, relatórios de vistorias anteriores e execução direta da suíte automatizada de testes.

---

## 1. Arquitetura e Stack Tecnológica

O SAUR é um sistema web monolítico moderno estruturado sobre a plataforma Java. A stack de tecnologia e a organização de sua infraestrutura são compostas por:

*   **Linguagem de Programação:** Java 21 (executado rigorosamente com o JDK 21).
*   **Framework Central:** Spring Boot 3.5.16.
    *   *Módulos Ativos:* `spring-boot-starter-web` (REST/MVC), `spring-boot-starter-data-jpa` (Persistência), `spring-boot-starter-thymeleaf` (Template Engine), `spring-boot-starter-security` (Segurança), `spring-boot-starter-validation` (Validação de entrada), `spring-boot-starter-aop` (Intercepção por aspectos), `spring-boot-starter-mail` (Comunicação SMTP) e `spring-boot-starter-actuator` (Monitoramento).
*   **Camada de Persistência / Banco de Dados:**
    *   *Desenvolvimento e Testes:* H2 Database (`v2.4.240` - banco em memória).
    *   *Produção:* PostgreSQL (`v42.7.13` - rodando em infraestrutura local na VM Oracle da Secretaria de Saúde desde 25/07/2026).
*   **Interface com o Usuário (Front-end):** Thymeleaf integrado ao Spring Boot (Server-Side Rendering) estilizado com Bootstrap (`v5.3.8`) e Bootstrap Icons (`v1.11.3`) importados via WebJars. Toda a responsividade e padrão de fontes (Inter) são servidos localmente.
*   **Módulo de Geração de Documentos:** OpenPDF (`v1.3.34` - ramificação moderna e mantida do iText/LibrePDF) para a geração de Relatórios Finais e Ofícios.
*   **Ambiente de Build e Dependências:** Apache Maven (`3.9.6` ou superior).
*   **Ambiente Operacional (VM):** Ubuntu Linux hospedado na VM Oracle Cloud, utilizando o gerenciador de processos `systemd` (`sgpur.service`), Nginx como proxy reverso com HTTPS gerenciado via Certbot (Let's Encrypt).

---

## 2. Principais Módulos do Sistema

O sistema é modularizado no nível de pacotes por responsabilidade arquitetural (`domain`, `repository`, `service`, `web`, `bootstrap`, `config`), dividindo os seus fluxos de negócio da seguinte forma:

### A. Módulo de Processo (Módulo Central)
Gerencia o fluxo de controle, triagem e o ciclo de vida dos processos de urgência renal desde a conversão da solicitação online até o arquivamento ou finalização.
*   **Entidades de Domínio (`domain/`):** `Processo` (contém informações de saúde e iniciais do paciente, status atual e motivo do indeferimento), `ControleUrgencia` (gestão de prioridades), `Anexo` (com tipos bem definidos em `TipoAnexo`), `Sexo` (enum), `StatusProcesso` (enum de estados: *SOLICITADO, ENVIADO, SOLICITA_INFORMACAO, DEFERIDO, INDEFERIDO, CANCELADO* — corrigido em 24/08: o valor real é `SOLICITADO`, não `RECEBIDO`; confirmado lendo `domain/StatusProcesso.java` diretamente).
*   **Camada de Negócio (`service/`):** `ProcessoService` (CRUD principal, gerenciamento de reaberturas, delegação ao validador), `FluxoProcessoService` (controla os gatilhos visuais das 6 etapas da linha do tempo), `ProcessoValidator` (guarda de precondições do processo), `AnexoStorageService` (upload seguro para pasta local `./data/anexos`).
*   **Controladores Web (`web/`):** `ProcessoListaController`, `ProcessoDetalheController` (exibe a timeline do processo detalhado por abas/cards), `ProcessoDecisaoController` (operações manuais de decisão de deferimento/indeferimento e lembretes), `ProcessoAnexoController` (upload e substituição de arquivos).

### B. Módulo de Parecer e Portal do Avaliador
Permite que médicos avaliadores autenticados acessem a área segura `/avaliador` para revisar casos clínicos anonimizados de pacientes e registrar seus votos.
*   **Entidades de Domínio (`domain/`):** `Parecer` (guarda o resultado, justificativa e carimbo de data), `HistoricoParecer` (arquiva o histórico de pareceres que foram sobrepostos ou invalidados por solicitações de informação complementar), `MensagemAvaliador` (chat privado entre operador e médico).
*   **Camada de Negócio (`service/`):** `SolicitacaoAvaliadorService` (envia convites por e-mail com link único e hash de acesso), `TempoRespostaService` (calcula a média de resposta dos avaliadores em dias), `MensagemAvaliadorService` (chats com médicos).
*   **Controladores Web (`web/`):** `AvaliadorController`.

### C. Portal do Solicitante
Canal de entrada (/solicitante) para a rede de saúde externa submeter novos pedidos clínicos de urgência renal com anexos originais e interagir em tempo real via chat.
*   **Entidades de Domínio (`domain/`):** `SolicitacaoOnline` (status e dados detalhados), `AnexoSolicitacaoOnline`, `RascunhoSolicitacaoOnline`, `MensagemSolicitacao` (conversação).
*   **Camada de Negócio (`service/`):** `SolicitacaoOnlineService` (CRUD e conversão de solicitação em processo), `RascunhoSolicitacaoOnlineService`, `MensagemSolicitacaoService`.
*   **Controladores Web (`web/`):** `SolicitanteController`, `SolicitacaoOnlineTriagemController` (painel administrativo de triagem que permite converter solicitações legítimas diretamente em processos unificados).

### D. Módulo de Auditoria
Assegura a rastreabilidade integral de todas as decisões tomadas dentro da plataforma, em atendimento a requisitos regulatórios estritos de auditoria.
*   **Entidades de Domínio (`domain/`):** `LogAuditoria` (contém IP, username, ação realizada, timestamp e detalhes estruturados).
*   **Camada de Negócio (`service/`):** `AuditoriaService` (centraliza a inserção de logs de forma transacional e independente, além de gerenciar a geração de exportação). É acoplado através de aspectos AOP (`AuditoriaAspect`).
*   **Controladores Web (`web/`):** `AuditoriaController` (consulta de logs paginada com filtro e opção de exportar dados para formato CSV).

### E. Módulo de Relatórios PDF
Responsável pela materialização física de relatórios consolidados e documentos para arquivamento legal.
*   **Camada de Negócio (`service/`):** `RelatorioService` (gera o Relatório Final que consolida a petição inicial, o sumário de transações, histórico de chats, todos os pareceres dos avaliadores e a decisão final).
*   **Utilitários de PDF:** `PdfRelatorioBuilder` (estrutura do documento), `PdfCabecalhoStamper` (utilizado para estampar cabeçalho, rodapé, brasão oficial do RS e paginação em todas as páginas dos arquivos anexados legados para formar o arquivo unificado final), `PaletaPdf` (mantém o design system e identidade visual de cores do Estado).
*   **Controladores Web (`web/`):** `RelatorioController`.

---

## 3. Regras de Negócio Centrais

O núcleo operacional do SAUR é delimitado por rígidas invariantes de negócio mapeadas no código de produção:

1.  **Regra de Decisão (Maioria Simples 2 de 3):** Cada processo de urgência é distribuído obrigatoriamente para **exatamente 3 médicos avaliadores**. O deferimento do processo exige pelo menos **2 votos Favoráveis**; o indeferimento exige **2 votos Não Favoráveis** (além de motivo formalizado de indeferimento).
2.  **Exceção de Coordenador (CET-RS):** Se o médico marcado no banco de dados como coordenador da Central Estadual de Transplantes (`MembroUrgenciaRenal.coordenador = true`) registrar um voto **Favorável**, o processo é **Deferido imediatamente por exceção regimental** com este único voto, sem necessidade de aguardar os outros dois médicos. Esta regra é prioritária apenas para o deferimento; o indeferimento continua exigindo a maioria simples de 2 de 3 votos desfavoráveis.
3.  **Origem e Autenticidade de Pareceres:** O preenchimento manual de pareceres por e-mail/operador foi desativado em definitivo no código (`OrigemParecer.OPERADOR_EMAIL` foi removido). O parecer só ingressa no sistema se for autenticado pelo próprio médico no Portal do Avaliador (`OrigemParecer.AVALIADOR_SISTEMA`), o que garante o não-repúdio através do registro do IP do médico no momento da gravação do voto.
4.  **Imparcialidade e Anonimização:** O médico avaliador está sob restrição de imparcialidade absoluta: no portal, ele nunca vê o nome completo do paciente, os co-avaliadores escalados, a equipe de origem da solicitação, nem os votos já dados por outros médicos. O sistema exibe apenas as iniciais do nome do paciente (ex.: `R.E.I.`) computadas pela classe `Iniciais.java`. Adicionalmente, o operador deve confirmar de forma manual que removeu elementos identificadores dos PDFs originais de saúde antes de autorizar o envio ao Portal do Avaliador (bloqueio físico do envio de anexos não-anonimizados).
5.  **Justificativa Clínica Obrigatória:** Se o médico votar `NAO_FAVORAVEL` ou `SOLICITA_INFORMACAO`, a inserção de uma justificativa de texto clínico detalhada passa a ser **obrigatória** na API (`registrarVoto`). O voto `FAVORAVEL` permanece com justificativa opcional. A justificativa dá suporte legal para o operador lavrar o ofício de indeferimento ou preencher o pedido de informação complementar.
6.  **Bloqueio por Pausa de Informação Complementar:** Se um médico registrar voto de `SOLICITA_INFORMACAO`, o processo é automaticamente pausado para que o solicitante apresente dados adicionais. Enquanto a pausa estiver ativa, nenhum operador consegue decidir o processo (a menos que o Coordenador CET-RS exerça seu direito de voto favorável excepcional, que quebra a trava da pausa para deferir na hora). Uma vez recebida a informação complementar, a pausa é desfeita de forma explícita e a análise é retomada.

---

## 4. Dívida Técnica e Riscos Encontrados no Código

Com base na vistoria sistemática de código finalizada em 22/08/2026, o projeto demonstrou um comportamento exemplar de correção e manutenção. Em 23/08/2026, três grandes riscos relatados anteriormente (Vazamento de usernames na memória, Bloqueio de threads HTTP por uso de `Thread.sleep` no rate-limit de autenticação e Ausência de validação de Magic Numbers no upload de arquivos) foram corrigidos de forma robusta e cobertos por testes unitários e de integração adicionais.

Atualmente, as principais dívidas técnicas e riscos técnicos identificados no código de produção são:

### A. ~~P0 — Vazamento de Credenciais de Banco e SMTP em Logs de Testes~~ (NÃO CONFIRMADO — verificado e descartado em 24/08)
*   **Causa alegada:** que `.\test.ps1` faria o log do Spring Boot imprimir variáveis carregadas de `application-local.yml` (credenciais reais de produção/SMTP).
*   **Verificação real (24/08):** `test.ps1` só roda `mvn test`, sem tocar em `application-local.yml`. O perfil ativo padrão é `dev` (`application.yml:9`, `spring.profiles.active: dev`, banco H2) — nenhum teste da suíte ativa o perfil `local` (`grep` por `@ActiveProfiles("local")` não encontrou nada em `src/test`). `application-local.yml` nem existe na raiz do projeto (só em `src/main/resources/`, listado no `.gitignore`). Também não há nenhum `EnvironmentPostProcessor`/log de propriedades no código que exporia esses valores no boot. **Não há evidência de que esse vazamento aconteça** — achado descartado, mantido aqui riscado só para registro de que foi investigado e não confirmado, não para reabrir sem prova nova.

### B. P1 — Hibernate com Schema Automático em Produção (`ddl-auto: update`)
*   **Causa:** O arquivo `application-prod.yml` define `spring.jpa.hibernate.ddl-auto: update`.
*   **Risco:** Alterações implícitas de schema no banco de produção PostgreSQL sem revisão explícita ou validação. Pode ocasionar falhas silenciosas de dados, bloqueios de tabelas em produção ou perda indesejada de integridade em dados clínicos e transações antigas.
*   **Recomendação:** Substituir por `validate` no arquivo de perfil produtivo e introduzir ferramentas de migração controlada e versionada do banco (como Flyway ou Liquibase).

### C. P1 — Operações Destrutivas e Silenciosas no Tempo de Boot (confirmado na prática em 24/08)
*   **Causa:** A classe `SchemaMigration.java` é disparada em cada inicialização do sistema, realizando exclusão física de tabelas auxiliares temporárias (`_COPY_`) e, via `removerChecksDeEnumObsoletasPostgres()`, **remove incondicionalmente TODA CHECK constraint (exceto NOT NULL)** das tabelas `processo`, `anexo`, `parecer`, `usuario` e `solicitacao_online` — é uma decisão de design deliberada (documentada no javadoc da própria classe), não um bug, para nunca travar o boot quando um enum ganha valor novo.
*   **Confirmado experimentalmente:** nesta mesma sessão, 6 CHECK constraints foram criadas manualmente em produção nessas 5 tabelas (para fechar o gap descrito no relatório anterior de análise técnica gerado via Gemini) — e serão **removidas automaticamente no próximo restart/deploy do `sgpur.service`**, sem nenhuma ação humana, exatamente como este item prevê. Isso explica por que hoje só `controle_urgencia` e `mensagem_solicitacao` mantêm CHECK de enum em produção (não estão na lista varrida por essa rotina).
*   **Risco:** Risco residual de remoção acidental de estruturas em modificações de código e inicializações do sistema; qualquer tentativa futura de "fechar o gap" adicionando CHECK manualmente nessas 5 tabelas específicas será silenciosamente desfeita no próximo boot, sem aviso ao operador.
*   **Recomendação:** Remover operações de migração cruas e destrutivas de código Java de inicialização rápida de boot, externalizando a responsabilidade para scripts SQL idempotentes estruturados (ou, no mínimo, documentar isso explicitamente no CLAUDE.md do projeto para não recair na tentativa de recriar essas constraints).

### D. P1 — Reset de Senha Público Permitindo Lockout (Negação de Serviço) de Usuários
*   **Causa:** O endpoint `POST /usuarios/esqueci-senha` em `UsuarioController.java` solicita apenas o username de login. Ao ser acionado, chama `UsuarioService.resetarSenha`, que invalida a senha atual e grava imediatamente uma senha temporária gerada de forma pseudo-aleatória.
*   **Risco:** Um atacante malicioso com conhecimento do nome de login de um médico avaliador ou administrador pode realizar chamadas automatizadas contínuas. Embora exista um limitador de tentativas (3 resets por janela de 15 minutos), o atacante consegue invalidar o acesso da conta legítima a qualquer momento, forçando lockout contínuo (Negação de Serviço do usuário real).
*   **Recomendação:** Alterar a lógica de reset para geração de um Token de Uso Único com expiração (TTL curto) enviado por e-mail de forma confidencial. O reset físico da senha ativa só deve ocorrer quando o token for de fato validado pelo usuário no sistema.

### E. P2 — Envio de E-mail de Nova Senha Pré-Commit Transacional
*   **Causa:** O método `UsuarioService.resetarSenha` executa o envio de e-mail com a nova senha temporária dentro do bloco transacional e antes de efetivar o commit final da transação do banco.
*   **Risco:** Se houver falha de persistência ou lentidão na consolidação dos dados no PostgreSQL no instante do commit (após o envio de e-mail), a transação sofrerá rollback. O usuário receberá um e-mail contendo uma senha temporária que nunca funcionará no portal, pois o banco reterá a senha original antiga.
*   **Recomendação:** Desacoplar o envio de e-mail do processo transacional de persistência da senha temporária (usando eventos de aplicação como `@TransactionalEventListener` pós-commit ou um padrão de Outbox Transacional).

### F. P3 — Carregamento Dinâmico de Agente Mockito em Testes
*   **Causa:** O aviso de Java Agent carregado dinamicamente é exibido ao iniciar testes unitários devido à autorresolução do inline-mock-maker do Mockito no JUnit 5.
*   **Risco:** Bloqueio futuro de build ao atualizar para versões posteriores do JDK (em que o carregamento dinâmico de agentes será vedado por padrão).
*   **Recomendação:** Configurar o parâmetro JVM explicitamente no Maven Surefire Plugin no arquivo `pom.xml` para anexar o agente Mockito no classpath de inicialização do teste.

---

## 5. Cobertura de Testes

O projeto apresenta um altíssimo padrão de cobertura de testes, demonstrando extremo rigor no desenvolvimento e validação de código:

*   **Resultados de Execução:** Executada com sucesso em ambiente Windows (via `.\test.ps1` rodando em JVM Java 21), a suíte concluiu o ciclo completo do Maven com **BUILD SUCCESS**.
*   **Volume de Testes:** **1.094 testes automatizados** (contagem exata verificada em 24/08 via `target/surefire-reports/*.txt` da última execução local, somando "Tests run" de todos os relatórios — o número de 1.114 citado antes era uma estimativa próxima, não a contagem oficial) integrados e unitários executados com **zero falhas, zero erros e nenhum teste ignorado**. **O CLAUDE.md do projeto está desatualizado** e ainda cita "144 testes" — corrigido nesta mesma sessão.
*   **Tipos de Teste Ativos:**
    1.  **Testes de Integração com Banco em Memória:** Utilizam o perfil `"dev"` e as configurações automáticas de injeção de dependência do Spring Boot para validar fluxos transacionais, integridade de exclusões, conversões de status e conformidade em controllers (ex.: `SolicitanteControllerTest`, `AvaliadorVotoTransacaoIntegrationTest`).
    2.  **Testes de Trava de Arquitetura (Gatilhos):** O projeto emprega testes para garantir o design da arquitetura, como `TransactionalDeClasseNaoPermitidoTest.java`, que bloqueia automaticamente o build caso algum desenvolvedor tente inserir a anotação `@Transactional` no nível de classe de qualquer Controller (mitigando o bug histórico de rollback silencioso que perdia votos de médicos).
    3.  **Testes de Validação de Assinatura de Bytes:** Testes que confirmam a segurança de upload através da biblioteca `AssinaturaArquivoUtilTest`, checando a rejeição de arquivos maliciosos de forma minuciosa.
    4.  **Testes Ponta a Ponta (E2E) com Microsoft Playwright:** Contidos no pacote `br.gov.saude.sgpur.e2e`, simulam cenários e jornadas completas de usuários de ponta a ponta em navegadores de verdade (Chromium visível com atraso de renderização controlado para inspeção humana, rodando via script `.\e2e.ps1`). Esses testes cobrem desde o fluxo do Solicitante no portal, conversão na tela do operador, distribuição automática de pareceres e o voto final dos médicos médicos até a emissão do Relatório Final em PDF.
