# Termo de imparcialidade do avaliador — e a hipótese de remover a anonimização

> **Status: DECISÃO DE PRODUTO COMPLETA (2026-08-23) — Opção 4 (remoção
> completa, todos os dados) + termo obrigatório como trava de acesso por
> processo, com substituição de avaliador impedido. Implementação ainda NÃO
> iniciada.** Todas as perguntas (seções 0.1 e 0.3) foram respondidas pelo
> dono do produto. Falta desenho técnico do fluxo de substituição de
> avaliador (Q5', o item de maior complexidade) antes de abrir PR de
> implementação. Nenhum arquivo `.java`/`.html`/`.js` foi alterado até aqui.

---

## 0. Decisão registrada (2026-08-23)

Depois de ler a análise completa (seções 1–8), o dono do produto respondeu
às perguntas da seção 8 e confirmou a decisão abaixo — inclusive depois de
lhe ser apresentada explicitamente a alternativa de menor risco (automatizar
a redação do nome nos documentos anexados, sem remover a anonimização do
avaliador).

### 0.1 O que foi decidido

- **Q1 (origem):** a anonimização nasceu de convenção/boa prática da equipe,
  **não** de um episódio concreto contestado ou de cobrança externa.
- **Q2 (problema real, reformulado):** o motivador original era o esforço
  operacional de **apagar manualmente o nome de dentro dos documentos
  clínicos anexados** (laudos/exames trazem o nome do paciente escrito no
  corpo, página a página — o carimbo automático de capa não resolve isso).
  Uma alternativa que resolveria exatamente esse esforço **sem** expor o
  nome ao avaliador (automação de redação/OCR) foi oferecida e **rejeitada
  explicitamente** — a decisão é prosseguir com a exposição do nome de
  qualquer forma. Portanto: **o esforço de redação deixou de ser o
  argumento decisivo**; a decisão passa a se apoiar só no termo de
  imparcialidade como salvaguarda.
- **Q4/Q6 (termo sem remoção / consulta externa):** o dono do produto **não
  quer só a Opção 1** (termo aditivo, mantendo anonimização) — quer
  prosseguir com a exposição do nome. **Não pretende** consultar
  jurídico/regulatório antes (posição explícita, contrária à recomendação
  da seção 7, item 2 — registrada aqui como decisão informada e assumida
  pelo dono do produto, não como omissão).
- **Mecanismo do termo, definido pelo usuário:** o termo de imparcialidade
  **não é meramente informativo** — é uma **trava de acesso por caso**. Se o
  avaliador, ao ser confrontado com o termo, **não se comprometer**, ele
  **fica impedido de avaliar aquele processo específico** (precisa ser
  substituído por outro avaliador). Isto é mais próximo da **Opção 2**
  (declaração por caso, com trava) combinada com a **Opção 4** (nome
  exposto) do que da Opção 1 pura — ou seja, a decisão final é um **híbrido
  não previsto nas 4 opções originais da seção 4**: nome exposto (Opção 4) +
  aceite obrigatório e bloqueante por processo (mecanismo da Opção 2).

### 0.2 O que isso confirma da análise das seções 1–7 (não anula, reforça)

- A ressalva da seção 3.2.4 (erosão dos DTOs projetados) e da seção 5.5
  (tabela arquivo-a-arquivo) continuam valendo **integralmente** — a decisão
  de expor o nome não elimina o cuidado de manter os DTOs projetados
  expondo *mais* campos, nunca a entidade inteira.
- A seção 5.4 (impacto no fluxo de decisão da Opção 2) passa a ser
  **obrigatória**, não mais hipotética: como o avaliador pode ficar
  impedido por processo, o sistema PRECISA de um caminho de substituição
  que hoje não existe (`ProcessoService.AVALIADORES_POR_PROCESSO = 3`,
  atribuídos no cadastro, sem mecanismo de troca pós-atribuição) — ver
  pergunta em aberto Q5' abaixo.
- A condição 3 da seção 7 (nunca expor CPF/nome da mãe junto) continua
  valendo até segunda ordem — **não foi perguntado nem confirmado** que o
  escopo inclui esses campos; assumir por padrão que é **só nome completo**
  até o usuário dizer o contrário (ver Q3' abaixo).

### 0.3 Perguntas fechadas em 2026-08-23 (substituem as Q3/Q5/Q7/Q8 originais)

**Q3' — Escopo do dado exposto.** **RESOLVIDO — TODOS os dados**: nome
completo, `pacienteCpf`, `pacienteDataNascimento` e `pacienteNomeMae`
passam a chegar ao avaliador. **Decisão contrária à recomendação da análise**
(seção 4, Opção 4, nota final: "mesmo nesta opção, não juntar CPF/nome da
mãe no mesmo pacote... dados de identificação civil sem utilidade clínica
na avaliação — expô-los seria o pior custo pelo menor benefício"). Registrada
aqui como decisão informada e assumida pelo dono do produto, não como
omissão da análise.

**Q5' — Substituição do avaliador impedido.** **RESOLVIDO — o operador
substitui por outro médico.** Confirma a leitura da seção 5.4: isto exige um
fluxo/tela **novo**, que hoje não existe (`ProcessoService
.AVALIADORES_POR_PROCESSO = 3` só atribui no cadastro, sem caminho de troca
pós-atribuição). O processo deve continuar sempre com 3 avaliadores efetivos
— nunca decidir com só 2 por causa de um impedimento não resolvido. Este é
o item de maior complexidade de engenharia da decisão inteira, porque toca
`ProcessoValidator`/maioria simples, e merece desenho técnico dedicado antes
de codar (não é "adicionar um botão").

**Q7' — Frequência do termo.** **RESOLVIDO — por processo.** Cada processo
pede o aceite de novo; o avaliador pode recusar pontualmente um caso
específico mesmo já tendo aceitado o termo em processos anteriores.

**Q8' — Retroatividade e comunicação.** **RESOLVIDO — só a marcação
retroativa**, sem aviso prévio a avaliadores/solicitantes antes da mudança
valer. Processos decididos antes desta mudança ficam marcados como
"regime anonimizado" no dossiê/Relatório Final, para que fique claro, daqui
a anos, sob qual regra cada processo foi julgado — mas a mudança em si entra
em vigor sem comunicação prévia às partes.

### 0.4 Estado da decisão: completa, pronta para desenho técnico

Todas as 8 perguntas originais (Q1–Q8) e as 4 de acompanhamento (Q3'/Q5'/
Q7'/Q8') estão respondidas. **O que falta antes de codar não é mais decisão
de produto — é desenho técnico**, principalmente do fluxo de substituição de
avaliador impedido (Q5'), que é o único item que toca a regra de decisão
mais protegida do sistema. Recomenda-se um relatório de desenho técnico
próprio para esse fluxo específico antes de abrir qualquer PR de
implementação — os demais itens (exposição dos DTOs, campo de aceite no
`Parecer`, marcação retroativa) são bem mais diretos e já estão esboçados
nas seções 5.1–5.5 acima.

---

## 1. A ideia, como veio do dono do produto

> Remover a anonimização hoje aplicada ao material que o avaliador vê (só
> iniciais do paciente) — o avaliador passaria a ver o **nome completo** e,
> possivelmente, os demais dados de identificação (`pacienteCpf`,
> `pacienteDataNascimento`, `pacienteNomeMae`). **No lugar** dessa proteção
> técnica, o avaliador assinaria/aceitaria um **termo de imparcialidade**
> antes de analisar cada processo.

O pedido tem duas partes que **não são a mesma coisa** e, criticamente,
**não precisam andar juntas**:

- **(A)** ADICIONAR um termo de compromisso formal de imparcialidade.
- **(B)** REMOVER a anonimização (mostrar o nome completo ao avaliador).

O restante deste documento parte dessa separação, porque ela é o achado mais
importante da análise: **(A) é barato, reversível e provavelmente bom
independentemente de (B). (B) é caro, irreversível e é onde mora todo o
risco.** Tratá-los como um pacote único é o principal jeito de errar aqui.

---

## 2. O que a anonimização é hoje, de fato (levantado no código)

Não é uma regra isolada num `if`. É uma **premissa estrutural** que atravessa
o sistema. Levantamento por `grep` (`iniciais|imparcial|anonimiz`):

- **27 arquivos** em `src/main/java` mencionam explicitamente
  iniciais/imparcialidade/anonimização — com concentração em
  `ProcessoDetalheController` (41 ocorrências), `RegistroEnvioService` (17),
  `EmailTemplateService` (16), `AvaliadorController` (12),
  `ProcessoValidator` (11), `SolicitacaoAvaliadorService` (9),
  `PdfCabecalhoStamper` (6).
- **23 arquivos de teste** cobrem esse comportamento.
- Templates do Portal do Avaliador (`avaliador/lista.html`,
  `avaliador/votar.html`) trazem o aviso ao médico **na própria tela**:
  *"Por preservação da imparcialidade do julgamento, os dados do paciente são
  identificados apenas pelas iniciais"*.

Os mecanismos concretos que existem **só** por causa dessa regra:

| Mecanismo | Arquivo | O que faz |
|---|---|---|
| `Iniciais.de(...)` | `service/Iniciais.java` | "Mariana da Rosa Martins" → "M.R.M."; usado em tela, e-mail, PDF e auditoria |
| DTOs projetados (`ProcessoVotoView`, `ParecerVotoView`, `ParecerPendenteView`, `ParecerHistoricoView`, `ParecerDispensadoView`) | `web/AvaliadorController.java` | O template **nunca recebe** a entidade `Processo`/`Parecer` — só campos escolhidos a dedo, "para um `th:text` futuro digitado errado não conseguir vazar o nome por acidente" |
| PDF consolidado anonimizado (`SOLICITACAO_AVALIADOR`) | `service/SolicitacaoAvaliadorService.java` | Funde os documentos clínicos e carimba cada página com número + **iniciais** |
| Anonimização de metadados do PDF | `service/PdfCabecalhoStamper.java` | `setInfoDictionary` + XMP — apaga até chave `/Info` customizada "envenenada" com o nome (o navegador mostra o `Title` na aba) |
| `VerificadorNomePaciente` | `service/VerificadorNomePaciente.java` | Bloqueia, no chat operador→avaliador, mensagem que cite nome do paciente ou equipe solicitante. Calibragem própria, documentada, com decisão de produto explícita por trás |
| Auditoria com iniciais | `AuditoriaController`, `ProcessoExportacaoController`, `SolicitanteController` | Mesmo `/auditoria`, que é ADMIN-only, usa `Iniciais.de()` |
| Whitelist de anexos do avaliador | `AvaliadorController.baixarPdf` | Só `SOLICITACAO_AVALIADOR` e `INFO_COMPLEMENTAR_AVALIADOR` — nunca um anexo qualquer por id |
| Informação complementar mediada | `service/InfoComplementarAvaliadorService.java` | O texto do solicitante **nunca** chega cru ao avaliador: o operador redige/revisa antes |
| E-mails ao avaliador | `service/EmailTemplateService.java` | Convite, lembrete e aviso de cancelamento levam **só iniciais** |

E vai além do nome: o avaliador também não vê **equipe solicitante**,
**co-avaliadores**, **votos alheios**, nem — em modo leitura — **o resultado
da decisão** do processo (`AvaliadorController.votar`, atributo
`motivoLeitura`). A regra 17 do `CLAUDE.md` diz que CPF/data de
nascimento/nome da mãe "**nunca** chegam ao avaliador".

**Três incidentes reais de vazamento acidental já aconteceram** e foram
tratados como bug grave (`docs/CATALOGO-BUGS-CONHECIDOS.md`, seção 5):
`PROCESSO_CADASTRADO` logando o nome completo (2×, é uma recaída
documentada) e `ProcessoExportacaoController` vazando o nome dentro de
`dossie.nomePasta()`. Ou seja: a equipe já gastou esforço real defendendo
essa fronteira **de dentro para fora**.

### 2.1 O que NÃO se sabe (e é a pergunta mais importante)

O `CLAUDE.md` diz, repetidamente e em maiúsculas, que a anonimização é
**"convenção da equipe de Urgência Renal, NÃO é LGPD"**. Mas em nenhum
lugar do repositório está registrado **quem** convencionou, **quando**, e
**por qual episódio concreto**. Não há ata, não há e-mail, não há
justificativa original.

Isso é decisivo e **não pode ser respondido lendo código**. Se a
anonimização nasceu de um caso concreto (um avaliador que reconheceu um
paciente e a decisão foi contestada; uma cobrança do Ministério Público, de
um conselho, de um comitê de ética), remover é reabrir uma ferida
específica. Se nasceu só de "pareceu boa prática" numa reunião, o custo de
revisitar é muito menor. **É a pergunta Q1 da seção 8.**

---

## 3. A tensão central

### 3.1 O argumento a FAVOR da mudança (levado a sério)

Não é um pedido caprichoso. Há razões defensáveis:

1. **Anonimização por iniciais nunca foi garantia real de anonimato.** O RS
   tem uma comunidade de nefrologia/transplante pequena. Um avaliador que
   recebe "M.R.M., processo 12/2026" mais um prontuário com idade, comorbidades,
   histórico dialítico, tempo de lista e centro de origem **frequentemente
   consegue identificar o paciente** — sobretudo em cidade pequena ou quando
   o próprio avaliador atende naquele serviço. Chama-se reidentificação por
   quase-identificadores, e é um problema conhecido: iniciais são uma
   proteção fraca contra alguém do próprio meio.
2. **Segurança do paciente.** Avaliar um caso de urgência renal sem
   identificação inequívoca cria risco clínico real: troca de paciente,
   homônimo, prontuário que não bate. CPF/data de nascimento existem
   exatamente para desambiguar. Uma decisão de priorização em transplante é
   uma decisão de vida — errar de paciente é pior que o viés que se está
   tentando evitar.
3. **Conflito de interesse hoje é detectado por heurística frágil.**
   `ConflitoEquipeMatcher` casa sigla × nome por extenso × cidade contra uma
   tabela `ALIASES` mantida na mão, e o `CLAUDE.md` avisa: instituição nova
   fora do `ALIASES` cai num fallback por tokens. Ou seja: o sistema hoje
   detecta conflito **de instituição**, e mal. Ele **não detecta** conflito
   pessoal — o avaliador ser médico assistente do paciente, parente, ou ter
   uma relação prévia. Paradoxalmente, **ocultar o nome impede o próprio
   avaliador de identificar o conflito que só ele conseguiria identificar.**
4. **Rastreabilidade e responsabilização.** Um termo aceito, com timestamp e
   IP, é um artefato auditável que hoje não existe. Anonimização não deixa
   rastro nenhum de compromisso assumido.

O ponto 3 é o mais forte, e merece ser dito com clareza: **a anonimização
atual pode estar produzindo um falso sentido de segurança**, ao mesmo tempo
em que cega o único mecanismo confiável de detecção de conflito (a
autodeclaração de quem conhece o caso).

### 3.2 O argumento CONTRA (por que ainda assim é arriscado)

1. **Termo e anonimização são mitigações de natureza diferente — e não são
   substitutos.** Este é o cerne.
   - Anonimização é **controle estrutural/preventivo**: o dado enviesante
     não chega. O viés fica *impossível* (na medida em que o anonimato
     funciona), sem depender da virtude de ninguém.
   - Termo é **controle administrativo/detectivo**: o dado chega, o viés
     torna-se *possível*, e a mitigação passa a depender de
     auto-policiamento humano — cuja falha é, por definição, invisível
     (viés implícito não é percebido nem por quem o tem; é o achado
     central da literatura de vieses cognitivos).

   Trocar um controle preventivo por um administrativo é um **rebaixamento
   de categoria de controle**, não uma troca lateral. Em qualquer framework
   de risco isso exige justificativa positiva, não só "achamos que dá".

2. **Irreversibilidade.** Uma vez que o avaliador Fulano viu o nome do
   paciente Beltrano no processo 12/2026, **isso não se desfaz**. Não é uma
   cor de badge que se reverte com um commit. Isso pesa muito contra a
   estratégia natural de "vamos experimentar e ver como fica": um piloto de
   3 meses cria um passivo permanente em todos os processos do período. E
   piora: se um indeferimento desse período for contestado depois, o fato de
   o avaliador conhecer a identidade vira munição para a contestação — a
   defesa institucional ("o julgamento foi cego") deixa de existir
   retroativamente.

3. **O argumento "a anonimização já não funciona bem" corta para os dois
   lados.** Sim, ela é imperfeita. Mas:
   - "Imperfeita" ≠ "inútil". Ela funciona no caso comum (paciente de outro
     centro, sem relação com o avaliador) e falha no caso difícil.
     Remover troca "protege na maioria dos casos" por "não protege em caso
     nenhum".
   - O argumento prova demais: pela mesma lógica se removeria qualquer
     mitigação parcial. A pergunta certa não é "é perfeita?", é "o que
     acontece na margem que ela ainda protege?".
   - E há um efeito que o código não mede: **a anonimização sinaliza uma
     norma**. O aviso na tela do avaliador ("julgue sem saber quem é")
     comunica o valor institucional a cada acesso. Removê-lo comunica o
     oposto, mesmo que um termo diga o contrário — comportamento segue o
     desenho do sistema mais do que segue o texto que se assina.

4. **A arquitetura assume anonimização como permanente.** Não é retórica —
   os DTOs projetados do `AvaliadorController` existem literalmente para
   tornar o vazamento *inexprimível*. Se o nome passar a ser exibido, esses
   DTOs perdem o propósito, tendem a ser "simplificados" de volta para a
   entidade inteira, e aí **também** vazam CPF, nome da mãe, equipe
   solicitante, votos alheios — coisas que ninguém pediu para expor. A
   remoção precisaria manter a disciplina dos DTOs expondo *mais* campos, o
   que é exatamente o tipo de invariante que degrada em 6 meses de manutenção.

5. **Precedente institucional/regulatório não checado.** O `CLAUDE.md`
   afirma que não é LGPD — e provavelmente está certo quanto à base legal
   do tratamento (o avaliador tem finalidade legítima de saúde). **Mas LGPD
   não é a única norma em jogo**, e este documento **não** afirma qual se
   aplica. Precisam ser verificados por quem tem competência jurídica, e
   **antes** de decidir:
   - princípio da **minimização** (LGPD art. 6º, III — tratar só o
     necessário à finalidade): se o julgamento é clinicamente possível sem o
     nome, expor o nome pode ser tratamento além do necessário, mesmo com
     base legal válida;
   - regras do **SNT/Sistema Nacional de Transplantes** e da Central
     Estadual de Transplantes sobre critérios de priorização e conflito de
     interesse;
   - normas do **CFM/CREMERS** sobre atuação em comissões/câmaras técnicas e
     impedimento;
   - regimento interno da própria CET-RS, se houver — que pode ter a regra
     escrita, tornando isto uma alteração de regimento, não de software.

   **Não assumir que "não é LGPD" significa "é livre".** São coisas
   diferentes.

6. **Teatro de conformidade.** Um termo que todo mundo aceita com um clique
   antes de cada processo vira ruído em 2 semanas. Tem valor real de
   auditoria e de responsabilização (é oponível: "você declarou"), mas valor
   quase nulo de mitigação de viés. Vendê-lo internamente como "substituto
   equivalente da anonimização" é o risco de discurso mais provável — e o
   mais difícil de defender se for questionado depois.

---

## 4. As opções, lado a lado

### Opção 1 — Termo ADICIONAL, sem remover a anonimização *(menor risco)*

O avaliador aceita o termo de imparcialidade antes de votar (ou no primeiro
acesso, com re-aceite periódico). **Nada do que ele vê muda.**

- **Ganha:** artefato auditável de compromisso, reforço explícito da norma,
  responsabilização formal, base para o Relatório Final/dossiê citar que o
  parecer foi emitido sob termo aceito.
- **Perde:** não resolve segurança do paciente (identificação inequívoca)
  nem detecção de conflito pessoal.
- **Risco:** baixo. Aditivo puro, reversível, não toca em nenhum invariante.
- **Custo:** pequeno — um campo, uma tela, um teste.

### Opção 2 — Declaração de não-conflito por caso *(meio-termo forte)*

Mantém a anonimização, **mas** o avaliador declara explicitamente, **como
parte do voto**, uma de duas coisas:

- "Declaro não ter identificado este paciente nem relação com ele ou com a
  equipe solicitante"; **ou**
- "Declaro-me impedido" — e aí o operador substitui o avaliador.

Isto é mais forte que um termo genérico, porque é **específico por caso** e
gera uma afirmação falseável, não uma boa intenção.

Achado relevante do código: **`Parecer.impedido` já existe** (`domain/
Parecer.java`, "Membro impedido por ser o solicitante do processo
(conflito)") e é **lido** por `RelatorioService`, `ExportacaoProcessoService`
e `PainelLinha` — mas **nenhum código o escreve**. Existe um conceito de
impedimento já modelado, já exibido em relatório, e **inteiramente sem
caminho de entrada**. A Opção 2 daria uso real a um campo hoje órfão, em vez
de inventar estrutura nova.

- **Ganha:** endereça o furo real (conflito pessoal invisível ao sistema)
  sem entregar o nome a ninguém. O avaliador que *já* reidentificou o
  paciente ganha um caminho para se declarar impedido — hoje ele não tem.
- **Perde:** não resolve a identificação inequívoca do paciente
  (segurança clínica).
- **Risco:** baixo/médio. Precisa de regra para o que acontece quando alguém
  se declara impedido (substituir o 3º avaliador; o processo volta a ter só
  2 votos e a maioria simples não fecha — **isso mexe no fluxo de decisão**,
  então exige desenho cuidadoso, ver 5.4).

### Opção 3 — Revelação graduada / sob demanda *(meio-termo)*

Mantém iniciais por padrão, mas oferece um botão explícito
**"Revelar identidade — justificar"**, que exige motivo (ex.: "preciso
confirmar que é o mesmo paciente do prontuário X") e fica registrado em
auditoria + no Relatório Final.

- **Ganha:** resolve o caso de segurança clínica (dúvida de identidade) sem
  tornar a exposição rotineira; a exceção fica visível e contável, e em 3
  meses se sabe **com dados** se a exposição era necessária em 2% ou em 90%
  dos processos — o que informa a decisão maior.
- **Perde:** complexidade; e se todo mundo clicar sempre, virou a Opção 4
  com passos a mais (mas aí o dado terá provado a necessidade).
- **Risco:** médio. É a única opção que **gera evidência** para decidir
  depois, em vez de decidir no escuro agora.

### Opção 4 — Remoção completa + termo *(o pedido original; maior risco)*

Nome completo (e possivelmente CPF/data de nascimento/nome da mãe) visíveis
ao avaliador, com termo aceito antes.

- **Ganha:** resolve identificação inequívoca e conflito pessoal; simplifica
  a operação (menos anonimização, menos mediação de informação complementar,
  menos calibragem de `VerificadorNomePaciente`).
- **Perde:** o controle preventivo, de forma **irreversível**; a defesa
  institucional "o julgamento foi cego"; e abre a porta para a erosão dos
  DTOs projetados (item 3.2.4).
- **Risco:** alto, e **não é só técnico** — é institucional e reputacional.
- **Nota:** mesmo nesta opção, **não** juntar CPF/nome da mãe no mesmo
  pacote. Nome completo já basta para identificação clínica na esmagadora
  maioria dos casos; CPF/nome da mãe são dados de identificação civil sem
  utilidade clínica na avaliação — expô-los seria o pior custo pelo menor
  benefício.

---

## 5. O que cada opção quebraria na arquitetura *(esboço, sem implementar)*

### 5.1 Onde o aceite do termo seria registrado (vale para 1, 2, 3 e 4)

Seguir o padrão já validado de `Parecer.eraCoordenadorNoVoto`: **snapshot no
momento do ato, nunca estado "ao vivo"**. Um `MembroUrgenciaRenal.termoAceito
= true` global seria o antipadrão exato que o projeto já corrigiu uma vez
(ver `CATALOGO-BUGS-CONHECIDOS.md` 4.6: `temVotoCoordenadorFavoravel` lia o
cargo ao vivo, não o papel no voto).

- Campo em `domain/Parecer.java`: `termoImparcialidadeAceitoEm`
  (`LocalDateTime`, **nullable**, `@Column(name = "termo_imparcialidade_aceito_em")`).
  Nullable é obrigatório e semanticamente correto: parecer antigo = "termo
  não existia", e coluna nova numa tabela populada nasce `NULL`
  (`ddl-auto: update` não faz backfill — `CATALOGO-BUGS-CONHECIDOS.md` 1.1).
  **`null` nunca deve ser tratado como aceite**, pela mesma lógica
  conservadora já adotada para `eraCoordenadorNoVoto`.
- Provavelmente também `termoImparcialidadeVersao` (`String`/`Integer`): se o
  texto do termo mudar, o dossiê precisa saber **qual texto** foi aceito. Sem
  isso, o artefato perde valor jurídico logo na primeira revisão do texto.
- Auditoria: ação nova (ex. `TERMO_IMPARCIALIDADE_ACEITO`) com IP, via
  `AuditoriaService.registrar(acao, detalhe, ip)` — **só número do processo
  + rótulo do médico**, jamais nome de paciente (regra 5.1/5.2 do catálogo).
- Exibição: `service/RelatorioService.java` (Relatório Final) e
  `service/ExportacaoProcessoService.java` (dossiê).
- Ponto de aceite: `AvaliadorController.registrarVoto`, validado
  **server-side antes de abrir a TX 1 do voto** — exatamente o padrão já
  usado para a justificativa obrigatória (checkbox no HTML é burlável por
  DevTools; a trava de verdade mora no controller).

### 5.2 Opção 1 (termo adicional) — impacto

Praticamente nulo além do item 5.1: `avaliador/votar.html` ganha o bloco do
termo, `AvaliadorController.registrarVoto` ganha a validação, e a suíte
ganha testes (incluindo o caminho de falha sem mock — regra 3.1 do catálogo,
já que voto é escrita irreversível). Nenhum invariante de imparcialidade é
tocado.

### 5.3 Opção 3 (revelação sob demanda) — impacto

- Endpoint novo em `AvaliadorController` (`POST /avaliador/{id}/revelar`),
  com justificativa obrigatória e auditoria.
- `ProcessoVotoView` ganharia `pacienteNome` **condicional** — e aqui mora
  um risco de desenho: o DTO deixa de ser "seguro por construção" e passa a
  ser "seguro se o controller preencher certo". Mitigação: **dois DTOs
  distintos** (`ProcessoVotoView` e `ProcessoVotoViewRevelado`), nunca um
  campo opcional no mesmo record.
- O PDF `SOLICITACAO_AVALIADOR` continuaria anonimizado (é gerado uma vez, no
  envio, e é o mesmo arquivo para os 3 avaliadores — revelar por avaliador
  exigiria gerar 3 PDFs distintos, o que **não vale a pena**; a revelação
  ficaria só na tela).

### 5.4 Opção 2 (declaração de impedimento) — impacto no fluxo de decisão

O ponto delicado, e o motivo de esta opção não ser tão barata quanto parece:
se um dos 3 avaliadores se declara impedido, **a maioria simples de 2 em 3
não fecha mais** com os 2 restantes discordando, e o processo trava. Precisa
de regra explícita:

- o operador substitui o avaliador impedido (novo `Parecer` para outro
  membro) — mas isso exige um caminho de substituição que **hoje não
  existe** (`ProcessoService.AVALIADORES_POR_PROCESSO = 3`, atribuídos no
  cadastro);
- `ProcessoValidator` precisa saber ignorar pareceres impedidos ao contar
  favoráveis/desfavoráveis, sem quebrar `FAVORAVEIS_PARA_DEFERIR = 2` /
  `DESFAVORAVEIS_PARA_INDEFERIR = 2` nem a exceção do coordenador;
- `RegraDecisao` provavelmente precisa de um caso novo ou de detalhe extra.

**Isso mexe no núcleo de regra de negócio mais protegido do sistema.** Se a
Opção 2 for escolhida, ela merece um relatório próprio de desenho — não cabe
em "adicionar um checkbox".

### 5.5 Opção 4 (remoção completa) — impacto

O que precisaria ser revisitado, arquivo a arquivo:

| Mecanismo | O que acontece |
|---|---|
| `service/Iniciais.java` | Deixa de ser usado no Portal do Avaliador. **Continua** necessário na auditoria (regra 5.1 do catálogo é independente) — não apagar a classe |
| DTOs projetados (`AvaliadorController`) | Passam a expor `pacienteNome`. **Manter os DTOs mesmo assim** (senão CPF/nome da mãe/equipe/votos alheios vazam junto) |
| `SolicitacaoAvaliadorService` | O PDF consolidado e o carimbo página a página deixam de precisar de iniciais; `nomeArquivoOficial` muda |
| `PdfCabecalhoStamper.anonimizarMetadados` | Perde o propósito no fluxo do avaliador. **Cuidado:** tem teste próprio (`PdfCabecalhoStamperTest`) que verifica o "envenenamento" de chave `/Info` |
| `VerificadorNomePaciente` | Perde a razão de existir no chat operador→avaliador. Toda a calibragem de 2026-08-10 (decisão de produto explícita) vira código morto |
| `InfoComplementarAvaliadorService` | A mediação do operador (redigir/revisar antes de encaminhar) perde metade da justificativa |
| `EmailTemplateService` | Convite/lembrete/cancelamento ao avaliador poderiam levar o nome — **não recomendado mesmo na Opção 4**: e-mail sai do perímetro autenticado do sistema |
| `avaliador/lista.html`, `avaliador/votar.html` | Avisos de imparcialidade na tela precisam ser reescritos, não só apagados |
| `ProcessoDetalheController` (41 ocorrências) | Comentários e gates de anonimização a revisar um a um |
| **23 arquivos de teste** | Vários passam a testar o oposto do que testam hoje |
| `ConflitoEquipeMatcher` | Continua útil, mas deixa de ser a única defesa contra conflito |

Estimativa honesta: **não é uma tarde de trabalho.** É uma sessão dedicada,
com risco alto de regressão silenciosa, num sistema em produção com deploy
automático.

---

## 6. Recomendação *(a decisão é do dono do produto)*

**Recomendo desacoplar (A) de (B) e implementar apenas a Opção 1 agora, com
a Opção 3 como próximo passo se o motivo real for segurança clínica, e a
Opção 4 como último recurso — nunca como primeiro.**

Raciocínio:

1. A Opção 1 é **aditiva e reversível**: captura o benefício que o dono do
   produto já quer (compromisso formal, artefato auditável) sem gastar nada
   de irreversível. Se o objetivo era "formalizar a responsabilidade do
   avaliador", ela **entrega isso por inteiro**, hoje.
2. A pergunta que decide entre 2, 3 e 4 é **"qual problema concreto está se
   tentando resolver?"** — e ela ainda não foi respondida (seção 8, Q2). Se
   for segurança clínica (identificar o paciente certo), a resposta é a
   Opção 3, não a 4. Se for conflito de interesse não detectado, é a Opção
   2. Se for "o avaliador reclamou que trabalha às cegas", talvez seja um
   problema de **volume de informação clínica no PDF**, não de identidade —
   e nesse caso melhorar o PDF resolve sem tocar em nada disto.
3. A Opção 4 é a única em que o erro **não tem volta**. Numa decisão
   irreversível sob incerteza, a assimetria manda começar pelo reversível.
4. E há um caminho barato de reduzir incerteza: implementar a Opção 1 (ou 3)
   e **medir** — quantos avaliadores pedem revelação, quantos se declaram
   impedidos, quantas vezes o operador é perguntado "quem é esse paciente?".
   Em 3 meses a decisão sobre a Opção 4 deixa de ser opinião e passa a ter
   base empírica.

**O que eu não recomendo em nenhum cenário:** apresentar o termo
internamente como "substituto equivalente" da anonimização. Ele não é, e
essa afirmação é justamente a que ficaria indefensável se um indeferimento
for contestado. Se a Opção 4 for adotada, que seja com a justificativa
honesta — *"decidimos que a identificação inequívoca do paciente vale mais
que a proteção parcial que o anonimato dava, e assumimos esse trade-off"* —
e não com a narrativa de que nada se perdeu.

---

## 7. Se a Opção 4 for mesmo escolhida — condições mínimas

Não como recomendação, mas para que a decisão não seja executada mal:

1. **Registro formal da decisão** com data, quem aprovou e justificativa —
   fora do código (ata/e-mail da coordenação da CET-RS), não só um commit.
2. **Parecer jurídico/regulatório por escrito** antes do deploy (ver 3.2.5).
3. **Não expor CPF/nome da mãe junto.** Nome completo e data de nascimento
   bastam para desambiguar; o resto é dado civil sem função clínica aqui.
4. **Manter os DTOs projetados** — expondo mais campos, mas nunca a entidade
   inteira.
5. **Manter `Iniciais.de()` na auditoria e nos e-mails** (e-mail sai do
   perímetro autenticado; auditoria é outra regra, independente desta).
6. **Termo com versionamento** (`termoImparcialidadeVersao`), aceite por
   processo, snapshot no `Parecer`, validado server-side antes da TX do voto.
7. **Data de corte visível**: o dossiê/Relatório Final deve deixar claro se
   aquele processo foi avaliado sob regime anonimizado ou identificado —
   senão, daqui a 2 anos ninguém saberá dizer, processo a processo, sob qual
   regra a decisão foi tomada.
8. **Comunicação prévia aos avaliadores e aos solicitantes.** O solicitante
   preencheu o formulário sob um regime; mudá-lo em silêncio é o pior
   caminho possível.

---

## 8. Perguntas em aberto — decisão do usuário

Nenhuma destas é respondível pelo código. Todas precisam ser respondidas
**antes** de qualquer implementação.

**Q1 — Origem da regra.** Por que a anonimização foi adotada? Houve um caso
concreto (decisão contestada, avaliador que reconheceu paciente, cobrança de
MP/conselho/comitê de ética), ou foi uma convenção sem episódio disparador?
Existe registro escrito (ata, regimento da CET-RS, e-mail)?
→ *Decisão do usuário:*

**Q2 — Problema a resolver.** Qual é o problema concreto que motivou a
ideia? (a) avaliador não consegue confirmar que é o paciente certo; (b)
avaliador não consegue detectar conflito de interesse pessoal; (c)
avaliadores reclamam de trabalhar "às cegas"; (d) falta um artefato formal
de responsabilização; (e) outro. **A resposta muda qual opção é a certa.**
→ *Decisão do usuário:*

**Q3 — Escopo do dado.** Se houver exposição, é só nome completo, ou também
CPF / data de nascimento / nome da mãe? (Recomendação: nunca CPF e nome da
mãe.)
→ *Decisão do usuário:*

**Q4 — Termo sem remoção.** Adicionar o termo de imparcialidade **mantendo**
a anonimização (Opção 1) atende ao objetivo? Se não, o que exatamente falta?
→ *Decisão do usuário:*

**Q5 — Declaração de impedimento.** Faz sentido o avaliador poder se
declarar impedido por caso (Opção 2)? Se sim: o que acontece com o processo
— o operador substitui o avaliador, ou o processo decide com 2 pareceres?
(Isto mexe na regra de maioria simples e precisa de desenho próprio.)
→ *Decisão do usuário:*

**Q6 — Consulta externa.** Alguém com competência jurídica/regulatória
(assessoria da Secretaria, comitê de ética, CREMERS) vai ser consultado
antes? Existe regimento da CET-RS que trate de imparcialidade/impedimento e
que precise ser alterado junto?
→ *Decisão do usuário:*

**Q7 — Frequência e forma do termo.** Aceite **por processo** (mais forte,
mais atrito) ou **por período** (ex. anual, menos atrito, menos específico)?
Quem redige o texto? (O texto é jurídico, não técnico — não deve ser
escrito por quem implementa.)
→ *Decisão do usuário:*

**Q8 — Retroatividade e comunicação.** Processos já decididos sob regime
anonimizado ficam marcados como tal no dossiê? Avaliadores e equipes
solicitantes serão comunicados da mudança antes de ela valer?
→ *Decisão do usuário:*

---

## 9. Referências no repositório

- `CLAUDE.md` — "Regras de negócio" (identificação do paciente; item 17,
  dados adicionais que nunca chegam ao avaliador); "Portal do Avaliador
  (/avaliador)"; "Chat / mensageria".
- `docs/CATALOGO-BUGS-CONHECIDOS.md` — seção 5 (imparcialidade / vazamento
  de dado), 4.6 (snapshot vs. estado ao vivo), 1.1 (coluna nova nullable),
  3.1 (teste do caminho de falha em escrita irreversível).
- `src/main/java/br/gov/saude/sgpur/web/AvaliadorController.java` — javadoc
  da classe (regra de imparcialidade) e os records de projeção no fim.
- `src/main/java/br/gov/saude/sgpur/service/Iniciais.java`,
  `VerificadorNomePaciente.java`, `SolicitacaoAvaliadorService.java`,
  `PdfCabecalhoStamper.java`, `InfoComplementarAvaliadorService.java`.
- `src/main/java/br/gov/saude/sgpur/domain/Parecer.java` — campo `impedido`
  (modelado, lido em relatório, **nunca escrito**) e
  `eraCoordenadorNoVoto` (padrão de snapshot a replicar).
