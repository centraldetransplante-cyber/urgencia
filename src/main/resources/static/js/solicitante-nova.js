// === SAUR - paciente preemptivo (2026-08-27, ajustado no mesmo dia para
// checkbox unico opcional): alterna a obrigatoriedade/visibilidade do RGCT
// e o rotulo da data conforme o tipo de pedido escolhido. So UX/apresentacao
// - a regra de verdade (RGCT obrigatorio so para urgencia renal comum) mora
// sempre no backend (SolicitacaoOnlineService.criar), nunca so aqui.
// "preemptivo" aqui e so um boolean (o estado .checked do checkbox
// #tipoPreemptivo) - desmarcado = urgencia renal (caso padrao, sem exigir
// nenhum clique do solicitante), marcado = preemptivo. ===
function atualizarTipoSolicitacao(preemptivo) {
    var campoRgct = document.getElementById('pacienteRgct');
    var blocoRgct = document.getElementById('blocoPacienteRgct');
    var labelData = document.getElementById('labelDataSituacaoEspecial');
    var textoAjudaData = document.getElementById('textoAjudaData');
    var labelJustificativa = document.getElementById('labelJustificativa');

    if (campoRgct) {
        campoRgct.required = !preemptivo;
        if (preemptivo) {
            campoRgct.value = '';
        }
    }
    if (blocoRgct) {
        blocoRgct.style.display = preemptivo ? 'none' : '';
    }
    if (labelData) {
        labelData.textContent = preemptivo ? 'Data da solicitação *' : 'Data da urgência *';
    }
    if (textoAjudaData) {
        textoAjudaData.textContent = preemptivo
            ? 'Data do pedido de inserção na lista de espera.'
            : 'Quando a equipe constatou a urgência (não pode ser futura).';
    }
    // Rotulo "Por que a urgencia se aplica" | "Por que a insercao preemptiva
    // se aplica" - mesmo vocabulario de RotuloProcesso.rotuloJustificativa
    // (Java), aqui so espelhado em JS para nao exigir round-trip ao servidor
    // ao trocar o radio de tipo.
    if (labelJustificativa) {
        labelJustificativa.textContent = preemptivo
            ? 'Por que a inserção preemptiva se aplica'
            : 'Por que a urgência se aplica';
    }
}

