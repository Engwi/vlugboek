package za.co.vlugboek.api.dto;

import java.time.Instant;
import java.util.List;

public record IngestionRunDto(
        Long id,
        String status,
        String startedByEmail,
        Instant startedAt,
        Instant completedAt,
        String inboxPath,
        String reportPath,
        String reportUrl,
        int totalFiles,
        int importedCount,
        int suspectCount,
        int duplicateCount,
        int rejectedCount,
        int failedCount,
        List<IngestionItemDto> items
) {
}
