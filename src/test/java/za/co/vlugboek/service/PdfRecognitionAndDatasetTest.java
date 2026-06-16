package za.co.vlugboek.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import za.co.vlugboek.domain.ClassificationCategory;
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

        assertThat(recognised.family()).isEqualTo(ReportFamily.COMBINE);
        assertThat(recognised.recognisedType()).isEqualTo("Combine Race Result");
        assertThat(recognised.category()).isEqualTo(ClassificationCategory.NONE);
        assertThat(recognised.title()).containsIgnoringCase("GWC");
        assertThat(columnNames(dataset)).containsExactly(
                "Pos", "Loft Name", "Club", "Ring Id", "Year", "Bird No",
                "Colour", "Sex", "Clock Time", "Var", "Distance Km", "Velocity"
        );
        assertThat(dataset.getRows()).hasSizeGreaterThan(50);
        assertThat(rowValues(dataset, 0)).contains("1", "F&A LENSLEY A", "CPK", "1149.831");
    }

    @Test
    void parsesOfficialFederationRacePdfWithIntegerDistance() throws Exception {
        Path path = fixture2026("Week 1", "THEUNISSEN2026JO.pdf");
        String pdfText = pdfTextService.extract(path);

        RecognisedReport recognised = recognitionService.recognise(path.getFileName().toString(), pdfText);
        ReportDataset dataset = datasetFor(path, recognised, pdfText);

        assertThat(recognised.family()).isEqualTo(ReportFamily.RACE_DETAIL);
        assertThat(columnNames(dataset)).containsExactly(
                "Pos", "Loft Name", "Ring Id", "Year", "Bird No", "Colour", "Sex",
                "Clock Time", "Var", "Distance", "Velocity", "Pools"
        );
        assertThat(dataset.getRows()).hasSizeGreaterThan(100);
        assertThat(rowValues(dataset, 0)).containsExactly(
                "1", "CST B", "CST", "25", "1359", "BB", "C", "11:54:37", "0", "324502", "1300.0013", ""
        );
    }

    @Test
    void parsesOfficialClubRacePdfWithBirdCounter() throws Exception {
        Path path = fixture2026("Week 1", "THEUNISSEN2026JOWESMOOT.pdf");
        String pdfText = pdfTextService.extract(path);

        RecognisedReport recognised = recognitionService.recognise(path.getFileName().toString(), pdfText);
        ReportDataset dataset = datasetFor(path, recognised, pdfText);

        assertThat(recognised.family()).isEqualTo(ReportFamily.RACE_DETAIL);
        assertThat(columnNames(dataset)).containsExactly(
                "Pos", "Loft Name", "Club", "Ring Id", "Year", "Bird No", "Colour", "Sex",
                "Bd#", "Clock Time", "Var", "Coeff", "Velocity"
        );
        assertThat(dataset.getRows()).hasSizeGreaterThan(40);
        assertThat(rowValues(dataset, 0)).containsExactly(
                "1", "CST B", "PWF", "CST", "25", "1359", "BB", "C", "1", "11:54:37", "0", "", "1300.0013"
        );
    }

    @Test
    void parsesOfficialWeekOneRacePdfsWithoutRawFallback() throws Exception {
        try (var paths = Files.list(Path.of("Docs", "Uitslae 2026", "Week 1"))) {
            List<Path> racePdfs = paths
                    .filter(path -> path.getFileName().toString().startsWith("THEUNISSEN"))
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".pdf"))
                    .toList();

            assertThat(racePdfs).isNotEmpty();
            for (Path path : racePdfs) {
                String pdfText = pdfTextService.extract(path);
                RecognisedReport recognised = recognitionService.recognise(path.getFileName().toString(), pdfText);
                ReportDataset dataset = datasetFor(path, recognised, pdfText);

                assertThat(columnNames(dataset))
                        .as(path.getFileName().toString())
                        .doesNotContain("Line", "Text")
                        .startsWith("Pos", "Loft Name");
                assertThat(dataset.getRows())
                        .as(path.getFileName().toString())
                        .isNotEmpty();
            }
        }
    }

    @Test
    void recognisesAndParsesCombineLogPdfs() throws Exception {
        assertCombineLog("Week 2", "GWC BEST BIRD.pdf", ClassificationCategory.COMBINE_BIRDS_LOG_SHORT_DISTANCE_ALL_RACES,
                "BIRDS LOG - SHORT DISTANCE - ALL RACES");
        assertCombineLog("Week 2", "GWC BEST OLD BIRD.pdf", ClassificationCategory.COMBINE_BIRDS_LOG_SHORT_DISTANCE_OPEN_RACES,
                "BIRDS LOG - SHORT DISTANCE - OPEN RACES");
        assertCombineLog("Week 2", "GWC BEST YB .pdf", ClassificationCategory.COMBINE_BIRDS_LOG_SHORT_DISTANCE_YEARLING_RACES,
                "BIRDS LOG - SHORT DISTANCE - YEARLING RACES");
        assertCombineLog("Week 2", "GWC OLD BIRD POINTS.pdf", ClassificationCategory.COMBINE_SHORT_DISTANCE_LOG_OPEN_RACES,
                "SHORT DISTANCE LOG - OPEN RACES");
        assertCombineLog("Week 2", "GWC OVERALL POINTS.pdf", ClassificationCategory.COMBINE_SHORT_DISTANCE_LOG_ALL_RACES,
                "SHORT DISTANCE LOG - ALL RACES");
        assertCombineLog("Week 2", "GWC YB POINTS.pdf", ClassificationCategory.COMBINE_SHORT_DISTANCE_LOG_YEARLING_RACES,
                "SHORT DISTANCE LOG - YEARLING RACES");
        assertCombineLog("Week 3", "GWC Open points.pdf", ClassificationCategory.COMBINE_OVERALL_LOG_OPEN_RACES,
                "OVERALL LOG - OPEN RACES");
        assertCombineLog("Week 3", "GWC Overall points.pdf", ClassificationCategory.COMBINE_OVERALL_LOG_ALL_RACES,
                "OVERALL LOG - ALL RACES");
        assertCombineLog("Week 3", "GWC Young Bird points.pdf", ClassificationCategory.COMBINE_OVERALL_LOG_YEARLING_RACES,
                "OVERALL LOG - YEARLING RACES");
    }

    @Test
    void recognisesAndParsesCombineRacePdfsFromCurrentSeason() throws Exception {
        assertCombineRace("Week 2", "GWC BULTFONTEIN OPE.pdf");
        assertCombineRace("Week 2", "GWC BULTFONTEIN YB.pdf");
        assertCombineRace("Week 3", "GWC Brandfort Ope.pdf");
        assertCombineRace("Week 3", "GWC Brandfort YB.pdf");
    }

    @Test
    void parsesLegacyRaceRowsWithoutClubColumnAsFullLoftNames() throws Exception {
        DocumentRecord document = new DocumentRecord(
                "BULTFONTEIN 1 YB",
                "BULTFONTEIN2026JO.pdf",
                "BULTFONTEIN2026JO.pdf",
                "application/pdf",
                0
        );
        document.applyRecognition(
                ReportFamily.RACE_DETAIL,
                ClassificationCategory.NONE,
                "Race Detail Report",
                "BULTFONTEIN 1 YB",
                LocalDate.of(2026, 6, 6),
                LocalDateTime.of(2026, 6, 6, 8, 30),
                null
        );

        String pdfText = String.join("\n",
                "BULTFONTEIN 1 YB",
                "Pos Loft Name Bird Particulars Velocity Var Clock Time Pos Pools ToWin Distance Km",
                "1 CST A CST 25 1210 BB C 12:24:37 1 338.521 1442.8685",
                "2 CST A CST 25 1283 BB H 12:24:38 1 338.521 00:00:01 1442.7660",
                "3 HANS&LOUIS PWFD 25 5312 PIED H 12:28:40 0 340.783 00:02:28 1427.8617",
                "4 HANS&LOUIS PWF 25 8127 BBWP H 12:28:43 0 340.783 00:02:31 1427.5627",
                "9 ABRIE&CHRIS B PWFD 25 5020 BB H 12:34:00 0 347.791 00:02:57 1425.3730",
                "11 LEN&LANA PWFD 25 3604 H 12:33:52 -1 346.981 00:03:24 1422.7336",
                "12 STOLZ FAMILIE 1 PWFD 25 4564 BB H 12:36:23 -1 349.990 00:03:50 1420.4140",
                "13 DIE WHEELERS C&N 1 VSDK 25 2146 BCP H 12:40:14 0 355.434 00:03:53 1420.4103",
                "20 PIETER DE BEER PWFD 25 4897 BB H 12:38:25 1 350.304 00:05:38 1410.1469"
        );

        ReportDataset dataset = datasetBuilderService.buildDataset(document, pdfText);

        assertThat(columnNames(dataset)).containsExactly(
                "Pos", "Loft Name", "Bird No", "Ring Id", "Year", "Colour", "Sex",
                "Club", "Velocity", "Var", "Clock Time", "Distance Km", "ToWin"
        );
        assertThat(dataset.getRows()).hasSize(9);
        assertThat(rowValues(dataset, 0)).containsExactly(
                "1", "CST A", "1210", "CST", "25", "BB", "C", "",
                "1442.8685", "1", "12:24:37", "338.521", ""
        );
        assertThat(rowValues(dataset, 2)).containsExactly(
                "3", "HANS&LOUIS", "5312", "PWFD", "25", "PIED", "H", "",
                "1427.8617", "0", "12:28:40", "340.783", "00:02:28"
        );
        assertThat(rowValues(dataset, 4)).containsExactly(
                "9", "ABRIE&CHRIS B", "5020", "PWFD", "25", "BB", "H", "",
                "1425.3730", "0", "12:34:00", "347.791", "00:02:57"
        );
        assertThat(rowValues(dataset, 5)).containsExactly(
                "11", "LEN&LANA", "3604", "PWFD", "25", "", "H", "",
                "1422.7336", "-1", "12:33:52", "346.981", "00:03:24"
        );
        assertThat(rowValues(dataset, 7)).containsExactly(
                "13", "DIE WHEELERS C&N 1", "2146", "VSDK", "25", "BCP", "H", "",
                "1420.4103", "0", "12:40:14", "355.434", "00:03:53"
        );
        assertThat(rowValues(dataset, 8)).containsExactly(
                "20", "PIETER DE BEER", "4897", "PWFD", "25", "BB", "H", "",
                "1410.1469", "1", "12:38:25", "350.304", "00:05:38"
        );
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

    private void assertCombineLog(String folder, String filename, ClassificationCategory category, String titlePart) throws Exception {
        Path path = fixture2026(folder, filename);
        String pdfText = pdfTextService.extract(path);

        RecognisedReport recognised = recognitionService.recognise(path.getFileName().toString(), pdfText);
        ReportDataset dataset = datasetFor(path, recognised, pdfText);

        assertThat(recognised.family()).as(filename).isEqualTo(ReportFamily.COMBINE);
        assertThat(recognised.category()).as(filename).isEqualTo(category);
        assertThat(recognised.title()).as(filename).contains(titlePart);
        assertThat(columnNames(dataset)).as(filename).doesNotContain("Line", "Text").startsWith("Pos", "Member");
        assertThat(dataset.getRows()).as(filename).isNotEmpty();
    }

    private void assertCombineRace(String folder, String filename) throws Exception {
        Path path = fixture2026(folder, filename);
        String pdfText = pdfTextService.extract(path);

        RecognisedReport recognised = recognitionService.recognise(path.getFileName().toString(), pdfText);
        ReportDataset dataset = datasetFor(path, recognised, pdfText);

        assertThat(recognised.family()).as(filename).isEqualTo(ReportFamily.COMBINE);
        assertThat(recognised.category()).as(filename).isEqualTo(ClassificationCategory.NONE);
        assertThat(recognised.recognisedType()).as(filename).isEqualTo("Combine Race Result");
        assertThat(columnNames(dataset)).as(filename).containsExactly(
                "Pos", "Loft Name", "Club", "Ring Id", "Year", "Bird No",
                "Colour", "Sex", "Clock Time", "Var", "Distance Km", "Velocity"
        );
        assertThat(dataset.getRows()).as(filename).isNotEmpty();
    }

    private Path fixture(String folder, String filename) {
        return Path.of("Docs", "Uitslae", folder, filename);
    }

    private Path fixture2026(String folder, String filename) {
        return Path.of("Docs", "Uitslae 2026", folder, filename);
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
