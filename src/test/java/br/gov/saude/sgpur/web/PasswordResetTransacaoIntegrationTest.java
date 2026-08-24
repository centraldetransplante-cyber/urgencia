package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.PasswordResetToken;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.PasswordResetTokenRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de INTEGRACAO (contexto Spring real + H2, {@code PasswordResetService}
 * REAL com o proxy transacional de verdade) do fluxo novo de "esqueci minha
 * senha" por token (achados D e E da vistoria de 2026-08-24).
 *
 * <p><b>Por que nao da para cobrir isso com {@code @WebMvcTest}:</b> o que
 * importa aqui e a ORDEM real commit-antes-de-notificar entre duas chamadas
 * de metodo do MESMO service ({@code gerarTokenResetSenha} e
 * {@code enviarEmail}) feitas pelo controller - com o service mockado nao
 * existe nenhum @Transactional de verdade rodando, entao o teste passaria
 * mesmo se alguem "simplificasse" o controller voltando a mandar o e-mail
 * DENTRO da transacao que persiste o token (recriando o achado E).
 *
 * <p><b>Cenario forcado deterministicamente:</b> o {@code JavaMailSender} e
 * mockado para lancar {@code MailSendException} em todo envio - simula uma
 * falha real de SMTP. O que o teste prova: mesmo com o e-mail falhando, o
 * TOKEN continua persistido e valido (porque foi commitado ANTES da
 * tentativa de envio), e da para completar a troca de senha normalmente com
 * ele - a falha de e-mail nunca desfaz o pedido de reset.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sgpur-password-reset-tx;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.mail.override-recipient=",
        "app.base-url=http://localhost:3000"
})
class PasswordResetTransacaoIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UsuarioRepository usuarioRepo;
    @Autowired
    private PasswordResetTokenRepository tokenRepo;
    @Autowired
    private PasswordEncoder encoder;

    /**
     * Unico ponto mockado: infraestrutura de SMTP, nao o service. Forca a
     * falha de envio de forma deterministica sem depender de rede real.
     */
    @MockitoBean
    private JavaMailSender mailSender;

    @BeforeEach
    @Transactional
    void preparar() {
        tokenRepo.deleteAll();
        usuarioRepo.findByUsername("reset-tx-it").ifPresent(usuarioRepo::delete);

        Usuario u = new Usuario();
        u.setUsername("reset-tx-it");
        u.setNome("Usuario Reset TX");
        u.setEmail("reset-tx-it@example.com");
        u.setSenha(encoder.encode("SenhaAntiga123!"));
        u.setPerfil(Perfil.OPERADOR);
        u.setAtivo(true);
        usuarioRepo.saveAndFlush(u);

        Session session = Session.getDefaultInstance(new Properties());
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(session));
    }

    /**
     * REGRESSAO DO ACHADO E: o envio do e-mail falha (SMTP fora do ar), mas
     * o token ja esta persistido e continua valido - da para completar a
     * troca de senha com ele mesmo assim. O pedido original nunca e perdido
     * por causa de uma falha de rede que aconteceu DEPOIS do commit.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void tokenSobreviveEContinuaValidoQuandoEnvioDoEmailFalha() throws Exception {
        org.mockito.Mockito.doThrow(new MailSendException("SMTP fora do ar (simulado)"))
            .when(mailSender).send(any(MimeMessage.class));

        // Passo 1: pedir o reset. Mesmo com o SMTP falhando, a rota nao pode
        // quebrar (500) nem deixar de mostrar a mensagem neutra de sempre.
        mvc.perform(post("/usuarios/esqueci-senha")
                        .with(csrf())
                        .param("username", "reset-tx-it"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("sucesso", true));

        // O QUE IMPORTA: o token esta no banco, apesar do e-mail ter falhado -
        // porque foi commitado ANTES da tentativa de envio (achado E).
        List<PasswordResetToken> tokens = tokenRepo.findAll();
        assertThat(tokens).hasSize(1);
        PasswordResetToken token = tokens.get(0);
        assertThat(token.isUsado()).isFalse();
        assertThat(token.getDataExpiracao()).isAfter(Instant.now());

        // Passo 2: mesmo com a falha de e-mail no passo 1, o link (token)
        // continua funcional - o usuario consegue trocar a senha com ele.
        mvc.perform(post("/usuarios/redefinir-senha")
                        .with(csrf())
                        .param("token", token.getToken())
                        .param("novaSenha", "SenhaNova456!")
                        .param("confirmacao", "SenhaNova456!"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        Usuario depois = usuarioRepo.findByUsername("reset-tx-it").orElseThrow();
        assertThat(encoder.matches("SenhaNova456!", depois.getSenha())).isTrue();
        PasswordResetToken tokenDepois = tokenRepo.findByToken(token.getToken()).orElseThrow();
        assertThat(tokenDepois.isUsado()).isTrue();
    }

    /**
     * Atomicidade da confirmacao: um token expirado nunca troca a senha -
     * nem parcialmente. A senha antiga continua valida e o token nao vira
     * "usado" so por essa tentativa rejeitada.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void confirmarComTokenExpiradoNaoAlteraSenhaNemMarcaComoUsado() throws Exception {
        PasswordResetToken prt = new PasswordResetToken();
        prt.setUsuario(usuarioRepo.findByUsername("reset-tx-it").orElseThrow());
        prt.setToken("token-expirado-teste");
        prt.setDataCriacao(Instant.now().minusSeconds(7200));
        prt.setDataExpiracao(Instant.now().minusSeconds(60));
        prt.setUsado(false);
        tokenRepo.saveAndFlush(prt);

        mvc.perform(post("/usuarios/redefinir-senha")
                        .with(csrf())
                        .param("token", "token-expirado-teste")
                        .param("novaSenha", "SenhaNova456!")
                        .param("confirmacao", "SenhaNova456!"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("erroToken", org.hamcrest.Matchers.containsString("expirou")));

        Usuario depois = usuarioRepo.findByUsername("reset-tx-it").orElseThrow();
        assertThat(encoder.matches("SenhaAntiga123!", depois.getSenha())).isTrue();
        PasswordResetToken tokenDepois = tokenRepo.findByToken("token-expirado-teste").orElseThrow();
        assertThat(tokenDepois.isUsado()).isFalse();
    }
}
