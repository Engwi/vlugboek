package za.co.vlugboek.api.dto;

import java.util.List;

public record DashboardDto(
        long documentCount,
        long raceCount,
        long leaderboardCount,
        long federationCount,
        List<DocumentDto> recentDocuments
) {
}
