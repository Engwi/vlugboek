package za.co.vlugboek.repo;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import za.co.vlugboek.domain.ClassificationCategory;
import za.co.vlugboek.domain.DocumentRecord;
import za.co.vlugboek.domain.ReportFamily;

public interface DocumentRepository extends JpaRepository<DocumentRecord, Long> {
    Optional<DocumentRecord> findFirstByOriginalFilenameOrderByUploadedAtDesc(String originalFilename);

    Optional<DocumentRecord> findFirstByContentSha256OrderByUploadedAtDesc(String contentSha256);

    List<DocumentRecord> findByContentSha256IsNullAndFileSize(long fileSize);

    List<DocumentRecord> findAllByOrderByUploadedAtDesc();

    List<DocumentRecord> findByAvailableToUsersTrueOrderByUploadedAtDesc();

    List<DocumentRecord> findByReportFamilyOrderByOfficialDateDesc(ReportFamily reportFamily);

    List<DocumentRecord> findByAvailableToUsersTrueAndReportFamilyOrderByOfficialDateDesc(ReportFamily reportFamily);

    List<DocumentRecord> findByClassificationCategoryOrderByOfficialDateDesc(ClassificationCategory category);

    long countByFederationId(Long federationId);
}
