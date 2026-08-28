// === SAUR - Cadastro de processo: limite de medicos avaliadores selecionados
//     + aviso de possivel conflito de equipe/instituicao (Opcao A do
//     docs/RELATORIO-CONFIRMACAO-CONFLITO-EQUIPE-2026-08.md) ===
(function () {
    var container = document.getElementById('listaMedicos');
    var contador = document.getElementById('contador');
    if (!container || !contador) return;

    var max = parseInt(container.dataset.maxAvaliadores, 10);
    if (isNaN(max)) {
        console.error('SAUR: data-max-avaliadores ausente ou invalido; usando 3 (regra de negocio: sempre 3 avaliadores).');
        max = 3;
    }
    var checks = document.querySelectorAll('.medico-check');
    var campoEquipe = document.querySelector('[name="solicitanteEquipe"]');
    // URL do endpoint de conflito, montada pelo template via data-attribute
    // (nao hardcoded aqui - respeita o context-path/@{...} do Thymeleaf).
    var urlConflito = container.dataset.conflitoEquipeUrl;

    // Guarda contra re-perguntar para o MESMO trio ja consultado (evita fetch
    // repetido ao alternar entre dois medicos sem conflito, por exemplo).
    var ultimoTrioConsultado = null;

    function atualizar() {
        var marcadosEls = document.querySelectorAll('.medico-check:checked');
        var marcados = marcadosEls.length;
        contador.textContent = marcados;
        checks.forEach(function (c) {
            c.disabled = (!c.checked && marcados >= max);
        });
        verificarConflitoSeCompleto(marcadosEls);
    }

    function idsOrdenados(marcadosEls) {
        var ids = [];
        marcadosEls.forEach(function (c) { ids.push(c.value); });
        ids.sort();
        return ids;
    }

    function verificarConflitoSeCompleto(marcadosEls) {
        if (!urlConflito || marcadosEls.length !== max) {
            // Trio incompleto (ou desfeito): libera a checagem para o proximo
            // trio que vier a se formar.
            ultimoTrioConsultado = null;
            return;
        }
        var equipe = campoEquipe ? campoEquipe.value : '';
        if (!equipe || !equipe.trim()) return;

        var ids = idsOrdenados(marcadosEls);
        var chaveTrio = ids.join(',') + '|' + equipe;
        if (chaveTrio === ultimoTrioConsultado) return; // mesmo trio+equipe ja perguntado
        ultimoTrioConsultado = chaveTrio;

        var url = urlConflito + '?equipe=' + encodeURIComponent(equipe)
            + '&medicoIds=' + ids.map(encodeURIComponent).join(',');

        fetch(url, { headers: { 'Accept': 'application/json' } })
            .then(function (resp) {
                if (!resp.ok) throw new Error('resposta nao-ok');
                return resp.json();
            })
            .then(function (data) {
                var conflitos = data && data.conflitos ? data.conflitos : [];
                if (conflitos.length === 0) return;
                var nomes = conflitos.map(function (c) {
                    return c.nome + ' (' + c.instituicao + ')';
                }).join('; ');
                var mensagem = 'Um ou mais médicos selecionados parecem ser da mesma '
                    + 'equipe/instituição do solicitante: ' + nomes + '. '
                    + 'Deseja prosseguir com esta seleção mesmo assim?';
                if (typeof window.confirmarAcao === 'function') {
                    window.confirmarAcao(mensagem);
                } else {
                    window.alert(mensagem);
                }
            })
            .catch(function () {
                // Fail-open: falha de rede/endpoint nunca trava o operador -
                // e so um aviso heuristico auxiliar, nao uma regra de negocio.
            });
    }

    checks.forEach(function (c) { c.addEventListener('change', atualizar); });
    if (campoEquipe) {
        // Se a equipe mudar DEPOIS de o trio ja estar completo (ex.: campo
        // pre-preenchido corrigido manualmente), reavalia o conflito.
        campoEquipe.addEventListener('change', function () {
            ultimoTrioConsultado = null;
            atualizar();
        });
        campoEquipe.addEventListener('blur', function () {
            ultimoTrioConsultado = null;
            atualizar();
        });
    }
    atualizar();
})();

