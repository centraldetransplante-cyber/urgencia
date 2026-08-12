package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.Anexo;
import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.OrigemParecer;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.ResultadoParecer;
import br.gov.saude.sgpur.domain.SolicitacaoOnline;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.StatusSolicitacaoOnline;
import br.gov.saude.sgpur.domain.TipoAnexo;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.AnexoRepository;
import br.gov.saude.sgpur.repository.HistoricoParecerRepository;
import br.gov.saude.sgpur.repository.LogAuditoriaRepository;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.AnexoStorageService;
import br.gov.saude.sgpur.service.EmailSenderService;
import br.gov.saude.sgpur.service.ProcessoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de INTEGRACAO (contexto Spring real, H2 real, <b>sem mock de
 * service</b>) da feature que fecha a lacuna do avaliador que pediu
 * informacao complementar e nunca conseguia ler a resposta.
 *
 * <p>Cobre o caminho inteiro: solicitante responde (texto e/ou arquivo) ->
 * operador REDIGE e encaminha (com checagem de imparcialidade) -> so quem
 * pediu ve o material na tela de voto e no download.</p>
 *
 * <p>Um {@code @WebMvcTest} nao serviria aqui: as regras que importam
 * (gravacao do anexo em disco+banco, quem enxerga o que, rollback/nao-rollback
 * do aviso por e-mail) so existem com os servicos e o JPA de verdade.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sgpur-info-compl-avaliador;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.solicitante.habilitado=true",
        "app.anexos.dir=./target/test-anexos-info-compl-avaliador"
})
class InfoComplementarAvaliadorIntegrationTest {

    /** Texto seguro: nao cita nenhum token do paciente ("Joao"/"Neves") nem da equipe ("HCPA"). */
    private static final String TEXTO_SEGURO =
        "A equipe informou que o exame de imagem foi realizado em 05/08 e nao evidenciou alteracao.";

    @Autowired private MockMvc mvc;
    @Autowired private UsuarioRepository usuarioRepo;
    @Autowired private SolicitacaoOnlineRepository solicitacaoRepo;
    @Autowired private ProcessoRepository processoRepo;
    @Autowired private ParecerRepository parecerRepo;
    @Autowired private HistoricoParecerRepository historicoRepo;
    @Autowired private MembroUrgenciaRenalRepository membroRepo;
    @Autowired private AnexoRepository anexoRepo;
    @Autowired private LogAuditoriaRepository auditoriaRepo;
    @Autowired private AnexoStorageService anexoStorage;
    @Autowired private ProcessoService processoService;

    /** Unico mock: o SMTP. Todo o resto e real (convencao do projeto para escrita irreversivel). */
    @MockitoBean private EmailSenderService emailSenderService;

    private Long solicitacaoId;
    private Long processoId;
    private Long membroPediuAId;
    private Long membroPediuBId;
    private Long membroNaoPediuId;

    @BeforeEach
    @Transactional
    void preparar() {
        anexoRepo.deleteAll();
        historicoRepo.deleteAll();
        solicitacaoRepo.deleteAll();
        parecerRepo.deleteAll();
        processoRepo.deleteAll();
        usuarioRepo.deleteAll();
        membroRepo.deleteAll();

        when(emailSenderService.enviar(anyString(), anyString(), anyString())).thenReturn(true);

        Usuario dono = new Usuario();
        dono.setUsername("solicitante-ica");
        dono.setSenha("{noop}x");
        dono.setNome("Equipe Solicitante ICA");
        dono.setEmail("solicitante-ica@example.com");
        dono.setPerfil(Perfil.SOLICITANTE);
        dono.setEquipeSolicitante("HCPA");
        usuarioRepo.saveAndFlush(dono);

        Processo p = new Processo();
        p.setNumero("12/2026");
        p.setAno(2026);
        p.setSequencial(12);
        p.setPacienteNome("Joao das Neves");
        p.setPacienteRgct("999999999");
        p.setSolicitanteEquipe("HCPA");
        p.setSolicitanteEmail("equipe@hcpa.example.com");
        p.setDataSituacaoEspecial(LocalDate.of(2026, 3, 1));
        p.setStatus(StatusProcesso.ENVIADO);
        processoRepo.saveAndFlush(p);
        processoId = p.getId();

        membroPediuAId = criarAvaliador(p, "aval-a-ica", "Ana Nefro", "ISCMPA", true);
        membroPediuBId = criarAvaliador(p, "aval-b-ica", "Bruno Nefro", "CET", true);
        membroNaoPediuId = criarAvaliador(p, "aval-c-ica", "Carla Nefro", "HMV", false);

        // Dois pedidos simultaneos de informacao (o cenario real do processo
        // 12/2026): a pausa passa a valer para o processo inteiro.
        processoService.atualizarStatusPorPareceres(processoId);

        SolicitacaoOnline s = new SolicitacaoOnline();
        s.setUsuarioSolicitante(dono);
        s.setPacienteNome("Joao das Neves");
        s.setPacienteRgct("999999999");
        s.setSolicitanteEquipe("HCPA");
        s.setSolicitanteEmail("solicitante-ica@example.com");
        s.setDataSituacaoEspecial(LocalDate.of(2026, 3, 1));
        s.setJustificativaClinica("Justificativa clinica de teste.");
        s.setStatus(StatusSolicitacaoOnline.CONVERTIDA);
        s.setProcessoGerado(processoRepo.getReferenceById(processoId));
        solicitacaoRepo.saveAndFlush(s);
        solicitacaoId = s.getId();
    }

