package lt.satsyuk.api.integrationtest;

import com.github.tomakehurst.wiremock.http.Fault;
import lt.satsyuk.api.util.WireMockIntegrationTest;
import lt.satsyuk.dto.AppResponse;
import lt.satsyuk.dto.KeycloakTokenResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

class KeycloakNegativeIT extends WireMockIntegrationTest {

    @Test
    void login_keycloak_unavailable_500() {
        stubFor(post(urlEqualTo(TOKEN_PATH))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader(CONTENT_TYPE, APPLICATION_JSON_VALUE)
                        .withBody("{\"error\":\"internal_server_error\"}")));

        EntityExchangeResult<AppResponse<KeycloakTokenResponse>> result =
                loginExchange("user", "password", "test-client", "test-secret");

        assertErrorResult(result, 500, AppResponse.ErrorCode.UNAUTHORIZED.getCode(), INVALID_GRANT);
    }

    @Test
    void login_keycloak_timeout() {
        stubFor(post(urlEqualTo(TOKEN_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(3000)
                        .withHeader(CONTENT_TYPE, APPLICATION_JSON_VALUE)
                        .withBody("{}")));

        EntityExchangeResult<AppResponse<KeycloakTokenResponse>> result =
                loginExchange("user", "password", "test-client", "test-secret");

        assertErrorResult(result, 500, AppResponse.ErrorCode.UNAUTHORIZED.getCode(), INVALID_GRANT);
    }

    @Test
    void login_keycloak_malformed_response() {
        stubFor(post(urlEqualTo(TOKEN_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(CONTENT_TYPE, APPLICATION_JSON_VALUE)
                        .withBody("invalid-json")));

        EntityExchangeResult<AppResponse<KeycloakTokenResponse>> result =
                loginExchange("user", "password", "test-client", "test-secret");

        assertErrorResult(result, 500, AppResponse.ErrorCode.UNAUTHORIZED.getCode(), INVALID_GRANT);
    }

    @Test
    void login_invalid_credentials() {
        stubFor(post(urlEqualTo(TOKEN_PATH))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader(CONTENT_TYPE, APPLICATION_JSON_VALUE)
                        .withBody("{\"error\":\"invalid_grant\",\"error_description\":\"Invalid user credentials\"}")));

        EntityExchangeResult<AppResponse<KeycloakTokenResponse>> result =
                loginExchange("user", "wrongpassword", "test-client", "test-secret");

        assertErrorResult(result, 401, AppResponse.ErrorCode.UNAUTHORIZED.getCode(), INVALID_GRANT);
    }

    @Test
    void login_invalid_client() {
        stubFor(post(urlEqualTo(TOKEN_PATH))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader(CONTENT_TYPE, APPLICATION_JSON_VALUE)
                        .withBody("{\"error\":\"invalid_client\",\"error_description\":\"Invalid client credentials\"}")));

        EntityExchangeResult<AppResponse<KeycloakTokenResponse>> result =
                loginExchange("user", "password", "wrong-client", "wrong-secret");

        assertErrorResult(result, 401, AppResponse.ErrorCode.UNAUTHORIZED.getCode(), INVALID_CLIENT);
    }

    @Test
    void refresh_invalid_token() {
        stubFor(post(urlEqualTo(TOKEN_PATH))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader(CONTENT_TYPE, APPLICATION_JSON_VALUE)
                        .withBody("{\"error\":\"invalid_grant\"}")));

        EntityExchangeResult<AppResponse<KeycloakTokenResponse>> result =
                refreshExchange("invalid-refresh-token", "test-client", "test-secret");

        assertErrorResult(result, 400, AppResponse.ErrorCode.INVALID_GRANT.getCode(), INVALID_GRANT);
    }

    @Test
    void logout_invalid_token() {
        stubFor(post(urlEqualTo(LOGOUT_PATH))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader(CONTENT_TYPE, APPLICATION_JSON_VALUE)
                        .withBody("{\"error\":\"invalid_grant\"}")));

        EntityExchangeResult<AppResponse<Void>> result =
                logoutExchange("invalid-token", "test-client", "test-secret");

        assertErrorResult(result, 400, AppResponse.ErrorCode.INVALID_TOKEN.getCode(), INVALID_GRANT);
    }

    @Test
    void login_network_error() {
        stubFor(post(urlEqualTo(TOKEN_PATH))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        EntityExchangeResult<AppResponse<KeycloakTokenResponse>> result =
                loginExchange("user", "password", "test-client", "test-secret");

        assertErrorResult(result, 500, AppResponse.ErrorCode.UNAUTHORIZED.getCode(), INVALID_GRANT);
    }

    @Test
    void login_empty_response() {
        stubFor(post(urlEqualTo(TOKEN_PATH))
                .willReturn(aResponse().withStatus(204)));

        EntityExchangeResult<AppResponse<KeycloakTokenResponse>> result =
                loginExchange("user", "password", "test-client", "test-secret");

        assertErrorResult(result, 400, AppResponse.ErrorCode.UNAUTHORIZED.getCode(), INVALID_GRANT);
    }
}




