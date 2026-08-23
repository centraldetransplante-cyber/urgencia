package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Anexo;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.TipoAnexo;
import br.gov.saude.sgpur.repository.AnexoRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;

/**
 * Armazena os arquivos anexados em disco e registra os metadados no banco.
 */
@Service
public class AnexoStorageService {

    /**
     * Extensoes aceitas para upload manual (todo o app so pede PDF, e-mail
     * (.eml/.msg) ou imagem do comprovante SNT nos forms - ver accept="" dos
     * templates). Bloqueia executaveis/scripts sendo armazenados como anexo
     * de um sistema de saude.
     */
    private static final Set<String> EXTENSOES_PERMITIDAS =
        Set.of("pdf", "eml", "msg", "png", "jpg", "jpeg");

    private final AnexoRepository anexoRepository;
    private final Path raiz;

    public AnexoStorageService(AnexoRepository anexoRepository,
                               @Value("${app.anexos.dir:./data/anexos}") String dir) {
        this.anexoRepository = anexoRepository;
        this.raiz = Paths.get(dir).toAbsolutePath().normalize();
    }

    /**
     * Retorna o diretorio de armazenamento do processo no padrao legivel
     * {@code "NN-AAAA - Nome do Paciente"} (a barra do numero vira traco, pois
     * "/" e separador de caminho). Para retrocompatibilidade com registros
     * antigos (pasta "processo-{id}" ou a que incluia o RGCT), o metodo
     * {@link #resolverArquivo(Anexo)} usa o caminho gravado no banco, entao
     * downloads de anexos antigos continuam funcionando.
     */
    public Path resolverDirProcesso(Processo processo) {
        String numero = (processo.getNumero() == null || processo.getNumero().isBlank())
                ? "SN" : processo.getNumero().replace("/", "-");
        String nome = numero;
        if (processo.getPacienteNome() != null && !processo.getPacienteNome().isBlank()) {
            nome = numero + " - " + processo.getPacienteNome();
        }
        nome = nome.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (nome.length() > 120) {
            nome = nome.substring(0, 120);
        }
        return raiz.resolve(nome);
    }

    /** Mesma mensagem de negocio da checagem de extensao - nao expoe detalhe tecnico de "assinatura invalida". */
    private static final String MSG_TIPO_NAO_PERMITIDO_SUFIXO =
        ". Envie PDF, imagem (PNG/JPG) ou e-mail (EML/MSG).";

    /**
     * Rejeita uploads com extensao fora da allowlist (PDF/e-mail/imagem) OU
     * cujo CONTEUDO nao bate com a assinatura (magic number) esperada para a
     * extensao declarada (ver {@link AssinaturaArquivoUtil}) - bloqueia o
     * caso obvio de subir um executavel/script disfarcado de anexo
     * clinico/comprobatorio so trocando o nome do arquivo.
     */
    private void validarTipoPermitido(MultipartFile arquivo) throws IOException {
        String nome = arquivo.getOriginalFilename();
        String extensao = (nome != null && nome.contains("."))
            ? nome.substring(nome.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)
            : "";
        if (!EXTENSOES_PERMITIDAS.contains(extensao)) {
            throw new IllegalArgumentException(
                "Tipo de arquivo nao permitido (" + extensao + ")" + MSG_TIPO_NAO_PERMITIDO_SUFIXO);
        }
        byte[] primeirosBytes = lerPrimeirosBytes(arquivo);
        if (!AssinaturaArquivoUtil.validoParaExtensao(extensao, primeirosBytes)) {
            throw new IllegalArgumentException(
                "Tipo de arquivo nao permitido (" + extensao + ")" + MSG_TIPO_NAO_PERMITIDO_SUFIXO);
        }
    }

    /** Le so o inicio do arquivo (suficiente para conferir a assinatura), sem carregar tudo em memoria. */
    private static byte[] lerPrimeirosBytes(MultipartFile arquivo) throws IOException {
        byte[] buffer = new byte[16];
        try (InputStream in = arquivo.getInputStream()) {
            int lidos = in.readNBytes(buffer, 0, buffer.length);
            return lidos == buffer.length ? buffer : java.util.Arrays.copyOf(buffer, lidos);
        }
    }

    /**
     * Nome de arquivo unico dentro da pasta: parte do {@code nomeDesejado} e,
     * se ja existir um arquivo com esse nome, acrescenta " (2)", " (3)"... antes
     * da extensao. Sanitiza caracteres ilegais em nome de arquivo.
     */
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

