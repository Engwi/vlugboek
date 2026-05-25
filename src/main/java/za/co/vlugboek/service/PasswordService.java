package za.co.vlugboek.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class PasswordService {
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);

    public String hash(String email, String password) {
        return passwordEncoder.encode(password);
    }

    public boolean matches(String email, String password, String hash) {
        if (hash == null || hash.isBlank()) {
            return false;
        }
        if (isBcrypt(hash)) {
            return passwordEncoder.matches(password, hash);
        }
        return legacyHash(email, password).equals(hash);
    }

    public boolean needsRehash(String hash) {
        return !isBcrypt(hash);
    }

    private boolean isBcrypt(String hash) {
        return hash.startsWith("$2a$") || hash.startsWith("$2b$") || hash.startsWith("$2y$");
    }

    private String legacyHash(String email, String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String salted = email.toLowerCase() + "::vlugboek::" + password;
            return HexFormat.of().formatHex(digest.digest(salted.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
