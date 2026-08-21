package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.domain.Sexo;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.dto.EmailTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Testes do fluxo de staging do modulo experimental "Solicitacao Online"
 * (ver docs/PLANO-SOLICITANTE.md). Cobre as regras que este servico impoe:
 * equipe/e-mail SEMPRE vem do usuario logado (nunca do formulario), e as
 * transicoes de status (ENVIADA -> CANCELADA/DEVOLVIDA/CONVERTIDA) so valem
 * a partir de ENVIADA.
 */
@ExtendWith(MockitoExtension.class)
class SolicitacaoOnlineServiceTest {

    @Mock
    SolicitacaoOnlineRepository repository;
    @Mock
    AnexoSolicitacaoOnlineStorageService anexoStorage;
    @Mock
    AnexoStorageService anexoStorageProcesso;
    @Mock
    UsuarioRepository usuarioRepository;
    @Mock
    EmailSenderService emailSenderService;
    @Mock
    EmailTemplateService emailTemplateService;
    @Mock
    ProcessoService processoService;
    @Mock
    AuditoriaService auditoria;

    SolicitacaoOnlineService service;

    @BeforeEach
    void setUp() {
        service = new SolicitacaoOnlineService(repository, anexoStorage, anexoStorageProcesso,
            usuarioRepository, emailSenderService, emailTemplateService, processoService,
            auditoria, "http://localhost:3000");
    }

    private Usuario usuarioSolicitante(Long id) {
        Usuario u = new Usuario();
        u.setId(id);
        u.setUsername("solicitante" + id);
        u.setPerfil(Perfil.SOLICITANTE);
        u.setEquipeSolicitante("HCPA");
        u.setEmail("hcpa@example.com");
        return u;
    }

    private SolicitacaoOnline solicitacaoPedido() {
        SolicitacaoOnline s = new SolicitacaoOnline();
        s.setPacienteNome("Fulano de Tal");
        s.setPacienteRgct("123456789-12345");
        s.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        s.setPacienteCpf("11144477735");
        s.setPacienteSexo(Sexo.MASCULINO);
        s.setDataSituacaoEspecial(LocalDate.now());
        s.setJustificativaClinica("Quadro grave, necessita avaliacao urgente.");
        return s;
    }

    @Test
    void criarPreencheEquipeEEmailAPartirDoUsuarioLogadoNuncaDoFormulario() {
        when(repository.save(any(SolicitacaoOnline.class))).thenAnswer(inv -> inv.getArgument(0));
        Usuario usuario = usuarioSolicitante(1L);
        SolicitacaoOnline pedido = solicitacaoPedido();
        // Tentativa de forjar outra equipe no formulario - deve ser ignorada.
        pedido.setSolicitanteEquipe("EQUIPE FORJADA");
        pedido.setSolicitanteEmail("forjado@example.com");

        SolicitacaoOnline salva = service.criar(pedido, usuario, null);

        assertThat(salva.getSolicitanteEquipe()).isEqualTo("HCPA");
        assertThat(salva.getSolicitanteEmail()).isEqualTo("hcpa@example.com");
        assertThat(salva.getUsuarioSolicitante()).isSameAs(usuario);
        assertThat(salva.getStatus()).isEqualTo(StatusSolicitacaoOnline.ENVIADA);
    }

    @Test
    void criarSobrescreveDataEnvioForjadaNoFormularioComOMomentoRealDoEnvio() {
        when(repository.save(any(SolicitacaoOnline.class))).thenAnswer(inv -> inv.getArgument(0));
        Usuario usuario = usuarioSolicitante(1L);
        SolicitacaoOnline pedido = solicitacaoPedido();
        // Tentativa de forjar uma data de envio antiga (ex.: para furar a fila
        // de triagem, que ordena por dataEnvio ASC) - deve ser sobrescrita.
        LocalDateTime dataForjada = LocalDateTime.now().minusYears(1);
        pedido.setDataEnvio(dataForjada);

        LocalDateTime antes = LocalDateTime.now();
        SolicitacaoOnline salva = service.criar(pedido, usuario, null);
        LocalDateTime depois = LocalDateTime.now();

        assertThat(salva.getDataEnvio()).isNotEqualTo(dataForjada);
        assertThat(salva.getDataEnvio()).isBetween(antes, depois);
    }

