package za.co.vlugboek.api;

import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.vlugboek.api.dto.AuthRequest;
import za.co.vlugboek.api.dto.AuthResponse;
import za.co.vlugboek.api.dto.MessageResponse;
import za.co.vlugboek.api.dto.PasswordResetConfirmRequest;
import za.co.vlugboek.api.dto.PasswordResetRequest;
import za.co.vlugboek.service.AuthService;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @Transactional
    public AuthResponse register(@Valid @RequestBody AuthRequest request) {
        return Dtos.auth(authService.register(request));
    }

    @PostMapping("/login")
    @Transactional
    public AuthResponse login(@Valid @RequestBody AuthRequest request) {
        return Dtos.auth(authService.login(request));
    }

    @PostMapping("/password-reset/request")
    @Transactional
    public MessageResponse requestPasswordReset(@Valid @RequestBody PasswordResetRequest request) {
        authService.requestPasswordReset(request);
        return new MessageResponse("If the email is registered, a reset link has been sent");
    }

    @PostMapping("/password-reset/confirm")
    @Transactional
    public AuthResponse confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmRequest request) {
        return Dtos.auth(authService.confirmPasswordReset(request));
    }
}
