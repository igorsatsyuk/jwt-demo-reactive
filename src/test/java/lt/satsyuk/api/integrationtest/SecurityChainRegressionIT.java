package lt.satsyuk.api.integrationtest;

import lt.satsyuk.api.util.WireMockIntegrationTest;
import lt.satsyuk.dto.AppResponse;
import lt.satsyuk.dto.KeycloakTokenResponse;
import lt.satsyuk.dto.LoginRequest;
import lt.satsyuk.security.DpopProofValidator;
import lt.satsyuk.security.RateLimitingWebFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

@TestPropertySource(properties = {
        "app.rate-limit.rules[0].id=login",
        "app.rate-limit.rules[0].enabled=true",
        "app.rate-limit.rules[0].order=0",
        "app.rate-limit.rules[0].path-pattern=/api/auth/login",
        "app.rate-limit.rules[0].methods[0]=POST",
        "app.rate-limit.rules[0].key-strategy=IP",
        "app.rate-limit.rules[0].cache-name=loginBuckets",
        "app.rate-limit.rules[0].capacity=3",
        "app.rate-limit.rules[0].window-seconds=20",
        "app.rate-limit.rules[1].id=clients",
        "app.rate-limit.rules[1].enabled=true",
        "app.rate-limit.rules[1].order=1",
        "app.rate-limit.rules[1].path-pattern=/api/clients/**",
        "app.rate-limit.rules[1].client-ids[0]=spring-app",
        "app.rate-limit.rules[1].key-strategy=CLIENT_ID",
        "app.rate-limit.rules[1].cache-name=clientsBuckets",
        "app.rate-limit.rules[1].capacity=20",
        "app.rate-limit.rules[1].window-seconds=20"
})
class SecurityChainRegressionIT extends WireMockIntegrationTest {

    @Autowired
    private RateLimitingWebFilter rateLimitingWebFilter;

    @MockitoBean
    private DpopProofValidator dpopProofValidator;

    @BeforeEach
    void beforeEach() {
        rateLimitingWebFilter.clearBuckets();
    }

    @Test
    void rate_limiting_filter_is_applied_once_per_login_request() {
        stubTokenEndpointSuccess();

        login().expectStatus().isOk();
        login().expectStatus().isOk();
        login().expectStatus().isOk();

        EntityExchangeResult<AppResponse<KeycloakTokenResponse>> blocked = login()
                .expectStatus().isEqualTo(429)
                .expectBody(new ParameterizedTypeReference<AppResponse<KeycloakTokenResponse>>() {})
                .returnResult();

        assertThat(blocked.getResponseBody()).isNotNull();
        assertThat(blocked.getResponseBody().code()).isEqualTo(AppResponse.ErrorCode.TOO_MANY_REQUESTS.getCode());
    }

    @Test
    void dpop_filter_validates_proof_once_for_single_protected_request() {
        doNothing().when(dpopProofValidator)
                .validate(anyString(), anyString(), anyString(), anyString(), anyString());
        stubIntrospectionWithBoundToken("dpop-token", "expected-jkt");

        webTestClient.get()
                .uri("/api/accounts/client/999999")
                .header(AUTHORIZATION, "DPoP dpop-token")
                .header("DPoP", "proof-1")
                .exchange()
                .expectStatus().isNotFound();

        verify(dpopProofValidator, times(1)).validate(
                eq("GET"),
                anyString(),
                eq("dpop-token"),
                eq("proof-1"),
                eq("expected-jkt")
        );
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec login() {
        return webTestClient.post()
                .uri("/api/auth/login")
                .bodyValue(new LoginRequest(USERNAME, USER_PASSWORD, "test-client", "test-secret"))
                .exchange();
    }

    private void stubTokenEndpointSuccess() {
        stubFor(post(urlEqualTo(TOKEN_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(CONTENT_TYPE, APPLICATION_JSON_VALUE)
                        .withBody("""
                                {
                                  "access_token": "access",
                                  "refresh_token": "refresh",
                                  "token_type": "Bearer"
                                }
                                """)));
    }

    private void stubIntrospectionWithBoundToken(String token, String jkt) {
        stubFor(post(urlEqualTo(INTROSPECT_PATH))
                .withRequestBody(containing("token=" + token))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(CONTENT_TYPE, APPLICATION_JSON_VALUE)
                        .withBody("""
                                {
                                  "active": true,
                                  "username": "user",
                                  "client_id": "spring-app",
                                  "azp": "spring-app",
                                  "cnf": {"jkt": "%s"},
                                  "realm_access": {"roles": ["CLIENT_GET"]},
                                  "resource_access": {"spring-app": {"roles": ["CLIENT_GET"]}}
                                }
                                """.formatted(jkt))));
    }
}

