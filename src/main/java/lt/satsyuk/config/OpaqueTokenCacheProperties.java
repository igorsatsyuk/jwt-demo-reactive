package lt.satsyuk.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "security.opaque-token.cache")
public class OpaqueTokenCacheProperties {

    private boolean enabled = true;

    @NotNull
    private Duration ttl = Duration.ofSeconds(10);

    @Min(1)
    private long maxSize = 10_000;
}
