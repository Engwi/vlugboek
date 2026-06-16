package za.co.vlugboek.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import za.co.vlugboek.domain.ClassificationCategory;
import za.co.vlugboek.domain.DocumentRecord;
import za.co.vlugboek.domain.ReportDataset;
import za.co.vlugboek.domain.ReportFamily;

@Service
public class DatasetBuilderService {
    private static final Pattern INTEGER = Pattern.compile("-?\\d+");
    private static final Pattern TWO_DIGIT_YEAR = Pattern.compile("\\d{2}");
    private static final Pattern DECIMAL = Pattern.compile("-?\\d+\\.\\d+");
    private static final Pattern DISTANCE = Pattern.compile("-?\\d+(?:\\.\\d+)?");
    private static final Pattern CLOCK = Pattern.compile("\\d{1,2}:\\d{2}:\\d{2}");
    private static final Pattern COMBINE_ROW = Pattern.compile("^(\\d+)\\s*(.+)$");
    private static final Set<String> LEGACY_CLUB_CODES = Set.of(
            "ACPK", "CPK", "HHH", "KPU", "KRPS", "NDK", "PWF", "WDK", "ZWDK"
    );

    private enum RaceLayout {
        LEGACY,
        OFFICIAL_FEDERATION,
        OFFICIAL_CLUB
    }

    public ReportDataset buildDataset(DocumentRecord document, String pdfText) {
        ReportDataset dataset = new ReportDataset(
                document,
                document.getTitle(),
                document.getReportFamily(),
                document.getOfficialDate()
        );

        List<String> lines = usefulLines(pdfText);
        if (lines.isEmpty()) {
            addRawLines(dataset, List.of("No extractable PDF text found"));
        } else if (isCombineRaceResult(lines, document)) {
            addCombineRows(dataset, lines);
        } else if (document.getReportFamily() == ReportFamily.COMBINE
                && document.getClassificationCategory().name().startsWith("COMBINE_BIRDS_LOG")) {
            addCombineBirdRows(dataset, lines);
        } else if (document.getReportFamily() == ReportFamily.COMBINE
                && document.getClassificationCategory().name().startsWith("COMBINE_OVERALL_LOG")) {
            addOverallPointsRows(dataset, lines);
        } else if (document.getReportFamily() == ReportFamily.COMBINE) {
            addDistancePointsRows(dataset, lines);
        } else if (document.getReportFamily() == ReportFamily.RACE_DETAIL) {
            addPretoriaRaceRows(dataset, lines);
        } else if (document.getReportFamily() == ReportFamily.CLASSIFICATION) {
            addOverallPointsRows(dataset, lines);
        } else if (document.getReportFamily() == ReportFamily.DISTANCE_LOG) {
            addDistancePointsRows(dataset, lines);
        } else {
            addRawLines(dataset, lines);
        }

        if (dataset.getRows().isEmpty()) {
            dataset.getColumns().clear();
            addRawLines(dataset, lines);
        }
        return dataset;
    }

    public ReportDataset buildDataset(DocumentRecord document) {
        return buildDataset(document, "");
    }

    private void addOverallPointsRows(ReportDataset dataset, List<String> lines) {
        addColumns(dataset, List.of("Pos", "Member", "Short", "Middle", "Long", "Total"));
        int scoreCount = pointScoreCount(lines, 3);
        if (addPointRowsFromLines(dataset, lines, scoreCount)) {
            return;
        }

        int index = dataStart(lines);
        int rowIndex = 0;
        while (index < lines.size()) {
            String member = lines.get(index++);
            List<String> values = takeNumberRun(lines, index);
            index += values.size();
            if (values.size() < 5) {
                continue;
            }
            dataset.addRow(List.of(
                    values.get(3),
                    member,
                    values.get(0),
                    values.get(1),
                    values.get(2),
                    values.get(4)
            ), rowIndex++);
        }
    }

