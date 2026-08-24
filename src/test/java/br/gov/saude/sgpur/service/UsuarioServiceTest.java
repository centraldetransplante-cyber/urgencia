package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.RascunhoSolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * O fluxo "esqueci minha senha" (token de uso unico por link, desde
 * 2026-08-24) mudou de dono: mora inteiro em {@code PasswordResetService}
 * (ver {@code PasswordResetServiceTest}) - este arquivo cobre so o CRUD de
 * usuario (criar/atualizar/excluir/ativar-desativar, auto-lockout do ultimo
 * ADMIN, vinculo de membro/equipe por perfil).
 */
@ExtendWith(MockitoExtension.class)
class UsuarioServiceTest {

    @Mock private UsuarioRepository repo;
    @Mock private PasswordEncoder encoder;
    @Mock private MembroUrgenciaRenalRepository membroRepo;
    @Mock private SolicitacaoOnlineRepository solicitacaoRepo;
    @Mock private RascunhoSolicitacaoOnlineRepository rascunhoRepo;

    private UsuarioService service;

    @BeforeEach
    void setUp() {
        service = new UsuarioService(repo, encoder, membroRepo, solicitacaoRepo, rascunhoRepo);
    }

    // ---- Auto-lockout: exclusao/desativacao do ultimo ADMIN ativo ou da propria conta ----

    private Usuario admin(Long id, String username) {
        Usuario u = new Usuario();
        u.setId(id);
        u.setUsername(username);
        u.setNome("Admin " + username);
        u.setPerfil(Perfil.ADMIN);
        u.setAtivo(true);
        return u;
    }

    private Usuario operador(Long id, String username) {
        Usuario u = new Usuario();
        u.setId(id);
        u.setUsername(username);
        u.setNome("Operador " + username);
        u.setPerfil(Perfil.OPERADOR);
        u.setAtivo(true);
        return u;
    }