    /** Cria membro + login AVALIADOR + parecer; quando {@code pediuInfo}, o parecer ja vota SOLICITA_INFORMACAO. */
    private Long criarAvaliador(Processo p, String username, String nome, String instituicao, boolean pediuInfo) {
        MembroUrgenciaRenal m = membroRepo.saveAndFlush(
            new MembroUrgenciaRenal(instituicao, nome, username + "@example.com"));
        Usuario u = new Usuario();
        u.setUsername(username);
        u.setSenha("{noop}x");
        u.setNome(nome);
        u.setEmail(username + "@example.com");
        u.setPerfil(Perfil.AVALIADOR);
        u.setMembro(m);
        usuarioRepo.saveAndFlush(u);

        Parecer par = new Parecer(m);
        par.setProcesso(p);
        par.setDataEnvio(LocalDate.of(2026, 3, 2));
        if (pediuInfo) {
            par.setResultado(ResultadoParecer.SOLICITA_INFORMACAO);
            par.setJustificativa("Falta o exame de imagem mais recente.");
            par.setDataResposta(LocalDate.of(2026, 3, 5));
            par.setDataHoraVoto(LocalDateTime.of(2026, 3, 5, 10, 0));
            par.setOrigem(OrigemParecer.AVALIADOR_SISTEMA);
        }
        parecerRepo.saveAndFlush(par);
        return m.getId();
    }

    private static RequestPostProcessor operador() {
        return user("operador-ica").roles("OPERADOR");
    }

    private List<Anexo> anexosDoTipo(TipoAnexo tipo) {
        return anexoRepo.findByProcessoIdAndTipo(processoId, tipo);
    }

    private void encaminharComSucesso() throws Exception {
        mvc.perform(multipart("/processos/" + processoId + "/info-complementar/encaminhar-avaliadores")
                .param("texto", TEXTO_SEGURO).with(csrf()).with(operador()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attributeExists("msg"));
    }

    // ------------------------------------------------------------------
    // 1-3: resposta do solicitante agora aceita TEXTO, arquivo, ou os dois
    // ------------------------------------------------------------------

    @Test
    void solicitanteRespondeApenasComTextoEOConteudoViraAnexo() throws Exception {
        mvc.perform(multipart("/solicitante/" + solicitacaoId + "/informacao-complementar")
                .param("texto", "O paciente segue internado e o exame sai amanha.")
                .with(csrf()).with(user("solicitante-ica").roles("SOLICITANTE")))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attributeExists("msg"));

        List<Anexo> anexos = anexosDoTipo(TipoAnexo.INFO_COMPLEMENTAR);
        assertThat(anexos).hasSize(1);
        assertThat(anexos.get(0).getNomeArquivo()).endsWith(".txt");
        assertThat(anexoStorage.lerTextoInline(anexos.get(0)))
            .isEqualTo("O paciente segue internado e o exame sai amanha.");
    }

    @Test
    void solicitanteRespondeApenasComArquivoComoSempre() throws Exception {
        MockMultipartFile arquivo = new MockMultipartFile("arquivos", "exame.pdf",
            MediaType.APPLICATION_PDF_VALUE, "conteudo do exame".getBytes());

        mvc.perform(multipart("/solicitante/" + solicitacaoId + "/informacao-complementar")
                .file(arquivo).with(csrf()).with(user("solicitante-ica").roles("SOLICITANTE")))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attributeExists("msg"));