    private void addDistancePointsRows(ReportDataset dataset, List<String> lines) {
        int lineScoreCount = pointScoreCount(lines, 0);
        if (lineScoreCount > 0) {
            List<String> columns = new ArrayList<>();
            columns.add("Pos");
            columns.add("Member");
            for (int i = 1; i <= lineScoreCount; i++) {
                columns.add("Race " + i);
            }
            columns.add("Total");
            addColumns(dataset, columns);
            if (addPointRowsFromLines(dataset, lines, lineScoreCount)) {
                return;
            }
            dataset.getColumns().clear();
        }

        int index = dataStart(lines);
        List<List<String>> parsedRows = new ArrayList<>();
        int widestScoreCount = 0;
        int rowNumber = 1;

        while (index < lines.size()) {
            String member = lines.get(index++);
            List<String> values = takeNumberRun(lines, index);
            index += values.size();
            if (values.size() < 4) {
                continue;
            }

            int positionIndex = positionIndex(values, rowNumber);
            if (positionIndex <= 0) {
                continue;
            }

            String total = values.get(positionIndex - 1);
            List<String> scores = new ArrayList<>();
            scores.addAll(values.subList(0, positionIndex - 1));
            if (positionIndex + 1 < values.size()) {
                scores.addAll(values.subList(positionIndex + 1, values.size()));
            }
            widestScoreCount = Math.max(widestScoreCount, scores.size());

            List<String> row = new ArrayList<>();
            row.add(values.get(positionIndex));
            row.add(member);
            row.addAll(scores);
            row.add(total);
            parsedRows.add(row);
            rowNumber++;
        }

        List<String> columns = new ArrayList<>();
        columns.add("Pos");
        columns.add("Member");
        for (int i = 1; i <= widestScoreCount; i++) {
            columns.add("Race " + i);
        }
        columns.add("Total");
        addColumns(dataset, columns);

        for (int i = 0; i < parsedRows.size(); i++) {
            List<String> row = new ArrayList<>(parsedRows.get(i));
            while (row.size() < columns.size()) {
                row.add(row.size() - 1, "");
            }
            dataset.addRow(row, i);
        }
    }

    private void addPretoriaRaceRows(ReportDataset dataset, List<String> lines) {
        RaceLayout layout = raceLayout(lines);
        if (layout == RaceLayout.OFFICIAL_FEDERATION) {
            addOfficialFederationRaceRows(dataset, lines);
            return;
        }
        if (layout == RaceLayout.OFFICIAL_CLUB) {
            addOfficialClubRaceRows(dataset, lines);
            return;
        }

        addColumns(dataset, List.of(
                "Pos",
                "Loft Name",
                "Bird No",
                "Ring Id",
                "Year",
                "Colour",
                "Sex",
                "Club",
                "Velocity",
                "Var",
                "Clock Time",
                "Distance Km",
                "ToWin"
        ));

        boolean hasClubColumn = hasLegacyClubColumn(lines);
        int lineRowIndex = 0;
        for (String line : lines) {
            List<String> row = parseRaceLine(line, hasClubColumn);
            if (!row.isEmpty()) {
                dataset.addRow(row, lineRowIndex++);
            }
        }
        if (lineRowIndex > 0) {
            return;
        }

        int index = raceDataStart(lines);
        int rowIndex = 0;
        while (index + 10 < lines.size()) {
            if (!isRaceRowStart(lines, index)) {
                index++;
                continue;
            }

            String loftName = lines.get(index++);
            String birdNo = lines.get(index++);
            String ringId = lines.get(index++);
            String year = lines.get(index++);
            String colour = lines.get(index++);
            String sex = isSex(lines.get(index)) ? lines.get(index++) : "";
            String club = lines.get(index++);
            String velocity = lines.get(index++);
            String var = lines.get(index++);
            String clockTime = lines.get(index++);
            String distance = lines.get(index++);
            String position = lines.get(index++);
            String toWin = "";
            if (index < lines.size() && CLOCK.matcher(lines.get(index)).matches() && !isRaceRowStart(lines, index)) {
                toWin = lines.get(index++);
            }

            if (!DECIMAL.matcher(velocity).matches() || !CLOCK.matcher(clockTime).matches() || !INTEGER.matcher(position).matches()) {
                continue;
            }

            dataset.addRow(List.of(
                    position,
                    loftName,
                    birdNo,
                    ringId,
                    year,
                    colour,
                    sex,
                    club,
                    velocity,
                    var,
                    clockTime,
                    distance,
                    toWin
            ), rowIndex++);
        }
    }

    private void addOfficialFederationRaceRows(ReportDataset dataset, List<String> lines) {
        addColumns(dataset, List.of(
                "Pos",
                "Loft Name",
                "Ring Id",
                "Year",
                "Bird No",
                "Colour",
                "Sex",
                "Clock Time",
                "Var",
                "Distance",
                "Velocity",
                "Pools"
        ));

        int rowIndex = 0;
        for (String line : lines) {
            List<String> row = parseOfficialFederationRaceLine(line);
            if (!row.isEmpty()) {
                dataset.addRow(row, rowIndex++);
            }
        }
    }

