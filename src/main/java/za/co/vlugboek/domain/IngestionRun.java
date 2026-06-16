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
@Table(name = "ingestion_runs")
public class IngestionRun {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IngestionRunStatus status = IngestionRunStatus.RUNNING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "started_by_user_id")
    private AppUser startedBy;

    @Column(nullable = false)
    private Instant startedAt = Instant.now();

    private Instant completedAt;

    @Column(nullable = false, length = 1000)
    private String inboxPath;

    @Column(length = 1000)
    private String reportPath;

    private int totalFiles;

    private int importedCount;

    private int suspectCount;

    private int duplicateCount;

    private int rejectedCount;

    private int failedCount;

    protected IngestionRun() {
    }

    public IngestionRun(AppUser startedBy, String inboxPath) {
        this.startedBy = startedBy;
        this.inboxPath = inboxPath;
    }

    public Long getId() {
        return id;
    }

    public IngestionRunStatus getStatus() {
        return status;
    }

    public AppUser getStartedBy() {
        return startedBy;
    }

    public String getStartedByEmail() {
        return startedBy == null ? null : startedBy.getEmail();
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public String getInboxPath() {
        return inboxPath;
    }

    public String getReportPath() {
        return reportPath;
    }

    public int getTotalFiles() {
        return totalFiles;
    }

    public int getImportedCount() {
        return importedCount;
    }

    public int getSuspectCount() {
        return suspectCount;
    }

    public int getDuplicateCount() {
        return duplicateCount;
    }

    public int getRejectedCount() {
        return rejectedCount;
    }

    public int getFailedCount() {
        return failedCount;
    }

    public void markReportPath(String reportPath) {
        this.reportPath = reportPath;
    }

    public void markComplete(int totalFiles, int importedCount, int suspectCount, int duplicateCount,
                             int rejectedCount, int failedCount) {
        this.totalFiles = totalFiles;
        this.importedCount = importedCount;
        this.suspectCount = suspectCount;
        this.duplicateCount = duplicateCount;
        this.rejectedCount = rejectedCount;
        this.failedCount = failedCount;
        this.completedAt = Instant.now();
        this.status = failedCount > 0 || rejectedCount > 0 || suspectCount > 0
                ? IngestionRunStatus.COMPLETED_WITH_WARNINGS
                : IngestionRunStatus.COMPLETED;
    }

    public void markFailed(String reportPath) {
        this.reportPath = reportPath;
        this.completedAt = Instant.now();
        this.status = IngestionRunStatus.FAILED;
    }
}
