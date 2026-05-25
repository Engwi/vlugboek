package za.co.vlugboek.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "report_rows")
public class ReportRow {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dataset_id")
    private ReportDataset dataset;

    @Column(nullable = false)
    private int rowIndex;

    @OneToMany(mappedBy = "row", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("columnIndex ASC")
    private List<ReportCell> cells = new ArrayList<>();

    protected ReportRow() {
    }

    public ReportRow(ReportDataset dataset, int rowIndex) {
        this.dataset = dataset;
        this.rowIndex = rowIndex;
    }

    public int getRowIndex() {
        return rowIndex;
    }

    public List<ReportCell> getCells() {
        return cells;
    }

    public void addCell(String value, int columnIndex) {
        cells.add(new ReportCell(this, value, columnIndex));
    }
}
