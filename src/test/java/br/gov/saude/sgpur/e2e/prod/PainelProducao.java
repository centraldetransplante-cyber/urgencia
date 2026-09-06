package br.gov.saude.sgpur.e2e.prod;

import com.microsoft.playwright.Page;

import java.util.List;

/**
 * Overlay fixo específico do Robô de Inspeção em PRODUÇÃO — deliberadamente
 * separado de {@link br.gov.saude.sgpur.e2e.Legenda} (usado pelo e2e local
 * via {@code .\e2e.ps1}) para não arriscar quebrar aquele fluxo por causa de
 * uma necessidade que só existe aqui: alguém (outro operador, o próprio
 * administrador) pode literalmente estar olhando pra tela enquanto uma
 * sessão autenticada como ADMIN mexe sozinha no sistema real de produção.
 * Sem contexto visível isso parece uma invasão, não uma inspeção autorizada.
 *
 * <p>Desenha 3 elementos fixos, sempre reconstruídos do zero a cada chamada
 * (idempotente, sobrevive a re-injeção após um {@code navigate} matar o
 * documento anterior):
 * <ol>
 *   <li>Selo de identificação, permanente, bem visível — nunca muda de
 *   texto durante a execução (não é a "legenda" da ação atual);</li>
 *   <li>Linha da ação em andamento neste exato instante (equivalente ao que
 *   {@code Legenda.mostrar} já fazia, mas dentro do mesmo overlay);</li>
 *   <li>Checklist fixo de todas as etapas do roteiro, com status de cada
 *   uma (pendente / em andamento / concluída / falhou) — permite entender o
 *   progresso geral só de bater o olho, sem precisar ler o relatório HTML
 *   ao final.</li>
 * </ol>
 */
final class PainelProducao {

    enum StatusEtapa {
        PENDENTE("⏳", "#64748b"),      // ⏳ cinza
        EM_ANDAMENTO("▶", "#facc15"),  // ▶ amarelo
        CONCLUIDA("✅", "#22c55e"),     // ✅ verde
        FALHA("❌", "#ef4444");         // ❌ vermelho

        final String icone;
        final String cor;

        StatusEtapa(String icone, String cor) {
            this.icone = icone;
            this.cor = cor;
        }
    }

    record ItemChecklist(String titulo, StatusEtapa status) {
    }

    private static final String JS = """
        (dados) => {
            const acaoAtual = dados.acaoAtual;
            const etapas = dados.etapas;

            let identificacao = document.getElementById('saur-prod-identificacao');
            if (!identificacao) {
                identificacao = document.createElement('div');
                identificacao.id = 'saur-prod-identificacao';
                identificacao.style.cssText = 'position:fixed;top:0;left:0;right:0;z-index:2147483647;'
                    + 'background:repeating-linear-gradient(45deg,#7f1d1d,#7f1d1d 12px,#991b1b 12px,#991b1b 24px);'
                    + 'color:#ffffff;font:800 14px/1.4 monospace;padding:8px 16px;text-align:center;'
                    + 'box-shadow:0 2px 10px rgba(0,0,0,.5);pointer-events:none;letter-spacing:.3px;'
                    + 'border-bottom:3px solid #facc15;box-sizing:border-box;';
                document.documentElement.appendChild(identificacao);
            }
            identificacao.textContent = '\\uD83E\\uDD16 ROBÔ DE INSPEÇÃO AUTOMATIZADA — SAUR · AUTORIZADO · SOMENTE LEITURA, NADA É ALTERADO';

            let acao = document.getElementById('saur-prod-acao');
            if (!acao) {
                acao = document.createElement('div');
                acao.id = 'saur-prod-acao';
                acao.style.cssText = 'position:fixed;left:0;right:0;z-index:2147483646;'
                    + 'background:#111827;color:#facc15;font:600 15px/1.4 monospace;'
                    + 'padding:10px 16px;text-align:center;box-shadow:0 2px 8px rgba(0,0,0,.4);'
                    + 'pointer-events:none;box-sizing:border-box;';
                document.documentElement.appendChild(acao);
            }
            acao.textContent = '\\u25B6 AGORA: ' + acaoAtual;

            // As alturas dos 2 banners variam conforme o texto quebra linha
            // (a mensagem de identificacao e' longa e pode virar 1 ou 2
            // linhas dependendo da largura da janela) - medir de verdade
            // via getBoundingClientRect em vez de um "top" fixo no CSS evita
            // que o segundo banner fique escondido atras do primeiro quando
            // ele for mais alto que o previsto (bug real observado: com o
            // texto de identificacao em 2 linhas e um "top:38px" fixo no
            // banner de acao, o de cima cobria o de baixo por ter z-index
            // maior).
            const alturaIdentificacao = identificacao.getBoundingClientRect().height;
            acao.style.top = alturaIdentificacao + 'px';
            const alturaAcao = acao.getBoundingClientRect().height;
            const alturaTotal = alturaIdentificacao + alturaAcao;

            let checklist = document.getElementById('saur-prod-checklist');
            if (!checklist) {
                checklist = document.createElement('div');
                checklist.id = 'saur-prod-checklist';
                checklist.style.cssText = 'position:fixed;bottom:12px;right:12px;z-index:2147483646;'
                    + 'background:rgba(15,23,42,.95);color:#e2e8f0;font:600 12.5px/1.6 monospace;'
                    + 'padding:12px 16px;border-radius:10px;box-shadow:0 4px 16px rgba(0,0,0,.5);'
                    + 'pointer-events:none;border:1px solid #334155;max-width:340px;';
                document.documentElement.appendChild(checklist);
            }
            let html = '<div style="color:#facc15;font-weight:800;margin-bottom:6px;">'
                + 'ROTEIRO DA INSPEÇÃO</div>';
            for (const e of etapas) {
                const destaque = e.status === 'EM_ANDAMENTO' ? 'font-weight:800;' : 'opacity:.85;';
                html += '<div style="color:' + e.cor + ';' + destaque + '">'
                    + e.icone + ' ' + e.titulo + '</div>';
            }
            checklist.innerHTML = html;

            // Empurra o conteudo original da pagina para baixo dos 2 banners
            // fixos do topo (altura real, medida acima - nunca um numero
            // fixo, que quebra sempre que o texto de um dos banners mudar
            // de 1 para 2 linhas), senao eles ficam sobrepondo a navbar do
            // SAUR.
            document.documentElement.style.scrollPaddingTop = alturaTotal + 'px';
            if (document.body) {
                document.body.style.marginTop = alturaTotal + 'px';
            }
        }
        """;

    private PainelProducao() {
    }

    /**
     * (Re)desenha o overlay completo. Silencioso se a página estiver em
     * transição de navegação — quem chama reaplica na próxima ação estável
     * (mesmo contrato de {@code Legenda.mostrar}).
     */
    static void renderizar(Page page, String acaoAtual, List<ItemChecklist> etapas) {
        if (page == null) return;
        try {
            List<java.util.Map<String, String>> etapasJs = etapas.stream()
                .map(item -> java.util.Map.of(
                    "titulo", item.titulo(),
                    "status", item.status().name(),
                    "icone", item.status().icone,
                    "cor", item.status().cor))
                .toList();
            page.evaluate(JS, java.util.Map.of("acaoAtual", acaoAtual == null ? "" : acaoAtual, "etapas", etapasJs));
        } catch (RuntimeException ignored) {
            // pagina em transicao (navigate em andamento) - a proxima chamada reaplica.
        }
    }
}
