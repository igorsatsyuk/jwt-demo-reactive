package lt.satsyuk.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link FlywayMigrationConfig} creates a {@link Flyway} bean and applies
 * all migrations when running in a pure R2DBC context where no JDBC {@link DataSource}
 * bean is present — the bootstrapping scenario the config was introduced to fix.
 *
 * <p>Both {@code DataSourceAutoConfiguration} and {@code FlywayAutoConfiguration} are
 * excluded so that:
 * <ul>
 *   <li>no JDBC {@link DataSource} bean is created (simulating the R2DBC-only scenario)</li>
 *   <li>the {@link Flyway} bean can only originate from {@link FlywayMigrationConfig},
 *       making the assertion meaningful</li>
 * </ul>
 */
@ActiveProfiles("test")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                // Simulate pure R2DBC environment: exclude JDBC DataSource auto-configs so no
                // DataSource bean is created. Also exclude FlywayAutoConfiguration so the Flyway
                // bean can only be provided by FlywayMigrationConfig — making the assertion
                // that the bean comes from our config unambiguous.
                "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration," +
                "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration," +
                "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
        })
class FlywayMigrationConfigIT {

    // Container is not annotated with @Container / @Testcontainers — it is started explicitly
    // inside @DynamicPropertySource (same pattern as AbstractIntegrationTest) to guarantee
    // the container is running before Spring resolves property values from the suppliers.
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("flyway_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        ensureContainerStarted();
        registry.add("spring.r2dbc.url", () ->
                "r2dbc:postgresql://" + postgres.getHost() + ":" + postgres.getFirstMappedPort() + "/flyway_test");
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
        // Flyway connects directly over JDBC — no DataSource bean involved
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }

    private static synchronized void ensureContainerStarted() {
        if (!postgres.isRunning()) {
            postgres.start();
        }
    }

    @Autowired
    ApplicationContext context;

    @Autowired
    Flyway flyway;

    @Test
    void noDataSourceBeanExistsInPureR2dbcContext() {
        assertThat(context.getBeanNamesForType(DataSource.class))
                .as("no JDBC DataSource bean should be present when DataSourceAutoConfiguration is excluded")
                .isEmpty();
    }

    @Test
    void flywayBeanIsCreatedByFlywayMigrationConfig() {
        // FlywayAutoConfiguration is excluded — the Flyway bean can only come from FlywayMigrationConfig
        assertThat(flyway)
                .as("FlywayMigrationConfig should have created a Flyway bean")
                .isNotNull();
    }

    @Test
    void allMigrationsAreAppliedSuccessfully() {
        long versionedMigrationCount = Arrays.stream(flyway.info().all())
                .filter(info -> info.getVersion() != null)
                .count();

        long successCount = Arrays.stream(flyway.info().all())
                .filter(info -> info.getVersion() != null)
                .filter(info -> info.getState() == MigrationState.SUCCESS)
                .count();

        assertThat(versionedMigrationCount)
                .as("there should be at least one versioned migration to validate")
                .isGreaterThan(0);

        assertThat(successCount)
                .as("all versioned migrations should have been applied with SUCCESS state")
                .isEqualTo(versionedMigrationCount);
    }
}
