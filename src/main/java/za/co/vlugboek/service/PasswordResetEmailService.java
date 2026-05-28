package za.co.vlugboek.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import za.co.vlugboek.domain.AppUser;

@Service
public class PasswordResetEmailService {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetEmailService.class);

    private final ObjectMapper objectMapper;
    private final StructuredLogService structuredLogs;
    private final HttpClient httpClient;
    private final String mailerUrl;
    private final String mailerToken;
    private final String publicUrl;

    public PasswordResetEmailService(
            ObjectMapper objectMapper,
            StructuredLogService structuredLogs,
            @Value("${vlugboek.mailer.url}") String mailerUrl,
            @Value("${vlugboek.mailer.token}") String mailerToken,
            @Value("${vlugboek.public-url}") String publicUrl
    ) {
        this.objectMapper = objectMapper;
        this.structuredLogs = structuredLogs;
        this.mailerUrl = mailerUrl;
        this.mailerToken = mailerToken;
        this.publicUrl = publicUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public void sendResetLink(AppUser user, String token, String language) {
        if (mailerToken == null || mailerToken.isBlank()) {
            throw new EmailDeliveryException("Mailer token is not configured");
        }

        String requestId = "password-reset-" + user.getId() + "-" + System.currentTimeMillis();
        PasswordResetContent content = content(user, resetUrl(user, token), language);
        Map<String, Object> payload = Map.of(
                "to", user.getEmail(),
                "subject", content.subject(),
                "text", content.text(),
                "html", content.html(),
                "requestId", requestId,
                "meta", Map.of(
                        "requestId", requestId,
                        "userId", user.getId(),
                        "recipient", user.getEmail(),
                        "language", language
                )
        );

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(messageMailerUrl()))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + mailerToken)
                    .header("X-Vlugboek-Request-Id", requestId)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new EmailDeliveryException("Mailer returned HTTP " + response.statusCode() + ": " + trim(response.body()), response.statusCode());
            }

            JsonNode body = objectMapper.readTree(response.body());
            structuredLogs.info(log, "password_reset.email_sent", structuredLogs.fields(
                    "requestId", requestId,
                    "userId", user.getId(),
                    "recipient", user.getEmail(),
                    "messageId", body.path("messageId").asText("")
            ));
        } catch (EmailDeliveryException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new EmailDeliveryException("Could not reach mailer at " + messageMailerUrl(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new EmailDeliveryException("Password reset email request was interrupted", ex);
        } catch (IllegalArgumentException ex) {
            throw new EmailDeliveryException("Mailer URL is not valid", ex);
        }
    }

    private PasswordResetContent content(AppUser user, String resetUrl, String language) {
        String name = user.getDisplayName() == null || user.getDisplayName().isBlank()
                ? user.getEmail()
                : user.getDisplayName();
        if ("en".equalsIgnoreCase(language)) {
            return new PasswordResetContent(
                    "Reset your Vlugboek password",
                    "Hello " + name + "\n\nUse this link to reset your Vlugboek password:\n" + resetUrl + "\n\nThe link expires in 30 minutes.\n\nKind regards,\nVlugboek",
                    htmlEmail("Hello " + escape(name), "Use this secure link to reset your Vlugboek password.", "Reset password", resetUrl, "The link expires in 30 minutes.")
            );
        }

        return new PasswordResetContent(
                "Stel jou Vlugboek wagwoord terug",
                "Hallo " + name + "\n\nGebruik hierdie skakel om jou Vlugboek wagwoord terug te stel:\n" + resetUrl + "\n\nDie skakel verval oor 30 minute.\n\nVriendelike groete,\nVlugboek",
                htmlEmail("Hallo " + escape(name), "Gebruik hierdie veilige skakel om jou Vlugboek wagwoord terug te stel.", "Stel wagwoord terug", resetUrl, "Die skakel verval oor 30 minute.")
        );
    }

    private String htmlEmail(String greeting, String message, String action, String resetUrl, String footer) {
        return """
                <!doctype html>
                <html>
                  <body style="margin:0;background:#F8F6F1;font-family:Arial,sans-serif;color:#0B1623;">
                    <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="background:#F8F6F1;padding:28px 12px;">
                      <tr>
                        <td align="center">
                          <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="max-width:620px;background:#FFFFFF;border:1px solid #E2DACD;border-radius:8px;overflow:hidden;box-shadow:0 16px 40px rgba(11,22,35,0.10);">
                            <tr>
                              <td style="background:#0B1623;padding:22px 26px;color:#F8F6F1;">
                                <div style="font-size:12px;letter-spacing:2px;text-transform:uppercase;color:#C79A47;">Vlugboek</div>
                                <div style="font-size:26px;line-height:1.1;font-weight:700;margin-top:5px;">Password Reset</div>
                              </td>
                            </tr>
                            <tr>
                              <td style="padding:26px;">
                                <p style="margin:0 0 14px;font-size:18px;font-weight:700;">%s</p>
                                <p style="margin:0 0 22px;font-size:15px;line-height:1.6;color:#3C4856;">%s</p>
                                <p style="margin:0 0 24px;">
                                  <a href="%s" style="display:inline-block;background:#C79A47;color:#0B1623;text-decoration:none;font-weight:700;border-radius:8px;padding:13px 18px;">%s</a>
                                </p>
                                <p style="margin:0 0 18px;font-size:13px;line-height:1.5;color:#6B7280;">%s</p>
                                <p style="margin:0;font-size:13px;line-height:1.5;color:#6B7280;word-break:break-all;">%s</p>
                              </td>
                            </tr>
                          </table>
                        </td>
                      </tr>
                    </table>
                  </body>
                </html>
                """.formatted(greeting, message, escapeAttribute(resetUrl), action, footer, escape(resetUrl));
    }

    private String resetUrl(AppUser user, String token) {
        String base = publicUrl == null || publicUrl.isBlank() ? "http://localhost:5173" : publicUrl.replaceAll("/+$", "");
        return base + "/?resetToken=" + urlEncode(token) + "&email=" + urlEncode(user.getEmail());
    }

    private String messageMailerUrl() {
        if (mailerUrl == null || mailerUrl.isBlank()) {
            return "http://127.0.0.1:8788/send-message";
        }
        if (mailerUrl.endsWith("/send-document")) {
            return mailerUrl.substring(0, mailerUrl.length() - "/send-document".length()) + "/send-message";
        }
        return mailerUrl.replaceAll("/+$", "") + "/send-message";
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String trim(String body) {
        if (body == null || body.isBlank()) {
            return "empty response body";
        }
        String compact = body.replaceAll("\\s+", " ").trim();
        return compact.length() <= 500 ? compact : compact.substring(0, 500) + "...";
    }

    private String escape(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String escapeAttribute(String value) {
        return escape(value).replace("\"", "&quot;");
    }

    private record PasswordResetContent(String subject, String text, String html) {
    }
}
