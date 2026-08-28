# Relatório de Diagnóstico de Bugs e Controle de Qualidade — SAUR
**Sistema de Avaliação de Urgência Renal (SAUR)**  
*Data: 24 de agosto de 2026*

Este relatório consolida uma investigação aprofundada voltada ao mapeamento, diagnóstico e controle de qualidade de bugs e incidentes técnicos ocorridos na história e desenvolvimento recente do sistema **SAUR** (código legado sob a nomenclatura de pacote **SGPUR**). O SAUR possui um histórico extremamente maduro de monitoramento e contenção de falhas, cujos aprendizados práticos estão catalogados no documento vivo `docs/CATALOGO-BUGS-CONHECIDOS.md`.

---

## 1. Taxonomia das Categorias de Bugs do SAUR

As falhas mapeadas no ecossistema do SAUR dividem-se em 8 principais categorias estruturais, conforme analisado no repositório:

1.  **Persistência e Hibernate:** Problemas decorrentes do uso do Hibernate em ambiente de produção com PostgreSQL vs. o comportamento do H2 em ambiente de desenvolvimento local (como discrepâncias de constraints, falta de backfills e inferência de tipos em queries).
2.  **Thymeleaf e Frontend:** Regressões visuais de renderização, natural templating em Javascript sem tags corretas e formatação ISO obrigatória para inputs de data que causavam ocultação silenciosa de campos preenchidos.
3.  **Concorrência e Controle Transacional:** Problemas clássicos e silenciosos de concorrência e o encapsulamento incorreto de limites transacionais em operações de gravação irreversíveis.
4.  **Fluxo de Decisão (Maioria Simples e Pausas):** Invariantes de negócio críticas de controle do ciclo de vida do processo, incluindo as regras de voto do Coordenador CET-RS e a coordenação de pausas por solicitação de informações adicionais.
5.  **Imparcialidade e Vazamento de Dados:** Vazamentos involuntários de nomes de pacientes em logs de auditoria (em quebra de conformidade de anonimização) e calibragem de algoritmos de processamento de texto de chat.
6.  **E-mail e Anexos:** Falhas lógicas de envio SMTP com arquivos inexistentes, reenvios limpando dados de auditoria temporal de médicos que já haviam votado e validação de formatos reais de upload.
7.  **Infraestrutura e Deploy:** Mudanças inesperadas de IPs efêmeros na Oracle Cloud e segredos de SSH com formatação ausente de quebras de linha quebrando workflows de deploy do GitHub Actions.
8.  **Processo de Build e Ambientes Locais:** Edição concorrente de arquivos corrompendo builds do Maven Surefire e testes de serviço parametrizados com mocks mascarando campos de atualização esquecidos.

---

## 2. Análise de Bugs Críticos e Soluções Aplicadas

Abaixo, detalhamos as causas raízes e as correções aplicadas aos bugs de maior impacto técnico e segurança ocorridos no sistema:

### A. O Bug do Voto Perdido (Rollback Silencioso nos Controllers)
*   **Sintoma:** Após registrar o voto no Portal do Avaliador (`/avaliador`), o médico recebia confirmação visual de sucesso, porém o voto era silenciosamente descartado pelo banco de dados ao final da requisição HTTP, fazendo com que o processo ficasse indefinidamente pendente.
*   **Causa Raiz:** A anotação `@Transactional` era utilizada no nível de classe nos controladores web (ex.: `AvaliadorController`). Ao acionar o POST de registro de voto (`registrarVoto`), a requisição utilizava a mesma transação física para registrar o voto no banco de dados e executar o pós-processamento de regras de negócio (cálculo de status, tentativa de decisão automática, etc.) dentro de um bloco `try/catch`. Se ocorresse uma falha não controlada no pós-processamento (como um erro no envio de e-mails automáticos ou geração de PDFs), o Spring interceptava o erro e marcava a transação inteira como `rollback-only`. O controller capturava o erro no `try/catch` para reportar um fluxo amigável de e-mail ao usuário, mas no momento do commit físico do controller, o banco executava o rollback integral. O voto do médico era perdido.
*   **Correção:**
    1.  Remoção completa de anotações `@Transactional` no nível de classe de todos os controllers do pacote `web/`.
    2.  Implementação de um teste de controle estrito de arquitetura chamado `TransactionalDeClasseNaoPermitidoTest.java`, que impede o build (BUILD FAILURE) caso algum desenvolvedor tente recolocar `@Transactional` a nível de classe em qualquer controlador web.
    3.  Adoção de transações explícitas e curtas utilizando `TransactionTemplate` (`txTemplate`) em `AvaliadorController.registrarVoto` para salvar e commitar fisicamente o voto do médico antes de disparar qualquer passo de pós-processamento vulnerável a falhas.

