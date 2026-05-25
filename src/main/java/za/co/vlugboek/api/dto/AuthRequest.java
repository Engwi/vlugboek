package za.co.vlugboek.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record AuthRequest(
        @Email @NotBlank String email,
        @NotBlank String password,
        String displayName,
        Long federationId,
        Long clubId,
        Long loftId,
        String language
) {
}
