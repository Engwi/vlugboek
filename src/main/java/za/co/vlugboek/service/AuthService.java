package za.co.vlugboek.service;

import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import za.co.vlugboek.api.dto.AuthRequest;
import za.co.vlugboek.api.dto.ChangePasswordRequest;
import za.co.vlugboek.api.dto.PasswordResetConfirmRequest;
import za.co.vlugboek.api.dto.PasswordResetRequest;
import za.co.vlugboek.domain.AppUser;
import za.co.vlugboek.domain.Club;
import za.co.vlugboek.domain.Federation;
import za.co.vlugboek.domain.Loft;
import za.co.vlugboek.domain.UserRole;
import za.co.vlugboek.repo.AppUserRepository;
import za.co.vlugboek.repo.ClubRepository;
import za.co.vlugboek.repo.FederationRepository;
import za.co.vlugboek.repo.LoftRepository;

@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final Duration PASSWORD_RESET_TTL = Duration.ofMinutes(30);

    private final AppUserRepository users;
    private final FederationRepository federations;
    private final ClubRepository clubs;
    private final LoftRepository lofts;
    private final PasswordService passwordService;
    private final PasswordResetEmailService passwordResetEmailService;
    private final StructuredLogService structuredLogs;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(AppUserRepository users, FederationRepository federations, ClubRepository clubs,
                       LoftRepository lofts, PasswordService passwordService,
                       PasswordResetEmailService passwordResetEmailService, StructuredLogService structuredLogs) {
        this.users = users;
        this.federations = federations;
        this.clubs = clubs;
        this.lofts = lofts;
        this.passwordService = passwordService;
        this.passwordResetEmailService = passwordResetEmailService;
        this.structuredLogs = structuredLogs;
    }

    @Transactional
    public AppUser register(AuthRequest request) {
        String email = request.email().trim().toLowerCase();
        AppUser user = users.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new IllegalArgumentException("Your email address has not been loaded by your federation admin"));
        if (user.isRegistered()) {
            throw new IllegalArgumentException("Email address is already registered");
        }
        validatePendingRegistration(user, request);

        String name = request.displayName() == null || request.displayName().isBlank()
                ? email.substring(0, email.indexOf("@"))
                : request.displayName().trim();
        String language = normaliseLanguage(request.language());

        user.completeRegistration(name, passwordService.hash(email, request.password()), language);
        user.setSessionToken(UUID.randomUUID().toString());
        structuredLogs.info(log, "auth.registered", structuredLogs.fields(
                "userId", user.getId(),
                "email", user.getEmail(),
                "role", user.getRole().responseName(),
                "federation", user.getFederation() == null ? null : user.getFederation().getCode(),
                "clubId", user.getClub() == null ? null : user.getClub().getId(),
                "loftId", user.getLoft() == null ? null : user.getLoft().getId()
        ));
        return user;
    }

    @Transactional
    public AppUser login(AuthRequest request) {
        String email = request.email().trim().toLowerCase();
        AppUser user = users.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new IllegalArgumentException("Unknown email or password"));
        if (!user.isRegistered()) {
            throw new IllegalArgumentException("Please register before signing in");
        }
        if (!passwordService.matches(email, request.password(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Unknown email or password");
        }
        if (passwordService.needsRehash(user.getPasswordHash())) {
            user.setPasswordHash(passwordService.hash(email, request.password()));
        }
        user.setSessionToken(UUID.randomUUID().toString());
        if (request.language() != null && !request.language().isBlank()) {
            user.setLanguage(normaliseLanguage(request.language()));
        }
        structuredLogs.info(log, "auth.login", structuredLogs.fields(
                "userId", user.getId(),
                "email", user.getEmail(),
                "role", user.getRole().responseName(),
                "federation", user.getFederation() == null ? null : user.getFederation().getCode()
        ));
        return user;
    }

    @Transactional
    public AppUser updateLanguage(Long userId, String language) {
        AppUser user = users.findById(userId).orElseThrow();
        user.setLanguage(normaliseLanguage(language));
        return user;
    }

    @Transactional
    public AppUser changePassword(Long userId, ChangePasswordRequest request) {
        AppUser user = users.findById(userId).orElseThrow();
        if (!user.isRegistered()) {
            throw new IllegalArgumentException("Please register before changing your password");
        }
        if (!passwordService.matches(user.getEmail(), request.currentPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }

        user.setPasswordHash(passwordService.hash(user.getEmail(), request.newPassword()));
        user.clearPasswordReset();
        user.setSessionToken(UUID.randomUUID().toString());
        structuredLogs.info(log, "auth.password_changed", structuredLogs.fields(
                "userId", user.getId(),
                "email", user.getEmail()
        ));
        return user;
    }

    @Transactional
    public void requestPasswordReset(PasswordResetRequest request) {
        String email = request.email().trim().toLowerCase();
        users.findByEmailIgnoreCase(email)
                .filter(AppUser::isRegistered)
                .ifPresent(user -> {
                    String token = secureToken();
                    user.assignPasswordReset(tokenHash(token), Instant.now().plus(PASSWORD_RESET_TTL));
                    passwordResetEmailService.sendResetLink(user, token, normaliseLanguage(request.language()));
                    structuredLogs.info(log, "auth.password_reset_requested", structuredLogs.fields(
                            "userId", user.getId(),
                            "email", user.getEmail()
                    ));
                });
    }

    @Transactional
    public AppUser confirmPasswordReset(PasswordResetConfirmRequest request) {
        String email = request.email().trim().toLowerCase();
        AppUser user = users.findByEmailIgnoreCase(email)
                .filter(AppUser::isRegistered)
                .orElseThrow(() -> new IllegalArgumentException("Password reset link is invalid or expired"));
        String expectedHash = user.getPasswordResetTokenHash();
        Instant expiresAt = user.getPasswordResetExpiresAt();
        if (expectedHash == null || expiresAt == null || expiresAt.isBefore(Instant.now()) || !constantTimeEquals(expectedHash, tokenHash(request.token()))) {
            throw new IllegalArgumentException("Password reset link is invalid or expired");
        }

        user.setPasswordHash(passwordService.hash(email, request.password()));
        user.clearPasswordReset();
        user.setSessionToken(UUID.randomUUID().toString());
        if (request.language() != null && !request.language().isBlank()) {
            user.setLanguage(normaliseLanguage(request.language()));
        }
        structuredLogs.info(log, "auth.password_reset_confirmed", structuredLogs.fields(
                "userId", user.getId(),
                "email", user.getEmail()
        ));
        return user;
    }

    private String normaliseLanguage(String language) {
        return "en".equalsIgnoreCase(language) ? "en" : "af";
    }

    private String secureToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String tokenHash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private void validatePendingRegistration(AppUser user, AuthRequest request) {
        Federation selectedFederation = request.federationId() == null ? null : federations.findById(request.federationId()).orElseThrow();
        Club selectedClub = request.clubId() == null ? null : clubs.findById(request.clubId()).orElseThrow();
        Loft selectedLoft = request.loftId() == null ? null : lofts.findById(request.loftId()).orElseThrow();
        if (selectedFederation != null || selectedClub != null || selectedLoft != null) {
            validateOrganisationSelection(selectedFederation, selectedClub, selectedLoft);
            requireSameSelection(user.getFederation(), selectedFederation, "Federation does not match your preloaded profile");
            requireSameSelection(user.getClub(), selectedClub, "Club does not match your preloaded profile");
            requireSameSelection(user.getLoft(), selectedLoft, "Loft does not match your preloaded profile");
        }
        if (user.getRole() == UserRole.USER && (user.getFederation() == null || user.getClub() == null || user.getLoft() == null)) {
            throw new IllegalArgumentException("Your profile is missing federation, club, or loft details");
        }
        if (user.getRole() == UserRole.FEDERATION_ADMIN && user.getFederation() == null) {
            throw new IllegalArgumentException("Your federation admin profile is missing a federation");
        }
    }

    private void requireSameSelection(Object expected, Object selected, String message) {
        if (expected == null || selected == null) {
            return;
        }
        Long expectedId = organisationId(expected);
        Long selectedId = organisationId(selected);
        if (!expectedId.equals(selectedId)) {
            throw new IllegalArgumentException(message);
        }
    }

    private Long organisationId(Object value) {
        if (value instanceof Federation federation) {
            return federation.getId();
        }
        if (value instanceof Club club) {
            return club.getId();
        }
        if (value instanceof Loft loft) {
            return loft.getId();
        }
        throw new IllegalArgumentException("Unknown organisation selection");
    }

    private void validateOrganisationSelection(Federation federation, Club club, Loft loft) {
        if (federation == null || club == null || loft == null) {
            throw new IllegalArgumentException("Choose federation, club, and loft from the official list");
        }
        if (club != null && federation == null) {
            throw new IllegalArgumentException("Choose a federation before choosing a club");
        }
        if (loft != null && club == null) {
            throw new IllegalArgumentException("Choose a club before choosing a loft");
        }
        if (club != null && !club.getFederation().getId().equals(federation.getId())) {
            throw new IllegalArgumentException("Club does not belong to the selected federation");
        }
        if (loft != null && !loft.getClub().getId().equals(club.getId())) {
            throw new IllegalArgumentException("Loft does not belong to the selected club");
        }
    }
}
