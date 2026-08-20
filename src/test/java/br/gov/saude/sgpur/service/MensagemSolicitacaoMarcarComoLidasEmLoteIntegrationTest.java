package br.gov.saude.sgpur.service;

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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste de INTEGRACAO (contexto Spring real + H2, sem mock) de
 * {@link MensagemSolicitacaoService#marcarComoLidas} apos a F6 (S10,
 * docs/RELATORIO-VISTORIA-CHAT-2026-08-10.md, achado A13) trocar o antigo
 * "carregar a thread inteira em Java e filtrar" por um UPDATE em lote
 * ({@link MensagemSolicitacaoRepository#marcarComoLidasEmLote}).
 *
 * <p>Mesmo modelo/racional de
 * {@code MensagemAvaliadorMarcarComoLidasEmLoteIntegrationTest} - releem cada
 * mensagem envolvida do banco depois da chamada e confirmam EXATAMENTE quais
 * linhas mudaram, inclusive o cenario negativo de nao vazar para outra
 * solicitacao/remetente/mensagem ja lida.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-marcar-lidas-solicitacao-lote;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.solicitante.habilitado=true",
    "app.anexos.dir=./target/test-anexos-marcar-lidas-solicitacao-lote"
})
class MensagemSolicitacaoMarcarComoLidasEmLoteIntegrationTest {

    @Autowired
    private MensagemSolicitacaoService service;
    @Autowired
    private MensagemSolicitacaoRepository mensagemRepo;
    @Autowired
    private SolicitacaoOnlineRepository solicitacaoRepo;
    @Autowired
    private UsuarioRepository usuarioRepo;

    private static final Long OPERADOR_ID = 20L;

    private Long solicitacaoId;
    private Long outraSolicitacaoId;

    @BeforeEach
    @Transactional
    void preparar() {
        mensagemRepo.deleteAll();
        solicitacaoRepo.deleteAll();
        usuarioRepo.findByUsername("solicitante-marcar-lidas-lote").ifPresent(usuarioRepo::delete);
        usuarioRepo.findByUsername("solicitante-marcar-lidas-lote-2").ifPresent(usuarioRepo::delete);

        Usuario solicitante = new Usuario();
        solicitante.setUsername("solicitante-marcar-lidas-lote");
        solicitante.setSenha("{noop}x");
        solicitante.setNome("Solicitante Marcar Lidas Lote");
        solicitante.setEmail("solicitante.lote@example.com");
        solicitante.setPerfil(Perfil.SOLICITANTE);
        solicitante.setEquipeSolicitante("HCPA");
        usuarioRepo.saveAndFlush(solicitante);

        Usuario outroSolicitante = new Usuario();
        outroSolicitante.setUsername("solicitante-marcar-lidas-lote-2");
        outroSolicitante.setSenha("{noop}x");
        outroSolicitante.setNome("Outro Solicitante Lote");
        outroSolicitante.setEmail("outro.solicitante.lote@example.com");
        outroSolicitante.setPerfil(Perfil.SOLICITANTE);
        outroSolicitante.setEquipeSolicitante("ISCMPA");
        usuarioRepo.saveAndFlush(outroSolicitante);

        SolicitacaoOnline s = new SolicitacaoOnline();
        s.setUsuarioSolicitante(solicitante);
        s.setPacienteNome("Paciente Marcar Lidas Lote");
        s.setPacienteRgct("222222222");
        s.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        s.setPacienteCpf("11144477735");
        s.setPacienteSexo(Sexo.MASCULINO);
        s.setSolicitanteEquipe("HCPA");
        s.setSolicitanteEmail("solicitante.lote@example.com");
        s.setDataSituacaoEspecial(LocalDate.of(2026, 6, 1));
        s.setJustificativaClinica("Justificativa de teste do lote.");
        s.setStatus(StatusSolicitacaoOnline.ENVIADA);
        solicitacaoRepo.saveAndFlush(s);
        solicitacaoId = s.getId();

        SolicitacaoOnline outra = new SolicitacaoOnline();
        outra.setUsuarioSolicitante(outroSolicitante);
        outra.setPacienteNome("Outro Paciente Lote");
        outra.setPacienteRgct("111111111");
        outra.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        outra.setPacienteCpf("11144477735");
        outra.setPacienteSexo(Sexo.MASCULINO);
        outra.setSolicitanteEquipe("ISCMPA");
        outra.setSolicitanteEmail("outro.solicitante.lote@example.com");
        outra.setDataSituacaoEspecial(LocalDate.of(2026, 6, 1));
        outra.setJustificativaClinica("Justificativa de outra solicitacao.");
        outra.setStatus(StatusSolicitacaoOnline.ENVIADA);
        solicitacaoRepo.saveAndFlush(outra);
        outraSolicitacaoId = outra.getId();
    }

