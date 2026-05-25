package za.co.vlugboek.api.dto;

public record LoftAdminDto(
        Long id,
        Long clubId,
        String name,
        long userCount,
        long documentCount,
        boolean locked
) {
}
