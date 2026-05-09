package lt.satsyuk.api.integrationtest;

import lt.satsyuk.dto.AppResponse;
import lt.satsyuk.dto.KeycloakTokenResponse;
import lt.satsyuk.dto.LoginRequest;
import lt.satsyuk.dto.LogoutRequest;
import lt.satsyuk.dto.RefreshRequest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    // -------------------------------------------------------------------------
    // SHARED CONSTANTS
    // -------------------------------------------------------------------------
    protected static final String USERNAME        = "user";
    protected static final String USER_PASSWORD   = "password";
    protected static final String ADMIN           = "admin";
    protected static final String ADMIN_PASSWORD  = "admin";
    /** Default OAuth2 client used in realm-export.json */
    protected static final String CLIENT_ID       = "spring-app";
    protected static final String CLIENT_SECRET   = "vYbuDDmT4ouy6vBn6ZzaEPkmaMSHfvab";
    protected static final String INVALID_GRANT   = "invalid_grant";
    protected static final String INVALID_CLIENT  = "invalid_client";
    protected static final String API_AUTH_LOGIN = "/api/auth/login";
    protected static final String API_AUTH_REFRESH = "/api/auth/refresh";
    protected static final String API_AUTH_LOGOUT = "/api/auth/logout";
    protected static final String API_ACCOUNTS_CLIENT = "/api/accounts/client/";
    protected static final String API_ACCOUNTS_BY_CLIENT_ID = API_ACCOUNTS_CLIENT + "{clientId}";
    protected static final String API_CLIENTS = "/api/clients";
    protected static final String API_REQUESTS_ID = "/api/requests/{id}";


    // -------------------------------------------------------------------------
    // POSTGRES TESTCONTAINER
    // -------------------------------------------------------------------------
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("appdb")
            .withUsername("app")
            .withPassword("app");

    @LocalServerPort
    private int port;

    protected WebTestClient webTestClient;

    @BeforeEach
    void initializeWebTestClient() {
        ensureSchemaMigrated();
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    private void ensureSchemaMigrated() {
        ensurePostgresStarted();
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    /** Only dynamic (port-bearing) Postgres props — static Keycloak defaults live in application-test.properties */
    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        ensurePostgresStarted();
        registry.add("spring.r2dbc.url", () ->
                "r2dbc:postgresql://" + postgres.getHost() + ":" + postgres.getFirstMappedPort() + "/appdb");
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }

    private static synchronized void ensurePostgresStarted() {
        if (!postgres.isRunning()) {
            postgres.start();
        }
    }

    // =========================================================================
    // AUTH HELPER METHODS  (WebTestClient-based, reusable in subclass ITs)
    // =========================================================================

    /** POST /api/auth/login — returns raw exchange result (status + body). */
    protected EntityExchangeResult<AppResponse<KeycloakTokenResponse>> loginExchange(
            String username, String password, String clientId, String clientSecret) {
        return webTestClient.post()
                .uri(API_AUTH_LOGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new LoginRequest(username, password, clientId, clientSecret))
                .exchange()
                .expectBody(new ParameterizedTypeReference<AppResponse<KeycloakTokenResponse>>() {})
                .returnResult();
    }

    protected EntityExchangeResult<AppResponse<KeycloakTokenResponse>> loginExchange(
            String username, String password) {
        return loginExchange(username, password, CLIENT_ID, CLIENT_SECRET);
    }

    protected void loginAndAssertOk(String username, String password) {
        KeycloakTokenResponse data = loginAndGetData(username, password);
        assertThat(data).isNotNull();
        assertThat(data.getAccessToken()).isNotBlank();
        assertThat(data.getRefreshToken()).isNotBlank();
    }

    /** POST /api/auth/login — asserts 200 and returns the token payload. */
    protected KeycloakTokenResponse loginAndGetData(String username, String password) {
        EntityExchangeResult<AppResponse<KeycloakTokenResponse>> result =
                loginExchange(username, password);
        assertThat(result.getStatus().value()).as("login HTTP status").isEqualTo(200);
        assertThat(result.getResponseBody()).isNotNull();
        return result.getResponseBody().data();
    }

    protected String loginAndGetRefresh(String username, String password) {
        return loginAndGetData(username, password).getRefreshToken();
    }

    /** POST /api/auth/refresh */
    protected EntityExchangeResult<AppResponse<KeycloakTokenResponse>> refreshExchange(
            String refreshToken, String clientId, String clientSecret) {
        return webTestClient.post()
                .uri(API_AUTH_REFRESH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new RefreshRequest(refreshToken, clientId, clientSecret))
                .exchange()
                .expectBody(new ParameterizedTypeReference<AppResponse<KeycloakTokenResponse>>() {})
                .returnResult();
    }

    /** POST /api/auth/logout */
    protected EntityExchangeResult<AppResponse<Void>> logoutExchange(
            String refreshToken, String clientId, String clientSecret) {
        return webTestClient.post()
                .uri(API_AUTH_LOGOUT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new LogoutRequest(refreshToken, clientId, clientSecret))
                .exchange()
                .expectBody(new ParameterizedTypeReference<AppResponse<Void>>() {})
                .returnResult();
    }

    // =========================================================================
    // ASSERTION HELPERS
    // =========================================================================

    protected <T> void assertErrorResult(EntityExchangeResult<AppResponse<T>> result,
                                         int expectedHttpStatus,
                                         int expectedCode,
                                         String expectedMessage) {
        assertThat(result.getStatus().value()).as("HTTP status").isEqualTo(expectedHttpStatus);
        AppResponse<T> body = result.getResponseBody();
        assertThat(body).as("response body").isNotNull();
        assertThat(body.code()).as("error code").isEqualTo(expectedCode);
        assertThat(body.message()).as("error message").isEqualTo(expectedMessage);
    }

    protected String absoluteUrl(String path) {
        return "http://localhost:" + port + path;
    }
}
