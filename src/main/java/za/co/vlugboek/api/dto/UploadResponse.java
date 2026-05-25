package za.co.vlugboek.api.dto;

public record UploadResponse(
        String message,
        DocumentDto document,
        DatasetDto dataset
) {
}
