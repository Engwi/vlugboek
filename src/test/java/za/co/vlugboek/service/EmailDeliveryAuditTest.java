package za.co.vlugboek.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import za.co.vlugboek.domain.AppUser;
import za.co.vlugboek.domain.DocumentRecord;
import za.co.vlugboek.domain.EmailDeliveryAudit;
import za.co.vlugboek.domain.EmailDeliveryStatus;
import za.co.vlugboek.repo.AppUserRepository;
import za.co.vlugboek.repo.DocumentRepository;
import za.co.vlugboek.repo.EmailDeliveryAuditRepository;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emailaudit;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "vlugboek.seed.pdf-root=target/missing-test-pdfs",
        "vlugboek.storage.uploads-dir=target/test-uploads",
        "vlugboek.mailer.token="
})
class EmailDeliveryAuditTest {
    @Autowired
    private EmailDeliveryService emailDeliveryService;

    @Autowired
    private EmailDeliveryAuditRepository audits;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private DocumentRepository documents;

    @Test
    void failedDeliveryIsAudited() throws Exception {
        AppUser recipient = users.findByEmailIgnoreCase("demo@vlugboek.local").orElseThrow();
        DocumentRecord document = documents.save(new DocumentRecord(
                "Audit Fixture",
                "audit-fixture.pdf",
                "target/audit-fixture.pdf",
                "application/pdf",
                12
        ));
        Path pdfPath = Path.of("target", "audit-fixture.pdf");
        Files.createDirectories(pdfPath.getParent());
        Files.writeString(pdfPath, "fake pdf");

        assertThatThrownBy(() -> emailDeliveryService.sendDocument(document, recipient, pdfPath))
                .isInstanceOf(EmailDeliveryException.class)
                .hasMessageContaining("Mailer token is not configured");

        List<EmailDeliveryAudit> deliveries = audits.findByDocument_IdOrderByRequestedAtDesc(document.getId());
        assertThat(deliveries).hasSize(1);
        assertThat(deliveries.getFirst().getStatus()).isEqualTo(EmailDeliveryStatus.FAILED);
        assertThat(deliveries.getFirst().getErrorMessage()).contains("Mailer token is not configured");
    }
}