    private void addOfficialClubRaceRows(ReportDataset dataset, List<String> lines) {
        addColumns(dataset, List.of(
                "Pos",
                "Loft Name",
                "Club",
                "Ring Id",
                "Year",
                "Bird No",
                "Colour",
                "Sex",
                "Bd#",
                "Clock Time",
                "Var",
                "Coeff",
                "Velocity"
        ));

        int rowIndex = 0;
        for (String line : lines) {
            List<String> row = parseOfficialClubRaceLine(line);
            if (!row.isEmpty()) {
                dataset.addRow(row, rowIndex++);
            }
        }
    }

    private void addCombineRows(ReportDataset dataset, List<String> lines) {
        addColumns(dataset, List.of(
                "Pos",
                "Loft Name",
                "Club",
                "Ring Id",
                "Year",
                "Bird No",
                "Colour",
                "Sex",
                "Clock Time",
                "Var",
                "Distance Km",
                "Velocity"
        ));
        int rowIndex = 0;
        for (String line : lines) {
            List<String> detailedRow = parseCombineLine(line);
            if (!detailedRow.isEmpty()) {
                dataset.addRow(detailedRow, rowIndex++);
                continue;
            }

            Matcher matcher = COMBINE_ROW.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String position = matcher.group(1);
            String rest = matcher.group(2).trim();
            Matcher clockMatcher = CLOCK.matcher(rest);
            if (!clockMatcher.find()) {
                continue;
            }

            String entry = rest.substring(0, clockMatcher.start()).trim();
            String clock = clockMatcher.group();
            String afterClock = rest.substring(clockMatcher.end()).trim();
            String[] pieces = afterClock.split("\\s+", 2);
            if (pieces.length < 2 || !INTEGER.matcher(pieces[0]).matches()) {
                continue;
            }

            String var = pieces[0];
            String[] distanceVelocity = splitDistanceVelocity(pieces[1]);
            if (distanceVelocity.length != 2) {
                continue;
            }

            dataset.addRow(List.of(position, entry, "", "", "", "", "", "", clock, var, distanceVelocity[0], distanceVelocity[1]), rowIndex++);
        }
    }

    private boolean addPointRowsFromLines(ReportDataset dataset, List<String> lines, int scoreCount) {
        int rowIndex = 0;
        for (String line : lines) {
            List<String> row = parsePointLine(line, scoreCount);
            if (!row.isEmpty()) {
                dataset.addRow(row, rowIndex++);
            }
        }
        return rowIndex > 0;
    }

    private void addCombineBirdRows(ReportDataset dataset, List<String> lines) {
        List<List<String>> rows = lines.stream()
                .map(line -> parsePointLine(line, 0))
                .filter(row -> !row.isEmpty())
                .toList();
        if (rows.isEmpty()) {
            return;
        }

        int scoreCount = rows.stream()
                .mapToInt(row -> Math.max(0, row.size() - 3))
                .max()
                .orElse(0);

        List<String> columns = new ArrayList<>();
        columns.add("Pos");
        columns.add("Member");
        for (int i = 1; i <= scoreCount; i++) {
            columns.add("Race " + i);
        }
        columns.add("Total");
        addColumns(dataset, columns);

        int rowIndex = 0;
        for (List<String> row : rows) {
            List<String> normalisedRow = new ArrayList<>(row);
            while (normalisedRow.size() < scoreCount + 3) {
                normalisedRow.add(normalisedRow.size() - 1, "");
            }
            dataset.addRow(normalisedRow, rowIndex++);
        }
    }

    private List<String> parsePointLine(String line, int scoreCount) {
        String[] tokens = line.split("\\s+");
        if (tokens.length < 4 || !INTEGER.matcher(tokens[0]).matches()) {
            return List.of();
        }

        int trailingStart = tokens.length;
        while (trailingStart > 1 && INTEGER.matcher(tokens[trailingStart - 1]).matches()) {
            trailingStart--;
        }
        int trailingCount = tokens.length - trailingStart;
        if (trailingCount < 2) {
            return List.of();
        }

        int valueCount = scoreCount <= 0 ? trailingCount : Math.min(scoreCount + 1, trailingCount);
        int valuesStart = tokens.length - valueCount;
        if (valuesStart <= 1) {
            return List.of();
        }

        String member = join(tokens, 1, valuesStart);
        List<String> values = new ArrayList<>();
        for (int i = valuesStart; i < tokens.length; i++) {
            values.add(tokens[i]);
        }

        String total = values.remove(values.size() - 1);
        while (values.size() < scoreCount) {
            values.add("");
        }

        List<String> row = new ArrayList<>();
        row.add(tokens[0]);
        row.add(member);
        row.addAll(values);
        row.add(total);
        return row;
    }

