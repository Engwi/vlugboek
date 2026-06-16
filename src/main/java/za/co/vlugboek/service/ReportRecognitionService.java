package za.co.vlugboek.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import za.co.vlugboek.domain.ClassificationCategory;
import za.co.vlugboek.domain.ReportFamily;

@Service
public class ReportRecognitionService {
    private static final Pattern LIBERATED_AT = Pattern.compile(
            "(?i)liberated\\s*:?\\s*(\\d{2})/(\\d{2})/(\\d{4})\\s+(\\d{2}):(\\d{2}):(\\d{2})"
    );
    private static final Pattern REPORT_CREATED_AT = Pattern.compile(
            "(?i)date\\s*:?\\s*(\\d{2})/(\\d{2})/(\\d{4})\\s+(\\d{2}):(\\d{2}):(\\d{2})"
    );

    public RecognisedReport recognise(String filename) {
        return recognise(filename, "");
    }

    public RecognisedReport recognise(String filename, String pdfText) {
        String base = filename.replaceFirst("(?i)\\.pdf$", "");
        String filenameNormalised = normalise(base);
        String textNormalised = normalise(pdfText);
        String normalised = (textNormalised + " " + filenameNormalised).trim();
        LocalDateTime reportCreatedAt = extractDateTime(pdfText, REPORT_CREATED_AT);

        if (isGautengWestCombine(filenameNormalised, textNormalised)) {
            return combine(base, normalised, pdfText, reportCreatedAt);
        }

        if (normalised.contains("hokpunte")) {
            return classification("Hok Punte", ClassificationCategory.HOK_PUNTE, "Loft Points / Hok Punte", reportCreatedAt);
        }
        if (normalised.contains("opepunte") || normalised.contains("overall log open races")) {
            return classification("Ope Punte", ClassificationCategory.OPE_PUNTE, "Open Points", reportCreatedAt);
        }
        if (normalised.contains("ledepunte")) {
            return classification("Lede Punte", ClassificationCategory.LEDE_PUNTE, "Member Points / Lede Punte", reportCreatedAt);
        }
        if (normalised.contains("jopunte") || normalised.contains("overall log yearling races")) {
            return classification("JO Punte", ClassificationCategory.JO_PUNTE, "Year Old Points / JO Punte", reportCreatedAt);
        }
        if (normalised.contains("kort pad") || normalised.contains("short distance log all races")) {
            return distance("Short Distance Log - All Races", ClassificationCategory.SHORT_DISTANCE, reportCreatedAt);
        }
        if (normalised.contains("middel pad") || normalised.contains("middle distance log all races")) {
            return distance("Middle Distance Log - All Races", ClassificationCategory.MIDDLE_DISTANCE, reportCreatedAt);
        }
        if (normalised.contains("lang pad") || normalised.contains("long distance log all races")) {
            return distance("Long Distance Log - All Races", ClassificationCategory.LONG_DISTANCE, reportCreatedAt);
        }
        return race(toRaceTitle(base, pdfText), normalised, pdfText, "Race Detail Report");
    }

    private boolean isGautengWestCombine(String filenameNormalised, String textNormalised) {
        return textNormalised.contains("gauteng west combine") || filenameNormalised.contains("gwc");
    }

