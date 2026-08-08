package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.LogAuditoria;
import br.gov.saude.sgpur.repository.LogAuditoriaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Registra acoes relevantes para auditoria. O usuario e obtido do contexto
 * de seguranca. Falhas de log nunca devem quebrar a acao principal.
 */
@Service
public class AuditoriaService {

    private static final Logger log = LoggerFactory.getLogger(AuditoriaService.class);

    private final LogAuditoriaRepository repo;

    public AuditoriaService(LogAuditoriaRepository repo) {
        this.repo = repo;
    }

    public void registrar(String acao, String detalhe) {
        registrar(acao, detalhe, null);
    }

    /**
     * Registra a acao com o IP do cliente. Usar quando o IP for relevante para
     * nao-repudio (ex.: voto autenticado do Portal do Avaliador).
     */
    public void registrar(String acao, String detalhe, String ip) {
        try {
            String usuario = usuarioAtual();
            String det = (detalhe != null && detalhe.length() > 400) ? detalhe.substring(0, 400) : detalhe;
            repo.save(new LogAuditoria(usuario, acao, det, ip));
        } catch (Exception e) {
            // auditoria nunca pode interromper a operacao principal, mas uma
            // falha aqui (ex.: banco fora do ar durante um voto) nao pode
            // ficar totalmente invisivel - loga para nao perder o rastro.
            log.warn("Falha ao registrar auditoria (acao={}): {}", acao, e.getMessage());
        }
    }

    public Page<LogAuditoria> listar(Pageable pageable) {
        return repo.findAllByOrderByDataHoraDesc(pageable);
    }

    /**
     * Listagem filtrada da trilha (usuario / acao / periodo). Qualquer
     * parametro nulo ou vazio simplesmente nao filtra, entao esta chamada
     * cobre tambem o caso "sem filtro nenhum".
     *
     * <p><b>CORRIGIDO em 2026-08-07 (bug real de producao — 500 em
     * {@code /auditoria} em toda carga, ver javadoc de {@link
     * LogAuditoriaRepository#buscar}):</b> antes desta correcao, usuario/acao/
     * datas ausentes eram repassados como {@code null} direto para a
     * consulta JPQL, que testava {@code :de is null}/{@code :ate is null}
     * isoladamente — o PostgreSQL nao consegue inferir o tipo de um
     * parametro usado so em {@code IS NULL} (protocolo estendido), e a
     * consulta quebrava com {@code PSQLException 42P18 could not determine
     * data type of parameter}. O H2 dos testes tolera esse padrao, entao o
     * bug nunca apareceu na suite. Corrigido convertendo cada ausencia para
     * um valor efetivo ANTES de chamar o repositorio — mesma tecnica ja
     * usada em {@link #buscarParaExportacao} desde a criacao dela.</p>
     */
    public Page<LogAuditoria> buscar(String usuario, String acao,
                                     java.time.LocalDate de, java.time.LocalDate ate,
                                     Pageable pageable) {
        // O periodo e informado em DATA, mas o campo gravado e data+hora: o dia
        // final precisa ir ate 23:59:59, senao "ate = hoje" esconderia tudo o
        // que aconteceu hoje depois da meia-noite (ou seja, o dia inteiro).
        String usuarioFiltro = (usuario != null) ? usuario : "";
        String acaoFiltro = (acao != null) ? acao : "";
        java.time.LocalDateTime inicio = de != null ? de.atStartOfDay() : DATA_MINIMA;
        java.time.LocalDateTime fim = ate != null ? ate.atTime(java.time.LocalTime.MAX) : DATA_MAXIMA;
        return repo.buscar(usuarioFiltro, acaoFiltro, inicio, fim, pageable);
    }

    /** Acoes distintas ja registradas, para o select do filtro. */
    public java.util.List<String> acoesDistintas() {
        return repo.acoesDistintas();
    }

    /**
     * Limites de data usados quando "de"/"ate" nao sao informados — bem fora
     * de qualquer registro real do sistema, mas DENTRO da faixa que uma
     * coluna {@code timestamp} do banco realmente representa.
     *
     * <p>Testado e corrigido nesta sessao: usar
     * {@link java.time.LocalDateTime#MIN}/{@link java.time.LocalDateTime#MAX}
     * como sentinela parecia mais "correto" a primeira vista, mas o ano
     * -999999999/+999999999 desses valores estoura a faixa representavel de
     * {@code timestamp} no PostgreSQL/H2 (grosso modo 4713 a.C. a 294276
     * d.C.) — a comparacao {@code dataHora >= inicio} nunca casava com
     * NENHUM registro real, entao a exportacao "sem filtro de data" sempre
     * devolvia lista vazia. Confirmado por teste de integracao real (H2,
     * nao um mock) antes desta correcao.</p>
     */
    private static final java.time.LocalDateTime DATA_MINIMA = java.time.LocalDateTime.of(1900, 1, 1, 0, 0);
    private static final java.time.LocalDateTime DATA_MAXIMA = java.time.LocalDateTime.of(2200, 12, 31, 23, 59, 59);

    /**
     * Registros filtrados (usuario / acao / periodo) para exportacao —
     * mesmos filtros de {@link #buscar}, mas sem paginacao (o CSV precisa de
     * todos os registros do filtro). Ver o javadoc de
     * {@link br.gov.saude.sgpur.repository.LogAuditoriaRepository#buscarParaExportacao}
     * para o motivo de nunca passar {@code null} para essa consulta.
     */
    public java.util.List<LogAuditoria> buscarParaExportacao(String usuario, String acao,
                                     java.time.LocalDate de, java.time.LocalDate ate) {
        String usuarioFiltro = (usuario != null) ? usuario : "";
        String acaoFiltro = (acao != null) ? acao : "";
        java.time.LocalDateTime inicio = de != null ? de.atStartOfDay() : DATA_MINIMA;
        java.time.LocalDateTime fim = ate != null ? ate.atTime(java.time.LocalTime.MAX) : DATA_MAXIMA;
        return repo.buscarParaExportacao(usuarioFiltro, acaoFiltro, inicio, fim);
    }

    private String usuarioAtual() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        // getName() de um token com principal nulo retorna "" (nao null) - checar
        // so != null deixava o campo "usuario" do log em branco nesse caso.
        return (auth != null && auth.getName() != null && !auth.getName().isBlank())
            ? auth.getName() : "sistema";
    }
}
