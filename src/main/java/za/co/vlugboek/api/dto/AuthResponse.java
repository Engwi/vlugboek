package za.co.vlugboek.api.dto;

public record AuthResponse(
        String token,
        String email,
        String displayName,
        String role,
        String language,
        LabelDto federation,
        LabelDto club,
        LabelDto loft
) {
}
