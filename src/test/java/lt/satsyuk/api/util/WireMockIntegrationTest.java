package lt.satsyuk.api.util;

import com.github.tomakehurst.wiremock.WireMockServer;
import lt.satsyuk.api.integrationtest.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

public abstract class WireMockIntegrationTest extends AbstractIntegrationTest {

    protected static final String REALM = "test-realm";
    protected static final String RESOURCE_CLIENT_ID = "resource-server";
    protected static final String RESOURCE_CLIENT_SECRET = "resource-server-secret";

    protected static final String TOKEN_PATH = "/realms/" + REALM + "/protocol/openid-connect/token";
    protected static final String LOGOUT_PATH = "/realms/" + REALM + "/protocol/openid-connect/logout";
    protected static final String INTROSPECT_PATH = "/realms/" + REALM + "/protocol/openid-connect/token/introspect";

    protected static final WireMockServer wireMockServer = new WireMockServer(options().dynamicPort());

    @DynamicPropertySource
    static void wireMockProperties(DynamicPropertyRegistry registry) {
        if (!wireMockServer.isRunning()) {
            wireMockServer.start();
        }

        String baseUrl = wireMockServer.baseUrl();
        String tokenUrl = baseUrl + TOKEN_PATH;
        String logoutUrl = baseUrl + LOGOUT_PATH;
        String introspectionUrl = baseUrl + INTROSPECT_PATH;

        registry.add("keycloak.auth-server-url", () -> baseUrl);
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

    @BeforeEach
    void setupWireMock() {
        wireMockServer.resetAll();
        configureFor("localhost", wireMockServer.port());

        stubFor(post(urlEqualTo(INTROSPECT_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(CONTENT_TYPE, APPLICATION_JSON_VALUE)
                        .withBody("""
                                {
                                  "active": true,
                                  "username": "user",
                                  "client_id": "spring-app",
                                  "azp": "spring-app",
                                  "realm_access": {"roles": ["CLIENT_CREATE", "CLIENT_GET", "CLIENT_SEARCH", "UPDATE_BALANCE"]},
                                  "resource_access": {"spring-app": {"roles": ["CLIENT_CREATE", "CLIENT_GET", "CLIENT_SEARCH", "UPDATE_BALANCE"]}}
                                }
                                """)));
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer.isRunning()) {
            wireMockServer.stop();
        }
    }
}