    private List<String> parseRaceLine(String line, boolean hasClubColumn) {
        List<String> row = parseBirdRaceLine(line, true, hasClubColumn);
        if (row.isEmpty()) {
            return row;
        }
        return row;
    }

    private List<String> parseCombineLine(String line) {
        return parseBirdRaceLine(line, false, true);
    }

    private List<String> parseOfficialFederationRaceLine(String line) {
        String[] tokens = line.split("\\s+");
        if (tokens.length < 10 || !INTEGER.matcher(tokens[0]).matches()) {
            return List.of();
        }

        int cursor = tokens.length - 1;
        String pools = "";
        while (cursor >= 0 && !DECIMAL.matcher(tokens[cursor]).matches()) {
            pools = tokens[cursor] + (pools.isBlank() ? "" : " " + pools);
            cursor--;
        }
        if (cursor < 0) {
            return List.of();
        }
        String velocity = tokens[cursor--];

        if (cursor < 0 || !DISTANCE.matcher(tokens[cursor]).matches()) {
            return List.of();
        }
        String distance = tokens[cursor--];

        if (cursor < 0 || !INTEGER.matcher(tokens[cursor]).matches()) {
            return List.of();
        }
        String var = tokens[cursor--];

        if (cursor < 0 || !CLOCK.matcher(tokens[cursor]).matches()) {
            return List.of();
        }
        String clockTime = tokens[cursor--];

        int yearIndex = yearIndex(tokens, 1, cursor);
        if (yearIndex < 2 || yearIndex + 1 > cursor) {
            return List.of();
        }

        String loftName = join(tokens, 1, yearIndex - 1);
        String ringId = tokens[yearIndex - 1];
        String year = tokens[yearIndex];
        String birdNo = tokens[yearIndex + 1];
        String colour = join(tokens, yearIndex + 2, cursor + 1);
        String sex = "";
        if (yearIndex + 2 <= cursor && isSex(tokens[cursor])) {
            sex = tokens[cursor];
            colour = join(tokens, yearIndex + 2, cursor);
        }

        if (loftName.isBlank()) {
            return List.of();
        }

        return List.of(tokens[0], loftName, ringId, year, birdNo, colour, sex, clockTime, var, distance, velocity, pools);
    }

    private List<String> parseOfficialClubRaceLine(String line) {
        String[] tokens = line.split("\\s+");
        if (tokens.length < 11 || !INTEGER.matcher(tokens[0]).matches()) {
            return List.of();
        }

        int cursor = tokens.length - 1;
        if (!DECIMAL.matcher(tokens[cursor]).matches()) {
            return List.of();
        }
        String velocity = tokens[cursor--];

        String coefficient = "";
        if (cursor >= 0 && DECIMAL.matcher(tokens[cursor]).matches()) {
            coefficient = tokens[cursor--];
        }

        if (cursor < 0 || !INTEGER.matcher(tokens[cursor]).matches()) {
            return List.of();
        }
        String var = tokens[cursor--];

        if (cursor < 0 || !CLOCK.matcher(tokens[cursor]).matches()) {
            return List.of();
        }
        String clockTime = tokens[cursor--];

        if (cursor < 0 || !INTEGER.matcher(tokens[cursor]).matches()) {
            return List.of();
        }
        String birdCounter = tokens[cursor--];

        int yearIndex = yearIndex(tokens, 1, cursor);
        if (yearIndex < 3 || yearIndex + 1 > cursor) {
            return List.of();
        }

        String loftName = join(tokens, 1, yearIndex - 2);
        String club = tokens[yearIndex - 2];
        String ringId = tokens[yearIndex - 1];
        String year = tokens[yearIndex];
        String birdNo = tokens[yearIndex + 1];
        String colour = join(tokens, yearIndex + 2, cursor + 1);
        String sex = "";
        if (yearIndex + 2 <= cursor && isSex(tokens[cursor])) {
            sex = tokens[cursor];
            colour = join(tokens, yearIndex + 2, cursor);
        }

        if (loftName.isBlank()) {
            return List.of();
        }

        return List.of(tokens[0], loftName, club, ringId, year, birdNo, colour, sex, birdCounter, clockTime, var, coefficient, velocity);
    }

