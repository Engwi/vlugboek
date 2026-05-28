package za.co.vlugboek.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:securitytest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "vlugboek.seed.pdf-root=target/missing-test-pdfs",
        "vlugboek.storage.uploads-dir=target/test-uploads"
})
@AutoConfigureMockMvc
class SecuritySmokeTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void anonymousApiRequestsNeedAuthentication() throws Exception {
        mvc.perform(get("/api/dashboard"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginTokenCanReadProtectedReports() throws Exception {
        String token = login("demo@vlugboek.local", "demo123");

        mvc.perform(get("/api/dashboard")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void normalUsersCannotUploadDocuments() throws Exception {
        String token = login("demo@vlugboek.local", "demo123");
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "race.pdf",
                "application/pdf",
                "fake pdf".getBytes()
        );

        mvc.perform(multipart("/api/documents/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DirtiesContext
    void usersCanChangeTheirPassword() throws Exception {
        String token = login("demo@vlugboek.local", "demo123");

        mvc.perform(post("/api/profile/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"demo123","newPassword":"new-demo-123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"demo@vlugboek.local","password":"demo123","language":"en"}
                                """))
                .andExpect(status().isBadRequest());

        login("demo@vlugboek.local", "new-demo-123");
    }

    @Test
    void adminUploadsAreStagedUntilConfirmed() throws Exception {
        String adminToken = login("admin@vlugboek.local", "admin123");
        String userToken = login("demo@vlugboek.local", "demo123");
        MockMultipartFile file = pdfFixture();

        MvcResult upload = mvc.perform(multipart("/api/documents/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.document.status").value("RECOGNISED"))
                .andExpect(jsonPath("$.document.availableToUsers").value(false))
                .andExpect(jsonPath("$.document.federation.code").value("PWDF"))
                .andExpect(jsonPath("$.document.racePoint").value("Britstown"))
                .andExpect(jsonPath("$.document.clubNames").isNotEmpty())
                .andExpect(jsonPath("$.document.loftNames").isNotEmpty())
                .andExpect(jsonPath("$.dataset.rows").isArray())
                .andReturn();

        long documentId = objectMapper.readTree(upload.getResponse().getContentAsString())
                .get("document")
                .get("id")
                .asLong();

        mvc.perform(get("/api/reports")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + documentId + ")]").isEmpty());

        mvc.perform(post("/api/documents/" + documentId + "/confirm")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/documents/" + documentId + "/confirm")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.document.status").value("IMPORTED"))
                .andExpect(jsonPath("$.document.availableToUsers").value(true));

        mvc.perform(get("/api/reports")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + documentId + ")]").isNotEmpty());

        mvc.perform(get("/api/reports")
                        .param("family", "RACE_DETAIL")
                        .param("racePoint", "Britstown")
                        .param("dateFrom", "2025-08-01")
                        .param("dateTo", "2025-08-31")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + documentId + ")]").isNotEmpty());

        mvc.perform(get("/api/reports")
                        .param("racePoint", "De Aar")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + documentId + ")]").isEmpty());
    }

    @Test
    void duplicatePdfUploadsAreRejectedByContentHash() throws Exception {
        String adminToken = login("admin@vlugboek.local", "admin123");
        Path path = Path.of("Docs", "Uitslae", "# 3 Wedvlugte", "Christiana1JO.pdf");

        MvcResult upload = mvc.perform(multipart("/api/documents/upload")
                        .file(pdfFixture(path, "Christiana1JO.pdf"))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        long documentId = objectMapper.readTree(upload.getResponse().getContentAsString())
                .get("document")
                .get("id")
                .asLong();

        mvc.perform(multipart("/api/documents/upload")
                        .file(pdfFixture(path, "renamed-christiana.pdf"))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.documentId").value(documentId))
                .andExpect(jsonPath("$.documentTitle").value("Christiana 1 JO"));
    }

    private String login(String email, String password) throws Exception {
        String body = """
                {"email":"%s","password":"%s","language":"en"}
                """.formatted(email, password);

        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private MockMultipartFile pdfFixture() throws Exception {
        Path path = Path.of("Docs", "Uitslae", "# 3 Wedvlugte", "Britstown1OPE.pdf");
        return pdfFixture(path, "Britstown1OPE.pdf");
    }

    private MockMultipartFile pdfFixture(Path path, String filename) throws Exception {
        return new MockMultipartFile(
                "file",
                filename,
                "application/pdf",
                Files.readAllBytes(path)
        );
    }
}
