package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.ResultadoParecer;
import br.gov.saude.sgpur.domain.StatusProcesso;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de INTEGRACAO (contexto Spring real + H2, sem mock de repositorio) do
 * BUG REAL reportado em producao: {@code GET /arquivo} devolvia 500 cru
 * ({@code LazyInitializationException}) para qualquer processo DEFERIDO na
 * pagina.
 *
 * <p><b>Causa raiz confirmada por reproducao direta (nao presumida).</b>
 * {@code ArquivoController.listar} nao e {@code @Transactional} e busca a
 * pagina via {@code ProcessoRepository.buscarEncerrados} - uma consulta SEM
 * {@code left join fetch p.pareceres} de proposito (paginacao + fetch join de
 * colecao na MESMA query faz o Hibernate paginar em memoria, ver javadoc do
 * repositorio). Em seguida o controller chama
 * {@code ProcessoValidator.regraAplicada(p)} para CADA processo da pagina;
 * para status {@code DEFERIDO} esse metodo navega
 * {@code processo.getPareceres()} (colecao LAZY) via
 * {@code temVotoCoordenadorFavoravel}. Como {@code spring.jpa.open-in-view} e
 * {@code false} e o metodo do controller nao abre transacao propria, a sessao
 * Hibernate ja fechou quando o loop tenta ler {@code pareceres} ->
 * {@code LazyInitializationException}, sem nenhum {@code @ExceptionHandler}
 * especifico -> 500 cru.</p>
 *
 * <p>Introduzido pelo PR #90 (F2 da "Vistoria de brechas na decisao",
 * 2026-08-10), quando {@code ArquivoController} ganhou {@code ProcessoValidator}
 * injetado so para alimentar o mapa {@code regrasDecisao} (badge da regra de
 * decisao) - sem hidratar {@code pareceres} antes, ao contrario de
 * {@code ProcessoListaController}/{@code HomeController}, que ja carregavam
 * essa colecao por outro motivo antes de F2 chegar.</p>
 *
 * <p>Acontece com QUALQUER processo DEFERIDO na pagina (o predicado so
 * confere status - INDEFERIDO/CANCELADO curto-circuitam ANTES de tocar
 * {@code pareceres}, ver {@code ProcessoValidator.regraAplicada}) -
 * praticamente garantido em producao real, ja que o Arquivo acumula todo
 * processo encerrado e Deferido e um desfecho comum.</p>
 *
 * <p><b>Correcao:</b> {@code ArquivoController.listar} passou a chamar
 * {@code ProcessoRepository.inicializarPareceresComMembro} (mesmo metodo ja
 * usado por {@code ProcessoListaController}) logo apos a paginacao, ANTES do
 * loop que monta {@code regrasDecisao}.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-arquivo-lazy-pareceres;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.anexos.dir=./target/test-anexos-arquivo-lazy-pareceres"
})
class ArquivoLazyPareceresIntegrationTest {

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired
    private PlatformTransactionManager txManager;
    @PersistenceContext
    private EntityManager em;

    /** Contador estatico para numero/sequencial sempre unico entre metodos (mesmo padrao de
     *  AnexoTipoInvalidoNaoDerrubaPainelIntegrationTest - evita colisao de UNIQUE em CI). */
    private static final AtomicInteger PROXIMO_SEQUENCIAL = new AtomicInteger(700);

    @BeforeEach
    void preparar() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(s -> {
            MembroUrgenciaRenal membro = new MembroUrgenciaRenal("HCPA", "Medico Arquivo Teste", "medico@example.com");
            em.persist(membro);

            int ano = java.time.Year.now().getValue();
            int sequencial = PROXIMO_SEQUENCIAL.getAndIncrement();

            Processo p = new Processo();
            p.setNumero(sequencial + "/" + ano);
            p.setSequencial(sequencial);
            p.setAno(ano);
            p.setPacienteNome("Paciente Arquivo Deferido");
            p.setPacienteRgct("RGCT-ARQUIVO-" + sequencial);
            p.setSolicitanteEquipe("Equipe Teste");
            p.setSolicitanteEmail("equipe@example.com");
            p.setDataSituacaoEspecial(LocalDate.now());
            p.setStatus(StatusProcesso.DEFERIDO);
            em.persist(p);

            Parecer parecer = new Parecer();
            parecer.setProcesso(p);
            parecer.setMembro(membro);
            parecer.setResultado(ResultadoParecer.FAVORAVEL);
            parecer.setDataEnvio(LocalDate.now());
            parecer.setDataResposta(LocalDate.now());
            em.persist(parecer);

            em.flush();
        });
    }

    /** REGRESSAO DO BUG: /arquivo nunca pode devolver 500 por causa de um processo Deferido na pagina. */
    @Test
    void arquivoNaoQuebraComProcessoDeferidoNaPagina() throws Exception {
        mvc.perform(get("/arquivo").with(user("operador-it").roles("OPERADOR")))
            .andExpect(status().isOk());
    }
}
