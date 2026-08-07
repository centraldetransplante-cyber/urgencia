// === SAUR - Toast de notificacao ===
// Implementacao UNICA de mostrarToast(mensagem, tipo, onClick). Ate 2026-08-04
// existiam DUAS definicoes divergentes: uma inline em layout.html (fragment
// "notificacaoSonora", incluido automaticamente em toda tela com navbar - 24
// telas) e outra em processo-detalhe.js (so a tela de detalhe do processo),
// que sobrescrevia window.mostrarToast por ser carregado depois na mesma
// pagina. A versao de processo-detalhe.js tinha fade-out suave e
// aria-label="Fechar" no botao de fechar; a de layout.html nao tinha nenhum
// dos dois. Unificadas aqui (a versao mais completa), carregado por
// layout.html dentro do fragment "notificacaoSonora" - toda tela que ja
// tinha mostrarToast() disponivel continua tendo, com a mesma assinatura.
//
// 3o parametro `onClick` (opcional, 2026-08-07): quando informado, o toast
// vira clicavel (cursor pointer + role="button") e chama essa funcao ao
// clicar, alem de fechar o proprio toast - usado por chat-solicitacao.js
// para levar direto ao chat que gerou a notificacao (rolar ate o card +
// expandir o collapse se estiver fechado). As ~24 chamadas existentes sem
// esse argumento continuam identicas (nunca clicaveis).
window.mostrarToast = function (mensagem, tipo, onClick) {
    tipo = tipo || 'info';
    var container = document.getElementById('toastContainer');
    if (!container) {
        container = document.createElement('div');
        container.id = 'toastContainer';
        container.className = 'toast-container-sgpur';
        container.setAttribute('role', 'status');
        container.setAttribute('aria-live', 'polite');
        document.body.appendChild(container);
    }
    var icones = {success: 'bi-check-circle-fill text-success', error: 'bi-exclamation-triangle-fill text-danger', info: 'bi-info-circle-fill text-primary'};
    var toast = document.createElement('div');
    toast.className = 'toast-sgpur toast-' + tipo;
    var icone = document.createElement('i');
    icone.className = 'bi toast-icon ' + (icones[tipo] || icones.info);
    var corpo = document.createElement('div');
    corpo.className = 'toast-body';
    corpo.textContent = mensagem;
    var fechar = document.createElement('button');
    fechar.className = 'toast-close';
    fechar.setAttribute('aria-label', 'Fechar');
    fechar.textContent = '×';
    fechar.addEventListener('click', function (e) {
        e.stopPropagation();
        toast.remove();
    });
    toast.appendChild(icone);
    toast.appendChild(corpo);
    toast.appendChild(fechar);
    if (typeof onClick === 'function') {
        toast.classList.add('toast-sgpur-clicavel');
        toast.setAttribute('role', 'button');
        toast.setAttribute('tabindex', '0');
        var ativar = function () { toast.remove(); onClick(); };
        toast.addEventListener('click', ativar);
        toast.addEventListener('keydown', function (e) {
            if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); ativar(); }
        });
    }
    container.appendChild(toast);
    setTimeout(function () {
        toast.style.opacity = '0';
        toast.style.transition = 'opacity .3s';
        setTimeout(function () { toast.remove(); }, 300);
    }, 5000);
};
