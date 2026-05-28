package za.co.vlugboek.api;

import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import za.co.vlugboek.api.dto.ChangePasswordRequest;
import za.co.vlugboek.api.dto.AuthResponse;
import za.co.vlugboek.api.dto.LanguagePreferenceRequest;
import za.co.vlugboek.domain.AppUser;
import za.co.vlugboek.service.AuthService;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {
    private final AuthService authService;

    public ProfileController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/language")
    @Transactional
    public AuthResponse updateLanguage(@Valid @RequestBody LanguagePreferenceRequest request, Authentication authentication) {
        AppUser user = currentUser(authentication);
        return Dtos.auth(authService.updateLanguage(user.getId(), request.language()));
    }

    @PostMapping("/password")
    @Transactional
    public AuthResponse changePassword(@Valid @RequestBody ChangePasswordRequest request, Authentication authentication) {
        AppUser user = currentUser(authentication);
        return Dtos.auth(authService.changePassword(user.getId(), request));
    }

    private AppUser currentUser(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof AppUser user) {
            return user;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to update your profile");
    }
}
