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

@Entity
@Table(name = "ingestion_items")
public class IngestionItem {
    private static final int MESSAGE_LIMIT = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id", nullable = false)
    private IngestionRun run;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id")
    private DocumentRecord document;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IngestionItemStatus status = IngestionItemStatus.REJECTED;

    @Column(nullable = false)
    private String filename;

    @Column(length = 1000)
    private String sourcePath;

    @Column(length = 1000)
    private String archivePath;

    @Column(length = 64)
    private String contentSha256;

    private Long fileSize;

    private String title;

    private String recognisedType;

    private String reportFamily;

    private Integer rowCount;

    private Integer columnCount;

    @Column(length = MESSAGE_LIMIT)
    private String message;

    @Column(length = MESSAGE_LIMIT)
    private String warnings;

    protected IngestionItem() {
    }

    public IngestionItem(IngestionRun run, String filename, String sourcePath, Long fileSize) {
        this.run = run;
        this.filename = filename;
        this.sourcePath = sourcePath;
        this.fileSize = fileSize;
    }

    public Long getId() {
        return id;
    }

    public IngestionRun getRun() {
        return run;
    }

    public DocumentRecord getDocument() {
        return document;
    }

    public Long getDocumentId() {
        return document == null ? null : document.getId();
    }

    public IngestionItemStatus getStatus() {
        return status;
    }

    public String getFilename() {
        return filename;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public String getArchivePath() {
        return archivePath;
    }

    public String getContentSha256() {
        return contentSha256;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public String getTitle() {
        return title;
    }

    public String getRecognisedType() {
        return recognisedType;
    }

    public String getReportFamily() {
        return reportFamily;
    }

    public Integer getRowCount() {
        return rowCount;
    }

    public Integer getColumnCount() {
        return columnCount;
    }

    public String getMessage() {
        return message;
    }

    public String getWarnings() {
        return warnings;
    }

    public void markArchivePath(String archivePath) {
        this.archivePath = archivePath;
    }

    public void markSha256(String contentSha256) {
        this.contentSha256 = contentSha256;
    }

    public void markDuplicate(DocumentRecord existing, String archivePath) {
        this.status = IngestionItemStatus.DUPLICATE;
        this.document = existing;
        this.archivePath = archivePath;
        this.title = existing.getTitle();
        this.recognisedType = existing.getRecognisedType();
        this.reportFamily = existing.getReportFamily().name();
        this.message = "Skipped duplicate of document #" + existing.getId();
    }

    public void markImported(DocumentRecord document, int rowCount, int columnCount, String warnings) {
        this.document = document;
        this.title = document.getTitle();
        this.recognisedType = document.getRecognisedType();
        this.reportFamily = document.getReportFamily().name();
        this.rowCount = rowCount;
        this.columnCount = columnCount;
        this.warnings = truncate(warnings);
        this.status = warnings == null || warnings.isBlank() ? IngestionItemStatus.IMPORTED : IngestionItemStatus.SUSPECT;
        this.message = this.status == IngestionItemStatus.IMPORTED ? "Imported and published" : "Imported with warnings";
    }

    public void markRejected(String message, String archivePath) {
        this.status = IngestionItemStatus.REJECTED;
        this.message = truncate(message);
        this.archivePath = archivePath;
    }

    public void markFailed(String message, String archivePath) {
        this.status = IngestionItemStatus.FAILED;
        this.message = truncate(message);
        this.archivePath = archivePath;
    }

    private String truncate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= MESSAGE_LIMIT ? compact : compact.substring(0, MESSAGE_LIMIT);
    }
}
