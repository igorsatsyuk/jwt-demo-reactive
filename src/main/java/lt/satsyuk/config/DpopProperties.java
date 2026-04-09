package lt.satsyuk.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties(prefix = "security.dpop")
public class DpopProperties {

    private boolean enabled = true;
    private Duration maxProofAge = Duration.ofMinutes(5);
    private Duration clockSkew = Duration.ofSeconds(30);
    private long replayCacheSize = 100_000;
}
