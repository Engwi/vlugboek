package za.co.vlugboek.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "report_datasets")
public class ReportDataset {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", unique = true)
    private DocumentRecord document;

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReportFamily reportFamily;

    private LocalDate officialDate;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "dataset", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("positionIndex ASC")
    private List<ReportColumn> columns = new ArrayList<>();

    @OneToMany(mappedBy = "dataset", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("rowIndex ASC")
    private List<ReportRow> rows = new ArrayList<>();

    protected ReportDataset() {
    }

    public ReportDataset(DocumentRecord document, String title, ReportFamily reportFamily, LocalDate officialDate) {
        this.document = document;
        this.title = title;
        this.reportFamily = reportFamily;
        this.officialDate = officialDate;
    }

    public Long getId() {
        return id;
    }

    public DocumentRecord getDocument() {
        return document;
    }

    public String getTitle() {
        return title;
    }

    public ReportFamily getReportFamily() {
        return reportFamily;
    }

    public LocalDate getOfficialDate() {
        return officialDate;
    }

    public List<ReportColumn> getColumns() {
        return columns;
    }

    public List<ReportRow> getRows() {
        return rows;
    }

    public void addColumn(String name, int positionIndex) {
        columns.add(new ReportColumn(this, name, positionIndex));
    }

    public void addRow(List<String> values, int rowIndex) {
        ReportRow row = new ReportRow(this, rowIndex);
        for (int i = 0; i < values.size(); i++) {
            row.addCell(values.get(i), i);
        }
        rows.add(row);
    }
}
