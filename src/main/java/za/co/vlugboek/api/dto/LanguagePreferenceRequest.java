package za.co.vlugboek.api.dto;

import jakarta.validation.constraints.NotBlank;

public record LanguagePreferenceRequest(
        @NotBlank String language
) {
}
