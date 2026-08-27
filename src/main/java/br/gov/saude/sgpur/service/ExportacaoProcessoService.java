package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Anexo;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.service.dto.EtapaFluxo;
import br.gov.saude.sgpur.service.dto.RegraDecisao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * "Exportar processo completo" (dossie): empacota TODA a movimentacao de um
 * processo num unico ZIP que, ao ser descompactado, produz UMA pasta com o
 * nome do paciente + numero/ano do processo, contendo:
 *
 * <ul>
 *   <li>{@code Relatorio-Final.pdf} - reaproveita {@link RelatorioService};</li>
 *   <li>{@code Resumo-do-Processo.txt} - todos os dados cadastrais, os 3
 *       pareceres, a decisao e a linha do tempo do {@link FluxoProcessoService};</li>
 *   <li>{@code Anexos/} - copia de todos os anexos, com nome legivel.</li>
 * </ul>
 *
 * <strong>Armadilha do open-in-view=false:</strong> a escrita do ZIP acontece
 * num {@code StreamingResponseBody}, ou seja, FORA da transacao do controller.
 * Por isso {@link #montarDossie(Long)} roda em transacao propria e
 * MATERIALIZA tudo (bytes do relatorio, texto do resumo e os {@link Path}
 * fisicos dos anexos) num {@link Dossie} sem nenhum proxy LAZY, e so entao
 * {@link #escreverZip(Dossie, OutputStream)} e chamado ja fora da transacao.
 * Nada em {@code Dossie} pode voltar a referenciar entidade JPA.
 */
@Service
public class ExportacaoProcessoService {

    private static final Logger log = LoggerFactory.getLogger(ExportacaoProcessoService.class);

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    public static final String ARQUIVO_RELATORIO = "Relatorio-Final.pdf";
    public static final String ARQUIVO_RESUMO = "Resumo-do-Processo.txt";
    public static final String PASTA_ANEXOS = "Anexos";
    public static final String ARQUIVO_PROBLEMAS = "ANEXOS-COM-PROBLEMA.txt";

    private final ProcessoService processoService;
    private final RelatorioService relatorioService;
    private final FluxoProcessoService fluxoService;
    private final AnexoStorageService anexoStorage;

    public ExportacaoProcessoService(ProcessoService processoService,
                                     RelatorioService relatorioService,
                                     FluxoProcessoService fluxoService,
                                     AnexoStorageService anexoStorage) {
        this.processoService = processoService;
        this.relatorioService = relatorioService;
        this.fluxoService = fluxoService;
        this.anexoStorage = anexoStorage;
    }

    // ------------------------------------------------------------------
    // Modelo materializado (sem nenhuma entidade JPA dentro)
    // ------------------------------------------------------------------

    /** Um anexo ja resolvido: nome que tera dentro do ZIP + arquivo em disco. */
    public record ItemAnexo(String nomeNoZip, Path arquivo) {
    }

    /**
     * Pacote pronto para virar ZIP. Todos os campos ja estao carregados em
     * memoria/disco - nenhum proxy LAZY, para poder ser escrito fora da
     * transacao (ver javadoc da classe).
     */
    public record Dossie(String nomePasta, byte[] relatorioPdf, String resumo,
                         List<ItemAnexo> anexos) {

        /** Nome do arquivo baixado pelo navegador. */
        public String nomeArquivoZip() {
            return nomePasta + ".zip";
        }
    }

    // ------------------------------------------------------------------
    // Nome da pasta
    // ------------------------------------------------------------------

    /**
     * Nome da pasta unica do dossie:
     * {@code "<Nome Completo do Paciente> - Processo CET-RS <NN>-<AAAA>"}.
     * A barra do numero {@code NN/AAAA} vira traco (e separador de caminho) e
     * os caracteres proibidos no Windows sao removidos.
     */
    public static String nomePasta(Processo p) {
        String paciente = (p.getPacienteNome() == null || p.getPacienteNome().isBlank())
            ? "Paciente" : p.getPacienteNome();
        String numero = (p.getNumero() == null || p.getNumero().isBlank())
            ? "SN" : p.getNumero().replace('/', '-');
        String bruto = paciente + " - Processo CET-RS " + numero;
        String nome = sanitizar(bruto);
        return nome.isEmpty() ? "Processo CET-RS" : nome;
    }

    /**
     * Sanitiza um nome de arquivo/pasta para Windows: remove os caracteres
     * proibidos ({@code \ / : * ? " < > |}) e os de controle, colapsa espacos
     * repetidos e nao deixa ponto nem espaco no fim (o Explorer do Windows nao
     * aceita e o proprio ZIP fica estranho).
     */
    public static String sanitizar(String bruto) {
        if (bruto == null) {
            return "";
        }
        String s = bruto.replaceAll("[\\\\/:*?\"<>|]", "")
                        .replaceAll("[\\p{Cntrl}]", " ")
                        .replaceAll("\\s+", " ")
                        .trim();
        // Sem ponto/espaco no final (restricao do Windows).
        int fim = s.length();
        while (fim > 0 && (s.charAt(fim - 1) == '.' || s.charAt(fim - 1) == ' ')) {
            fim--;
        }
        s = s.substring(0, fim);
        if (s.length() > 150) {
            s = s.substring(0, 150).trim();
        }
        return s;
    }

    /**
     * Garante nome unico dentro do ZIP acrescentando " (2)", " (3)"... antes da
     * extensao. {@code usados} e atualizado.
     */
    static String nomeUnico(Set<String> usados, String desejado) {
        String candidato = desejado;
        if (!usados.contains(candidato.toLowerCase(java.util.Locale.ROOT))) {
            usados.add(candidato.toLowerCase(java.util.Locale.ROOT));
            return candidato;
        }
        int dot = desejado.lastIndexOf('.');
        String base = dot > 0 ? desejado.substring(0, dot) : desejado;
        String ext = dot > 0 ? desejado.substring(dot) : "";
        int n = 1;
        do {
            n++;
            candidato = base + " (" + n + ")" + ext;
        } while (usados.contains(candidato.toLowerCase(java.util.Locale.ROOT)));
        usados.add(candidato.toLowerCase(java.util.Locale.ROOT));
        return candidato;
    }

    // ------------------------------------------------------------------
    // Montagem (DENTRO da transacao)
    // ------------------------------------------------------------------

    /**
     * Carrega o processo e materializa tudo o que o ZIP precisa. Roda em
     * transacao propria justamente porque a escrita do ZIP (streaming) acontece
     * depois, sem sessao do Hibernate aberta.
     */
    @Transactional(readOnly = true)
    public Dossie montarDossie(Long processoId) {
        Processo p = processoService.buscar(processoId);

        // Toca as colecoes LAZY ainda dentro da transacao.
        List<Anexo> anexos = new ArrayList<>(p.getAnexos());
        List<Parecer> pareceres = new ArrayList<>(p.getPareceres());

        String nomePasta = nomePasta(p);
        byte[] relatorio = relatorioService.gerar(p);
        String resumo = montarResumo(p, pareceres, anexos);

        List<ItemAnexo> itens = new ArrayList<>();
        Set<String> usados = new HashSet<>();
        int indice = 0;
        for (Anexo a : anexos.stream().sorted(Comparator.comparing(
                Anexo::getDataUpload, Comparator.nullsLast(Comparator.naturalOrder()))).toList()) {
            indice++;
            String tipo = a.getTipo() != null ? a.getTipo().getDescricao() : "Anexo";
            String desejado = String.format("%02d - %s - %s", indice, sanitizar(tipo),
                sanitizar(a.getNomeArquivo() == null ? "arquivo" : a.getNomeArquivo()));
            String nomeNoZip = nomeUnico(usados, desejado);
            Path arquivo;
            try {
                arquivo = anexoStorage.resolverArquivo(a);
            } catch (RuntimeException e) {
                // caminho corrompido/fora da area de armazenamento: registra e segue
                log.warn("Anexo {} do processo {} com caminho invalido: {}",
                    a.getId(), processoId, e.getMessage());
                arquivo = null;
            }
            itens.add(new ItemAnexo(nomeNoZip, arquivo));
        }

        return new Dossie(nomePasta, relatorio, resumo, List.copyOf(itens));
    }

    // ------------------------------------------------------------------
    // Resumo em texto
    // ------------------------------------------------------------------

    private String montarResumo(Processo p, List<Parecer> pareceres, List<Anexo> anexos) {
        StringBuilder sb = new StringBuilder();
        linhaTitulo(sb, (p.isPreemptivo()
            ? "PROCESSO DE INSERCAO EM LISTA DE ESPERA RENAL (PREEMPTIVO)"
            : "PROCESSO DE URGENCIA RENAL") + " - RESUMO COMPLETO");
        sb.append("Gerado pelo SAUR em ").append(java.time.LocalDateTime.now().format(DATA_HORA))
          .append('\n')
          .append("Documento interno da equipe de Urgencia Renal (contem o nome completo do paciente).\n\n");

        linhaTitulo(sb, "1. IDENTIFICACAO");
        campo(sb, "Numero do processo", p.getNumero());
        campo(sb, "Ano", p.getAno());
        campo(sb, "Sequencial", p.getSequencial());
        campo(sb, "Situacao atual", p.getStatus() != null ? p.getStatus().getDescricao() : null);
        campo(sb, "Paciente (receptor)", p.getPacienteNome());
        campo(sb, "Registro RGCT / SNT", p.getPacienteRgct());
        campo(sb, "Data de nascimento", data(p.getPacienteDataNascimento()));
        campo(sb, "CPF", p.getPacienteCpf() != null ? CpfUtil.formatar(p.getPacienteCpf()) : null);
        campo(sb, "Sexo", p.getPacienteSexo() != null ? p.getPacienteSexo().getDescricao() : null);
        campo(sb, "Nome da mae", p.getPacienteNomeMae());
        sb.append('\n');

        linhaTitulo(sb, "2. SOLICITANTE");
        campo(sb, "Equipe / instituicao", p.getSolicitanteEquipe());
        campo(sb, "E-mail", p.getSolicitanteEmail());
        sb.append('\n');

        linhaTitulo(sb, "3. DATAS");
        campo(sb, p.isPreemptivo() ? "Data da solicitacao" : "Data de solicitacao da urgencia renal",
            data(p.getDataSituacaoEspecial()));
        campo(sb, "Data de cadastro no sistema", dataHora(p.getDataCadastro()));
        campo(sb, "Data da decisao", dataHora(p.getDataDecisao()));
        campo(sb, "Data de emissao do oficio", data(p.getDataEmissaoOficio()));
        campo(sb, "Data de envio do oficio", data(p.getDataEnvioOficio()));
        sb.append('\n');

        linhaTitulo(sb, "4. AVALIADORES E PARECERES");
        if (pareceres.isEmpty()) {
            sb.append("Nenhum avaliador definido para este processo.\n");
        }
        int i = 0;
        for (Parecer par : pareceres) {
            i++;
            sb.append("Avaliador ").append(i).append(": ")
              .append(par.getMembro() != null ? par.getMembro().getRotulo() : "-").append('\n');
            campo(sb, "  Resultado", par.getResultado() != null
                ? par.getResultado().getDescricao()
                : (par.isImpedido() ? "Impedido (conflito de interesse)" : "Pendente"));
            campo(sb, "  Data de envio", data(par.getDataEnvio()));
            campo(sb, "  Data da resposta", data(par.getDataResposta()));
            // Origem so e preenchida quando o parecer ja foi votado (unico
            // caminho hoje e o Portal do Avaliador, ver OrigemParecer) - null
            // aqui significa apenas "ainda nao votado", nunca um lancamento
            // manual pelo operador (esse caminho foi removido do enum em
            // 2026-07-29, commit 041dc43).
            campo(sb, "  Origem do voto", par.getOrigem() != null ? par.getOrigem().getDescricao() : null);
            campo(sb, "  Registrado em", dataHora(par.getDataHoraVoto()));
            campo(sb, "  Registrado por", par.getVotadoPor());
            campo(sb, "  Justificativa", par.getJustificativa());
            sb.append('\n');
        }
        // Achado 2 do relatorio de vistoria de brechas (2026-08-10): o texto
        // antigo era fixo em "(regra: 2 de 3 defere)", mesmo quando o
        // processo foi deferido pelo voto isolado do Coordenador da CET-RS
        // (ex.: "1 (regra: 2 de 3 defere)" ao lado de "Resultado: Deferido"
        // - o dossie afirmava que a propria regra que citava tinha sido
        // violada). Fonte unica com o Relatorio Final/o badge/a auditoria:
        // RegraDecisao (ProcessoValidator.regraAplicada), nunca reconstruida
        // por conta propria.
        campo(sb, "Pareceres favoraveis", String.valueOf(processoService.contarFavoraveis(p)));
        campo(sb, "Pareceres desfavoraveis", String.valueOf(processoService.contarNaoFavoraveis(p)));
        RegraDecisao regraDecisao = processoService.regraAplicada(p);
        campo(sb, "Regra de decisao aplicada", regraDecisao.getRotuloLongo());
        sb.append('\n');

        linhaTitulo(sb, "5. DECISAO FINAL");
        campo(sb, "Resultado", p.getStatus() != null ? p.getStatus().getDescricao() : null);
        campo(sb, "Motivo do indeferimento", p.getMotivoIndeferimento());
        campo(sb, "E-mail de resposta enviado ao solicitante", p.isEmailEnviadoSolicitante() ? "Sim" : "Nao");
        campo(sb, "Observacoes", p.getObservacoes());
        sb.append('\n');

        linhaTitulo(sb, "6. MOVIMENTACAO (LINHA DO TEMPO)");
        for (EtapaFluxo e : fluxoService.montarEtapas(p)) {
            String marca = switch (e.estado()) {
                case CONCLUIDA -> "[CONCLUIDA]";
                case ATUAL -> "[EM ANDAMENTO]";
                case BLOQUEADA -> "[PENDENTE]";
            };
            sb.append(marca).append(' ').append(e.titulo()).append('\n')
              .append("    ").append(e.detalhe()).append('\n');
        }
        sb.append('\n');

        linhaTitulo(sb, "7. ANEXOS DO PROCESSO");
        if (anexos.isEmpty()) {
            sb.append("Nenhum anexo registrado.\n");
        } else {
            for (Anexo a : anexos) {
                sb.append("- [").append(a.getTipo() != null ? a.getTipo().getDescricao() : "Anexo").append("] ")
                  .append(a.getNomeArquivo())
                  .append(a.getDataUpload() != null ? " (" + a.getDataUpload().format(DATA_HORA) + ")" : "")
                  .append('\n');
            }
        }
        sb.append("\nOs arquivos correspondentes estao na subpasta \"").append(PASTA_ANEXOS).append("\".\n");
        return sb.toString();
    }

    private static void linhaTitulo(StringBuilder sb, String titulo) {
        sb.append(titulo).append('\n')
          .append("=".repeat(Math.min(titulo.length(), 70))).append('\n');
    }

    private static void campo(StringBuilder sb, String rotulo, Object valor) {
        String v = (valor == null || valor.toString().isBlank()) ? "-" : valor.toString();
        sb.append(rotulo).append(": ").append(v).append('\n');
    }

    private static String data(java.time.LocalDate d) {
        return d == null ? null : d.format(DATA);
    }

    private static String dataHora(java.time.LocalDateTime d) {
        return d == null ? null : d.format(DATA_HORA);
    }

    // ------------------------------------------------------------------
    // Escrita do ZIP (FORA da transacao)
    // ------------------------------------------------------------------

    /**
     * Escreve o ZIP no {@code out}. Todas as entradas ficam sob a pasta unica,
     * de modo que descompactar gera exatamente uma pasta no disco do usuario.
     * Anexo ausente/ilegivel NAO aborta a exportacao: vira uma linha em
     * {@code Anexos/ANEXOS-COM-PROBLEMA.txt}.
     */
    public void escreverZip(Dossie dossie, OutputStream out) throws IOException {
        String raiz = dossie.nomePasta() + "/";
        List<String> problemas = new ArrayList<>();

        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry(raiz));
            zip.closeEntry();

            gravar(zip, raiz + ARQUIVO_RELATORIO, dossie.relatorioPdf());
            gravar(zip, raiz + ARQUIVO_RESUMO, dossie.resumo().getBytes(StandardCharsets.UTF_8));

            String pastaAnexos = raiz + PASTA_ANEXOS + "/";
            zip.putNextEntry(new ZipEntry(pastaAnexos));
            zip.closeEntry();

            for (ItemAnexo item : dossie.anexos()) {
                Path arquivo = item.arquivo();
                if (arquivo == null || !Files.isReadable(arquivo)) {
                    problemas.add(item.nomeNoZip() + " -> arquivo nao encontrado ou ilegivel no servidor"
                        + (arquivo != null ? " (" + arquivo.getFileName() + ")" : ""));
                    continue;
                }
                try {
                    zip.putNextEntry(new ZipEntry(pastaAnexos + item.nomeNoZip()));
                    Files.copy(arquivo, zip);
                    zip.closeEntry();
                } catch (IOException e) {
                    // Nao aborta o dossie por causa de um anexo problematico.
                    log.warn("Falha ao incluir o anexo {} no ZIP: {}", item.nomeNoZip(), e.getMessage());
                    problemas.add(item.nomeNoZip() + " -> falha de leitura: " + e.getMessage());
                    zip.closeEntry();
                }
            }

            if (!problemas.isEmpty()) {
                StringBuilder sb = new StringBuilder(
                    "Os anexos abaixo constam no processo, mas nao puderam ser incluidos nesta exportacao.\n"
                    + "Procure o administrador do sistema.\n\n");
                problemas.forEach(l -> sb.append("- ").append(l).append('\n'));
                gravar(zip, pastaAnexos + ARQUIVO_PROBLEMAS, sb.toString().getBytes(StandardCharsets.UTF_8));
            }
            zip.finish();
        }
    }

    private static void gravar(ZipOutputStream zip, String nome, byte[] conteudo) throws IOException {
        zip.putNextEntry(new ZipEntry(nome));
        zip.write(conteudo);
        zip.closeEntry();
    }
}
