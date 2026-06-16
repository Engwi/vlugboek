package za.co.vlugboek.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import za.co.vlugboek.repo.DocumentRepository;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ingestiontest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "vlugboek.seed.pdf-root=target/missing-test-pdfs",
        "vlugboek.storage.uploads-dir=target/test-ingestion-uploads",
        "vlugboek.ingestion.root=target/test-ingestion"
})
@AutoConfigureMockMvc
class IngestionPipelineSmokeTest {
    private static final Path INGESTION_ROOT = Path.of("target", "test-ingestion");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DocumentRepository documents;

    @BeforeEach
    void resetInbox() throws IOException {
        deleteRecursively(INGESTION_ROOT);
        Files.createDirectories(INGESTION_ROOT.resolve("inbox"));
    }

    @Test
    void systemAdminCanRunIngestionFromInbox() throws Exception {
        Files.copy(
                Path.of("Docs", "Uitslae", "# 3 Wedvlugte", "De Aar 1 JO.pdf"),
                INGESTION_ROOT.resolve("inbox").resolve("De Aar 1 JO.pdf")
        );
        String adminToken = login("admin@vlugboek.local", "admin123");

        MvcResult result = mvc.perform(post("/api/admin/ingestion-runs")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"effectiveDate":"2026-06-13"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalFiles").value(1))
                .andExpect(jsonPath("$.importedCount").value(1))
                .andExpect(jsonPath("$.items[0].status").value("IMPORTED"))
                .andReturn();

        org.assertj.core.api.Assertions.assertThat(documents.findFirstByOriginalFilenameOrderByUploadedAtDesc("De Aar 1 JO.pdf").orElseThrow().getOfficialDate())
                .isEqualTo(LocalDate.of(2026, 6, 13));

        long runId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(get("/api/admin/ingestion-runs/" + runId + "/report.html")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Effective date: 2026-06-13")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("De Aar 1 JO.pdf")));
    }

    @Test
    void normalUserCannotRunIngestion() throws Exception {
        String userToken = login("demo@vlugboek.local", "demo123");

        mvc.perform(post("/api/admin/ingestion-runs")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"effectiveDate":"2026-06-13"}
                                """))
                .andExpect(status().isForbidden());
    }

    private String login(String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s","language":"en"}
                                """.formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
