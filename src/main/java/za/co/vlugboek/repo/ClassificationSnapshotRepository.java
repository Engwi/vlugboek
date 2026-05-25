package za.co.vlugboek.repo;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import za.co.vlugboek.domain.ClassificationCategory;
import za.co.vlugboek.domain.ClassificationSnapshot;

public interface ClassificationSnapshotRepository extends JpaRepository<ClassificationSnapshot, Long> {
    List<ClassificationSnapshot> findByCategoryAndLatestTrue(ClassificationCategory category);

    List<ClassificationSnapshot> findByLatestTrueOrderByCategoryAsc();
}
