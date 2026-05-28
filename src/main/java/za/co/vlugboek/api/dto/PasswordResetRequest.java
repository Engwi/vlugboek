package za.co.vlugboek.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record PasswordResetRequest(
        @Email @NotBlank String email,
        String language
) {
}