    private RecognisedReport combine(String base, String normalised, String pdfText, LocalDateTime reportCreatedAt) {
        String heading = combineHeading(pdfText);
        String headingNormalised = normalise(heading);
        if (headingNormalised.contains("birds log short distance all races")) {
            return combineLog("GWC BIRDS LOG - SHORT DISTANCE - ALL RACES",
                    ClassificationCategory.COMBINE_BIRDS_LOG_SHORT_DISTANCE_ALL_RACES,
                    "Combine Birds Log - Short Distance - All Races",
                    reportCreatedAt);
        }
        if (headingNormalised.contains("birds log short distance open races")) {
            return combineLog("GWC BIRDS LOG - SHORT DISTANCE - OPEN RACES",
                    ClassificationCategory.COMBINE_BIRDS_LOG_SHORT_DISTANCE_OPEN_RACES,
                    "Combine Birds Log - Short Distance - Open Races",
                    reportCreatedAt);
        }
        if (headingNormalised.contains("birds log short distance yearling races")) {
            return combineLog("GWC BIRDS LOG - SHORT DISTANCE - YEARLING RACES",
                    ClassificationCategory.COMBINE_BIRDS_LOG_SHORT_DISTANCE_YEARLING_RACES,
                    "Combine Birds Log - Short Distance - Yearling Races",
                    reportCreatedAt);
        }
        if (headingNormalised.contains("short distance log all races")) {
            return combineLog("GWC SHORT DISTANCE LOG - ALL RACES",
                    ClassificationCategory.COMBINE_SHORT_DISTANCE_LOG_ALL_RACES,
                    "Combine Short Distance Log - All Races",
                    reportCreatedAt);
        }
        if (headingNormalised.contains("short distance log open races")) {
            return combineLog("GWC SHORT DISTANCE LOG - OPEN RACES",
                    ClassificationCategory.COMBINE_SHORT_DISTANCE_LOG_OPEN_RACES,
                    "Combine Short Distance Log - Open Races",
                    reportCreatedAt);
        }
        if (headingNormalised.contains("short distance log yearling races")) {
            return combineLog("GWC SHORT DISTANCE LOG - YEARLING RACES",
                    ClassificationCategory.COMBINE_SHORT_DISTANCE_LOG_YEARLING_RACES,
                    "Combine Short Distance Log - Yearling Races",
                    reportCreatedAt);
        }
        if (headingNormalised.contains("overall log all races")) {
            return combineLog("GWC OVERALL LOG - ALL RACES",
                    ClassificationCategory.COMBINE_OVERALL_LOG_ALL_RACES,
                    "Combine Overall Log - All Races",
                    reportCreatedAt);
        }
        if (headingNormalised.contains("overall log open races")) {
            return combineLog("GWC OVERALL LOG - OPEN RACES",
                    ClassificationCategory.COMBINE_OVERALL_LOG_OPEN_RACES,
                    "Combine Overall Log - Open Races",
                    reportCreatedAt);
        }
        if (headingNormalised.contains("overall log yearling races")) {
            return combineLog("GWC OVERALL LOG - YEARLING RACES",
                    ClassificationCategory.COMBINE_OVERALL_LOG_YEARLING_RACES,
                    "Combine Overall Log - Yearling Races",
                    reportCreatedAt);
        }

        LocalDateTime liberatedAt = extractDateTime(pdfText, LIBERATED_AT);
        if (liberatedAt == null) {
            liberatedAt = LocalDateTime.of(raceDateFor(normalised), LocalTime.of(7, 15));
        }
        String title = combineTitle(base, pdfText);
        return new RecognisedReport(
                title,
                ReportFamily.COMBINE,
                ClassificationCategory.NONE,
                "Combine Race Result",
                racePoint(title),
                liberatedAt.toLocalDate(),
                liberatedAt,
                null
        );
    }

    private RecognisedReport combineLog(String title, ClassificationCategory category, String type, LocalDateTime reportCreatedAt) {
        LocalDate reportDate = reportCreatedAt == null ? LocalDate.of(2026, 5, 20) : reportCreatedAt.toLocalDate();
        return new RecognisedReport(
                title,
                ReportFamily.COMBINE,
                category,
                type,
                null,
                reportDate,
                null,
                reportCreatedAt == null ? LocalDateTime.of(reportDate, LocalTime.of(18, 15)) : reportCreatedAt
        );
    }

    private RecognisedReport classification(String title, ClassificationCategory category, String type, LocalDateTime reportCreatedAt) {
        LocalDate reportDate = reportCreatedAt == null ? LocalDate.of(2026, 5, 20) : reportCreatedAt.toLocalDate();
        return new RecognisedReport(
                title,
                ReportFamily.CLASSIFICATION,
                category,
                type,
                null,
                reportDate,
                null,
                reportCreatedAt == null ? LocalDateTime.of(reportDate, LocalTime.of(18, 15)) : reportCreatedAt
        );
    }

    private RecognisedReport distance(String title, ClassificationCategory category, LocalDateTime reportCreatedAt) {
        LocalDate reportDate = reportCreatedAt == null ? LocalDate.of(2026, 5, 20) : reportCreatedAt.toLocalDate();
        return new RecognisedReport(
                title,
                ReportFamily.DISTANCE_LOG,
                category,
                "Distance Log",
                null,
                reportDate,
                null,
                reportCreatedAt == null ? LocalDateTime.of(reportDate, LocalTime.of(18, 10)) : reportCreatedAt
        );
    }

