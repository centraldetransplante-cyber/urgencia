package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de INTEGRACAO (contexto Spring real + H2, sem mock de repositorio -
 * o bug so se manifesta contra o banco de verdade, hidratando uma linha real)
 * do BUG REAL reportado: {@code GET /} (Painel) e {@code GET /processos}
 * (sem filtro) devolviam 500 cru quando existia uma linha de {@code Anexo}
 * com {@code tipo} fora do enum {@code TipoAnexo} atual - cenario real e
 * plausivel em banco de desenvolvimento/producao antigo, ja que valores como
 * {@code CAPA_PROCESSO} foram REMOVIDOS por completo do enum (commit
 * {@code 041dc43}) e {@code ddl-auto: update} nunca valida dado ja gravado.
 *
 * <p><b>Causa raiz confirmada por reproducao direta</b> (nao presumida): o
 * pre-carregamento em lote dos anexos do ano
 * ({@code ProcessoRepository.inicializarAnexos}, chamado por
 * {@code HomeController.dashboard} e, via
 * {@code ProcessoService.inicializarPareceresEAnexos}, por
 * {@code ProcessoListaController.listar}) hidrata TODOS os anexos de TODOS
 * os processos numa unica consulta; se QUALQUER linha tiver um {@code tipo}
 * invalido, o Hibernate lanca ({@code No enum constant ...}), traduzido pelo
 * Spring Data como {@code InvalidDataAccessApiUsageException} - um tipo sem
 * nenhum {@code @ExceptionHandler} no projeto, resultando em "Erro interno
 * do servidor" (500) para QUALQUER processo do ano, mesmo os saudaveis.</p>
 *
 * <p><b>Correcao:</b> o pre-carregamento em lote passa a ter fallback
 * (try/catch + log) para carregamento individual sob demanda, e
 * {@code FluxoProcessoService.anexosSeguro} isola a falha por processo (so o
 * processo com o anexo invalido perde o calculo de pendencia - o resto da
 * pagina renderiza normalmente).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-anexo-tipo-invalido;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.anexos.dir=./target/test-anexos-anexo-tipo-invalido"
})
class AnexoTipoInvalidoNaoDerrubaPainelIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private PlatformTransactionManager txManager;
    @PersistenceContext
    private EntityManager em;

    /**
     * Contador ESTATICO, determinístico, incrementado a cada chamada de
     * {@link #preparar()} - fonte do sequencial/numero "unico" usado abaixo.
     *
     * <p><b>Bug real corrigido aqui (achado no CI, run
     * {@code 31514984633}, commit {@code 8af1a8a} em {@code main}):</b> a
     * versao anterior sorteava o sequencial via
     * {@code 900 + (int) (System.nanoTime() % 90)} - so 90 valores
     * possiveis. Como esta classe tem 3 metodos {@code @Test} e o Spring
     * reaproveita o MESMO contexto/banco H2 nomeado
     * ({@code jdbc:h2:mem:sgpur-anexo-tipo-invalido;DB_CLOSE_DELAY=-1})
     * entre eles, {@code preparar()} roda 3 vezes na mesma instancia de
     * banco, cada vez inserindo um {@code Processo} com um numero "unico"
     * sorteado desses 90 valores - em runners de CI com resolucao de clock
     * mais grosseira, 3 sorteios de {@code nanoTime() % 90} proximos no
     * tempo colidem com chance real (~3%), violando a constraint
     * {@code UNIQUE} de {@code processo.numero}
     * ({@code Unique index or primary key violation... PROCESSO(NUMERO...)}).
     * Um contador estatico incrementado a cada chamada elimina a colisao
     * por construcao, sem depender de timing.</p>
     */
    private static final AtomicInteger PROXIMO_SEQUENCIAL = new AtomicInteger(900);

    @BeforeEach
    void preparar() {
        // Cada teste usa um numero/sequencial UNICO (nunca reaproveitado nem
        // apagado entre metodos): apagar um Processo com um anexo de tipo
        // invalido tambem navega (cascade) a colecao de anexos e sofreria o
        // MESMO problema que este teste existe para cobrir - a limpeza fica
        // fora de escopo aqui de proposito, o contexto Spring/H2 e descartado
        // ao fim da classe de teste.
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(s -> {
            Processo p = new Processo();
            int ano = java.time.Year.now().getValue();
            int sequencial = PROXIMO_SEQUENCIAL.getAndIncrement();
            p.setNumero(sequencial + "/" + ano);
            p.setSequencial(sequencial);
            p.setAno(ano);
            p.setPacienteNome("Paciente Anexo Invalido");
            p.setPacienteRgct("RGCT-ANEXO-INVALIDO");
            p.setSolicitanteEquipe("Equipe Teste");
            p.setSolicitanteEmail("equipe@example.com");
            p.setDataSituacaoEspecial(LocalDate.now());
            p.setStatus(StatusProcesso.SOLICITADO);
            em.persist(p);
            em.flush();

            // Anexo com 'tipo' fora do enum TipoAnexo atual - simula uma linha
            // legada de valor removido do enum (ex.: CAPA_PROCESSO), que
            // ddl-auto: update nunca valida.
            em.createNativeQuery(
                    "INSERT INTO anexo (processo_id, tipo, nome_arquivo, caminho_armazenado, data_upload) "
                    + "VALUES (:pid, 'CAPA_PROCESSO', 'capa.pdf', '/tmp/capa.pdf', :dt)")
                .setParameter("pid", p.getId())
                .setParameter("dt", LocalDateTime.now())
                .executeUpdate();
        });
    }

    /** REGRESSAO DO BUG: o Painel nunca pode devolver 500 por causa disso. */
    @Test
    void painelNaoQuebraComAnexoDeTipoInvalido() throws Exception {
        mvc.perform(get("/").with(user("admin-it").roles("ADMIN")))
            .andExpect(status().isOk());
    }

    /** REGRESSAO DO BUG: a lista de processos (sem filtro) tambem nao pode quebrar. */
    @Test
    void listaDeProcessosNaoQuebraComAnexoDeTipoInvalido() throws Exception {
        mvc.perform(get("/processos").with(user("operador-it").roles("OPERADOR")))
            .andExpect(status().isOk());
    }
}
