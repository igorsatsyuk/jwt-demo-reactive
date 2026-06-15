package lt.satsyuk.api.util;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import lt.satsyuk.api.integrationtest.AbstractIntegrationTest;
import org.awaitility.Awaitility;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public abstract class KeycloakIntegrationTest extends AbstractIntegrationTest {

    private static final String REALM = "my-realm";
    private static final String RESOURCE_CLIENT_ID = "resource-server";
    private static final String RESOURCE_CLIENT_SECRET = "A64B28FBDC31B2BC068CAFC793DB5FEA";

    @Container
    protected static final KeycloakContainer keycloak = new KeycloakContainer("quay.io/keycloak/keycloak:26.0.0")
            .withRealmImportFile("keycloak/realm-export.json");

    @DynamicPropertySource
    static void keycloakProperties(DynamicPropertyRegistry registry) {
        if (!keycloak.isRunning()) {
            keycloak.start();
        }

        String authServerUrl = keycloak.getAuthServerUrl();
        awaitRealmReady(authServerUrl);
        String tokenUrl = authServerUrl + "/realms/" + REALM + "/protocol/openid-connect/token";
        String logoutUrl = authServerUrl + "/realms/" + REALM + "/protocol/openid-connect/logout";
        String introspectionUrl = authServerUrl + "/realms/" + REALM + "/protocol/openid-connect/token/introspect";

        registry.add("keycloak.auth-server-url", () -> authServerUrl);
        registry.add("keycloak.realm", () -> REALM);
        registry.add("keycloak.token-url", () -> tokenUrl);
        registry.add("keycloak.logout-url", () -> logoutUrl);
        registry.add("keycloak.introspection-url", () -> introspectionUrl);
        registry.add("keycloak.resource-client-id", () -> RESOURCE_CLIENT_ID);
        registry.add("keycloak.resource-client-secret", () -> RESOURCE_CLIENT_SECRET);
        registry.add("spring.security.oauth2.resourceserver.opaque-token.introspection-uri", () -> introspectionUrl);
        registry.add("spring.security.oauth2.resourceserver.opaque-token.client-id", () -> RESOURCE_CLIENT_ID);
        registry.add("spring.security.oauth2.resourceserver.opaque-token.client-secret", () -> RESOURCE_CLIENT_SECRET);
    }

    private static void awaitRealmReady(String authServerUrl) {
        String realmConfigUrl = authServerUrl + "/realms/" + REALM + "/.well-known/openid-configuration";
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        Awaitility.await()
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .ignoreExceptions()
                .until(() -> {
                    HttpRequest request = HttpRequest.newBuilder(URI.create(realmConfigUrl))
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build();
                    HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                    return response.statusCode() == 200;
                });
    }
}

