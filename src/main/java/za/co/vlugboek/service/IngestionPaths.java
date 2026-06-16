package za.co.vlugboek.service;

public record IngestionPaths(
        String rootPath,
        String inboxPath,
        String processingPath,
        String importedPath,
        String skippedPath,
        String rejectedPath,
        String reportsPath
) {
}
