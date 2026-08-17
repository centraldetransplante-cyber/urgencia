package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.service.ConflitoEquipeMatcher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Endpoint auxiliar, SO LEITURA, para o aviso de possivel conflito de
 * interesse (medico avaliador da mesma equipe/instituicao do solicitante) no
 * MOMENTO em que o operador escolhe os 3 medicos avaliadores em
 * {@code processos/form.html} (GET/POST {@code /processos}).
 *
 * <p>Implementa a Opcao A de
 * {@code docs/RELATORIO-CONFIRMACAO-CONFLITO-EQUIPE-2026-08.md}, aprovada
 * pelo dono do produto: checagem client-side em tempo real, disparada assim
 * que o 3o medico e marcado (ou o trio muda), abrindo o modal generico de
 * confirmacao ({@code window.confirmarAcao}) via
 * {@code static/js/processo-form.js}.
 *
 * <p><b>Reusa {@link ConflitoEquipeMatcher#mesmaEquipe} sem duplicar a
 * logica em JavaScript</b> - o matcher tem normalizacao de acento/maiuscula,
 * mapa de apelidos por sigla e casamento bidirecional por tokens; reescrever
 * isso em JS criaria uma segunda fonte de verdade fadada a divergir (a mesma
 * armadilha ja documentada no CLAUDE.md para {@code VerificadorNomePaciente}).
 *
 * <p><b>Nunca bloqueia nada de verdade.</b> Continua sendo, como sempre foi,
 * um AVISO heuristico (nao uma regra de negocio server-side inquebravel) -
 * o {@code POST /processos} nao chama este endpoint nem depende dele. O
 * aviso ja existente na tela de detalhe do processo
 * ({@link ProcessoDetalheController#detalhe}) continua existindo sem
 * alteracao - este endpoint e aditivo, so antecipa o momento em que o
 * operador toma conhecimento do possivel conflito.
 *
 * <p>Novo controller isolado (em vez de mais um metodo em
 * {@link ProcessoDetalheController}, ja descrito no CLAUDE.md como a classe
 * mais complexa/sensivel do sistema) - registrado no mesmo prefixo
 * {@code /processos}, protegido pelo mesmo
 * {@code hasAnyRole("ADMIN","OPERADOR")} de {@code SecurityConfig} (que casa
 * por padrao de URL, nao por classe de controller).
 */
@RestController
@RequestMapping("/processos")
public class ProcessoConflitoEquipeController {

    private final MembroUrgenciaRenalRepository membroRepo;
    private final ConflitoEquipeMatcher conflitoEquipeMatcher;

    public ProcessoConflitoEquipeController(MembroUrgenciaRenalRepository membroRepo,
                                             ConflitoEquipeMatcher conflitoEquipeMatcher) {
        this.membroRepo = membroRepo;
        this.conflitoEquipeMatcher = conflitoEquipeMatcher;
    }

    /**
     * {@code GET /processos/conflito-equipe?equipe=...&medicoIds=1,2,3}
     *
     * <p>Path deliberadamente literal (nao numerico) para nunca colidir com
     * {@code /processos/{id}} de {@link ProcessoDetalheController} - o
     * Spring resolve o segmento estatico mais especifico antes de cair na
     * variavel de path, mas o path escolhido ja evita qualquer ambiguidade
     * por construcao (mesmo padrao ja usado por
     * {@code /processos/solicitacoes-online}/{@code /processos/mensagens-
     * avaliadores}, que coexistem com {@code /processos/{id}} hoje sem
     * problema).
     */
    @GetMapping("/conflito-equipe")
    public Map<String, Object> verificar(@RequestParam(required = false) String equipe,
                                          @RequestParam(required = false) List<Long> medicoIds) {
        List<Map<String, Object>> conflitos = new ArrayList<>();
        if (equipe != null && !equipe.isBlank() && medicoIds != null && !medicoIds.isEmpty()) {
            for (MembroUrgenciaRenal m : membroRepo.findAllById(medicoIds)) {
                if (conflitoEquipeMatcher.mesmaEquipe(m.getInstituicao(), equipe)) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", m.getId());
                    item.put("nome", m.getNome());
                    item.put("instituicao", m.getInstituicao());
                    conflitos.add(item);
                }
            }
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("conflitos", conflitos);
        return resp;
    }
}
