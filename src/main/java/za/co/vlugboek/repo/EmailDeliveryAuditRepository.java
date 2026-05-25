package za.co.vlugboek.repo;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import za.co.vlugboek.domain.EmailDeliveryAudit;

public interface EmailDeliveryAuditRepository extends JpaRepository<EmailDeliveryAudit, Long> {
    List<EmailDeliveryAudit> findByDocument_IdOrderByRequestedAtDesc(Long documentId);
}
