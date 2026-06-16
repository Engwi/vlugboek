package za.co.vlugboek.api.dto;

import java.util.List;

public record IngestionWorkspaceDto(
        String rootPath,
        String inboxPath,
        String processingPath,
        String importedPath,
        String skippedPath,
        String rejectedPath,
        String reportsPath,
        List<IngestionRunDto> runs
) {
}
