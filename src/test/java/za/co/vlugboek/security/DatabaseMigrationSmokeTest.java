package za.co.vlugboek.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:migrationtest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "vlugboek.seed.reference-data-enabled=false",
        "vlugboek.seed.pdf-import-enabled=false",
        "vlugboek.seed.admin-enabled=false",
        "vlugboek.seed.demo-users-enabled=false"
})
class DatabaseMigrationSmokeTest {
    @Autowired
    private DataSource dataSource;

    @Test
    void flywayCreatesTheMvpSchemaOnACleanDatabase() throws Exception {
        try (Connection connection = dataSource.getConnection();
             ResultSet tables = connection.getMetaData().getTables(null, "public", "documents", null);
             ResultSet history = connection.getMetaData().getTables(null, "public", "flyway_schema_history", null)) {
            assertThat(tables.next()).isTrue();
            assertThat(history.next()).isTrue();
        }
    }
}