    @Test
    void criarComUsuarioSemEquipeVinculadaLancaExcecao() {
        Usuario usuario = usuarioSolicitante(1L);
        usuario.setEquipeSolicitante(null);

        assertThatThrownBy(() -> service.criar(solicitacaoPedido(), usuario, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("sem equipe vinculada");
    }

    /**
     * E-mail adicional (2026-08-21): campo opcional, so para este pedido -
     * ver javadoc de SolicitacaoOnline.emailAdicional. Preenchido e valido,
     * e salvo (trim aplicado); nunca substitui solicitanteEmail (que continua
     * vindo do usuario logado, ja coberto pelo teste acima).
     */
    @Test
    void criarComEmailAdicionalValidoSalvaOValorAparado() {
        when(repository.save(any(SolicitacaoOnline.class))).thenAnswer(inv -> inv.getArgument(0));
        Usuario usuario = usuarioSolicitante(1L);
        SolicitacaoOnline pedido = solicitacaoPedido();
        pedido.setEmailAdicional("  outro-contato@equipe.com.br  ");

        SolicitacaoOnline salva = service.criar(pedido, usuario, null);

        assertThat(salva.getEmailAdicional()).isEqualTo("outro-contato@equipe.com.br");
        assertThat(salva.getSolicitanteEmail()).isEqualTo("hcpa@example.com");
    }

    /**
     * Campo vazio no formulario ("" submetido pelo navegador) vira null no
     * banco, nunca uma string em branco - o resto do sistema (CC nos
     * e-mails, exibicao condicional nos templates) testa null/isBlank.
     */
    @Test
    void criarComEmailAdicionalEmBrancoNormalizaParaNull() {
        when(repository.save(any(SolicitacaoOnline.class))).thenAnswer(inv -> inv.getArgument(0));
        Usuario usuario = usuarioSolicitante(1L);
        SolicitacaoOnline pedido = solicitacaoPedido();
        pedido.setEmailAdicional("   ");

        SolicitacaoOnline salva = service.criar(pedido, usuario, null);

        assertThat(salva.getEmailAdicional()).isNull();
    }

    /**
     * Sem @Valid neste @ModelAttribute (ver SolicitanteController), a
     * validacao de formato precisa acontecer explicitamente em criar() -
     * senao um valor invalido so seria pego pela validacao automatica do
     * Hibernate no save(), que lanca ConstraintViolationException sem
     * @ExceptionHandler dedicado (500 cru), em vez do redirect gracioso que
     * o catch (IllegalArgumentException) do controller ja devolve.
     */
    @Test
    void criarComEmailAdicionalInvalidoLancaExcecaoAntesDeSalvar() {
        Usuario usuario = usuarioSolicitante(1L);
        SolicitacaoOnline pedido = solicitacaoPedido();
        pedido.setEmailAdicional("nao-e-um-email");

        assertThatThrownBy(() -> service.criar(pedido, usuario, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("E-mail adicional invalido");

        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void cancelarPorOutroUsuarioLancaExcecao() {
        SolicitacaoOnline s = solicitacaoPedido();
        s.setId(10L);
        s.setUsuarioSolicitante(usuarioSolicitante(1L));
        s.setStatus(StatusSolicitacaoOnline.ENVIADA);
        when(repository.findById(10L)).thenReturn(java.util.Optional.of(s));

        assertThatThrownBy(() -> service.cancelar(10L, 2L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("proprias solicitacoes");
    }

    @Test
    void cancelarSolicitacaoConvertidaSemProcessoLancaExcecao() {
        SolicitacaoOnline s = solicitacaoPedido();
        s.setId(11L);
        s.setUsuarioSolicitante(usuarioSolicitante(1L));
        s.setStatus(StatusSolicitacaoOnline.CONVERTIDA);
        when(repository.findById(11L)).thenReturn(java.util.Optional.of(s));

        assertThatThrownBy(() -> service.cancelar(11L, 1L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("nao pode mais ser cancelada");
    }

    @Test
    void cancelarPeloProprioDonoEnquantoEnviadaMarcaCancelada() {
        SolicitacaoOnline s = solicitacaoPedido();
        s.setId(12L);
        s.setUsuarioSolicitante(usuarioSolicitante(1L));
        s.setStatus(StatusSolicitacaoOnline.ENVIADA);
        when(repository.findById(12L)).thenReturn(java.util.Optional.of(s));

        Long processoCancelado = service.cancelar(12L, 1L);

        assertThat(s.getStatus()).isEqualTo(StatusSolicitacaoOnline.CANCELADA);
        // Sem processo gerado, nao ha ninguem para avisar.
        assertThat(processoCancelado).isNull();
        org.mockito.Mockito.verifyNoInteractions(processoService);
    }

    // -------------------------------------------------------------------------
    // Cancelamento depois de o pedido ja ter virado processo (2026-07-29).
    // -------------------------------------------------------------------------

    private SolicitacaoOnline convertidaCom(StatusProcesso statusProcesso) {
        Processo p = new Processo();
        p.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        p.setPacienteCpf("11144477735");
        p.setPacienteSexo(Sexo.MASCULINO);
        p.setId(500L);
        p.setNumero("07/2026");
        p.setPacienteNome("Fulano de Tal");
        p.setStatus(statusProcesso);

        SolicitacaoOnline s = solicitacaoPedido();
        s.setId(20L);
        s.setUsuarioSolicitante(usuarioSolicitante(1L));
        s.setStatus(StatusSolicitacaoOnline.CONVERTIDA);
        s.setProcessoGerado(p);
        return s;
    }

    @Test
    void podeCancelarEnquantoOProcessoNaoFoiDecidido() {
        assertThat(service.podeCancelar(convertidaCom(StatusProcesso.SOLICITADO))).isTrue();
        assertThat(service.podeCancelar(convertidaCom(StatusProcesso.ENVIADO))).isTrue();
        assertThat(service.podeCancelar(convertidaCom(StatusProcesso.SOLICITA_INFORMACAO))).isTrue();
    }

    @Test
    void naoPodeCancelarDepoisDaDecisaoFinal() {
        assertThat(service.podeCancelar(convertidaCom(StatusProcesso.DEFERIDO))).isFalse();
        assertThat(service.podeCancelar(convertidaCom(StatusProcesso.INDEFERIDO))).isFalse();
        assertThat(service.podeCancelar(convertidaCom(StatusProcesso.CANCELADO))).isFalse();
    }

    /**
     * Delega a ProcessoService.decidir em vez de trocar o status na mao: mesmo
     * caminho do cancelamento pelo operador, com as mesmas travas.
     */
    @Test
    void cancelarProcessoEmAnaliseDelegaParaDecidirEDevolveOIdDoProcesso() {
        SolicitacaoOnline s = convertidaCom(StatusProcesso.ENVIADO);
        when(repository.findById(20L)).thenReturn(java.util.Optional.of(s));

        Long processoCancelado = service.cancelar(20L, 1L);

        assertThat(processoCancelado).isEqualTo(500L);
        org.mockito.Mockito.verify(processoService)
            .decidir(500L, StatusProcesso.CANCELADO, null);
    }

    @Test
    void cancelarProcessoJaDecididoLancaExcecaoComMensagemPropria() {
        SolicitacaoOnline s = convertidaCom(StatusProcesso.DEFERIDO);
        when(repository.findById(20L)).thenReturn(java.util.Optional.of(s));

        assertThatThrownBy(() -> service.cancelar(20L, 1L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ja foi decidido");
        org.mockito.Mockito.verifyNoInteractions(processoService);
    }

    @Test
    void notificaAvaliadoresPendentesDoCancelamento() {
        Processo p = new Processo();
        p.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        p.setPacienteCpf("11144477735");
        p.setPacienteSexo(Sexo.MASCULINO);
        p.setId(500L);
        p.setNumero("07/2026");
        p.setPacienteNome("Fulano de Tal");
        Parecer pendente = new Parecer(new MembroUrgenciaRenal("HCPA", "Dr. A", "a@hcpa.br"));
        when(processoService.buscar(500L)).thenReturn(p);
        when(processoService.pareceresPendentesComEmail(500L)).thenReturn(java.util.List.of(pendente));
        when(emailTemplateService.emailCancelamentoAvaliador(any(), any()))
            .thenReturn(new EmailTemplate("cancelamento-avaliador", "Aviso", "slash-circle",
                "Assunto", "Corpo"));
        when(emailSenderService.enviar(any(String.class), any(), any())).thenReturn(true);

        assertThat(service.notificarAvaliadoresCancelamento(500L)).isEmpty();

        org.mockito.Mockito.verify(emailSenderService).enviar("a@hcpa.br", "Assunto", "Corpo");
    }

    /**
     * Avaliador sem e-mail ou falha de SMTP volta na lista de "nao avisados" -
     * nunca lanca, porque o cancelamento ja esta commitado quando isto roda.
     */
    @Test
    void avaliadorSemEmailVoltaComoNaoAvisadoSemLancar() {
        Processo p = new Processo();
        p.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        p.setPacienteCpf("11144477735");
        p.setPacienteSexo(Sexo.MASCULINO);
        p.setId(500L);
        p.setNumero("07/2026");
        p.setPacienteNome("Fulano de Tal");
        Parecer pendente = new Parecer(new MembroUrgenciaRenal("HCPA", "Dr. Sem Email", null));
        when(processoService.buscar(500L)).thenReturn(p);
        when(processoService.pareceresPendentesComEmail(500L)).thenReturn(java.util.List.of(pendente));

        assertThat(service.notificarAvaliadoresCancelamento(500L))
            .containsExactly("Dr. Sem Email");
        org.mockito.Mockito.verifyNoInteractions(emailSenderService);
    }

    @Test
    void devolverRegistraObservacoesEStatusDevolvida() {
        SolicitacaoOnline s = solicitacaoPedido();
        s.setId(13L);
        s.setStatus(StatusSolicitacaoOnline.ENVIADA);
        when(repository.findById(13L)).thenReturn(java.util.Optional.of(s));

        service.devolver(13L, "Falta documento clinico.");

        assertThat(s.getStatus()).isEqualTo(StatusSolicitacaoOnline.DEVOLVIDA);
        assertThat(s.getObservacoesTriagem()).isEqualTo("Falta documento clinico.");
    }

    @Test
    void devolverSolicitacaoJaTriadaLancaExcecao() {
        SolicitacaoOnline s = solicitacaoPedido();
        s.setId(14L);
        s.setStatus(StatusSolicitacaoOnline.DEVOLVIDA);
        when(repository.findById(14L)).thenReturn(java.util.Optional.of(s));

        assertThatThrownBy(() -> service.devolver(14L, "motivo"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void converterSemAnexosMarcaConvertidaEVinculaProcessoGerado() {
        SolicitacaoOnline s = solicitacaoPedido();
        s.setId(15L);
        s.setStatus(StatusSolicitacaoOnline.ENVIADA);
        when(repository.findById(15L)).thenReturn(java.util.Optional.of(s));
        Processo processo = new Processo();
        processo.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        processo.setPacienteCpf("11144477735");
        processo.setPacienteSexo(Sexo.MASCULINO);
        processo.setId(99L);
        processo.setNumero("01/2026");

        service.converter(15L, processo);

        assertThat(s.getStatus()).isEqualTo(StatusSolicitacaoOnline.CONVERTIDA);
        assertThat(s.getProcessoGerado()).isSameAs(processo);
    }

    /**
     * TRAVA DE ANONIMIZACAO: o documento que o solicitante anexou no portal
     * traz o nome completo do paciente no corpo do laudo. Ele NAO pode ser
     * copiado como DOCUMENTO_CLINICO_AVALIADOR (tipo que o
     * RegistroEnvioService funde e entrega aos 3 medicos) - entra como
     * staging (DOCUMENTO_PORTAL_NAO_ANONIMIZADO) e so e promovido por
     * confirmacao explicita do operador.
     */
    @Test
    void converterCopiaAnexoDoPortalComoStagingNaoAnonimizado(@org.junit.jupiter.api.io.TempDir
                                                              java.nio.file.Path tempDir) throws Exception {
        SolicitacaoOnline s = solicitacaoPedido();
        s.setId(17L);
        s.setStatus(StatusSolicitacaoOnline.ENVIADA);
        AnexoSolicitacaoOnline anexo = new AnexoSolicitacaoOnline();
        anexo.setNomeArquivo("laudo.pdf");
        anexo.setContentType("application/pdf");
        s.addAnexo(anexo);
        when(repository.findById(17L)).thenReturn(java.util.Optional.of(s));
        java.nio.file.Path arquivo = tempDir.resolve("laudo.pdf");
        java.nio.file.Files.write(arquivo, "conteudo com nome do paciente".getBytes());
        when(anexoStorage.resolverArquivo(anexo)).thenReturn(arquivo);
        Processo processo = new Processo();
        processo.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        processo.setPacienteCpf("11144477735");
        processo.setPacienteSexo(Sexo.MASCULINO);
        processo.setId(99L);
        processo.setNumero("01/2026");

        service.converter(17L, processo);

        org.mockito.Mockito.verify(anexoStorageProcesso).salvarBytes(
            org.mockito.ArgumentMatchers.eq(processo),
            org.mockito.ArgumentMatchers.eq(TipoAnexo.DOCUMENTO_PORTAL_NAO_ANONIMIZADO),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq("laudo.pdf"),
            org.mockito.ArgumentMatchers.eq("application/pdf"),
            org.mockito.ArgumentMatchers.any(byte[].class));
        org.mockito.Mockito.verify(anexoStorageProcesso, org.mockito.Mockito.never()).salvarBytes(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(byte[].class));
    }

    @Test
    void converterSolicitacaoJaTriadaLancaExcecao() {
        SolicitacaoOnline s = solicitacaoPedido();
        s.setId(16L);
        s.setStatus(StatusSolicitacaoOnline.CANCELADA);
        when(repository.findById(16L)).thenReturn(java.util.Optional.of(s));

        assertThatThrownBy(() -> service.converter(16L, new Processo()))
            .isInstanceOf(IllegalStateException.class);
    }

    private SolicitacaoOnline comStatus(long id, StatusSolicitacaoOnline status) {
        SolicitacaoOnline s = solicitacaoPedido();
        s.setId(id);
        s.setStatus(status);
        return s;
    }

    @Test
    void resumirContaCorretamentePorStatus() {
        java.util.List<SolicitacaoOnline> solicitacoes = java.util.List.of(
            comStatus(1L, StatusSolicitacaoOnline.ENVIADA),
            comStatus(2L, StatusSolicitacaoOnline.ENVIADA),
            comStatus(3L, StatusSolicitacaoOnline.CONVERTIDA),
            comStatus(4L, StatusSolicitacaoOnline.DEVOLVIDA),
            comStatus(5L, StatusSolicitacaoOnline.CANCELADA));

        SolicitacaoOnlineService.Resumo resumo = service.resumir(solicitacoes);

        assertThat(resumo.total()).isEqualTo(5);
        assertThat(resumo.aguardandoTriagem()).isEqualTo(2);
        assertThat(resumo.emAnalise()).isEqualTo(1);
        assertThat(resumo.decididas()).isEqualTo(1);
        assertThat(resumo.devolvidas()).isEqualTo(1);
    }

    @Test
    void resumirComListaVaziaRetornaTodosZerados() {
        SolicitacaoOnlineService.Resumo resumo = service.resumir(java.util.List.of());

        assertThat(resumo.total()).isZero();
        assertThat(resumo.aguardandoTriagem()).isZero();
        assertThat(resumo.emAnalise()).isZero();
        assertThat(resumo.decididas()).isZero();
        assertThat(resumo.devolvidas()).isZero();
    }

    private Processo processoComStatus(StatusProcesso status) {
        Processo p = new Processo();
        p.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        p.setPacienteCpf("11144477735");
        p.setPacienteSexo(Sexo.MASCULINO);
        p.setId(100L);
        p.setNumero("01/2026");
        p.setStatus(status);
        return p;
    }

    @Test
    void precisaInformacaoComplementarQuandoConvertidaEProcessoPausado() {
        SolicitacaoOnline s = comStatus(20L, StatusSolicitacaoOnline.CONVERTIDA);
        s.setProcessoGerado(processoComStatus(StatusProcesso.SOLICITA_INFORMACAO));

        assertThat(service.precisaInformacaoComplementar(s)).isTrue();
    }

    @Test
    void precisaInformacaoComplementarFalsoQuandoAindaEnviada() {
        SolicitacaoOnline s = comStatus(21L, StatusSolicitacaoOnline.ENVIADA);

        assertThat(service.precisaInformacaoComplementar(s)).isFalse();
    }

    @Test
    void precisaInformacaoComplementarFalsoQuandoConvertidaMasProcessoNaoPausado() {
        SolicitacaoOnline s = comStatus(22L, StatusSolicitacaoOnline.CONVERTIDA);
        s.setProcessoGerado(processoComStatus(StatusProcesso.ENVIADO));

        assertThat(service.precisaInformacaoComplementar(s)).isFalse();
    }

    @Test
    void precisaInformacaoComplementarFalsoQuandoProcessoGeradoNulo() {
        SolicitacaoOnline s = comStatus(23L, StatusSolicitacaoOnline.CONVERTIDA);
        s.setProcessoGerado(null);

        assertThat(service.precisaInformacaoComplementar(s)).isFalse();
    }

    @Test
    void enviarInformacaoComplementarGravaAnexoQuandoEstadoCorreto() throws Exception {
        SolicitacaoOnline s = comStatus(24L, StatusSolicitacaoOnline.CONVERTIDA);
        Processo processo = processoComStatus(StatusProcesso.SOLICITA_INFORMACAO);
        s.setProcessoGerado(processo);
        org.springframework.mock.web.MockMultipartFile arquivo =
            new org.springframework.mock.web.MockMultipartFile("arquivos", "resposta.pdf",
                "application/pdf", "conteudo".getBytes());

        service.enviarInformacaoComplementar(s, null, java.util.List.of(arquivo));

        org.mockito.Mockito.verify(anexoStorageProcesso).salvar(
            org.mockito.ArgumentMatchers.eq(processo),
            org.mockito.ArgumentMatchers.eq(TipoAnexo.INFO_COMPLEMENTAR),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq(arquivo));
    }

    @Test
    void enviarInformacaoComplementarSemTextoNemArquivoLancaExcecao() {
        SolicitacaoOnline s = comStatus(25L, StatusSolicitacaoOnline.CONVERTIDA);
        s.setProcessoGerado(processoComStatus(StatusProcesso.SOLICITA_INFORMACAO));

        assertThatThrownBy(() -> service.enviarInformacaoComplementar(s, null, java.util.List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("anexe pelo menos um arquivo");
    }

    @Test
    void enviarInformacaoComplementarComEstadoErradoLancaExcecao() {
        SolicitacaoOnline s = comStatus(26L, StatusSolicitacaoOnline.CONVERTIDA);
        s.setProcessoGerado(processoComStatus(StatusProcesso.ENVIADO));
        org.springframework.mock.web.MockMultipartFile arquivo =
            new org.springframework.mock.web.MockMultipartFile("arquivos", "resposta.pdf",
                "application/pdf", "conteudo".getBytes());

        assertThatThrownBy(() -> service.enviarInformacaoComplementar(s, null, java.util.List.of(arquivo)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("nao esta aguardando informacao complementar");
    }

    /**
     * Regressao do achado do ultrareview no PR #1: IOException (checked) do
     * storage propagando sem wrap faria o Spring COMMITAR a transacao mesmo
     * com falha (rollback default so cobre RuntimeException/Error) -
     * anexos ja gravados em iteracoes anteriores do loop ficariam
     * commitados. Precisa virar RuntimeException para garantir rollback,
     * mesmo padrao ja usado em criar() (acima, no mesmo arquivo).
     */
    @Test
    void enviarInformacaoComplementarEnvolveIOExceptionEmIllegalStateException() throws Exception {
        SolicitacaoOnline s = comStatus(27L, StatusSolicitacaoOnline.CONVERTIDA);
        s.setProcessoGerado(processoComStatus(StatusProcesso.SOLICITA_INFORMACAO));
        org.springframework.mock.web.MockMultipartFile arquivo =
            new org.springframework.mock.web.MockMultipartFile("arquivos", "resposta.pdf",
                "application/pdf", "conteudo".getBytes());
        org.mockito.Mockito.when(anexoStorageProcesso.salvar(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(TipoAnexo.INFO_COMPLEMENTAR),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(arquivo)))
            .thenThrow(new java.io.IOException("Disco cheio"));

        assertThatThrownBy(() -> service.enviarInformacaoComplementar(s, null, java.util.List.of(arquivo)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Falha ao salvar arquivo enviado")
            .hasCauseInstanceOf(java.io.IOException.class);
    }
}
