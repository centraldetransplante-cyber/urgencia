package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.Anexo;
import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.MensagemSolicitacao;
import br.gov.saude.sgpur.domain.MensagemSolicitacao.RemetenteMensagem;
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
import br.gov.saude.sgpur.domain.Sexo;
import br.gov.saude.sgpur.repository.AnexoRepository;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.MensagemSolicitacaoRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.AnexoStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Teste de INTEGRACAO (contexto Spring real + H2, servicos/repositorios REAIS,
 * sem nenhum {@code @MockitoBean}) da tela de detalhe do processo e dos POSTs
 * dos 3 controllers de {@code /processos} que perderam o {@code @Transactional}
 * de nivel de classe em 2026-07-29 ({@link ProcessoDetalheController},
 * {@link ProcessoDecisaoController}, {@link ProcessoAnexoController}).
 *
 * <p><b>Por que nao da para cobrir isso com {@code @WebMvcTest}:</b> aqueles
 * testes usam {@code @MockitoBean} nos servicos/repositorios, entao as
 * entidades sao objetos Java montados a mao - nunca proxies do Hibernate.
 * {@code LazyInitializationException} e literalmente inexprimivel ali, e o
 * mesmo vale para o {@code UnexpectedRollbackException} da familia de bug
 * corrigida aqui: sem o proxy transacional do Spring nao existe transacao
 * nenhuma para ser marcada como rollback-only. So um contexto real, com
 * {@code spring.jpa.open-in-view=false} e servicos {@code @Transactional} de
 * verdade, reproduz os dois problemas.</p>
 *
 * <p>Os testes abaixo quebram se alguem reintroduzir um acesso LAZY nao
 * resolvido em {@code GET /processos/{id}} (a tela mais pesada: navega
 * {@code pareceres} + {@code par.membro} E {@code anexos}, no controller e no
 * template, ja fora da transacao) ou uma transacao de controller em volta de um
 * {@code try/catch} de servico transacional.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sgpur-processo-detalhe-lazy;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.anexos.dir=./target/test-anexos-processo-detalhe-lazy"
})
class ProcessoDetalheSemTransacaoIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ProcessoRepository processoRepo;
    @Autowired
    private ParecerRepository parecerRepo;
    @Autowired
    private MembroUrgenciaRenalRepository membroRepo;
    @Autowired
    private AnexoRepository anexoRepo;
    @Autowired
    private AnexoStorageService anexoStorage;
    @Autowired
    private SolicitacaoOnlineRepository solicitacaoOnlineRepo;
    @Autowired
    private MensagemSolicitacaoRepository mensagemRepo;
    @Autowired
    private UsuarioRepository usuarioRepo;

    private Long processoId;
    private Long processoDeferidoId;
    private Long anexoClinicoId;
    private Long mensagemDeOutroOperadorId;
    // AnexoStorageService renomeia o arquivo para o nome padrao
    // (NomePadraoAnexo.gerar) - guardamos o nome REAL gravado para conferir a
    // renderizacao dos anexos na tela.
    private String nomeAnexoClinico;
    private String nomeAnexoInfoComplementar;
    private String nomeAnexoPortal;

    @BeforeEach
    @Transactional
    void preparar() throws Exception {
        mensagemRepo.deleteAll();
        solicitacaoOnlineRepo.deleteAll();
        anexoRepo.deleteAll();
        parecerRepo.deleteAll();
        processoRepo.deleteAll();
        membroRepo.deleteAll();
        usuarioRepo.findByUsername("operador-it").ifPresent(usuarioRepo::delete);
        usuarioRepo.findByUsername("solicitante-detalhe-it").ifPresent(usuarioRepo::delete);

        // 3 avaliadores: dois COM e-mail e um SEM (exercita os dois ramos do
        // template na aba Respostas - botao de lembrete x aviso "sem e-mail").
        MembroUrgenciaRenal comEmail = membroRepo.saveAndFlush(
                new MembroUrgenciaRenal("HCPA", "Dra. Com Email", "com.email@example.com"));
        MembroUrgenciaRenal outroComEmail = membroRepo.saveAndFlush(
                new MembroUrgenciaRenal("ISCMPA", "Dr. Outro Com Email", "outro@example.com"));
        MembroUrgenciaRenal semEmail = membroRepo.saveAndFlush(
                new MembroUrgenciaRenal("HNSC", "Dr. Sem Email", null));

        Processo p = new Processo();
        p.setNumero("77/2026");
        p.setAno(2026);
        p.setSequencial(77);
        p.setPacienteNome("Paciente Teste Detalhe");
        p.setPacienteRgct("999999999");
        p.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        p.setPacienteCpf("11144477735");
        p.setPacienteSexo(Sexo.MASCULINO);
        p.setSolicitanteEquipe("HCPA");
        p.setSolicitanteEmail("equipe@hcpa.example.com");
        p.setDataSituacaoEspecial(LocalDate.of(2026, 6, 1));
        p.setStatus(StatusProcesso.ENVIADO);
        processoRepo.saveAndFlush(p);
        processoId = p.getId();

        // Dois pareceres ja votados pelo portal (um favoravel, um desfavoravel) e
        // um ainda pendente - cobre os 3 estados exibidos na aba Respostas.
        Parecer votadoPortal = new Parecer(comEmail);
        votadoPortal.setProcesso(p);
        votadoPortal.setDataEnvio(LocalDate.of(2026, 6, 2));
        votadoPortal.setDataResposta(LocalDate.of(2026, 6, 3));
        votadoPortal.setResultado(ResultadoParecer.FAVORAVEL);
        votadoPortal.setOrigem(OrigemParecer.AVALIADOR_SISTEMA);
        votadoPortal.setJustificativa("Justificativa do avaliador do portal.");
        parecerRepo.saveAndFlush(votadoPortal);

        Parecer votadoPortalDesfavoravel = new Parecer(outroComEmail);
        votadoPortalDesfavoravel.setProcesso(p);
        votadoPortalDesfavoravel.setDataEnvio(LocalDate.of(2026, 6, 2));
        votadoPortalDesfavoravel.setDataResposta(LocalDate.of(2026, 6, 4));
        votadoPortalDesfavoravel.setResultado(ResultadoParecer.NAO_FAVORAVEL);
        votadoPortalDesfavoravel.setOrigem(OrigemParecer.AVALIADOR_SISTEMA);
        parecerRepo.saveAndFlush(votadoPortalDesfavoravel);

        Parecer pendente = new Parecer(semEmail);
        pendente.setProcesso(p);
        pendente.setDataEnvio(LocalDate.of(2026, 6, 2));
        parecerRepo.saveAndFlush(pendente);

        // Anexos de tipos variados, gravados de verdade (arquivo + registro).
        anexoStorage.salvarBytes(p, TipoAnexo.SOLICITACAO_AVALIADOR,
                "Copia anonimizada para as equipes", "processo-77-2026.pdf",
                "application/pdf", "%PDF-1.4 anonimizado".getBytes());
        Anexo clinico = anexoStorage.salvarBytes(p, TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR,
                "Documento clinico anonimizado", "laudo-clinico.pdf",
                "application/pdf", "%PDF-1.4 laudo".getBytes());
        anexoClinicoId = clinico.getId();
        nomeAnexoClinico = clinico.getNomeArquivo();
        nomeAnexoInfoComplementar = anexoStorage.salvarBytes(p, TipoAnexo.INFO_COMPLEMENTAR,
                "Informacao complementar recebida", "info-complementar.pdf",
                "application/pdf", "%PDF-1.4 info".getBytes()).getNomeArquivo();
        nomeAnexoPortal = anexoStorage.salvarBytes(p, TipoAnexo.DOCUMENTO_PORTAL_NAO_ANONIMIZADO,
                "Documento do portal pendente de anonimizacao", "portal-original.pdf",
                "application/pdf", "%PDF-1.4 portal".getBytes()).getNomeArquivo();

        // Usuario OPERADOR real (username usado pelo @WithMockUser dos testes de
        // mensagem abaixo) - enviarMensagem/apagarMensagem resolvem o operador
        // logado via usuarioRepo.findByUsername, entao precisa existir de verdade.
        Usuario operador = new Usuario();
        operador.setUsername("operador-it");
        operador.setSenha("{noop}x");
        operador.setNome("Operador Detalhe Teste");
        operador.setEmail("operador.detalhe@example.com");
        operador.setPerfil(Perfil.OPERADOR);
        usuarioRepo.saveAndFlush(operador);

        Usuario solicitante = new Usuario();
        solicitante.setUsername("solicitante-detalhe-it");
        solicitante.setSenha("{noop}x");
        solicitante.setNome("Solicitante Detalhe Teste");
        solicitante.setEmail("solicitante.detalhe@example.com");
        solicitante.setPerfil(Perfil.SOLICITANTE);
        solicitante.setEquipeSolicitante("HCPA");
        usuarioRepo.saveAndFlush(solicitante);

        // Solicitacao de origem CONVERTIDA, vinculada ao processo p - e o que
        // permite o chat na tela de detalhe do processo (enviarMensagem/
        // apagarMensagem resolvem a SolicitacaoOnline via
        // solicitacaoOnlineRepository.findIdByProcessoGeradoId).
        SolicitacaoOnline origem = new SolicitacaoOnline();
        origem.setUsuarioSolicitante(solicitante);
        origem.setPacienteNome(p.getPacienteNome());
        origem.setPacienteRgct(p.getPacienteRgct());
        origem.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        origem.setPacienteCpf("11144477735");
        origem.setPacienteSexo(Sexo.MASCULINO);
        origem.setSolicitanteEquipe(p.getSolicitanteEquipe());
        origem.setSolicitanteEmail(p.getSolicitanteEmail());
        origem.setDataSituacaoEspecial(p.getDataSituacaoEspecial());
        origem.setJustificativaClinica("Quadro grave.");
        origem.setStatus(StatusSolicitacaoOnline.CONVERTIDA);
        origem.setProcessoGerado(p);
        solicitacaoOnlineRepo.saveAndFlush(origem);

        // Mensagem de OUTRO operador (remetenteId != o do operador logado no
        // teste) - forca IllegalArgumentException em mensagemService.apagar
        // ("Voce nao pode apagar esta mensagem."), mesmo padrao usado em
        // SolicitacaoOnlineTriagemSemTransacaoIntegrationTest.
        MensagemSolicitacao msgDeOutro = new MensagemSolicitacao();
        msgDeOutro.setSolicitacaoOnline(origem);
        msgDeOutro.setRemetente(RemetenteMensagem.OPERADOR);
        msgDeOutro.setRemetenteId(999999L);
        msgDeOutro.setTexto("Mensagem de outro operador");
        msgDeOutro.setDataEnvio(java.time.LocalDateTime.now());
        msgDeOutro.setLida(false);
        mensagemRepo.saveAndFlush(msgDeOutro);
        mensagemDeOutroOperadorId = msgDeOutro.getId();

        // Mensagem do SOLICITANTE, usada para conferir que o polling AJAX
        // rotula o remetente com o NOME REAL dele (Usuario.nome), nao mais o
        // literal generico "Solicitante" (corrigido em 2026-08-07).
        MensagemSolicitacao msgDoSolicitante = new MensagemSolicitacao();
        msgDoSolicitante.setSolicitacaoOnline(origem);
        msgDoSolicitante.setRemetente(RemetenteMensagem.SOLICITANTE);
        msgDoSolicitante.setRemetenteId(solicitante.getId());
        msgDoSolicitante.setTexto("Mensagem do solicitante de verdade");
        msgDoSolicitante.setDataEnvio(java.time.LocalDateTime.now());
        msgDoSolicitante.setLida(false);
        mensagemRepo.saveAndFlush(msgDoSolicitante);

        // Segundo processo, ja DEFERIDO e SEM comprovante SNT: usado para o
        // caminho de erro de negocio da aba Finalizacao.
        Processo deferido = new Processo();
        deferido.setNumero("78/2026");
        deferido.setAno(2026);
        deferido.setSequencial(78);
        deferido.setPacienteNome("Paciente Ja Deferido");
        deferido.setPacienteRgct("888888888");
        deferido.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        deferido.setPacienteCpf("11144477735");
        deferido.setPacienteSexo(Sexo.MASCULINO);
        deferido.setSolicitanteEquipe("HCPA");
        deferido.setSolicitanteEmail("equipe@hcpa.example.com");
        deferido.setDataSituacaoEspecial(LocalDate.of(2026, 3, 1));
        deferido.setStatus(StatusProcesso.DEFERIDO);
        processoRepo.saveAndFlush(deferido);
        processoDeferidoId = deferido.getId();

        Parecer doDeferido = new Parecer(comEmail);
        doDeferido.setProcesso(deferido);
        doDeferido.setDataEnvio(LocalDate.of(2026, 3, 2));
        doDeferido.setDataResposta(LocalDate.of(2026, 3, 3));
        doDeferido.setResultado(ResultadoParecer.FAVORAVEL);
        doDeferido.setOrigem(OrigemParecer.AVALIADOR_SISTEMA);
        parecerRepo.saveAndFlush(doDeferido);
    }

    /**
     * O teste central desta classe: renderiza a tela de detalhe inteira com as
     * DUAS colecoes bag do processo em uso (pareceres + membro e anexos).
     *
     * <p>Falha (HTTP 500, {@code LazyInitializationException} no
     * {@code GlobalExceptionHandler}) se alguem remover o fetch join de
     * {@code ProcessoRepository.findByIdComPareceres}, a inicializacao explicita
     * dos anexos em {@code detalhe()} ou o {@code @Transactional} do metodo -
     * com {@code open-in-view=false} nao ha nenhuma outra rede de protecao.</p>
     */
    @Test
    @WithMockUser(username = "operador-it", roles = "OPERADOR")
    void detalheRenderizaPareceresComMembroEAnexosSemLazyInitializationException() throws Exception {
        mvc.perform(get("/processos/" + processoId))
                .andExpect(status().isOk())
                .andExpect(view().name("processos/detalhe"))
                // Colecao 1 (fetch join): pareceres + membro (rotulo = instituicao - nome).
                .andExpect(content().string(containsString("HCPA - Dra. Com Email")))
                .andExpect(content().string(containsString("ISCMPA - Dr. Outro Com Email")))
                .andExpect(content().string(containsString("HNSC - Dr. Sem Email")))
                .andExpect(content().string(containsString("Justificativa do avaliador do portal.")))
                // Colecao 2 (inicializada dentro da transacao): anexos, cada um
                // exibido numa area diferente da tela (lista geral de Anexos,
                // aba Respostas e a trava de anonimizacao da aba Envio).
                .andExpect(content().string(containsString(nomeAnexoClinico)))
                .andExpect(content().string(containsString(nomeAnexoInfoComplementar)))
                .andExpect(content().string(containsString(nomeAnexoPortal)));
    }

    /**
     * Guarda o FETCH JOIN de {@code findByIdComPareceres}: a consulta e usada
     * fora de qualquer transacao aqui, entao {@code pareceres} e
     * {@code par.membro} tem que voltar ja materializados. Se alguem trocar a
     * consulta por um {@code findById} simples (ou tirar o {@code left join
     * fetch}), esta navegacao lanca {@code LazyInitializationException} - o que
     * na tela de detalhe apareceria so como um N+1 silencioso (o
     * {@code @Transactional} do metodo mascara), mas quebraria qualquer uso da
     * consulta fora de transacao.
     */
    @Test
    void consultaComFetchJoinDevolvePareceresEMembroJaCarregadosForaDeTransacao() {
        var p = processoRepo.findByIdComPareceres(processoId).orElseThrow();
        assertThat(p.getPareceres()).hasSize(3);
        assertThat(p.getPareceres())
                .extracting(par -> par.getMembro().getRotulo())
                .contains("HCPA - Dra. Com Email", "HNSC - Dr. Sem Email");
    }

    /** Mesma tela para um processo ja encerrado (banner + abas 5/6 liberadas). */
    @Test
    @WithMockUser(username = "operador-it", roles = "OPERADOR")
    void detalheDeProcessoEncerradoTambemRenderizaSemLazyInitializationException() throws Exception {
        mvc.perform(get("/processos/" + processoDeferidoId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("HCPA - Dra. Com Email")));
    }

    /**
     * Exclusao de anexo navega {@code anexo.getProcesso()} e
     * {@code anexo.getParecer()} (ambos LAZY) nas guardas de negocio - so
     * funciona com o {@code @Transactional} proprio do metodo.
     */
    @Test
    @WithMockUser(username = "operador-it", roles = "OPERADOR")
    void excluirAnexoNavegaAssociacoesLazyDoAnexoSemErro() throws Exception {
        mvc.perform(post("/processos/anexos/" + anexoClinicoId + "/excluir").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("msg", containsString("Anexo removido")));

        assertThat(anexoRepo.findById(anexoClinicoId)).isEmpty();
    }

    /**
     * CAMINHO DE FALHA (a familia de bug desta correcao): o upload rejeita a
     * extensao dentro de {@code AnexoStorageService.salvar}, um metodo
     * {@code @Transactional}. Enquanto o controller abria transacao de classe, a
     * {@code IllegalArgumentException} marcava a transacao como rollback-only e
     * o commit no fim do metodo devolvia {@code UnexpectedRollbackException} -
     * o operador via "Erro interno" (500) no lugar da mensagem de negocio.
     */
    @Test
    @WithMockUser(username = "operador-it", roles = "OPERADOR")
    void uploadComExtensaoProibidaDevolveMensagemDeNegocioENao500() throws Exception {
        MockMultipartFile arquivo = new MockMultipartFile("arquivo", "malware.exe",
                "application/octet-stream", "MZ".getBytes());

        mvc.perform(multipart("/processos/" + processoId + "/anexos")
                        .file(arquivo)
                        .param("tipo", TipoAnexo.OUTRO.name())
                        .param("descricao", "teste")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("erro", containsString("Falha ao anexar")));
    }

    /**
     * Reabrir um processo que NAO esta encerrado lanca
     * {@code IllegalStateException} dentro de {@code ProcessoService.reabrir}
     * ({@code @Transactional}), tratada por {@code try/catch} no controller -
     * outro ponto que virava 500 com a transacao de classe.
     */
    @Test
    @WithMockUser(username = "admin-it", roles = "ADMIN")
    void reabrirProcessoNaoEncerradoDevolveMensagemDeNegocioENao500() throws Exception {
        mvc.perform(post("/processos/" + processoId + "/reabrir").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("erro"));
    }

    /**
     * POST .../mensagem/{id}/apagar sobre uma mensagem que NAO pertence ao
     * operador logado: {@code mensagemService.apagar} lanca
     * {@code IllegalArgumentException} (erro de negocio esperado, nao bug).
     * Mesmo padrao ja coberto para os controllers irmaos
     * ({@code SolicitacaoOnlineTriagemController}/{@code SolicitanteController}
     * em seus respectivos testes "SemTransacao") - faltava aqui para
     * {@link ProcessoDetalheController#apagarMensagem}. Antes da correcao
     * (transacao de classe), isto resultaria em
     * {@code UnexpectedRollbackException} -> HTTP 500. Com a transacao de
     * classe removida, o controller deve devolver o redirect normal com o
     * flash de erro tratado pelo catch - e a mensagem, que nao era do
     * operador, continua intacta (nao apagada).
     */
    @Test
    @WithMockUser(username = "operador-it", roles = "OPERADOR")
    void apagarMensagemDeOutroOperadorDevolveFlashDeErroSemHttp500EMantemMensagemIntacta() throws Exception {
        mvc.perform(post("/processos/" + processoId + "/mensagem/" + mensagemDeOutroOperadorId + "/apagar")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/processos/" + processoId))
                .andExpect(flash().attributeExists("erro"));

        MensagemSolicitacao aindaLa = mensagemRepo.findById(mensagemDeOutroOperadorId).orElseThrow();
        assertThat(aindaLa.isDeletada()).isFalse();
        assertThat(aindaLa.getTexto()).isEqualTo("Mensagem de outro operador");
    }

    /**
     * POST .../mensagem/{id}/apagar/ajax (variante AJAX) com a mesma mensagem
     * de outro operador: deve devolver 400 JSON tratado, nunca 500. Mesmo
     * padrao de {@link #apagarMensagemDeOutroOperadorDevolveFlashDeErroSemHttp500EMantemMensagemIntacta}.
     */
    @Test
    @WithMockUser(username = "operador-it", roles = "OPERADOR")
    void apagarMensagemAjaxDeOutroOperadorDevolve400SemHttp500() throws Exception {
        mvc.perform(post("/processos/" + processoId + "/mensagem/" + mensagemDeOutroOperadorId + "/apagar/ajax")
                        .with(csrf()))
                .andExpect(status().isBadRequest());

        MensagemSolicitacao aindaLa = mensagemRepo.findById(mensagemDeOutroOperadorId).orElseThrow();
        assertThat(aindaLa.isDeletada()).isFalse();
    }

    /**
     * Corrigido em 2026-08-07: o polling AJAX do chat (lado do operador)
     * rotulava toda mensagem do solicitante com o literal generico
     * "Solicitante", em vez do nome real de quem enviou. Confirma que o JSON
     * devolvido por {@code GET /processos/{id}/mensagens} traz
     * {@code Usuario.nome} de verdade ("Solicitante Detalhe Teste") e nao o
     * literal antigo.
     */
    @Test
    @WithMockUser(username = "operador-it", roles = "OPERADOR")
    void mensagensAjaxRotulaMensagemDoSolicitanteComONomeRealENaoComLiteralGenerico() throws Exception {
        mvc.perform(get("/processos/" + processoId + "/mensagens"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Solicitante Detalhe Teste")))
                .andExpect(content().string(containsString("Mensagem do solicitante de verdade")));
    }
}
