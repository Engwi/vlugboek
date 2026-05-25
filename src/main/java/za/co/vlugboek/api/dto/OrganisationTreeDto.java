package za.co.vlugboek.api.dto;

import java.util.List;

public record OrganisationTreeDto(
        List<FederationAdminDto> federations
) {
}
