package za.co.vlugboek.repo;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import za.co.vlugboek.domain.IngestionRun;

public interface IngestionRunRepository extends JpaRepository<IngestionRun, Long> {
    List<IngestionRun> findTop20ByOrderByStartedAtDesc();
}
