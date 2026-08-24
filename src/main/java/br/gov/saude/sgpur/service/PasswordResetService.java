package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.PasswordResetToken;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.PasswordResetTokenRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Fluxo "esqueci minha senha" por LINK de uso unico (2026-08-24, corrige o
 * achado D da vistoria de 2026-08-24: o fluxo antigo, em
 * {@code UsuarioService.resetarSenha} - removido -, trocava a senha ATIVA na
 * hora de cada pedido e mandava a senha nova por e-mail. Isso permitia um
 * ataque de negacao de servico: qualquer pessoa que soubesse o LOGIN de um
 * avaliador/admin (nao precisa do e-mail nem da senha) conseguia derrubar o
 * acesso dele repetidamente - um reset a cada janela do rate-limit ja
 * mantinha a conta travada indefinidamente para quem nao tem acesso aquele
 * e-mail.
 *
 * <p><b>Fluxo novo:</b> {@link #gerarTokenResetSenha} gera e PERSISTE um
 * {@link PasswordResetToken} de uso unico com TTL curto ({@link #TTL}) - a
 * senha ativa NAO muda nesse passo, continua valida normalmente. So quando o
 * usuario abre o link (dentro do prazo) e confirma uma nova senha em
 * {@link #confirmarNovaSenha} e que a senha ativa e trocada. Um atacante
 * gerando tokens repetidamente nunca derruba o acesso de ninguem - o pior
 * caso e spam de e-mail, ja mitigado pelo rate-limit existente
 * ({@link PasswordResetAttemptService}).
 *
 * <p><b>Ordem commit-antes-de-notificar (achado E da mesma vistoria):</b> o
 * e-mail com o link SO deve ser disparado pelo controller DEPOIS que
 * {@link #gerarTokenResetSenha} retornar - ou seja, depois do commit da
 * transacao que persiste o token (mesmo padrao de
 * {@code RegistroEnvioService.enviarConvitesAvaliadores}, chamado pelo
 * controller depois do {@code registrar()} ja ter comitado). Se o SMTP
 * falhar, o token ja persistido continua valido - o usuario pode pedir um
 * reenvio (que invalida e substitui o token anterior) sem perder o pedido
 * original.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    /**
     * TTL do token: curto de proposito (e um link por e-mail, uso unico,
     * nao uma sessao de trabalho) - 60 minutos e generoso o bastante para dar
     * tempo de abrir o e-mail sem deixar o link valido por dias.
     */
    static final Duration TTL = Duration.ofMinutes(60);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UsuarioRepository usuarioRepo;
    private final UsuarioService usuarioService;
    private final PasswordResetTokenRepository tokenRepo;
    private final PasswordEncoder encoder;
    private final EmailSenderService emailSenderService;
    private final PasswordResetAttemptService passwordResetAttemptService;
    private final String baseUrl;

    public PasswordResetService(UsuarioRepository usuarioRepo,
                                UsuarioService usuarioService,
                                PasswordResetTokenRepository tokenRepo,
                                PasswordEncoder encoder,
                                EmailSenderService emailSenderService,
                                PasswordResetAttemptService passwordResetAttemptService,
                                @Value("${app.base-url:http://localhost:3000}") String baseUrl) {
        this.usuarioRepo = usuarioRepo;
        this.usuarioService = usuarioService;
        this.tokenRepo = tokenRepo;
        this.encoder = encoder;
        this.emailSenderService = emailSenderService;
        this.passwordResetAttemptService = passwordResetAttemptService;
        this.baseUrl = baseUrl;
    }

    /** Dados minimos para montar/enviar o e-mail com o link, DEPOIS do commit do token. */
    public record TokenGerado(String token, String emailDestino, String nomeUsuario) {}

    public enum EstadoToken { VALIDO, INVALIDO, EXPIRADO, JA_USADO }

    /**
     * Passo 1: valida o rate-limit, localiza o usuario e, se ele existir e
     * tiver e-mail cadastrado, gera e persiste um token novo (invalidando
     * qualquer token pendente anterior do mesmo usuario). NUNCA envia e-mail
     * nem altera a senha ativa - ver javadoc da classe.
     *
     * <p>Retorna {@code Optional.empty()} em qualquer caminho que nao deve
     * gerar e-mail (usuario inexistente, sem e-mail cadastrado, rate-limit
     * excedido) - o chamador (controller) SEMPRE exibe a mesma mensagem
     * neutra ao usuario final, exista ou nao o login, para nao permitir
     * enumeracao de usuarios validos.
     */
    @Transactional
    public Optional<TokenGerado> gerarTokenResetSenha(String username) {
        if (!passwordResetAttemptService.tentarRegistrar(username)) {
            return Optional.empty();
        }
        Usuario u = usuarioRepo.findByUsername(username).orElse(null);
        if (u == null) {
            log.debug("gerarTokenResetSenha: usuario '{}' nao encontrado.", username);
            return Optional.empty();
        }
        if (u.getEmail() == null || u.getEmail().isBlank()) {
            log.warn("gerarTokenResetSenha: usuario '{}' sem e-mail cadastrado - nenhum token "
                + "gerado. Peca ao ADMIN redefinir manualmente.", username);
            return Optional.empty();
        }
        tokenRepo.deleteByUsuarioId(u.getId());
        String token = gerarTokenOpaco();
        PasswordResetToken prt = new PasswordResetToken();
        prt.setUsuario(u);
        prt.setToken(token);
        prt.setDataCriacao(Instant.now());
        prt.setDataExpiracao(Instant.now().plus(TTL));
        prt.setUsado(false);
        tokenRepo.save(prt);
        return Optional.of(new TokenGerado(token, u.getEmail(), u.getNome()));
    }

    /**
     * Passo 1b: envia o e-mail com o link de redefinicao. Chamar SEMPRE
     * depois de {@link #gerarTokenResetSenha} ja ter retornado (token
     * comitado) - nunca de dentro dessa transacao. Nao lanca excecao em
     * falha de SMTP (so loga e devolve {@code false}) - o token ja
     * persistido continua valido independente do envio ter funcionado.
     */
    public boolean enviarEmail(TokenGerado info) {
        String link = baseUrl + "/usuarios/redefinir-senha?token=" + info.token();
        // Acentuado, mesma convencao de e-mail institucional das demais telas
        // (ver EmailTemplateService).
        String corpo = """
            Olá, %s,

            Recebemos um pedido de redefinição da sua senha de acesso ao SAUR.

            Para definir uma nova senha, acesse o link abaixo em até %d minutos:
            %s

            Se você não solicitou esta redefinição, ignore este e-mail - sua
            senha atual continua válida e nenhuma alteração foi feita.

            Atenciosamente,
            Equipe SAUR - Secretaria de Saúde
            """.formatted(info.nomeUsuario(), TTL.toMinutes(), link);
        boolean enviado = emailSenderService.enviar(info.emailDestino(), "SAUR - Redefinição de senha", corpo);
        if (!enviado) {
            log.warn("PasswordResetService: falha ao enviar e-mail de redefinicao de senha para '{}'.",
                info.emailDestino());
        }
        return enviado;
    }

    /**
     * Usado pelo GET do formulario de redefinicao para decidir se mostra o
     * form de nova senha ou uma mensagem de erro generica (token
     * invalido/expirado/ja usado nunca revela qual dos tres motivos, so a
     * tela usa mensagens levemente diferentes por UX - nada que ajude um
     * atacante a adivinhar tokens de outros usuarios).
     */
    @Transactional(readOnly = true)
    public EstadoToken validar(String token) {
        if (token == null || token.isBlank()) {
            return EstadoToken.INVALIDO;
        }
        return tokenRepo.findByToken(token)
            .map(prt -> {
                if (prt.isUsado()) {
                    return EstadoToken.JA_USADO;
                }
                if (prt.getDataExpiracao().isBefore(Instant.now())) {
                    return EstadoToken.EXPIRADO;
                }
                return EstadoToken.VALIDO;
            })
            .orElse(EstadoToken.INVALIDO);
    }

    /**
     * Passo 2: confirma a nova senha a partir do token. Lanca
     * {@code IllegalArgumentException} com mensagem amigavel para token
     * invalido/expirado/ja usado, senha fora da politica ou confirmacao
     * divergente - nenhum efeito colateral nesses casos (nem a senha nem o
     * token sao alterados). No sucesso, troca a senha ATIVA e marca o token
     * como usado NA MESMA TRANSACAO (atomico: ou os dois efeitos acontecem,
     * ou nenhum - nunca uma senha trocada com o token ainda reutilizavel).
     *
     * <p>Retorna o {@code username} do usuario afetado - usado pelo
     * controller para nomear o evento de auditoria
     * {@code SENHA_RESET_CONFIRMADO} (bug_001 da revisao de codigo do PR de
     * 2026-08-24), no mesmo padrao dos demais eventos de senha
     * ({@code SENHA_ALTERADA}, {@code SENHA_RESET_SOLICITADO}), que sempre
     * nomeiam o usuario.
     */
    @Transactional
    public String confirmarNovaSenha(String token, String novaSenha, String confirmacao) {
        PasswordResetToken prt = tokenRepo.findByToken(token)
            .orElseThrow(() -> new IllegalArgumentException(
                "Link de redefinição inválido. Solicite uma nova redefinição de senha."));
        if (prt.isUsado()) {
            throw new IllegalArgumentException(
                "Este link de redefinição já foi utilizado. Solicite um novo.");
        }
        if (prt.getDataExpiracao().isBefore(Instant.now())) {
            throw new IllegalArgumentException(
                "Este link de redefinição expirou. Solicite uma nova redefinição de senha.");
        }
        SenhaPolicy.validar(novaSenha);
        if (!novaSenha.equals(confirmacao)) {
            throw new IllegalArgumentException("A confirmação não confere com a nova senha.");
        }
        // Mesma normalizacao de versao legada que UsuarioService.atualizar/
        // alternarAtivo/alterarPropriaSenha ja fazem (bug_006) - reusada em
        // vez de duplicada, ver UsuarioService.normalizarVersaoLegada.
        Usuario u = usuarioService.normalizarVersaoLegada(prt.getUsuario());
        u.setSenha(encoder.encode(novaSenha));
        usuarioRepo.save(u);
        prt.setUsado(true);
        tokenRepo.save(prt);
        return u.getUsername();
    }

    /** Gera um token opaco (nao previsivel) a partir de 32 bytes de SecureRandom, Base64 URL-safe. */
    private String gerarTokenOpaco() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Remove do banco os tokens ja expirados - chamado pelo
     * {@code RateLimitLimpezaScheduler} periodicamente. Nao muda nenhuma
     * semantica (um token expirado ja e rejeitado por {@link #validar}/
     * {@link #confirmarNovaSenha}), so evita a tabela crescer sem limite.
     */
    @Transactional
    public void limparTokensExpirados() {
        int removidos = tokenRepo.deleteExpiradosAntesDe(Instant.now());
        if (removidos > 0) {
            log.debug("PasswordResetService: {} token(s) de reset de senha expirado(s) removido(s).", removidos);
        }
    }
}
