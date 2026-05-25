package za.co.vlugboek.repo;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import za.co.vlugboek.domain.Federation;

public interface FederationRepository extends JpaRepository<Federation, Long> {
    Optional<Federation> findByCode(String code);

    Optional<Federation> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);
}
