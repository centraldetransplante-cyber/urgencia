package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.OrigemParecer;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.ResultadoParecer;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de INTEGRACAO (contexto Spring real + H2, {@code AvaliadorController}/
 * {@code ProcessoService} REAIS, sem mock de servico) que reproduz e prova a
 * correcao do bug real relatado pelo usuario em 2026-08:
 * <i>"quando um membro solicita informacao, os outros membros nao conseguem
 * mais votar"</i>. Ver
 * {@code docs/RELATORIO-BUG-PAUSA-BLOQUEIA-OUTROS-AVALIADORES-2026-08.md}.
 *
 * <p><b>Causa raiz:</b> um voto {@code SOLICITA_INFORMACAO} de UM avaliador
 * muda {@code Processo.status} para {@code SOLICITA_INFORMACAO}, e o portal
 * exigia {@code status == ENVIADO} para QUALQUER voto novo — os outros dois
 * avaliadores, com {@code parecer.resultado == null}, ficavam bloqueados
 * (403 ao abrir o formulario direto, e o processo sumia da lista de
 * pendencias/badge) ate o operador concluir manualmente o ciclo de retomada
 * (que pode levar dias). A correcao (({@code StatusProcesso.
 * aceitaVotoAvaliador()}) libera voto novo tanto em {@code ENVIADO} quanto em
 * {@code SOLICITA_INFORMACAO} — a pausa continua bloqueando so a DECISAO
 * ({@code ProcessoValidator.validarPausaDecisao}/{@code
 * tentarDecisaoAutomatica}, nao tocados por esta correcao).</p>
 *
 * <p><b>Por que integracao, sem mock do servico:</b> convencao do projeto
 * para rota que grava algo irreversivel (o voto) — ver
 * {@code AvaliadorVotoTransacaoIntegrationTest} como modelo.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sgpur-avaliador-voto-pausa;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.anexos.dir=./target/test-anexos-avaliador-voto-pausa"
})
class AvaliadorVotoDuranteSolicitaInformacaoIntegrationTest {

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

    private Long processoId;
    private Long parecerQuePediuInfoId;
    private Long parecerBId;
    private Long parecerCId;

    @BeforeEach
    @Transactional
    void preparar() {
        parecerRepo.deleteAll();
        usuarioRepo.findByUsername("avaliador-pausa-a").ifPresent(usuarioRepo::delete);
        usuarioRepo.findByUsername("avaliador-pausa-b").ifPresent(usuarioRepo::delete);
        usuarioRepo.findByUsername("avaliador-pausa-c").ifPresent(usuarioRepo::delete);
        processoRepo.deleteAll();
        membroRepo.deleteAll();

        Processo p = new Processo();
        p.setNumero("88/2026");
        p.setAno(2026);
        p.setSequencial(88);
        p.setPacienteNome("Paciente Teste Pausa");
        p.setPacienteRgct("321321321");
        p.setSolicitanteEquipe("HCPA");
        p.setSolicitanteEmail("equipe@hcpa.example.com");
        p.setDataSituacaoEspecial(LocalDate.of(2026, 5, 1));
        p.setStatus(StatusProcesso.ENVIADO);
        processoRepo.saveAndFlush(p);
        processoId = p.getId();

        MembroUrgenciaRenal medicoA = membroRepo.saveAndFlush(
                new MembroUrgenciaRenal("HCPA", "Ana PediuInfo", "ana.pediuinfo@example.com"));
        MembroUrgenciaRenal medicoB = membroRepo.saveAndFlush(
                new MembroUrgenciaRenal("ISCMPA", "Bruno Pendente", "bruno.pendente@example.com"));
        MembroUrgenciaRenal medicoC = membroRepo.saveAndFlush(
                new MembroUrgenciaRenal("CET", "Carla Pendente", "carla.pendente@example.com"));

        // Medico A ja votou SOLICITA_INFORMACAO - isso e o que pausa o processo.
        Parecer pA = new Parecer(medicoA);
        pA.setProcesso(p);
        pA.setDataEnvio(LocalDate.of(2026, 5, 2));
        pA.setResultado(ResultadoParecer.SOLICITA_INFORMACAO);
        pA.setDataResposta(LocalDate.of(2026, 5, 3));
        pA.setJustificativa("Falta laudo de biopsia.");
        pA.setOrigem(OrigemParecer.AVALIADOR_SISTEMA);
        parecerRepo.saveAndFlush(pA);
        parecerQuePediuInfoId = pA.getId();

        // Medicos B e C ainda nao votaram.
        Parecer pB = new Parecer(medicoB);
        pB.setProcesso(p);
        pB.setDataEnvio(LocalDate.of(2026, 5, 2));
        parecerRepo.saveAndFlush(pB);
        parecerBId = pB.getId();

        Parecer pC = new Parecer(medicoC);
        pC.setProcesso(p);
        pC.setDataEnvio(LocalDate.of(2026, 5, 2));
        parecerRepo.saveAndFlush(pC);
        parecerCId = pC.getId();

        // O processo reflete a pausa - reproduz exatamente o efeito de
        // atualizarStatusPorPareceres apos o voto de A.
        p.setStatus(StatusProcesso.SOLICITA_INFORMACAO);
        processoRepo.saveAndFlush(p);

        criarUsuarioAvaliador("avaliador-pausa-a", "Ana PediuInfo", medicoA);
        criarUsuarioAvaliador("avaliador-pausa-b", "Bruno Pendente", medicoB);
        criarUsuarioAvaliador("avaliador-pausa-c", "Carla Pendente", medicoC);
    }

