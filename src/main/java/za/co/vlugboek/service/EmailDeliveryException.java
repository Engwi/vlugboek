package za.co.vlugboek.service;

public class EmailDeliveryException extends RuntimeException {
    private final Integer statusCode;

    public EmailDeliveryException(String message) {
        super(message);
        this.statusCode = null;
    }

    public EmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = null;
    }

    public EmailDeliveryException(String message, Integer statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public Integer statusCode() {
        return statusCode;
    }
}
