package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.RascunhoSolicitacaoOnline;
import br.gov.saude.sgpur.domain.Sexo;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.RascunhoSolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Rascunho de "Nova solicitacao", salvo pelo proprio solicitante enquanto
 * ainda preenche o formulario (ver javadoc de {@link RascunhoSolicitacaoOnline}
 * para a decisao de modelagem: entidade de staging separada, sem nenhuma
 * validacao obrigatoria, nunca visivel ao operador).
 *
 * <p>Um rascunho por usuario solicitante: {@link #salvar} faz upsert (cria se
 * nao existir, atualiza se ja existir), sempre sobrescrevendo com o estado
 * mais recente do formulario - nao ha historico de versoes de rascunho.
 */
@Service
public class RascunhoSolicitacaoOnlineService {

    private final RascunhoSolicitacaoOnlineRepository repository;
    private final UsuarioRepository usuarioRepository;

    public RascunhoSolicitacaoOnlineService(RascunhoSolicitacaoOnlineRepository repository,
                                            UsuarioRepository usuarioRepository) {
        this.repository = repository;
        this.usuarioRepository = usuarioRepository;
    }

    public Optional<RascunhoSolicitacaoOnline> buscarPorUsuario(Long usuarioId) {
        return repository.findByUsuarioSolicitanteId(usuarioId);
    }

    /**
     * Cria ou atualiza o rascunho do usuario logado. Nenhum campo e
     * obrigatorio aqui de proposito - o rascunho pode estar parcialmente
     * preenchido (ou ate totalmente vazio); a validacao completa so acontece
     * no envio final ({@code SolicitacaoOnlineService#criar}).
     */
    @Transactional
    public RascunhoSolicitacaoOnline salvar(Long usuarioId, String pacienteNome, String pacienteRgct,
                                            LocalDate pacienteDataNascimento, String pacienteCpf, Sexo pacienteSexo,
                                            String pacienteNomeMae, LocalDate dataSituacaoEspecial,
                                            String justificativaClinica) {
        RascunhoSolicitacaoOnline r = repository.findByUsuarioSolicitanteId(usuarioId)
            .orElseGet(RascunhoSolicitacaoOnline::new);
        if (r.getId() == null) {
            Usuario usuario = usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new IllegalArgumentException("Usuario nao encontrado: " + usuarioId));
            r.setUsuarioSolicitante(usuario);
        }
        r.setPacienteNome(pacienteNome);
        r.setPacienteRgct(pacienteRgct);
        r.setPacienteDataNascimento(pacienteDataNascimento);
        r.setPacienteCpf(pacienteCpf);
        r.setPacienteSexo(pacienteSexo);
        r.setPacienteNomeMae(pacienteNomeMae);
        r.setDataSituacaoEspecial(dataSituacaoEspecial);
        r.setJustificativaClinica(justificativaClinica);
        r.setAtualizadoEm(LocalDateTime.now());
        return repository.save(r);
    }

    /**
     * Apaga o rascunho do usuario, se houver. Chamado tanto apos o envio
     * final bem-sucedido (o rascunho virou uma SolicitacaoOnline de verdade,
     * nao faz mais sentido mante-lo) quanto por um descarte explicito do
     * usuario.
     */
    @Transactional
    public void apagar(Long usuarioId) {
        repository.deleteByUsuarioSolicitanteId(usuarioId);
    }
}
