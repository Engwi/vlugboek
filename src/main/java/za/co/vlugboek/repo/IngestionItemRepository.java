package za.co.vlugboek.repo;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import za.co.vlugboek.domain.IngestionItem;

public interface IngestionItemRepository extends JpaRepository<IngestionItem, Long> {
    List<IngestionItem> findByRunIdOrderByIdAsc(Long runId);
}
