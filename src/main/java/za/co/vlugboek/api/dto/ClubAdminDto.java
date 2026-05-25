package za.co.vlugboek.api.dto;

import java.util.List;

public record ClubAdminDto(
        Long id,
        Long federationId,
        String name,
        long userCount,
        long documentCount,
        long loftCount,
        boolean locked,
        List<LoftAdminDto> lofts
) {
}
