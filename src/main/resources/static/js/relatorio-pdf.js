// === SAUR - Exportacao de relatorio (PDF / CSV / HTML imprimivel) ===
// Compartilhado por relatorio-anual.js e relatorio-avaliador.js, que so
// diferem na quantidade de segmentos de URL (ano; ano+membro).
window.abrirRelatorioPdf = function (base, partes) {
    var url = base + '/' + partes.join('/') + '/pdf';
    var aba = window.open(url, '_blank', 'noopener,noreferrer');
    if (!aba && typeof mostrarToast === 'function') {
        mostrarToast('Nao foi possivel abrir o PDF - verifique se o navegador bloqueou o pop-up.', 'error');
    }
};

// CSV e um download direto (Content-Disposition: attachment) - nao precisa
// de nova aba, so navegar ate a URL basta.
window.baixarRelatorioCsv = function (base, partes) {
    window.location.href = base + '/' + partes.join('/') + '/csv';
};

// Visualizacao HTML imprimivel abre em nova aba, igual ao PDF.
window.abrirRelatorioHtml = function (base, partes) {
    var url = base + '/' + partes.join('/') + '/html';
    var aba = window.open(url, '_blank', 'noopener,noreferrer');
    if (!aba && typeof mostrarToast === 'function') {
        mostrarToast('Nao foi possivel abrir a visualizacao - verifique se o navegador bloqueou o pop-up.', 'error');
    }
};
