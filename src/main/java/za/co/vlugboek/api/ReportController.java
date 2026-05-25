package za.co.vlugboek.api;

import java.util.Comparator;
import java.util.List;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import za.co.vlugboek.api.dto.DashboardDto;
import za.co.vlugboek.api.dto.DatasetDto;
import za.co.vlugboek.api.dto.DocumentDto;
import za.co.vlugboek.api.dto.LeaderboardDto;
import za.co.vlugboek.domain.ClassificationCategory;
import za.co.vlugboek.domain.Club;
import za.co.vlugboek.domain.DocumentRecord;
import za.co.vlugboek.domain.Loft;
import za.co.vlugboek.domain.ReportFamily;
import za.co.vlugboek.repo.ClassificationSnapshotRepository;
import za.co.vlugboek.repo.ClubRepository;
import za.co.vlugboek.repo.DocumentRepository;
import za.co.vlugboek.repo.FederationRepository;
import za.co.vlugboek.repo.LoftRepository;
import za.co.vlugboek.repo.ReportDatasetRepository;
import za.co.vlugboek.service.ReportAccessService;

@RestController
public class ReportController {
    private final DocumentRepository documents;
    private final ReportDatasetRepository datasets;
    private final ClassificationSnapshotRepository snapshots;
    private final FederationRepository federations;
    private final ClubRepository clubs;
    private final LoftRepository lofts;
    private final ReportAccessService reportAccess;

    public ReportController(DocumentRepository documents, ReportDatasetRepository datasets,
                            ClassificationSnapshotRepository snapshots, FederationRepository federations,
                            ClubRepository clubs, LoftRepository lofts, ReportAccessService reportAccess) {
        this.documents = documents;
        this.datasets = datasets;
        this.snapshots = snapshots;
        this.federations = federations;
        this.clubs = clubs;
        this.lofts = lofts;
        this.reportAccess = reportAccess;
    }

    @GetMapping("/api/reports")
    @Transactional(readOnly = true)
    public List<DocumentDto> reports(@RequestParam(required = false) String query,
                                     @RequestParam(required = false) ReportFamily family,
                                     @RequestParam(required = false) ClassificationCategory category,
                                     @RequestParam(required = false) LocalDate dateFrom,
                                     @RequestParam(required = false) LocalDate dateTo,
                                     @RequestParam(required = false) Long federationId,
                                     @RequestParam(required = false) Long clubId,
                                     @RequestParam(required = false) Long loftId,
                                     @RequestParam(required = false) String racePoint,
                                     Authentication authentication) {
        String clubName = clubId == null ? null : clubs.findById(clubId).map(Club::getName).orElse(null);
        String loftName = loftId == null ? null : lofts.findById(loftId).map(Loft::getName).orElse(null);
        return documents.findByAvailableToUsersTrueOrderByUploadedAtDesc().stream()
                .filter(document -> reportAccess.canRead(document, authentication))
                .filter(document -> family == null || document.getReportFamily() == family)
                .filter(document -> category == null || document.getClassificationCategory() == category)
                .filter(document -> dateFrom == null || document.getOfficialDate() == null || !document.getOfficialDate().isBefore(dateFrom))
                .filter(document -> dateTo == null || document.getOfficialDate() == null || !document.getOfficialDate().isAfter(dateTo))
                .filter(document -> federationId == null || (document.getFederation() != null && document.getFederation().getId().equals(federationId)))
                .filter(document -> clubName == null || document.matchesClubName(clubName))
                .filter(document -> loftName == null || document.matchesLoftName(loftName))
                .filter(document -> racePoint == null || racePoint.isBlank()
                        || (document.getRacePoint() != null && document.getRacePoint().equalsIgnoreCase(racePoint.trim())))
                .filter(document -> document.matchesSearch(query))
                .map(Dtos::document)
                .toList();
    }

    @GetMapping("/api/reports/{id}")
    @Transactional(readOnly = true)
    public DatasetDto report(@PathVariable Long id, Authentication authentication) {
        var dataset = datasets.findByDocumentId(id).orElseThrow();
        if (!reportAccess.canRead(dataset.getDocument(), authentication)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Report is not available");
        }
        return Dtos.dataset(dataset);
    }

    @GetMapping("/api/leaderboards")
    @Transactional(readOnly = true)
    public List<LeaderboardDto> leaderboards(Authentication authentication) {
        return snapshots.findByLatestTrueOrderByCategoryAsc().stream()
                .filter(snapshot -> reportAccess.canRead(snapshot.getDataset().getDocument(), authentication))
                .map(Dtos::leaderboard)
                .toList();
    }

    @GetMapping("/api/races")
    @Transactional(readOnly = true)
    public List<DocumentDto> races(Authentication authentication) {
        return documents.findByAvailableToUsersTrueAndReportFamilyOrderByOfficialDateDesc(ReportFamily.RACE_DETAIL).stream()
                .filter(document -> reportAccess.canRead(document, authentication))
                .map(Dtos::document)
                .toList();
    }

    @GetMapping("/api/dashboard")
    @Transactional(readOnly = true)
    public DashboardDto dashboard(Authentication authentication) {
        List<DocumentRecord> all = documents.findByAvailableToUsersTrueOrderByUploadedAtDesc().stream()
                .filter(document -> reportAccess.canRead(document, authentication))
                .toList();
        long raceCount = all.stream().filter(document -> document.getReportFamily() == ReportFamily.RACE_DETAIL).count();
        long leaderboardCount = all.stream().filter(document -> document.getReportFamily() == ReportFamily.CLASSIFICATION).count();
        List<DocumentDto> recent = all.stream()
                .sorted(Comparator.comparing(DocumentRecord::getUploadedAt).reversed())
                .limit(5)
                .map(Dtos::document)
                .toList();
        return new DashboardDto(all.size(), raceCount, leaderboardCount, federations.count(), recent);
    }
}
