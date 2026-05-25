package za.co.vlugboek.api.dto;

public record UserAdminDto(
        Long id,
        String email,
        String displayName,
        String role,
        boolean registered,
        LabelDto federation,
        LabelDto club,
        LabelDto loft
) {
}
