package za.co.vlugboek.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import za.co.vlugboek.domain.ClassificationCategory;
import za.co.vlugboek.domain.ReportFamily;

public record RecognisedReport(
        String title,
        ReportFamily family,
        ClassificationCategory category,
        String recognisedType,
        String racePoint,
        LocalDate officialDate,
        LocalDateTime liberatedAt,
        LocalDateTime reportCreatedAt
) {
}
