package za.co.vlugboek.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import za.co.vlugboek.domain.AppUser;
import za.co.vlugboek.domain.DocumentRecord;
import za.co.vlugboek.domain.EmailDeliveryAudit;
import za.co.vlugboek.repo.EmailDeliveryAuditRepository;

@Service
public class EmailDeliveryService {
    private static final Logger log = LoggerFactory.getLogger(EmailDeliveryService.class);
    private static final int MAX_MAILER_ERROR_LENGTH = 500;

    private final ObjectMapper objectMapper;
    private final EmailDeliveryAuditRepository audits;
    private final HttpClient httpClient;
    private final String mailerUrl;
    private final String mailerToken;
    private final StructuredLogService structuredLogs;

    public EmailDeliveryService(
            ObjectMapper objectMapper,
            EmailDeliveryAuditRepository audits,
            StructuredLogService structuredLogs,
            @Value("${vlugboek.mailer.url}") String mailerUrl,
            @Value("${vlugboek.mailer.token}") String mailerToken
    ) {
        this.objectMapper = objectMapper;
        this.audits = audits;
        this.structuredLogs = structuredLogs;
        this.mailerUrl = mailerUrl;
        this.mailerToken = mailerToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public EmailDeliveryResult sendDocument(DocumentRecord document, AppUser recipient, Path pdfPath) {
        String recipientName = displayName(recipient);
        String language = normaliseLanguage(recipient.getLanguage());
        EmailDeliveryAudit audit = audits.save(new EmailDeliveryAudit(
                document,
                recipient,
                recipient.getEmail(),
                recipientName,
                language,
                mailerUrl
        ));
        String requestId = "email-" + audit.getId();
        audit.assignRequestId(requestId);
        audit = audits.save(audit);
        structuredLogs.info(log, "email.requested", structuredLogs.fields(
                "requestId", requestId,
                "auditId", audit.getId(),
                "documentId", document.getId(),
                "recipient", recipient.getEmail(),
                "language", language
        ));

        try {
            if (mailerToken == null || mailerToken.isBlank()) {
                throw new EmailDeliveryException("Mailer token is not configured");
            }
            if (!Files.exists(pdfPath)) {
                throw new EmailDeliveryException("PDF file is not available on disk");
            }

            String filename = safeFilename(document.getOriginalFilename());
            if (!filename.toLowerCase().endsWith(".pdf")) {
                filename = safeFilename(document.getTitle()) + ".pdf";
            }

            String contentBase64;
            try {
                contentBase64 = Base64.getEncoder().encodeToString(Files.readAllBytes(pdfPath));
            } catch (IOException ex) {
                throw new EmailDeliveryException("Could not read PDF file from disk", ex);
            }

            EmailContent emailContent = emailContent(document, recipientName, language);
            Map<String, Object> payload = Map.of(
                    "to", recipient.getEmail(),
                    "subject", emailContent.subject(),
                    "text", emailContent.text(),
                    "html", emailContent.html(),
                    "attachments", List.of(Map.of(
                            "filename", filename,
                            "contentType", "application/pdf",
                            "contentBase64", contentBase64
                    )),
                    "requestId", requestId,
                    "meta", Map.of(
                            "auditId", audit.getId(),
                            "requestId", requestId,
                            "documentId", document.getId(),
                            "documentTitle", document.getTitle(),
                            "recipient", recipient.getEmail(),
                            "recipientName", recipientName,
                            "language", language
                    )
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(mailerUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + mailerToken)
                    .header("X-Vlugboek-Request-Id", requestId)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String body = trimMailerResponse(response.body());
                log.warn("Vlugboek mailer returned HTTP {} for document {} to {}. Body: {}",
                        response.statusCode(), document.getId(), recipient.getEmail(), body);
                throw new EmailDeliveryException("Mailer returned HTTP " + response.statusCode() + ": " + body,
                        response.statusCode());
            }

            JsonNode body = objectMapper.readTree(response.body());
            String messageId = body.path("messageId").asText("");
            audit.markSent(messageId, response.statusCode());
            audits.save(audit);
            structuredLogs.info(log, "email.sent", structuredLogs.fields(
                    "requestId", requestId,
                    "auditId", audit.getId(),
                    "documentId", document.getId(),
                    "recipient", recipient.getEmail(),
                    "providerStatus", response.statusCode(),
                    "messageId", messageId
            ));
            return new EmailDeliveryResult(messageId, audit.getId(), requestId);
        } catch (EmailDeliveryException ex) {
            recordFailure(audit, ex.getMessage(), ex.statusCode());
            throw ex;
        } catch (IOException ex) {
            log.warn("Could not reach Vlugboek mailer at {} for document {}: {}", mailerUrl, document.getId(), ex.getMessage());
            EmailDeliveryException failure = new EmailDeliveryException("Could not reach mailer at " + mailerUrl, ex);
            recordFailure(audit, failure.getMessage(), failure.statusCode());
            throw failure;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            EmailDeliveryException failure = new EmailDeliveryException("PDF email request was interrupted", ex);
            recordFailure(audit, failure.getMessage(), failure.statusCode());
            throw failure;
        } catch (IllegalArgumentException ex) {
            EmailDeliveryException failure = new EmailDeliveryException("Mailer URL is not valid", ex);
            recordFailure(audit, failure.getMessage(), failure.statusCode());
            throw failure;
        }
    }

    private String safeFilename(String input) {
        String safe = input == null ? "" : input.replaceAll("[^A-Za-z0-9.-]+", "-").replaceAll("^-+|-+$", "");
        return safe.isBlank() ? "vlugboek-document.pdf" : safe;
    }

    private String trimMailerResponse(String body) {
        if (body == null || body.isBlank()) {
            return "empty response body";
        }
        String compact = body.replaceAll("\\s+", " ").trim();
        return compact.length() <= MAX_MAILER_ERROR_LENGTH ? compact : compact.substring(0, MAX_MAILER_ERROR_LENGTH) + "...";
    }

    private void recordFailure(EmailDeliveryAudit audit, String message, Integer statusCode) {
        try {
            audit.markFailed(message, statusCode);
            audits.save(audit);
            structuredLogs.warn(log, "email.failed", structuredLogs.fields(
                    "requestId", audit.getRequestId(),
                    "auditId", audit.getId(),
                    "documentId", audit.getDocumentId(),
                    "recipient", audit.getRecipientEmail(),
                    "providerStatus", statusCode,
                    "message", message
            ));
        } catch (RuntimeException auditFailure) {
            log.warn("Could not write email delivery audit {} failure status: {}",
                    audit.getId(), auditFailure.getMessage());
        }
    }

    private EmailContent emailContent(DocumentRecord document, String name, String language) {
        String title = document.getTitle();
        String subject = "Vlugboek PDF: " + title;

        if ("af".equals(language)) {
            return new EmailContent(
                    subject,
                    """
                    Hallo %s

                    Die PDF wat jy vanaf Vlugboek aangevra het, is aangeheg.

                    Verslag: %s

                    Vriendelike groete,
                    Vlugboek
                    """.formatted(name, title),
                    htmlEmail(
                            "Hallo " + name,
                            "Die PDF wat jy vanaf Vlugboek aangevra het, is aangeheg.",
                            "Verslag",
                            title,
                            "Vriendelike groete,",
                            "Vlugboek PDF verslag"
                    )
            );
        }

        return new EmailContent(
                subject,
                """
                Hello %s

                The PDF you requested from Vlugboek is attached.

                Report: %s

                Regards,
                Vlugboek
                """.formatted(name, title),
                htmlEmail(
                        "Hello " + name,
                        "The PDF you requested from Vlugboek is attached.",
                        "Report",
                        title,
                        "Regards,",
                        "Vlugboek PDF report"
                )
        );
    }

    private String htmlEmail(String greeting, String message, String reportLabel, String reportTitle,
                             String signoff, String eyebrow) {
        return """
                <!doctype html>
                <html>
                <body style="margin:0;background:#F8F6F1;color:#0B1623;font-family:Inter,'Segoe UI',Arial,sans-serif;">
                  <div style="display:none;max-height:0;overflow:hidden;color:transparent;">%s</div>
                  <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="background:#F8F6F1;padding:28px 12px;">
                    <tr>
                      <td align="center">
                        <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="max-width:640px;background:#FFFFFF;border:1px solid #E2DACD;border-radius:8px;overflow:hidden;box-shadow:0 16px 40px rgba(11,22,35,0.10);">
                          <tr>
                            <td style="background:#0B1623;padding:26px 28px;color:#F8F6F1;">
                              <div style="font-family:Georgia,Cambria,'Times New Roman',serif;font-size:32px;line-height:1;font-weight:700;">Vlugboek</div>
                              <div style="margin-top:10px;color:#D4A85A;font-size:13px;letter-spacing:0.16em;text-transform:uppercase;">%s</div>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:30px 28px 26px;">
                              <h1 style="margin:0 0 16px;font-family:Georgia,Cambria,'Times New Roman',serif;font-size:28px;line-height:1.2;color:#0B1623;">%s</h1>
                              <p style="margin:0 0 24px;font-size:16px;line-height:1.6;color:#4F5B66;">%s</p>
                              <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="margin:0 0 26px;border:1px solid #E2DACD;border-radius:8px;background:#F8F6F1;">
                                <tr>
                                  <td style="padding:16px 18px;">
                                    <div style="margin-bottom:6px;color:#B98734;font-size:12px;font-weight:700;letter-spacing:0.14em;text-transform:uppercase;">%s</div>
                                    <div style="font-size:18px;line-height:1.35;font-weight:700;color:#0B1623;">%s</div>
                                  </td>
                                </tr>
                              </table>
                              <p style="margin:0;font-size:16px;line-height:1.6;color:#4F5B66;">%s<br><strong style="color:#0B1623;">Vlugboek</strong></p>
                            </td>
                          </tr>
                        </table>
                      </td>
                    </tr>
                  </table>
                </body>
                </html>
                """.formatted(
                escapeHtml(message),
                escapeHtml(eyebrow),
                escapeHtml(greeting),
                escapeHtml(message),
                escapeHtml(reportLabel),
                escapeHtml(reportTitle),
                escapeHtml(signoff)
        );
    }

    private String displayName(AppUser user) {
        String displayName = user.getDisplayName();
        if (displayName != null && !displayName.isBlank()) {
            return displayName.trim();
        }
        String email = user.getEmail();
        return email == null || !email.contains("@") ? "Vlugboek gebruiker" : email.substring(0, email.indexOf("@"));
    }

    private String normaliseLanguage(String language) {
        return "en".equalsIgnoreCase(language) ? "en" : "af";
    }

    private String escapeHtml(String input) {
        return input == null ? "" : input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private record EmailContent(String subject, String text, String html) {
    }
}
