package za.co.vlugboek.api;

import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.vlugboek.api.dto.AuthRequest;
import za.co.vlugboek.api.dto.AuthResponse;
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
}
