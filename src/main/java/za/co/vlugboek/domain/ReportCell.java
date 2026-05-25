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
@Table(name = "report_cells")
public class ReportCell {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "row_id")
    private ReportRow row;

    @Column(length = 2000)
    private String textValue;

    @Column(nullable = false)
    private int columnIndex;

    protected ReportCell() {
    }

    public ReportCell(ReportRow row, String textValue, int columnIndex) {
        this.row = row;
        this.textValue = textValue;
        this.columnIndex = columnIndex;
    }

    public String getTextValue() {
        return textValue;
    }

    public int getColumnIndex() {
        return columnIndex;
    }
}