    @Test
    void unicoAdminAtivoNaoConsegueSeAutoExcluir() {
        Usuario admin = admin(1L, "admin");
        when(repo.findById(1L)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> service.excluir(1L, "admin"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("propria conta");

        verify(repo, never()).delete(any());
    }

    @Test
    void unicoAdminAtivoNaoConsegueSeAutoDesativar() {
        Usuario admin = admin(1L, "admin");
        when(repo.findById(1L)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> service.alternarAtivo(1L, "admin"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("propria conta");

        verify(repo, never()).save(any());
        assertThat(admin.isAtivo()).isTrue();
    }

    @Test
    void ultimoAdminAtivoNaoPodeSerExcluidoPorOutroUsuario() {
        Usuario admin = admin(1L, "admin");
        when(repo.findById(1L)).thenReturn(Optional.of(admin));
        when(repo.countByPerfilAndAtivoTrue(Perfil.ADMIN)).thenReturn(1L);

        assertThatThrownBy(() -> service.excluir(1L, "outro-operador"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unico administrador ativo");

        verify(repo, never()).delete(any());
    }

    @Test
    void ultimoAdminAtivoNaoPodeSerDesativadoPorOutroUsuario() {
        Usuario admin = admin(1L, "admin");
        when(repo.findById(1L)).thenReturn(Optional.of(admin));
        when(repo.countByPerfilAndAtivoTrue(Perfil.ADMIN)).thenReturn(1L);

        assertThatThrownBy(() -> service.alternarAtivo(1L, "outro-operador"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unico administrador ativo");

        verify(repo, never()).save(any());
        assertThat(admin.isAtivo()).isTrue();
    }

    @Test
    void comDoisAdminsAtivosExcluirUmDelesFunciona() {
        Usuario admin1 = admin(1L, "admin1");
        when(repo.findById(1L)).thenReturn(Optional.of(admin1));
        when(repo.countByPerfilAndAtivoTrue(Perfil.ADMIN)).thenReturn(2L);

        service.excluir(1L, "outro-usuario");

        verify(repo).delete(admin1);
    }

    @Test
    void comDoisAdminsAtivosDesativarUmDelesFunciona() {
        Usuario admin1 = admin(1L, "admin1");
        when(repo.findById(1L)).thenReturn(Optional.of(admin1));
        when(repo.countByPerfilAndAtivoTrue(Perfil.ADMIN)).thenReturn(2L);

        service.alternarAtivo(1L, "outro-usuario");

        verify(repo).save(admin1);
        assertThat(admin1.isAtivo()).isFalse();
    }

    @Test
    void excluirUsuarioNaoAdminSempreFuncionaLivremente() {
        Usuario op = operador(2L, "operador1");
        when(repo.findById(2L)).thenReturn(Optional.of(op));

        service.excluir(2L, "admin");

        verify(repo).delete(op);
    }

    @Test
    void desativarUsuarioNaoAdminSempreFuncionaLivremente() {
        Usuario op = operador(2L, "operador1");
        when(repo.findById(2L)).thenReturn(Optional.of(op));

        service.alternarAtivo(2L, "admin");

        verify(repo).save(op);
        assertThat(op.isAtivo()).isFalse();
    }

    // ---- Perfil SOLICITANTE: equipe obrigatoria + limpeza de vinculo ao trocar de perfil ----

    private Usuario formSolicitante(String equipe) {
        Usuario u = new Usuario();
        u.setUsername("solicitante1");
        u.setNome("Solicitante Um");
        u.setPerfil(br.gov.saude.sgpur.domain.Perfil.SOLICITANTE);
        u.setEquipeSolicitante(equipe);
        return u;
    }

    @Test
    void criarComPerfilSolicitanteSemEquipeLancaExcecao() {
        when(repo.existsByUsername("solicitante1")).thenReturn(false);

        assertThatThrownBy(() -> service.criar(formSolicitante(null), "Senha123!"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("equipe/hospital solicitante");
    }

    @Test
    void criarComPerfilSolicitanteComEquipeEmBrancoLancaExcecao() {
        when(repo.existsByUsername("solicitante1")).thenReturn(false);

        assertThatThrownBy(() -> service.criar(formSolicitante("   "), "Senha123!"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("equipe/hospital solicitante");
    }

    @Test
    void criarComPerfilSolicitanteComEquipePreenchidaSalvaComSucesso() {
        when(repo.existsByUsername("solicitante1")).thenReturn(false);
        when(encoder.encode(any())).thenReturn("hash-fake");
        when(repo.save(any(Usuario.class))).thenAnswer(inv -> inv.getArgument(0));

        Usuario salvo = service.criar(formSolicitante("  HCPA  "), "Senha123!");

        // Equipe e sempre trim()ada antes de salvar.
        assertThat(salvo.getEquipeSolicitante()).isEqualTo("HCPA");
    }

    @Test
    void trocaDeAvaliadorParaSolicitanteLimpaOVinculoDeMembroAntigo() {
        Usuario existente = new Usuario();
        existente.setId(5L);
        existente.setUsername("usuario5");
        existente.setPerfil(br.gov.saude.sgpur.domain.Perfil.AVALIADOR);
        br.gov.saude.sgpur.domain.MembroUrgenciaRenal membro =
            new br.gov.saude.sgpur.domain.MembroUrgenciaRenal("HCPA", "Dr. Fulano", "fulano@hcpa.edu.br");
        membro.setId(1L);
        existente.setMembro(membro);
        when(repo.findById(5L)).thenReturn(Optional.of(existente));
        when(repo.save(any(Usuario.class))).thenAnswer(inv -> inv.getArgument(0));

        Usuario form = formSolicitante("HNSC");
        form.setUsername("usuario5"); // mesmo username - nao dispara checagem de duplicidade

        Usuario atualizado = service.atualizar(5L, form, null, null, "HNSC");

        assertThat(atualizado.getMembro()).isNull();
        assertThat(atualizado.getEquipeSolicitante()).isEqualTo("HNSC");
    }

    @Test
    void trocaDeSolicitanteParaOutroPerfilLimpaAEquipeSolicitante() {
        Usuario existente = new Usuario();
        existente.setId(6L);
        existente.setUsername("usuario6");
        existente.setPerfil(br.gov.saude.sgpur.domain.Perfil.SOLICITANTE);
        existente.setEquipeSolicitante("HCPA");
        when(repo.findById(6L)).thenReturn(Optional.of(existente));
        when(repo.save(any(Usuario.class))).thenAnswer(inv -> inv.getArgument(0));

        Usuario form = new Usuario();
        form.setUsername("usuario6");
        form.setNome("Operador Seis");
        form.setPerfil(br.gov.saude.sgpur.domain.Perfil.OPERADOR);

        Usuario atualizado = service.atualizar(6L, form, null, null, null);

        assertThat(atualizado.getEquipeSolicitante()).isNull();
        assertThat(atualizado.getMembro()).isNull();
    }
}
