package za.co.vlugboek.service;

import jakarta.transaction.Transactional;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import za.co.vlugboek.api.Dtos;
import za.co.vlugboek.api.dto.ClubAdminDto;
import za.co.vlugboek.api.dto.FederationAdminDto;
import za.co.vlugboek.api.dto.LoftAdminDto;
import za.co.vlugboek.api.dto.OrganisationRequest;
import za.co.vlugboek.api.dto.OrganisationTreeDto;
import za.co.vlugboek.api.dto.UserAdminDto;
import za.co.vlugboek.api.dto.UserAdminRequest;
import za.co.vlugboek.domain.AppUser;
import za.co.vlugboek.domain.Club;
import za.co.vlugboek.domain.DocumentRecord;
import za.co.vlugboek.domain.Federation;
import za.co.vlugboek.domain.Loft;
import za.co.vlugboek.domain.UserRole;
import za.co.vlugboek.repo.AppUserRepository;
import za.co.vlugboek.repo.ClubRepository;
import za.co.vlugboek.repo.DocumentRepository;
import za.co.vlugboek.repo.FederationRepository;
import za.co.vlugboek.repo.LoftRepository;

@Service
public class OrganisationAdminService {
    private static final Logger log = LoggerFactory.getLogger(OrganisationAdminService.class);

    private final FederationRepository federations;
    private final ClubRepository clubs;
    private final LoftRepository lofts;
    private final AppUserRepository users;
    private final DocumentRepository documents;
    private final StructuredLogService structuredLogs;

    public OrganisationAdminService(FederationRepository federations, ClubRepository clubs, LoftRepository lofts,
                                    AppUserRepository users, DocumentRepository documents, StructuredLogService structuredLogs) {
        this.federations = federations;
        this.clubs = clubs;
        this.lofts = lofts;
        this.users = users;
        this.documents = documents;
        this.structuredLogs = structuredLogs;
    }

    @Transactional
    public OrganisationTreeDto tree(AppUser actor) {
        List<DocumentRecord> allDocuments = documents.findAll();
        List<Federation> visibleFederations = actor.getRole().isSystemAdmin()
                ? federations.findAll()
                : actor.getFederation() == null ? List.of() : List.of(actor.getFederation());
        List<FederationAdminDto> federationDtos = visibleFederations.stream()
                .sorted(Comparator.comparing(Federation::getCode, String.CASE_INSENSITIVE_ORDER))
                .map(federation -> federationDto(federation, allDocuments))
                .toList();
        return new OrganisationTreeDto(federationDtos);
    }

    @Transactional
    public FederationAdminDto createFederation(OrganisationRequest request, AppUser actor) {
        requireSystemAdmin(actor);
        String code = clean(request.code(), "Federation code").toUpperCase();
        String name = clean(request.name(), "Federation name");
        if (federations.existsByCodeIgnoreCase(code)) {
            throw new IllegalArgumentException("Federation code is already in use");
        }
        Federation federation = federations.save(new Federation(code, name));
        structuredLogs.info(log, "admin.federation.created", adminFields(actor,
                "federationId", federation.getId(),
                "code", federation.getCode(),
                "name", federation.getName()
        ));
        return federationDto(federation, documents.findAll());
    }