// Feedback do formulario de Nova solicitacao (Portal do Solicitante):
// contador de caracteres da justificativa clinica e lista de arquivos
// selecionados com opcao de remover um por um. Nao valida tipo/tamanho real
// dos arquivos (isso e feito no servidor) - so ajuda o solicitante a ver e
// corrigir o que escolheu antes de enviar.
document.addEventListener('DOMContentLoaded', function () {
    // Sincroniza o estado inicial (obrigatoriedade do RGCT/rotulo da data)
    // com o checkbox de tipo ja marcado/desmarcado pelo servidor (rascunho
    // salvo ou reexibicao apos erro de validacao).
    var checkboxPreemptivoInicial = document.getElementById('tipoPreemptivo');
    atualizarTipoSolicitacao(!!(checkboxPreemptivoInicial && checkboxPreemptivoInicial.checked));

    // Bloqueia data futura no campo "Data em que a urgencia foi identificada".
    // Calculado em JS (nao via Thymeleaf/SpringEL no atributo "max") porque
    // T(java.time.LocalDate) e instanciacao de objeto sao bloqueados nesse
    // contexto de expressao pelo Thymeleaf (TemplateProcessingException:
    // "Instantiation of new objects and access to static classes or
    // parameters is forbidden in this context").
    var campoData = document.getElementById('dataSituacaoEspecial');
    if (campoData) {
        var hoje = new Date();
        var iso = hoje.getFullYear() + '-'
            + String(hoje.getMonth() + 1).padStart(2, '0') + '-'
            + String(hoje.getDate()).padStart(2, '0');
        campoData.max = iso;
    }

    // Aviso ao sair com a solicitacao preenchida e nao enviada (ver
    // aviso-sair-sem-salvar.js). Adicional ao botao manual "Salvar
    // rascunho" - o rascunho so guarda os 4 campos de texto abaixo, nunca os
    // arquivos selecionados, entao mesmo quem ja salvou um rascunho ainda
    // pode perder os documentos anexados ao fechar a aba sem enviar de
    // verdade. Por isso "Salvar rascunho" NAO desarma este aviso.
    if (typeof window.iniciarAvisoSairSemSalvar === 'function') {
        window.iniciarAvisoSairSemSalvar({
            campos: [
                document.getElementById('pacienteNome'),
                document.getElementById('pacienteRgct'),
                document.getElementById('dataSituacaoEspecial'),
                document.getElementById('justificativaClinica'),
                document.getElementById('documentos')
            ]
        });
    }

    // Rola ate o erro de validacao do servidor ao reexibir o formulario
    // (2026-08-21, corrige "as mensagens de erro ficam muito longe do campo
    // e nao permitem visualizacao"). Prioridade: o campo destacado (.is-
    // invalid, mais preciso - o solicitante ve o campo E a mensagem juntos)
    // e, na falta de um campo mapeado, o alerta generico do topo (erro que
    // nao pertence a nenhum campo especifico, ex. "sem equipe vinculada").
    var alvoErro = document.querySelector('.is-invalid') || document.getElementById('alertaErroGeral');
    if (alvoErro) {
        alvoErro.scrollIntoView({behavior: 'smooth', block: 'center'});
        if (typeof alvoErro.focus === 'function') {
            alvoErro.focus({preventScroll: true});
        }
    }

    var LIMITE_ATENCAO = 80; // abaixo disso, sinal visual de "provavelmente incompleto"

    var textarea = document.getElementById('justificativaClinica');
    var contador = document.getElementById('justificativaContador');
    if (textarea && contador) {
        var atualizarContador = function () {
            var tamanho = textarea.value.length;
            contador.textContent = tamanho + ' caractere' + (tamanho === 1 ? '' : 's');
            contador.classList.toggle('text-warning', tamanho > 0 && tamanho < LIMITE_ATENCAO);
            contador.classList.toggle('fw-semibold', tamanho > 0 && tamanho < LIMITE_ATENCAO);
        };
        textarea.addEventListener('input', atualizarContador);
        atualizarContador();
    }

    // Salvar rascunho (AJAX, sem recarregar a pagina) - ver
    // SolicitanteController#salvarRascunho. Nenhum campo e validado aqui: o
    // rascunho pode ser salvo parcialmente preenchido.
    var form = document.getElementById('formNovaSolicitacao');
    var btnSalvarRascunho = document.getElementById('btnSalvarRascunho');
    var rascunhoStatus = document.getElementById('rascunhoStatus');
    if (form && btnSalvarRascunho) {
        var rascunhoUrl = form.getAttribute('data-rascunho-url');
        var csrfMeta = document.querySelector('meta[name="_csrf"]');
        var csrfHeaderMeta = document.querySelector('meta[name="_csrf_header"]');
        var csrfToken = csrfMeta ? csrfMeta.content : null;
        var csrfHeader = csrfHeaderMeta ? csrfHeaderMeta.content : null;

        btnSalvarRascunho.addEventListener('click', function () {
            var campoNome = document.getElementById('pacienteNome');
            var campoRgct = document.getElementById('pacienteRgct');
            var campoData = document.getElementById('dataSituacaoEspecial');
            var campoJustificativa = document.getElementById('justificativaClinica');
            var campoEmailAdicional = document.getElementById('emailAdicional');
            var checkboxPreemptivo = document.getElementById('tipoPreemptivo');

            var params = new URLSearchParams();
            params.set('pacienteNome', (campoNome && campoNome.value) || '');
            params.set('pacienteRgct', (campoRgct && campoRgct.value) || '');
            params.set('dataSituacaoEspecial', (campoData && campoData.value) || '');
            params.set('justificativaClinica', (campoJustificativa && campoJustificativa.value) || '');
            params.set('emailAdicional', (campoEmailAdicional && campoEmailAdicional.value) || '');
            params.set('preemptivo', String(!!(checkboxPreemptivo && checkboxPreemptivo.checked)));

            var headers = {'Content-Type': 'application/x-www-form-urlencoded'};
            if (csrfHeader && csrfToken) {
                headers[csrfHeader] = csrfToken;
            }

            btnSalvarRascunho.disabled = true;
            fetch(rascunhoUrl, {
                method: 'POST',
                headers: headers,
                credentials: 'same-origin',
                body: params.toString()
            }).then(function (resp) {
                if (!resp.ok) {
                    throw new Error('Falha ao salvar rascunho');
                }
                return resp.json();
            }).then(function () {
                var agora = new Date();
                var hh = String(agora.getHours()).padStart(2, '0');
                var mm = String(agora.getMinutes()).padStart(2, '0');
                if (rascunhoStatus) {
                    rascunhoStatus.textContent = 'Rascunho salvo às ' + hh + ':' + mm;
                }
                if (typeof mostrarToast === 'function') {
                    mostrarToast('Rascunho salvo.', 'success');
                }
            }).catch(function () {
                if (typeof mostrarToast === 'function') {
                    // 'danger' nao e um tipo reconhecido por toast.js (so
                    // 'success'/'error'/'info' - ver static/js/toast.js) e cai
                    // no fallback neutro azul de "info", alem de gerar a
                    // classe CSS "toast-danger" (inexistente em app.css,
                    // barra lateral cinza). 'error' e o tipo correto.
                    mostrarToast('Não foi possível salvar o rascunho. Tente novamente.', 'error');
                } else if (rascunhoStatus) {
                    rascunhoStatus.textContent = 'Falha ao salvar rascunho.';
                }
            }).finally(function () {
                btnSalvarRascunho.disabled = false;
            });
        });
    }

    var input = document.getElementById('documentos');
    var lista = document.getElementById('documentosSelecionados');
    var resumo = document.getElementById('documentosResumo');
    var resumoTexto = document.getElementById('documentosResumoTexto');
    var avisoTamanho = document.getElementById('documentosAvisoTamanho');
    var avisoTamanhoTexto = document.getElementById('documentosAvisoTamanhoTexto');
    if (!input || !lista) {
        return;
    }

    // Limiar de aviso (nao bloqueante) para a SOMA de todos os arquivos
    // selecionados. O mesmo limite documentado por arquivo individual
    // (25 MB, application.yml: spring.servlet.multipart.max-file-size) -
    // o servidor tambem tem um limite de REQUISICAO inteira
    // (max-request-size: 30MB), entao varios arquivos grandes juntos podem
    // ser recusados mesmo cada um respeitando o limite individual.
    var LIMITE_TOTAL_BYTES = 25 * 1024 * 1024;

    function formatarTamanho(bytes) {
        if (bytes < 1024) return bytes + ' B';
        if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(0) + ' KB';
        return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
    }

    // O input file nao permite remover um arquivo individualmente da selecao
    // nativa - reescrevemos input.files com um DataTransfer contendo so os
    // arquivos que sobraram, e disparamos 'change' para re-renderizar a lista.
    function removerArquivo(indice) {
        var restantes = Array.prototype.slice.call(input.files || []);
        restantes.splice(indice, 1);
        var dt = new DataTransfer();
        restantes.forEach(function (arquivo) { dt.items.add(arquivo); });
        input.files = dt.files;
        renderizar();
    }

    // Atualiza o aviso "N documento(s) selecionado(s)" acima da lista - some
    // por completo quando nao ha nenhum arquivo selecionado, para nao dar a
    // falsa impressao de que algo foi anexado. Mostra tambem a soma do
    // tamanho de todos os arquivos.
    function atualizarResumo(total, tamanhoTotalBytes) {
        if (!resumo || !resumoTexto) {
            return;
        }
        if (total === 0) {
            resumo.classList.add('d-none');
            return;
        }
        resumo.classList.remove('d-none');
        resumoTexto.textContent = total + ' documento' + (total === 1 ? '' : 's') + ' selecionado' + (total === 1 ? '' : 's')
            + ' — total: ' + formatarTamanho(tamanhoTotalBytes);
    }

    // Aviso preventivo (nao bloqueante) quando a soma dos arquivos passa do
    // limiar razoavel - so um alerta visivel, o envio continua liberado (o
    // servidor decide de verdade se aceita ou recusa).
    function atualizarAvisoTamanho(total, tamanhoTotalBytes) {
        if (!avisoTamanho || !avisoTamanhoTexto) {
            return;
        }
        if (total === 0 || tamanhoTotalBytes <= LIMITE_TOTAL_BYTES) {
            avisoTamanho.classList.add('d-none');
            return;
        }
        avisoTamanho.classList.remove('d-none');
        avisoTamanhoTexto.textContent = 'O total selecionado (' + formatarTamanho(tamanhoTotalBytes) + ') e grande. '
            + 'O envio pode demorar mais ou ser recusado pelo servidor — considere reduzir a quantidade/tamanho dos arquivos.';
    }

    function renderizar() {
        var arquivos = Array.prototype.slice.call(input.files || []);
        var tamanhoTotalBytes = arquivos.reduce(function (soma, arquivo) { return soma + arquivo.size; }, 0);
        lista.replaceChildren();
        atualizarResumo(arquivos.length, tamanhoTotalBytes);
        atualizarAvisoTamanho(arquivos.length, tamanhoTotalBytes);
        arquivos.forEach(function (arquivo, indice) {
            var item = document.createElement('li');
            item.className = 'list-group-item d-flex justify-content-between align-items-start gap-2 py-2 px-3 small';

            var infoDiv = document.createElement('div');
            infoDiv.className = 'd-flex align-items-center gap-2';
            infoDiv.style.minWidth = '0';

            var icone = document.createElement('i');
            icone.className = 'bi bi-file-earmark-text-fill text-primary flex-shrink-0';

            var nomeSpan = document.createElement('span');
            // "text-break" (utilitario do Bootstrap) em vez de "text-truncate":
            // "text-truncate" corta o nome do arquivo com reticencias em vez de
            // quebrar linha, escondendo o nome real (bug relatado pelo usuario -
            // a lista de anexos aparecia "cortada", sem dar para confirmar qual
            // arquivo tinha sido selecionado).
            nomeSpan.className = 'text-break';
            nomeSpan.textContent = arquivo.name + ' (' + formatarTamanho(arquivo.size) + ')';

            infoDiv.appendChild(icone);
            infoDiv.appendChild(nomeSpan);

            var btnRemover = document.createElement('button');
            btnRemover.type = 'button';
            btnRemover.className = 'btn btn-sm btn-outline-danger py-0 px-2 flex-shrink-0';
            btnRemover.title = 'Remover ' + arquivo.name;
            btnRemover.setAttribute('aria-label', 'Remover arquivo ' + arquivo.name);
            btnRemover.innerHTML = '<i class="bi bi-x-lg"></i>';
            btnRemover.addEventListener('click', function () { removerArquivo(indice); });

            item.appendChild(infoDiv);
            item.appendChild(btnRemover);
            lista.appendChild(item);
        });
    }

    input.addEventListener('change', renderizar);
});