    @Transactional
    public Anexo salvar(Processo processo, TipoAnexo tipo, String descricao, MultipartFile arquivo)
            throws IOException {
        if (arquivo == null || arquivo.isEmpty()) {
            throw new IllegalArgumentException("Arquivo vazio.");
        }
        validarTipoPermitido(arquivo);
        Path pastaProcesso = resolverDirProcesso(processo);
        Files.createDirectories(pastaProcesso);

        String original = arquivo.getOriginalFilename() == null ? "anexo" : arquivo.getOriginalFilename();
        String nomePadrao = NomePadraoAnexo.gerar(processo, tipo, original, LocalDate.now());
        String nomeFinal = nomeArquivoUnico(pastaProcesso, nomePadrao);
        Path destino = pastaProcesso.resolve(nomeFinal);

        try (InputStream in = arquivo.getInputStream()) {
            Files.copy(in, destino);
        }

        Anexo anexo = new Anexo();
        anexo.setProcesso(processo);
        anexo.setTipo(tipo);
        anexo.setDescricao(descricao);
        anexo.setNomeArquivo(nomeFinal);
        anexo.setContentType(arquivo.getContentType());
        anexo.setTamanhoBytes(arquivo.getSize());
        anexo.setCaminhoArmazenado(raiz.relativize(destino).toString());
        return anexoRepository.save(anexo);
    }

    /**
     * Salva um arquivo a partir de bytes (ex.: Oficio/Relatorio Final gerados
     * na decisao). Aplica o NOME PADRAO, exceto para {@code SOLICITACAO_AVALIADOR},
     * cujo nome oficial ("Processo CET-RS NN-AAAA - Paciente X.X.X.pdf", so
     * iniciais) e definido por {@code SolicitacaoAvaliadorService.nomeArquivoOficial}
     * e nao deve ser sobrescrito (imparcialidade + convencao ja documentada).
     */
    @Transactional
    public Anexo salvarBytes(Processo processo, TipoAnexo tipo, String descricao,
                             String nomeArquivo, String contentType, byte[] dados) throws IOException {
        Path pastaProcesso = resolverDirProcesso(processo);
        Files.createDirectories(pastaProcesso);
        String nomeDesejado = (tipo == TipoAnexo.SOLICITACAO_AVALIADOR)
                ? nomeArquivo
                : NomePadraoAnexo.gerar(processo, tipo, nomeArquivo, LocalDate.now());
        String nomeFinal = nomeArquivoUnico(pastaProcesso, nomeDesejado);
        Path destino = pastaProcesso.resolve(nomeFinal);
        Files.write(destino, dados);

        Anexo anexo = new Anexo();
        anexo.setProcesso(processo);
        anexo.setTipo(tipo);
        anexo.setDescricao(descricao);
        anexo.setNomeArquivo(nomeFinal);
        anexo.setContentType(contentType);
        anexo.setTamanhoBytes((long) dados.length);
        anexo.setCaminhoArmazenado(raiz.relativize(destino).toString());
        return anexoRepository.save(anexo);
    }

