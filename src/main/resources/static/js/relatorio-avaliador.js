(function () {
    var form = document.getElementById('formAvaliador');
    var base = form ? form.dataset.base : null;
    var ano = document.getElementById('anoSelect');
    var membro = document.getElementById('membroSelect');
    if (!base || !ano || !membro) {
        return;
    }

    function comAnoEMembro(acao) {
        if (!ano.value || !membro.value) {
            return;
        }
        acao([ano.value, membro.value]);
    }

    var btnPdf = document.getElementById('btnExportarPdf');
    var btnCsv = document.getElementById('btnExportarCsv');
    var btnHtml = document.getElementById('btnExportarHtml');
    if (btnPdf) {
        btnPdf.addEventListener('click', function () {
            comAnoEMembro(function (partes) { window.abrirRelatorioPdf(base, partes); });
        });
    }
    if (btnCsv) {
        btnCsv.addEventListener('click', function () {
            comAnoEMembro(function (partes) { window.baixarRelatorioCsv(base, partes); });
        });
    }
    if (btnHtml) {
        btnHtml.addEventListener('click', function () {
            comAnoEMembro(function (partes) { window.abrirRelatorioHtml(base, partes); });
        });
    }
})();
