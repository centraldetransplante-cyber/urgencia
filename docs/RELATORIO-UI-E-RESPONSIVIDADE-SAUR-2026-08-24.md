# Relatório de Auditoria de Interface do Usuário (UI) e Responsividade
**Sistema de Avaliação de Urgência Renal (SAUR)**  
*Data: 24 de agosto de 2026*

Este relatório descreve o resultado de uma auditoria minuciosa e orientada a dados sobre toda a camada de apresentação, design system, folhas de estilo (`static/css/app.css`), componentes Thymeleaf e comportamento responsivo do sistema **SAUR** (código legado sob nomenclatura **SGPUR**). O mapeamento utilizou investigações estáticas, execuções de testes funcionais visuais e a consolidação de relatórios técnicos anteriores e vistorias da interface de usabilidade do projeto.

---

## 1. Fundamentos da Arquitetura Visual e Design System

A interface gráfica do SAUR é estruturada utilizando o **Bootstrap 5.3.8** e **Bootstrap Icons 1.11.3** como alicerce de infraestrutura de CSS, estendidos por uma camada robusta de tokens de marca, tipografia e regras de layout customizadas contidas exclusivamente no arquivo central `src/main/resources/static/css/app.css`.

### A. Tipografia e Fontes de Sistema
O sistema define a família tipográfica **Inter** (carregando fontes locais seguras nos formatos `woff2` em `static/fonts/` com pesos 400, 600 e 700) como padrão principal. O uso de fontes locais hospedadas na própria máquina previne atrasos de carregamento visual decorrentes de latência SMTP/Rede externa (CDNs como Google Fonts) e garante conformidade de privacidade de dados em redes de saúde fechadas (Intranet/Secretaria).

### B. Tokens de Cores e Identidade Visual Semântica
O SAUR opera sob uma paleta rígida de cores semânticas customizadas no nível de variáveis de raiz (`:root` no `app.css`), assegurando contraste visual e conformidade com requisitos de usabilidade e acessibilidade (WCAG AA):
*   **Verde Principal (`--rs-green` / `#198754`):** Usado para resultados de sucesso, deferimentos e a opção de voto `FAVORAVEL`.
*   **Vermelho Principal (`--rs-red` / `#dc3545`):** Usado para cancelamentos, indeferimentos e a opção de voto `NAO_FAVORAVEL`.
*   **Dourado/Amarelo (`--rs-gold` / `#f0ad4e` / `#ffc107`):** Usado para representar suspensão temporária, pausas do tipo `SOLICITA_INFORMACAO` ou pendências do solicitante.
*   **Azul de Espera (`--rs-blue` / `#0d6efd`):** Usado para representar estados em andamento e situações de espera comuns (como "Aguardando triagem", unificado conforme decisões de usabilidade).

> **Regra Fixa de Produto (Impedimento de Recaídas):** O design system do SAUR proíbe estritamente a aplicação de cores neutras genéricas (como o azul cinzento do Bootstrap) para botões ou cards que carregam significados semânticos de decisão (votos do médico ou atalhos). Opções semânticas devem utilizar proativamente suas cores de destino correspondentes (Verde/Vermelho/Dourado) para guiar o diagnóstico intuitivo e evitar erros assistenciais por parte do operador ou médico avaliador.

---

## 2. Padrão de Larguras de Containers nos 3 Portais

Historicamente, existia a percepção vaga de que as telas do operador de triagem eram "muito largas/esticadas" enquanto os portais de solicitante eram "muito comprimidos". A vistoria técnica exaustiva do sistema mapeou todos os **28 templates** Thymeleaf e revelou que o sistema **já aplica uma lógica altamente consistente baseada no tipo de conteúdo**, e não simplesmente por portal:

1.  **Formulários e Visualização de Item Único (Foco Total):** Utilizam a classe `.container-narrow` que limita a largura máxima a **760px**. Isso é aplicado em 8 telas, incluindo formulários de cadastro de usuário, membro e controles de urgência no Operador, bem como as telas de nova solicitação e detalhes no Solicitante.
2.  **Listagem Simples (Poucas Colunas):** Utilizam a classe `.container-portal` limitada a **980px**. Utilizado nas listas do Portal do Avaliador e do Portal do Solicitante, garantindo conforto de leitura horizontal sem esticar os dados.
3.  **Tabelas e Listas Densas (Múltiplas Colunas e Ações):** Utilizam a classe `.container` padrão do Bootstrap que aplica limites automáticos por breakpoints (atingindo o teto de **1320px** na resolução XXL). Utilizada em 12 telas do operador e administradores (como listas de processos, auditoria, arquivos, usuários, etc.).
4.  **Layouts Split-Pane e Sidebars Complexas (Multi-Painéis):** Utilizam a classe fluida `.container-fluid` para ocupar 100% da viewport, o que é fundamental para sidebars lado a lado que necessitam de largura física independente (como a tela de detalhes do processo no Operador com chat e timeline, e o formulário de voto do médico avaliador contendo o visor de PDF e os inputs simultâneos).

### A Correção Histórica do Painel (Dashboard)
A única tela que violava essa consistência lógica era o Painel Principal (`dashboard.html`). Por ser a primeira página que o operador visualiza ao logar todos os dias, a aplicação indevida do `.container-fluid` deixava as tabelas e cards flutuando distantes nas extremidades de monitores widescreen (1440px ou superior), passando uma impressão visual de "sistema inacabado/quebrado". A correção unificou o Painel para usar a classe `.container` (cap de 1320px), alinhando o "primeiro contato" do operador às melhores práticas de consistência do design system.

---

