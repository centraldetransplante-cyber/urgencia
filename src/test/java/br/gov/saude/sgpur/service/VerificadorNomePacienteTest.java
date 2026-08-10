package br.gov.saude.sgpur.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes da verificacao deterministica de nome de paciente/equipe solicitante
 * (docs/RELATORIO-CHAT-MEMBROS-OPERADORES-2026-08.md, secao 8.1).
 */
class VerificadorNomePacienteTest {

    private final VerificadorNomePaciente verificador = new VerificadorNomePaciente();

    @Test
    void textoLivreDeReferenciaAoPacienteOuEquipeELivre() {
        var r = verificador.verificar("O PDF deste processo abriu em branco, poderia reenviar?",
            "Mariana da Rosa Martins", "Hospital de Clinicas de Porto Alegre");
        assertThat(r.nivel()).isEqualTo(VerificadorNomePaciente.Nivel.LIVRE);
        assertThat(r.bloqueado()).isFalse();
    }

    @Test
    void nomeCompletoDoPacienteEBloqueado() {
        var r = verificador.verificar("A Mariana Martins pediu para reagendar o exame.",
            "Mariana da Rosa Martins", "HCPA");
        assertThat(r.nivel()).isEqualTo(VerificadorNomePaciente.Nivel.BLOQUEADO);
        assertThat(r.bloqueado()).isTrue();
    }

    @Test
    void apenasUmTokenDoNomeGeraAlerta() {
        // "Rosa" e um sobrenome comum: 1 token so (nao "Mariana Martins" juntos)
        // deve gerar ALERTA, nao BLOQUEADO direto - mitiga falso-positivo.
        var r = verificador.verificar("Precisamos falar sobre uma rosa que apareceu no laudo.",
            "Mariana da Rosa Martins", "HCPA");
        assertThat(r.nivel()).isEqualTo(VerificadorNomePaciente.Nivel.ALERTA);
        assertThat(r.bloqueado()).isFalse();
    }

    @Test
    void substringFalsaNaoDisparaAlerta() {
        // "Ana" nao pode casar dentro de "analise" - exige palavra inteira.
        var r = verificador.verificar("Preciso de uma nova análise clínica deste caso.",
            "Ana Paula Souza", "HCPA");
        assertThat(r.nivel()).isEqualTo(VerificadorNomePaciente.Nivel.LIVRE);
    }

    /**
     * Calibragem de 2026-08-10 (S4): equipes com MAIS de 1 token
     * significativo no total exigem >=2 tokens simultaneos na mensagem para
     * bloquear (ver {@link #umUnicoTokenGenericoDeEquipeNaoBloqueiaMaisSozinho}) -
     * a mensagem deste teste precisa citar mais de uma palavra da equipe
     * (nao so "Hospital de Clinicas", que sozinha virou permitida) para
     * continuar sendo um verdadeiro positivo.
     */
    @Test
    void equipeSolicitanteCitadaPorInteiroEBloqueada() {
        var r = verificador.verificar("O Hospital de Clinicas de Porto Alegre esta tentando contato.",
            "Fulano de Tal", "Hospital de Clinicas de Porto Alegre");
        assertThat(r.nivel()).isEqualTo(VerificadorNomePaciente.Nivel.BLOQUEADO);
    }

    @Test
    void textoComAcentoENormalizadoAntesDeComparar() {
        var r = verificador.verificar("Confirmar dados de José da Conceição hoje.",
            "Jose da Conceicao", "HCPA");
        assertThat(r.nivel()).isEqualTo(VerificadorNomePaciente.Nivel.BLOQUEADO);
    }

    @Test
    void nomeVazioOuNuloNuncaBloqueiaNadaAlemDaEquipe() {
        var r = verificador.verificar("Mensagem qualquer sem nada de especial.", "", "");
        assertThat(r.nivel()).isEqualTo(VerificadorNomePaciente.Nivel.LIVRE);
    }

    @Test
    void textoNuloOuEmBrancoELivre() {
        var r = verificador.verificar(null, "Mariana da Rosa Martins", "HCPA");
        assertThat(r.nivel()).isEqualTo(VerificadorNomePaciente.Nivel.LIVRE);
        var r2 = verificador.verificar("   ", "Mariana da Rosa Martins", "HCPA");
        assertThat(r2.nivel()).isEqualTo(VerificadorNomePaciente.Nivel.LIVRE);
    }

    // -------------------------------------------------------------------
    // Calibragem de 2026-08-10 (S4, docs/RELATORIO-VISTORIA-CHAT-2026-08-10.md,
    // achados A4 e A5) - casos de borda explicitamente reproduzidos no
    // relatorio como bugados, agora corrigidos.
    // -------------------------------------------------------------------

