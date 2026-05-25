package za.co.vlugboek.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import za.co.vlugboek.domain.DocumentRecord;
import za.co.vlugboek.domain.ReportDataset;
import za.co.vlugboek.domain.ReportFamily;

class PdfRecognitionAndDatasetTest {
    private final PdfTextService pdfTextService = new PdfTextService();
    private final ReportRecognitionService recognitionService = new ReportRecognitionService();
    private final DatasetBuilderService datasetBuilderService = new DatasetBuilderService();

    @Test
    void recognisesAndParsesPretoriaRacePdf() throws Exception {
        Path path = fixture("# 3 Wedvlugte", "Britstown1OPE.pdf");
        String pdfText = pdfTextService.extract(path);

        RecognisedReport recognised = recognitionService.recognise(path.getFileName().toString(), pdfText);
        ReportDataset dataset = datasetFor(path, recognised, pdfText);

        assertThat(recognised.family()).isEqualTo(ReportFamily.RACE_DETAIL);
        assertThat(recognised.title()).containsIgnoringCase("Britstown");
        assertThat(recognised.officialDate()).isEqualTo(LocalDate.of(2025, 8, 9));
        assertThat(recognised.liberatedAt()).isEqualTo(LocalDateTime.of(2025, 8, 9, 7, 45));
        assertThat(columnNames(dataset)).containsExactly(
                "Pos", "Loft Name", "Bird No", "Ring Id", "Year", "Colour", "Sex",
                "Club", "Velocity", "Var", "Clock Time", "Distance Km", "ToWin"
        );
        assertThat(dataset.getRows()).hasSizeGreaterThan(100);
        assertThat(rowValues(dataset, 0)).contains("1", "KACHELHOFFER&TROSKIE 1", "1174.1213");
    }

    @Test
    void parsesLoftPointsPdf() throws Exception {
        Path path = fixture("# 4 Hok Punte", "HokPunte.pdf");
        String pdfText = pdfTextService.extract(path);

        RecognisedReport recognised = recognitionService.recognise(path.getFileName().toString(), pdfText);
        ReportDataset dataset = datasetFor(path, recognised, pdfText);

        assertThat(recognised.family()).isEqualTo(ReportFamily.CLASSIFICATION);
        assertThat(columnNames(dataset)).containsExactly("Pos", "Member", "Short", "Middle", "Long", "Total");
        assertThat(dataset.getRows()).hasSizeGreaterThan(40);
        assertThat(rowValues(dataset, 0)).containsExactly("1", "KACHELHOFFER&TROSKIE 1", "10787", "13034", "19499", "43320");
    }

    @Test
    void parsesShortDistanceLogPdf() throws Exception {
        Path path = fixture("# 1 Kort Pad", "Kort PadPunte.pdf");
        String pdfText = pdfTextService.extract(path);

        RecognisedReport recognised = recognitionService.recognise(path.getFileName().toString(), pdfText);
        ReportDataset dataset = datasetFor(path, recognised, pdfText);

        assertThat(recognised.family()).isEqualTo(ReportFamily.DISTANCE_LOG);
        assertThat(columnNames(dataset)).startsWith("Pos", "Member", "Race 1");
        assertThat(dataset.getRows()).hasSizeGreaterThan(40);
        assertThat(rowValues(dataset, 0)).contains("1", "STOLZ FAMILIE 1", "1930");
    }

    @Test
    void recognisesAndParsesCombinePdf() throws Exception {
        Path path = fixture("# 7 Combine Results", "GWC BEAUFORT WEST OPEN.pdf");
        String pdfText = pdfTextService.extract(path);

        RecognisedReport recognised = recognitionService.recognise(path.getFileName().toString(), pdfText);
        ReportDataset dataset = datasetFor(path, recognised, pdfText);

        assertThat(recognised.recognisedType()).isEqualTo("Combine Race Result");
        assertThat(recognised.title()).containsIgnoringCase("GWC");
        assertThat(columnNames(dataset)).containsExactly(
                "Pos", "Loft Name", "Club", "Ring Id", "Year", "Bird No",
                "Colour", "Sex", "Clock Time", "Var", "Distance Km", "Velocity"
        );
        assertThat(dataset.getRows()).hasSizeGreaterThan(50);
        assertThat(rowValues(dataset, 0)).contains("1", "F&A LENSLEY A", "CPK", "1149.831");
    }

    private ReportDataset datasetFor(Path path, RecognisedReport recognised, String pdfText) throws Exception {
        DocumentRecord document = new DocumentRecord(
                recognised.title(),
                path.getFileName().toString(),
                path.toString(),
                "application/pdf",
                Files.size(path)
        );
        document.applyRecognition(
                recognised.family(),
                recognised.category(),
                recognised.recognisedType(),
                recognised.title(),
                recognised.officialDate(),
                recognised.liberatedAt(),
                recognised.reportCreatedAt()
        );
        return datasetBuilderService.buildDataset(document, pdfText);
    }

    private Path fixture(String folder, String filename) {
        return Path.of("Docs", "Uitslae", folder, filename);
    }

    private List<String> columnNames(ReportDataset dataset) {
        return dataset.getColumns().stream()
                .map(column -> column.getName())
                .toList();
    }

    private List<String> rowValues(ReportDataset dataset, int index) {
        return dataset.getRows().get(index).getCells().stream()
                .map(cell -> cell.getTextValue())
                .toList();
    }
}