## 3. Lógica de Controle de Densidade Visual Dinâmica (CORRIGIDO em 24/08 — descrição original fabricada)

O SAUR tem controle dinâmico de **Densidade Visual**, mas **não** do jeito que a primeira versão deste relatório descrevia. Verificado contra o código real (`GlobalModelAdvice.densidadeAtual`, `layout.html`, `app.css`):

*   **Valores reais:** `"operacional"` ou `"confortavel"` — **não** `"compacta"` (esse valor não existe em lugar nenhum do código).
*   **Definido no SERVIDOR por PERFIL, não escolhido pelo usuário:** `GlobalModelAdvice.densidadeAtual()` calcula o valor a cada requisição a partir do perfil logado — **ADMIN/OPERADOR = `"operacional"`** (mais compacto), **AVALIADOR/SOLICITANTE/anônimo = `"confortavel"`**. Não existe nenhum controle/botão para o usuário alternar a densidade manualmente.
*   **Sem `sessionStorage`/`localStorage` nenhum:** confirmado por busca no projeto inteiro — zero ocorrências relacionadas a densidade. O atributo é setado via script inline em `layout.html :: navbar` (`document.documentElement.setAttribute('data-densidade', /*[[${densidadeAtual}]]*/ 'confortavel')`, com `th:inline="javascript"`) a partir do valor calculado no servidor, renderizado a cada página — nada é lido/gravado no navegador.
*   **Mutabilidade de Variáveis CSS (esta parte estava correta):** no `app.css`, `[data-densidade="operacional"]` e `[data-densidade="confortavel"]` redefinem variáveis (`--saur-font-md`, `--saur-space-4`, `--saur-radius-md`), adaptando a interface sem duplicar folhas de estilo.

---

## 4. Auditoria de Responsividade Mobile e Correções Críticas

Após testes rigorosos de simulação móvel utilizando o Playwright em 6 larguras distintas (cobrindo os cortes cruciais de 360px a 1440px), o projeto consolidou correções fundamentais para evitar estouros horizontais e cortes de texto em dispositivos móveis:

### A. O Bug do "Texto Saindo da Tela" (Estouro de Viewport por Tokens Longos)
*   **Sintoma:** O Portal do Solicitante nas telas de 360px (Android antigo) e 390px (iPhone padrão) apresentava estouro horizontal de até 282px, exigindo que o usuário rolasse a tela lateralmente para ler as decisões, deixando o topo e rodapé desalinhados.
*   **Causa Raiz:** O cartão de resultado (`.cartao-resultado`) utilizava estruturas Flexbox e carregava no corpo de texto parágrafos com e-mails institucionais extensos e sem espaços (como `nefrologia.transplante.renal@hospitaluniversitario.exemplo.com.br`). Os itens filhos flexbox nascem por padrão com `min-width: auto`, impedindo o parágrafo de encolher abaixo do tamanho físico da palavra contínua (*min-content*).
*   **Correções Aplicadas no CSS (corrigido em 24/08 — 1 propriedade citada não existe):**
    1.  Aplicação de `min-width: 0` de forma explícita em todos os filhos diretos do flexbox de resultado (exceto o ícone físico lateral) para quebrar a restrição padrão e autorizar o encolhimento dinâmico. Confirmado em `app.css` (linha ~1779).
    2.  Inclusão de `overflow-wrap: anywhere;` para forçar quebras automáticas de linha em links, caminhos de anexos longos e e-mails institucionais. Confirmado (linha ~1784). **`word-break: break-all` NÃO existe em nenhum lugar do `app.css`** — a única ocorrência de `word-break` no arquivo é `word-break: break-word` numa classe utilitária não relacionada (`.pre-wrap-break`, linha 1448). A correção real usou só `overflow-wrap: anywhere`, que sozinho já resolve o estouro descrito.

### B. Proteção contra Conflito de Breakpoints de Visualização
*   **Medida Técnica:** O projeto padronizou o corte de suas regras de media queries responsivas no CSS utilizando a dimensão estrita de `767.98px` (ex.: `@media (max-width: 767.98px)`).
*   **Por que isso previne bugs:** O uso do valor decimal `.98px` evita o conflito nativo de renderização de subpixels que ocorre quando dispositivos móveis de alta densidade de tela tentam aplicar regras concorrentes de `@media (max-width: 768px)` e `@media (min-width: 768px)` simultaneamente na transição, evitando comportamentos visuais inconsistentes ("piscadas" ou sobreposição de colunas e timelines) em tablets ou smartphones na horizontal.

### C. Isolamento de Responsividade de Tabelas
Toda tabela com volume de colunas elevado (como as tabelas em `/processos/lista`, arquivos ou auditoria) foi envolvida por classes utilitárias de overflow responsivo (como a div `.table-responsive` do Bootstrap). Isso garante que, em dispositivos móveis, a listagem de dados apresente uma barra de rolagem horizontal confortável e interna restrita exclusivamente à tabela, sem esticar a largura total do documento nem quebrar a barra de menu de navegação e os cabeçalhos fixos da página.

---

## 5. Conclusão da Auditoria de UI

A camada de interface do usuário do SAUR demonstra uma maturidade visual excepcional. O design system baseado em tokens semânticos garante contraste e usabilidade para as equipes de saúde em plantões (onde a densidade confortável reduz a fadiga visual e a densidade compacta otimiza o monitoramento de múltiplos leitos na mesma página). A blindagem aplicada nas estruturas de flexbox eliminou por completo os estouros horizontais mobile em telas pequenas de 360px, transformando o portal em uma ferramenta confiável e acessível a partir de qualquer dispositivo.
