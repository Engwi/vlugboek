package za.co.vlugboek.repo;

import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import za.co.vlugboek.domain.AppUser;
import za.co.vlugboek.domain.UserRole;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    @EntityGraph(attributePaths = {"federation", "club", "loft"})
    Optional<AppUser> findByEmailIgnoreCase(String email);

    @EntityGraph(attributePaths = {"federation", "club", "loft"})
    Optional<AppUser> findBySessionToken(String sessionToken);

    boolean existsByEmailIgnoreCase(String email);

    @EntityGraph(attributePaths = {"federation", "club", "loft"})
    List<AppUser> findByFederationIdOrderByEmailAsc(Long federationId);

    @EntityGraph(attributePaths = {"federation", "club", "loft"})
    List<AppUser> findByRoleAndFederationId(UserRole role, Long federationId);

    long countByFederationId(Long federationId);

    long countByClubId(Long clubId);

    long countByLoftId(Long loftId);
}
