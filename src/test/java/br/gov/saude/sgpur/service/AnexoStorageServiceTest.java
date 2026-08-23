package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Anexo;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.TipoAnexo;
import br.gov.saude.sgpur.repository.AnexoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cobre a allowlist de extensao de upload (PDF/e-mail/imagem) - bloqueia
 * armazenar executaveis/scripts disfarcados de anexo clinico ou comprobatorio.
 */
@ExtendWith(MockitoExtension.class)
class AnexoStorageServiceTest {

    @Mock
    private AnexoRepository anexoRepository;

    private AnexoStorageService service;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        service = new AnexoStorageService(anexoRepository, tempDir.toString());
    }

    private Processo processo() {
        Processo p = new Processo();
        p.setNumero("07/2026");
        p.setPacienteNome("Mariana da Rosa Martins");
        return p;
    }

    /** Bytes minimos de um PDF de verdade ("%PDF-1.4" + resto qualquer) - passa na checagem de assinatura. */
    private static final byte[] CONTEUDO_PDF_VALIDO = "%PDF-1.4\nconteudo".getBytes();

    @Test
    void salvarRenomeiaParaNomePadrao() throws Exception {
        when(anexoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        MockMultipartFile arquivo = new MockMultipartFile(
            "arquivo", "scan0012.pdf", "application/pdf", CONTEUDO_PDF_VALIDO);
        var anexo = service.salvar(processo(), TipoAnexo.DOCUMENTO_PACIENTE, "desc", arquivo);
        // Nome padrao: "AAAA-MM-DD - CET-RS 07-2026 - Documento paciente.pdf"
        // (a barra do numero vira traco; o nome original do upload e descartado).
        assertThat(anexo.getNomeArquivo())
            .matches("\\d{4}-\\d{2}-\\d{2} - CET-RS 07-2026 - Documento paciente\\.pdf");
    }

    @Test
    void salvarNumeraNomesRepetidosDoMesmoTipo() throws Exception {
        when(anexoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var p = processo();
        service.salvar(p, TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR, "d",
            new MockMultipartFile("arquivo", "a.pdf", "application/pdf", CONTEUDO_PDF_VALIDO));
        var segundo = service.salvar(p, TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR, "d",
            new MockMultipartFile("arquivo", "b.pdf", "application/pdf", CONTEUDO_PDF_VALIDO));
        // O segundo arquivo do mesmo tipo/dia recebe o sufixo " (2)".
        assertThat(segundo.getNomeArquivo()).endsWith("- Documento clinico (2).pdf");
    }

    @Test
    void salvarRejeitaExecutavel() {
        MockMultipartFile arquivo = new MockMultipartFile(
            "arquivo", "malware.exe", "application/octet-stream", "conteudo".getBytes());
        assertThatThrownBy(() -> service.salvar(processo(), TipoAnexo.DOCUMENTO_PACIENTE, "desc", arquivo))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nao permitido");
    }

    @Test
    void salvarRejeitaScriptDisfarcadoDeDocumento() {
        MockMultipartFile arquivo = new MockMultipartFile(
            "arquivo", "documento-clinico.html", "text/html", "<script>alert(1)</script>".getBytes());
        assertThatThrownBy(() -> service.salvar(processo(), TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR, "desc", arquivo))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void salvarRejeitaExecutavelDisfarcadoDeExtensaoPermitida() {
        // Extensao ".pdf" (passa na allowlist), mas conteudo real e um
        // executavel Windows ("MZ...") - a checagem de assinatura (magic
        // number) deve rejeitar mesmo com a extensao correta.
        byte[] bytesExecutavel = {0x4D, 0x5A, (byte) 0x90, 0x00, 0x03};
        MockMultipartFile arquivo = new MockMultipartFile(
            "arquivo", "documento-clinico.pdf", "application/pdf", bytesExecutavel);
        assertThatThrownBy(() -> service.salvar(processo(), TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR, "desc", arquivo))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nao permitido");
    }

    @Test
    void removerAntigosDoTipoIgnoraAnexoComCaminhoInvalido() {
        Processo p = processo();
        p.setId(1L);
        Anexo antigo = new Anexo();
        antigo.setId(10L);
        antigo.setProcesso(p);
        antigo.setTipo(TipoAnexo.COMPROVANTE_SNT);
        antigo.setCaminhoArmazenado(null);
        when(anexoRepository.findByProcessoIdAndTipo(1L, TipoAnexo.COMPROVANTE_SNT))
            .thenReturn(java.util.List.of(antigo));

        assertThatCode(() -> service.removerAntigosDoTipo(p, TipoAnexo.COMPROVANTE_SNT, 99L))
            .doesNotThrowAnyException();

        verify(anexoRepository).delete(antigo);
    }

}
