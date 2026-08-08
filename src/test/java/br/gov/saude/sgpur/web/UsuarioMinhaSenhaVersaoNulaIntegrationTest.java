package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de INTEGRACAO (contexto Spring real + H2, sem mock do service — a
 * mesma convencao do CLAUDE.md para escrita irreversivel: {@code @WebMvcTest}
 * + {@code @MockitoBean} nao pega erro de transacao, porque nao existe proxy
 * do Spring nesse tipo de teste) do BUG REAL reportado: {@code POST
 * /usuarios/minha-senha} devolvia 500 cru para um usuario cujo {@code
 * Usuario.versao} estava {@code NULL} no banco (dado seed/legado anterior ao
 * commit que adicionou {@code @Version} a esta entidade, 2026-07-29, sem o
 * backfill manual documentado no CLAUDE.md ter rodado - ex.: o arquivo H2 de
 * desenvolvimento de alguem, que persiste entre reinicios).
 *
 * <p><b>Causa raiz confirmada por reproducao direta</b> (nao presumida):
 * salvar um {@code Usuario} com {@code versao == null} faz o Hibernate
 * lancar {@code NullPointerException} CRUA
 * ({@code org.hibernate.type.descriptor.java.LongJavaType.next}, ao tentar
 * incrementar um {@code Long} nulo) no COMMIT da transacao, envolvida em
 * {@code TransactionSystemException} - um tipo bem diferente de {@code
 * ObjectOptimisticLockingFailureException} (que o {@code
 * GlobalExceptionHandler} ja trata graciosamente para conflito de escrita
 * concorrente de verdade). Nenhum {@code @ExceptionHandler} do projeto
 * reconhecia {@code TransactionSystemException}, entao virava "Erro interno
 * do servidor" (500) para o usuario.</p>
 *
 * <p><b>Correcao:</b> {@code UsuarioService.normalizarVersaoLegada} passa a
 * normalizar {@code versao == null} para {@code 0L} (mesmo efeito do
 * backfill manual documentado) antes de salvar, em todo ponto de escrita do
 * service que mutua um {@code Usuario} ja existente.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-usuario-versao-nula;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.anexos.dir=./target/test-anexos-usuario-versao-nula"
})
class UsuarioMinhaSenhaVersaoNulaIntegrationTest {

    private static final String USERNAME = "operador-versao-nula-it";
    private static final String SENHA_ATUAL = "SenhaAtual123!";
    private static final String SENHA_NOVA = "SenhaNova456!";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UsuarioRepository usuarioRepo;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private PlatformTransactionManager txManager;
    @PersistenceContext
    private EntityManager em;

    @BeforeEach
    void preparar() {
        usuarioRepo.findByUsername(USERNAME).ifPresent(usuarioRepo::delete);

        Usuario u = new Usuario();
        u.setUsername(USERNAME);
        u.setSenha(encoder.encode(SENHA_ATUAL));
        u.setNome("Operador Versao Nula");
        u.setPerfil(Perfil.OPERADOR);
        u.setAtivo(true);
        Long id = usuarioRepo.saveAndFlush(u).getId();

        // Simula o dado seed/legado: derruba a coluna 'versao' para NULL "por
        // baixo" do JPA, exatamente como um banco criado antes do commit que
        // adicionou @Version a esta entidade, sem o backfill manual.
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(s -> em.createNativeQuery(
                "UPDATE usuario SET versao = NULL WHERE id = :id")
            .setParameter("id", id)
            .executeUpdate());

        assertThat(usuarioRepo.findById(id).orElseThrow().getVersao())
            .as("fixture: versao precisa estar nula ANTES do teste, para reproduzir o bug real")
            .isNull();
    }

    /**
     * REGRESSAO DO BUG: trocar a propria senha de um usuario com {@code
     * versao} nula NUNCA pode devolver 500 - tem que trocar a senha com
     * sucesso (o caminho feliz normal), e a senha nova precisa realmente
     * valer no proximo login (relido do banco, nao so "sem excecao").
     */
    @Test
    void trocarPropriaSenhaComVersaoNulaNaoQuebraEATrocaComSucesso() throws Exception {
        mvc.perform(post("/usuarios/minha-senha")
                .with(csrf())
                .with(user(USERNAME).roles("OPERADOR"))
                .param("senhaAtual", SENHA_ATUAL)
                .param("novaSenha", SENHA_NOVA)
                .param("confirmacao", SENHA_NOVA))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/usuarios/minha-senha"))
            .andExpect(flash().attribute("msg", "Senha alterada com sucesso."));

        Usuario doBanco = usuarioRepo.findByUsername(USERNAME).orElseThrow();
        assertThat(encoder.matches(SENHA_NOVA, doBanco.getSenha()))
            .as("a nova senha precisa valer de verdade, nao so 'sem excecao'")
            .isTrue();
        assertThat(doBanco.getVersao())
            .as("versao normalizada (deixou de ser nula) apos a escrita")
            .isNotNull();
    }
}
