package za.co.vlugboek.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record PasswordResetConfirmRequest(
        @Email @NotBlank String email,
        @NotBlank String token,
        @NotBlank String password,
        String language
) {
}
