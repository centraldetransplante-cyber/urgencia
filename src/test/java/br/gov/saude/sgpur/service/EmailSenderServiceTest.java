package br.gov.saude.sgpur.service;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cobre a protecao "modo teste" (app.mail.override-recipient): quando
 * configurado, todo envio deve ser redirecionado para o endereco fixo,
 * nunca para o destinatario real - evita mandar e-mail de teste para
 * avaliadores/solicitantes de verdade.
 */
class EmailSenderServiceTest {

    private JavaMailSender mailSenderMock() {
        JavaMailSender sender = mock(JavaMailSender.class);
        Session session = Session.getDefaultInstance(new Properties());
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(session));
        return sender;
    }

    @Test
    void semOverrideEnviaParaDestinatarioReal() throws Exception {
        JavaMailSender sender = mailSenderMock();
        EmailSenderService service = new EmailSenderService(sender, "remetente@saur.gov.br", "");

        boolean ok = service.enviar("real@example.com", "Assunto original", "corpo");

        assertThat(ok).isTrue();
        MimeMessage[] captured = capturarMensagem(sender);
        assertThat(captured[0].getRecipients(Message.RecipientType.TO)[0].toString())
            .isEqualTo("real@example.com");
        assertThat(captured[0].getSubject()).isEqualTo("Assunto original");
    }

    @Test
    void comOverrideRedirecionaTodoEnvioIgnorandoDestinatarioReal() throws Exception {
        JavaMailSender sender = mailSenderMock();
        EmailSenderService service = new EmailSenderService(sender, "remetente@saur.gov.br", "rafaelioppi@gmail.com");

        boolean ok = service.enviar(new String[]{"avaliador1@example.com", "avaliador2@example.com"},
            new String[]{"copia@example.com"}, "Assunto original", "corpo");

        assertThat(ok).isTrue();
        MimeMessage[] captured = capturarMensagem(sender);
        assertThat(captured[0].getRecipients(Message.RecipientType.TO))
            .extracting(Object::toString)
            .containsExactly("rafaelioppi@gmail.com");
        assertThat(captured[0].getRecipients(Message.RecipientType.CC)).isNull();
        assertThat(captured[0].getSubject())
            .contains("[TESTE - para: avaliador1@example.com, avaliador2@example.com | cc: copia@example.com]")
            .contains("Assunto original");
    }

    @Test
    void comOverrideRedirecionaTambemOEnvioComAnexo() throws Exception {
        JavaMailSender sender = mailSenderMock();
        EmailSenderService service = new EmailSenderService(sender, "remetente@saur.gov.br", "rafaelioppi@gmail.com");

        boolean ok = service.enviarComAnexo("solicitante@example.com", "Deferido", "corpo", null, null);

        assertThat(ok).isTrue();
        MimeMessage[] captured = capturarMensagem(sender);
        assertThat(captured[0].getRecipients(Message.RecipientType.TO))
            .extracting(Object::toString)
            .containsExactly("rafaelioppi@gmail.com");
        assertThat(captured[0].getSubject()).contains("[TESTE - para: solicitante@example.com]");
    }

    /**
     * anexo == null continua sendo "sem anexo, de proposito" (ex.: modo teste
     * acima) e envia normalmente - ver o teste seguinte para o caso oposto.
     */
    @Test
    void anexoAusenteEmDiscoFalhaEmVezDeEnviarSemAnexo(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir)
            throws Exception {
        JavaMailSender sender = mailSenderMock();
        EmailSenderService service = new EmailSenderService(sender, "remetente@saur.gov.br", "");
        java.io.File anexoInexistente = tempDir.resolve("comprovante-que-nao-existe-mais.pdf").toFile();

        boolean ok = service.enviarComAnexo("solicitante@example.com", "Deferido", "corpo",
            anexoInexistente, "comprovante.pdf");

        assertThat(ok).isFalse();
        org.mockito.Mockito.verify(sender, org.mockito.Mockito.never()).send(org.mockito.Mockito.any(MimeMessage.class));
    }

    private MimeMessage[] capturarMensagem(JavaMailSender sender) {
        org.mockito.ArgumentCaptor<MimeMessage> captor = org.mockito.ArgumentCaptor.forClass(MimeMessage.class);
        org.mockito.Mockito.verify(sender).send(captor.capture());
        return new MimeMessage[]{captor.getValue()};
    }
}
