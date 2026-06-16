package za.co.vlugboek.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import za.co.vlugboek.domain.AppUser;
import za.co.vlugboek.domain.DocumentRecord;
import za.co.vlugboek.domain.IngestionItem;
import za.co.vlugboek.domain.IngestionItemStatus;
import za.co.vlugboek.domain.IngestionRun;
import za.co.vlugboek.domain.ReportDataset;
import za.co.vlugboek.domain.ReportFamily;
import za.co.vlugboek.repo.IngestionItemRepository;
import za.co.vlugboek.repo.IngestionRunRepository;
import za.co.vlugboek.repo.ReportDatasetRepository;

@Service
public class IngestionPipelineService {
    private static final DateTimeFormatter REPORT_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final DocumentService documentService;
    private final ReportDatasetRepository datasets;
    private final IngestionRunRepository runs;
    private final IngestionItemRepository items;
    private final Path rootPath;
    private final Path inboxPath;
    private final Path processingPath;
    private final Path importedPath;
    private final Path skippedPath;
    private final Path rejectedPath;
    private final Path reportsPath;

    public IngestionPipelineService(DocumentService documentService, ReportDatasetRepository datasets,
                                    IngestionRunRepository runs, IngestionItemRepository items,
                                    @Value("${vlugboek.ingestion.root}") String ingestionRoot) {
        this.documentService = documentService;
        this.datasets = datasets;
        this.runs = runs;
        this.items = items;
        this.rootPath = Path.of(ingestionRoot).toAbsolutePath().normalize();
        this.inboxPath = rootPath.resolve("inbox");
        this.processingPath = rootPath.resolve("processing");
        this.importedPath = rootPath.resolve("imported");
        this.skippedPath = rootPath.resolve("skipped");
        this.rejectedPath = rootPath.resolve("rejected");
        this.reportsPath = rootPath.resolve("reports");
    }

    public synchronized IngestionRun runInbox(AppUser actor) {
        return runInbox(actor, null);
    }

    public synchronized IngestionRun runInbox(AppUser actor, LocalDate effectiveDate) {
        ensureDirectories();

        IngestionRun run = runs.save(new IngestionRun(actor, inboxPath.toString()));
        List<IngestionItem> processed = new ArrayList<>();
        Path reportPath = reportsPath.resolve("ingestion-run-" + run.getId() + ".html");
        run.markReportPath(reportPath.toString());
        runs.save(run);

        try {
            for (Path source : inboxFiles()) {
                processed.add(processFile(run, source, effectiveDate));
            }
            finishRun(run, processed);
        } catch (RuntimeException ex) {
            run.markFailed(reportPath.toString());
            runs.save(run);
            throw ex;
        } finally {
            writeReport(run, processed, reportPath, effectiveDate);
        }

        return run;
    }

    public IngestionPaths paths() {
        ensureDirectories();
        return new IngestionPaths(
                rootPath.toString(),
                inboxPath.toString(),
                processingPath.toString(),
                importedPath.toString(),
                skippedPath.toString(),
                rejectedPath.toString(),
                reportsPath.toString()
        );
    }