        assertThat(anexosDoTipo(TipoAnexo.INFO_COMPLEMENTAR))
            .hasSize(1)
            .allMatch(a -> a.getNomeArquivo().endsWith(".pdf"));
    }

    @Test
    void solicitanteSemTextoNemArquivoERecusadoSemGravarNada() throws Exception {
        mvc.perform(multipart("/solicitante/" + solicitacaoId + "/informacao-complementar")
                .with(csrf()).with(user("solicitante-ica").roles("SOLICITANTE")))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attributeExists("erro"));

        assertThat(anexosDoTipo(TipoAnexo.INFO_COMPLEMENTAR)).isEmpty();
    }

    // ------------------------------------------------------------------
    // 4-5: encaminhamento do operador (com a checagem de imparcialidade)
    // ------------------------------------------------------------------

    @Test
    void operadorEncaminhaTextoSeguroEOMaterialViraAnexoDoAvaliadorComAuditoria() throws Exception {
        encaminharComSucesso();

        List<Anexo> material = anexosDoTipo(TipoAnexo.INFO_COMPLEMENTAR_AVALIADOR);
        assertThat(material).hasSize(1);
        assertThat(anexoStorage.lerTextoInline(material.get(0))).isEqualTo(TEXTO_SEGURO);
        assertThat(auditoriaRepo.findAll())
            .anyMatch(l -> "INFO_COMPLEMENTAR_ENCAMINHADA".equals(l.getAcao()));
        // Auditoria NUNCA carrega o nome do paciente nem o texto encaminhado.
        assertThat(auditoriaRepo.findAll())
            .filteredOn(l -> "INFO_COMPLEMENTAR_ENCAMINHADA".equals(l.getAcao()))
            .allSatisfy(l -> {
                assertThat(l.getDetalhe()).doesNotContain("Joao");
                assertThat(l.getDetalhe()).doesNotContain(TEXTO_SEGURO);
            });
    }

    @Test
    void operadorNaoConsegueEncaminharTextoQueCitaONomeDoPaciente() throws Exception {
        mvc.perform(multipart("/processos/" + processoId + "/info-complementar/encaminhar-avaliadores")
                .param("texto", "O paciente Joao Neves realizou o exame na semana passada.")
                .with(csrf()).with(operador()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attributeExists("erro"));

        assertThat(anexosDoTipo(TipoAnexo.INFO_COMPLEMENTAR_AVALIADOR)).isEmpty();
    }

    @Test
    void operadorNaoConsegueEncaminharTextoQueCitaAEquipeSolicitante() throws Exception {
        mvc.perform(multipart("/processos/" + processoId + "/info-complementar/encaminhar-avaliadores")
                .param("texto", "A resposta veio do HCPA na sexta-feira.")
                .with(csrf()).with(operador()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attributeExists("erro"));

        assertThat(anexosDoTipo(TipoAnexo.INFO_COMPLEMENTAR_AVALIADOR)).isEmpty();
    }

    @Test
    void operadorNaoConsegueEncaminharTextoEmBranco() throws Exception {
        mvc.perform(multipart("/processos/" + processoId + "/info-complementar/encaminhar-avaliadores")
                .param("texto", "   ").with(csrf()).with(operador()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attributeExists("erro"));

        assertThat(anexosDoTipo(TipoAnexo.INFO_COMPLEMENTAR_AVALIADOR)).isEmpty();
    }

    // ------------------------------------------------------------------
    // 6-8: quem ve o material (N pedidos + teste NEGATIVO de imparcialidade)
    // ------------------------------------------------------------------

    @Test
    void osDoisAvaliadoresQuePediramVeemOMesmoMaterialEQuemNaoPediuNaoVe() throws Exception {
        encaminharComSucesso();

        for (String username : List.of("aval-a-ica", "aval-b-ica")) {
            mvc.perform(get("/avaliador/" + processoId).with(user(username).roles("AVALIADOR")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                    .content().string(org.hamcrest.Matchers.containsString(TEXTO_SEGURO)));
        }

        // Avaliador que NUNCA pediu informacao nao ve nada do material, mesmo
        // sendo avaliador do mesmo processo (imparcialidade - teste negativo).
        mvc.perform(get("/avaliador/" + processoId).with(user("aval-c-ica").roles("AVALIADOR")))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(TEXTO_SEGURO))));
    }

    @Test
    void comUmUnicoPedidoSoOAvaliadorQuePediuVeOMaterial() throws Exception {
        // Desfaz o pedido de B: sobra so o de A (cenario N=1).
        Parecer parB = parecerRepo.findByProcessoIdAndMembroId(processoId, membroPediuBId).orElseThrow();
        parB.setResultado(null);
        parB.setJustificativa(null);
        parB.setDataHoraVoto(null);
        parecerRepo.saveAndFlush(parB);

        encaminharComSucesso();

        mvc.perform(get("/avaliador/" + processoId).with(user("aval-a-ica").roles("AVALIADOR")))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .content().string(org.hamcrest.Matchers.containsString(TEXTO_SEGURO)));
        mvc.perform(get("/avaliador/" + processoId).with(user("aval-b-ica").roles("AVALIADOR")))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(TEXTO_SEGURO))));
    }

    /**
     * Depois que o operador RETOMA a analise, o {@code Parecer} e resetado e o
     * unico rastro do pedido passa a ser {@code HistoricoParecer} - o material
     * tem que continuar visivel para quem pediu (e so para ele).
     */
    @Test
    void materialContinuaVisivelDepoisDeRetomarAAnaliseQuandoOParecerJaFoiResetado() throws Exception {
        encaminharComSucesso();
        processoService.retomarAposInformacao(processoId);

        assertThat(historicoRepo.findByProcessoIdOrderByArquivadoEmDesc(processoId)).isNotEmpty();
        assertThat(parecerRepo.findByProcessoIdAndMembroId(processoId, membroPediuAId).orElseThrow()
            .getResultado()).isNull();

        mvc.perform(get("/avaliador/" + processoId).with(user("aval-a-ica").roles("AVALIADOR")))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .content().string(org.hamcrest.Matchers.containsString(TEXTO_SEGURO)));
        mvc.perform(get("/avaliador/" + processoId).with(user("aval-c-ica").roles("AVALIADOR")))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(TEXTO_SEGURO))));
    }

    // ------------------------------------------------------------------
    // 9: download com dupla checagem de posse
    // ------------------------------------------------------------------

    @Test
    void downloadDoMaterialE403ParaAvaliadorQueNuncaPediuInformacao() throws Exception {
        encaminharComSucesso();
        Long anexoId = anexosDoTipo(TipoAnexo.INFO_COMPLEMENTAR_AVALIADOR).get(0).getId();

        mvc.perform(get("/avaliador/" + processoId + "/pdf/" + anexoId)
                .with(user("aval-c-ica").roles("AVALIADOR")))
            .andExpect(status().isForbidden());

        mvc.perform(get("/avaliador/" + processoId + "/pdf/" + anexoId)
                .with(user("aval-a-ica").roles("AVALIADOR")))
            .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // 10-11: e-mail best-effort e trava de processo encerrado
    // ------------------------------------------------------------------

    @Test
    void falhaNoAvisoPorEmailNaoDesfazOEncaminhamentoJaGravado() throws Exception {
        when(emailSenderService.enviar(anyString(), anyString(), anyString()))
            .thenThrow(new RuntimeException("SMTP fora do ar"));

        mvc.perform(multipart("/processos/" + processoId + "/info-complementar/encaminhar-avaliadores")
                .param("texto", TEXTO_SEGURO).with(csrf()).with(operador()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attributeExists("msg"))
            .andExpect(flash().attributeExists("aviso"));

        // A escrita principal sobreviveu: o material continua disponivel.
        assertThat(anexosDoTipo(TipoAnexo.INFO_COMPLEMENTAR_AVALIADOR)).hasSize(1);
        mvc.perform(get("/avaliador/" + processoId).with(user("aval-a-ica").roles("AVALIADOR")))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .content().string(org.hamcrest.Matchers.containsString(TEXTO_SEGURO)));
    }

    @Test
    void processoEncerradoBloqueiaOEncaminhamento() throws Exception {
        Processo p = processoRepo.findById(processoId).orElseThrow();
        p.setStatus(StatusProcesso.DEFERIDO);
        processoRepo.saveAndFlush(p);

        mvc.perform(multipart("/processos/" + processoId + "/info-complementar/encaminhar-avaliadores")
                .param("texto", TEXTO_SEGURO).with(csrf()).with(operador()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attributeExists("erro"));

        assertThat(anexosDoTipo(TipoAnexo.INFO_COMPLEMENTAR_AVALIADOR)).isEmpty();
    }
}
