package za.co.vlugboek.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "app_users")
public class AppUser {
    public static final String PENDING_PASSWORD_HASH = "PENDING_REGISTRATION";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String displayName;

    @Column(nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role = UserRole.USER;

    @Column(nullable = false)
    private String language = "af";

    @Column(nullable = false)
    private boolean registered = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "federation_id")
    private Federation federation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "club_id")
    private Club club;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loft_id")
    private Loft loft;

    private String sessionToken;

    private String passwordResetTokenHash;

    private Instant passwordResetExpiresAt;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected AppUser() {
    }

    public AppUser(String email, String displayName, String passwordHash, UserRole role, String language,
                   Federation federation, Club club, Loft loft) {
        this(email, displayName, passwordHash, role, language, federation, club, loft, true);
    }

    public AppUser(String email, String displayName, String passwordHash, UserRole role, String language,
                   Federation federation, Club club, Loft loft, boolean registered) {
        this.email = email;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.role = role;
        this.language = language;
        this.federation = federation;
        this.club = club;
        this.loft = loft;
        this.registered = registered;
    }

    public static AppUser pending(String email, UserRole role, Federation federation, Club club, Loft loft) {
        String cleanEmail = email == null ? "" : email.trim().toLowerCase();
        String name = cleanEmail.contains("@") ? cleanEmail.substring(0, cleanEmail.indexOf("@")) : cleanEmail;
        return new AppUser(cleanEmail, name, PENDING_PASSWORD_HASH, role, "af", federation, club, loft, false);
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public UserRole getRole() {
        return role;
    }

    public boolean isRegistered() {
        return registered;
    }

    public String getLanguage() {
        return language;
    }

    public Federation getFederation() {
        return federation;
    }

    public Club getClub() {
        return club;
    }

    public Loft getLoft() {
        return loft;
    }

    public String getSessionToken() {
        return sessionToken;
    }

    public void setSessionToken(String sessionToken) {
        this.sessionToken = sessionToken;
    }

    public String getPasswordResetTokenHash() {
        return passwordResetTokenHash;
    }

    public Instant getPasswordResetExpiresAt() {
        return passwordResetExpiresAt;
    }

    public void assignPasswordReset(String tokenHash, Instant expiresAt) {
        this.passwordResetTokenHash = tokenHash;
        this.passwordResetExpiresAt = expiresAt;
    }

    public void clearPasswordReset() {
        this.passwordResetTokenHash = null;
        this.passwordResetExpiresAt = null;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public void setRole(UserRole role) {
        this.role = role;
    }

    public void setOrganisation(Federation federation, Club club, Loft loft) {
        this.federation = federation;
        this.club = club;
        this.loft = loft;
    }

    public void updatePending(UserRole role, Federation federation, Club club, Loft loft) {
        if (registered) {
            throw new IllegalStateException("Registered users cannot be preloaded again");
        }
        this.role = role;
        this.federation = federation;
        this.club = club;
        this.loft = loft;
    }

    public void completeRegistration(String displayName, String passwordHash, String language) {
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.language = language;
        this.registered = true;
    }
}
