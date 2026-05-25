package za.co.vlugboek.repo;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import za.co.vlugboek.domain.ReportDataset;
import za.co.vlugboek.domain.ReportFamily;

public interface ReportDatasetRepository extends JpaRepository<ReportDataset, Long> {
    Optional<ReportDataset> findByDocumentId(Long documentId);

    List<ReportDataset> findByReportFamilyOrderByOfficialDateDesc(ReportFamily reportFamily);
}
