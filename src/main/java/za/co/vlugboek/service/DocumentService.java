package za.co.vlugboek.service;

import jakarta.transaction.Transactional;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import za.co.vlugboek.domain.ClassificationCategory;
import za.co.vlugboek.domain.ClassificationSnapshot;
import za.co.vlugboek.domain.DocumentRecord;
import za.co.vlugboek.domain.DocumentStatus;
import za.co.vlugboek.domain.Federation;
import za.co.vlugboek.domain.ReportColumn;
import za.co.vlugboek.domain.ReportDataset;
import za.co.vlugboek.domain.ReportFamily;
import za.co.vlugboek.repo.ClassificationSnapshotRepository;
import za.co.vlugboek.repo.DocumentRepository;
import za.co.vlugboek.repo.FederationRepository;
import za.co.vlugboek.repo.ReportDatasetRepository;

@Service
public class DocumentService {
    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final DocumentRepository documents;
    private final ReportDatasetRepository datasets;
    private final ClassificationSnapshotRepository snapshots;
    private final FederationRepository federations;
    private final ReportRecognitionService recognitionService;
    private final DatasetBuilderService datasetBuilderService;
    private final PdfTextService pdfTextService;
    private final StructuredLogService structuredLogs;
    private final Path uploadsDir;

    public DocumentService(DocumentRepository documents, ReportDatasetRepository datasets,
                           ClassificationSnapshotRepository snapshots, FederationRepository federations,
                           ReportRecognitionService recognitionService,
                           DatasetBuilderService datasetBuilderService,
                           PdfTextService pdfTextService,
                           StructuredLogService structuredLogs,
                           @Value("${vlugboek.storage.uploads-dir}") String uploadsDir) {
        this.documents = documents;
        this.datasets = datasets;
        this.snapshots = snapshots;
        this.federations = federations;
        this.recognitionService = recognitionService;
        this.datasetBuilderService = datasetBuilderService;
        this.pdfTextService = pdfTextService;
        this.structuredLogs = structuredLogs;
        this.uploadsDir = Path.of(uploadsDir);
    }

    @Transactional
    public DocumentRecord ingestExistingPdf(Path pdfPath) {
        String filename = pdfPath.getFileName().toString();
        return documents.findFirstByOriginalFilenameOrderByUploadedAtDesc(filename)
                .map(this::ensureMetadata)
                .orElseGet(() -> importDocument(filename, pdfPath.toAbsolutePath(), "application/pdf", sizeOf(pdfPath), true));
    }

