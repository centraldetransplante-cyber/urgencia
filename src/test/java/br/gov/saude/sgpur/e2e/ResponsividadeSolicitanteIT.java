package br.gov.saude.sgpur.e2e;

import br.gov.saude.sgpur.domain.Anexo;
import br.gov.saude.sgpur.domain.AnexoSolicitacaoOnline;
import br.gov.saude.sgpur.domain.MensagemSolicitacao;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.SolicitacaoOnline;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.StatusSolicitacaoOnline;
import br.gov.saude.sgpur.domain.TipoAnexo;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.AnexoRepository;
import br.gov.saude.sgpur.repository.MensagemSolicitacaoRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.service.UsuarioService;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guarda de RESPONSIVIDADE do Portal do Solicitante — vistoria de 2026-08-11
 * ({@code docs/RELATORIO-RESPONSIVIDADE-CORES-SOLICITANTE-2026-08.md}).
 *
 * <p><b>O bug que este teste existe para impedir.</b> O dono do produto
 * relatou <i>"tinha texto saindo da tela"</i>. A causa era o cartao de
 * situacao ({@code .cartao-resultado}): um item de flex nasce com
 * {@code min-width: auto}, isto e, NAO encolhe abaixo da largura min-content
 * do proprio conteudo. As mensagens desse cartao carregam o e-mail
 * institucional da equipe solicitante — um token longo e sem nenhum espaco.
 * Resultado medido: num celular de 360px, o documento inteiro ficava com
 * <b>642px</b> de largura (282px de estouro) e o solicitante tinha que rolar
 * de lado para ler a propria decisao do seu pedido.
 *
 * <p><b>Por que nenhum teste existente pegava isso.</b> A suite verifica
 * status HTTP, model attributes e presenca de texto/id;
 * {@link RedesignVisualSolicitanteIT} verifica cor/tamanho de fonte de
 * elementos isolados. Nenhum dos dois olha a LARGURA do documento contra a
 * viewport — que e a unica medida capaz de expressar "saiu da tela". Aqui a
 * assercao central e exatamente essa, em 6 larguras e em todos os estados do
 * portal, incluindo os que so aparecem depois da decisao (Deferido/Indeferido,
 * com e sem o anexo final), que sao justamente os que carregam as mensagens
 * mais longas.
 *
 * <p>Roda via {@code .\e2e.ps1} / {@code mvn verify -Pe2e} (Failsafe), fora do
 * {@code mvn test} do dia a dia — mesmo padrao de
 * {@link RedesignVisualSolicitanteIT}/{@link ChatVisualVerificacaoIT}.
 */
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-e2e-responsividade;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ResponsividadeSolicitanteIT extends PlaywrightTestBase {

    @Autowired private UsuarioService usuarioService;
    @Autowired private SolicitacaoOnlineRepository solicitacaoRepo;
    @Autowired private ProcessoRepository processoRepo;
    @Autowired private AnexoRepository anexoRepo;
    @Autowired private MensagemSolicitacaoRepository mensagemRepo;

    private static final String SENHA = "Senha123!";
    /**
     * Equipe e e-mail deliberadamente LONGOS, do tamanho de um nome de hospital
     * e de um endereco institucional reais. O e-mail e o gatilho exato do bug
     * original (token unico, sem espaco, dentro de um item de flex) — um
     * fixture com "teste@teste.com" nao reproduziria nada.
     */
    private static final String EQUIPE = "Hospital Universitario de Clinicas de Porto Alegre - Servico de Nefrologia";
    private static final String EMAIL = "nefrologia.transplante.renal@hospitaluniversitario.exemplo.com.br";

    /** 360=Android pequeno, 390=iPhone padrao, 576/768/992=cortes do Bootstrap, 1440=desktop. */
    private static final int[] LARGURAS = {360, 390, 576, 768, 992, 1440};

    private Usuario solicitante;

    private void seedUsuario() {
        Usuario u = new Usuario();
        u.setUsername("sol.resp");
        u.setNome("Dra. Maria Aparecida de Souza Nascimento Rodrigues");
        u.setEmail(EMAIL);
        u.setPerfil(Perfil.SOLICITANTE);
        u.setEquipeSolicitante(EQUIPE);
        solicitante = usuarioService.criar(u, SENHA);
    }

    private SolicitacaoOnline novaSolicitacao(String paciente, String rgct, StatusSolicitacaoOnline status) {
        SolicitacaoOnline s = new SolicitacaoOnline();
        s.setUsuarioSolicitante(solicitante);
        s.setPacienteNome(paciente);
        s.setPacienteRgct(rgct);
        s.setSolicitanteEquipe(EQUIPE);
        s.setSolicitanteEmail(EMAIL);
        s.setDataSituacaoEspecial(LocalDate.now().minusDays(4));
        // A URL sem espacos exercita o mesmo risco de quebra no bloco de
        // justificativa clinica (.text-pre-wrap, que ja trata isso).
        s.setJustificativaClinica("Paciente em hemodialise ha 7 anos com esgotamento de acessos vasculares. "
            + "Mapeamento venoso demonstra trombose de veia subclavia bilateral. Laudo: "
            + "https://exemplo.hospital.br/laudos/2026/nefrologia/mapeamento-venoso-doppler-completo.pdf");
        s.setStatus(status);
        s.setDataEnvio(LocalDateTime.now().minusDays(9));
        s = solicitacaoRepo.save(s);
        AnexoSolicitacaoOnline a = new AnexoSolicitacaoOnline();
        a.setSolicitacaoOnline(s);
        // Nome de anexo clinico do tamanho que a equipe realmente envia.
        a.setNomeArquivo("Laudo_Doppler_Venoso_Membros_Superiores_Paciente_Encaminhamento_Urgencia_Renal_2026_v3_final.pdf");
        a.setContentType("application/pdf");
        a.setTamanhoBytes(102400L);
        a.setCaminhoArmazenado("/tmp/fake.pdf");
        s.addAnexo(a);
        return solicitacaoRepo.save(s);
    }

    private Processo novoProcesso(String numero, SolicitacaoOnline s, StatusProcesso status) {
        Processo p = new Processo();
        p.setNumero(numero);
        p.setAno(2026);
        p.setSequencial(Integer.parseInt(numero.split("/")[0]));
        p.setPacienteNome(s.getPacienteNome());
        p.setPacienteRgct(s.getPacienteRgct());
        p.setSolicitanteEquipe(EQUIPE);
        p.setSolicitanteEmail(EMAIL);
        p.setDataSituacaoEspecial(s.getDataSituacaoEspecial());
        p.setStatus(status);
        p.setVersao(0L);
        return processoRepo.save(p);
    }

    private void anexoNoProcesso(Processo p, TipoAnexo tipo, String nome) {
        Anexo a = new Anexo();
        a.setProcesso(p);
        a.setTipo(tipo);
        a.setNomeArquivo(nome);
        a.setContentType("application/pdf");
        a.setTamanhoBytes(2048L);
        a.setCaminhoArmazenado("/tmp/fake.pdf");
        anexoRepo.save(a);
    }

    private void mensagem(SolicitacaoOnline s, MensagemSolicitacao.RemetenteMensagem quem, Long quemId, String texto) {
        MensagemSolicitacao m = new MensagemSolicitacao();
        m.setSolicitacaoOnline(s);
        m.setRemetente(quem);
        m.setRemetenteId(quemId);
        m.setTexto(texto);
        m.setDataEnvio(LocalDateTime.now().minusHours(3));
        mensagemRepo.save(m);
    }

    /**
     * Mede o estouro horizontal REAL da pagina e, quando existe, nomeia os
     * elementos culpados (tag/classe/coordenadas) — sem isso a falha diria so
     * "a pagina estourou", sem indicar onde mexer.
     *
     * <p>Ignora {@code position: fixed} (toast/modal vivem fora do fluxo e nao
     * geram barra de rolagem) e tolera 1px de arredondamento subpixel.
     */
    @SuppressWarnings("unchecked")
    private static String medirOverflow(Page alvo) {
        Map<String, Object> m = (Map<String, Object>) alvo.evaluate("""
            () => {
              const vw = document.documentElement.clientWidth;
              const doc = document.documentElement.scrollWidth;
              const culpados = [];
              document.querySelectorAll('body *').forEach(el => {
                const r = el.getBoundingClientRect();
                if (r.width === 0 && r.height === 0) return;
                if (getComputedStyle(el).position === 'fixed') return;
                if (r.right > vw + 1 || r.left < -1) {
                  culpados.push({
                    tag: el.tagName.toLowerCase(),
                    cls: (el.className && el.className.baseVal !== undefined
                          ? el.className.baseVal : el.className || '').toString().slice(0, 90),
                    left: Math.round(r.left), right: Math.round(r.right),
                    txt: (el.textContent || '').trim().slice(0, 55)
                  });
                }
              });
              return {vw, doc, overflow: doc - vw, culpados: culpados.slice(0, 10)};
            }
            """);
        int overflow = ((Number) m.get("overflow")).intValue();
        if (overflow <= 0) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("estourou ").append(overflow).append("px (viewport=").append(m.get("vw"))
          .append(", documento=").append(m.get("doc")).append("). Elementos fora da tela:\n");
        for (Object c : (List<Object>) m.get("culpados")) {
            Map<String, Object> e = (Map<String, Object>) c;
            sb.append("   <").append(e.get("tag")).append(" class='").append(e.get("cls"))
              .append("'> left=").append(e.get("left")).append(" right=").append(e.get("right"))
              .append(" | ").append(e.get("txt")).append('\n');
        }
        return sb.toString();
    }

    @Test
    void nenhumaTelaDoPortalEstouraAHorizontalEmNenhumBreakpoint() {
        seedUsuario();
        Map<String, String> telas = new LinkedHashMap<>();
        telas.put("lista", "/solicitante");
        telas.put("nova", "/solicitante/nova");

        // Aguardando triagem: a mensagem deste estado cita o e-mail da equipe -
        // era um dos 4 estados que estouravam a tela antes da correcao.
        SolicitacaoOnline enviada = novaSolicitacao(
            "Sebastiao Wanderley de Albuquerque Cavalcanti Filho", "123456789-00001",
            StatusSolicitacaoOnline.ENVIADA);
        mensagem(enviada, MensagemSolicitacao.RemetenteMensagem.OPERADOR, 999L,
            "Bom dia! Recebemos o pedido e ja estamos com ele na fila de triagem.");
        mensagem(enviada, MensagemSolicitacao.RemetenteMensagem.SOLICITANTE, solicitante.getId(),
            "Obrigada pelo retorno.");
        telas.put("detalhe-aguardando", "/solicitante/" + enviada.getId());

        SolicitacaoOnline analise = novaSolicitacao(
            "Maria das Gracas Conceicao do Nascimento", "123456789-00002", StatusSolicitacaoOnline.CONVERTIDA);
        analise.setProcessoGerado(novoProcesso("14/2026", analise, StatusProcesso.ENVIADO));
        solicitacaoRepo.save(analise);
        telas.put("detalhe-em-analise", "/solicitante/" + analise.getId());

        // Pausa "Solicita informacao": o cartao carrega um formulario de upload
        // inteiro dentro dele - o estado com mais conteudo no cartao.
        SolicitacaoOnline pausa = novaSolicitacao(
            "Joao Carlos Pereira", "123456789-00003", StatusSolicitacaoOnline.CONVERTIDA);
        pausa.setProcessoGerado(novoProcesso("15/2026", pausa, StatusProcesso.SOLICITA_INFORMACAO));
        solicitacaoRepo.save(pausa);
        telas.put("detalhe-precisa-info", "/solicitante/" + pausa.getId());

        // Deferido SEM o comprovante ainda anexado (mensagem "providenciando").
        SolicitacaoOnline defSem = novaSolicitacao(
            "Antonio Marcos da Silva Junior", "123456789-00004", StatusSolicitacaoOnline.CONVERTIDA);
        defSem.setProcessoGerado(novoProcesso("16/2026", defSem, StatusProcesso.DEFERIDO));
        solicitacaoRepo.save(defSem);
        telas.put("detalhe-deferido-sem-anexo", "/solicitante/" + defSem.getId());

        // Deferido COM comprovante: inclui o botao de download de rotulo longo
        // ("Baixar comprovante de inserção no SNT"), que sozinho media 446px
        // numa tela de 360px antes da correcao.
        SolicitacaoOnline defCom = novaSolicitacao(
            "Rosangela Aparecida Ferreira dos Santos", "123456789-00005", StatusSolicitacaoOnline.CONVERTIDA);
        Processo pDefCom = novoProcesso("17/2026", defCom, StatusProcesso.DEFERIDO);
        pDefCom.setEmailEnviadoSolicitante(true);
        pDefCom.setMensagemResposta("Urgencia renal reconhecida. Paciente inserido no SNT com prioridade.");
        processoRepo.save(pDefCom);
        anexoNoProcesso(pDefCom, TipoAnexo.COMPROVANTE_SNT, "Comprovante_Insercao_SNT_Urgencia_Renal.pdf");
        defCom.setProcessoGerado(pDefCom);
        solicitacaoRepo.save(defCom);
        telas.put("detalhe-deferido-com-anexo", "/solicitante/" + defCom.getId());

        SolicitacaoOnline indef = novaSolicitacao(
            "Francisco de Assis Oliveira Nogueira", "123456789-00006", StatusSolicitacaoOnline.CONVERTIDA);
        Processo pInd = novoProcesso("18/2026", indef, StatusProcesso.INDEFERIDO);
        pInd.setEmailEnviadoSolicitante(true);
        pInd.setMotivoIndeferimento("Os criterios de urgencia renal previstos na Portaria vigente nao foram "
            + "integralmente demonstrados na documentacao apresentada pela equipe solicitante.");
        processoRepo.save(pInd);
        anexoNoProcesso(pInd, TipoAnexo.OFICIO_INDEFERIMENTO, "Oficio_1401_2026_Indeferimento.pdf");
        indef.setProcessoGerado(pInd);
        solicitacaoRepo.save(indef);
        telas.put("detalhe-indeferido-com-oficio", "/solicitante/" + indef.getId());

        SolicitacaoOnline dev = novaSolicitacao(
            "Terezinha de Jesus Barbosa", "123456789-00007", StatusSolicitacaoOnline.DEVOLVIDA);
        dev.setObservacoesTriagem("Faltou anexar o mapeamento venoso com doppler e o ultimo laudo de creatinina.");
        solicitacaoRepo.save(dev);
        telas.put("detalhe-devolvida", "/solicitante/" + dev.getId());

        SolicitacaoOnline canc = novaSolicitacao(
            "Luiz Fernando Machado", "123456789-00008", StatusSolicitacaoOnline.CANCELADA);
        telas.put("detalhe-cancelada", "/solicitante/" + canc.getId());

        login("sol.resp", SENHA);

        List<String> falhas = new ArrayList<>();
        try {
            for (Map.Entry<String, String> tela : telas.entrySet()) {
                for (int largura : LARGURAS) {
                    page.setViewportSize(largura, largura <= 400 ? 900 : 1000);
                    page.navigate(tela.getValue());
                    page.waitForLoadState();
                    page.waitForTimeout(250);
                    String problema = medirOverflow(page);
                    if (problema != null) {
                        falhas.add(tela.getKey() + " @" + largura + "px: " + problema);
                        screenshot("responsividade-FALHA-" + tela.getKey() + "-w" + largura);
                    }
                }
            }
            // Capturas para a revisao visual humana que a §11 do relatorio de
            // redesign exige (o teste garante o basico; o olho humano, o resto).
            for (Map.Entry<String, String> tela : telas.entrySet()) {
                for (int largura : new int[]{390, 1440}) {
                    page.setViewportSize(largura, largura <= 400 ? 900 : 1000);
                    page.navigate(tela.getValue());
                    page.waitForLoadState();
                    page.waitForTimeout(250);
                    screenshot("responsividade-" + tela.getKey() + "-w" + largura);
                }
            }

            assertThat(falhas)
                .as("Nenhuma tela do Portal do Solicitante pode estourar a largura da tela "
                    + "(bug real reportado: \"tinha texto saindo da tela\"). Causa classica: item "
                    + "de flex sem min-width:0 com conteudo sem espaco (e-mail/URL) - ver "
                    + ".cartao-resultado em app.css.")
                .isEmpty();
        } catch (AssertionError | RuntimeException e) {
            screenshot("responsividade-solicitante-FALHA");
            throw e;
        }
    }
}