    /**
     * Salva um TEXTO digitado por um usuario como anexo {@code .txt} (UTF-8)
     * do processo, pelo MESMO pipeline dos demais anexos (nome padrao,
     * unicidade na pasta, registro em {@code anexo}) - nao existe schema
     * proprio para "resposta em texto".
     *
     * <p>Usado pelos dois lados da informacao complementar: a resposta que o
     * SOLICITANTE digita no portal ({@code INFO_COMPLEMENTAR}) e o texto que
     * o OPERADOR redige para os avaliadores
     * ({@code INFO_COMPLEMENTAR_AVALIADOR}). Passa por
     * {@link #salvarBytes} de proposito: {@code .txt} nao esta (nem deve
     * estar) na allowlist de UPLOAD ({@link #EXTENSOES_PERMITIDAS}), que
     * existe para barrar arquivo enviado de fora - aqui o conteudo e uma
     * string gerada pelo proprio sistema, sem arquivo recebido.</p>
     *
     * @param nomeBase nome logico do arquivo (a extensao {@code .txt} e
     *                 acrescentada se faltar); o nome final segue o padrao de
     *                 {@link NomePadraoAnexo} e nunca inclui o nome do paciente
     */
    @Transactional
    public Anexo salvarTexto(Processo processo, TipoAnexo tipo, String descricao,
                             String nomeBase, String texto) throws IOException {
        if (texto == null || texto.isBlank()) {
            throw new IllegalArgumentException("Texto vazio.");
        }
        String nome = (nomeBase == null || nomeBase.isBlank()) ? "texto.txt" : nomeBase.trim();
        if (!nome.toLowerCase(Locale.ROOT).endsWith(".txt")) {
            nome = nome + ".txt";
        }
        return salvarBytes(processo, tipo, descricao, nome,
            "text/plain; charset=UTF-8", texto.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Conteudo textual de um anexo {@code .txt} gravado por
     * {@link #salvarTexto}, para exibicao INLINE na tela (mais legivel que
     * obrigar o usuario a baixar um arquivo de texto). Devolve {@code null}
     * — nunca lanca — quando o anexo nao e texto, e maior que
     * {@link #TEXTO_INLINE_MAX_BYTES} ou o arquivo sumiu do disco: nesses
     * casos a tela cai no link de download de sempre.
     */
    public String lerTextoInline(Anexo anexo) {
        if (anexo == null || anexo.getNomeArquivo() == null
                || !anexo.getNomeArquivo().toLowerCase(Locale.ROOT).endsWith(".txt")) {
            return null;
        }
        try {
            Path arquivo = resolverArquivo(anexo);
            if (!Files.isReadable(arquivo) || Files.size(arquivo) > TEXTO_INLINE_MAX_BYTES) {
                return null;
            }
            return Files.readString(arquivo, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Teto de tamanho para exibir um .txt inline na tela (acima disso, so download). */
    private static final long TEXTO_INLINE_MAX_BYTES = 64 * 1024;

    /**
     * Remove os anexos de um tipo de um processo, EXCETO o informado em
     * {@code manterId}. Usado por "substituir": salva o novo anexo primeiro
     * e so entao remove os antigos, chamando este metodo com o id do que
     * acabou de ser criado - assim, se o save() do novo tivesse falhado, os
     * antigos nunca seriam tocados (evita o processo ficar sem nenhum anexo
     * daquele tipo em caso de falha no meio do caminho).
     *
     * <p><b>Recebe o {@code Processo} (nao so o id) desde a correcao de
     * 2026-07-29.</b> {@code anexoRepository.delete(a)} apaga a linha do
     * banco e marca aquela instancia de {@code Anexo} como REMOVIDA na
     * sessao/persistence context atual, mas nunca tirava essa mesma instancia
     * da colecao {@code Processo.anexos} em memoria. Como
     * {@code Processo.anexos} e {@code cascade = CascadeType.ALL} (inclui
     * MERGE), um {@code processoRepository.save(processo)} chamado logo
     * depois NA MESMA transacao (ex.: {@code RegistroEnvioService.registrar})
     * cascateava merge para a colecao inteira - inclusive o {@code Anexo} que
     * acabou de ser deletado aqui, ainda presente na lista - e o Hibernate
     * recusava com {@code ObjectDeletedException: deleted instance passed to
     * merge}. Bug real reportado em 2026-07-29 (500 ao registrar o envio de
     * um processo pela segunda vez, ex.: apos o ADMIN reabrir).
     *
     * <p>Corrigido chamando {@link Processo#removerAnexo(Anexo)} logo apos
     * cada {@code delete()}, que tira a instancia da colecao SEM reatribuir a
     * referencia da lista (ver javadoc daquele metodo - reatribuir quebra o
     * orphan-removal, outro bug real ja documentado em
     * {@code ProcessoAnexoController.substituirAnexo}).
     *
     * <p><b>Nem todo chamador tem o {@code Processo} preso a uma sessao
     * aberta.</b> {@code RegistroEnvioService}/{@code DecisaoFinalService}
     * chamam este metodo dentro da MESMA transacao em que o {@code Processo}
     * foi carregado (a colecao continua acessivel), mas
     * {@code ProcessoAnexoController.substituirAnexo} chama de dentro de um
     * metodo SEM transacao de controller (cada chamada abre a sua propria,
     * de proposito - ver javadoc da classe), entao o {@code Processo} recebido
     * ja esta DESANEXADO de uma sessao ja fechada quando chega aqui. Tentar
     * remover da colecao nesse caso lanca
     * {@code LazyInitializationException} (a colecao lazy nao pode ser
     * carregada sem sessao) - inofensivo de ignorar: sem sessao aberta essa
     * mesma instancia de {@code Processo} nao vai ser reaproveitada num merge
     * cascade dentro DESTA chamada mesmo (os 3 uploads que usam
     * {@code substituirAnexo} nunca salvam o {@code Processo} de novo depois),
     * entao nao ha risco real de reproduzir o bug original nesse caminho.
     */
    @Transactional
    public void removerAntigosDoTipo(Processo processo, TipoAnexo tipo, Long manterId) {
        for (Anexo a : anexoRepository.findByProcessoIdAndTipo(processo.getId(), tipo)) {
            if (a.getId().equals(manterId)) {
                continue;
            }
            try {
                Files.deleteIfExists(resolverArquivo(a));
            } catch (RuntimeException | IOException ignored) {
                // best-effort
            }
            anexoRepository.delete(a);
            try {
                processo.removerAnexo(a);
            } catch (org.hibernate.LazyInitializationException ignored) {
                // Processo desanexado (sessao ja fechada) - ver javadoc acima.
            }
        }
    }

    /**
     * Resolve o arquivo fisico do anexo. Tenta primeiro o caminho gravado no banco
     * (que pode ser relativo a pasta legivel ou a pasta antiga "processo-{id}").
     * Como o {@code caminhoArmazenado} e sempre relativo a raiz, a resolucao ja
     * cobre ambos os casos - este metodo existe para clareza e eventual extensao.
     */
    public Path resolverArquivo(Anexo anexo) {
        if (anexo == null) {
            throw new IllegalArgumentException("Anexo invalido.");
        }
        String caminho = anexo.getCaminhoArmazenado();
        if (caminho == null || caminho.isBlank()) {
            throw new IllegalArgumentException("Caminho de anexo invalido (sem caminho gravado).");
        }
        // Normaliza \ para / ANTES de resolver: no Linux (producao) a barra
        // invertida nao e separador de caminho, entao um valor gravado como
        // ..\..\Windows\win.ini viraria so um nome de arquivo literal e nunca
        // escaparia da raiz - a checagem abaixo so pega esse ataque em
        // qualquer SO se a barra invertida for tratada como separador antes.
        String caminhoNormalizado = caminho.replace('\\', '/');
        Path resolvido = raiz.resolve(caminhoNormalizado).normalize();
        // Defesa em profundidade: garante que o caminho resolvido continua
        // dentro da raiz de anexos, mesmo que caminhoArmazenado seja corrompido
        // (nunca deveria escapar, pois e gravado pelo proprio sistema).
        if (!resolvido.startsWith(raiz)) {
            throw new IllegalArgumentException("Caminho de anexo invalido (fora da area de armazenamento).");
        }
        return resolvido;
    }

    public Anexo buscar(Long id) {
        return anexoRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Anexo nao encontrado: " + id));
    }

    /**
     * Ultimo anexo de um tipo para o processo (o mais recente, pela ordem
     * natural da consulta - so deveria haver um por tipo apos uma
     * substituicao bem sucedida, mas usa o ultimo por seguranca). Retorna
     * null se nao existir nenhum.
     */
    public Anexo buscarUltimoPorTipo(Long processoId, TipoAnexo tipo) {
        var anexos = anexoRepository.findByProcessoIdAndTipo(processoId, tipo);
        return anexos.isEmpty() ? null : anexos.get(anexos.size() - 1);
    }

    /** Remove um anexo (arquivo em disco + registro no banco). Retorna o id do processo. */
    @Transactional
    public Long excluir(Long anexoId) {
        Anexo a = buscar(anexoId);
        Long processoId = a.getProcesso().getId();
        try {
            Files.deleteIfExists(resolverArquivo(a));
        } catch (RuntimeException | IOException ignored) {
            // best-effort
        }
        anexoRepository.delete(a);
        return processoId;
    }

    /**
     * Remove a pasta de anexos de um processo (usado ao excluir o processo).
     * Tenta remover tanto a pasta pelo ID legado ("processo-{id}") quanto a
     * pasta pelo nome legivel, se existirem.
     */
    public void removerPastaProcesso(Processo processo) {
        removerPasta(raiz.resolve("processo-" + processo.getId()).normalize());
        removerPasta(resolverDirProcesso(processo).normalize());
    }

    private void removerPasta(Path pasta) {
        try {
            if (Files.exists(pasta)) {
                try (var paths = Files.walk(pasta)) {
                    paths.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
                }
            }
        } catch (IOException ignored) {
            // best-effort: metadados ja removidos do banco
        }
    }
}
