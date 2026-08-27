package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Sexo;
import br.gov.saude.sgpur.domain.SolicitacaoOnline;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Teste de INTEGRACAO (contexto Spring real + H2, sem mock de
 * {@code SolicitacaoOnlineService} - ver CLAUDE.md "Rota que grava algo
 * irreversivel exige um teste do caminho de falha SEM mock do servico")
 * da defesa em profundidade contra duplo-submit em
 * {@code SolicitacaoOnlineService.criar}, adicionada em 2026-08-27.
 *
 * <p>Causa raiz real do bug (ver
 * docs/RELATORIO-BUG-DUPLICACAO-E-COBERTURA-BADGE-PREEMPTIVO-2026-08-27.md):
 * {@code solicitante/nova.html} tinha {@code data-lock-submit} no form mas
 * nunca incluia o script que le esse atributo ({@code lockSubmitScript}) -
 * corrigido no template, mas essa correcao e client-side e invisivel para
 * qualquer teste de backend. Esta guarda cobre o caso de o clique duplo (ou
 * um F5 num POST, ou uma aba antiga reaberta) passar mesmo assim.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-solicitacao-duplicidade;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.anexos.dir=./target/test-anexos-solicitacao-duplicidade"
})
class SolicitacaoOnlineDuplicidadeIntegrationTest {

    @Autowired
    private SolicitacaoOnlineService service;
    @Autowired
    private SolicitacaoOnlineRepository repo;
    @Autowired
    private UsuarioRepository usuarioRepo;

    /** Evita qualquer tentativa de SMTP real na notificacao aos operadores. */
    @MockitoBean
    private EmailSenderService emailSenderService;

    private Usuario solicitante;

    @BeforeEach
    void preparar() {
        repo.deleteAll();
        usuarioRepo.findByUsername("solicitante-duplicidade").ifPresent(usuarioRepo::delete);
        Usuario u = new Usuario();
        u.setUsername("solicitante-duplicidade");
        u.setNome("Solicitante Duplicidade");
        u.setEmail("solicitante.duplicidade@example.com");
        u.setSenha("{noop}irrelevante");
        u.setPerfil(Perfil.SOLICITANTE);
        u.setAtivo(true);
        u.setEquipeSolicitante("HCPA - Nefrologia");
        solicitante = usuarioRepo.saveAndFlush(u);
    }

    private SolicitacaoOnline pedido(String cpf) {
        SolicitacaoOnline s = new SolicitacaoOnline();
        s.setPacienteNome("Paciente Duplicado");
        s.setPacienteRgct("123456789-12345");
        s.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        s.setPacienteCpf(cpf);
        s.setPacienteSexo(Sexo.MASCULINO);
        s.setDataSituacaoEspecial(LocalDate.now());
        s.setJustificativaClinica("Quadro grave, necessita avaliacao urgente.");
        return s;
    }

    /**
     * Caminho de falha real: duas chamadas seguidas de criar(), MESMO
     * usuario + MESMO CPF de paciente, simulando o duplo-clique/duplo-POST.
     * Só a primeira deve persistir.
     */
    @Test
    void duploSubmitComMesmoUsuarioEMesmoCpfSoGravaUmaVez() {
        service.criar(pedido("11144477735"), solicitante, null);

        assertThatThrownBy(() -> service.criar(pedido("11144477735"), solicitante, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Ja recebemos uma solicitacao");

        assertThat(repo.count()).isEqualTo(1);
    }

    /**
     * Reenvio LEGITIMO (paciente diferente, mesmo usuario) nao pode ficar
     * bloqueado pela guarda - so o par usuario+CPF conta.
     */
    @Test
    void mesmoUsuarioComPacienteDiferenteContinuaFuncionandoNormalmente() {
        service.criar(pedido("11144477735"), solicitante, null);
        service.criar(pedido("52998224725"), solicitante, null);

        assertThat(repo.count()).isEqualTo(2);
    }

    /**
     * Reenvio LEGITIMO depois da janela de protecao (15s) nao pode virar um
     * bloqueio permanente por usuario+paciente - simulado recuando a
     * dataEnvio da primeira solicitacao manualmente (mesma tecnica de
     * "simular o passado" que os demais testes deste servico ja usam para
     * dataEnvio, sem precisar de Thread.sleep/tempo real de teste).
     */
    @Test
    void reenvioDepoisDaJanelaDeProtecaoContinuaFuncionandoNormalmente() {
        SolicitacaoOnline primeira = service.criar(pedido("11144477735"), solicitante, null);
        primeira.setDataEnvio(primeira.getDataEnvio().minusSeconds(20));
        repo.saveAndFlush(primeira);

        service.criar(pedido("11144477735"), solicitante, null);

        assertThat(repo.count()).isEqualTo(2);
    }
}
