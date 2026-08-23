package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.AnexoSolicitacaoOnline;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.SolicitacaoOnline;
import br.gov.saude.sgpur.domain.StatusSolicitacaoOnline;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.domain.Sexo;
import br.gov.saude.sgpur.repository.AnexoSolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.AnexoSolicitacaoOnlineStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Teste de INTEGRACAO (contexto Spring real + H2 real, storage real em
 * disco) para o novo endpoint de download de anexo da triagem
 * ({@code GET /processos/solicitacoes-online/{id}/anexo/{anexoId}}),
 * espelhando o padrao de posse de
 * {@code SolicitanteController.baixarAnexo}. Sem mock de repositorio/servico
 * porque a checagem de posse (IDOR) so faz sentido contra dados reais
 * persistidos.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-triagem-anexo-download-it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.solicitante.habilitado=true",
    "app.anexos.dir=./target/test-anexos-triagem-anexo-download-it"
})
class SolicitacaoOnlineAnexoDownloadIntegrationTest {

    @Autowired
    private SolicitacaoOnlineTriagemController controller;
    @Autowired
    private SolicitacaoOnlineRepository solicitacaoRepo;
    @Autowired
    private UsuarioRepository usuarioRepo;
    @Autowired
    private AnexoSolicitacaoOnlineRepository anexoRepo;
    @Autowired
    private AnexoSolicitacaoOnlineStorageService anexoStorage;

    private Long solicitacaoAId;
    private Long anexoDaSolicitacaoAId;
    private Long solicitacaoBId;

    @BeforeEach
    void preparar() throws Exception {
        anexoRepo.deleteAll();
        solicitacaoRepo.deleteAll();
        usuarioRepo.findByUsername("solicitante-download-a").ifPresent(usuarioRepo::delete);
        usuarioRepo.findByUsername("solicitante-download-b").ifPresent(usuarioRepo::delete);

        Usuario donoA = criarSolicitante("solicitante-download-a");
        Usuario donoB = criarSolicitante("solicitante-download-b");

        SolicitacaoOnline a = criarSolicitacao(donoA);
        solicitacaoAId = a.getId();
        SolicitacaoOnline b = criarSolicitacao(donoB);
        solicitacaoBId = b.getId();

        MockMultipartFile arquivo = new MockMultipartFile(
            "documentos", "exame-creatinina.pdf", "application/pdf", "%PDF-1.4\nconteudo-do-exame".getBytes());
        AnexoSolicitacaoOnline anexo = anexoStorage.salvar(a, arquivo);
        anexoDaSolicitacaoAId = anexo.getId();
    }

    private Usuario criarSolicitante(String username) {
        Usuario u = new Usuario();
        u.setUsername(username);
        u.setNome("Solicitante " + username);
        u.setEmail(username + "@example.com");
        u.setSenha("{noop}irrelevante");
        u.setPerfil(Perfil.SOLICITANTE);
        u.setAtivo(true);
        u.setEquipeSolicitante("HCPA - Nefrologia");
        return usuarioRepo.saveAndFlush(u);
    }

    private SolicitacaoOnline criarSolicitacao(Usuario dono) {
        SolicitacaoOnline s = new SolicitacaoOnline();
        s.setUsuarioSolicitante(dono);
        s.setPacienteNome("Paciente de Teste " + dono.getUsername());
        s.setPacienteRgct("123456789-12345");
        s.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        s.setPacienteCpf("11144477735");
        s.setPacienteSexo(Sexo.MASCULINO);
        s.setDataSituacaoEspecial(LocalDate.now().minusDays(1));
        s.setJustificativaClinica("Justificativa clinica.");
        s.setSolicitanteEquipe(dono.getEquipeSolicitante());
        s.setSolicitanteEmail(dono.getEmail());
        s.setStatus(StatusSolicitacaoOnline.ENVIADA);
        s.setDataEnvio(LocalDateTime.now());
        return solicitacaoRepo.saveAndFlush(s);
    }

    /** Caminho feliz: o anexo pertence de fato a solicitacao da URL. */
    @Test
    void baixarAnexoDaProprioSolicitacaoFunciona() throws Exception {
        ResponseEntity<Resource> resposta = controller.baixarAnexo(solicitacaoAId, anexoDaSolicitacaoAId);

        assertThat(resposta.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resposta.getBody()).isNotNull();
        assertThat(resposta.getBody().exists()).isTrue();
        // Nao usa igualdade exata: o storage deduplica nomes de arquivo em
        // disco entre execucoes (nomeArquivoUnico), entao rodadas repetidas
        // do teste podem gerar "exame-creatinina (2).pdf" etc.
        assertThat(resposta.getHeaders().getContentDisposition().getFilename())
            .startsWith("exame-creatinina")
            .endsWith(".pdf");
    }

    /**
     * Caminho de posse (IDOR): o anexo pertence a solicitacao A, mas a URL
     * pede pelo id da solicitacao B - nunca deve vazar o arquivo, mesmo que
     * o anexoId exista de verdade no banco.
     */
    @Test
    void baixarAnexoDeOutraSolicitacaoENegado() {
        assertThatThrownBy(() -> controller.baixarAnexo(solicitacaoBId, anexoDaSolicitacaoAId))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("403")
            .hasMessageContaining("nao pertence");
    }

    /** anexoId inexistente nunca deve vazar detalhe nenhum - so 404. */
    @Test
    void baixarAnexoInexistenteDevolve404() {
        assertThatThrownBy(() -> controller.baixarAnexo(solicitacaoAId, 999999L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("404");
    }
}