    private List<String> parseBirdRaceLine(String line, boolean includeToWin, boolean hasClubColumn) {
        String[] tokens = line.split("\\s+");
        if (tokens.length < 10 || !INTEGER.matcher(tokens[0]).matches()) {
            return List.of();
        }

        int cursor = tokens.length - 1;
        if (!DECIMAL.matcher(tokens[cursor]).matches()) {
            return List.of();
        }
        String velocity = tokens[cursor--];

        String toWin = "";
        if (includeToWin && cursor >= 0 && CLOCK.matcher(tokens[cursor]).matches()) {
            toWin = tokens[cursor--];
        }

        if (cursor < 0 || !DISTANCE.matcher(tokens[cursor]).matches()) {
            return List.of();
        }
        String distance = tokens[cursor--];

        if (cursor < 0 || !INTEGER.matcher(tokens[cursor]).matches()) {
            return List.of();
        }
        String var = tokens[cursor--];

        if (cursor < 0 || !CLOCK.matcher(tokens[cursor]).matches()) {
            return List.of();
        }
        String clockTime = tokens[cursor--];

        int yearIndex = yearIndex(tokens, 1, cursor);
        if (yearIndex < 3 || yearIndex + 1 > cursor) {
            return List.of();
        }

        String loftName;
        String club = "";
        if (yearIndex > 3 && isLegacyClubCode(tokens[yearIndex - 2])) {
            loftName = join(tokens, 1, yearIndex - 2);
            club = tokens[yearIndex - 2];
        } else {
            loftName = join(tokens, 1, yearIndex - 1);
        }
        String ringId = tokens[yearIndex - 1];
        String year = tokens[yearIndex];
        String birdNo = tokens[yearIndex + 1];
        String colour = join(tokens, yearIndex + 2, cursor + 1);
        String sex = "";
        if (yearIndex + 2 <= cursor && isSex(tokens[cursor])) {
            sex = tokens[cursor];
            colour = join(tokens, yearIndex + 2, cursor);
        }

        if (loftName.isBlank()) {
            return List.of();
        }

        if (includeToWin) {
            return List.of(tokens[0], loftName, birdNo, ringId, year, colour, sex, club, velocity, var, clockTime, distance, toWin);
        }
        return List.of(tokens[0], loftName, club, ringId, year, birdNo, colour, sex, clockTime, var, distance, velocity);
    }

    private String[] splitDistanceVelocity(String value) {
        String trimmed = value.trim();
        String[] spaced = trimmed.split("\\s+");
        if (spaced.length >= 2 && DECIMAL.matcher(spaced[0]).matches() && DECIMAL.matcher(spaced[1]).matches()) {
            return new String[] {spaced[0], spaced[1]};
        }

        Matcher matcher = Pattern.compile("^([0-9]+\\.[0-9]+)([0-9]{3,4}\\.[0-9]+)$").matcher(trimmed);
        if (matcher.matches()) {
            return new String[] {matcher.group(1), matcher.group(2)};
        }
        return new String[0];
    }

    private void addRawLines(ReportDataset dataset, List<String> lines) {
        addColumns(dataset, List.of("Line", "Text"));
        for (int i = 0; i < lines.size(); i++) {
            dataset.addRow(List.of(String.valueOf(i + 1), lines.get(i)), i);
        }
    }

    private List<String> takeNumberRun(List<String> lines, int index) {
        List<String> values = new ArrayList<>();
        while (index < lines.size() && INTEGER.matcher(lines.get(index)).matches()) {
            values.add(lines.get(index));
            index++;
        }
        return values;
    }

    private int positionIndex(List<String> values, int expectedPosition) {
        String expected = String.valueOf(expectedPosition);
        for (int i = 1; i < values.size() - 1; i++) {
            if (values.get(i).equals(expected)) {
                return i;
            }
        }
        return -1;
    }

    private int dataStart(List<String> lines) {
        for (int i = 0; i < lines.size() - 1; i++) {
            if (!INTEGER.matcher(lines.get(i)).matches() && INTEGER.matcher(lines.get(i + 1)).matches()) {
                return i;
            }
        }
        return lines.size();
    }

