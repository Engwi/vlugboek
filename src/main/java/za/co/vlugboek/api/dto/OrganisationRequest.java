package za.co.vlugboek.api.dto;

public record OrganisationRequest(
        String code,
        String name,
        Long federationId,
        Long clubId
) {
}
