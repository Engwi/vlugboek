package za.co.vlugboek.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import za.co.vlugboek.domain.AppUser;
import za.co.vlugboek.domain.Club;
import za.co.vlugboek.domain.Federation;
import za.co.vlugboek.domain.Loft;
import za.co.vlugboek.domain.UserRole;
import za.co.vlugboek.repo.AppUserRepository;
import za.co.vlugboek.repo.ClubRepository;
import za.co.vlugboek.repo.FederationRepository;
import za.co.vlugboek.repo.LoftRepository;

@Component
public class DataSeeder implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final FederationRepository federations;
    private final ClubRepository clubs;
    private final LoftRepository lofts;
    private final AppUserRepository users;
    private final PasswordService passwordService;
    private final DocumentService documentService;
    private final Path seedRoot;
    private final boolean referenceDataEnabled;
    private final boolean pdfImportEnabled;
    private final boolean adminEnabled;
    private final String adminEmail;
    private final String adminName;
    private final String adminPassword;
    private final boolean demoUsersEnabled;
    private final String demoEmail;
    private final String demoName;
    private final String demoPassword;

    public DataSeeder(FederationRepository federations, ClubRepository clubs, LoftRepository lofts,
                      AppUserRepository users, PasswordService passwordService, DocumentService documentService,
                      @Value("${vlugboek.seed.pdf-root}") String seedRoot,
                      @Value("${vlugboek.seed.reference-data-enabled:true}") boolean referenceDataEnabled,
                      @Value("${vlugboek.seed.pdf-import-enabled:true}") boolean pdfImportEnabled,
                      @Value("${vlugboek.seed.admin-enabled:true}") boolean adminEnabled,
                      @Value("${vlugboek.seed.admin-email:admin@vlugboek.local}") String adminEmail,
                      @Value("${vlugboek.seed.admin-name:Admin}") String adminName,
                      @Value("${vlugboek.seed.admin-password:admin123}") String adminPassword,
                      @Value("${vlugboek.seed.demo-users-enabled:true}") boolean demoUsersEnabled,
                      @Value("${vlugboek.seed.demo-email:demo@vlugboek.local}") String demoEmail,
                      @Value("${vlugboek.seed.demo-name:Demo Fancier}") String demoName,
                      @Value("${vlugboek.seed.demo-password:demo123}") String demoPassword) {
        this.federations = federations;
        this.clubs = clubs;
        this.lofts = lofts;
        this.users = users;
        this.passwordService = passwordService;
        this.documentService = documentService;
        this.seedRoot = Path.of(seedRoot);
        this.referenceDataEnabled = referenceDataEnabled;
        this.pdfImportEnabled = pdfImportEnabled;
        this.adminEnabled = adminEnabled;
        this.adminEmail = adminEmail;
        this.adminName = adminName;
        this.adminPassword = adminPassword;
        this.demoUsersEnabled = demoUsersEnabled;
        this.demoEmail = demoEmail;
        this.demoName = demoName;
        this.demoPassword = demoPassword;
    }

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        if (!referenceDataEnabled) {
            log.info("Reference data seed is disabled.");
            return;
        }

        Federation pwdf = federations.findByCode("PWDF")
                .orElseGet(() -> federations.save(new Federation("PWDF", "Pretoria Wedvlug Duiwe Federasie")));
        Club zwartkops = ensureClub("Zwartkops", pwdf);
        Club magalies = ensureClub("Magalies", pwdf);
        Club eureka = ensureClub("Eureka", pwdf);
        Club wesMoot = ensureClub("Wes-Moot", pwdf);

        ensureLofts(zwartkops, List.of("Boshoff & Seun", "Pretorius Loft", "Van Wyk Hok"));
        ensureLofts(magalies, List.of("Malan Racing", "Fourie Familie", "Botha Hok"));
        ensureLofts(eureka, List.of("Kruger Wedvlug", "Ndlovu Loft"));
        ensureLofts(wesMoot, List.of("De Beer Kombinasie", "Naidoo Racing"));

        if (adminEnabled) {
            ensureConfiguredPassword(adminPassword, "Admin seed password");
            ensureUser(adminEmail, adminName, adminPassword, UserRole.SYSTEM_ADMIN, pwdf, zwartkops, lofts.findByNameAndClubId("Boshoff & Seun", zwartkops.getId()).orElse(null));
        } else {
            log.info("Admin user seed is disabled.");
        }

        if (demoUsersEnabled) {
            ensureConfiguredPassword(demoPassword, "Demo seed password");
            ensureUser(demoEmail, demoName, demoPassword, UserRole.USER, pwdf, magalies, lofts.findByNameAndClubId("Malan Racing", magalies.getId()).orElse(null));
        } else {
            log.info("Demo user seed is disabled.");
        }

        if (pdfImportEnabled) {
            seedPdfDocuments();
        } else {
            log.info("PDF document seed is disabled.");
        }
    }

    private Club ensureClub(String name, Federation federation) {
        return clubs.findByNameAndFederationId(name, federation.getId())
                .orElseGet(() -> clubs.save(new Club(name, federation)));
    }

    private void ensureLofts(Club club, List<String> names) {
        names.forEach(name -> lofts.findByNameAndClubId(name, club.getId())
                .orElseGet(() -> lofts.save(new Loft(name, club))));
    }

    private void ensureUser(String email, String name, String password, UserRole role, Federation federation, Club club, Loft loft) {
        users.findByEmailIgnoreCase(email).ifPresentOrElse(existing -> {
            if (role == UserRole.SYSTEM_ADMIN && !existing.getRole().isSystemAdmin()) {
                existing.setRole(UserRole.SYSTEM_ADMIN);
            }
        }, () -> users.save(new AppUser(
                email,
                name,
                passwordService.hash(email, password),
                role,
                "af",
                federation,
                club,
                loft
        )));
    }

    private void ensureConfiguredPassword(String password, String label) {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(label + " is required when that seed is enabled");
        }
    }

    private void seedPdfDocuments() throws IOException {
        if (!Files.exists(seedRoot)) {
            return;
        }
        try (var stream = Files.walk(seedRoot)) {
            stream.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().toLowerCase().endsWith(".pdf"))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(this::seedPdfDocument);
        }
    }

    private void seedPdfDocument(Path path) {
        try {
            documentService.ingestExistingPdf(path);
        } catch (RuntimeException ex) {
            log.warn("Skipping seed PDF {} because it could not be imported: {}", path, ex.getMessage());
        }
    }
}
