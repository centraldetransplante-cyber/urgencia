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
