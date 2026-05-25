package za.co.vlugboek.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class BuildInfoService {
    private final String version;
    private final String release;
    private final String buildTime;
    private final String commit;

    public BuildInfoService(
            @Value("${vlugboek.build.version:0.0.1-SNAPSHOT}") String configuredVersion,
            @Value("${VLUGBOEK_RELEASE:}") String environmentRelease,
            @Value("${VLUGBOEK_BUILD_TIME:}") String environmentBuildTime,
            @Value("${VLUGBOEK_BUILD_COMMIT:}") String environmentCommit
    ) {
        Properties releaseInfo = readReleaseInfo();
        this.version = valueOrDefault(releaseInfo.getProperty("version"), configuredVersion);
        this.release = valueOrDefault(environmentRelease, valueOrDefault(releaseInfo.getProperty("release"), "local"));
        this.buildTime = valueOrDefault(environmentBuildTime, valueOrDefault(releaseInfo.getProperty("buildTime"), "unknown"));
        this.commit = valueOrDefault(environmentCommit, valueOrDefault(releaseInfo.getProperty("commit"), "unknown"));
    }

    public Map<String, Object> healthDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("version", version);
        details.put("release", release);
        details.put("buildTime", buildTime);
        details.put("commit", commit);
        details.put("checkedAt", Instant.now().toString());
        return details;
    }

    private Properties readReleaseInfo() {
        Properties properties = new Properties();
        Path path = Path.of("app", "release-info.properties");
        if (!Files.isRegularFile(path)) {
            return properties;
        }
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        } catch (IOException ignored) {
            return new Properties();
        }
        return properties;
    }

    private String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
