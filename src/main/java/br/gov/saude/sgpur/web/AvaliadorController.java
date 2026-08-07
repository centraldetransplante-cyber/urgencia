package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.domain.MensagemAvaliador.RemetenteMensagemAvaliador;
import br.gov.saude.sgpur.repository.AnexoRepository;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.AnexoStorageService;
import br.gov.saude.sgpur.service.AuditoriaService;
import br.gov.saude.sgpur.service.DecisaoFinalService;
import br.gov.saude.sgpur.service.Iniciais;
import br.gov.saude.sgpur.service.MensagemAvaliadorService;
import br.gov.saude.sgpur.service.ProcessoService;
import br.gov.saude.sgpur.service.TempoRespostaService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Portal do Avaliador — visao restrita para medicos avaliadores autenticados.
 *
 * REGRA DE IMPARCIALIDADE: o avaliador NUNCA ve o nome completo do paciente,
 * a equipe solicitante, os co-avaliadores nem os votos dos outros medicos.
 * Apenas iniciais do paciente sao exibidas (convencao da equipe de Urgencia
 * Renal, nao LGPD). O PDF que o avaliador baixa (SOLICITACAO_AVALIADOR) ja
 * foi gerado anonimizado pelo sistema no momento do envio.
 *
 * <p><b>Sem @Transactional de nivel de classe (removido em 2026-07-29).</b> Essa
 * anotacao sustentava os acessos LAZY dos metodos GET (Parecer.processo,
 * Usuario.membro), mas tambem fazia o POST de voto ({@link #registrarVoto})
 * compartilhar a MESMA transacao fisica com os servicos chamados dentro do seu
 * try/catch - uma falha de pos-processamento marcava a transacao inteira como
 * rollback-only e o voto do medico, ja "salvo" aos olhos do metodo, era perdido
 * no commit final (ver o javadoc grande de {@link #registrarVoto}). Cada acesso
 * LAZY dos metodos GET foi resolvido na origem, com fetch join nas consultas de
 * {@link ParecerRepository} ({@code ComProcesso}) ou recarregando a entidade
 * completa por uma consulta propria ({@link #resolverMembro}) - nenhuma
 * transacao aberta neste controller, nem open-in-view reativado.
 */
@Controller
@RequestMapping("/avaliador")
public class AvaliadorController {

    private static final Logger log = LoggerFactory.getLogger(AvaliadorController.class);

    private final UsuarioRepository usuarioRepo;
    private final ParecerRepository parecerRepo;
    private final MembroUrgenciaRenalRepository membroRepo;
    private final AnexoRepository anexoRepo;
    private final AnexoStorageService anexoStorage;
    private final ProcessoService processoService;
    private final AuditoriaService auditoria;
    private final DecisaoFinalService decisaoFinalService;
    private final MensagemAvaliadorService mensagemAvaliadorService;
    /**
     * Transacoes explicitas e CURTAS do POST de voto. Ver o comentario grande em
     * {@link #registrarVoto}: o voto do medico precisa ser commitado numa
     * transacao propria, ANTES do pos-processamento (status/decisao automatica/
     * PDFs), para que uma falha desses passos nunca desfaca o voto.
     */
    private final TransactionTemplate txTemplate;
    private final int prazoDias;

    public AvaliadorController(UsuarioRepository usuarioRepo,
                               ParecerRepository parecerRepo,
                               MembroUrgenciaRenalRepository membroRepo,
                               AnexoRepository anexoRepo,
                               AnexoStorageService anexoStorage,
                               ProcessoService processoService,
                               AuditoriaService auditoria,
                               DecisaoFinalService decisaoFinalService,
                               MensagemAvaliadorService mensagemAvaliadorService,
                               PlatformTransactionManager txManager,
                               // Prazo-meta vem do TempoRespostaService (nao um @Value proprio):
                               // fonte unica de verdade pro mesmo criterio "fora do prazo" usado
                               // em /membros e no Painel - evita os dois valores divergirem se o
                               // default/chave mudar em um lugar so.
                               TempoRespostaService tempoRespostaService) {
        this.usuarioRepo = usuarioRepo;
        this.parecerRepo = parecerRepo;
        this.membroRepo = membroRepo;
        this.anexoRepo = anexoRepo;
        this.anexoStorage = anexoStorage;
        this.processoService = processoService;
        this.auditoria = auditoria;
        this.decisaoFinalService = decisaoFinalService;
        this.mensagemAvaliadorService = mensagemAvaliadorService;
        this.txTemplate = new TransactionTemplate(txManager);
        this.prazoDias = tempoRespostaService.getPrazoDias();
    }

    /**
     * Painel do medico avaliador logado: contadores consolidados, lista de
     * pendentes e historico das suas avaliacoes.
     *
     * Mostra apenas: numero, iniciais do paciente, datas, resultado proprio,
     * link ao PDF. NUNCA: nome completo, equipe solicitante, co-avaliadores ou
     * votos alheios.
     */
    @GetMapping
    public String lista(Principal principal, Model model) {
        MembroUrgenciaRenal membro = resolverMembro(principal);
        Long membroId = membro.getId();

        // Mesmo CRITERIO de "pendente ativo para voto" (pendenteAtivoParaVoto),
        // reaproveitado tambem pelo contador do badge global em
        // GlobalModelAdvice (hoje resolvido por uma query de count() dedicada,
        // sem carregar entidade nenhuma). Aqui em lista() precisamos das
        // entidades de verdade para montar a tabela, entao usamos a consulta
        // com fetch join do Processo: sem transacao de controller (removida em
        // 2026-07-29), a consulta sem fetch join devolveria Processo como
        // proxy LAZY inutilizavel no loop abaixo.
        List<Parecer> parecersFiltrados = parecerRepo.findPendentesComProcesso(membroId)
            .stream()
            .filter(AvaliadorController::pendenteAtivoParaVoto)
            .toList();

        // Mapas por processoId — passados ao template para evitar logica na view.
        Map<Long, Anexo> pdfPorProcesso = new HashMap<>();
        Map<Long, String> iniciaisPorProcesso = new HashMap<>();
        // PRAZOS: o dominio NAO possui campo de prazo/SLA/data-limite. Por isso
        // exibimos apenas "dias desde o envio" (hoje - dataEnvio) como informacao
        // auxiliar, sem inventar campo novo nem migracao.
        Map<Long, Long> diasDesdeEnvio = new HashMap<>();
        // Fora do prazo-meta (app.avaliador.prazo-dias): sinal visual pre-atentivo
        // (cor + icone, nao so cor - ver docs/ESTUDO-UI-COMPORTAMENTAL.md #2) de
        // que aquele voto esta atrasado, calculado com o mesmo prazo-meta usado
        // pelo indicador de tempo de resposta (TempoRespostaService).
        Map<Long, Boolean> foraDoPrazoPorProcesso = new HashMap<>();
        LocalDate hoje = LocalDate.now();
        // Projecao para a view: so numero/id/dataEnvio do processo, nunca a
        // entidade completa (que carrega pacienteNome, solicitanteEquipe e os
        // outros pareceres do mesmo processo) - mesmo padrao ja usado em
        // votar() (ProcessoVotoView/ParecerVotoView), fechando por design o
        // risco de um th:text futuro vazar dado protegido nesta tela tambem.
        List<ParecerPendenteView> pareceresView = new java.util.ArrayList<>();
        for (Parecer par : parecersFiltrados) {
            Long pid = par.getProcesso().getId();
            List<Anexo> pdfs = anexoRepo.findByProcessoIdAndTipo(
                pid, TipoAnexo.SOLICITACAO_AVALIADOR);
            if (!pdfs.isEmpty()) {
                pdfPorProcesso.put(pid, pdfs.get(0));
            }
            iniciaisPorProcesso.put(pid, Iniciais.de(par.getProcesso().getPacienteNome()));
            if (par.getDataEnvio() != null) {
                long dias = ChronoUnit.DAYS.between(par.getDataEnvio(), hoje);
                diasDesdeEnvio.put(pid, dias);
                foraDoPrazoPorProcesso.put(pid, dias > prazoDias);
            }
            pareceresView.add(new ParecerPendenteView(pid, par.getProcesso().getNumero(), par.getDataEnvio()));
        }

        // Particiona server-side (nao na view) os atrasados dos demais: evita
        // repetir a mesma decisao de filtro duas vezes no template e, mais
        // importante, evita a secao "Demais pendentes" ficar com tabela vazia
        // e sem nenhuma mensagem quando TODOS os pendentes estiverem atrasados
        // (o antigo th:if="${pareceres.isEmpty()}" nao cobria esse caso, so o
        // caso de nao haver pendente nenhum).
        List<ParecerPendenteView> pareceresAtrasados = new java.util.ArrayList<>();
        List<ParecerPendenteView> pareceresDemais = new java.util.ArrayList<>();
        for (ParecerPendenteView pv : pareceresView) {
            if (Boolean.TRUE.equals(foraDoPrazoPorProcesso.get(pv.processoId()))) {
                pareceresAtrasados.add(pv);
            } else {
                pareceresDemais.add(pv);
            }
        }

        // Historico: pareceres ja votados pelo membro (mais recente primeiro).
        // findHistoricoComProcesso (fetch join): o loop abaixo navega
        // par.getProcesso().getPacienteNome()/getNumero(), que precisa do
        // Processo ja carregado (sem @Transactional de classe neste controller
        // desde 2026-07-29, o metodo original devolveria um proxy LAZY inutil
        // apos o retorno desta consulta).
        List<Parecer> historicoEntidades = parecerRepo
            .findHistoricoComProcesso(membroId);
        Map<Long, String> iniciaisHistorico = new HashMap<>();
        List<ParecerHistoricoView> historico = new java.util.ArrayList<>();
        for (Parecer par : historicoEntidades) {
            iniciaisHistorico.put(par.getId(),
                Iniciais.de(par.getProcesso().getPacienteNome()));
            historico.add(new ParecerHistoricoView(par.getId(), par.getProcesso().getNumero(),
                par.getResultado(), par.getDataHoraVoto(), par.getDataResposta()));
        }

        // Contadores consolidados (reutilizam as queries de contagem do repo).
        long totalAtribuidos = parecerRepo.countByMembroId(membroId);
        long totalAvaliados = parecerRepo.countByMembroIdAndResultadoNotNull(membroId);
        long favoraveis = parecerRepo
            .countByMembroIdAndResultado(membroId, ResultadoParecer.FAVORAVEL);
        long naoFavoraveis = parecerRepo
            .countByMembroIdAndResultado(membroId, ResultadoParecer.NAO_FAVORAVEL);
        long solicitaInfo = parecerRepo
            .countByMembroIdAndResultado(membroId, ResultadoParecer.SOLICITA_INFORMACAO);

        model.addAttribute("pareceres", pareceresView);
        model.addAttribute("pareceresAtrasados", pareceresAtrasados);
        model.addAttribute("pareceresDemais", pareceresDemais);
        model.addAttribute("pdfPorProcesso", pdfPorProcesso);
        model.addAttribute("iniciaisPorProcesso", iniciaisPorProcesso);
        model.addAttribute("diasDesdeEnvio", diasDesdeEnvio);
        model.addAttribute("foraDoPrazoPorProcesso", foraDoPrazoPorProcesso);
        model.addAttribute("prazoDias", prazoDias);
        model.addAttribute("historico", historico);
        model.addAttribute("iniciaisHistorico", iniciaisHistorico);
        // String, nao a entidade: membro ja vem totalmente carregado por
        // resolverMembro (MembroUrgenciaRenalRepository.findById, sem proxy
        // lazy), mas mesmo assim so passamos o rotulo pronto - nao a entidade
        // inteira - para o template nunca ter a chance de expor um campo alem
        // do que a tela realmente usa.
        model.addAttribute("membroRotulo", membro.getRotulo());
        model.addAttribute("totalAtribuidos", totalAtribuidos);
        model.addAttribute("totalPendentes", parecersFiltrados.size());
        model.addAttribute("totalAvaliados", totalAvaliados);
        model.addAttribute("favoraveis", favoraveis);
        model.addAttribute("naoFavoraveis", naoFavoraveis);
        model.addAttribute("solicitaInfo", solicitaInfo);
        return "avaliador/lista";
    }

    /**
     * Exibe o formulario de voto para um processo especifico.
     * 403 se: nao for avaliador do processo, parecer ja emitido, processo nao ativo.
     */
    @GetMapping("/{processoId}")
    public String votar(@PathVariable Long processoId, Principal principal, Model model) {
        MembroUrgenciaRenal membro = resolverMembro(principal);
        Parecer parecer = resolverParecerPendente(processoId, membro);

        // "Processo X de N pendentes" (Fase 10 do relatorio de UI): da nocao de
        // progresso a quem tem varios pendentes, sem inventar nenhum estado novo -
        // so a posicao deste processo na MESMA lista/ordem de pendentesDoMembro().
        // Usa so dados DO PROPRIO membro logado, nunca informacao sobre o processo
        // em si (ex.: quantos votos ja tem) - isso quebraria a imparcialidade.
        List<Parecer> pendentesDoMembro = parecerRepo.findPendentesComProcesso(membro.getId())
            .stream()
            .filter(AvaliadorController::pendenteAtivoParaVoto)
            .toList();
        int posicaoPendente = 1;
        for (int i = 0; i < pendentesDoMembro.size(); i++) {
            if (pendentesDoMembro.get(i).getProcesso().getId().equals(processoId)) {
                posicaoPendente = i + 1;
                break;
            }
        }
        model.addAttribute("posicaoPendente", posicaoPendente);
        model.addAttribute("totalPendentesMembro", pendentesDoMembro.size());

        Processo processo = parecer.getProcesso();
        List<Anexo> pdfsAvaliador = anexoRepo
            .findByProcessoIdAndTipo(processoId, TipoAnexo.SOLICITACAO_AVALIADOR);
        // O <iframe> da tela de voto nao tem como avisar sozinho se baixarPdf
        // devolver 404 (arquivo apagado do disco mas o registro continua no
        // banco) - avisamos aqui, antes, com a mesma checagem que baixarPdf usa.
        boolean algumPdfIndisponivel = pdfsAvaliador.stream()
            .anyMatch(a -> !Files.isReadable(anexoStorage.resolverArquivo(a)));

        // Apenas iniciais — NUNCA nome completo. Nao expomos a entidade Processo
        // (tem pacienteNome) nem Parecer (tem processo.pacienteNome) inteiras ao
        // template: so os poucos campos que a tela de voto realmente usa, num DTO
        // projetado (ProcessoVotoView/ParecerVotoView) — assim um th:text futuro
        // digitado errado nao consegue vazar o nome completo por acidente.
        model.addAttribute("iniciais", Iniciais.de(processo.getPacienteNome()));
        model.addAttribute("numero", processo.getNumero());
        model.addAttribute("parecer", new ParecerVotoView(parecer.getDataEnvio()));
        model.addAttribute("processo", new ProcessoVotoView(processo.getId()));
        model.addAttribute("pdfsAvaliador", pdfsAvaliador);
        model.addAttribute("algumPdfIndisponivel", algumPdfIndisponivel);
        model.addAttribute("resultados", List.of(
            ResultadoParecer.FAVORAVEL,
            ResultadoParecer.NAO_FAVORAVEL,
            ResultadoParecer.SOLICITA_INFORMACAO
        ));
        // O card de chat nasce expandido quando ja existe conversa (mesmo com
        // tudo lido) - so fica recolhido quando ainda nao ha nenhuma mensagem
        // (CLAUDE.md, 2026-08-07). Antes o card nascia SEMPRE recolhido, o que
        // escondia mensagens do operador ja recebidas (bug relatado em producao).
        model.addAttribute("existeConversaAval", mensagemAvaliadorService.existeConversa(processoId, membro.getId()));
        return "avaliador/votar";
    }

    /**
     * Registra o voto do avaliador autenticado.
     *
     * Grava: resultado, dataResposta=hoje, dataHoraVoto=agora, votadoPor=username,
     * origem=AVALIADOR_SISTEMA. Nao exige anexo (o registro autenticado + IP e a
     * prova de nao-repudio). Chama atualizarStatusPorPareceres para manter a maquina
     * de estados do processo correta (inclusive SOLICITA_INFORMACAO).
     *
     * <p><b>O VOTO E COMMITADO ANTES DE QUALQUER POS-PROCESSAMENTO.</b> O metodo
     * roda em 4 transacoes curtas e independentes, em sequencia:
     * <ol>
     *   <li>voto do medico (unica escrita realmente critica);</li>
     *   <li>anexo opcional do avaliador;</li>
     *   <li>{@code atualizarStatusPorPareceres};</li>
     *   <li>{@code tentarDecisaoAutomatica} (+ geracao dos PDFs finais).</li>
     * </ol>
     * Motivo (historico do bug real, ja corrigido): antes o metodo era
     * {@code @Transactional} (a classe inteira tinha
     * {@code @Transactional(readOnly = true)}) e os servicos chamados aqui
     * (todos {@code @Transactional} com propagacao REQUIRED) participavam da
     * MESMA transacao fisica. Quando um deles lancava
     * {@code IllegalStateException} (ex.: processo finalizado por outro medico
     * votando quase junto - janela real entre a checagem de
     * {@code resolverParecerPendente} e o commit, ampliada pela decisao
     * automatica de Indeferido), o TransactionInterceptor da chamada aninhada
     * marcava a transacao compartilhada como rollback-only: o {@code catch}
     * abaixo tratava o erro e devolvia um flash amigavel, mas o commit no fim
     * do metodo estourava {@code UnexpectedRollbackException} (500 cru) E
     * levava junto o {@code parecerRepo.save} do passo 1 - <b>o voto do medico
     * era perdido</b>. Mesma classe de bug corrigida em
     * {@code ProcessoDecisaoController.finalizar} (commit 164af0a); la
     * NOT_SUPPORTED puro bastou porque o metodo so delega, aqui nao: o voto
     * (+ anexo) precisa de transacao propria, por isso o TransactionTemplate.
     *
     * <p><b>2026-07-29: o {@code @Transactional} de nivel de classe foi
     * removido</b> (causa raiz da familia de bug, nao so o sintoma neste
     * metodo - ver javadoc da classe). Sem ele, o
     * {@code @Transactional(propagation = NOT_SUPPORTED)} que existia aqui
     * virou no-op (nao ha mais transacao de classe nenhuma para suspender) e
     * foi removido junto. O {@link #txTemplate} continua exatamente como
     * antes: cada bloco abaixo permanece uma transacao curta e independente,
     * que e o que realmente evita o bug - a ausencia da anotacao de classe
     * so torna essa independencia explicita/garantida em vez de "garantida
     * por suspensao".
     */
    @PostMapping("/{processoId}/votar")
    public String registrarVoto(@PathVariable Long processoId,
                                @RequestParam ResultadoParecer resultado,
                                @RequestParam(required = false) String justificativa,
                                @RequestParam(required = false) MultipartFile arquivo,
                                Principal principal,
                                HttpServletRequest request,
                                RedirectAttributes ra) {
        if (!resultado.isVotoValido()) {
            ra.addFlashAttribute("erro", "Parecer invalido: " + resultado);
            return "redirect:/avaliador/" + processoId;
        }
        // Justificativa OBRIGATORIA para voto desfavoravel ou pedido de
        // informacao complementar (decisao de produto aprovada em 2026-08-03,
        // item 1 da Fase 11 do relatorio de UI): o operador depende desse
        // texto para redigir o oficio de indeferimento ou o pedido de
        // informacao complementar ao solicitante sem ter que reescrever do
        // zero. FAVORAVEL continua opcional. Validacao SERVER-SIDE (o
        // "required" condicional do textarea, no template, e so UX - da pra
        // burlar via DevTools/requisicao direta) e ANTES de qualquer escrita,
        // para nunca gravar um voto invalido nem abrir a TX do voto a toa.
        boolean justificativaObrigatoria = resultado == ResultadoParecer.NAO_FAVORAVEL
            || resultado == ResultadoParecer.SOLICITA_INFORMACAO;
        if (justificativaObrigatoria && (justificativa == null || justificativa.isBlank())) {
            ra.addFlashAttribute("erro",
                "Justificativa obrigatoria para parecer " + resultado.getDescricao()
                + ": o operador depende desse texto para o oficio/pedido de informacao.");
            return "redirect:/avaliador/" + processoId;
        }
        // ---- TX 1: o voto. Unica escrita critica; commitada aqui e ponto. ----
        VotoGravado voto = txTemplate.execute(status -> {
            MembroUrgenciaRenal membro = resolverMembro(principal);
            Parecer parecer = resolverParecerPendente(processoId, membro);

            // Registra o voto com nao-repudio completo
            parecer.setResultado(resultado);
            parecer.setDataResposta(LocalDate.now());
            parecer.setDataHoraVoto(LocalDateTime.now());
            parecer.setVotadoPor(principal.getName());
            parecer.setOrigem(OrigemParecer.AVALIADOR_SISTEMA);
            // Snapshot do papel de coordenador NO INSTANTE do voto (ver
            // javadoc de Parecer.eraCoordenadorNoVoto) -- nao le
            // MembroUrgenciaRenal.coordenador "ao vivo" na hora de decidir,
            // que poderia ter mudado de mao entre o voto e a decisao.
            parecer.setEraCoordenadorNoVoto(membro.isCoordenador());
            // Justificativa e material INTERNO do operador (nunca vaza a outros
            // avaliadores). Vazio/em-branco vira null para nao poluir o banco.
            String justificativaLimpa = (justificativa == null || justificativa.isBlank())
                ? null : justificativa.trim();
            parecer.setJustificativa(justificativaLimpa);
            parecerRepo.save(parecer);

            // parecer.getProcesso() ja vem carregado (fetch join em
            // resolverParecerPendente, nao um proxy LAZY) - repassamos aqui
            // porque os passos seguintes (anexo/auditoria) rodam fora desta
            // transacao e precisam de numero/id do processo como objeto real.
            return new VotoGravado(parecer, parecer.getProcesso(), membro.getNome());
        });

        // Auditoria do voto: logo apos o commit (antes o registro vinha no fim
        // do metodo e era PULADO pelos returns de erro dos passos seguintes -
        // ficava voto gravado sem trilha de auditoria).
        auditoria.registrar("PARECER_VOTADO",
            "Processo " + voto.processo().getNumero()
                + " - " + voto.membroNome()
                + " - " + resultado.getDescricao(),
            request.getRemoteAddr());

        // ---- TX 2: anexo opcional (ex.: exame/documento de apoio do proprio
        // avaliador). O voto autenticado ja dispensa anexo como comprovante -
        // isso e so material extra. Em transacao separada: falha aqui vira
        // aviso, nunca desfaz o voto ja commitado.
        if (arquivo != null && !arquivo.isEmpty()) {
            try {
                txTemplate.executeWithoutResult(status -> {
                    Anexo anexo;
                    try {
                        anexo = anexoStorage.salvar(voto.processo(), TipoAnexo.ANEXO_AVALIADOR,
                            "Documento anexado por " + voto.membroNome() + " junto ao parecer", arquivo);
                    } catch (IOException e) {
                        // IOException e checada e o callback nao pode declara-la:
                        // envelopa para o catch abaixo (desembrulhado na mensagem).
                        throw new UncheckedIOException(e);
                    }
                    anexo.setParecer(voto.parecer());
                    anexoRepo.save(anexo);
                });
            } catch (RuntimeException e) {
                Throwable causa = (e instanceof UncheckedIOException) ? e.getCause() : e;
                log.warn("Falha ao anexar documento do avaliador ao parecer {}: {}",
                    voto.parecer().getId(), causa.toString());
                ra.addFlashAttribute("aviso",
                    "Voto registrado, mas houve falha ao anexar o documento: " + causa.getMessage());
            }
        }

        // ---- TX 3: atualiza o status do processo (pode ir para SOLICITA_INFORMACAO).
        // Transacao propria do servico: se lancar, so ela e desfeita.
        try {
            processoService.atualizarStatusPorPareceres(processoId);
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("aviso",
                "Voto registrado, mas houve um conflito ao atualizar o status do processo: "
                + e.getMessage());
            return "redirect:/avaliador";
        }

        // ---- TX 4: decisao automatica. Se a maioria foi atingida e nao ha
        // pareceres sem anexo pendentes (AVALIADOR_SISTEMA dispensa o anexo),
        // decide imediatamente. Idem: transacao propria do servico.
        Processo pDecidido;
        try {
            pDecidido = processoService.tentarDecisaoAutomatica(processoId);
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("aviso",
                "Voto registrado, mas nao foi possivel decidir automaticamente: "
                + e.getMessage());
            return "redirect:/avaliador";
        }
        if (pDecidido != null && pDecidido.getStatus().isFinalizado()) {
            try {
                // Oficio/Relatorio Final navegam colecoes LAZY do processo
                // (getAnexos/getPareceres), entao precisam de uma sessao aberta:
                // transacao curta e propria, com o processo RECARREGADO dentro
                // dela (o pDecidido devolvido pelo servico ja esta desanexado).
                // Fica fora da transacao do voto - a geracao dos PDFs (I/O de
                // arquivo) nao segura mais a conexao usada para gravar o parecer.
                txTemplate.executeWithoutResult(status ->
                    decisaoFinalService.gerarDocumentos(processoService.buscar(processoId)));
            } catch (IllegalStateException e) {
                log.warn("Falha ao gerar documentos finais do processo {} apos decisao automatica no portal: {}",
                    pDecidido.getNumero(), e.getMessage());
            }
            auditoria.registrar("PROCESSO_DECIDIDO",
                "Processo " + pDecidido.getNumero() + " - decisao automatica portal: "
                + pDecidido.getStatus().getDescricao());
        }

        ra.addFlashAttribute("msg",
            "Voto registrado: " + resultado.getDescricao() + ". Obrigado pela avaliacao.");
        // Oferece ir direto para o proximo pendente (Fase 10): quem tem varios
        // atrasados nao precisa reabrir a lista e escanear de novo a cada voto.
        // So dados do PROPRIO membro (nunca do processo que acabou de votar).
        // resolverMembro de novo (barato, mesma consulta ja usada acima) em vez
        // de voto.parecer().getMembro(): essa associacao nao foi necessariamente
        // inicializada fora da transacao do voto (TX1 ja commitada e fechada).
        MembroUrgenciaRenal membroLogado = resolverMembro(principal);
        List<Parecer> restantes = parecerRepo.findPendentesComProcesso(membroLogado.getId())
            .stream()
            .filter(AvaliadorController::pendenteAtivoParaVoto)
            .toList();
        if (!restantes.isEmpty()) {
            ra.addFlashAttribute("proximoPendenteId", restantes.get(0).getProcesso().getId());
            ra.addFlashAttribute("totalPendentesRestantes", restantes.size());
        }
        return "redirect:/avaliador";
    }

    /**
     * Dados do voto ja commitado (TX 1) que os passos seguintes precisam.
     * Carrega as proprias entidades porque elas ja foram inicializadas dentro
     * da transacao - o anexo opcional as reaproveita (ManyToOne sem cascade so
     * usa o id) sem precisar de uma segunda ida ao banco.
     */
    private record VotoGravado(Parecer parecer, Processo processo, String membroNome) {}

    /**
     * Download do PDF anonimizado (SOLICITACAO_AVALIADOR) do processo pelo
     * proprio avaliador. Antes vinha de /processos/anexos/{id}/download, que
     * exige ROLE_ADMIN/OPERADOR e por isso dava 403 para o avaliador - sem
     * este endpoint o medico nao conseguia ler o material antes de votar.
     * So permite o download se o membro for avaliador do processo (posse
     * verificada via Parecer, independente de ja ter votado ou nao).
     */
    @GetMapping("/{processoId}/pdf/{anexoId}")
    public ResponseEntity<Resource> baixarPdf(@PathVariable Long processoId,
                                              @PathVariable Long anexoId,
                                              Principal principal)
            throws MalformedURLException {
        MembroUrgenciaRenal membro = resolverMembro(principal);
        if (parecerRepo.findByProcessoIdAndMembroId(processoId, membro.getId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Voce nao e avaliador deste processo.");
        }
        List<Anexo> pdfs = anexoRepo.findByProcessoIdAndTipo(processoId, TipoAnexo.SOLICITACAO_AVALIADOR);
        Anexo anexo = pdfs.stream().filter(a -> a.getId().equals(anexoId)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Anexo nao pertence ao material de avaliacao deste processo."));
        Path arquivo = anexoStorage.resolverArquivo(anexo);
        Resource resource = new UrlResource(arquivo.toUri());
        if (!resource.exists() || !resource.isReadable()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + anexo.getNomeArquivo() + "\"")
            .body(resource);
    }

    // -------------------------------------------------------------------------
    // Chat com a equipe operacional (docs/RELATORIO-CHAT-MEMBROS-OPERADORES-2026-08.md)
    // -------------------------------------------------------------------------
    //
    // Endpoints no MESMO formato exigido pelo modulo JS reutilizado
    // (chat-solicitacao.js, ver comentario do topo do arquivo: nao foi
    // reescrito, so reusado com URLs/seletores diferentes). Posse SEMPRE
    // verificada via parecerRepo.findByProcessoIdAndMembroIdComProcesso
    // (mesmo predicado ja usado por baixarPdf) - nunca resolve a thread so
    // pelo id do processo. Conversa fica aberta ENQUANTO o processo nao for
    // finalizado (Q4 do relatorio, recomendacao aceita), mesmo depois do
    // proprio voto ja emitido - por isso NAO reusa resolverParecerPendente
    // (que exige resultado nulo).

    /** Polling do chat com a equipe operacional (AJAX). */
    @GetMapping("/{processoId}/mensagens")
    @ResponseBody
    public Map<String, Object> mensagensAvaliadorJson(@PathVariable Long processoId, Principal principal) {
        MembroUrgenciaRenal membro = resolverMembro(principal);
        Parecer parecer = resolverParecerDoMembro(processoId, membro);
        Usuario usuario = usuarioLogado(principal);
        mensagemAvaliadorService.marcarComoLidas(processoId, membro.getId(),
            RemetenteMensagemAvaliador.OPERADOR, usuario.getId());
        Map<String, Object> resp = new HashMap<>();
        resp.put("mensagens", mensagemAvaliadorService.paraChat(
            processoId, membro.getId(), RemetenteMensagemAvaliador.AVALIADOR, usuario.getId(),
            "Voce", "Equipe CET-RS"));
        resp.put("podeEnviar", !parecer.getProcesso().getStatus().isFinalizado());
        return resp;
    }

    @PostMapping("/{processoId}/mensagem/ajax")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> enviarMensagemAvaliadorAjax(@PathVariable Long processoId,
            @RequestParam String texto, Principal principal) {
        MembroUrgenciaRenal membro = resolverMembro(principal);
        Parecer parecer = resolverParecerDoMembro(processoId, membro);
        if (parecer.getProcesso().getStatus().isFinalizado()) {
            return ResponseEntity.badRequest().body(Map.of("erro",
                "Este processo ja foi decidido; a conversa ficou somente leitura."));
        }
        if (texto == null || texto.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("erro", "A mensagem nao pode estar em branco."));
        }
        Usuario usuario = usuarioLogado(principal);
        mensagemAvaliadorService.enviar(parecer.getProcesso(), membro, texto,
            RemetenteMensagemAvaliador.AVALIADOR, usuario.getId());
        // Auditoria: SO id do processo + rotulo do medico, NUNCA o texto nem o
        // nome do paciente (mesmo padrao ja exigido para MENSAGEM_OPERADOR_ENVIADA).
        auditoria.registrar("MENSAGEM_AVALIADOR_ENVIADA",
            "Processo " + parecer.getProcesso().getNumero() + " - " + membro.getRotulo());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/{processoId}/mensagem/{mensagemId}/apagar/ajax")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> apagarMensagemAvaliadorAjax(@PathVariable Long processoId,
            @PathVariable Long mensagemId, Principal principal) {
        try {
            MembroUrgenciaRenal membro = resolverMembro(principal);
            Usuario usuario = usuarioLogado(principal);
            mensagemAvaliadorService.apagar(mensagemId, usuario.getId(), RemetenteMensagemAvaliador.AVALIADOR);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("erro", e.getMessage()));
        }
    }

    /**
     * Badge global de mensagens nao lidas do avaliador (F4 do relatorio):
     * terceiro bloco de poll em layout.html, ao lado dos ja existentes de
     * ADMIN/OPERADOR e SOLICITANTE. Deliberadamente independente da flag
     * {@code app.solicitante.habilitado} - este canal nao tem nada a ver com
     * o Portal do Solicitante.
     */
    @GetMapping("/nao-lidas-count")
    @ResponseBody
    public Map<String, Object> naoLidasCount(Principal principal) {
        MembroUrgenciaRenal membro = resolverMembro(principal);
        return Map.of("total", mensagemAvaliadorService.contarNaoLidasParaMembro(membro.getId()));
    }

    /**
     * Resolve o parecer do membro no processo, INDEPENDENTE do resultado ja
     * ter sido emitido (ao contrario de {@link #resolverParecerPendente}) -
     * o chat continua aberto para leitura/escrita ate o PROCESSO ser
     * finalizado, nao ate o voto individual do medico.
     */
    private Parecer resolverParecerDoMembro(Long processoId, MembroUrgenciaRenal membro) {
        return parecerRepo.findByProcessoIdAndMembroIdComProcesso(processoId, membro.getId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Voce nao e avaliador deste processo."));
    }

    private Usuario usuarioLogado(Principal principal) {
        return usuarioRepo.findByUsername(principal.getName())
            .orElseThrow(() -> new SessaoInvalidaException(
                "Usuario da sessao (" + principal.getName() + ") nao encontrado no banco."));
    }

    // -------------------------------------------------------------------------
    // Regra reutilizavel de pendencias
    // -------------------------------------------------------------------------

    /**
     * Criterio de "pendente ativo para voto": parecer sem resultado, ja enviado,
     * cujo processo ainda esta em status que aceita votacao (ENVIADO ou
     * SOLICITA_INFORMACAO — ver {@link StatusProcesso#aceitaVotoAvaliador()};
     * a pausa por pedido de informacao de UM avaliador nao deve impedir o
     * voto dos outros dois, bug real corrigido em 2026-08, ver
     * docs/RELATORIO-BUG-PAUSA-BLOQUEIA-OUTROS-AVALIADORES-2026-08.md).
     *
     * Regra UNICA de negocio, reaproveitada pelas consultas com fetch join
     * usadas em {@link #lista()}/{@link #registrarVoto} — mas nao mais pelo
     * contador da navbar: desde a correcao do N+1 do badge (2026-08),
     * {@code GlobalModelAdvice.pendentesAvaliador()} usa uma query de
     * {@code count(...)} dedicada no repositorio
     * ({@code ParecerRepository.
     * countByMembroIdAndResultadoIsNullAndDataEnvioIsNotNullAndProcessoStatusIn}),
     * que expressa o MESMO criterio (resultado nulo + dataEnvio preenchida +
     * processo.status em ENVIADO/SOLICITA_INFORMACAO) diretamente no banco,
     * sem carregar nenhuma entidade {@code Parecer}/{@code Processo}. O
     * metodo estatico {@code pendentesDoMembro} que existia aqui antes
     * (carregava as entidades e filtrava em Java navegando
     * {@code par.getProcesso()}) foi removido por ter ficado sem nenhum
     * chamador.
     */
    private static boolean pendenteAtivoParaVoto(Parecer par) {
        StatusProcesso s = par.getProcesso().getStatus();
        return s.aceitaVotoAvaliador();
    }

    // -------------------------------------------------------------------------
    // Helpers privados
    // -------------------------------------------------------------------------

    /**
     * Resolve o membro vinculado ao usuario logado.
     * Lanca 403 se o usuario nao tiver membro vinculado (configuracao incorreta).
     *
     * <p><b>Sessao orfa (bug real corrigido):</b> se o usuario correspondente ao
     * username gravado na sessao nao existir mais no banco (ex.: um ADMIN trocou
     * o {@code username} desse avaliador em {@code /usuarios}, ou excluiu a
     * conta, enquanto ele tinha sessao ativa — o Spring Security nao rele o
     * {@code UserDetails} a cada requisicao), lanca {@link SessaoInvalidaException}
     * em vez de {@code ResponseStatusException(UNAUTHORIZED)}. O
     * {@code GlobalExceptionHandler} trata esse tipo invalidando a sessao e
     * redirecionando para {@code /login} com mensagem clara, em vez do 401 cru
     * que o navegador exibia antes (a excecao antiga era tratada direto pelo
     * Spring, sem chance de o usuario simplesmente logar de novo).</p>
     */
    private MembroUrgenciaRenal resolverMembro(Principal principal) {
        Usuario usuario = usuarioRepo.findByUsername(principal.getName())
            .orElseThrow(() -> new SessaoInvalidaException(
                "Usuario da sessao (" + principal.getName() + ") nao encontrado no banco."));
        MembroUrgenciaRenal vinculo = usuario.getMembro();
        if (vinculo == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Usuario avaliador sem membro vinculado. Contate o administrador.");
        }
        // usuario.getMembro() devolve um proxy Hibernate (Usuario.membro e
        // LAZY), ja sem sessao aberta neste ponto (open-in-view=false, sem
        // @Transactional de classe desde 2026-07-29). vinculo.getId() e seguro
        // mesmo assim: Hibernate nao inicializa o proxy so para ler o
        // identificador (ja conhecido desde a coluna membro_id lida ao
        // carregar Usuario, sem precisar de outra consulta). Qualquer outro
        // acesso (getRotulo/getNome) exigiria a sessao aberta - por isso
        // recarregamos a entidade completa numa consulta propria e
        // independente (MembroUrgenciaRenalRepository.findById), sem tocar em
        // UsuarioRepository nem abrir transacao aqui.
        return membroRepo.findById(vinculo.getId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Membro vinculado nao encontrado."));
    }

    /**
     * Resolve o parecer pendente do membro no processo.
     * Lanca 403 se o membro nao for avaliador do processo, se o parecer ja foi
     * emitido, ou se o processo nao esta em status ativo para votacao.
     */
    private Parecer resolverParecerPendente(Long processoId, MembroUrgenciaRenal membro) {
        // findByProcessoIdAndMembroIdComProcesso (fetch join): logo abaixo
        // navegamos parecer.getProcesso().getStatus(), e este metodo e chamado
        // tanto por votar() (GET, sem transacao no controller desde
        // 2026-07-29) quanto por registrarVoto() - o metodo original devolveria
        // um proxy LAZY inutil fora de uma transacao aberta.
        Parecer parecer = parecerRepo.findByProcessoIdAndMembroIdComProcesso(processoId, membro.getId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Voce nao e avaliador deste processo."));

        if (parecer.getResultado() != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Voce ja emitiu seu parecer para este processo.");
        }

        StatusProcesso status = parecer.getProcesso().getStatus();
        if (!status.aceitaVotoAvaliador()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Este processo nao esta disponivel para avaliacao (status: "
                    + status.getDescricao() + ").");
        }

        return parecer;
    }

    /** Projecao de Processo para a tela de voto: so o id, usado pelas actions dos forms/links. */
    private record ProcessoVotoView(Long id) {}

    /** Projecao de Parecer para a tela de voto: so a data de envio, exibida na tela. */
    private record ParecerVotoView(LocalDate dataEnvio) {}

    /** Projecao para a lista de pendentes (aba "Pendentes de voto" do painel). */
    private record ParecerPendenteView(Long processoId, String processoNumero, LocalDate dataEnvio) {}

    /** Projecao para o historico de votos do proprio avaliador. */
    private record ParecerHistoricoView(Long id, String processoNumero, ResultadoParecer resultado,
                                        LocalDateTime dataHoraVoto, LocalDate dataResposta) {}
}
