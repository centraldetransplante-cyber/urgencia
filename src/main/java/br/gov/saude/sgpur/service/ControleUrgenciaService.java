package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.ControleUrgencia;
import br.gov.saude.sgpur.domain.SituacaoUrgencia;
import br.gov.saude.sgpur.repository.ControleUrgenciaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Servico para o Controle de Urgencias para Transplante Renal.
 * <p>
 * Regra: a urgencia dura 30 dias. Se o receptor permanecer ativo na lista unica,
 * faz-se a renovacao por mais 30 dias, sem nova solicitacao da equipe.
 */
@Service
public class ControleUrgenciaService {

    /** Duracao padrao de cada ciclo de urgencia (30 dias). */
    public static final int DIAS_URGENCIA = 30;

    private final ControleUrgenciaRepository repo;

    public ControleUrgenciaService(ControleUrgenciaRepository repo) {
        this.repo = repo;
    }

    /**
     * Cria um novo registro de urgencia. A data de vencimento e calculada
     * automaticamente como {@code hoje + 30 dias}.
     */
    @Transactional
    public ControleUrgencia criar(ControleUrgencia c) {
        c.setSituacao(SituacaoUrgencia.ATIVA);
        if (c.getDataVencimento() == null) {
            c.setDataVencimento(LocalDate.now().plusDays(DIAS_URGENCIA));
        }
        return repo.save(c);
    }

    /**
     * Renova a urgencia por mais 30 dias.
     * <p>
     * A situacao passa para RENOVADA e o vencimento avanca {@code hoje + 30 dias}.
     */
    @Transactional
    public ControleUrgencia renovar(Long id) {
        ControleUrgencia c = repo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Registro nao encontrado: " + id));
        c.setSituacao(SituacaoUrgencia.RENOVADA);
        c.setDataUltimaRenovacao(LocalDate.now());
        c.setDataVencimento(LocalDate.now().plusDays(DIAS_URGENCIA));
        return repo.save(c);
    }

    /**
     * Cancela a urgencia (receptor nao esta mais ativo na lista unica).
     */
    @Transactional
    public ControleUrgencia cancelar(Long id, String observacoes) {
        ControleUrgencia c = repo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Registro nao encontrado: " + id));
        c.setSituacao(SituacaoUrgencia.CANCELADA);
        if (observacoes != null && !observacoes.isBlank()) {
            c.setObservacoes(observacoes);
        }
        return repo.save(c);
    }

