package lt.satsyuk.api.integrationtest;

import lt.satsyuk.dto.LoginRequest;
import lt.satsyuk.exception.KeycloakAuthException;
import lt.satsyuk.service.KeycloakReactiveAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * Smoke tests for AuthController.
 * Note: In a real integration test, you'd typically mock or stub the Keycloak endpoint.
 */
class AuthControllerIT extends AbstractIntegrationTest {

    @MockitoBean
    private KeycloakReactiveAuthService authService;

    @Test
    void testLoginEndpointReturns400OnBadCredentials() {
        LoginRequest request = new LoginRequest("testuser", "password", "spring-app", "spring-app-secret");
        when(authService.login(any(LoginRequest.class), isNull()))
                .thenReturn(Mono.error(new KeycloakAuthException("Login failed", HttpStatus.UNAUTHORIZED, "invalid_grant")));

        webTestClient.post()
                .uri("/api/auth/login")
                .bodyValue(request)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo(40101)
                .jsonPath("$.message").isEqualTo("invalid_grant");
    }

    @Test
    void testLoginEndpointReturnsValidationErrorOnMissingFields() {
        webTestClient.post()
                .uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo(40001);
    }
}

