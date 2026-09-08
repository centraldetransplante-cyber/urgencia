package br.gov.saude.sgpur.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RoboNavegadorServiceTest {

    private final RoboNavegadorService service =
            new RoboNavegadorService("/caminho/inexistente/run.sh");

    @Test
    void pararRetornaFalsoQuandoNaoHaExecucaoEmAndamento() {
        assertThat(service.estaExecutando()).isFalse();
        assertThat(service.parar()).isFalse();
    }

    @Test
    void iniciarFalhaComScriptInexistenteENaoDeixaEstadoTravado() {
        assertThat(service.iniciar()).isFalse();
        assertThat(service.getStatus()).isEqualTo("ERRO");
        assertThat(service.estaExecutando()).isFalse();
        // parar() continua seguro de chamar mesmo apos uma falha de iniciar()
        assertThat(service.parar()).isFalse();
    }

    @Test
    void getRelatorioHtmlApontaParaReportIndexAoLadoDoScript() {
        Path relatorio = service.getRelatorioHtml();
        assertThat(relatorio.getFileName().toString()).isEqualTo("index.html");
        assertThat(relatorio.getParent().getFileName().toString()).isEqualTo("report");
    }
}
