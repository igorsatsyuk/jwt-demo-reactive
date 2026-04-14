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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link FlywayMigrationConfig} creates a {@link Flyway} bean and applies
 * all migrations when running in a pure R2DBC context where no JDBC {@link DataSource}
 * bean is present — the bootstrapping scenario the config was introduced to fix.
 *
 * <p>JDBC {@code DataSourceAutoConfiguration} is excluded so that
 * {@code FlywayMigrationConfig}'s {@code @ConditionalOnMissingBean(DataSource.class)}
 * condition is satisfied and the bean is actually created by our configuration rather
 * than by Spring Boot's Flyway auto-configuration.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                // Simulate pure R2DBC environment: exclude all JDBC DataSource auto-configs
                "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration," +
                "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration"
        })
class FlywayMigrationConfigIT {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("flyway_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () ->
                "r2dbc:postgresql://" + postgres.getHost() + ":" + postgres.getFirstMappedPort() + "/flyway_test");
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
        // Flyway connects directly over JDBC — no DataSource bean involved
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
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
        assertThat(flyway)
                .as("FlywayMigrationConfig should have created a Flyway bean")
                .isNotNull();
    }

    @Test
    void allMigrationsAreAppliedSuccessfully() {
        long successCount = Arrays.stream(flyway.info().all())
                .filter(info -> info.getState() == MigrationState.SUCCESS)
                .count();

        assertThat(successCount)
                .as("all versioned migrations should have been applied with SUCCESS state")
                .isGreaterThan(0);
    }
}

