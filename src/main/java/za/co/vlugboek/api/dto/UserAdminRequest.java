package za.co.vlugboek.api.dto;

public record UserAdminRequest(
        String email,
        Long federationId,
        Long clubId,
        Long loftId
) {
}
