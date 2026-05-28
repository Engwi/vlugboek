package za.co.vlugboek.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Entity
@Table(name = "documents")
public class DocumentRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String originalFilename;

    @Column(nullable = false, length = 1000)
    private String storedPath;

    private String contentType;

    private long fileSize;

    @Column(length = 64)
    private String contentSha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReportFamily reportFamily = ReportFamily.UNKNOWN;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ClassificationCategory classificationCategory = ClassificationCategory.NONE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentStatus status = DocumentStatus.UPLOADED;

    private String recognisedType;

    private LocalDate officialDate;

    private LocalDateTime liberatedAt;

    private LocalDateTime reportCreatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "federation_id")
    private Federation federation;

    private String racePoint;

    @Column(length = 10000)
    private String clubNames = "";

    @Column(length = 10000)
    private String loftNames = "";

    @Column(length = 20000)
    private String searchIndex = "";

    private boolean availableToUsers;

    @Column(nullable = false)
    private Instant uploadedAt = Instant.now();

    private Instant importedAt;

    protected DocumentRecord() {
    }

    public DocumentRecord(String title, String originalFilename, String storedPath, String contentType, long fileSize) {
        this.title = title;
        this.originalFilename = originalFilename;
        this.storedPath = storedPath;
        this.contentType = contentType;
        this.fileSize = fileSize;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getStoredPath() {
        return storedPath;
    }

    public String getContentType() {
        return contentType;
    }

    public long getFileSize() {
        return fileSize;
    }

    public String getContentSha256() {
        return contentSha256;
    }

    public void setContentSha256(String contentSha256) {
        this.contentSha256 = contentSha256;
    }

    public ReportFamily getReportFamily() {
        return reportFamily;
    }

    public ClassificationCategory getClassificationCategory() {
        return classificationCategory;
    }

    public DocumentStatus getStatus() {
        return status;
    }

    public String getRecognisedType() {
        return recognisedType;
    }

    public LocalDate getOfficialDate() {
        return officialDate;
    }

    public LocalDateTime getLiberatedAt() {
        return liberatedAt;
    }

    public LocalDateTime getReportCreatedAt() {
        return reportCreatedAt;
    }

    public Federation getFederation() {
        return federation;
    }

    public String getRacePoint() {
        return racePoint;
    }

    public List<String> getClubNames() {
        return splitNames(clubNames);
    }

    public List<String> getLoftNames() {
        return splitNames(loftNames);
    }

    public boolean isAvailableToUsers() {
        return availableToUsers;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public Instant getImportedAt() {
        return importedAt;
    }

    public void applyRecognition(ReportFamily reportFamily, ClassificationCategory category, String recognisedType,
                                 String title, LocalDate officialDate, LocalDateTime liberatedAt,
                                 LocalDateTime reportCreatedAt) {
        this.reportFamily = reportFamily;
        this.classificationCategory = category;
        this.recognisedType = recognisedType;
        this.title = title;
        this.officialDate = officialDate;
        this.liberatedAt = liberatedAt;
        this.reportCreatedAt = reportCreatedAt;
        this.status = DocumentStatus.RECOGNISED;
        this.availableToUsers = false;
    }

    public void applyMetadata(Federation federation, String racePoint, Collection<String> clubs, Collection<String> lofts) {
        this.federation = federation;
        this.racePoint = blankToNull(racePoint);
        this.clubNames = joinNames(clubs);
        this.loftNames = joinNames(lofts);
        this.searchIndex = normaliseSearchText(String.join(" ",
                Objects.toString(title, ""),
                Objects.toString(originalFilename, ""),
                Objects.toString(recognisedType, ""),
                Objects.toString(this.racePoint, ""),
                reportFamily.name(),
                classificationCategory.name(),
                this.clubNames,
                this.loftNames
        ));
    }

    public boolean hasMetadata() {
        return searchIndex != null && !searchIndex.isBlank();
    }

    public boolean matchesSearch(String query) {
        String normalised = normaliseSearchText(query);
        return normalised.isBlank() || normaliseSearchText(searchIndex).contains(normalised);
    }

    public boolean matchesClubName(String clubName) {
        return containsName(clubNames, clubName);
    }

    public boolean matchesLoftName(String loftName) {
        return containsName(loftNames, loftName);
    }

    public boolean hasClubMetadata() {
        return clubNames != null && !clubNames.isBlank();
    }

    public boolean hasLoftMetadata() {
        return loftNames != null && !loftNames.isBlank();
    }

    public void markImported() {
        this.status = DocumentStatus.IMPORTED;
        this.availableToUsers = true;
        this.importedAt = Instant.now();
    }

    public void markFailed() {
        this.status = DocumentStatus.FAILED;
        this.availableToUsers = false;
    }

    private static String joinNames(Collection<String> values) {
        if (values == null) {
            return "";
        }
        return values.stream()
                .map(DocumentRecord::blankToNull)
                .filter(Objects::nonNull)
                .distinct()
                .reduce((left, right) -> left + " | " + right)
                .orElse("");
    }

    private static List<String> splitNames(String values) {
        if (values == null || values.isBlank()) {
            return List.of();
        }
        return Arrays.stream(values.split("\\s*\\|\\s*"))
                .filter(value -> !value.isBlank())
                .toList();
    }

    private static boolean containsName(String names, String value) {
        String normalisedValue = normaliseSearchText(value);
        if (normalisedValue.isBlank() || names == null || names.isBlank()) {
            return false;
        }
        return splitNames(names).stream()
                .map(DocumentRecord::normaliseSearchText)
                .anyMatch(name -> name.equals(normalisedValue) || name.contains(normalisedValue));
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String normaliseSearchText(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }
}
