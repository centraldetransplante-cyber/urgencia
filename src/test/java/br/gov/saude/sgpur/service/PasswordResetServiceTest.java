package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.PasswordResetToken;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.PasswordResetTokenRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Cobre o fluxo novo de "esqueci minha senha" por token de uso unico
 * (2026-08-24, ver javadoc de {@code PasswordResetService}): geracao do
 * token NUNCA altera a senha ativa nem exige mock de e-mail dentro da mesma
 * chamada (achado E - envio e passo separado, chamado depois), e a
 * confirmacao so aceita token valido, nao expirado e nao usado.
 */
@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock private UsuarioRepository usuarioRepo;
    @Mock private PasswordResetTokenRepository tokenRepo;
    @Mock private PasswordEncoder encoder;
    @Mock private EmailSenderService emailSenderService;

    private PasswordResetAttemptService passwordResetAttemptService;
    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        passwordResetAttemptService = new PasswordResetAttemptService();
        service = new PasswordResetService(usuarioRepo, tokenRepo, encoder, emailSenderService,
            passwordResetAttemptService, "http://localhost:3000");
    }

    private Usuario usuarioComEmail() {
        Usuario u = new Usuario();
        u.setId(1L);
        u.setUsername("operador1");
        u.setNome("Operador Um");
        u.setEmail("operador1@example.com");
        u.setSenha("hash-antigo");
        u.setVersao(0L);
        return u;
    }

    // ---- gerarTokenResetSenha: NUNCA altera a senha, so persiste o token ----

    @Test
    void gerarTokenComUsuarioValidoPersisteTokenSemAlterarSenha() {
        Usuario u = usuarioComEmail();
        when(usuarioRepo.findByUsername("operador1")).thenReturn(Optional.of(u));

        Optional<PasswordResetService.TokenGerado> resultado = service.gerarTokenResetSenha("operador1");

        assertThat(resultado).isPresent();
        assertThat(resultado.get().emailDestino()).isEqualTo("operador1@example.com");
        assertThat(resultado.get().nomeUsuario()).isEqualTo("Operador Um");
        assertThat(resultado.get().token()).isNotBlank();

        // A senha ATIVA nunca e tocada neste passo - so o token e persistido.
        assertThat(u.getSenha()).isEqualTo("hash-antigo");
        verify(usuarioRepo, never()).save(any());
        verifyNoInteractions(emailSenderService);

        ArgumentCaptor<PasswordResetToken> captor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepo).save(captor.capture());
        PasswordResetToken salvo = captor.getValue();
        assertThat(salvo.getUsuario()).isSameAs(u);
        assertThat(salvo.isUsado()).isFalse();
        assertThat(salvo.getToken()).isEqualTo(resultado.get().token());
        assertThat(salvo.getDataExpiracao()).isAfter(Instant.now());

        // Qualquer token pendente anterior do mesmo usuario e invalidado antes.
        verify(tokenRepo).deleteByUsuarioId(1L);
    }

    @Test
    void gerarTokenSemUsuarioNaoPersisteNadaNemLancaExcecao() {
        when(usuarioRepo.findByUsername("inexistente")).thenReturn(Optional.empty());

        Optional<PasswordResetService.TokenGerado> resultado = service.gerarTokenResetSenha("inexistente");

        assertThat(resultado).isEmpty();
        verify(tokenRepo, never()).save(any());
    }

    @Test
    void gerarTokenSemEmailCadastradoNaoPersisteNada() {
        Usuario u = new Usuario();
        u.setId(2L);
        u.setUsername("sememail");
        u.setNome("Sem Email");
        u.setVersao(0L);
        when(usuarioRepo.findByUsername("sememail")).thenReturn(Optional.of(u));

        Optional<PasswordResetService.TokenGerado> resultado = service.gerarTokenResetSenha("sememail");

        assertThat(resultado).isEmpty();
        verify(tokenRepo, never()).save(any());
    }

    @Test
    void gerarTokenBloqueiaAposExcederLimiteDeTentativasParaOMesmoUsername() {
        Usuario u = usuarioComEmail();
        when(usuarioRepo.findByUsername("operador1")).thenReturn(Optional.of(u));

        // 3 primeiras passam (MAX_TENTATIVAS de PasswordResetAttemptService).
        assertThat(service.gerarTokenResetSenha("operador1")).isPresent();
        assertThat(service.gerarTokenResetSenha("operador1")).isPresent();
        assertThat(service.gerarTokenResetSenha("operador1")).isPresent();
        verify(tokenRepo, times(3)).save(any());

        // A partir da 4a, bloqueado silenciosamente - nenhum token novo.
        assertThat(service.gerarTokenResetSenha("operador1")).isEmpty();
        verify(tokenRepo, times(3)).save(any());
    }

    // ---- enviarEmail: SO chamado depois do commit (achado E) - aqui so confere o conteudo ----

    @Test
    void enviarEmailMandaLinkComTokenParaOEmailDoUsuario() {
        when(emailSenderService.enviar(anyString(), anyString(), anyString())).thenReturn(true);
        PasswordResetService.TokenGerado info =
            new PasswordResetService.TokenGerado("abc123", "operador1@example.com", "Operador Um");

        boolean enviado = service.enviarEmail(info);

        assertThat(enviado).isTrue();
        ArgumentCaptor<String> corpoCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailSenderService).enviar(eq("operador1@example.com"), anyString(), corpoCaptor.capture());
        assertThat(corpoCaptor.getValue())
            .contains("http://localhost:3000/usuarios/redefinir-senha?token=abc123");
        // Nunca a senha em texto puro - so o link.
        assertThat(corpoCaptor.getValue()).doesNotContain("senha temporária");
    }

    // ---- validar: os 4 estados possiveis do token ----

    private PasswordResetToken tokenValido() {
        PasswordResetToken prt = new PasswordResetToken();
        prt.setUsuario(usuarioComEmail());
        prt.setToken("tok-valido");
        prt.setDataCriacao(Instant.now());
        prt.setDataExpiracao(Instant.now().plusSeconds(3600));
        prt.setUsado(false);
        return prt;
    }

    @Test
    void validarTokenInexistenteRetornaInvalido() {
        when(tokenRepo.findByToken("nao-existe")).thenReturn(Optional.empty());

        assertThat(service.validar("nao-existe")).isEqualTo(PasswordResetService.EstadoToken.INVALIDO);
    }

    @Test
    void validarTokenValidoRetornaValido() {
        when(tokenRepo.findByToken("tok-valido")).thenReturn(Optional.of(tokenValido()));

        assertThat(service.validar("tok-valido")).isEqualTo(PasswordResetService.EstadoToken.VALIDO);
    }

    @Test
    void validarTokenJaUsadoRetornaJaUsado() {
        PasswordResetToken prt = tokenValido();
        prt.setUsado(true);
        when(tokenRepo.findByToken("tok-usado")).thenReturn(Optional.of(prt));

        assertThat(service.validar("tok-usado")).isEqualTo(PasswordResetService.EstadoToken.JA_USADO);
    }

    @Test
    void validarTokenExpiradoRetornaExpirado() {
        PasswordResetToken prt = tokenValido();
        prt.setDataExpiracao(Instant.now().minusSeconds(60));
        when(tokenRepo.findByToken("tok-expirado")).thenReturn(Optional.of(prt));

        assertThat(service.validar("tok-expirado")).isEqualTo(PasswordResetService.EstadoToken.EXPIRADO);
    }

    // ---- confirmarNovaSenha: atomico (senha + token usado juntos), rejeita nos 4 casos invalidos ----

    @Test
    void confirmarNovaSenhaComTokenInexistenteLancaExcecaoSemAlterarNada() {
        when(tokenRepo.findByToken("nao-existe")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmarNovaSenha("nao-existe", "NovaSenha123!", "NovaSenha123!"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("inválido");

        verify(usuarioRepo, never()).save(any());
        verify(tokenRepo, never()).save(any());
    }

    @Test
    void confirmarNovaSenhaComTokenJaUsadoLancaExcecaoSemAlterarNada() {
        PasswordResetToken prt = tokenValido();
        prt.setUsado(true);
        when(tokenRepo.findByToken("tok-usado")).thenReturn(Optional.of(prt));

        assertThatThrownBy(() -> service.confirmarNovaSenha("tok-usado", "NovaSenha123!", "NovaSenha123!"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("já foi utilizado");

        verify(usuarioRepo, never()).save(any());
        verify(tokenRepo, never()).save(any());
    }

    @Test
    void confirmarNovaSenhaComTokenExpiradoLancaExcecaoSemAlterarNada() {
        PasswordResetToken prt = tokenValido();
        prt.setDataExpiracao(Instant.now().minusSeconds(60));
        when(tokenRepo.findByToken("tok-expirado")).thenReturn(Optional.of(prt));

        assertThatThrownBy(() -> service.confirmarNovaSenha("tok-expirado", "NovaSenha123!", "NovaSenha123!"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("expirou");

        verify(usuarioRepo, never()).save(any());
        verify(tokenRepo, never()).save(any());
    }

    @Test
    void confirmarNovaSenhaComSenhaForaDaPoliticaLancaExcecaoSemAlterarNada() {
        when(tokenRepo.findByToken("tok-valido")).thenReturn(Optional.of(tokenValido()));

        assertThatThrownBy(() -> service.confirmarNovaSenha("tok-valido", "fraca", "fraca"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("8 caracteres");

        verify(usuarioRepo, never()).save(any());
        verify(tokenRepo, never()).save(any());
    }

    @Test
    void confirmarNovaSenhaComConfirmacaoDivergenteLancaExcecaoSemAlterarNada() {
        when(tokenRepo.findByToken("tok-valido")).thenReturn(Optional.of(tokenValido()));

        assertThatThrownBy(() -> service.confirmarNovaSenha("tok-valido", "NovaSenha123!", "Outra456!"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("confirmação");

        verify(usuarioRepo, never()).save(any());
        verify(tokenRepo, never()).save(any());
    }

    @Test
    void confirmarNovaSenhaComSucessoAlteraSenhaEMarcaTokenComoUsado() {
        PasswordResetToken prt = tokenValido();
        Usuario u = prt.getUsuario();
        when(tokenRepo.findByToken("tok-valido")).thenReturn(Optional.of(prt));
        when(encoder.encode("NovaSenha123!")).thenReturn("hash-nova");

        service.confirmarNovaSenha("tok-valido", "NovaSenha123!", "NovaSenha123!");

        assertThat(u.getSenha()).isEqualTo("hash-nova");
        verify(usuarioRepo).save(u);
        assertThat(prt.isUsado()).isTrue();
        verify(tokenRepo).save(prt);
    }
}