    @Transactional
    public DocumentRecord ingestUpload(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Upload a non-empty PDF");
        }
        String original = file.getOriginalFilename() == null ? "upload.pdf" : Path.of(file.getOriginalFilename()).getFileName().toString();
        if (!original.toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("Only PDF uploads are supported");
        }
        try {
            Files.createDirectories(uploadsDir);
            String storedName = Instant.now().toEpochMilli() + "-" + UUID.randomUUID() + "-" + original;
            Path stored = uploadsDir.resolve(storedName).toAbsolutePath().normalize();
            file.transferTo(stored);
            structuredLogs.info(log, "document.upload.stored", structuredLogs.fields(
                    "filename", original,
                    "storedName", stored.getFileName().toString(),
                    "contentType", file.getContentType(),
                    "size", Files.size(stored)
            ));
            return importDocument(original, stored, file.getContentType(), Files.size(stored), false);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Transactional
    public DocumentRecord confirmImport(Long documentId) {
        DocumentRecord document = documents.findById(documentId).orElseThrow();
        ReportDataset dataset = datasets.findByDocumentId(documentId).orElseThrow();

        if (document.isAvailableToUsers()) {
            return document;
        }
        if (document.getStatus() != DocumentStatus.RECOGNISED) {
            throw new IllegalArgumentException("Only recognised documents can be confirmed");
        }

        publishDocument(document, dataset);
        structuredLogs.info(log, "document.import.confirmed", structuredLogs.fields(
                "documentId", document.getId(),
                "title", document.getTitle(),
                "family", document.getReportFamily().name(),
                "category", document.getClassificationCategory().name()
        ));
        return documents.save(document);
    }

    public Path pdfPath(Long documentId) {
        DocumentRecord document = documents.findById(documentId).orElseThrow();
        return Path.of(document.getStoredPath());
    }

    private DocumentRecord importDocument(String filename, Path path, String contentType, long size, boolean publishImmediately) {
        String pdfText = pdfTextService.extract(path);
        RecognisedReport recognised = recognitionService.recognise(filename, pdfText);
        structuredLogs.info(log, "document.recognised", structuredLogs.fields(
                "filename", filename,
                "title", recognised.title(),
                "family", recognised.family().name(),
                "category", recognised.category().name(),
                "racePoint", recognised.racePoint(),
                "publishImmediately", publishImmediately
        ));
        DocumentRecord document = new DocumentRecord(recognised.title(), filename, path.toString(), contentType, size);
        document.applyRecognition(
                recognised.family(),
                recognised.category(),
                recognised.recognisedType(),
                recognised.title(),
                recognised.officialDate(),
                recognised.liberatedAt(),
                recognised.reportCreatedAt()
        );
        document = documents.save(document);

        ReportDataset dataset = datasetBuilderService.buildDataset(document, pdfText);
        dataset = datasets.save(dataset);
        applyMetadata(document, dataset, recognised);
        structuredLogs.info(log, "document.dataset.built", structuredLogs.fields(
                "documentId", document.getId(),
                "title", document.getTitle(),
                "columns", dataset.getColumns().size(),
                "rows", dataset.getRows().size()
        ));

        if (publishImmediately) {
            publishDocument(document, dataset);
        }
        return documents.save(document);
    }

    private DocumentRecord ensureMetadata(DocumentRecord document) {
        if (document.hasMetadata()) {
            return document;
        }
        datasets.findByDocumentId(document.getId()).ifPresent(dataset ->
                applyMetadata(document, dataset, recognitionService.recognise(document.getOriginalFilename(), document.getTitle())));
        return documents.save(document);
    }

    private void applyMetadata(DocumentRecord document, ReportDataset dataset, RecognisedReport recognised) {
        Federation federation = federations.findByCode("PWDF").orElse(null);
        document.applyMetadata(
                federation,
                recognised.racePoint(),
                distinctColumnValues(dataset, "Club"),
                distinctColumnValues(dataset, "Loft Name", "Member")
        );
    }

    private Set<String> distinctColumnValues(ReportDataset dataset, String... candidateColumns) {
        Set<Integer> columnIndexes = new LinkedHashSet<>();
        for (String candidate : candidateColumns) {
            for (ReportColumn column : dataset.getColumns()) {
                if (normalise(column.getName()).equals(normalise(candidate))) {
                    columnIndexes.add(column.getPositionIndex());
                }
            }
        }

        Set<String> values = new LinkedHashSet<>();
        if (columnIndexes.isEmpty()) {
            return values;
        }

        dataset.getRows().forEach(row -> row.getCells().forEach(cell -> {
            if (columnIndexes.contains(cell.getColumnIndex()) && cell.getTextValue() != null && !cell.getTextValue().isBlank()) {
                values.add(cell.getTextValue().trim());
            }
        }));
        return values;
    }

    private String normalise(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private void publishDocument(DocumentRecord document, ReportDataset dataset) {
        document.markImported();

        if (document.getReportFamily() == ReportFamily.CLASSIFICATION
                && document.getClassificationCategory() != ClassificationCategory.NONE) {
            List<ClassificationSnapshot> previous = snapshots.findByCategoryAndLatestTrue(document.getClassificationCategory());
            previous.forEach(snapshot -> snapshot.setLatest(false));
            snapshots.saveAll(previous);
            snapshots.save(new ClassificationSnapshot(
                    document.getClassificationCategory(),
                    dataset,
                    document.getOfficialDate(),
                    true
            ));
        }
    }

    private long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ex) {
            return 0;
        }
    }
}
