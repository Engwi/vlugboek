package za.co.vlugboek.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "email_delivery_audits")
public class EmailDeliveryAudit {
    private static final int ERROR_LIMIT = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private DocumentRecord document;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(nullable = false)
    private String recipientEmail;

    @Column(nullable = false)
    private String recipientName;

    @Column(nullable = false)
    private String documentTitle;

    @Column(nullable = false, length = 12)
    private String language;

    @Column(length = 1000)
    private String mailerUrl;

    private String requestId;

    private String messageId;

    private Integer providerStatusCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EmailDeliveryStatus status = EmailDeliveryStatus.REQUESTED;

    @Column(length = ERROR_LIMIT)
    private String errorMessage;

    @Column(nullable = false)
    private Instant requestedAt = Instant.now();

    private Instant completedAt;

    protected EmailDeliveryAudit() {
    }

    public EmailDeliveryAudit(DocumentRecord document, AppUser user, String recipientEmail, String recipientName,
                              String language, String mailerUrl) {
        this.document = document;
        this.user = user;
        this.recipientEmail = recipientEmail;
        this.recipientName = recipientName;
        this.documentTitle = document.getTitle();
        this.language = language;
        this.mailerUrl = mailerUrl;
    }

    public Long getId() {
        return id;
    }

    public DocumentRecord getDocument() {
        return document;
    }

    public Long getDocumentId() {
        return document == null ? null : document.getId();
    }

    public String getRecipientEmail() {
        return recipientEmail;
    }

    public String getRequestId() {
        return requestId;
    }

    public EmailDeliveryStatus getStatus() {
        return status;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void assignRequestId(String requestId) {
        this.requestId = requestId;
    }

    public void markSent(String messageId, int providerStatusCode) {
        this.status = EmailDeliveryStatus.SENT;
        this.messageId = messageId;
        this.providerStatusCode = providerStatusCode;
        this.errorMessage = null;
        this.completedAt = Instant.now();
    }

    public void markFailed(String errorMessage, Integer providerStatusCode) {
        this.status = EmailDeliveryStatus.FAILED;
        this.errorMessage = truncate(errorMessage);
        this.providerStatusCode = providerStatusCode;
        this.completedAt = Instant.now();
    }

    private String truncate(String value) {
        if (value == null || value.isBlank()) {
            return "Unknown email delivery failure";
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= ERROR_LIMIT ? compact : compact.substring(0, ERROR_LIMIT);
    }
}
