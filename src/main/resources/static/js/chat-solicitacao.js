/**
 * Chat da SolicitacaoOnline (portal do solicitante, triagem do operador e
 * detalhe do processo) - poll AJAX periodico + envio/apagar sem recarregar
 * a pagina inteira. Antes de 2026-07-28 cada envio de mensagem era um
 * <form method="post"> classico (POST + redirect + GET da pagina inteira) e
 * so era possivel ver mensagem nova recarregando manualmente; ver CLAUDE.md
 * para o diagnostico completo.
 */
function iniciarChatSolicitacao(cfg) {
    var chatBox = document.querySelector(cfg.chatBoxSelector);
    var form = document.querySelector(cfg.formSelector);
    var input = form ? form.querySelector(cfg.inputSelector) : null;
    var emptyMsg = cfg.emptySelector ? document.querySelector(cfg.emptySelector) : null;
    var badgeTotal = cfg.badgeTotalSelector ? document.querySelector(cfg.badgeTotalSelector) : null;
    var badgeNaoLida = cfg.badgeNaoLidaSelector ? document.querySelector(cfg.badgeNaoLidaSelector) : null;

    var csrfMeta = document.querySelector('meta[name="_csrf"]');
    var csrfHeaderMeta = document.querySelector('meta[name="_csrf_header"]');
    var csrfToken = csrfMeta ? csrfMeta.content : null;
    var csrfHeader = csrfHeaderMeta ? csrfHeaderMeta.content : null;

    var idsRecebidosConhecidos = null; // null ate o 1o poll (evita notificar do que ja estava na tela)
    var pollAtivo = true;
    var assinaturaRenderizada = null;  // ver assinatura()/atualizarTela()

    function escapeHtml(s) {
        var d = document.createElement('div');
        d.textContent = s == null ? '' : s;
        return d.innerHTML;
    }

    function confirmarApagar() {
        var msg = 'Apagar esta mensagem?';
        if (typeof window.confirmarAcao === 'function') return window.confirmarAcao(msg);
        // Tela sem o modal generico carregado: mesma rede de seguranca
        // documentada em confirmar-acao.js - nunca apagar sem confirmar.
        return Promise.resolve(window.confirm(msg));
    }

    function estaProximoDoFim() {
        if (!chatBox) return true;
        return (chatBox.scrollHeight - chatBox.clientHeight - chatBox.scrollTop) < 80;
    }

    function aplicarTimestampsRelativos() {
        (chatBox ? chatBox.querySelectorAll('.ts-relative') : []).forEach(function (el) {
            var data = el.getAttribute('data-ts');
            if (!data) return;
            var partes = data.split(/[-\s:]/);
            var d = new Date(partes[0], partes[1] - 1, partes[2], partes[3], partes[4], partes[5]);
            var diff = Math.floor((Date.now() - d.getTime()) / 1000);
            var texto;
            if (diff < 60) texto = 'agora';
            else if (diff < 3600) texto = Math.floor(diff / 60) + ' min atras';
            else if (diff < 86400) texto = Math.floor(diff / 3600) + ' h atras';
            else if (diff < 172800) texto = 'ontem';
            else texto = el.getAttribute('data-formatada') || el.textContent;
            el.textContent = texto;
        });
    }

    function renderMensagem(msg) {
        var alinhamento = msg.deVoce ? 'justify-content-end' : 'justify-content-start';
        var corpo;
        if (msg.deletada) {
            var txt = msg.deVoce ? 'Voce apagou esta mensagem.' : ('Mensagem apagada ' + cfg.labelApagadaOutro + '.');
            corpo = '<div class="rounded-3 px-3 py-2 small text-muted fst-italic border" style="max-width:80%;">'
                + escapeHtml(txt) + '</div>';
        } else {
            var bolha = msg.deVoce ? 'bg-primary text-white' : 'bg-light border';
            var nomeCls = msg.deVoce ? '' : 'text-muted';
            var tsCls = msg.deVoce ? 'text-white-50' : 'text-muted';
            var apagarBtn = '';
            if (msg.podeApagar) {
                apagarBtn = '<button type="button" class="btn btn-sm p-0 border-0 bg-transparent text-danger btn-apagar-msg-chat" '
                    + 'data-id="' + msg.id + '" title="Apagar mensagem" aria-label="Apagar mensagem">'
                    + '<i class="bi bi-trash"></i></button>';
            }
            var check = '';
            if (msg.deVoce) {
                check = msg.lida
                    ? '<i class="bi bi-check-all" style="font-size:.75rem;"></i>'
                    : '<i class="bi bi-check" style="font-size:.75rem;"></i>';
            }
            corpo = '<div class="rounded-3 px-3 py-2 position-relative ' + bolha + '" style="max-width:80%;">'
                + '<div class="d-flex justify-content-between align-items-start gap-2">'
                + '<div class="small fw-semibold ' + nomeCls + '">' + escapeHtml(msg.nomeRemetente) + '</div>'
                + apagarBtn
                + '</div>'
                + '<div class="small" style="white-space: pre-wrap;">' + escapeHtml(msg.texto) + '</div>'
                + '<div class="small opacity-75 mt-1 d-flex align-items-center gap-1 ' + tsCls + '">'
                + '<span class="ts-relative" data-ts="' + msg.dataEnvioIso + '" data-formatada="' + escapeHtml(msg.dataEnvioFormatada) + '">'
                + escapeHtml(msg.dataEnvioFormatada) + '</span>'
                + check
                + '</div>'
                + '</div>';
        }
        return '<div class="mb-2 d-flex ' + alinhamento + '" data-msg-id="' + msg.id + '">' + corpo + '</div>';
    }

    function atualizarBadges(mensagens) {
        if (badgeTotal) {
            if (mensagens.length) {
                badgeTotal.textContent = mensagens.length + ' total';
                badgeTotal.classList.remove('d-none');
            } else {
                badgeTotal.classList.add('d-none');
            }
        }
        // O poll ja marca como lida no servidor - o badge de "nao lida" so
        // faz sentido no 1o carregamento (renderizado pelo servidor).
        if (badgeNaoLida) badgeNaoLida.classList.add('d-none');
    }

    /**
     * Assinatura do estado visivel das mensagens. Serve para NAO reescrever o
     * chatBox quando o poll (a cada 5s) devolve exatamente a mesma conversa -
     * reescrever innerHTML destroi os filhos, zera o scrollTop e cancela
     * qualquer selecao de texto em andamento. Cobre tudo que renderMensagem
     * usa e pode mudar entre polls (texto editado nunca muda hoje, mas
     * "deletada" e "lida" mudam).
     */
    function assinatura(mensagens) {
        return mensagens.map(function (m) {
            return [m.id, m.deletada ? 1 : 0, m.lida ? 1 : 0, m.podeApagar ? 1 : 0, m.texto].join('');
        }).join('');
    }

    function atualizarTela(mensagens) {
        if (emptyMsg) emptyMsg.classList.toggle('d-none', mensagens.length > 0);
        if (chatBox) {
            var nova = assinatura(mensagens);
            if (nova !== assinaturaRenderizada) {
                var seguirFim = estaProximoDoFim();
                var scrollAnterior = chatBox.scrollTop;
                chatBox.classList.toggle('d-none', mensagens.length === 0);
                chatBox.innerHTML = mensagens.map(renderMensagem).join('');
                assinaturaRenderizada = nova;
                // Quem esta lendo o historico (rolado pra cima) mantem a
                // posicao; quem estava acompanhando o fim continua no fim.
                chatBox.scrollTop = seguirFim ? chatBox.scrollHeight : scrollAnterior;
            }
            // Timestamps sao relativos ("3 min atras"): precisam ser
            // reavaliados a cada poll mesmo sem mensagem nova.
            aplicarTimestampsRelativos();
        }
        atualizarBadges(mensagens);
    }

    /**
     * Rola ate o card do chat (cfg.collapseAlvoSelector, se informado) e
     * expande o collapse do Bootstrap se estiver fechado - acionado ao
     * clicar no toast de "mensagem nova" (2026-08-07, chat do avaliador
     * nasce recolhido quando ainda nao ha conversa nenhuma, entao o toast
     * precisa poder abrir o card sozinho, sem o usuario precisar achar o
     * card na tela). Sem cfg.collapseAlvoSelector, o toast so avisa (sem
     * 3o argumento em mostrarToast) - retrocompativel com as 3 telas que ja
     * mostravam o chat sempre expandido antes desta mudanca.
     */
    function irParaOChat() {
        if (!cfg.collapseAlvoSelector) return;
        var alvo = document.querySelector(cfg.collapseAlvoSelector);
        if (!alvo) return;
        alvo.scrollIntoView({behavior: 'smooth', block: 'center'});
        if (typeof bootstrap !== 'undefined' && bootstrap.Collapse) {
            bootstrap.Collapse.getOrCreateInstance(alvo, {toggle: false}).show();
        } else {
            alvo.classList.add('show');
        }
        if (input) input.focus({preventScroll: true});
    }

    function detectarNovasMensagens(mensagens) {
        var idsDoOutroLado = mensagens.filter(function (m) { return !m.deVoce && !m.deletada; })
            .map(function (m) { return m.id; });
        if (idsRecebidosConhecidos !== null) {
            var novas = idsDoOutroLado.filter(function (id) { return idsRecebidosConhecidos.indexOf(id) === -1; });
            if (novas.length > 0) {
                if (typeof tocarNotificacao === 'function') tocarNotificacao();
                if (typeof mostrarToast === 'function') {
                    mostrarToast(cfg.notifMensagem, 'info', cfg.collapseAlvoSelector ? irParaOChat : undefined);
                }
            }
        }
        idsRecebidosConhecidos = idsDoOutroLado;
    }

    function poll() {
        if (!pollAtivo) return;
        fetch(cfg.pollUrl, {headers: {'Accept': 'application/json'}, credentials: 'same-origin'})
            .then(function (r) { return r.ok ? r.json() : Promise.reject(new Error('HTTP ' + r.status)); })
            .then(function (data) {
                var mensagens = data.mensagens || [];
                detectarNovasMensagens(mensagens);
                atualizarTela(mensagens);
                if (form && data.podeEnviar === false) form.classList.add('d-none');
            })
            .catch(function () { /* falha de rede pontual - proxima tentativa em breve, sem incomodar o usuario */ });
    }

    if (form) {
        form.addEventListener('submit', function (e) {
            e.preventDefault();
            if (!input || !input.value.trim()) return;
            var texto = input.value;
            var headers = {'Content-Type': 'application/x-www-form-urlencoded'};
            if (csrfHeader && csrfToken) headers[csrfHeader] = csrfToken;
            input.disabled = true;
            fetch(cfg.sendUrl, {method: 'POST', headers: headers, credentials: 'same-origin', body: 'texto=' + encodeURIComponent(texto)})
                .then(function (r) { return r.json().then(function (body) { return {ok: r.ok, body: body}; }); })
                .then(function (res) {
                    if (!res.ok) {
                        if (typeof mostrarToast === 'function') mostrarToast(res.body.erro || 'Nao foi possivel enviar a mensagem.', 'error');
                        return;
                    }
                    input.value = '';
                    poll();
                })
                .catch(function () {
                    if (typeof mostrarToast === 'function') mostrarToast('Falha de conexao ao enviar a mensagem.', 'error');
                })
                .finally(function () {
                    input.disabled = false;
                    input.focus();
                });
        });
    }

    if (chatBox) {
        chatBox.addEventListener('click', function (e) {
            var btn = e.target.closest('.btn-apagar-msg-chat');
            if (!btn) return;
            // Modal padrao do sistema (confirmar-acao.js), como as demais acoes
            // destrutivas. O confirm() nativo era ignorado (retorna false) em
            // navegadores com "impedir dialogos adicionais" marcado, deixando o
            // botao de apagar sem nenhum efeito. O fallback para confirm() que
            // existe DENTRO de confirmarAcao() e proposital e continua valendo.
            confirmarApagar().then(function (confirmado) {
                if (!confirmado) return;
                var headers = {};
                if (csrfHeader && csrfToken) headers[csrfHeader] = csrfToken;
                fetch(cfg.deleteUrlBase + btn.getAttribute('data-id') + '/apagar/ajax',
                    {method: 'POST', headers: headers, credentials: 'same-origin'})
                    .then(function (r) { return r.ok ? poll() : Promise.reject(new Error('HTTP ' + r.status)); })
                    .catch(function () {
                        if (typeof mostrarToast === 'function') mostrarToast('Nao foi possivel apagar a mensagem.', 'error');
                    });
            });
        });
    }

    document.addEventListener('visibilitychange', function () {
        pollAtivo = !document.hidden;
        if (pollAtivo) poll();
    });

    if (chatBox) chatBox.scrollTop = chatBox.scrollHeight;
    poll();
    setInterval(poll, cfg.intervaloMs || 5000);
}
