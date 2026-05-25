package za.co.vlugboek.api.dto;

import java.util.List;

public record DatasetDto(
        DocumentDto document,
        String title,
        List<String> columns,
        List<List<String>> rows
) {
}
