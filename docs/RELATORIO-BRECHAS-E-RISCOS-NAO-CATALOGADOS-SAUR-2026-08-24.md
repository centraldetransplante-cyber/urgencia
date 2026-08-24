# Relatório de Investigação Técnica — Riscos e Brechas Não Catalogadas
**Sistema de Avaliação de Urgência Renal (SAUR)**  
*Data: 24 de agosto de 2026*

Este relatório descreve o resultado de uma varredura preventiva e estática realizada sobre o código-fonte, arquitetura e configurações do sistema **SAUR** (SGPUR). O propósito deste documento é apontar **riscos residuais e brechas potenciais de segurança, concorrência ou estabilidade** que ainda **não foram catalogados** no histórico do projeto, fornecendo recomendações acionáveis de mitigação.

---

## 1. Brecha de Segurança: Sessões Ativas de Usuários Inativados persistem
*   **Módulo Afetado:** `br.gov.saude.sgpur.service.UsuarioDetailsService`, `SecurityConfig` e Controle de Sessão.
*   **Descrição:** Quando um usuário (por exemplo, um médico avaliador ou operador) é desativado por um administrador no painel `/usuarios` (`u.isAtivo() = false`), o método `loadUserByUsername` do `UsuarioDetailsService` lançará um erro ou retornará a propriedade `.disabled(true)` que impedirá **novas** autenticações daquele usuário. No entanto, no modelo padrão de gerenciamento de sessão stateful HTTP (Session Cookies) adotado pelo Tomcat e Spring Security no projeto, a sessão já ativa do usuário permanece gravada na memória de sessão do servidor.
*   **Impacto:** Se um médico avaliador for desligado do conselho ou inativado administrativamente por suspeita de má-fé, ele continuará com acesso total de leitura e escrita ao Portal do Avaliador `/avaliador` em qualquer aba do navegador que já esteja previamente autenticada, podendo continuar a registrar votos ou interagir em chats privados até que ocorra a expiração de inatividade da sua sessão (que tem timeout padrão de 30 minutos em produção).
*   **Recomendação:** Introduzir um mecanismo ativo para revogar ou invalidar as sessões HTTP correspondentes ao `username` modificado na mesma transação em que a conta é inativada no banco de dados. Isso pode ser feito integrando o `SessionRegistry` do Spring Security e invalidando as instâncias de `SessionInformation` associadas ao principal do usuário inativado.

---

## 2. ~~Risco de Sincronismo e Conflito de Fuso Horário VM vs. BD PostgreSQL~~ (DESCARTADO — verificado e refutado em 24/08)
*   **Alegação original:** que a VM/Postgres rodando UTC (confirmado por SSH: `timedatectl` e `SHOW timezone` retornam `Etc/UTC`) causaria defasagem de até 3h nos timestamps gravados, já que a JVM força `America/Sao_Paulo`.
*   **Por que é falso:** as colunas de data (ex. `log_auditoria.data_hora`) são `timestamp(6) WITHOUT TIME ZONE`. O driver JDBC do PostgreSQL grava/lê `LocalDateTime` **literalmente**, sem nenhuma conversão de fuso horário — não há `hibernate.jdbc.time_zone` configurado nem é necessário. É exatamente por isso que forçar `TimeZone.setDefault(America/Sao_Paulo)` na JVM (`SgpurApplication.java`) já é suficiente e correto, **independente** do fuso do SO/Postgres. Combinar isso com fuso do SO/Postgres em UTC não causa nenhuma inconsistência nesse tipo de coluna.
*   **Erro adicional que confirma a falha de verificação:** a correção sugerida (`?serverTimezone=America/Sao_Paulo` na URL JDBC) é um parâmetro do driver **MySQL Connector/J** — o driver do PostgreSQL não reconhece essa diretiva. Achado descartado por completo, não implementar a recomendação.

---

