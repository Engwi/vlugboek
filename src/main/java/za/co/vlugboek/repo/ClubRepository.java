package za.co.vlugboek.repo;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import za.co.vlugboek.domain.Club;

public interface ClubRepository extends JpaRepository<Club, Long> {
    List<Club> findByFederationIdOrderByNameAsc(Long federationId);

    Optional<Club> findByNameAndFederationId(String name, Long federationId);

    boolean existsByNameIgnoreCaseAndFederationId(String name, Long federationId);

    long countByFederationId(Long federationId);
}
