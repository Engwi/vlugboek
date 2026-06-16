package za.co.vlugboek.api.dto;

public record IngestionItemDto(
        Long id,
        String status,
        String filename,
        String sourcePath,
        String archivePath,
        String contentSha256,
        Long fileSize,
        Long documentId,
        String title,
        String recognisedType,
        String reportFamily,
        Integer rowCount,
        Integer columnCount,
        String message,
        String warnings
) {
}