## 3. Falta de Validação MX e Limites de Envio para E-mail Adicional
*   **Módulo Afetado:** `br.gov.saude.sgpur.service.EmailSenderService` e `Processo.emailAdicional`.
*   **Descrição:** O sistema permite a coleta de um e-mail adicional (`emailAdicional`) fornecido pelo solicitante no preenchimento de processos para receber notificações e cópias de trâmites. Esse campo é validado apenas via Regex superficial no frontend/backend. Se o solicitante preencher por erro de digitação um domínio inválido ou inexistente (ex.: `usuario@gamil.con`), a tentativa de envio de notificações SMTP em cópia causará rejeição pelo servidor de saída do Gmail.
*   **Impacto:** Em provedores SMTP rígidos ou corporativos, tentativas sucessivas de entrega a endereços de e-mail inválidos ou inexistentes (rejeições do tipo *Hard Bounce*) degradam a reputação do IP do servidor de e-mail oficial da Secretaria de Saúde ou causam o bloqueio temporário da conta de envio automatizado de e-mails, o que paralisaria o envio de convites legítimos para novos médicos avaliadores.
*   **Recomendação:** Adicionar uma verificação simples de formato de domínio e implementar uma fila de envios de e-mails assíncronos (utilizando `@Async` ou um padrão de Mensageria) de forma que a falha de envio para o e-mail secundário ocorra de forma isolada, registrando um log de alerta sem comprometer as requisições principais de finalização e mantendo a resiliência operacional.

---

## 4. ~~Janela de Corrida na Edição Concorrente de Dados do Paciente via Operador~~ (DESCARTADO — falso, verificado em 24/08)
*   **Alegação original:** que `Processo` não teria `@Version`/lock otimista, permitindo que edições concorrentes de dois operadores se sobrescrevam silenciosamente.
*   **Por que é falso:** `Processo.java` (linha 252) **já tem** `@Version private Long versao;` — é o mesmo campo extensamente documentado no CLAUDE.md do projeto desde 2026-07-10 (commit `8f98d60`), inclusive com o backfill manual em produção (`UPDATE processo SET versao = 0 WHERE versao IS NULL`) já feito e reconfirmado por SQL direto em 2026-08-03. Achado inventado — o controle que ele recomenda "adicionar" já existe e está em produção há mais de um mês.

---

## 5. Risco de Negação de Serviço por Consumo excessivo de CPU no parser de PDF do open-PDF
*   **Módulo Afetado:** `br.gov.saude.sgpur.service.RegistroEnvioService` e `PdfCabecalhoStamper`.
*   **Descrição:** Na consolidação de PDFs anexados para formar o Relatório Final unificado, o `PdfCabecalhoStamper` lê e processa páginas de PDFs legados importados recursivamente. PDFs extremamente pesados (como tomografias digitalizadas com resoluções de imagem gigantescas ou arquivos com milhares de páginas) são carregados na memória heap do servidor de uma só vez para análise e desenho de cabeçalhos.
*   **Impacto:** Um ataque malicioso ou uso descuidado de upload de arquivos pesados pode esgotar o pool de memória Java (OutOfMemoryError) na VM ou causar picos de 100% de uso de CPU no servidor do Tomcat durante o processamento do PDF no boot ou no finalizador, resultando em lentidão extrema ou queda (crash) do SAUR.
*   **Recomendação:** Introduzir um teto rígido não apenas para o tamanho físico do upload (que já está configurado para 25MB), mas também limitar a contagem máxima de páginas de PDF de anexos consolidáveis na persistência (por exemplo, rejeitar PDFs com mais de 100 páginas ou limitar a quantidade de PDFs importáveis simultâneos).

---

## 6. Cobertura Preventiva da Análise
A investigação demonstrou que o SAUR é um sistema com engenharia de altíssimo nível, contendo defesas em profundidade excelentes (como a sanitização e adição de sufixos numéricos para evitar colisões de arquivos em disco de forma perfeitamente idempotente). Os riscos residuais apontados neste relatório não representam erros imediatos, mas sim melhorias de projeto a serem priorizadas no plano de evolução do sistema pela Secretaria de Saúde.
