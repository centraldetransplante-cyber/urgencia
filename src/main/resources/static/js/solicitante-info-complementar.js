/*
 * Aviso de "sair sem salvar" para a resposta de informacao complementar do
 * Portal do Solicitante (/solicitante/{id}).
 *
 * O campo de texto passou a existir em 2026-08-11 (antes o formulario so
 * aceitava arquivo) e pode carregar varias linhas escritas a mao - a mesma
 * classe de perda silenciosa de dado que motivou o guard em
 * /solicitante/nova (ver aviso-sair-sem-salvar.js). Reaproveita o utilitario
 * existente, sem nenhuma logica propria: cada campo listado desarma a si
 * mesmo no submit do proprio formulario, entao enviar normalmente nunca
 * dispara o aviso.
 */
(function () {
    if (typeof window.iniciarAvisoSairSemSalvar !== 'function') {
        return;
    }
    var campos = [
        document.getElementById('textoInfoComplementar'),
        document.getElementById('arquivosInfoComplementar')
    ].filter(Boolean);
    if (campos.length === 0) {
        // A tela so renderiza este formulario quando ha pedido de informacao
        // em aberto (situacao.precisaAcao) - nas demais situacoes nao ha nada
        // a proteger.
        return;
    }
    window.iniciarAvisoSairSemSalvar({ campos: campos });
})();