    private void criarUsuarioAvaliador(String username, String nome, MembroUrgenciaRenal membro) {
        Usuario u = new Usuario();
        u.setUsername(username);
        u.setSenha("{noop}x");
        u.setNome(nome);
        u.setEmail(username + "@example.com");
        u.setPerfil(Perfil.AVALIADOR);
        u.setMembro(membro);
        usuarioRepo.saveAndFlush(u);
    }

    /** REGRESSAO DO BUG: medico B, que nao pediu informacao, consegue ABRIR a tela de voto durante a pausa. */
    @Test
    @WithMockUser(username = "avaliador-pausa-b", roles = "AVALIADOR")
    void medicoQueNaoPediuInformacaoConsegueAbrirFormularioDeVotoDuranteAPausa() throws Exception {
        mvc.perform(get("/avaliador/" + processoId))
                .andExpect(status().isOk());
    }

    /** REGRESSAO DO BUG: medico B consegue REGISTRAR o voto de verdade enquanto o processo esta pausado. */
    @Test
    @WithMockUser(username = "avaliador-pausa-b", roles = "AVALIADOR")
    void medicoQueNaoPediuInformacaoConsegueVotarDuranteAPausa() throws Exception {
        mvc.perform(post("/avaliador/" + processoId + "/votar")
                        .with(csrf())
                        .param("resultado", "FAVORAVEL"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/avaliador"))
                .andExpect(flash().attributeExists("msg"));

        Parecer gravado = parecerRepo.findById(parecerBId).orElseThrow();
        assertThat(gravado.getResultado()).isEqualTo(ResultadoParecer.FAVORAVEL);
        assertThat(gravado.getOrigem()).isEqualTo(OrigemParecer.AVALIADOR_SISTEMA);
        assertThat(gravado.getVotadoPor()).isEqualTo("avaliador-pausa-b");
        assertThat(gravado.getDataHoraVoto()).isNotNull();

        // A decisao continua bloqueada pela pausa (nao mexemos nisso): o
        // processo NAO deve ter sido decidido automaticamente so porque um
        // segundo voto favoravel chegou - falta a resposta do solicitante.
        Processo depois = processoRepo.findById(processoId).orElseThrow();
        assertThat(depois.getStatus()).isEqualTo(StatusProcesso.SOLICITA_INFORMACAO);
    }

    /** O terceiro medico (C) tambem consegue votar normalmente durante a pausa. */
    @Test
    @WithMockUser(username = "avaliador-pausa-c", roles = "AVALIADOR")
    void terceiroMedicoTambemConsegueVotarDuranteAPausa() throws Exception {
        mvc.perform(post("/avaliador/" + processoId + "/votar")
                        .with(csrf())
                        .param("resultado", "NAO_FAVORAVEL")
                        .param("justificativa", "Sem indicacao clara ainda."))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/avaliador"));

        Parecer gravado = parecerRepo.findById(parecerCId).orElseThrow();
        assertThat(gravado.getResultado()).isEqualTo(ResultadoParecer.NAO_FAVORAVEL);
    }

    /** O processo continua aparecendo na lista de pendencias de B (nao "some" durante a pausa). */
    @Test
    @WithMockUser(username = "avaliador-pausa-b", roles = "AVALIADOR")
    void processoContinuaNaListaDePendenciasDosOutrosDoisDuranteAPausa() throws Exception {
        mvc.perform(get("/avaliador"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("88/2026")));
    }

    /**
     * NAO regride: o medico A, que causou a pausa, continua proibido de VOTAR
     * de novo.
     *
     * <p>A tela em si passou a abrir para ele em <b>modo leitura</b> (200, sem
     * formulario de voto) desde a correcao de 2026-08-11 — ela e o unico lugar
     * do Portal com o chat do processo, e o badge de mensagens nao lidas conta
     * mensagens de processo ja votado; ver
     * {@code AvaliadorLeituraProcessoConcluidoIntegrationTest}. O que importa
     * aqui é a trava do POST, que nunca mudou.</p>
     */
    @Test
    @WithMockUser(username = "avaliador-pausa-a", roles = "AVALIADOR")
    void medicoQuePediuInformacaoContinuaBloqueadoDeVotarDeNovo() throws Exception {
        mvc.perform(get("/avaliador/" + processoId))
                .andExpect(status().isOk())
                .andExpect(model().attribute("modoLeitura", true))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("id=\"formVotoAvaliador\""))));

        mvc.perform(post("/avaliador/" + processoId + "/votar")
                        .with(csrf())
                        .param("resultado", "FAVORAVEL"))
                .andExpect(status().isForbidden());

        // Confirma que o resultado original de A nao foi alterado.
        Parecer aindaComPedido = parecerRepo.findById(parecerQuePediuInfoId).orElseThrow();
        assertThat(aindaComPedido.getResultado()).isEqualTo(ResultadoParecer.SOLICITA_INFORMACAO);
    }
}
