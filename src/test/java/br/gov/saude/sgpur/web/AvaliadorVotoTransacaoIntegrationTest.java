package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.OrigemParecer;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.ResultadoParecer;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.domain.Sexo;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de INTEGRACAO (contexto Spring real + H2, ProcessoService REAL com o
 * proxy transacional de verdade) da garantia transacional do voto no Portal do
 * Avaliador.
 *
 * <p><b>Por que nao da para cobrir isso com {@code @WebMvcTest}:</b> o bug
 * mora na interacao entre o {@code @Transactional} do controller e o
 * {@code @Transactional} dos servicos chamados dentro dele. Com
 * {@code @MockitoBean ProcessoService} nao existe TransactionInterceptor
 * nenhum, entao a transacao nunca e marcada como rollback-only e o teste passa
 * mesmo com o codigo bugado.</p>
 *
 * <p><b>Bug reproduzido aqui:</b> {@code registrarVoto} gravava o parecer e
 * so depois chamava {@code atualizarStatusPorPareceres} /
 * {@code tentarDecisaoAutomatica}, ambos {@code @Transactional} REQUIRED, ou
 * seja, participantes da MESMA transacao fisica. Quando um deles falhava com
 * {@code IllegalStateException} (janela real: dois medicos votando quase
 * juntos, com a decisao automatica disparando no 2o voto), o interceptor da
 * chamada aninhada marcava a transacao compartilhada como rollback-only. O
 * {@code catch} do controller tratava o erro e devolvia flash + redirect, mas
 * o commit no fim do metodo estourava {@code UnexpectedRollbackException}
 * (500 cru) e <b>desfazia o voto do medico junto</b>.</p>
 *
 * <p>A falha do pos-processamento e injetada de forma deterministica: o
 * {@code SolicitacaoOnlineRepository} — consultado por
 * {@code ProcessoService.decidir} logo apos gravar a decisao, para espelhar o
 * status na solicitacao de origem — e mockado para lancar
 * {@code IllegalStateException}. A excecao sobe de dentro de {@code decidir}
 * (@Transactional) para {@code tentarDecisaoAutomatica} (@Transactional) e daí
 * para o controller — exatamente o caminho do bug.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sgpur-avaliador-voto-tx;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.anexos.dir=./target/test-anexos-avaliador-voto-tx"
})
class AvaliadorVotoTransacaoIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UsuarioRepository usuarioRepo;
    @Autowired
    private ProcessoRepository processoRepo;
    @Autowired
    private ParecerRepository parecerRepo;
    @Autowired
    private MembroUrgenciaRenalRepository membroRepo;

    /**
     * Unico ponto mockado: usado dentro de {@code ProcessoService.decidir} para
     * espelhar a decisao na SolicitacaoOnline de origem. Serve de gatilho
     * deterministico para a falha do pos-processamento (ver javadoc da classe).
     */
    @MockitoBean
    private SolicitacaoOnlineRepository solicitacaoOnlineRepo;

    private Long processoId;
    private Long parecerDoVotanteId;

    @BeforeEach
    @Transactional
    void preparar() {
        parecerRepo.deleteAll();
        usuarioRepo.findByUsername("avaliador-tx-it").ifPresent(usuarioRepo::delete);
        processoRepo.deleteAll();
        membroRepo.deleteAll();

        Processo p = new Processo();
        p.setNumero("77/2026");
        p.setAno(2026);
        p.setSequencial(77);
        p.setPacienteNome("Paciente Teste Transacao");
        p.setPacienteRgct("123123123");
        p.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        p.setPacienteCpf("11144477735");
        p.setPacienteSexo(Sexo.MASCULINO);
        p.setSolicitanteEquipe("HCPA");
        p.setSolicitanteEmail("equipe@hcpa.example.com");
        p.setDataSituacaoEspecial(LocalDate.of(2026, 5, 1));
        p.setStatus(StatusProcesso.ENVIADO);
        processoRepo.saveAndFlush(p);
        processoId = p.getId();

        MembroUrgenciaRenal votante = membroRepo.saveAndFlush(
                new MembroUrgenciaRenal("HCPA", "Ana Votante", "ana.votante@example.com"));
        MembroUrgenciaRenal jaVotou = membroRepo.saveAndFlush(
                new MembroUrgenciaRenal("ISCMPA", "Bruno JaVotou", "bruno@example.com"));
        MembroUrgenciaRenal terceiro = membroRepo.saveAndFlush(
                new MembroUrgenciaRenal("CET", "Carla Terceira", "carla@example.com"));

        // 1o voto ja registrado (desfavoravel, pelo proprio portal): o voto da
        // Ana fecha a maioria simples e dispara a decisao automatica.
        Parecer pJaVotou = new Parecer(jaVotou);
        pJaVotou.setProcesso(p);
        pJaVotou.setDataEnvio(LocalDate.of(2026, 5, 2));
        pJaVotou.setResultado(ResultadoParecer.NAO_FAVORAVEL);
        pJaVotou.setDataResposta(LocalDate.of(2026, 5, 3));
        pJaVotou.setOrigem(OrigemParecer.AVALIADOR_SISTEMA);
        parecerRepo.saveAndFlush(pJaVotou);

        Parecer pVotante = new Parecer(votante);
        pVotante.setProcesso(p);
        pVotante.setDataEnvio(LocalDate.of(2026, 5, 2));
        parecerRepo.saveAndFlush(pVotante);
        parecerDoVotanteId = pVotante.getId();

        Parecer pTerceiro = new Parecer(terceiro);
        pTerceiro.setProcesso(p);
        pTerceiro.setDataEnvio(LocalDate.of(2026, 5, 2));
        parecerRepo.saveAndFlush(pTerceiro);

        Usuario u = new Usuario();
        u.setUsername("avaliador-tx-it");
        u.setSenha("{noop}x");
        u.setNome("Ana Votante");
        u.setEmail("ana.votante@example.com");
        u.setPerfil(Perfil.AVALIADOR);
        u.setMembro(votante);
        usuarioRepo.saveAndFlush(u);
    }

    /**
     * REGRESSAO DO BUG: o pos-processamento (decisao automatica) falha, mas o
     * voto do medico continua gravado e ele recebe um aviso — nunca um 500 nem
     * um voto perdido.
     */
    @Test
    @WithMockUser(username = "avaliador-tx-it", roles = "AVALIADOR")
    void votoSobreviveQuandoDecisaoAutomaticaFalha() throws Exception {
        when(solicitacaoOnlineRepo.findByProcessoGeradoId(anyLong()))
                .thenThrow(new IllegalStateException("falha simulada no pos-processamento"));

        // andReturn (em vez de andExpect encadeado) de proposito: a assercao que
        // importa e a do BANCO, e ela deve ser a primeira a falhar se o bug
        // voltar — "voto perdido" e uma mensagem bem mais util que "esperava
        // redirect, veio pagina de erro".
        var resultado = mvc.perform(post("/avaliador/" + processoId + "/votar")
                        .with(csrf())
                        .param("resultado", "NAO_FAVORAVEL")
                        .param("justificativa", "Sem criterio de urgencia."))
                .andReturn();

        // O QUE IMPORTA: o voto do medico esta no banco. Sem a correcao, o
        // rollback-only da chamada aninhada desfazia este save junto.
        Parecer gravado = parecerRepo.findById(parecerDoVotanteId).orElseThrow();
        assertThat(gravado.getResultado()).isEqualTo(ResultadoParecer.NAO_FAVORAVEL);
        assertThat(gravado.getOrigem()).isEqualTo(OrigemParecer.AVALIADOR_SISTEMA);
        assertThat(gravado.getVotadoPor()).isEqualTo("avaliador-tx-it");
        assertThat(gravado.getDataHoraVoto()).isNotNull();
        assertThat(gravado.getJustificativa()).isEqualTo("Sem criterio de urgencia.");

        // E o medico ve um aviso, nao um 500 cru (sem a correcao vinha
        // UnexpectedRollbackException do commit do proprio metodo).
        assertThat(resultado.getResponse().getStatus()).isEqualTo(302);
        assertThat(resultado.getResponse().getRedirectedUrl()).isEqualTo("/avaliador");
        assertThat(resultado.getFlashMap().get("aviso")).isNotNull();

        // A decisao que falhou foi desfeita (transacao propria), sem contaminar
        // o voto: o processo continua aberto para o operador/3o avaliador.
        Processo depois = processoRepo.findById(processoId).orElseThrow();
        assertThat(depois.getStatus()).isEqualTo(StatusProcesso.ENVIADO);
    }

    /**
     * Controle: sem a falha injetada, o MESMO cenario chega ate o fim e a
     * decisao automatica indefere o processo. Garante que o teste acima falha
     * pelo motivo certo (o pos-processamento realmente e alcancado) e nao por
     * um setup que nunca chegaria a decidir.
     */
    @Test
    @WithMockUser(username = "avaliador-tx-it", roles = "AVALIADOR")
    void semFalhaOVotoFechaAMaioriaEIndeferAutomaticamente() throws Exception {
        when(solicitacaoOnlineRepo.findByProcessoGeradoId(anyLong())).thenReturn(Optional.empty());

        mvc.perform(post("/avaliador/" + processoId + "/votar")
                        .with(csrf())
                        .param("resultado", "NAO_FAVORAVEL")
                        .param("justificativa", "Sem criterio de urgencia - controle."))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/avaliador"))
                .andExpect(flash().attributeExists("msg"));

        Parecer gravado = parecerRepo.findById(parecerDoVotanteId).orElseThrow();
        assertThat(gravado.getResultado()).isEqualTo(ResultadoParecer.NAO_FAVORAVEL);

        Processo depois = processoRepo.findById(processoId).orElseThrow();
        assertThat(depois.getStatus()).isEqualTo(StatusProcesso.INDEFERIDO);
        assertThat(depois.getMotivoIndeferimento()).contains("maioria");
    }
}
