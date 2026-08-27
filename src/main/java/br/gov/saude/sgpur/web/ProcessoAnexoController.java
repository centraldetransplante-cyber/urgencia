package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.service.AnexoStorageService;
import br.gov.saude.sgpur.service.AuditoriaService;
import br.gov.saude.sgpur.service.GeminiService;
import br.gov.saude.sgpur.service.OficioService;
import br.gov.saude.sgpur.service.ProcessoService;
import br.gov.saude.sgpur.service.ProcessoValidator;
import br.gov.saude.sgpur.service.RelatorioService;
import br.gov.saude.sgpur.service.RotuloProcesso;
import br.gov.saude.sgpur.web.dto.IaTextoResponse;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Passos 5 e 6 do fluxo (oficio/comprovante e resposta ao solicitante) e o
 * gerenciamento de anexos: upload/download/remocao e a geracao de PDFs
 * (oficio, relatorio).
 *
 * <p>
 * <b>Sem @Transactional de nivel de classe (removido em 2026-07-29).</b>
 * Uma transacao aberta pelo controller e compartilhada (REQUIRED) com todo
 * servico {@code @Transactional} chamado dentro dela; quando um deles lanca
 * dentro de um {@code try/catch} do metodo, a transacao inteira e marcada como
 * rollback-only, o {@code catch} devolve o flash amigavel e o commit final
 * estoura {@code UnexpectedRollbackException} (500 cru). Aqui isso atingia os
 * QUATRO uploads e a confirmacao da resposta ao solicitante: um arquivo com
 * extensao nao permitida (ou um comprovante SNT faltando) devolvia "Erro
 * interno" em vez da mensagem de negocio. Os metodos que nao tem esse
 * {@code try/catch} mas precisam de sessao aberta ({@link #excluirAnexo})
 * declaram {@code @Transactional} no proprio metodo; os
 * GET de download/PDF ja tinham {@code @Transactional(readOnly = true)}
 * proprio.
 */
@Controller
@RequestMapping("/processos")
public class ProcessoAnexoController {

    private final ProcessoService processoService;
    private final ProcessoValidator validator;
    private final AnexoStorageService anexoStorage;
    private final AuditoriaService auditoria;
    private final OficioService oficioService;
    private final RelatorioService relatorioService;
    private final GeminiService geminiService;

    public ProcessoAnexoController(ProcessoService processoService,
            ProcessoValidator validator,
            AnexoStorageService anexoStorage,
            AuditoriaService auditoria,
            OficioService oficioService,
            RelatorioService relatorioService,
            GeminiService geminiService) {
        this.processoService = processoService;
        this.validator = validator;
        this.anexoStorage = anexoStorage;
        this.auditoria = auditoria;
        this.oficioService = oficioService;
        this.relatorioService = relatorioService;
        this.geminiService = geminiService;
    }

    /**
     * Substitui um anexo de um tipo especifico: salva o novo arquivo PRIMEIRO
     * e so remove o(s) existente(s) DEPOIS que o novo foi gravado com sucesso
     * (arquivo em disco + registro no banco). A ordem importa: o delete do
     * arquivo antigo em disco (removerPorTipo) nao e transacional - se fosse
     * feito antes e o salvar() do novo falhasse por qualquer motivo nao
     * coberto pelo catch do chamador (ex.: erro de banco no save(), nao so
     * IllegalArgumentException/IOException), o processo ficaria SEM NENHUM
     * anexo daquele tipo (nem o antigo, que ja foi apagado do disco, nem o
     * novo) - grave quando o tipo e um documento critico como o comprovante
     * SNT ou o oficio de indeferimento.
     */
    private void substituirAnexo(Processo p, TipoAnexo tipo, MultipartFile arquivo)
            throws IOException {
        substituirAnexo(p, tipo, tipo.getDescricao(), arquivo);
    }

    private void substituirAnexo(Processo p, TipoAnexo tipo, String descricao, MultipartFile arquivo)
            throws IOException {
        // Salva o novo anexo primeiro (se falhar, os antigos estao intactos), depois
        // remove os antigos via repositorio (removerAntigosDoTipo) - mesmo padrao de
        // DecisaoFinalService/RegistroEnvioService. NUNCA reatribuir p.setAnexos(...)
        // com uma nova List aqui: anexos e orphanRemoval=true, e trocar a referencia
        // da colecao gerenciada pelo Hibernate por uma ArrayList nova quebra o
        // orphan-removal no flush (HibernateException "collection with orphan
        // deletion was no longer referenced by the owning entity instance") - bug
        // real em producao no upload do Comprovante SNT, 2026-07-28. Desde
        // 2026-07-29, removerAntigosDoTipo recebe o Processo (nao so o id) e tira
        // cada anexo excluido da colecao em memoria via Processo.removerAnexo
        // (remove() in-place, mesma garantia acima) - sem isso, um merge cascade
        // posterior via processoService.salvar(p) recusava com ObjectDeletedException
        // ao encontrar, na colecao, a instancia que acabou de ser deletada aqui.
        Anexo novo = anexoStorage.salvar(p, tipo, descricao, arquivo);
        anexoStorage.removerAntigosDoTipo(p, tipo, novo.getId());
    }

    /*
     * O endpoint POST /{id}/finalizacao (campos "data de emissao/envio do
     * oficio" e "data de envio ao SNT") FOI REMOVIDO em 2026-08-04.
     *
     * Regra do sistema, decidida pelo usuario: a data de um ato registrado por
     * anexo e o MOMENTO DO ANEXO, gravada pelo relogio do servidor - nunca um
     * <input type="date"> na tela, que aceitava data retroativa (ou futura).
     * Num processo administrativo isso e inadmissivel: a data precisa ser a do
     * ato real, nao a que alguem escolheu digitar. Hoje:
     *
     * - dataEmissaoOficio -> gravada ao anexar/gerar o oficio
     * (DecisaoFinalService na decisao, uploadOficio no upload manual);
     * - dataEnvioSnt -> gravada ao anexar o comprovante SNT;
     * - dataEnvioOficio -> gravada quando a resposta com o oficio sai de
     * fato para o solicitante (ProcessoService.finalizarResposta).
     *
     * Some junto a regeracao do oficio "com as datas novas" que este endpoint
     * disparava: sem edicao de data, nao ha divergencia possivel entre a tela
     * e o PDF anexado.
     */

    /**
     * Upload do Oficio de Indeferimento na aba Finalizacao (so para processos
     * INDEFERIDOS).
     *
     * <p>
     * Sem {@code @Transactional} (vale para os 4 uploads deste controller):
     * o {@code try/catch} envolve {@code anexoStorage.salvar/salvarBytes},
     * metodos {@code @Transactional} que lancam {@code IllegalArgumentException}
     * para arquivo vazio ou extensao fora da allowlist. Com transacao de
     * controller esse caminho previsto virava 500. Alem disso, manter cada
     * escrita de {@code substituirAnexo} em sua propria transacao preserva a
     * garantia documentada la ("salva o novo antes de remover o antigo").
     */
    @PostMapping("/{id}/oficio-upload")
    public String uploadOficio(@PathVariable Long id,
            @RequestParam("arquivo") MultipartFile arquivo,
            RedirectAttributes ra) {
        Processo p = processoService.buscar(id);
        if (p.getStatus() != StatusProcesso.INDEFERIDO) {
            ra.addFlashAttribute("erro", "Upload de oficio so e permitido para processos Indeferidos.");
            return "redirect:/processos/" + id + "#finalizacao";
        }
        try {
            substituirAnexo(p, TipoAnexo.OFICIO_INDEFERIMENTO, arquivo);
            // A data de emissao do oficio e o momento em que ESTE documento
            // entrou no sistema - o upload manual substitui o oficio anterior,
            // entao a data acompanha o arquivo novo. Nunca digitada: ver
            // ProcessoService.registrarDataEmissaoOficio.
            processoService.registrarDataEmissaoOficio(id);
            auditoria.registrar("ANEXO_ADICIONADO",
                    "Processo " + p.getNumero() + " - " + TipoAnexo.OFICIO_INDEFERIMENTO.getDescricao());
            ra.addFlashAttribute("msg",
                    "Oficio de indeferimento anexado (data de emissao registrada com a data de hoje).");
        } catch (IllegalArgumentException | IOException e) {
            ra.addFlashAttribute("erro", "Falha ao anexar o oficio: " + e.getMessage());
        }
        return "redirect:/processos/" + id + "#finalizacao";
    }

    /** Upload do comprovante de envio da resposta ao solicitante (passo 6). */
    @PostMapping("/{id}/comprovante-envio-solicitante")
    public String uploadComprovanteEnvioSolicitante(@PathVariable Long id,
            @RequestParam("arquivo") MultipartFile arquivo,
            RedirectAttributes ra) {
        Processo p = processoService.buscar(id);
        if (!p.getStatus().isFinalizado()) {
            ra.addFlashAttribute("erro",
                    "Upload do comprovante de envio ao solicitante so e permitido apos a decisao "
                            + "(Deferido/Indeferido/Cancelado).");
            return "redirect:/processos/" + id + "#finalizacao";
        }
        try {
            substituirAnexo(p, TipoAnexo.COMPROVANTE_ENVIO_SOLICITANTE,
                    "Comprovante de envio da resposta ao solicitante", arquivo);
            auditoria.registrar("ANEXO_ADICIONADO",
                    "Processo " + p.getNumero() + " - " + TipoAnexo.COMPROVANTE_ENVIO_SOLICITANTE.getDescricao());
            ra.addFlashAttribute("msg", "Comprovante de envio ao solicitante anexado.");
        } catch (IllegalArgumentException | IOException e) {
            ra.addFlashAttribute("erro", "Falha ao anexar o comprovante: " + e.getMessage());
        }
        return "redirect:/processos/" + id + "#finalizacao";
    }

    /**
     * Upload do Comprovante SNT na aba Finalizacao (so para processos DEFERIDOS).
     */
    @PostMapping("/{id}/comprovante-snt")
    public String uploadComprovanteSnt(@PathVariable Long id,
            @RequestParam("arquivo") MultipartFile arquivo,
            RedirectAttributes ra) {
        Processo p = processoService.buscar(id);
        if (p.getStatus() != StatusProcesso.DEFERIDO) {
            ra.addFlashAttribute("erro", "Upload do comprovante SNT so e permitido para processos Deferidos.");
            return "redirect:/processos/" + id + "#finalizacao";
        }
        // Paciente PREEMPTIVO (2026-08-27): nao existe comprovante SNT nenhum
        // (ainda nao esta na lista de espera do SNT) - a tela ja esconde este
        // formulario para preemptivo (processos/detalhe.html), mas o endpoint
        // tambem precisa recusar um POST direto, senao um anexo com a
        // descricao "Comprovante de insercao da urgencia renal no SNT"
        // (redacao que so faz sentido para urgencia renal comum) poderia
        // acabar preso a um processo preemptivo.
        if (p.isPreemptivo()) {
            ra.addFlashAttribute("erro",
                    "Processo preemptivo nao possui comprovante SNT - a decisao ja autoriza a "
                            + "inscricao na lista de espera, sem anexo de comprovante.");
            return "redirect:/processos/" + id + "#finalizacao";
        }
        try {
            substituirAnexo(p, TipoAnexo.COMPROVANTE_SNT,
                    "Comprovante de insercao da urgencia renal no SNT", arquivo);
            // Data de envio ao SNT = momento do anexo (nunca digitada) - ver
            // ProcessoService.registrarDataEmissaoOficio para a regra.
            processoService.registrarDataEnvioSnt(id);
            auditoria.registrar("ANEXO_ADICIONADO",
                    "Processo " + p.getNumero() + " - " + TipoAnexo.COMPROVANTE_SNT.getDescricao());
            ra.addFlashAttribute("msg",
                    "Comprovante SNT anexado (data de envio ao SNT registrada com a data de hoje).");
        } catch (IllegalArgumentException | IOException e) {
            ra.addFlashAttribute("erro", "Falha ao anexar o comprovante SNT: " + e.getMessage());
        }
        return "redirect:/processos/" + id + "#finalizacao";
    }

    @PostMapping("/{id}/anexos")
    public String anexar(@PathVariable Long id,
            @RequestParam TipoAnexo tipo,
            @RequestParam(required = false) String descricao,
            @RequestParam("arquivo") MultipartFile arquivo,
            RedirectAttributes ra) {
        Processo p = processoService.buscar(id);
        if (validator.edicaoBloqueada(p)) {
            ra.addFlashAttribute("erro", ProcessoValidator.MSG_ENCERRADO);
            return "redirect:/processos/" + id + "#anexos";
        }
        try {
            anexoStorage.salvar(p, tipo, descricao, arquivo);
            auditoria.registrar("ANEXO_ADICIONADO",
                    "Processo " + p.getNumero() + " - " + tipo.getDescricao());
            ra.addFlashAttribute("msg", "Anexo enviado.");
        } catch (IllegalArgumentException | IOException e) {
            ra.addFlashAttribute("erro", "Falha ao anexar: " + e.getMessage());
        }
        return "redirect:/processos/" + id + "#anexos";
    }

    /**
     * Remove um anexo. {@code @Transactional} proprio porque as guardas navegam
     * associacoes LAZY do anexo ({@code getProcesso().getStatus()} e
     * {@code getParecer().getOrigem()}), o que exige sessao aberta com
     * {@code open-in-view: false}. Nao ha {@code try/catch} em volta de servico
     * transacional aqui, entao a transacao unica e segura (ver javadoc da classe).
     */
    @PostMapping("/anexos/{anexoId}/excluir")
    @Transactional
    public String excluirAnexo(@PathVariable Long anexoId, RedirectAttributes ra) {
        Anexo anexoParaExcluir = anexoStorage.buscar(anexoId);
        // Processo encerrado: nenhum anexo pode ser removido (edicao travada).
        if (validator.edicaoBloqueada(anexoParaExcluir.getProcesso())) {
            Long pid = anexoParaExcluir.getProcesso().getId();
            ra.addFlashAttribute("erro", ProcessoValidator.MSG_ENCERRADO);
            return "redirect:/processos/" + pid + "#anexos";
        }
        Long processoId = anexoStorage.excluir(anexoId);
        auditoria.registrar("ANEXO_REMOVIDO", "Processo id " + processoId);
        ra.addFlashAttribute("msg", "Anexo removido.");
        return "redirect:/processos/" + processoId + "#anexos";
    }

    @GetMapping("/{id}/relatorio")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> relatorio(@PathVariable Long id) {
        try {
            Processo p = processoService.buscar(id);
            byte[] pdf = relatorioService.gerar(p);
            String nome = "relatorio-processo-" + p.getNumero().replace("/", "-") + ".pdf";
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + nome + "\"")
                    .body(pdf);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * Previa do Oficio de Indeferimento gerada sob demanda (nao e o anexo).
     *
     * <p>
     * So para processos INDEFERIDOS (mesma guarda de {@link #uploadOficio},
     * desde 2026-08-04): antes esta URL gerava um "Oficio de Indeferimento"
     * para qualquer processo — inclusive um Deferido —, produzindo um
     * documento que contradiz a decisao do processo. Quem quer o oficio que
     * de fato foi enviado deve baixar o anexo
     * ({@code /processos/anexos/{id}/download}), que e o que a tela oferece.
     * </p>
     */
    @GetMapping("/{id}/oficio")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> oficio(@PathVariable Long id) {
        Processo p;
        try {
            p = processoService.buscar(id);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        // Guarda FORA do try/catch de baixo de proposito: ResponseStatusException
        // tambem e RuntimeException e seria reconvertida em 500 la dentro.
        if (p.getStatus() != StatusProcesso.INDEFERIDO) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Oficio de indeferimento so existe para processos Indeferidos.");
        }
        try {
            byte[] pdf = oficioService.gerar(p);
            String nome = "oficio-indeferimento-" + p.getNumero().replace("/", "-") + ".pdf";
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + nome + "\"")
                    .body(pdf);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    /**
     * RASCUNHO EDITAVEL do oficio de indeferimento (.rtf, abre no Word).
     *
     * <p>
     * Desde 2026-08-04 o sistema NAO gera mais o oficio como anexo: cada
     * caso tem particularidade de redacao, entao o oficio precisa ser editavel
     * e <b>o documento de registro e sempre o que o operador anexa</b>
     * ({@link #uploadOficio}). Este endpoint entrega so o ponto de partida,
     * ja preenchido com numero do oficio, cidade/data, paciente e motivo -
     * baixar o rascunho nao anexa nada ao processo nem muda data alguma.
     *
     * <p>
     * Mesma guarda de status do PDF acima: rascunho de indeferimento nao
     * faz sentido em processo que nao foi indeferido.
     */
    @GetMapping("/{id}/oficio-rascunho")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> oficioRascunho(@PathVariable Long id) {
        Processo p;
        try {
            p = processoService.buscar(id);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        if (p.getStatus() != StatusProcesso.INDEFERIDO) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Oficio de indeferimento so existe para processos Indeferidos.");
        }
        byte[] rtf = oficioService.gerarRascunhoRtf(p);
        String nome = "oficio-rascunho-" + p.getNumero().replace("/", "-") + ".rtf";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/rtf"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + nome + "\"")
                .body(rtf);
    }

    @GetMapping("/anexos/{anexoId}/download")
    @Transactional(readOnly = true)
    public ResponseEntity<Resource> baixarAnexo(@PathVariable Long anexoId) {
        try {
            Anexo anexo = anexoStorage.buscar(anexoId);
            Path arquivo = anexoStorage.resolverArquivo(anexo);
            Resource resource = new UrlResource(arquivo.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                return ResponseEntity.notFound().build();
            }
            String contentType = anexo.getContentType() != null
                    ? anexo.getContentType()
                    : MediaType.APPLICATION_OCTET_STREAM_VALUE;
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + anexo.getNomeArquivo() + "\"")
                    .body(resource);
        } catch (java.net.MalformedURLException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Erro ao processar o arquivo.");
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Anexo nao encontrado.");
        }
    }

    /**
     * Resume, via IA, o conteudo textual de um anexo PDF (ex.: documento
     * clinico anexado). Extrai o texto localmente (OpenPDF) e envia so o
     * texto extraido para a API - o arquivo em si nao e enviado a terceiros.
     */
    @GetMapping("/anexos/{anexoId}/resumo-ia")
    @ResponseBody
    @Transactional(readOnly = true)
    public IaTextoResponse resumoAnexoIa(@PathVariable Long anexoId) {
        if (!geminiService.isDisponivel()) {
            return IaTextoResponse.erro("Assistencia por IA nao configurada.");
        }
        Anexo anexo;
        try {
            anexo = anexoStorage.buscar(anexoId);
        } catch (RuntimeException e) {
            return IaTextoResponse.erro("Anexo nao encontrado.");
        }
        if (anexo.getContentType() == null
                || !anexo.getContentType().toLowerCase(java.util.Locale.ROOT).contains("application/pdf")) {
            return IaTextoResponse.erro("Resumo por IA disponivel apenas para anexos em PDF.");
        }
        String texto;
        try {
            byte[] bytes = Files.readAllBytes(anexoStorage.resolverArquivo(anexo));
            com.lowagie.text.pdf.PdfReader reader = new com.lowagie.text.pdf.PdfReader(bytes);
            var extractor = new com.lowagie.text.pdf.parser.PdfTextExtractor(reader);
            StringBuilder sb = new StringBuilder();
            int paginas = Math.min(reader.getNumberOfPages(), 20);
            for (int pagina = 1; pagina <= paginas; pagina++) {
                sb.append(extractor.getTextFromPage(pagina)).append('\n');
            }
            reader.close();
            texto = sb.toString();
        } catch (IOException e) {
            return IaTextoResponse.erro("Falha ao ler o PDF: " + e.getMessage());
        }
        if (texto.isBlank()) {
            return IaTextoResponse.erro("Nao foi possivel extrair texto deste PDF (pode ser uma imagem digitalizada).");
        }
        // Redige dados sensiveis do paciente antes de enviar a API externa
        // (Gemini/Google): documentos clinicos originais tipicamente contem
        // nome, RGCT, CPF e datas de nascimento - diferente do material aos
        // avaliadores (so iniciais), aqui nao ha necessidade de nenhum desses
        // dados para o resumo administrativo.
        texto = redigirDadosSensiveis(texto, anexo.getProcesso());
        // Limita o tamanho enviado a API (documentos muito longos sao truncados).
        String textoLimitado = texto.length() > 20000 ? texto.substring(0, 20000) : texto;
        String prompt = "Voce e um assistente administrativo de um orgao publico de saude do Brasil. "
                + "Resuma em ate 5 frases, em portugues do Brasil, o conteudo clinico/administrativo "
                + "do documento abaixo, destacando os pontos relevantes para analise de um pedido de "
                + RotuloProcesso.tipoPedido(anexo.getProcesso()) + ". Responda apenas com o resumo, sem introducao.\n\n"
                + "Documento:\n" + textoLimitado;
        return geminiService.perguntar(prompt)
                .map(IaTextoResponse::sucesso)
                .orElseGet(() -> IaTextoResponse.erro("Falha ao consultar a IA. Tente novamente."));
    }

    /**
     * Reforca a redacao de dados sensiveis do paciente antes de enviar o
     * texto extraido de um documento clinico a API externa do Gemini. O
     * {@code replaceAll} literal do nome completo (protecao original)
     * nao pega grafia diferente ("MARIA S. SILVA" quando o cadastro e
     * "Maria Silva"), entao aqui a redacao roda por TOKEN do nome (nome +
     * cada sobrenome separadamente), alem de padroes gerais que nao dependem
     * do cadastro exato: CPF, data (potencial data de nascimento) e o RGCT
     * do proprio processo (mesmo identificador usado em
     * {@link Processo#identificacao()}).
     */
    private String redigirDadosSensiveis(String texto, Processo processo) {
        String resultado = texto;

        // 1) Nome do paciente, token a token (nome + sobrenomes), com
        // word-boundary para nao redigir substring dentro de outra palavra
        // (ex.: nao trocar "Ana" dentro de "Anamnese"). Cobre abreviacoes
        // parciais do nome, ja que cada parte e tratada isoladamente.
        String pacienteNome = processo != null ? processo.getPacienteNome() : null;
        if (pacienteNome != null && !pacienteNome.isBlank()) {
            for (String token : pacienteNome.trim().split("\\s+")) {
                if (token.length() < 2) {
                    continue; // token de 1 letra (ex.: iniciais soltas) geraria falso-positivo demais
                }
                resultado = resultado.replaceAll(
                        "(?i)\\b" + java.util.regex.Pattern.quote(token) + "\\b", "[REDIGIDO]");
            }
        }

        // 2) RGCT do paciente, se conhecido no processo - mesmo identificador
        // sensivel usado em Processo.identificacao()/SolicitacaoOnline.identificacao().
        String pacienteRgct = processo != null ? processo.getPacienteRgct() : null;
        if (pacienteRgct != null && !pacienteRgct.isBlank()) {
            resultado = resultado.replaceAll(
                    "(?i)" + java.util.regex.Pattern.quote(pacienteRgct), "[REDIGIDO]");
        }

        // 3) CPF (com ou sem pontuacao) - documento pessoal, nunca relevante
        // para o resumo clinico/administrativo.
        resultado = resultado.replaceAll("\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}", "[REDIGIDO]");

        // 4) Datas no formato dd/mm/aaaa - cobre data de nascimento (o
        // padrao mais provavel de aparecer num documento clinico), que nao
        // e relevante para o resumo administrativo e nao vale o risco de
        // reidentificacao combinada com outros dados.
        resultado = resultado.replaceAll("\\d{2}/\\d{2}/\\d{4}", "[REDIGIDO]");

        return resultado;
    }
}
