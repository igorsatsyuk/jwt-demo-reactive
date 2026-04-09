package lt.satsyuk.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "keycloak")
public class KeycloakProperties {

    private String authServerUrl;
    private String realm;

    private String tokenUrl;
    private String logoutUrl;
    private String introspectionUrl;

    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(2);

    // optional - defaults
    private String clientId;
    private String clientSecret;

    private String resourceClientId;
    private String resourceClientSecret;
}