package za.co.vlugboek.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import za.co.vlugboek.repo.ClubRepository;
import za.co.vlugboek.repo.FederationRepository;
import za.co.vlugboek.repo.LoftRepository;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:organisationadmintest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "vlugboek.seed.pdf-root=target/missing-test-pdfs",
        "vlugboek.storage.uploads-dir=target/test-uploads"
})
@AutoConfigureMockMvc
class OrganisationAdminTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FederationRepository federations;

    @Autowired
    private ClubRepository clubs;

    @Autowired
    private LoftRepository lofts;

    @Test
    void onlyAdminsCanManageOrganisations() throws Exception {
        String userToken = login("demo@vlugboek.local", "demo123");
        String adminToken = login("admin@vlugboek.local", "admin123");

        mvc.perform(get("/api/admin/organisations")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/admin/organisations")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.federations").isArray());
    }

    @Test
    void adminCanCreateAndRemoveUnlinkedOrganisationRecords() throws Exception {
        String adminToken = login("admin@vlugboek.local", "admin123");

        MvcResult federationResult = mvc.perform(post("/api/admin/federations")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"TEST","name":"Test Federation"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("TEST"))
                .andReturn();
        long federationId = objectMapper.readTree(federationResult.getResponse().getContentAsString()).get("id").asLong();

        MvcResult clubResult = mvc.perform(post("/api/admin/clubs")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"federationId":%d,"name":"Test Club"}
                                """.formatted(federationId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Test Club"))
                .andReturn();
        long clubId = objectMapper.readTree(clubResult.getResponse().getContentAsString()).get("id").asLong();

        MvcResult loftResult = mvc.perform(post("/api/admin/lofts")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clubId":%d,"name":"Test Loft"}
                                """.formatted(clubId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Test Loft"))
                .andReturn();
        long loftId = objectMapper.readTree(loftResult.getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(put("/api/admin/lofts/" + loftId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Renamed Loft"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed Loft"));

        mvc.perform(delete("/api/admin/lofts/" + loftId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/admin/clubs/" + clubId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/admin/federations/" + federationId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void linkedOrganisationRecordsCannotBeChanged() throws Exception {
        String adminToken = login("admin@vlugboek.local", "admin123");
        long pwdfId = federations.findByCode("PWDF").orElseThrow().getId();
        long magaliesId = clubs.findByNameAndFederationId("Magalies", pwdfId).orElseThrow().getId();
        long malanLoftId = lofts.findByNameAndClubId("Malan Racing", magaliesId).orElseThrow().getId();

        mvc.perform(put("/api/admin/federations/" + pwdfId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"PWDF","name":"Renamed PWDF"}
                                """))
                .andExpect(status().isBadRequest());

        mvc.perform(delete("/api/admin/lofts/" + malanLoftId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void systemAdminSetsFederationAdminWhoCanLoadFanciers() throws Exception {
        String adminToken = login("admin@vlugboek.local", "admin123");
        long pwdfId = federations.findByCode("PWDF").orElseThrow().getId();

        mvc.perform(put("/api/admin/federations/" + pwdfId + "/admin")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"fed-admin@example.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.federationAdmin.email").value("fed-admin@example.com"))
                .andExpect(jsonPath("$.federationAdmin.registered").value(false));

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"fed-admin@example.com","password":"secret123"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Please register before signing in"));

        MvcResult fedAdminRegistration = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"fed-admin@example.com","password":"secret123","displayName":"Fed Admin"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("FEDERATION_ADMIN"))
                .andReturn();
        String federationAdminToken = objectMapper.readTree(fedAdminRegistration.getResponse().getContentAsString()).get("token").asText();

        mvc.perform(post("/api/admin/federations")
                        .header("Authorization", "Bearer " + federationAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"NOPE","name":"Should Fail"}
                                """))
                .andExpect(status().isForbidden());

        MvcResult clubResult = mvc.perform(post("/api/admin/clubs")
                        .header("Authorization", "Bearer " + federationAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"federationId":%d,"name":"Federation Managed Club"}
                                """.formatted(pwdfId)))
                .andExpect(status().isCreated())
                .andReturn();
        long clubId = objectMapper.readTree(clubResult.getResponse().getContentAsString()).get("id").asLong();

        MvcResult loftResult = mvc.perform(post("/api/admin/lofts")
                        .header("Authorization", "Bearer " + federationAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clubId":%d,"name":"Federation Managed Loft"}
                                """.formatted(clubId)))
                .andExpect(status().isCreated())
                .andReturn();
        long loftId = objectMapper.readTree(loftResult.getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(post("/api/admin/preloaded-users")
                        .header("Authorization", "Bearer " + federationAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"loaded-fancier@example.com","federationId":%d,"clubId":%d,"loftId":%d}
                                """.formatted(pwdfId, clubId, loftId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.registered").value(false))
                .andExpect(jsonPath("$.loft.name").value("Federation Managed Loft"));

        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"loaded-fancier@example.com","password":"secret123","displayName":"Loaded Fancier"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.loft.name").value("Federation Managed Loft"));
    }

    @Test
    void registrationRejectsMismatchedOrganisationSelection() throws Exception {
        String adminToken = login("admin@vlugboek.local", "admin123");
        long pwdfId = federations.findByCode("PWDF").orElseThrow().getId();
        long magaliesId = clubs.findByNameAndFederationId("Magalies", pwdfId).orElseThrow().getId();
        long malanLoftId = lofts.findByNameAndClubId("Malan Racing", magaliesId).orElseThrow().getId();
        long zwartkopsId = clubs.findByNameAndFederationId("Zwartkops", pwdfId).orElseThrow().getId();
        long boshoffLoftId = lofts.findByNameAndClubId("Boshoff & Seun", zwartkopsId).orElseThrow().getId();

        mvc.perform(post("/api/admin/preloaded-users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"bad-org@example.com","federationId":%d,"clubId":%d,"loftId":%d}
                                """.formatted(pwdfId, magaliesId, malanLoftId)))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"bad-org@example.com","password":"secret123","displayName":"Bad Org","federationId":%d,"clubId":%d,"loftId":%d}
                                """.formatted(pwdfId, zwartkopsId, boshoffLoftId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Club does not match your preloaded profile"));
    }

    @Test
    void registrationRequiresPreloadedEmail() throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"missing-org@example.com","password":"secret123","displayName":"Missing Org"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Your email address has not been loaded by your federation admin"));
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
}
