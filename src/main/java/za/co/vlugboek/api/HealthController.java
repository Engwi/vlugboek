package za.co.vlugboek.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.vlugboek.service.BuildInfoService;

@RestController
public class HealthController {
    private final BuildInfoService buildInfoService;

    public HealthController(BuildInfoService buildInfoService) {
        this.buildInfoService = buildInfoService;
    }

    @GetMapping({"/healthz", "/api/healthz"})
    public Map<String, Object> healthz() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("service", "vlugboek");
        response.put("time", Instant.now().toString());
        response.put("build", buildInfoService.healthDetails());
        return response;
    }
}
