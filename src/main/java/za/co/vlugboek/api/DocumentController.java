package za.co.vlugboek.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import za.co.vlugboek.api.dto.DatasetDto;
import za.co.vlugboek.api.dto.DocumentDto;
import za.co.vlugboek.api.dto.UploadResponse;
import za.co.vlugboek.domain.AppUser;
import za.co.vlugboek.domain.DocumentRecord;
import za.co.vlugboek.domain.ReportDataset;
import za.co.vlugboek.repo.DocumentRepository;
import za.co.vlugboek.repo.ReportDatasetRepository;
import za.co.vlugboek.service.DocumentService;
import za.co.vlugboek.service.EmailDeliveryException;
import za.co.vlugboek.service.EmailDeliveryResult;
import za.co.vlugboek.service.EmailDeliveryService;
import za.co.vlugboek.service.ReportAccessService;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {
    private final DocumentRepository documents;
    private final ReportDatasetRepository datasets;
    private final DocumentService documentService;
    private final EmailDeliveryService emailDeliveryService;
    private final ReportAccessService reportAccess;

    public DocumentController(DocumentRepository documents, ReportDatasetRepository datasets, DocumentService documentService,
                              EmailDeliveryService emailDeliveryService, ReportAccessService reportAccess) {
        this.documents = documents;
        this.datasets = datasets;
        this.documentService = documentService;
        this.emailDeliveryService = emailDeliveryService;
        this.reportAccess = reportAccess;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<DocumentDto> documents(Authentication authentication) {
        return documents.findAllByOrderByUploadedAtDesc().stream()
                .filter(document -> reportAccess.canManage(document, authentication))
                .map(Dtos::document)
                .toList();
    }

    @PostMapping("/upload")
    @Transactional
    public UploadResponse upload(@RequestParam("file") MultipartFile file) {
        DocumentRecord document = documentService.ingestUpload(file);
        ReportDataset dataset = datasets.findByDocumentId(document.getId()).orElseThrow();
        return new UploadResponse("PDF recognised. Review and confirm before publishing.", Dtos.document(document), Dtos.dataset(dataset));
    }

    @PostMapping("/{id}/confirm")
    @Transactional
    public UploadResponse confirm(@PathVariable Long id) {
        DocumentRecord document = documentService.confirmImport(id);
        ReportDataset dataset = datasets.findByDocumentId(document.getId()).orElseThrow();
        return new UploadResponse("PDF import confirmed and published", Dtos.document(document), Dtos.dataset(dataset));
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public DatasetDto dataset(@PathVariable Long id, Authentication authentication) {
        ReportDataset dataset = datasets.findByDocumentId(id).orElseThrow();
        requireReadable(dataset.getDocument(), authentication);
        return Dtos.dataset(dataset);
    }

    @GetMapping("/{id}/pdf")
    public ResponseEntity<Resource> pdf(@PathVariable Long id, Authentication authentication) throws IOException {
        DocumentRecord document = documents.findById(id).orElseThrow();
        requireReadable(document, authentication);
        Path path = documentService.pdfPath(id);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("PDF file is not available on disk");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(Files.size(path))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(document.getOriginalFilename(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(new FileSystemResource(path));
    }

    @GetMapping("/{id}/data.csv")
    @Transactional(readOnly = true)
    public ResponseEntity<String> csv(@PathVariable Long id, Authentication authentication) {
        ReportDataset dataset = datasets.findByDocumentId(id).orElseThrow();
        requireReadable(dataset.getDocument(), authentication);
        DatasetDto dto = Dtos.dataset(dataset);
        String csv = csvLine(dto.columns()) + "\n" + dto.rows().stream().map(this::csvLine).collect(Collectors.joining("\n"));
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeFilename(dataset.getTitle()) + ".csv\"")
                .body(csv);
    }

    @PostMapping("/{id}/email")
    public Map<String, String> email(@PathVariable Long id, Authentication authentication) {
        DocumentRecord document = documents.findById(id).orElseThrow();
        requireReadable(document, authentication);
        AppUser user = currentUser(authentication);
        Path pdfPath = documentService.pdfPath(id);

        try {
            EmailDeliveryResult result = emailDeliveryService.sendDocument(document, user, pdfPath);
            String message = "en".equalsIgnoreCase(user.getLanguage())
                    ? "PDF sent to " + user.getEmail()
                    : "PDF gestuur na " + user.getEmail();
            return Map.of(
                    "status", "sent",
                    "message", message,
                    "messageId", result.messageId(),
                    "deliveryId", String.valueOf(result.auditId()),
                    "requestId", result.requestId()
            );
        } catch (EmailDeliveryException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, ex.getMessage(), ex);
        }
    }

    private String csvLine(List<String> values) {
        return values.stream().map(value -> "\"" + value.replace("\"", "\"\"") + "\"").collect(Collectors.joining(","));
    }

    private String safeFilename(String input) {
        return input.replaceAll("[^A-Za-z0-9.-]+", "-");
    }

    private void requireReadable(DocumentRecord document, Authentication authentication) {
        if (reportAccess.canRead(document, authentication)) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document is not available");
    }

    private AppUser currentUser(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof AppUser user) {
            return user;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to email a PDF");
    }
}
