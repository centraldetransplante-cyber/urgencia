(function () {
    var form = document.getElementById('formAnual');
    var base = form ? form.dataset.base : null;
    var sel = document.getElementById('anoSelect');
    if (!base || !sel) {
        return;
    }

    function comAno(acao) {
        var ano = sel.value;
        if (!ano) {
            return;
        }
        acao([ano]);
    }

    var btnPdf = document.getElementById('btnExportarPdf');
    var btnCsv = document.getElementById('btnExportarCsv');
    var btnHtml = document.getElementById('btnExportarHtml');
    if (btnPdf) {
        btnPdf.addEventListener('click', function () {
            comAno(function (partes) { window.abrirRelatorioPdf(base, partes); });
        });
    }
    if (btnCsv) {
        btnCsv.addEventListener('click', function () {
            comAno(function (partes) { window.baixarRelatorioCsv(base, partes); });
        });
    }
    if (btnHtml) {
        btnHtml.addEventListener('click', function () {
            comAno(function (partes) { window.abrirRelatorioHtml(base, partes); });
        });
    }
})();
