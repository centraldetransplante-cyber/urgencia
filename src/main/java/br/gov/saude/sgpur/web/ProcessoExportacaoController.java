package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.service.AuditoriaService;
import br.gov.saude.sgpur.service.ExportacaoProcessoService;
import br.gov.saude.sgpur.service.ExportacaoProcessoService.Dossie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * "Exportar processo completo" (dossie): baixa um ZIP com TODA a movimentacao
 * do processo. Ao descompactar surge UMA pasta
 * {@code "<Paciente> - Processo CET-RS NN-AAAA"} com o Relatorio Final, o
 * resumo em texto e todos os anexos.
 *
 * <p><strong>Permissoes:</strong> a rota fica sob {@code /processos/**}, que o
 * {@code SecurityConfig} restringe a ADMIN e OPERADOR. AVALIADOR e SOLICITANTE
 * recebem 403 - o pacote contem o NOME COMPLETO do paciente e quebraria a
 * imparcialidade do julgamento.</p>
 *
 * <p><strong>Processo encerrado NAO bloqueia:</strong> e uma leitura (GET),
 * justamente o caso de uso principal (arquivar o processo ja decidido).</p>
 *
 * <p><strong>open-in-view=false:</strong> este controller NAO e
 * {@code @Transactional}. O dossie e materializado por
 * {@link ExportacaoProcessoService#montarDossie(Long)} (transacao propria) e so
 * depois o {@link StreamingResponseBody} escreve os bytes - que roda fora de
 * qualquer transacao/sessao do Hibernate.</p>
 */
@Controller
@RequestMapping("/processos")
public class ProcessoExportacaoController {

    private final ExportacaoProcessoService exportacaoService;
    private final AuditoriaService auditoria;

    public ProcessoExportacaoController(ExportacaoProcessoService exportacaoService,
                                        AuditoriaService auditoria) {
        this.exportacaoService = exportacaoService;
        this.auditoria = auditoria;
    }

    @GetMapping("/{id}/exportar")
    public ResponseEntity<StreamingResponseBody> exportar(@PathVariable Long id) {
        // Materializa TUDO antes de comecar o stream (ver javadoc da classe).
        Dossie dossie = exportacaoService.montarDossie(id);

        // Sem dossie.nomePasta() aqui: ela leva o NOME COMPLETO do paciente
        // (bug real corrigido em 2026-08-03 - recaida do mesmo padrao ja
        // endurecido em 2026-07-28 para PROCESSO_CADASTRADO, que usa
        // Iniciais.de() por causa exatamente disto: /auditoria e ADMIN-only,
        // mas nao deveria expor nome completo). O id do processo ja basta
        // para localizar o registro na auditoria.
        auditoria.registrar("PROCESSO_EXPORTADO",
            "Dossie completo (ZIP) do processo id " + id);

        StreamingResponseBody corpo = out -> {
            try {
                exportacaoService.escreverZip(dossie, out);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };

        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(dossie.nomeArquivoZip()))
            .body(corpo);
    }

    /**
     * {@code attachment} com nome ASCII de fallback (navegadores antigos) e
     * {@code filename*} em UTF-8 (RFC 5987) para preservar acentos do nome do
     * paciente.
     */
    static String contentDisposition(String nomeArquivo) {
        String ascii = nomeArquivo.replaceAll("[^A-Za-z0-9._ -]", "_");
        String utf8 = URLEncoder.encode(nomeArquivo, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + utf8;
    }
}