    /**
     * Achado A4: nome do paciente INTEIRO curto (aqui, 2 tokens de 3 letras
     * cada) nao tinha NENHUMA protecao antes desta calibragem - "Ana Luz"
     * gerava termos=[] (LIVRE) porque nenhum token alcancava o corte antigo
     * de 4 caracteres. Mencionar so 1 dos 2 tokens de um nome curto agora
     * BLOQUEIA direto (nao so ALERTA), porque nao ha um 3o token "sobrando"
     * pra distinguir coincidencia de sobrenome comum de mencao real.
     */
    @Test
    void nomeInteiroCurtoComApenasUmTokenCitadoEBloqueado() {
        var r = verificador.verificar("A Ana esta aguardando retorno sobre o exame.",
            "Ana Luz", "HCPA");
        assertThat(r.nivel()).isEqualTo(VerificadorNomePaciente.Nivel.BLOQUEADO);
        assertThat(r.bloqueado()).isTrue();
    }

    /** Mesmo nome curto, citando os 2 tokens juntos: BLOQUEADO (ja era via a regra de >=2). */
    @Test
    void nomeInteiroCurtoComOsDoisTokensCitadosEBloqueado() {
        var r = verificador.verificar("paciente Ana Luz esta na fila",
            "Ana Luz Silva", "HCPA");
        assertThat(r.nivel()).isEqualTo(VerificadorNomePaciente.Nivel.BLOQUEADO);
    }

    /**
     * Nome NAO curto (3 tokens significativos), citando 2 dos 3: continua
     * BLOQUEADO (regra pre-existente de >=2 tokens, sem mudanca de
     * comportamento aqui - so confirma que a calibragem nao afetou esse
     * caminho).
     */
    @Test
    void doisDeTresTokensDeNomeNaoCurtoContinuaBloqueado() {
        var r = verificador.verificar("A Mariana Rosa perguntou sobre o exame.",
            "Mariana da Rosa Martins", "HCPA");
        assertThat(r.nivel()).isEqualTo(VerificadorNomePaciente.Nivel.BLOQUEADO);
    }

    /**
     * Achado A5, primeiro exemplo reproduzido no relatorio: "clinicas" e
     * vocabulario clinico corrente, nao identifica a equipe sozinho quando
     * so 1 token aparece. Antes desta calibragem, isso BLOQUEAVA a mensagem
     * (falso-positivo real); agora exige um segundo token da equipe junto.
     */
    @Test
    void umUnicoTokenGenericoDeEquipeNaoBloqueiaMaisSozinho() {
        var r = verificador.verificar("O exame de clinicas nao abriu no meu celular.",
            "Fulano de Tal", "Hospital de Clinicas de Porto Alegre");
        assertThat(r.nivel()).isEqualTo(VerificadorNomePaciente.Nivel.LIVRE);
    }

    /** Achado A5, segundo exemplo: "alegre" isolado (metade do nome da cidade) tambem nao bloqueia mais sozinho. */
    @Test
    void toponimoIsoladoDaEquipeNaoBloqueiaMaisSozinho() {
        var r = verificador.verificar("Pode ficar tranquilo, favor desconsiderar o alegre da mensagem anterior.",
            "Fulano de Tal", "Hospital de Clinicas de Porto Alegre");
        assertThat(r.nivel()).isEqualTo(VerificadorNomePaciente.Nivel.LIVRE);
    }

    /**
     * Token genuinamente generico da equipe ("geral", parte de "Hospital
     * Geral"), acrescentado a STOPWORDS_EQUIPE nesta calibragem: nunca conta
     * como token significativo, mesmo citado sozinho ou junto de outro termo
     * generico - sem nenhum token distintivo real da equipe, a mensagem fica
     * livre.
     */
    @Test
    void tokenGenericoDeEquipeNaMensagemNuncaContaComoTokenSignificativo() {
        var r = verificador.verificar("Precisamos de uma resposta geral sobre o caso, sem pressa.",
            "Fulano de Tal", "Hospital Geral de Caxias do Sul");
        assertThat(r.nivel()).isEqualTo(VerificadorNomePaciente.Nivel.LIVRE);
    }

    /**
     * Equipe CURTA (so 1 token significativo no total, ex.: sigla sem
     * espacos) preserva o comportamento ANTERIOR a esta calibragem - exigir
     * 2 tokens seria impossivel de satisfazer e tornaria essa equipe
     * IMPOSSIVEL de detectar por este mecanismo.
     */
    @Test
    void equipeCurtaDeUmUnicoTokenContinuaBloqueandoComEsseUnicoToken() {
        var r = verificador.verificar("A HNSC ligou perguntando sobre o processo.",
            "Fulano de Tal", "HNSC");
        assertThat(r.nivel()).isEqualTo(VerificadorNomePaciente.Nivel.BLOQUEADO);
    }

    /** Acento e maiuscula na equipe nao escapam da deteccao (mesma normalizacao ja usada no nome). */
    @Test
    void acentoEMaiusculaNaEquipeNaoEscapamDaDeteccaoQuandoDoisTokensAparecem() {
        var r = verificador.verificar("O HOSPITAL DE CLÍNICAS DE PORTO ALEGRE está em contato.",
            "Fulano de Tal", "Hospital de Clinicas de Porto Alegre");
        assertThat(r.nivel()).isEqualTo(VerificadorNomePaciente.Nivel.BLOQUEADO);
    }
}