// === SAUR - paciente preemptivo (2026-08-27, ajustado no mesmo dia para
// checkbox unico opcional): alterna RGCT (obrigatorio so para urgencia renal
// comum), o rotulo da data e a SUGESTAO de numero da serie correta, ao
// trocar o checkbox de tipo (desmarcado = urgencia renal, marcado =
// preemptivo). So UX/apresentacao - a regra de verdade (obrigatoriedade do
// RGCT, formato/prefixo do numero) mora sempre no backend
// (ProcessoDetalheController/ProcessoService), nunca so aqui. ===
//
// Rastreio da ULTIMA sugestao automatica mostrada no campo "numero"
// (achado A1 da auditoria de 2026-08-27): o controller ja pre-preenche o
// campo com a sugestao da serie inicial (regime manual, 2026), entao o
// campo NUNCA fica vazio no primeiro toggle - "so preenche se vazio" nunca
// disparava. Em vez disso, comparamos o valor atual com a ultima sugestao
// que NOS mesmos colocamos ali: se ainda forem iguais, o operador nao
// digitou nada por cima, entao e seguro re-sugerir na nova serie; se o
// operador corrigiu manualmente, o valor diverge da ultima sugestao e
// nunca sobrescrevemos.
var ultimaSugestaoNumeroProcesso = (function () {
    var campo = document.getElementById('numero');
    return campo ? campo.value.trim() : null;
})();

function atualizarTipoProcesso(preemptivo) {
    var campoRgct = document.querySelector('[name="pacienteRgct"]');
    var blocoRgct = document.getElementById('blocoRgct');
    var labelRgct = document.getElementById('labelPacienteRgct');
    var labelData = document.getElementById('labelDataSituacaoEspecial');
    var campoNumero = document.getElementById('numero');
    var sugestaoUrgencia = document.getElementById('sugestaoNumeroUrgencia');
    var sugestaoPreemptivo = document.getElementById('sugestaoNumeroPreemptivo');
    var textoAjudaObservacoes = document.getElementById('textoAjudaObservacoes');

    if (campoRgct) {
        campoRgct.required = !preemptivo;
        if (preemptivo) {
            campoRgct.value = '';
        }
    }
    if (blocoRgct) {
        blocoRgct.style.display = preemptivo ? 'none' : '';
    }
    if (labelRgct) {
        labelRgct.textContent = 'RGCT / SNT *';
    }
    if (labelData) {
        labelData.textContent = preemptivo ? 'Data da solicitação *' : 'Data de solicitação da urgência renal *';
    }
    // Rotulo do texto de ajuda de "Observacoes" - mesmo vocabulario de
    // RotuloProcesso.rotuloJustificativa (Java), so espelhado em JS para nao
    // exigir round-trip ao servidor ao trocar o checkbox de tipo.
    if (textoAjudaObservacoes) {
        var rotuloJustificativa = preemptivo
            ? 'Por que a inserção preemptiva se aplica'
            : 'Por que a urgência se aplica';
        textoAjudaObservacoes.textContent = "Pré-preenchido com '" + rotuloJustificativa
            + "' enviado(a) pelo solicitante — revise com atenção antes de cadastrar.";
    }
    // Sugestao de numero: re-sugere ao trocar o tipo quando o campo esta
    // vazio OU quando o valor atual ainda e exatamente a ULTIMA sugestao
    // automatica que colocamos ali - nunca sobrescreve uma correcao manual
    // do operador (achado A1 da auditoria de 2026-08-27, §5.7/§9.2 do plano).
    if (campoNumero) {
        var sugestao = preemptivo
            ? (sugestaoPreemptivo ? sugestaoPreemptivo.textContent : '')
            : (sugestaoUrgencia ? sugestaoUrgencia.textContent : '');
        var valorAtual = campoNumero.value.trim();
        if (!valorAtual || valorAtual === ultimaSugestaoNumeroProcesso) {
            campoNumero.value = sugestao;
        }
        ultimaSugestaoNumeroProcesso = sugestao;
    }
}