    public String reportHtml(IngestionRun run) {
        if (run.getReportPath() == null || run.getReportPath().isBlank()) {
            return minimalReport(run, List.of(), "No report file has been written for this run.");
        }
        Path path = Path.of(run.getReportPath());
        if (!Files.isRegularFile(path)) {
            return minimalReport(run, List.of(), "The report file is not available on disk.");
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private IngestionItem processFile(IngestionRun run, Path source, LocalDate effectiveDate) {
        Long fileSize = safeSize(source);
        IngestionItem item = items.save(new IngestionItem(run, source.getFileName().toString(), source.toString(), fileSize));
        Path processing = processingPath.resolve(run.getId() + "-" + item.getId() + "-" + safeFilename(source.getFileName().toString()));
        Path activePath = processing;

        try {
            Files.move(source, processing, StandardCopyOption.REPLACE_EXISTING);
            item.markArchivePath(processing.toString());
            items.save(item);

            if (!processing.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                Path rejected = moveToArchive(processing, rejectedPath, run.getId(), item.getId());
                item.markRejected("Only PDF files can be ingested.", rejected.toString());
                return items.save(item);
            }

            String sha256 = documentService.contentSha256(processing);
            item.markSha256(sha256);
            Optional<DocumentRecord> duplicate = documentService.findDuplicatePdf(processing);
            if (duplicate.isPresent()) {
                Path skipped = moveToArchive(processing, skippedPath, run.getId(), item.getId());
                item.markDuplicate(duplicate.get(), skipped.toString());
                return items.save(item);
            }

            Path imported = moveToArchive(processing, importedPath, run.getId(), item.getId());
            activePath = imported;
            item.markArchivePath(imported.toString());
            items.save(item);

            DocumentRecord document = documentService.ingestExistingPdf(imported, item.getFilename(), effectiveDate);
            ReportDataset dataset = datasets.findByDocumentId(document.getId()).orElseThrow();
            List<String> warnings = warningsFor(document, dataset);
            item.markImported(document, dataset.getRows().size(), dataset.getColumns().size(), String.join("; ", warnings));
            return items.save(item);
        } catch (Exception ex) {
            Path rejected = moveIfPossible(activePath, rejectedPath, run.getId(), item.getId());
            item.markFailed(messageOf(ex), rejected == null ? item.getArchivePath() : rejected.toString());
            return items.save(item);
        }
    }

    private void finishRun(IngestionRun run, List<IngestionItem> processed) {
        int importedCount = count(processed, IngestionItemStatus.IMPORTED);
        int suspectCount = count(processed, IngestionItemStatus.SUSPECT);
        int duplicateCount = count(processed, IngestionItemStatus.DUPLICATE);
        int rejectedCount = count(processed, IngestionItemStatus.REJECTED);
        int failedCount = count(processed, IngestionItemStatus.FAILED);
        run.markComplete(processed.size(), importedCount, suspectCount, duplicateCount, rejectedCount, failedCount);
        runs.save(run);
    }

    private List<String> warningsFor(DocumentRecord document, ReportDataset dataset) {
        List<String> warnings = new ArrayList<>();
        int columnCount = dataset.getColumns().size();
        int rowCount = dataset.getRows().size();
        if (document.getReportFamily() == ReportFamily.UNKNOWN) {
            warnings.add("Report type was not recognised");
        }
        if (rowCount == 0) {
            warnings.add("No rows were parsed");
        }
        if (columnCount == 0) {
            warnings.add("No columns were parsed");
        }
        if (document.getReportFamily() == ReportFamily.RACE_DETAIL && columnCount < 5) {
            warnings.add("Race detail has fewer than 5 recognised columns");
        }
        if (document.getOfficialDate() == null && document.getReportFamily() != ReportFamily.UNKNOWN) {
            warnings.add("Official date was not recognised");
        }
        return warnings;
    }

    private List<Path> inboxFiles() {
        try (var stream = Files.list(inboxPath)) {
            return stream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private Path moveToArchive(Path source, Path targetDirectory, Long runId, Long itemId) throws IOException {
        Files.createDirectories(targetDirectory);
        Path target = targetDirectory.resolve(runId + "-" + itemId + "-" + safeFilename(source.getFileName().toString()));
        return Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private Path moveIfPossible(Path source, Path targetDirectory, Long runId, Long itemId) {
        if (source == null || !Files.exists(source)) {
            return null;
        }
        try {
            return moveToArchive(source, targetDirectory, runId, itemId);
        } catch (IOException ignored) {
            return source;
        }
    }

    private int count(List<IngestionItem> processed, IngestionItemStatus status) {
        return (int) processed.stream().filter(item -> item.getStatus() == status).count();
    }

    private Long safeSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ex) {
            return null;
        }
    }

    private void ensureDirectories() {
        try {
            Files.createDirectories(inboxPath);
            Files.createDirectories(processingPath);
            Files.createDirectories(importedPath);
            Files.createDirectories(skippedPath);
            Files.createDirectories(rejectedPath);
            Files.createDirectories(reportsPath);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private String safeFilename(String filename) {
        String clean = filename == null ? "document.pdf" : filename;
        clean = clean.replaceAll("[\\\\/:*?\"<>|]+", "-").replaceAll("\\s+", " ").trim();
        return clean.isBlank() ? "document.pdf" : clean;
    }

    private String messageOf(Exception ex) {
        Throwable current = ex;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null || current.getMessage().isBlank()
                ? ex.getClass().getSimpleName()
                : current.getMessage();
    }

    private void writeReport(IngestionRun run, List<IngestionItem> processed, Path reportPath, LocalDate effectiveDate) {
        try {
            Files.createDirectories(reportPath.getParent());
            Files.writeString(reportPath, reportHtml(run, processed, effectiveDate), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private String minimalReport(IngestionRun run, List<IngestionItem> processed, String message) {
        return reportHtml(run, processed, null) + "<!-- " + escape(message) + " -->";
    }

    private String reportHtml(IngestionRun run, List<IngestionItem> processed, LocalDate effectiveDate) {
        String rows = processed.stream().map(this::itemRow).reduce("", String::concat);
        if (rows.isBlank()) {
            rows = """
                    <tr><td colspan="9" class="empty">No files were found in the inbox.</td></tr>
                    """;
        }
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>Vlugboek Ingestion Run #%s</title>
                  <style>
                    body { margin: 0; padding: 24px; background: #f8f6f1; color: #182331; font-family: Segoe UI, Arial, sans-serif; }
                    main { max-width: 1180px; margin: 0 auto; }
                    h1 { margin: 0; color: #0b1623; font: 700 2rem Georgia, "Times New Roman", serif; }
                    .meta, .counts { display: flex; flex-wrap: wrap; gap: 8px; margin: 16px 0; }
                    .pill { border-radius: 8px; background: #ebe4d7; padding: 7px 10px; font-size: 13px; }
                    table { width: 100%%; border-collapse: collapse; background: white; border: 1px solid #d8d1c4; border-radius: 8px; overflow: hidden; }
                    th { background: #0b1623; color: #f8f6f1; text-align: left; font-size: 12px; padding: 10px; }
                    td { border-top: 1px solid #e5dfd4; padding: 10px; vertical-align: top; font-size: 13px; }
                    tr:nth-child(even) td { background: #fbfaf7; }
                    .status { font-weight: 700; white-space: nowrap; }
                    .IMPORTED { color: #2f6b57; }
                    .SUSPECT, .DUPLICATE { color: #aa7b18; }
                    .REJECTED, .FAILED { color: #8b2635; }
                    .empty { text-align: center; color: #5c6675; }
                    .path { color: #5c6675; word-break: break-all; }
                  </style>
                </head>
                <body>
                  <main>
                    <p class="pill">Vlugboek ingestion report</p>
                    <h1>Run #%s</h1>
                    <div class="meta">
                      <span class="pill">Status: %s</span>
                      <span class="pill">Started: %s</span>
                      <span class="pill">Completed: %s</span>
                      <span class="pill">Effective date: %s</span>
                      <span class="pill">Inbox: %s</span>
                    </div>
                    <div class="counts">
                      <span class="pill">Files: %s</span>
                      <span class="pill">Imported: %s</span>
                      <span class="pill">Suspect: %s</span>
                      <span class="pill">Duplicate: %s</span>
                      <span class="pill">Rejected: %s</span>
                      <span class="pill">Failed: %s</span>
                    </div>
                    <table>
                      <thead>
                        <tr>
                          <th>Status</th>
                          <th>File</th>
                          <th>Document</th>
                          <th>Type</th>
                          <th>Rows</th>
                          <th>Columns</th>
                          <th>SHA-256</th>
                          <th>Message</th>
                          <th>Archive</th>
                        </tr>
                      </thead>
                      <tbody>
                        %s
                      </tbody>
                    </table>
                  </main>
                </body>
                </html>
                """.formatted(
                run.getId(),
                run.getId(),
                escape(run.getStatus().name()),
                escape(formatTime(run.getStartedAt())),
                escape(formatTime(run.getCompletedAt())),
                escape(effectiveDate == null ? "-" : effectiveDate.toString()),
                escape(run.getInboxPath()),
                run.getTotalFiles(),
                run.getImportedCount(),
                run.getSuspectCount(),
                run.getDuplicateCount(),
                run.getRejectedCount(),
                run.getFailedCount(),
                rows
        );
    }

    private String itemRow(IngestionItem item) {
        return """
                <tr>
                  <td class="status %s">%s</td>
                  <td>%s</td>
                  <td>%s</td>
                  <td>%s</td>
                  <td>%s</td>
                  <td>%s</td>
                  <td class="path">%s</td>
                  <td>%s%s</td>
                  <td class="path">%s</td>
                </tr>
                """.formatted(
                item.getStatus().name(),
                escape(item.getStatus().name()),
                escape(item.getFilename()),
                escape(Objects.toString(item.getTitle(), "-")),
                escape(Objects.toString(item.getReportFamily(), "-")),
                escape(Objects.toString(item.getRowCount(), "-")),
                escape(Objects.toString(item.getColumnCount(), "-")),
                escape(Objects.toString(item.getContentSha256(), "-")),
                escape(Objects.toString(item.getMessage(), "")),
                item.getWarnings() == null || item.getWarnings().isBlank() ? "" : "<br><strong>Warnings:</strong> " + escape(item.getWarnings()),
                escape(Objects.toString(item.getArchivePath(), "-"))
        );
    }

    private String formatTime(Instant instant) {
        return instant == null ? "-" : REPORT_TIME.format(instant);
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
