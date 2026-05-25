package za.co.vlugboek.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "report_columns")
public class ReportColumn {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dataset_id")
    private ReportDataset dataset;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int positionIndex;

    protected ReportColumn() {
    }

    public ReportColumn(ReportDataset dataset, String name, int positionIndex) {
        this.dataset = dataset;
        this.name = name;
        this.positionIndex = positionIndex;
    }

    public String getName() {
        return name;
    }

    public int getPositionIndex() {
        return positionIndex;
    }
}
