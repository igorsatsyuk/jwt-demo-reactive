package lt.satsyuk.config;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.flyway.autoconfigure.FlywayProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Explicit Flyway migration configuration for reactive applications.
 * <p>
 * Spring Boot 4's {@code FlywayAutoConfiguration} requires a JDBC {@code DataSource} bean,
 * which may not be created when R2DBC ({@code ConnectionFactory}) is the primary data access
 * mechanism. This configuration bypasses auto-configuration by creating and running Flyway
 * programmatically — the same pattern used by integration tests ({@code ensureSchemaMigrated}).
 * <p>
 * This configuration applies the subset of {@code spring.flyway.*} settings that are mapped
 * explicitly in the {@link org.flywaydb.core.api.configuration.FluentConfiguration} setup below
 * (datasource, locations, connect-retries, validate-on-migrate, out-of-order, baseline-on-migrate,
 * schemas, and table). Other Flyway settings exposed through {@link FlywayProperties} require one
 * or more {@link FlywayConfigurationCustomizer} beans to be applied.
 * <p>
 * The bean activates only when {@code spring.flyway.url} is explicitly set,
 * {@code spring.flyway.enabled} is {@code true} (default), no other {@code Flyway} bean is present
 * (e.g. from auto-configuration in test contexts), and no JDBC {@link DataSource} bean is present
 * (if a {@code DataSource} exists, Spring Boot's own Flyway auto-configuration takes over).
 */
@Configuration
@EnableConfigurationProperties(FlywayProperties.class)
@Slf4j
public class FlywayMigrationConfig {

    @Bean
    @ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnProperty(name = "spring.flyway.url")
    @ConditionalOnMissingBean({Flyway.class, DataSource.class})
    public Flyway flyway(FlywayProperties props,
                         ObjectProvider<FlywayConfigurationCustomizer> customizers) {
        log.info("Running Flyway migrations");

        FluentConfiguration config = Flyway.configure()
                .dataSource(props.getUrl(), props.getUser(), props.getPassword())
                .locations(props.getLocations().toArray(String[]::new))
                .connectRetries(props.getConnectRetries())
                .validateOnMigrate(props.isValidateOnMigrate())
                .outOfOrder(props.isOutOfOrder())
                .baselineOnMigrate(props.isBaselineOnMigrate());

        if (!props.getSchemas().isEmpty()) {
            config.schemas(props.getSchemas().toArray(String[]::new));
        }
        config.table(props.getTable());

        customizers.orderedStream().forEach(c -> c.customize(config));

        Flyway flyway = config.load();
        flyway.migrate();
        log.info("Flyway migrations completed");
        return flyway;
    }
}
