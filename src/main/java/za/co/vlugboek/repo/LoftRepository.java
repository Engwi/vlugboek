package za.co.vlugboek.repo;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import za.co.vlugboek.domain.Loft;

public interface LoftRepository extends JpaRepository<Loft, Long> {
    List<Loft> findByClubIdOrderByNameAsc(Long clubId);

    Optional<Loft> findByNameAndClubId(String name, Long clubId);

    boolean existsByNameIgnoreCaseAndClubId(String name, Long clubId);

    long countByClubId(Long clubId);
}