    private RecognisedReport race(String title, String normalised, String pdfText, String type) {
        LocalDateTime liberatedAt = extractDateTime(pdfText, LIBERATED_AT);
        if (liberatedAt == null) {
            liberatedAt = LocalDateTime.of(raceDateFor(normalised), LocalTime.of(7, 15));
        }
        return new RecognisedReport(
                title,
                ReportFamily.RACE_DETAIL,
                ClassificationCategory.NONE,
                type,
                racePoint(title),
                liberatedAt.toLocalDate(),
                liberatedAt,
                null
        );
    }

    private LocalDate raceDateFor(String normalised) {
        if (normalised.contains("britstown")) {
            return LocalDate.of(2026, 5, 2);
        }
        if (normalised.contains("christiana")) {
            return LocalDate.of(2026, 5, 9);
        }
        if (normalised.contains("de aar")) {
            return LocalDate.of(2026, 5, 16);
        }
        if (normalised.contains("victoria west")) {
            return LocalDate.of(2026, 5, 23);
        }
        return LocalDate.of(2026, 5, 20);
    }

    private LocalDateTime extractDateTime(String pdfText, Pattern pattern) {
        if (pdfText == null || pdfText.isBlank()) {
            return null;
        }
        Matcher matcher = pattern.matcher(pdfText);
        if (!matcher.find()) {
            return null;
        }
        return LocalDateTime.of(
                Integer.parseInt(matcher.group(3)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(4)),
                Integer.parseInt(matcher.group(5)),
                Integer.parseInt(matcher.group(6))
        );
    }

    private String toRaceTitle(String base) {
        return toRaceTitle(base, "");
    }

    private String toRaceTitle(String base, String pdfText) {
        String title = firstUsefulTitleLine(pdfText);
        if (!title.isBlank()) {
            return title;
        }
        String spaced = base
                .replaceAll("([a-z])([0-9])", "$1 $2")
                .replaceAll("([0-9])([A-Za-z])", "$1 $2")
                .replaceAll("\\s+", " ")
                .trim();
        if (spaced.isBlank()) {
            return "Race Detail";
        }
        return spaced;
    }

    private String combineTitle(String base, String pdfText) {
        String[] lines = pdfText == null ? new String[0] : pdfText.split("\\R");
        for (int i = 0; i < lines.length - 1; i++) {
            if (lines[i].trim().equalsIgnoreCase("GAUTENG WEST COMBINE")) {
                return "GWC " + lines[i + 1].trim();
            }
        }
        return toRaceTitle(base, pdfText);
    }

    private String combineHeading(String pdfText) {
        String[] lines = pdfText == null ? new String[0] : pdfText.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.equalsIgnoreCase("GAUTENG WEST COMBINE")) {
                continue;
            }
            if (i + 2 < lines.length && lines[i + 1].trim().equalsIgnoreCase("BIRDS LOG")) {
                return "BIRDS LOG " + lines[i + 2].trim();
            }
            if (i + 1 < lines.length) {
                return lines[i + 1].trim();
            }
        }
        return "";
    }

    private String racePoint(String title) {
        String value = title == null ? "" : title;
        value = value.replaceFirst("(?i)^GWC\\s+", "");
        value = value.replaceAll("(?i)\\b(OPEN|OPE|JO|JONG|YEARLING)\\b.*$", "");
        value = value.replaceAll("(?i)\\b(POINTS|PUNTE)\\b.*$", "");
        value = value.trim();
        value = value.replaceAll("\\d+$", "");
        value = value.replaceAll("\\s+", " ").trim();
        return value.isBlank() ? null : value;
    }

    private String firstUsefulTitleLine(String pdfText) {
        if (pdfText == null || pdfText.isBlank()) {
            return "";
        }
        String[] lines = pdfText.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.equalsIgnoreCase("Pretoria Wedvlug Federasie") && i + 1 < lines.length) {
                return lines[i + 1].trim();
            }
        }
        return "";
    }

    private String normalise(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }
}
