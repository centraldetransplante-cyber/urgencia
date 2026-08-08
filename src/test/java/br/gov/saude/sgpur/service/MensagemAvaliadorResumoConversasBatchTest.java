package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.MensagemAvaliador;
import br.gov.saude.sgpur.domain.MensagemAvaliador.RemetenteMensagemAvaliador;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.MensagemAvaliadorRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
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
 * Teste de INTEGRACAO (contexto Spring real + H2) de
 * {@link MensagemAvaliadorService#resumoConversasDoProcesso}, a versao em
 * lote que substituiu o loop com ate 2 queries POR PARECER em
 * {@code ProcessoDetalheController.detalhe} (ate 6 SELECTs extras por
 * render, sempre 3 pareceres - {@code ProcessoService.AVALIADORES_POR_PROCESSO}).
 *
 * <p>Confirma que o resultado da versao em lote e IDENTICO ao que os dois
 * metodos antigos ({@link MensagemAvaliadorService#contarNaoLidasPorThreadParaOperador}
 * e {@link MensagemAvaliadorService#existeConversa}), chamados um a um por
 * membro, devolveriam - cobrindo os cenarios: zero conversas, varios membros
 * com contagens diferentes, membro com mensagens mas todas ja lidas (conversa
 * existe, mas zero nao lidas) e um membro sem NENHUMA mensagem (nao deve
 * aparecer em nenhum dos dois mapas).</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-resumo-conversas-batch;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.anexos.dir=./target/test-anexos-resumo-conversas-batch"
})
class MensagemAvaliadorResumoConversasBatchTest {

    @Autowired
    private MensagemAvaliadorService service;
    @Autowired
    private MensagemAvaliadorRepository mensagemRepo;
    @Autowired
    private ProcessoRepository processoRepo;
    @Autowired
    private ParecerRepository parecerRepo;
    @Autowired
    private MembroUrgenciaRenalRepository membroRepo;

    private Long processoId;
    private Long membroComNaoLidasId;
    private Long membroSoLidasId;
    private Long membroSemMensagemId;

    @BeforeEach
    @Transactional
    void preparar() {
        mensagemRepo.deleteAll();
        parecerRepo.deleteAll();
        processoRepo.deleteAll();
        membroRepo.deleteAll();

        Processo p = new Processo();
        p.setNumero("50/2026");
        p.setAno(2026);
        p.setSequencial(50);
        p.setPacienteNome("Paciente Resumo Conversas");
        p.setPacienteRgct("555555555");
        p.setSolicitanteEquipe("HCPA");
        p.setSolicitanteEmail("equipe@hcpa.example.com");
        p.setDataSituacaoEspecial(LocalDate.of(2026, 5, 1));
        p.setStatus(StatusProcesso.ENVIADO);
        processoRepo.saveAndFlush(p);
        processoId = p.getId();

        MembroUrgenciaRenal membroComNaoLidas = membroRepo.saveAndFlush(
            new MembroUrgenciaRenal("HCPA", "Com Nao Lidas", "com.naolidas@example.com"));
        membroComNaoLidasId = membroComNaoLidas.getId();
        MembroUrgenciaRenal membroSoLidas = membroRepo.saveAndFlush(
            new MembroUrgenciaRenal("ISCMPA", "So Lidas", "so.lidas@example.com"));
        membroSoLidasId = membroSoLidas.getId();
        MembroUrgenciaRenal membroSemMensagem = membroRepo.saveAndFlush(
            new MembroUrgenciaRenal("HNSC", "Sem Mensagem", "sem.mensagem@example.com"));
        membroSemMensagemId = membroSemMensagem.getId();

        for (MembroUrgenciaRenal m : new MembroUrgenciaRenal[]{membroComNaoLidas, membroSoLidas, membroSemMensagem}) {
            Parecer par = new Parecer(m);
            par.setProcesso(p);
            par.setDataEnvio(LocalDate.of(2026, 5, 2));
            parecerRepo.saveAndFlush(par);
        }

        // membroComNaoLidas: 2 mensagens do AVALIADOR nao lidas + 1 do OPERADOR (irrelevante pra contagem).
        salvarMensagem(p, membroComNaoLidas, RemetenteMensagemAvaliador.AVALIADOR, false);
        salvarMensagem(p, membroComNaoLidas, RemetenteMensagemAvaliador.AVALIADOR, false);
        salvarMensagem(p, membroComNaoLidas, RemetenteMensagemAvaliador.OPERADOR, true);

        // membroSoLidas: existe conversa, mas a unica mensagem do avaliador ja foi lida.
        salvarMensagem(p, membroSoLidas, RemetenteMensagemAvaliador.AVALIADOR, true);

        // membroSemMensagem: nenhuma linha na tabela - nao deve aparecer em nenhum mapa.
    }

    private void salvarMensagem(Processo p, MembroUrgenciaRenal m, RemetenteMensagemAvaliador remetente, boolean lida) {
        MensagemAvaliador msg = new MensagemAvaliador();
        msg.setProcesso(p);
        msg.setMembro(m);
        msg.setRemetente(remetente);
        msg.setRemetenteId(1L);
        msg.setTexto("Mensagem de teste");
        msg.setDataEnvio(LocalDateTime.now());
        msg.setLida(lida);
        mensagemRepo.saveAndFlush(msg);
    }

    @Test
    void resumoEmLoteConfereComOCalculoPorParecerParaCadaMembro() {
        var resumo = service.resumoConversasDoProcesso(processoId);

        // Membro com 2 nao lidas do avaliador.
        assertThat(resumo.naoLidasPorMembro().getOrDefault(membroComNaoLidasId, 0L)).isEqualTo(2L);
        assertThat(resumo.existeConversaPorMembro().getOrDefault(membroComNaoLidasId, false)).isTrue();
        assertThat(service.contarNaoLidasPorThreadParaOperador(processoId, membroComNaoLidasId)).isEqualTo(2L);
        assertThat(service.existeConversa(processoId, membroComNaoLidasId)).isTrue();

        // Membro so com mensagem ja lida: existe conversa, mas zero nao lidas.
        assertThat(resumo.naoLidasPorMembro().getOrDefault(membroSoLidasId, 0L)).isEqualTo(0L);
        assertThat(resumo.existeConversaPorMembro().getOrDefault(membroSoLidasId, false)).isTrue();
        assertThat(service.contarNaoLidasPorThreadParaOperador(processoId, membroSoLidasId)).isEqualTo(0L);
        assertThat(service.existeConversa(processoId, membroSoLidasId)).isTrue();

        // Membro sem NENHUMA mensagem: ausente dos dois mapas (o chamador trata como 0/false).
        assertThat(resumo.naoLidasPorMembro()).doesNotContainKey(membroSemMensagemId);
        assertThat(resumo.existeConversaPorMembro()).doesNotContainKey(membroSemMensagemId);
        assertThat(service.contarNaoLidasPorThreadParaOperador(processoId, membroSemMensagemId)).isEqualTo(0L);
        assertThat(service.existeConversa(processoId, membroSemMensagemId)).isFalse();
    }

    /** Processo sem nenhuma mensagem em nenhum avaliador: os dois mapas vem vazios, sem erro. */
    @Test
    void processoSemNenhumaMensagemDevolveMapasVazios() {
        mensagemRepo.deleteAll();

        var resumo = service.resumoConversasDoProcesso(processoId);

        assertThat(resumo.naoLidasPorMembro()).isEmpty();
        assertThat(resumo.existeConversaPorMembro()).isEmpty();
    }
}
