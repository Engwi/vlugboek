package za.co.vlugboek.api;

import java.util.Comparator;
import java.util.List;
import za.co.vlugboek.api.dto.AuthResponse;
import za.co.vlugboek.api.dto.DatasetDto;
import za.co.vlugboek.api.dto.DocumentDto;
import za.co.vlugboek.api.dto.LabelDto;
import za.co.vlugboek.api.dto.LeaderboardDto;
import za.co.vlugboek.api.dto.UserAdminDto;
import za.co.vlugboek.domain.AppUser;
import za.co.vlugboek.domain.ClassificationSnapshot;
import za.co.vlugboek.domain.Club;
import za.co.vlugboek.domain.DocumentRecord;
import za.co.vlugboek.domain.Federation;
import za.co.vlugboek.domain.Loft;
import za.co.vlugboek.domain.ReportCell;
import za.co.vlugboek.domain.ReportColumn;
import za.co.vlugboek.domain.ReportDataset;
import za.co.vlugboek.domain.ReportRow;

public final class Dtos {
    private Dtos() {
    }

    public static LabelDto federation(Federation federation) {
        return federation == null ? null : new LabelDto(federation.getId(), federation.getName(), federation.getCode());
    }

    public static LabelDto club(Club club) {
        return club == null ? null : new LabelDto(club.getId(), club.getName(), null);
    }

    public static LabelDto loft(Loft loft) {
        return loft == null ? null : new LabelDto(loft.getId(), loft.getName(), null);
    }

    public static AuthResponse auth(AppUser user) {
        return new AuthResponse(
                user.getSessionToken(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole().responseName(),
                user.getLanguage(),
                federation(user.getFederation()),
                club(user.getClub()),
                loft(user.getLoft())
        );
    }

    public static UserAdminDto userAdmin(AppUser user) {
        return user == null ? null : new UserAdminDto(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole().responseName(),
                user.isRegistered(),
                federation(user.getFederation()),
                club(user.getClub()),
                loft(user.getLoft())
        );
    }

    public static DocumentDto document(DocumentRecord document) {
        return new DocumentDto(
                document.getId(),
                document.getTitle(),
                document.getOriginalFilename(),
                document.getReportFamily().name(),
                document.getClassificationCategory().name(),
                document.getStatus().name(),
                document.getRecognisedType(),
                federation(document.getFederation()),
                document.getRacePoint(),
                document.getClubNames(),
                document.getLoftNames(),
                document.getOfficialDate(),
                document.getLiberatedAt(),
                document.getReportCreatedAt(),
                document.getFileSize(),
                document.isAvailableToUsers(),
                document.getUploadedAt(),
                "/api/documents/" + document.getId() + "/pdf",
                "/api/documents/" + document.getId() + "/data.csv"
        );
    }

    public static DatasetDto dataset(ReportDataset dataset) {
        List<String> columns = dataset.getColumns().stream()
                .sorted(Comparator.comparingInt(ReportColumn::getPositionIndex))
                .map(ReportColumn::getName)
                .toList();
        List<List<String>> rows = dataset.getRows().stream()
                .sorted(Comparator.comparingInt(ReportRow::getRowIndex))
                .map(row -> row.getCells().stream()
                        .sorted(Comparator.comparingInt(ReportCell::getColumnIndex))
                        .map(ReportCell::getTextValue)
                        .toList())
                .toList();
        return new DatasetDto(document(dataset.getDocument()), dataset.getTitle(), columns, rows);
    }

    public static LeaderboardDto leaderboard(ClassificationSnapshot snapshot) {
        ReportDataset dataset = snapshot.getDataset();
        return new LeaderboardDto(
                snapshot.getCategory().name(),
                dataset.getTitle(),
                snapshot.getSnapshotDate(),
                dataset(dataset).columns(),
                dataset(dataset).rows()
        );
    }
}
