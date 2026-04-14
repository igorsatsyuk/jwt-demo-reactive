package lt.satsyuk.config;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Explicit Flyway migration configuration for reactive applications.
 * <p>
 * Spring Boot 4's {@code FlywayAutoConfiguration} requires a JDBC {@code DataSource} bean,
 * which may not be created when R2DBC ({@code ConnectionFactory}) is the primary data access
 * mechanism. This configuration bypasses auto-configuration by creating and running Flyway
 * programmatically — the same pattern used by integration tests ({@code ensureSchemaMigrated}).
 * <p>
 * The bean is only activated when {@code spring.flyway.url} is explicitly configured, and backs
 * off if another {@code Flyway} bean (e.g., from auto-configuration in test context) is already
 * present.
 */
@Configuration
@Slf4j
public class FlywayMigrationConfig {

    @Bean
    @ConditionalOnProperty(name = "spring.flyway.url")
    @ConditionalOnMissingBean(Flyway.class)
    public Flyway flyway(
            @Value("${spring.flyway.url}") String url,
            @Value("${spring.flyway.user}") String user,
            @Value("${spring.flyway.password}") String password,
            @Value("${spring.flyway.locations:classpath:db/migration}") String locations,
            @Value("${spring.flyway.connect-retries:0}") int connectRetries
    ) {
        log.info("Running Flyway migrations: url={}", url);
        Flyway flyway = Flyway.configure()
                .dataSource(url, user, password)
                .locations(locations)
                .connectRetries(connectRetries)
                .load();
        flyway.migrate();
        log.info("Flyway migrations completed successfully");
        return flyway;
    }
}

