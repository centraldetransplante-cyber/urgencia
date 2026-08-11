package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.MensagemAvaliador.RemetenteMensagemAvaliador;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.ResultadoParecer;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.MensagemAvaliadorRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.MensagemAvaliadorService;
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
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bug real relatado em producao (2026-08-11): <i>"no portal do avaliador da Ana
 * indica que tenho 2 novas mensagens, e clico nesse indicador amarelo de 2
 * mensagem e nada acontece"</i>.
 *
 * <p>O badge da navbar
 * ({@code MensagemAvaliadorService.contarNaoLidasParaMembro}) soma mensagens do
 * operador de QUALQUER processo, sem filtro de status, mas
 * {@code GET /avaliador/{processoId}} — a UNICA tela do Portal com o chat do
 * processo — devolvia 403 quando o avaliador ja tinha votado ou quando o
 * processo ja estava decidido. Resultado: a mensagem era contada e nao havia
 * NENHUM caminho na UI para abri-la.
 *
 * <p>Este teste roda contra contexto Spring real + H2 real (sem mock de
 * servico, conforme a convencao do projeto para caminho de falha/escrita) e
 * cobre os dois cenarios do relato, mais as duas travas que NAO podem
 * afrouxar junto:
 * <ul>
 *   <li>o POST de voto continua 403 nos dois casos;</li>
 *   <li>quem nao e avaliador do processo continua sem acesso nenhum (posse).</li>
 * </ul>
 *
 * <p><b>Imparcialidade</b> (a tela mais sensivel do sistema): as assercoes
 * negativas confirmam que o modo leitura nao passou a revelar o resultado da
 * decisao a quem foi dispensado, nem o nome completo do paciente, nem a
 * identidade dos outros avaliadores.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-avaliador-leitura;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.anexos.dir=./target/test-anexos-avaliador-leitura"
})
class AvaliadorLeituraProcessoConcluidoIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private UsuarioRepository usuarioRepo;
    @Autowired private ProcessoRepository processoRepo;
    @Autowired private ParecerRepository parecerRepo;
    @Autowired private MembroUrgenciaRenalRepository membroRepo;
    @Autowired private MensagemAvaliadorRepository mensagemRepo;
    @Autowired private MensagemAvaliadorService mensagemService;

    @MockitoBean private SolicitacaoOnlineRepository solicitacaoOnlineRepo;

    /** Processo ainda em analise, no qual "Dra. Ana" JA VOTOU. */
    private Processo processoVotado;
    /** Processo ja DECIDIDO sem o voto dela (dispensada pela maioria dos outros dois). */
    private Processo processoDispensado;
    private MembroUrgenciaRenal ana;
    private Usuario usuarioAna;

    @BeforeEach
    @Transactional
    void preparar() {
        mensagemRepo.deleteAll();
        parecerRepo.deleteAll();
        usuarioRepo.findByUsername("ana-leitura-it").ifPresent(usuarioRepo::delete);
        usuarioRepo.findByUsername("outro-avaliador-it").ifPresent(usuarioRepo::delete);
        processoRepo.deleteAll();
        membroRepo.deleteAll();

        when(solicitacaoOnlineRepo.findByProcessoGeradoId(anyLong())).thenReturn(Optional.empty());

        ana = membroRepo.saveAndFlush(
            new MembroUrgenciaRenal("HCPA", "Dra. Ana Relato", "ana-leitura@example.com"));
        MembroUrgenciaRenal outroA = membroRepo.saveAndFlush(
            new MembroUrgenciaRenal("ISCMPA", "Dr. Primeiro Terceiro", "t1-leitura@example.com"));
        MembroUrgenciaRenal outroB = membroRepo.saveAndFlush(
            new MembroUrgenciaRenal("HSL", "Dr. Segundo Terceiro", "t2-leitura@example.com"));

        processoVotado = novoProcesso("61/2026", 61, "Roberto Nunes Prado", StatusProcesso.ENVIADO);
        processoDispensado = novoProcesso("62/2026", 62, "Fernanda Costa Almeida", StatusProcesso.DEFERIDO);

        // Ana ja votou no 61/2026 (processo segue em analise, esperando os outros).
        Parecer votado = new Parecer(ana);
        votado.setProcesso(processoVotado);
        votado.setDataEnvio(LocalDate.of(2026, 5, 2));
        votado.setResultado(ResultadoParecer.FAVORAVEL);
        votado.setDataResposta(LocalDate.of(2026, 5, 3));
        votado.setDataHoraVoto(LocalDateTime.of(2026, 5, 3, 10, 30));
        parecerRepo.saveAndFlush(votado);

        // Ana NUNCA votou no 62/2026 - os outros dois decidiram por maioria.
        Parecer dispensado = new Parecer(ana);
        dispensado.setProcesso(processoDispensado);
        dispensado.setDataEnvio(LocalDate.of(2026, 5, 2));
        parecerRepo.saveAndFlush(dispensado);
        for (MembroUrgenciaRenal outro : new MembroUrgenciaRenal[]{outroA, outroB}) {
            Parecer p = new Parecer(outro);
            p.setProcesso(processoDispensado);
            p.setDataEnvio(LocalDate.of(2026, 5, 2));
            p.setResultado(ResultadoParecer.FAVORAVEL);
            p.setDataResposta(LocalDate.of(2026, 5, 3));
            parecerRepo.saveAndFlush(p);
        }

        usuarioAna = new Usuario();
        usuarioAna.setUsername("ana-leitura-it");
        usuarioAna.setSenha("{noop}x");
        usuarioAna.setNome("Dra. Ana Relato");
        usuarioAna.setEmail("ana-leitura@example.com");
        usuarioAna.setPerfil(Perfil.AVALIADOR);
        usuarioAna.setMembro(ana);
        usuarioRepo.saveAndFlush(usuarioAna);

        // Avaliador SEM nenhum parecer nestes processos - a trava de posse.
        Usuario outroUsuario = new Usuario();
        outroUsuario.setUsername("outro-avaliador-it");
        outroUsuario.setSenha("{noop}x");
        outroUsuario.setNome("Dr. Primeiro Terceiro");
        outroUsuario.setEmail("t1-leitura@example.com");
        outroUsuario.setPerfil(Perfil.AVALIADOR);
        outroUsuario.setMembro(outroA);
        usuarioRepo.saveAndFlush(outroUsuario);

        // As duas mensagens do relato: o operador escreveu sobre os DOIS
        // processos que a UI nao deixava mais abrir.
        mensagemService.enviar(processoVotado, ana, "Pode conferir o exame do 61/2026?",
            RemetenteMensagemAvaliador.OPERADOR, 999L);
        mensagemService.enviar(processoDispensado, ana, "Obrigado pelo retorno no 62/2026.",
            RemetenteMensagemAvaliador.OPERADOR, 999L);
    }

    private Processo novoProcesso(String numero, int seq, String paciente, StatusProcesso status) {
        Processo p = new Processo();
        p.setNumero(numero);
        p.setAno(2026);
        p.setSequencial(seq);
        p.setPacienteNome(paciente);
        p.setPacienteRgct("99988877" + seq);
        p.setSolicitanteEquipe("HCPA");
        p.setSolicitanteEmail("equipe@hcpa.example.com");
        p.setDataSituacaoEspecial(LocalDate.of(2026, 5, 1));
        p.setStatus(status);
        return processoRepo.saveAndFlush(p);
    }

    // ------------------------------------------------------------------
    // Cenario 1: ja votei (processo ainda em analise)
    // ------------------------------------------------------------------

    @Test
    @WithMockUser(username = "ana-leitura-it", roles = "AVALIADOR")
    void processoJaVotadoAbreEmModoLeituraComOChatAcessivel() throws Exception {
        String html = mvc.perform(get("/avaliador/" + processoVotado.getId()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        // Chat presente e ja expandido (foi por ele que o avaliador veio).
        assertThat(html).contains("id=\"chatBodyAvaliador\"");
        int idxChat = html.indexOf("id=\"chatBodyAvaliador\"");
        assertThat(html.substring(Math.max(0, idxChat - 200), idxChat))
            .as("o card do chat nasce expandido em modo leitura")
            .contains("show");
        // Formulario de voto NAO renderizado.
        assertThat(html).doesNotContain("id=\"formVotoAvaliador\"");
        assertThat(html).contains("Seu parecer já foi registrado");
        // Imparcialidade: nome completo do paciente nunca aparece.
        assertThat(html).doesNotContain("Roberto Nunes Prado");
        assertThat(html).contains("R.N.P.");
    }

    @Test
    @WithMockUser(username = "ana-leitura-it", roles = "AVALIADOR")
    void avaliadorLeERespondeMensagemDeProcessoJaVotado() throws Exception {
        // Poll de leitura: devolve a mensagem do operador e marca como lida.
        mvc.perform(get("/avaliador/" + processoVotado.getId() + "/mensagens"))
            .andExpect(status().isOk());

        // Responde de verdade (processo ainda em analise -> envio liberado).
        mvc.perform(post("/avaliador/" + processoVotado.getId() + "/mensagem/ajax")
                .param("texto", "Confiro hoje ainda.")
                .with(csrf()))
            .andExpect(status().isOk());

        assertThat(mensagemRepo.findByProcessoIdAndMembroIdOrderByDataEnvioAsc(
                processoVotado.getId(), ana.getId()))
            .as("a resposta do avaliador foi de fato gravada")
            .anyMatch(m -> m.getRemetente() == RemetenteMensagemAvaliador.AVALIADOR
                && "Confiro hoje ainda.".equals(m.getTexto()));
    }

    @Test
    @WithMockUser(username = "ana-leitura-it", roles = "AVALIADOR")
    void votarDeNovoContinuaProibidoNoProcessoJaVotado() throws Exception {
        mvc.perform(post("/avaliador/" + processoVotado.getId() + "/votar")
                .param("resultado", "NAO_FAVORAVEL")
                .param("justificativa", "tentando trocar o voto")
                .with(csrf()))
            .andExpect(status().isForbidden());

        assertThat(parecerRepo.findByProcessoIdAndMembroId(processoVotado.getId(), ana.getId())
                .orElseThrow().getResultado())
            .as("o voto original nunca e sobrescrito")
            .isEqualTo(ResultadoParecer.FAVORAVEL);
    }

    // ------------------------------------------------------------------
    // Cenario 2: dispensado (processo decidido sem o meu voto)
    // ------------------------------------------------------------------

    @Test
    @WithMockUser(username = "ana-leitura-it", roles = "AVALIADOR")
    void processoDecididoSemMeuVotoAbreEmLeituraSemRevelarADecisao() throws Exception {
        String html = mvc.perform(get("/avaliador/" + processoDispensado.getId()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("id=\"chatBodyAvaliador\"");
        assertThat(html).doesNotContain("id=\"formVotoAvaliador\"");
        assertThat(html).contains("Este processo já foi concluído");

        assertThat(html)
            .as("resultado da decisao nunca e revelado a quem nao votou")
            .doesNotContain("Deferido")
            .doesNotContain("DEFERIDO");
        assertThat(html)
            .as("nome completo do paciente nunca aparece no Portal do Avaliador")
            .doesNotContain("Fernanda Costa Almeida");
        assertThat(html)
            .as("identidade/voto dos outros avaliadores nunca aparece")
            .doesNotContain("Primeiro Terceiro")
            .doesNotContain("Segundo Terceiro");
        assertThat(html).contains("F.C.A.");
    }

    @Test
    @WithMockUser(username = "ana-leitura-it", roles = "AVALIADOR")
    void votarEmProcessoJaDecididoContinuaProibido() throws Exception {
        mvc.perform(post("/avaliador/" + processoDispensado.getId() + "/votar")
                .param("resultado", "FAVORAVEL")
                .with(csrf()))
            .andExpect(status().isForbidden());

        assertThat(parecerRepo.findByProcessoIdAndMembroId(processoDispensado.getId(), ana.getId())
                .orElseThrow().getResultado())
            .as("um processo ja decidido nunca recebe voto novo")
            .isNull();
    }

    // ------------------------------------------------------------------
    // Posse: nao afrouxou nada
    // ------------------------------------------------------------------

    @Test
    @WithMockUser(username = "outro-avaliador-it", roles = "AVALIADOR")
    void avaliadorSemParecerNoProcessoContinuaBloqueado() throws Exception {
        // "outro-avaliador-it" nao tem parecer no 61/2026 - acesso negado por posse,
        // exatamente como antes desta correcao.
        mvc.perform(get("/avaliador/" + processoVotado.getId()))
            .andExpect(status().isForbidden());
        mvc.perform(get("/avaliador/" + processoVotado.getId() + "/mensagens"))
            .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // O badge: some ao abrir a conversa, e a lista indica ONDE ela esta
    // ------------------------------------------------------------------

    @Test
    @WithMockUser(username = "ana-leitura-it", roles = "AVALIADOR")
    void badgeDeMensagensZeraDepoisDeAbrirAsDuasConversasAgoraAcessiveis() throws Exception {
        assertThat(mensagemService.contarNaoLidasParaMembro(ana.getId()))
            .as("as 2 mensagens do relato comecam nao lidas")
            .isEqualTo(2L);

        mvc.perform(get("/avaliador/" + processoVotado.getId() + "/mensagens")).andExpect(status().isOk());
        mvc.perform(get("/avaliador/" + processoDispensado.getId() + "/mensagens")).andExpect(status().isOk());

        assertThat(mensagemService.contarNaoLidasParaMembro(ana.getId()))
            .as("abrir as conversas zera o badge que antes ficava preso")
            .isZero();
    }

    @Test
    @WithMockUser(username = "ana-leitura-it", roles = "AVALIADOR")
    void listaIndicaEmQuaisProcessosEstaoAsMensagensNaoLidasEOferecerLinkParaAbrir() throws Exception {
        String html = mvc.perform(get("/avaliador"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        // Link de leitura nas duas secoes que antes nao tinham nenhum.
        assertThat(html).contains("Abrir processo");
        assertThat(html).contains("/avaliador/" + processoVotado.getId() + "\"");
        assertThat(html).contains("/avaliador/" + processoDispensado.getId() + "\"");
        // Badge por linha (o detalhamento do contador global da navbar).
        assertThat(html).contains("Mensagens da equipe da Secretaria ainda não lidas");
        // Nenhuma regressao de imparcialidade na lista.
        assertThat(html).doesNotContain("Fernanda Costa Almeida");
        assertThat(html).doesNotContain("Roberto Nunes Prado");
    }
}
