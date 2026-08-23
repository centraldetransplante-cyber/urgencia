package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.AnexoSolicitacaoOnline;
import br.gov.saude.sgpur.domain.SolicitacaoOnline;
import br.gov.saude.sgpur.repository.AnexoSolicitacaoOnlineRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;

/**
 * Armazena os documentos clinicos anexados pelo solicitante ANTES de existir
 * um {@code Processo} (por isso guarda em pasta propria, separada de
 * {@code AnexoStorageService}, que exige um {@code Processo}). Mesma
 * allowlist de extensoes e a mesma raiz configurada em {@code app.anexos.dir}.
 */
@Service
public class AnexoSolicitacaoOnlineStorageService {

    private static final Set<String> EXTENSOES_PERMITIDAS =
        Set.of("pdf", "eml", "msg", "png", "jpg", "jpeg");

    private final AnexoSolicitacaoOnlineRepository repository;
    private final Path raiz;

    public AnexoSolicitacaoOnlineStorageService(AnexoSolicitacaoOnlineRepository repository,
                                                @Value("${app.anexos.dir:./data/anexos}") String dir) {
        this.repository = repository;
        this.raiz = Paths.get(dir).toAbsolutePath().normalize().resolve("solicitacoes-online");
    }

    /**
     * Rejeita uploads com extensao fora da allowlist OU cujo CONTEUDO nao
     * bate com a assinatura (magic number) esperada para a extensao
     * declarada (ver {@link AssinaturaArquivoUtil}) - mesma protecao de
     * {@code AnexoStorageService}.
     */
    private void validarTipoPermitido(MultipartFile arquivo) throws IOException {
        String nome = arquivo.getOriginalFilename();
        String extensao = (nome != null && nome.contains("."))
            ? nome.substring(nome.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)
            : "";
        if (!EXTENSOES_PERMITIDAS.contains(extensao)) {
            throw new IllegalArgumentException(
                "Tipo de arquivo nao permitido (" + extensao + ")" + AssinaturaArquivoUtil.MSG_TIPO_NAO_PERMITIDO_SUFIXO);
        }
        byte[] primeirosBytes = AssinaturaArquivoUtil.lerPrimeirosBytes(arquivo);
        if (!AssinaturaArquivoUtil.validoParaExtensao(extensao, primeirosBytes)) {
            throw new IllegalArgumentException(
                "Tipo de arquivo nao permitido (" + extensao + ")" + AssinaturaArquivoUtil.MSG_TIPO_NAO_PERMITIDO_SUFIXO);
        }
    }

    private static String nomeArquivoUnico(Path pasta, String nomeDesejado) {
        String sanitized = nomeDesejado.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        int dot = sanitized.lastIndexOf('.');
        String base = dot > 0 ? sanitized.substring(0, dot) : sanitized;
        String ext = dot > 0 ? sanitized.substring(dot) : "";
        String candidato = sanitized;
        int n = 1;
        while (Files.exists(pasta.resolve(candidato))) {
            n++;
            candidato = base + " (" + n + ")" + ext;
        }
        return candidato;
    }

    /**
     * Salva um documento anexado a uma solicitacao ainda sem id definitivo
     * de pasta legivel (a solicitacao ja tem id ao chegar aqui, pois e salva
     * antes de anexar os arquivos - ver SolicitacaoOnlineService.criar).
     */
    @Transactional
    public AnexoSolicitacaoOnline salvar(SolicitacaoOnline solicitacao, MultipartFile arquivo) throws IOException {
        if (arquivo == null || arquivo.isEmpty()) {
            throw new IllegalArgumentException("Arquivo vazio.");
        }
        validarTipoPermitido(arquivo);
        Path pasta = raiz.resolve(String.valueOf(solicitacao.getId()));
        Files.createDirectories(pasta);

        String original = arquivo.getOriginalFilename() == null ? "documento" : arquivo.getOriginalFilename();
        String nomeFinal = nomeArquivoUnico(pasta, original);
        Path destino = pasta.resolve(nomeFinal);

        try (InputStream in = arquivo.getInputStream()) {
            Files.copy(in, destino);
        }

        AnexoSolicitacaoOnline anexo = new AnexoSolicitacaoOnline();
        anexo.setSolicitacaoOnline(solicitacao);
        anexo.setNomeArquivo(nomeFinal);
        anexo.setContentType(arquivo.getContentType());
        anexo.setTamanhoBytes(arquivo.getSize());
        anexo.setCaminhoArmazenado(raiz.relativize(destino).toString());
        return repository.save(anexo);
    }

    /**
     * Resolve o arquivo fisico de um anexo, com defesa contra path traversal.
     * Normaliza {@code \} para {@code /} ANTES de resolver: no Linux (onde o
     * app roda em producao) a barra invertida nao e separador de caminho,
     * entao um valor gravado como {@code ..\..\Windows\win.ini} vira so um
     * nome de arquivo literal e nunca escapa da raiz - a checagem abaixo so
     * pega esse ataque de verdade em qualquer SO se a barra invertida for
     * tratada como separador primeiro.
     */
    public Path resolverArquivo(AnexoSolicitacaoOnline anexo) {
        String caminho = anexo.getCaminhoArmazenado().replace('\\', '/');
        Path resolvido = raiz.resolve(caminho).normalize();
        if (!resolvido.startsWith(raiz)) {
            throw new IllegalArgumentException("Caminho de anexo invalido (fora da area de armazenamento).");
        }
        return resolvido;
    }
}