    private int raceDataStart(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if ("ToWin".equalsIgnoreCase(lines.get(i))) {
                return i + 1;
            }
        }
        return dataStart(lines);
    }

    private boolean isRaceRowStart(List<String> lines, int index) {
        return index + 4 < lines.size()
                && !INTEGER.matcher(lines.get(index)).matches()
                && INTEGER.matcher(lines.get(index + 1)).matches()
                && lines.get(index + 2).matches("[A-Za-z0-9]+")
                && INTEGER.matcher(lines.get(index + 3)).matches();
    }

    private boolean isSex(String value) {
        String normalised = value.toUpperCase(Locale.ROOT);
        return normalised.equals("C") || normalised.equals("H");
    }

    private boolean isLegacyClubCode(String value) {
        return LEGACY_CLUB_CODES.contains(value.toUpperCase(Locale.ROOT));
    }

    private boolean isCombineRaceResult(List<String> lines, DocumentRecord document) {
        return document.getRecognisedType() != null
                && document.getRecognisedType().toLowerCase(Locale.ROOT).contains("combine race result");
    }

    private boolean hasLegacyClubColumn(List<String> lines) {
        for (String line : lines) {
            String normalised = line.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
            if (normalised.contains("loft name bird particulars clock time")) {
                return false;
            }
            if (normalised.contains("loft name bird particulars club velocity")) {
                return true;
            }
            if (normalised.contains("loft name bird particulars velocity")) {
                return false;
            }

            int birdParticulars = normalised.indexOf("bird particulars");
            if (birdParticulars >= 0) {
                int club = normalised.indexOf("club", birdParticulars);
                int clubSp = normalised.indexOf("club sp", birdParticulars);
                int velocity = normalised.indexOf("velocity", birdParticulars);
                int clockTime = normalised.indexOf("clock time", birdParticulars);
                int firstRaceValue = firstPositive(velocity, clockTime);
                if (club >= 0 && club != clubSp && (firstRaceValue < 0 || club < firstRaceValue)) {
                    return true;
                }
                if (firstRaceValue >= 0) {
                    return false;
                }
            }
        }

        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).equalsIgnoreCase("Bird Particulars")) {
                continue;
            }
            for (int j = i + 1; j < lines.size(); j++) {
                String normalised = lines.get(j).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
                if (normalised.equals("club")) {
                    return true;
                }
                if (normalised.equals("velocity")) {
                    return false;
                }
            }
        }

        return true;
    }

    private int firstPositive(int first, int second) {
        if (first < 0) {
            return second;
        }
        if (second < 0) {
            return first;
        }
        return Math.min(first, second);
    }

    private RaceLayout raceLayout(List<String> lines) {
        for (String line : lines) {
            String normalised = line.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
            if (normalised.contains("pos loft name club bird particulars bd# clock time var")) {
                return RaceLayout.OFFICIAL_CLUB;
            }
            if (normalised.contains("pos loft name bird particulars clock time var distance velocity")) {
                return RaceLayout.OFFICIAL_FEDERATION;
            }
        }
        return RaceLayout.LEGACY;
    }

    private int pointScoreCount(List<String> lines, int fallback) {
        for (String line : lines) {
            String[] tokens = line.split("\\s+");
            int memberIndex = -1;
            int totalIndex = -1;
            for (int i = 0; i < tokens.length; i++) {
                String token = tokens[i].replace(".", "");
                if (token.equalsIgnoreCase("Member") || token.equalsIgnoreCase("MemName")) {
                    memberIndex = i;
                } else if (token.equalsIgnoreCase("Total")) {
                    totalIndex = i;
                    break;
                }
            }
            if (memberIndex >= 0 && totalIndex > memberIndex) {
                return totalIndex - memberIndex - 1;
            }
        }
        return fallback;
    }

    private int yearIndex(String[] tokens, int startInclusive, int endInclusive) {
        for (int i = startInclusive; i <= endInclusive - 1; i++) {
            if (TWO_DIGIT_YEAR.matcher(tokens[i]).matches() && INTEGER.matcher(tokens[i + 1]).matches()) {
                return i;
            }
        }
        return -1;
    }

    private String join(String[] tokens, int startInclusive, int endExclusive) {
        if (startInclusive >= endExclusive) {
            return "";
        }
        return String.join(" ", List.of(tokens).subList(startInclusive, endExclusive));
    }

    private List<String> usefulLines(String pdfText) {
        if (pdfText == null || pdfText.isBlank()) {
            return List.of();
        }
        return pdfText.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .filter(line -> !line.matches("(?i)page\\s+\\d+.*"))
                .toList();
    }

    private void addColumns(ReportDataset dataset, List<String> columns) {
        for (int i = 0; i < columns.size(); i++) {
            dataset.addColumn(columns.get(i), i);
        }
    }
}
