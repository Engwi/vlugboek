package za.co.vlugboek.api.dto;

import java.util.List;

public record FederationAdminDto(
        Long id,
        String code,
        String name,
        String country,
        long userCount,
        long documentCount,
        long clubCount,
        boolean locked,
        UserAdminDto federationAdmin,
        List<ClubAdminDto> clubs
) {
}
