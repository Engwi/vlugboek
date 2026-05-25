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
import java.time.LocalDate;

@Entity
@Table(name = "classification_snapshots")
public class ClassificationSnapshot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ClassificationCategory category;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dataset_id")
    private ReportDataset dataset;

    @Column(nullable = false)
    private LocalDate snapshotDate;

    @Column(nullable = false)
    private boolean latest;

    protected ClassificationSnapshot() {
    }

    public ClassificationSnapshot(ClassificationCategory category, ReportDataset dataset, LocalDate snapshotDate, boolean latest) {
        this.category = category;
        this.dataset = dataset;
        this.snapshotDate = snapshotDate;
        this.latest = latest;
    }

    public ClassificationCategory getCategory() {
        return category;
    }

    public ReportDataset getDataset() {
        return dataset;
    }

    public LocalDate getSnapshotDate() {
        return snapshotDate;
    }

    public boolean isLatest() {
        return latest;
    }

    public void setLatest(boolean latest) {
        this.latest = latest;
    }
}
