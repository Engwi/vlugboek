package za.co.vlugboek.api.dto;

import java.time.LocalDate;
import java.util.List;

public record LeaderboardDto(
        String category,
        String title,
        LocalDate snapshotDate,
        List<String> columns,
        List<List<String>> rows
) {
}
