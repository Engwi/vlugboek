package za.co.vlugboek.api;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import za.co.vlugboek.api.dto.ClubAdminDto;
import za.co.vlugboek.api.dto.FederationAdminDto;
import za.co.vlugboek.api.dto.LabelDto;
import za.co.vlugboek.api.dto.LoftAdminDto;
import za.co.vlugboek.api.dto.OrganisationRequest;
import za.co.vlugboek.api.dto.OrganisationTreeDto;
import za.co.vlugboek.api.dto.UserAdminDto;
import za.co.vlugboek.api.dto.UserAdminRequest;
import za.co.vlugboek.domain.AppUser;
import za.co.vlugboek.repo.ClubRepository;
import za.co.vlugboek.repo.FederationRepository;
import za.co.vlugboek.repo.LoftRepository;
import za.co.vlugboek.service.OrganisationAdminService;

@RestController
public class OrganisationController {
    private final FederationRepository federations;
    private final ClubRepository clubs;
    private final LoftRepository lofts;
    private final OrganisationAdminService organisationAdmin;

    public OrganisationController(FederationRepository federations, ClubRepository clubs, LoftRepository lofts,
                                  OrganisationAdminService organisationAdmin) {
        this.federations = federations;
        this.clubs = clubs;
        this.lofts = lofts;
        this.organisationAdmin = organisationAdmin;
    }

    @GetMapping("/api/federations")
    public List<LabelDto> federations() {
        return federations.findAll().stream().map(Dtos::federation).toList();
    }

    @GetMapping("/api/clubs")
    public List<LabelDto> clubs(@RequestParam Long federationId) {
        return clubs.findByFederationIdOrderByNameAsc(federationId).stream().map(Dtos::club).toList();
    }

    @GetMapping("/api/lofts")
    public List<LabelDto> lofts(@RequestParam Long clubId) {
        return lofts.findByClubIdOrderByNameAsc(clubId).stream().map(Dtos::loft).toList();
    }

    @GetMapping("/api/admin/organisations")
    public OrganisationTreeDto organisationTree(Authentication authentication) {
        return organisationAdmin.tree(currentUser(authentication));
    }

    @PostMapping("/api/admin/federations")
    @ResponseStatus(HttpStatus.CREATED)
    public FederationAdminDto createFederation(@RequestBody OrganisationRequest request, Authentication authentication) {
        return organisationAdmin.createFederation(request, currentUser(authentication));
    }

    @PutMapping("/api/admin/federations/{id}")
    public FederationAdminDto updateFederation(@PathVariable Long id, @RequestBody OrganisationRequest request,
                                               Authentication authentication) {
        return organisationAdmin.updateFederation(id, request, currentUser(authentication));
    }

    @DeleteMapping("/api/admin/federations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFederation(@PathVariable Long id, Authentication authentication) {
        organisationAdmin.deleteFederation(id, currentUser(authentication));
    }

    @PutMapping("/api/admin/federations/{id}/admin")
    public FederationAdminDto setFederationAdmin(@PathVariable Long id, @RequestBody UserAdminRequest request,
                                                 Authentication authentication) {
        return organisationAdmin.setFederationAdmin(id, request, currentUser(authentication));
    }

    @PostMapping("/api/admin/clubs")
    @ResponseStatus(HttpStatus.CREATED)
    public ClubAdminDto createClub(@RequestBody OrganisationRequest request, Authentication authentication) {
        return organisationAdmin.createClub(request, currentUser(authentication));
    }

    @PutMapping("/api/admin/clubs/{id}")
    public ClubAdminDto updateClub(@PathVariable Long id, @RequestBody OrganisationRequest request,
                                   Authentication authentication) {
        return organisationAdmin.updateClub(id, request, currentUser(authentication));
    }

    @DeleteMapping("/api/admin/clubs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteClub(@PathVariable Long id, Authentication authentication) {
        organisationAdmin.deleteClub(id, currentUser(authentication));
    }

    @PostMapping("/api/admin/lofts")
    @ResponseStatus(HttpStatus.CREATED)
    public LoftAdminDto createLoft(@RequestBody OrganisationRequest request, Authentication authentication) {
        return organisationAdmin.createLoft(request, currentUser(authentication));
    }

    @PutMapping("/api/admin/lofts/{id}")
    public LoftAdminDto updateLoft(@PathVariable Long id, @RequestBody OrganisationRequest request,
                                   Authentication authentication) {
        return organisationAdmin.updateLoft(id, request, currentUser(authentication));
    }

    @DeleteMapping("/api/admin/lofts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLoft(@PathVariable Long id, Authentication authentication) {
        organisationAdmin.deleteLoft(id, currentUser(authentication));
    }

    @PostMapping("/api/admin/preloaded-users")
    @ResponseStatus(HttpStatus.CREATED)
    public UserAdminDto preloadFancier(@RequestBody UserAdminRequest request, Authentication authentication) {
        return organisationAdmin.preloadFancier(request, currentUser(authentication));
    }

    private AppUser currentUser(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof AppUser user) {
            return user;
        }
        throw new IllegalArgumentException("Sign in to continue");
    }
}