    @Transactional
    public FederationAdminDto updateFederation(Long id, OrganisationRequest request, AppUser actor) {
        requireSystemAdmin(actor);
        Federation federation = federations.findById(id).orElseThrow();
        ensureFederationEditable(federation);
        String code = clean(request.code(), "Federation code").toUpperCase();
        String name = clean(request.name(), "Federation name");
        federations.findByCodeIgnoreCase(code)
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("Federation code is already in use");
        });
        federation.update(code, name);
        structuredLogs.info(log, "admin.federation.updated", adminFields(actor,
                "federationId", federation.getId(),
                "code", federation.getCode(),
                "name", federation.getName()
        ));
        return federationDto(federation, documents.findAll());
    }

    @Transactional
    public void deleteFederation(Long id, AppUser actor) {
        requireSystemAdmin(actor);
        Federation federation = federations.findById(id).orElseThrow();
        ensureFederationEditable(federation);
        if (clubs.countByFederationId(id) > 0) {
            throw new IllegalArgumentException("Federation still has clubs");
        }
        federations.delete(federation);
        structuredLogs.info(log, "admin.federation.deleted", adminFields(actor,
                "federationId", id,
                "code", federation.getCode()
        ));
    }

    @Transactional
    public ClubAdminDto createClub(OrganisationRequest request, AppUser actor) {
        Federation federation = federations.findById(requiredId(request.federationId(), "Federation")).orElseThrow();
        requireFederationScope(actor, federation.getId());
        String name = clean(request.name(), "Club name");
        if (clubs.existsByNameIgnoreCaseAndFederationId(name, federation.getId())) {
            throw new IllegalArgumentException("Club name already exists in this federation");
        }
        Club club = clubs.save(new Club(name, federation));
        structuredLogs.info(log, "admin.club.created", adminFields(actor,
                "federationId", federation.getId(),
                "clubId", club.getId(),
                "name", club.getName()
        ));
        return clubDto(club, documents.findAll());
    }

    @Transactional
    public ClubAdminDto updateClub(Long id, OrganisationRequest request, AppUser actor) {
        Club club = clubs.findById(id).orElseThrow();
        requireFederationScope(actor, club.getFederation().getId());
        ensureClubEditable(club);
        String name = clean(request.name(), "Club name");
        if (clubs.findByFederationIdOrderByNameAsc(club.getFederation().getId()).stream()
                .anyMatch(existing -> !existing.getId().equals(id) && existing.getName().equalsIgnoreCase(name))) {
            throw new IllegalArgumentException("Club name already exists in this federation");
        }
        club.update(name);
        structuredLogs.info(log, "admin.club.updated", adminFields(actor,
                "federationId", club.getFederation().getId(),
                "clubId", club.getId(),
                "name", club.getName()
        ));
        return clubDto(club, documents.findAll());
    }

    @Transactional
    public void deleteClub(Long id, AppUser actor) {
        Club club = clubs.findById(id).orElseThrow();
        requireFederationScope(actor, club.getFederation().getId());
        ensureClubEditable(club);
        if (lofts.countByClubId(id) > 0) {
            throw new IllegalArgumentException("Club still has lofts");
        }
        clubs.delete(club);
        structuredLogs.info(log, "admin.club.deleted", adminFields(actor,
                "federationId", club.getFederation().getId(),
                "clubId", id,
                "name", club.getName()
        ));
    }

    @Transactional
    public LoftAdminDto createLoft(OrganisationRequest request, AppUser actor) {
        Club club = clubs.findById(requiredId(request.clubId(), "Club")).orElseThrow();
        requireFederationScope(actor, club.getFederation().getId());
        String name = clean(request.name(), "Loft name");
        if (lofts.existsByNameIgnoreCaseAndClubId(name, club.getId())) {
            throw new IllegalArgumentException("Loft name already exists in this club");
        }
        Loft loft = lofts.save(new Loft(name, club));
        structuredLogs.info(log, "admin.loft.created", adminFields(actor,
                "federationId", club.getFederation().getId(),
                "clubId", club.getId(),
                "loftId", loft.getId(),
                "name", loft.getName()
        ));
        return loftDto(loft, documents.findAll());
    }

    @Transactional
    public LoftAdminDto updateLoft(Long id, OrganisationRequest request, AppUser actor) {
        Loft loft = lofts.findById(id).orElseThrow();
        requireFederationScope(actor, loft.getClub().getFederation().getId());
        ensureLoftEditable(loft);
        String name = clean(request.name(), "Loft name");
        if (lofts.findByClubIdOrderByNameAsc(loft.getClub().getId()).stream()
                .anyMatch(existing -> !existing.getId().equals(id) && existing.getName().equalsIgnoreCase(name))) {
            throw new IllegalArgumentException("Loft name already exists in this club");
        }
        loft.update(name);
        structuredLogs.info(log, "admin.loft.updated", adminFields(actor,
                "federationId", loft.getClub().getFederation().getId(),
                "clubId", loft.getClub().getId(),
                "loftId", loft.getId(),
                "name", loft.getName()
        ));
        return loftDto(loft, documents.findAll());
    }

    @Transactional
    public void deleteLoft(Long id, AppUser actor) {
        Loft loft = lofts.findById(id).orElseThrow();
        requireFederationScope(actor, loft.getClub().getFederation().getId());
        ensureLoftEditable(loft);
        lofts.delete(loft);
        structuredLogs.info(log, "admin.loft.deleted", adminFields(actor,
                "federationId", loft.getClub().getFederation().getId(),
                "clubId", loft.getClub().getId(),
                "loftId", id,
                "name", loft.getName()
        ));
    }

    @Transactional
    public FederationAdminDto setFederationAdmin(Long federationId, UserAdminRequest request, AppUser actor) {
        requireSystemAdmin(actor);
        Federation federation = federations.findById(federationId).orElseThrow();
        String email = cleanEmail(request.email());
        users.findByRoleAndFederationId(UserRole.FEDERATION_ADMIN, federationId).stream()
                .filter(existing -> !existing.getEmail().equalsIgnoreCase(email))
                .forEach(existing -> {
                    if (existing.isRegistered()) {
                        throw new IllegalArgumentException("Federation already has a registered admin user");
                    }
                    users.delete(existing);
                });

        AppUser federationAdmin = users.findByEmailIgnoreCase(email).map(existing -> {
            if (existing.getRole().isSystemAdmin()) {
                throw new IllegalArgumentException("System admin cannot also be a federation admin");
            }
            if (existing.getFederation() != null && !existing.getFederation().getId().equals(federationId)) {
                throw new IllegalArgumentException("Email belongs to a different federation");
            }
            existing.setRole(UserRole.FEDERATION_ADMIN);
            if (existing.getFederation() == null) {
                existing.setOrganisation(federation, null, null);
            }
            return existing;
        }).orElseGet(() -> users.save(AppUser.pending(email, UserRole.FEDERATION_ADMIN, federation, null, null)));

        structuredLogs.info(log, "admin.federation_admin.assigned", adminFields(actor,
                "federationId", federation.getId(),
                "federationAdminUserId", federationAdmin.getId(),
                "federationAdminEmail", federationAdmin.getEmail(),
                "registered", federationAdmin.isRegistered()
        ));
        return federationDto(federationAdmin.getFederation(), documents.findAll());
    }

    @Transactional
    public UserAdminDto preloadFancier(UserAdminRequest request, AppUser actor) {
        String email = cleanEmail(request.email());
        Club club = clubs.findById(requiredId(request.clubId(), "Club")).orElseThrow();
        Loft loft = lofts.findById(requiredId(request.loftId(), "Loft")).orElseThrow();
        Federation federation = club.getFederation();
        if (request.federationId() != null && !request.federationId().equals(federation.getId())) {
            throw new IllegalArgumentException("Club does not belong to the selected federation");
        }
        if (!loft.getClub().getId().equals(club.getId())) {
            throw new IllegalArgumentException("Loft does not belong to the selected club");
        }
        requireFederationScope(actor, federation.getId());

        AppUser user = users.findByEmailIgnoreCase(email).map(existing -> {
            if (existing.isRegistered()) {
                throw new IllegalArgumentException("Email address is already registered");
            }
            if (existing.getRole() != UserRole.USER) {
                throw new IllegalArgumentException("Email belongs to an admin profile");
            }
            existing.updatePending(UserRole.USER, federation, club, loft);
            return existing;
        }).orElseGet(() -> users.save(AppUser.pending(email, UserRole.USER, federation, club, loft)));

        structuredLogs.info(log, "admin.fancier.preloaded", adminFields(actor,
                "federationId", federation.getId(),
                "clubId", club.getId(),
                "loftId", loft.getId(),
                "userId", user.getId(),
                "email", user.getEmail()
        ));
        return Dtos.userAdmin(user);
    }

    private FederationAdminDto federationDto(Federation federation, List<DocumentRecord> allDocuments) {
        long userCount = users.countByFederationId(federation.getId());
        long documentCount = documents.countByFederationId(federation.getId());
        long clubCount = clubs.countByFederationId(federation.getId());
        List<ClubAdminDto> clubDtos = clubs.findByFederationIdOrderByNameAsc(federation.getId()).stream()
                .map(club -> clubDto(club, allDocuments))
                .toList();
        return new FederationAdminDto(
                federation.getId(),
                federation.getCode(),
                federation.getName(),
                federation.getCountry(),
                userCount,
                documentCount,
                clubCount,
                userCount > 0 || documentCount > 0,
                federationAdminDto(federation.getId()),
                clubDtos
        );
    }

    private UserAdminDto federationAdminDto(Long federationId) {
        return users.findByRoleAndFederationId(UserRole.FEDERATION_ADMIN, federationId).stream()
                .findFirst()
                .map(Dtos::userAdmin)
                .orElse(null);
    }

    private ClubAdminDto clubDto(Club club, List<DocumentRecord> allDocuments) {
        long userCount = users.countByClubId(club.getId());
        long documentCount = allDocuments.stream().filter(document -> document.matchesClubName(club.getName())).count();
        long loftCount = lofts.countByClubId(club.getId());
        List<LoftAdminDto> loftDtos = lofts.findByClubIdOrderByNameAsc(club.getId()).stream()
                .map(loft -> loftDto(loft, allDocuments))
                .toList();
        return new ClubAdminDto(
                club.getId(),
                club.getFederation().getId(),
                club.getName(),
                userCount,
                documentCount,
                loftCount,
                userCount > 0 || documentCount > 0,
                loftDtos
        );
    }

    private LoftAdminDto loftDto(Loft loft, List<DocumentRecord> allDocuments) {
        long userCount = users.countByLoftId(loft.getId());
        long documentCount = allDocuments.stream().filter(document -> document.matchesLoftName(loft.getName())).count();
        return new LoftAdminDto(
                loft.getId(),
                loft.getClub().getId(),
                loft.getName(),
                userCount,
                documentCount,
                userCount > 0 || documentCount > 0
        );
    }

    private void ensureFederationEditable(Federation federation) {
        if (users.countByFederationId(federation.getId()) > 0 || documents.countByFederationId(federation.getId()) > 0) {
            throw new IllegalArgumentException("Federation is linked to users or reports and cannot be changed");
        }
    }

    private void ensureClubEditable(Club club) {
        if (users.countByClubId(club.getId()) > 0 || documents.findAll().stream().anyMatch(document -> document.matchesClubName(club.getName()))) {
            throw new IllegalArgumentException("Club is linked to users or reports and cannot be changed");
        }
    }

    private void ensureLoftEditable(Loft loft) {
        if (users.countByLoftId(loft.getId()) > 0 || documents.findAll().stream().anyMatch(document -> document.matchesLoftName(loft.getName()))) {
            throw new IllegalArgumentException("Loft is linked to users or reports and cannot be changed");
        }
    }

    private Long requiredId(Long id, String label) {
        if (id == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        return id;
    }

    private String clean(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    private String cleanEmail(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Email is required");
        }
        String email = value.trim().toLowerCase();
        if (!email.contains("@")) {
            throw new IllegalArgumentException("Enter a valid email address");
        }
        return email;
    }

    private void requireSystemAdmin(AppUser actor) {
        if (actor == null || !actor.getRole().isSystemAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "System admin access is required");
        }
    }

    private void requireFederationScope(AppUser actor, Long federationId) {
        if (actor == null || !actor.getRole().isAdminRole()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access is required");
        }
        if (actor.getRole().isSystemAdmin()) {
            return;
        }
        if (actor.getFederation() != null && actor.getFederation().getId().equals(federationId)) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Federation admin access is required");
    }

    private java.util.Map<String, Object> adminFields(AppUser actor, Object... values) {
        Object[] base = new Object[values.length + 6];
        base[0] = "actorId";
        base[1] = actor == null ? null : actor.getId();
        base[2] = "actorEmail";
        base[3] = actor == null ? null : actor.getEmail();
        base[4] = "actorRole";
        base[5] = actor == null ? null : actor.getRole().responseName();
        System.arraycopy(values, 0, base, 6, values.length);
        return structuredLogs.fields(base);
    }
}
