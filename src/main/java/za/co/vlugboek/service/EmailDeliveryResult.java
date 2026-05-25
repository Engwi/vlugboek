package za.co.vlugboek.service;

public record EmailDeliveryResult(
        String messageId,
        Long auditId,
        String requestId
) {
}