### B. O Bug da Pausa que Bloqueava os Co-Avaliadores
*   **Sintoma:** Assim que qualquer médico avaliador votava `SOLICITA_INFORMACAO` (gerando a pausa do processo), os outros dois médicos avaliadores escalados para o mesmo processo não conseguiam mais votar: eles recebiam 403 (Proibido) ou o processo simplesmente desaparecia de suas respectivas telas de pendências.
*   **Causa Raiz:** Os métodos `resolverParecerPendente` e `pendenteAtivoParaVoto` em `AvaliadorController` exigiam que o status geral do processo fosse estritamente `ENVIADO` para autorizar a votação de pareceres. No entanto, o método `atualizarStatusPorPareceres` mutava o status geral do processo para `SOLICITA_INFORMACAO` assim que **qualquer** parecer individual fosse registrado com esse resultado, bloqueando o acesso de voto para todos os outros médicos do mesmo fluxo.
*   **Correção:** Criação do método `StatusProcesso.aceitaVotoAvaliador()`, que retorna `true` tanto para processos em estado de `ENVIADO` quanto em `SOLICITA_INFORMACAO`. O gate do Portal do Avaliador passou a usar esse método flexível, garantindo que a pausa clínica bloqueie estritamente a **Decisão Final do operador** (através de `ProcessoValidator.validarPausaDecisao`), mas nunca impeça os demais médicos de exercerem seus pareceres individuais.

### C. Vulnerabilidade P2: Upload de Extensão Falsa (Magic Numbers)
*   **Sintoma (Identificado na Vistoria de Código de 22/08/2026):** O sistema aceitava arquivos de upload que declaravam a extensão permitida no nome (ex: `.pdf`, `.png`), mas que fisicamente continham conteúdos perigosos (como scripts maliciosos ou executáveis `.exe`).
*   **Causa Raiz:** Os serviços de armazenamento `AnexoStorageService` e `AnexoSolicitacaoOnlineStorageService` validavam os arquivos exclusivamente através da extensão textual informada pelo cliente em cabeçalhos HTTP. Um usuário malicioso poderia simplesmente renomear um arquivo perigoso e burlar a trava.
*   **Correção (23/08/2026):** Criação do utilitário `AssinaturaArquivoUtil.java`. Sem adicionar dependências pesadas, o utilitário analisa os primeiros bytes do arquivo buscando seu *Magic Number* (Magic bytes representativos de tipo):
    *   **PDF:** `%PDF-`
    *   **PNG:** `89 50 4E 47 0D 0A 1A 0A`
    *   **JPEG:** `FF D8 FF`
    *   **MSG (Outlook):** `D0 CF 11 E0 A1 B1 1A E1`
    *   Para arquivos `.eml` (texto RFC822 puro), o sistema apenas bloqueia se o cabeçalho contiver a assinatura binária de outros formatos conhecidos (como `MZ` de executáveis Windows). Os storage services agora barram uploads falsificados antes da gravação em disco física.

### D. Vulnerabilidade P2: Thread.sleep no Rate-limit de Login
*   **Sintoma (Identificado na Vistoria de Código de 22/08/2026):** Lentidão extrema e degradação temporária do sistema de login do SAUR sob tentativas contínuas de acesso.
*   **Causa Raiz:** O serviço `LoginAttemptService` aplicava um atraso progressivo usando `Thread.sleep` de até 5 segundos a cada falha de tentativa de login como mitigação de brute-force. Esse atraso era executado diretamente na mesma thread HTTP do Tomcat que atende o cliente. Um ataque simultâneo com múltiplos requests maliciosos preenchia todo o pool de threads do Tomcat com threads em repouso (sleeping), congelando a capacidade do servidor de processar novos requests legítimos de outros módulos.
*   **Correção (23/08/2026):** O atraso foi mantido por ser requisito de produto de segurança, mas foi adicionado um controle de saturação através de um `Semaphore` (`LoginAttemptService.permissoesAtraso`) com limite padrão configurável de até 20 threads em atraso simultâneo. O método utiliza `tryAcquire()` de forma não-bloqueante: caso existam 20 threads dormindo ao mesmo tempo devido ao rate-limit, novas requisições de login falhas apenas pulam o atraso (pula o `Thread.sleep`) e são processadas imediatamente, protegendo o pool de threads do servidor de exaustão e garantindo a resiliência do sistema.

