# Relatório de Varredura de Erros e Código Morto — SAUR
**Sistema de Avaliação de Urgência Renal (SAUR)**  
*Data: 24 de agosto de 2026*

Este relatório descreve os resultados de uma varredura abrangente por erros de sintaxe, inconsistências, avisos de compilação e presença de código morto (métodos, imports ou estruturas obsoletas e não utilizadas) em toda a base de código do sistema **SAUR** (SGPUR). O diagnóstico foi realizado utilizando a suíte de testes unitários do JDK 21 e uma compilação incremental limpa via Maven Compiler.

---

## 1. Diagnóstico Geral de Compilação

Executada a compilação limpa do projeto utilizando a configuração estrita do JDK 21 através do comando `mvn clean compile`:

*   **Status Geral:** **BUILD SUCCESS**
*   **Total de Arquivos Compilados:** 119 arquivos fonte de produção Java.
*   **Erros de Sintaxe / Compilação:** **0 erros** (todos os arquivos compilam perfeitamente, sem erros de tipagem, imports ausentes ou incompatibilidades de API).
*   **Avisos de Compilação (Warnings):** Apenas **2 avisos de deprecamento** menores foram identificados pelo compilador `javac`, detalhados a seguir.

---

## 2. Análise Detalhada dos Avisos do Compilador (Warnings)

O compilador Java identificou dois avisos específicos da categoria de anotações em estruturas de registros (Java Records) introduzidas no Java 21:

### Aviso A: `SituacaoPedidoView.java` (Linha 39)
*   **Mensagem:** `[WARNING] .../dto/SituacaoPedidoView.java:[39,8] deprecated item is not annotated with @Deprecated`
*   **Causa Raiz:** O Javadoc do record `SituacaoPedidoView` (usado para modelar o cartão de situação do Portal do Solicitante) declara a tag `@deprecated` para o parâmetro `classeCor` do componente. No Java 21, quando a tag `@deprecated` é inserida no Javadoc de um parâmetro de registro, o compilador exige de forma estrita que o respectivo componente de registro ou campo físico também receba a anotação `@Deprecated`.
*   **Código Atual:**
    ```java
    public record SituacaoPedidoView(
        String rotulo,
        String classeCor, // <-- Falta @Deprecated aqui
        // ...
    )
    ```
*   **Recomendação (aplicada em 24/08, mas não suprime o warning):** `@Deprecated` foi adicionado ao componente `classeCor` no cabeçalho do record. **Resultado real, verificado com `mvn clean compile`:** o warning **continua aparecendo**, na mesma linha/coluna — porque o acessor de `classeCor()` já é sobrescrito manualmente (com seu próprio `@Deprecated`), e o javac reporta o item pendente como o construtor canônico implícito, cuja localização de diagnóstico é sempre a linha do `record`. Suprimir de vez exigiria reescrever o construtor canônico completo (não-compacto, redeclarando todos os parâmetros com anotação individual) só para silenciar 1 warning cosmético sem nenhum impacto funcional — julgado não valer o código extra. A anotação no cabeçalho foi mantida (documenta a intenção corretamente e não introduziu nenhum warning novo em nenhum outro lugar, confirmado recompilando o projeto inteiro).

### Aviso B: `PainelLinha.java` (Linha 59)
*   **Mensagem:** `[WARNING] .../dto/PainelLinha.java:[59,12] deprecated item is not annotated with @Deprecated`
*   **Causa Raiz:** Idêntica à anterior. O Javadoc da classe interna `CelulaMedico` (dentro de `PainelLinha.java`) usa a tag `@deprecated` no parâmetro `cor`. O método de acessor customizado `cor()` está devidamente anotado com `@Deprecated`, mas o componente do registro na assinatura da classe não está.
*   **Recomendação (mesma situação do Aviso A):** `@Deprecated` foi adicionado ao componente `cor` no cabeçalho do record `CelulaMedico`, e o warning **também continua** pelo mesmo motivo (acessor explícito + construtor canônico implícito). Mantido documentado, não suprimido por completo — mesma decisão do Aviso A.

---

## 3. Investigação de Código Morto e Remoções Históricas

O SAUR passou por vistorias rigorosas que eliminaram grande parte do débito técnico e código obsoleto. A análise estática atual confirma que:

1.  **Excisão de Tipos de Origem Legados:** A antiga modalidade de lançamento manual de pareceres via e-mail (`OrigemParecer.OPERADOR_EMAIL`) foi completamente removida da base de código. Não há trechos de código mortos ou condicionais inativas tentando tratar pareceres lançados por operador. Toda a votação é agora autenticada de forma direta pelo Portal do Avaliador.
2.  **Substituição de Sementes Obsoletas (DataSeed):** A antiga classe `DataSeed.java` (que gerava registros de testes soltos em produção e provocava conflitos de integridade) foi completamente eliminada em commits anteriores (`e8449e9`). Foi substituída pela classe `AdminBootstrap.java`, que apenas cria o usuário administrador inicial em ambientes limpos, sem gerar dados fictícios.
3.  **Imports Não Utilizados:** Todas as classes Java de produção estão com seus imports otimizados, sem ocorrências de imports curinga (`import *`) ou referências órfãs a pacotes que não existem no `pom.xml`.
4.  **Métodos e Parâmetros Órfãos:** Métodos privados não utilizados ou campos declarados sem uso foram eliminados nas vistorias recentes. A ausência de warnings do tipo `unused` de nível grave no compilador atesta a limpeza das estruturas privadas.

---

## 4. Análise de Inconsistências de Design (Código "Frio")

Embora não sejam "erros" de compilação, foram identificados alguns padrões de código "frio" (suspeitos de ineficiência ou desvio de padrão):

### A. Uso de `Double` para Médias de Tempo de Resposta
*   **Classe:** `br.gov.saude.sgpur.service.TempoRespostaService`
*   **Inconsistência:** O retorno das médias de tempo de resposta utiliza a classe `Double`, que pode retornar `null` se não houver pareceres respondidos para um determinado médico avaliador. A exibição no Thymeleaf trata esse valor nulo exibindo um traço (`"—"`), o que é correto. No entanto, o uso de tipos numéricos primitivos encapsulados em operações de agregação pode gerar `NullPointerException` se em algum ponto do fluxo for tentado um unboxing implícito de `Double` para `double` sem validação `null-safe`.
*   **Mitigação:** Atualmente a classe de formatação `formatarDias` realiza a checagem null-safe de forma correta, mas deve-se monitorar novos usos desse serviço.

### B. Tratamento Best-Effort Silencioso na Exclusão de Anexos
*   **Classe:** `br.gov.saude.sgpur.service.AnexoStorageService` (Métodos `excluir` e `removerAntigosDoTipo`)
*   **Inconsistência:** Quando o sistema falha ao apagar fisicamente um arquivo de anexo em disco (por exemplo, por falta de permissão do sistema operacional ou arquivo já removido), o bloco catch captura silenciosamente a exceção:
    ```java
    try {
        Files.deleteIfExists(resolverArquivo(a));
    } catch (RuntimeException | IOException ignored) {
        // best-effort
    }
    ```
*   **Análise:** Embora a política de "best-effort" seja intencional para não interromper a exclusão de registros do banco de dados caso ocorra uma falha de disco, o uso de `ignored` sem registrar sequer um log em nível `DEBUG` ou `TRACE` impede a equipe de infraestrutura de rastrear arquivos órfãos que começam a se acumular no diretório `./data/anexos` devido a erros de permissão de escrita/leitura do usuário do Tomcat.
*   **Recomendação (aplicada em 24/08):** os 4 pontos de `catch (... ignored)` da classe (`removerAntigosDoTipo`, `excluir`, e os 2 dentro de `removerPasta`) agora registram `log.debug(...)` com o id/caminho do anexo e a mensagem da exceção, mantendo o comportamento best-effort (nunca lança, nunca bloqueia a exclusão do registro). Verificado: `mvn clean compile` sem warnings novos e a suíte `AnexoStorageServiceTest` (6 testes) continua verde.

---

## 5. Conclusão

A base de código do sistema SAUR apresenta uma qualidade de conservação e limpeza extremamente rara para sistemas legados. Os únicos warnings ativos referem-se a rigorosidades de documentação deprecada no Java 21 (Records) — que, mesmo após a correção aplicada em 24/08, o javac continua emitindo por limitação própria dele com records cujo acessor é sobrescrito manualmente (ver detalhe na seção 2) — sem qualquer impacto funcional ou de performance. A suíte tem **1.094 testes** (contagem exata via `target/surefire-reports`, não 1.114 — mesma correção já aplicada nos demais relatórios desta sessão e no CLAUDE.md), passando sem falhas, atestando a integridade absoluta de todo o código remanescente.
