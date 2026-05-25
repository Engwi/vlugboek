package za.co.vlugboek.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record DocumentDto(
        Long id,
        String title,
        String originalFilename,
        String reportFamily,
        String classificationCategory,
        String status,
        String recognisedType,
        LabelDto federation,
        String racePoint,
        List<String> clubNames,
        List<String> loftNames,
        LocalDate officialDate,
        LocalDateTime liberatedAt,
        LocalDateTime reportCreatedAt,
        long fileSize,
        boolean availableToUsers,
        Instant uploadedAt,
        String pdfUrl,
        String csvUrl
) {
}
