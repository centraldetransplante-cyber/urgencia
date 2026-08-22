package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.MensagemSolicitacao;
import br.gov.saude.sgpur.domain.MensagemSolicitacao.RemetenteMensagem;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.SolicitacaoOnline;
import br.gov.saude.sgpur.domain.StatusSolicitacaoOnline;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.domain.Sexo;
import br.gov.saude.sgpur.repository.MensagemSolicitacaoRepository;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
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
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de INTEGRACAO (contexto Spring real + H2, servicos/repositorios
 * REAIS, sem nenhum {@code @MockitoBean} de servico) dos dois metodos de
 * {@link SolicitacaoOnlineTriagemController} que tem try/catch de erro de
 * NEGOCIO esperado em cima de uma escrita ({@code apagarMensagem} e
 * {@code devolver}) - o mesmo padrao de risco documentado no javadoc da
 * classe (familia de bug corrigida em {@code AvaliadorController
 * .registrarVoto} / {@code ProcessoDecisaoController.finalizar}: com
 * {@code @Transactional} de nivel de classe, uma
 * {@code IllegalArgumentException}/{@code IllegalStateException} de negocio
 * tratada pelo {@code catch} do controller ainda marcava a transacao
 * COMPARTILHADA como rollback-only, e o commit no fim do metodo estourava
 * {@code UnexpectedRollbackException} - convertido pelo
 * {@code GlobalExceptionHandler} num HTTP 500 ("Operacao nao concluida") em
 * vez do flash de erro amigavel que o {@code catch} pretendia mostrar).
 *
 * <p><b>Por que nao da pra cobrir isso com {@code @WebMvcTest}:</b> os testes
 * daquela classe usam {@code @MockitoBean MensagemSolicitacaoService}/
 * {@code SolicitacaoOnlineService} - a excecao devolvida pelo mock nunca passa
 * pelo {@code TransactionInterceptor} de verdade, entao
 * {@code UnexpectedRollbackException} e literalmente inexprimivel ali (o
 * proprio motivo do pitfall documentado no CLAUDE.md do projeto). So um
 * contexto real, com o {@code TransactionManager} de verdade por tras dos
 * servicos reais, consegue reproduzir (ou, apos a correcao, provar que NAO
 * acontece mais) o rollback-only cruzando a fronteira controller/servico.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sgpur-triagem-sem-tx;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.anexos.dir=./target/test-anexos-triagem-sem-tx"
})
class SolicitacaoOnlineTriagemSemTransacaoIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UsuarioRepository usuarioRepo;
    @Autowired
    private SolicitacaoOnlineRepository solicitacaoRepo;
    @Autowired
    private MensagemSolicitacaoRepository mensagemRepo;

    private Long solicitacaoEnviadaId;
    private Long solicitacaoConvertidaId;
    private Long mensagemDeOutroOperadorId;

    @BeforeEach
    @Transactional
    void preparar() {
        mensagemRepo.deleteAll();
        solicitacaoRepo.deleteAll();
        usuarioRepo.findByUsername("solicitante-triagem-it").ifPresent(usuarioRepo::delete);
        usuarioRepo.findByUsername("operador-triagem-it").ifPresent(usuarioRepo::delete);

        Usuario solicitante = new Usuario();
        solicitante.setUsername("solicitante-triagem-it");
        solicitante.setSenha("{noop}x");
        solicitante.setNome("Solicitante Teste");
        solicitante.setEmail("solicitante.triagem@example.com");
        solicitante.setPerfil(Perfil.SOLICITANTE);
        solicitante.setEquipeSolicitante("HCPA");
        usuarioRepo.saveAndFlush(solicitante);

        Usuario operador = new Usuario();
        operador.setUsername("operador-triagem-it");
        operador.setSenha("{noop}x");
        operador.setNome("Operador Teste");
        operador.setEmail("operador.triagem@example.com");
        operador.setPerfil(Perfil.OPERADOR);
        usuarioRepo.saveAndFlush(operador);

        // Solicitacao ainda ENVIADA (aguardando triagem) - usada no fluxo de apagarMensagem.
        SolicitacaoOnline enviada = new SolicitacaoOnline();
        enviada.setUsuarioSolicitante(solicitante);
        enviada.setPacienteNome("Fulano de Tal");
        enviada.setPacienteRgct("111111111");
        enviada.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        enviada.setPacienteCpf("11144477735");
        enviada.setPacienteSexo(Sexo.MASCULINO);
        enviada.setSolicitanteEquipe("HCPA");
        enviada.setSolicitanteEmail(solicitante.getEmail());
        enviada.setDataSituacaoEspecial(LocalDate.now());
        enviada.setJustificativaClinica("Quadro grave.");
        enviada.setStatus(StatusSolicitacaoOnline.ENVIADA);
        enviada.setDataEnvio(LocalDateTime.now());
        solicitacaoRepo.saveAndFlush(enviada);
        solicitacaoEnviadaId = enviada.getId();

        // Mensagem de OUTRO operador (remetenteId != o do operador logado no
        // teste) - forca IllegalArgumentException em mensagemService.apagar
        // ("Voce nao pode apagar esta mensagem.").
        MensagemSolicitacao msgDeOutro = new MensagemSolicitacao();
        msgDeOutro.setSolicitacaoOnline(enviada);
        msgDeOutro.setRemetente(RemetenteMensagem.OPERADOR);
        msgDeOutro.setRemetenteId(999999L);
        msgDeOutro.setTexto("Mensagem de outro operador");
        msgDeOutro.setDataEnvio(LocalDateTime.now());
        msgDeOutro.setLida(false);
        mensagemRepo.saveAndFlush(msgDeOutro);
        mensagemDeOutroOperadorId = msgDeOutro.getId();

        // Mensagem do SOLICITANTE, usada para conferir que o polling AJAX do
        // operador rotula o remetente com o NOME REAL dele (Usuario.nome),
        // nao mais o literal generico "Solicitante" (corrigido em 2026-08-07).
        MensagemSolicitacao msgDoSolicitante = new MensagemSolicitacao();
        msgDoSolicitante.setSolicitacaoOnline(enviada);
        msgDoSolicitante.setRemetente(RemetenteMensagem.SOLICITANTE);
        msgDoSolicitante.setRemetenteId(solicitante.getId());
        msgDoSolicitante.setTexto("Mensagem do solicitante de verdade");
        msgDoSolicitante.setDataEnvio(LocalDateTime.now());
        msgDoSolicitante.setLida(false);
        mensagemRepo.saveAndFlush(msgDoSolicitante);

        // Solicitacao JA CONVERTIDA - usada no fluxo de devolver (forca
        // IllegalStateException "Esta solicitacao ja foi triada.").
        SolicitacaoOnline convertida = new SolicitacaoOnline();
        convertida.setUsuarioSolicitante(solicitante);
        convertida.setPacienteNome("Ciclano da Silva");
        convertida.setPacienteRgct("222222222");
        convertida.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        convertida.setPacienteCpf("11144477735");
        convertida.setPacienteSexo(Sexo.MASCULINO);
        convertida.setSolicitanteEquipe("HCPA");
        convertida.setSolicitanteEmail(solicitante.getEmail());
        convertida.setDataSituacaoEspecial(LocalDate.now());
        convertida.setJustificativaClinica("Quadro grave.");
        convertida.setStatus(StatusSolicitacaoOnline.CONVERTIDA);
        convertida.setDataEnvio(LocalDateTime.now());
        solicitacaoRepo.saveAndFlush(convertida);
        solicitacaoConvertidaId = convertida.getId();
    }

    /**
     * POST .../mensagem/{id}/apagar sobre uma mensagem que NAO pertence ao
     * operador logado: {@code mensagemService.apagar} lanca
     * {@code IllegalArgumentException} (erro de negocio esperado, nao bug).
     * Antes da correcao (transacao de classe), isto resultaria em
     * {@code UnexpectedRollbackException} -> HTTP 500 ("error" view). Com
     * {@code Propagation.NOT_SUPPORTED} no metodo, o controller deve devolver
     * o redirect normal com o flash de erro tratado pelo catch - e a
     * mensagem, que nao era do operador, continua intacta (nao apagada).
     */
    @Test
    @WithMockUser(username = "operador-triagem-it", roles = "OPERADOR")
    void apagarMensagemDeOutroOperadorDevolveFlashDeErroSemHttp500EMantemMensagemIntacta() throws Exception {
        mvc.perform(post("/processos/solicitacoes-online/" + solicitacaoEnviadaId
                        + "/mensagem/" + mensagemDeOutroOperadorId + "/apagar")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/processos/solicitacoes-online/" + solicitacaoEnviadaId))
                .andExpect(flash().attributeExists("erro"));

        MensagemSolicitacao aindaLa = mensagemRepo.findById(mensagemDeOutroOperadorId).orElseThrow();
        assertThat(aindaLa.isDeletada()).isFalse();
        assertThat(aindaLa.getTexto()).isEqualTo("Mensagem de outro operador");
    }

    /**
     * POST .../mensagem/{id}/apagar/ajax (variante AJAX) com a mesma
     * mensagem de outro operador: deve devolver 400 JSON tratado, nunca 500.
     */
    @Test
    @WithMockUser(username = "operador-triagem-it", roles = "OPERADOR")
    void apagarMensagemAjaxDeOutroOperadorDevolve400SemHttp500() throws Exception {
        mvc.perform(post("/processos/solicitacoes-online/" + solicitacaoEnviadaId
                        + "/mensagem/" + mensagemDeOutroOperadorId + "/apagar/ajax")
                        .with(csrf()))
                .andExpect(status().isBadRequest());

        MensagemSolicitacao aindaLa = mensagemRepo.findById(mensagemDeOutroOperadorId).orElseThrow();
        assertThat(aindaLa.isDeletada()).isFalse();
    }

    /**
     * POST .../devolver numa solicitacao JA CONVERTIDA: {@code
     * SolicitacaoOnlineService.devolver} lanca {@code IllegalStateException}
     * ("Esta solicitacao ja foi triada."). Antes da correcao, resultaria em
     * {@code UnexpectedRollbackException} -> HTTP 500. Com {@code
     * Propagation.NOT_SUPPORTED}, o controller devolve o redirect para o
     * detalhe com o flash de erro tratado, e o status da solicitacao
     * permanece CONVERTIDA (nao foi indevidamente alterado para DEVOLVIDA).
     */
    @Test
    @WithMockUser(username = "operador-triagem-it", roles = "OPERADOR")
    void devolverSolicitacaoJaTriadaDevolveFlashDeErroSemHttp500EMantemStatus() throws Exception {
        mvc.perform(post("/processos/solicitacoes-online/" + solicitacaoConvertidaId + "/devolver")
                        .param("observacoes", "tentativa tardia")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/processos/solicitacoes-online/" + solicitacaoConvertidaId))
                .andExpect(flash().attribute("erro", "Esta solicitacao ja foi triada."));

        SolicitacaoOnline aindaLa = solicitacaoRepo.findById(solicitacaoConvertidaId).orElseThrow();
        assertThat(aindaLa.getStatus()).isEqualTo(StatusSolicitacaoOnline.CONVERTIDA);
    }

    /**
     * Caminho feliz de devolver, no mesmo contexto real (sem mock de
     * servico): confirma que a transacao propria do metodo
     * (Propagation.NOT_SUPPORTED + a transacao interna do servico) ainda
     * persiste a escrita normalmente quando NAO ha erro.
     */
    @Test
    @WithMockUser(username = "operador-triagem-it", roles = "OPERADOR")
    void devolverComSucessoPersisteNovoStatus() throws Exception {
        mvc.perform(post("/processos/solicitacoes-online/" + solicitacaoEnviadaId + "/devolver")
                        .param("observacoes", "Falta documento clinico.")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/processos/solicitacoes-online"))
                .andExpect(flash().attributeExists("msg"));

        SolicitacaoOnline devolvida = solicitacaoRepo.findById(solicitacaoEnviadaId).orElseThrow();
        assertThat(devolvida.getStatus()).isEqualTo(StatusSolicitacaoOnline.DEVOLVIDA);
        assertThat(devolvida.getObservacoesTriagem()).isEqualTo("Falta documento clinico.");
    }

    /**
     * Corrigido em 2026-08-07: o polling AJAX do chat (lado do operador, na
     * tela de triagem) rotulava toda mensagem do solicitante com o literal
     * generico "Solicitante", em vez do nome real de quem enviou. Confirma
     * que o JSON devolvido por {@code GET .../mensagens} traz
     * {@code Usuario.nome} de verdade ("Solicitante Teste") e nao o literal
     * antigo.
     */
    @Test
    @WithMockUser(username = "operador-triagem-it", roles = "OPERADOR")
    void mensagensAjaxRotulaMensagemDoSolicitanteComONomeRealENaoComLiteralGenerico() throws Exception {
        mvc.perform(get("/processos/solicitacoes-online/" + solicitacaoEnviadaId + "/mensagens"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Solicitante Teste")))
                .andExpect(content().string(containsString("Mensagem do solicitante de verdade")));
    }
}
