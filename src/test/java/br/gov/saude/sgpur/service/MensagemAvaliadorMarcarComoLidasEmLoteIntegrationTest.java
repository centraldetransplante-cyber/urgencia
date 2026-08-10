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
 * Teste de INTEGRACAO (contexto Spring real + H2, sem mock) de
 * {@link MensagemAvaliadorService#marcarComoLidas} apos a F6 (S10,
 * docs/RELATORIO-VISTORIA-CHAT-2026-08-10.md, achado A13) trocar o antigo
 * "carregar a thread inteira em Java e filtrar" por um UPDATE em lote
 * ({@link MensagemAvaliadorRepository#marcarComoLidasEmLote}).
 *
 * <p>Segue a convencao do projeto para escrita irreversivel/em lote (CLAUDE.md,
 * "Teste de atualizacao deve reler do banco e conferir campo a campo"):
 * releem cada mensagem envolvida do banco depois da chamada e confirmam
 * EXATAMENTE quais linhas mudaram - inclusive o cenario negativo mais
 * importante de um UPDATE em lote, que e garantir que ele nao vaza pra fora
 * do escopo pretendido (thread errada, remetente errado, a propria mensagem
 * de quem esta marcando).</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-marcar-lidas-avaliador-lote;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.anexos.dir=./target/test-anexos-marcar-lidas-avaliador-lote"
})
class MensagemAvaliadorMarcarComoLidasEmLoteIntegrationTest {

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

    private static final Long OPERADOR_ID = 10L;

    private Long processoId;
    private Long membroId;
    private Long outroProcessoId;
    private Long outroMembroId;

    @BeforeEach
    @Transactional
    void preparar() {
        mensagemRepo.deleteAll();
        parecerRepo.deleteAll();
        processoRepo.deleteAll();
        membroRepo.deleteAll();

        Processo p = new Processo();
        p.setNumero("61/2026");
        p.setAno(2026);
        p.setSequencial(61);
        p.setPacienteNome("Paciente Marcar Lidas Lote");
        p.setPacienteRgct("444444444");
        p.setSolicitanteEquipe("HCPA");
        p.setSolicitanteEmail("equipe@hcpa.example.com");
        p.setDataSituacaoEspecial(LocalDate.of(2026, 5, 1));
        p.setStatus(StatusProcesso.ENVIADO);
        processoRepo.saveAndFlush(p);
        processoId = p.getId();

        Processo outroProcesso = new Processo();
        outroProcesso.setNumero("62/2026");
        outroProcesso.setAno(2026);
        outroProcesso.setSequencial(62);
        outroProcesso.setPacienteNome("Outro Paciente Lote");
        outroProcesso.setPacienteRgct("333333333");
        outroProcesso.setSolicitanteEquipe("ISCMPA");
        outroProcesso.setSolicitanteEmail("equipe2@iscmpa.example.com");
        outroProcesso.setDataSituacaoEspecial(LocalDate.of(2026, 5, 1));
        outroProcesso.setStatus(StatusProcesso.ENVIADO);
        processoRepo.saveAndFlush(outroProcesso);
        outroProcessoId = outroProcesso.getId();

        MembroUrgenciaRenal membro = membroRepo.saveAndFlush(
            new MembroUrgenciaRenal("HCPA", "Ana Lote", "ana.lote@example.com"));
        membroId = membro.getId();
        MembroUrgenciaRenal outroMembro = membroRepo.saveAndFlush(
            new MembroUrgenciaRenal("ISCMPA", "Bruno Lote", "bruno.lote@example.com"));
        outroMembroId = outroMembro.getId();

        Parecer par = new Parecer(membro);
        par.setProcesso(p);
        par.setDataEnvio(LocalDate.of(2026, 5, 2));
        parecerRepo.saveAndFlush(par);

        Parecer parOutro = new Parecer(outroMembro);
        parOutro.setProcesso(outroProcesso);
        parOutro.setDataEnvio(LocalDate.of(2026, 5, 2));
        parecerRepo.saveAndFlush(parOutro);
    }

    private MensagemAvaliador salvar(Processo p, MembroUrgenciaRenal m, RemetenteMensagemAvaliador remetente,
                                      Long remetenteId, boolean lida) {
        MensagemAvaliador msg = new MensagemAvaliador();
        msg.setProcesso(p);
        msg.setMembro(m);
        msg.setRemetente(remetente);
        msg.setRemetenteId(remetenteId);
        msg.setTexto("Mensagem de teste do lote");
        msg.setDataEnvio(LocalDateTime.now());
        msg.setLida(lida);
        return mensagemRepo.saveAndFlush(msg);
    }