    /**
     * Atualiza um registro com os dados vindos do formulario de edicao
     * ({@code templates/controle-urgencias/form.html}): nome, rgct, equipe,
     * abo, <b>data de vencimento</b> e observacoes - exatamente os campos que
     * aquele formulario envia.
     *
     * <p><b>Por que o vencimento e editavel aqui:</b> a prorrogacao normal de
     * 30 dias continua sendo o botao "Renovar" ({@link #renovar(Long)}, que
     * alem da data marca a situacao como RENOVADA e carimba
     * {@code dataUltimaRenovacao}). Esta edicao serve para <i>corrigir</i> uma
     * data digitada errada no cadastro - sem ela, a unica forma de arrumar um
     * vencimento errado seria "renovar", falsificando um ciclo de renovacao que
     * nunca existiu. Editar a data por aqui NAO altera a situacao nem registra
     * renovacao: sao operacoes com semanticas distintas e nao concorrentes.
     * (Ate 2026-07-29 este metodo simplesmente ignorava o campo: o formulario
     * exibia a data, o operador a alterava, recebia "Urgencia atualizada com
     * sucesso" e o vencimento continuava o antigo - prorrogacao fantasma numa
     * tela cuja unica funcao e controlar o prazo de 30 dias.)
     *
     * <p><b>Campos ignorados DE PROPOSITO</b> (nao "corrigir" numa proxima
     * vistoria - copia-los seria o bug, nao a correcao): o formulario nao envia
     * nenhum deles, logo o objeto recebido traz apenas o valor default do
     * construtor/inicializador, e copiar esse default sobrescreveria o estado
     * real do registro.
     * <ul>
     *   <li>{@code situacao} - chega sempre ATIVA (inicializador do campo);
     *       copiar "ressuscitaria" em silencio uma urgencia CANCELADA/EXPIRADA
     *       a cada correcao de nome. Muda so em renovar/cancelar.</li>
     *   <li>{@code ativo} - chega sempre {@code true} pelo mesmo motivo.</li>
     *   <li>{@code dataUltimaRenovacao} - carimbo de sistema, exclusivo de
     *       {@link #renovar(Long)}.</li>
     *   <li>{@code dataCadastro} - {@code updatable = false} na entidade.</li>
     *   <li>{@code processoId} - vinculo com o processo de origem, nao
     *       editavel por esta tela.</li>
     *   <li>{@code id} - chave usada para localizar o registro gerenciado.</li>
     * </ul>
     *
     * @throws IllegalStateException se a data de vencimento vier vazia - a
     *         coluna e {@code NOT NULL} e o formulario a marca como obrigatoria;
     *         falhar alto e melhor do que descartar a edicao em silencio.
     * @throws org.springframework.orm.ObjectOptimisticLockingFailureException
     *         se a {@code versao} trazida pelo formulario (campo oculto,
     *         carregado junto com a tela de edicao) nao bater com a versao
     *         atual do registro no banco - ou seja, alguem alterou o registro
     *         (ex.: clicou "Renovar") depois que esta tela foi aberta. Sem esta
     *         checagem explicita, {@code @Version} sozinho NAO protege este
     *         metodo: como ele sempre recarrega a entidade GERENCIADA via
     *         {@code findById} e muta essa instancia (nunca a {@code dados}
     *         recebida do formulario), o {@code save()} sempre flusharia com a
     *         versao mais recente ja lida agora mesmo, nunca a versao antiga
     *         que o navegador tinha - o conflito precisa ser comparado a mao
     *         contra o valor que veio do formulario. Reaproveita o mesmo
     *         handler generico de {@code GlobalExceptionHandler} ja usado para
     *         conflitos de escrita concorrente em outras entidades.
     */
    @Transactional
    public ControleUrgencia atualizar(ControleUrgencia dados) {
        ControleUrgencia c = repo.findById(dados.getId())
            .orElseThrow(() -> new IllegalArgumentException("Registro nao encontrado: " + dados.getId()));
        if (dados.getDataVencimento() == null) {
            throw new IllegalStateException(
                "Informe a data de vencimento da urgencia. Para prorrogar por mais "
                + DIAS_URGENCIA + " dias, use o botao Renovar.");
        }
        if (c.getVersao() != null && !c.getVersao().equals(dados.getVersao())) {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(
                ControleUrgencia.class, dados.getId());
        }
        c.setNomePaciente(dados.getNomePaciente());
        c.setRgct(dados.getRgct());
        c.setEquipe(dados.getEquipe());
        c.setAbo(dados.getAbo());
        c.setDataVencimento(dados.getDataVencimento());
        c.setObservacoes(dados.getObservacoes());
        return repo.save(c);
    }

    /**
     * Retorna todos os registros ativos ordenados por vencimento (mais urgentes primeiro).
     */
    public List<ControleUrgencia> listarAtivas() {
        return repo.findAllAtivasOrdenadas();
    }

    /**
     * Retorna todos os registros (inclusive inativos).
     */
    public List<ControleUrgencia> listarTodas() {
        return repo.findAll();
    }

    /**
     * Retorna urgencias ativas cujo vencimento ja passou ou vence ate a data informada.
     */
    public List<ControleUrgencia> listarAVencerOuVencidas(LocalDate ate) {
        return repo.findAVencerOuVencidas(ate);
    }

    public ControleUrgencia buscarPorId(Long id) {
        return repo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Registro nao encontrado: " + id));
    }

    public long contarPorSituacao(SituacaoUrgencia situacao) {
        return repo.countBySituacao(situacao);
    }
}
