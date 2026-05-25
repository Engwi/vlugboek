package za.co.vlugboek.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import za.co.vlugboek.domain.AppUser;
import za.co.vlugboek.domain.DocumentRecord;
import za.co.vlugboek.domain.EmailDeliveryAudit;
import za.co.vlugboek.domain.EmailDeliveryStatus;
import za.co.vlugboek.repo.AppUserRepository;
import za.co.vlugboek.repo.DocumentRepository;
import za.co.vlugboek.repo.EmailDeliveryAuditRepository;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emailsuccessaudit;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "vlugboek.seed.pdf-root=target/missing-test-pdfs",
        "vlugboek.storage.uploads-dir=target/test-uploads"
})
class EmailDeliverySuccessAuditTest {
    private static HttpServer mailer;

    @Autowired
    private EmailDeliveryService emailDeliveryService;

    @Autowired
    private EmailDeliveryAuditRepository audits;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private DocumentRepository documents;

    @DynamicPropertySource
    static void mailerProperties(DynamicPropertyRegistry registry) {
        registry.add("vlugboek.mailer.url", () -> "http://127.0.0.1:" + mailerPortUnchecked() + "/send-document");
        registry.add("vlugboek.mailer.token", () -> "test-token");
    }

    @AfterAll
    static void stopMailer() {
        if (mailer != null) {
            mailer.stop(0);
        }
    }

    @Test
    void successfulDeliveryIsAudited() throws Exception {
        AppUser recipient = users.findByEmailIgnoreCase("demo@vlugboek.local").orElseThrow();
        DocumentRecord document = documents.save(new DocumentRecord(
                "Success Audit Fixture",
                "success-audit-fixture.pdf",
                "target/success-audit-fixture.pdf",
                "application/pdf",
                12
        ));
        Path pdfPath = Path.of("target", "success-audit-fixture.pdf");
        Files.createDirectories(pdfPath.getParent());
        Files.writeString(pdfPath, "fake pdf");

        EmailDeliveryResult result = emailDeliveryService.sendDocument(document, recipient, pdfPath);

        assertThat(result.messageId()).isEqualTo("test-message");
        assertThat(result.auditId()).isNotNull();
        assertThat(result.requestId()).startsWith("email-");

        List<EmailDeliveryAudit> deliveries = audits.findByDocument_IdOrderByRequestedAtDesc(document.getId());
        assertThat(deliveries).hasSize(1);
        assertThat(deliveries.getFirst().getStatus()).isEqualTo(EmailDeliveryStatus.SENT);
        assertThat(deliveries.getFirst().getMessageId()).isEqualTo("test-message");
    }

    private static synchronized int mailerPort() throws IOException {
        if (mailer == null) {
            mailer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            mailer.createContext("/send-document", exchange -> {
                byte[] body = "{\"messageId\":\"test-message\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            mailer.start();
        }
        return mailer.getAddress().getPort();
    }

    private static int mailerPortUnchecked() {
        try {
            return mailerPort();
        } catch (IOException ex) {
            throw new IllegalStateException("Could not start fake mailer", ex);
        }
    }
}