    private MensagemSolicitacao salvar(SolicitacaoOnline s, RemetenteMensagem remetente, Long remetenteId, boolean lida) {
        MensagemSolicitacao msg = new MensagemSolicitacao();
        msg.setSolicitacaoOnline(s);
        msg.setRemetente(remetente);
        msg.setRemetenteId(remetenteId);
        msg.setTexto("Mensagem de teste do lote");
        msg.setDataEnvio(LocalDateTime.now());
        msg.setLida(lida);
        return mensagemRepo.saveAndFlush(msg);
    }

    /**
     * Cenario completo: mensagens do "outro lado" nao lidas (devem virar
     * lidas), mensagens do "proprio lado" (nunca tocadas), mensagens ja lidas
     * (idempotencia) e mensagens de OUTRA solicitacao (nunca tocadas).
     */
    @Test
    void marcarComoLidasSoAtingeAsMensagensDoOutroLadoNaoLidasNaSolicitacaoCerta() {
        SolicitacaoOnline s = solicitacaoRepo.findById(solicitacaoId).orElseThrow();
        SolicitacaoOnline outra = solicitacaoRepo.findById(outraSolicitacaoId).orElseThrow();

        // Solicitacao alvo: 2 do SOLICITANTE nao lidas, 1 do SOLICITANTE ja
        // lida (idempotencia), 1 do OPERADOR (proprio lado, nao toca).
        MensagemSolicitacao naoLida1 = salvar(s, RemetenteMensagem.SOLICITANTE, 1L, false);
        MensagemSolicitacao naoLida2 = salvar(s, RemetenteMensagem.SOLICITANTE, 1L, false);
        MensagemSolicitacao jaLida = salvar(s, RemetenteMensagem.SOLICITANTE, 1L, true);
        MensagemSolicitacao doProprioOperador = salvar(s, RemetenteMensagem.OPERADOR, OPERADOR_ID, false);

        // Outra solicitacao: nunca deve ser tocada.
        MensagemSolicitacao deOutraSolicitacao = salvar(outra, RemetenteMensagem.SOLICITANTE, 2L, false);

        service.marcarComoLidas(solicitacaoId, RemetenteMensagem.SOLICITANTE, OPERADOR_ID);

        assertThat(mensagemRepo.findById(naoLida1.getId()).orElseThrow().isLida()).isTrue();
        assertThat(mensagemRepo.findById(naoLida2.getId()).orElseThrow().isLida()).isTrue();
        assertThat(mensagemRepo.findById(jaLida.getId()).orElseThrow().isLida()).isTrue();
        assertThat(mensagemRepo.findById(doProprioOperador.getId()).orElseThrow().isLida())
            .as("Mensagem do PROPRIO lado (OPERADOR marcando, mensagem de OPERADOR) nao deve ser tocada")
            .isFalse();
        assertThat(mensagemRepo.findById(deOutraSolicitacao.getId()).orElseThrow().isLida())
            .as("Mensagem de outra solicitacao nunca deve ser marcada")
            .isFalse();
    }

    /** O remetenteId da propria pessoa que esta marcando nao e afetado, mesmo que o remetente informado bata. */
    @Test
    void marcarComoLidasNuncaMarcaMensagemDoProprioRemetenteId() {
        SolicitacaoOnline s = solicitacaoRepo.findById(solicitacaoId).orElseThrow();

        MensagemSolicitacao mesmoRemetenteId = salvar(s, RemetenteMensagem.SOLICITANTE, OPERADOR_ID, false);

        service.marcarComoLidas(solicitacaoId, RemetenteMensagem.SOLICITANTE, OPERADOR_ID);

        assertThat(mensagemRepo.findById(mesmoRemetenteId.getId()).orElseThrow().isLida()).isFalse();
    }

    /** Nenhuma mensagem para marcar: nao quebra, nao afeta nada. */
    @Test
    void marcarComoLidasSemNenhumaMensagemNaoQuebra() {
        service.marcarComoLidas(solicitacaoId, RemetenteMensagem.SOLICITANTE, OPERADOR_ID);

        assertThat(mensagemRepo.findBySolicitacaoOnlineIdOrderByDataEnvioAsc(solicitacaoId)).isEmpty();
    }
}