    /**
     * Cenario completo: mensagens do "outro lado" nao lidas (devem virar
     * lidas), mensagens do "proprio lado" (nao devem ser tocadas mesmo nao
     * lidas), mensagens ja lidas (idempotencia - continuam lidas, sem erro),
     * e mensagens de OUTRA thread/processo (nunca tocadas).
     */
    @Test
    void marcarComoLidasSoAtingeAsMensagensDoOutroLadoNaoLidasNaThreadCerta() {
        Processo p = processoRepo.findById(processoId).orElseThrow();
        MembroUrgenciaRenal membro = membroRepo.findById(membroId).orElseThrow();
        Processo outroP = processoRepo.findById(outroProcessoId).orElseThrow();
        MembroUrgenciaRenal outroMembro = membroRepo.findById(outroMembroId).orElseThrow();

        // Thread alvo (processoId, membroId): 2 do AVALIADOR nao lidas, 1 do
        // AVALIADOR ja lida (idempotencia), 1 do OPERADOR (proprio lado, nao toca).
        MensagemAvaliador naoLida1 = salvar(p, membro, RemetenteMensagemAvaliador.AVALIADOR, 1L, false);
        MensagemAvaliador naoLida2 = salvar(p, membro, RemetenteMensagemAvaliador.AVALIADOR, 1L, false);
        MensagemAvaliador jaLida = salvar(p, membro, RemetenteMensagemAvaliador.AVALIADOR, 1L, true);
        MensagemAvaliador doProprioOperador = salvar(p, membro, RemetenteMensagemAvaliador.OPERADOR, OPERADOR_ID, false);

        // Outra thread (mesmo processo, outro membro seria outro parecer - aqui
        // simulamos com outro PROCESSO e outro MEMBRO, cenario mais realista de
        // "nao deve vazar pra fora do escopo").
        MensagemAvaliador deOutraThread = salvar(outroP, outroMembro, RemetenteMensagemAvaliador.AVALIADOR, 2L, false);

        service.marcarComoLidas(processoId, membroId, RemetenteMensagemAvaliador.AVALIADOR, OPERADOR_ID);

        assertThat(mensagemRepo.findById(naoLida1.getId()).orElseThrow().isLida()).isTrue();
        assertThat(mensagemRepo.findById(naoLida2.getId()).orElseThrow().isLida()).isTrue();
        assertThat(mensagemRepo.findById(jaLida.getId()).orElseThrow().isLida()).isTrue();
        assertThat(mensagemRepo.findById(doProprioOperador.getId()).orElseThrow().isLida())
            .as("Mensagem do PROPRIO lado (OPERADOR marcando, mensagem de OPERADOR) nao deve ser tocada")
            .isFalse();
        assertThat(mensagemRepo.findById(deOutraThread.getId()).orElseThrow().isLida())
            .as("Mensagem de outro processo/membro nunca deve ser marcada")
            .isFalse();
    }

    /** O remetenteId da propria pessoa que esta marcando nao e afetado, mesmo que o remetente informado bata. */
    @Test
    void marcarComoLidasNuncaMarcaMensagemDoProprioRemetenteId() {
        Processo p = processoRepo.findById(processoId).orElseThrow();
        MembroUrgenciaRenal membro = membroRepo.findById(membroId).orElseThrow();

        // Mensagem "AVALIADOR" mas com o MESMO remetenteId de quem esta chamando
        // marcarComoLidas (cenario de guarda defensiva, equivalente ao antigo
        // filtro em Java "!m.getRemetenteId().equals(remetenteId)").
        MensagemAvaliador mesmoRemetenteId = salvar(p, membro, RemetenteMensagemAvaliador.AVALIADOR, OPERADOR_ID, false);

        service.marcarComoLidas(processoId, membroId, RemetenteMensagemAvaliador.AVALIADOR, OPERADOR_ID);

        assertThat(mensagemRepo.findById(mesmoRemetenteId.getId()).orElseThrow().isLida()).isFalse();
    }

    /** Nenhuma mensagem para marcar: nao quebra, nao afeta nada. */
    @Test
    void marcarComoLidasSemNenhumaMensagemNaoQuebra() {
        service.marcarComoLidas(processoId, membroId, RemetenteMensagemAvaliador.AVALIADOR, OPERADOR_ID);

        assertThat(mensagemRepo.findByProcessoIdAndMembroIdOrderByDataEnvioAsc(processoId, membroId)).isEmpty();
    }
}