### E. O Bug da Query de Auditoria que Quebrava no Postgres Real
*   **Sintoma:** Ao tentar carregar a listagem paginada em `/auditoria`, a tela exibia erro 500 em produção no Postgres, enquanto funcionava normalmente em desenvolvimento (H2).
*   **Causa Raiz:** A query do repositório continha a cláusula opcional de filtro utilizando o padrão `(:de is null or l.dataHora >= :de)`. O driver JDBC do PostgreSQL no protocolo estendido precisa inferir previamente os tipos de dados de cada parâmetro antes de alocar valores clínicos. Parâmetros passados como nulos e comparados apenas via `IS NULL` impedem o Postgres de determinar se trata-se de tipo Texto, Inteiro ou Timestamp, gerando a exceção do tipo `42P18`. O H2, por tolerância ao padrão SQL, compilava normalmente a query.
*   **Correção:** O serviço passou a normalizar parâmetros antes do repositório: valores vazios de texto ou timestamps opcionais são pré-calculados na camada de serviço (servindo strings vazias ou datas de sentinela mínima) e a query passou a efetuar comparações fixas do tipo `:usuario = '' or ...`, eliminando a necessidade de inferência vaga sobre tipos nulos.

---

## 3. Estratégias Preventivas Adotadas pelo SAUR

Para blindar o sistema contra regressões e ressurgimento de bugs já resolvidos uma vez, o projeto adota os seguintes padrões disciplinares:

1.  **Testes de Atualização com Releitura Obrigatória:** Todo teste que valida métodos de atualização de serviços (`atualizar()`) deve obrigatoriamente preencher todos os campos editáveis com valores novos, executar a gravação, **limpar o contexto de persistência e reler a entidade diretamente do banco de dados (releitura física, sem mocks)** para garantir que nenhum campo de formulário tenha sido esquecido no mapeamento manual de cópia de atributos (conforme descrito no item 10.4 do catálogo).
2.  **Validador Automatizado de Constraints de Enums:** O Hibernate `ddl-auto: update` falha silenciosamente ao atualizar CHECK constraints de colunas modificadas em produção. Para mitigar isso, o SAUR possui o `EnumCheckConstraintValidator.java`, que roda de forma automatizada no boot e verifica se os valores do banco PostgreSQL de produção divergem dos enums Java mapeados, avisando o administrador sem derrubar a subida do servidor.
3.  **Proibições de Concorrência de Processo de Build:** O projeto proíbe estritamente que arquivos fontes de classes ou templates de teste sejam editados em paralelo à execução de comandos como `mvn test` ou `mvn verify`, evitando corrupção de compilação incremental sob diretórios de target (item 10.2).
4.  **Uso Indireto de Filtros em Auditoria (Segurança):** O sistema não grava termos de busca inseridos por operadores nos logs de auditoria por risco direto de vazar dados confidenciais de nomes de pacientes que o operador digitou no input de busca rápida (item 5.2).

---

## 4. Cobertura Analítica das Correções e Qualidade

O sistema alcançou um estado de estabilidade sem falhas ativas de build:

*   **Resultados de Integração:** Todas as correções aplicadas no ecossistema (incluindo o utilitário de magic numbers, rate-limits, semáforos de Tomcat e travas transacionais) estão totalmente integradas e validadas.
*   **Suíte de Testes:** A suíte de testes do SAUR rodando sob o JDK 21 possui **1.094 testes automatizados** (contagem exata via `target/surefire-reports`, verificada em 24/08 — o número de 1.114 citado antes era uma estimativa próxima, não a contagem oficial; mesma correção já aplicada em `docs/RELATORIO-ANALISE-TECNICA-SAUR-2026-08-24.md` e no CLAUDE.md). O build do Maven conclui com status **BUILD SUCCESS** e **zero falhas, zero erros e nenhum teste ignorado**, demonstrando a robustez absoluta das validações e a blindagem contra reintrodução de bugs antigos.

O histórico estruturado do SAUR o posiciona como uma das bases de código mais controladas e protegidas contra regressão na Secretaria de Saúde.
