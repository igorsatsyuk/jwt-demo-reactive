package lt.satsyuk.api.integrationtest;

import lt.satsyuk.api.util.WireMockIntegrationTest;
import lt.satsyuk.config.RateLimitProperties;
import lt.satsyuk.dto.AppResponse;
import lt.satsyuk.dto.KeycloakTokenResponse;
import lt.satsyuk.dto.LoginRequest;
import lt.satsyuk.security.RateLimitingWebFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

import java.util.Comparator;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;
import static org.springframework.http.HttpHeaders.ACCEPT_LANGUAGE;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

@TestPropertySource(properties = {
        "app.rate-limit.rules[0].id=login",
        "app.rate-limit.rules[0].enabled=true",
        "app.rate-limit.rules[0].order=0",
        "app.rate-limit.rules[0].path-pattern=/api/auth/login",
        "app.rate-limit.rules[0].methods[0]=POST",
        "app.rate-limit.rules[0].key-strategy=IP",
        "app.rate-limit.rules[0].cache-name=loginBuckets",
        "app.rate-limit.rules[0].capacity=2",
        "app.rate-limit.rules[0].window-seconds=20",
        "app.rate-limit.rules[1].id=clients",
        "app.rate-limit.rules[1].enabled=true",
        "app.rate-limit.rules[1].order=1",
        "app.rate-limit.rules[1].path-pattern=/api/clients/**",
        "app.rate-limit.rules[1].client-ids[0]=spring-app",
        "app.rate-limit.rules[1].key-strategy=CLIENT_ID",
        "app.rate-limit.rules[1].cache-name=clientsBuckets",
        "app.rate-limit.rules[1].capacity=2",
        "app.rate-limit.rules[1].window-seconds=20"
})
class RateLimitingIT extends WireMockIntegrationTest {

    @Autowired
    private RateLimitingWebFilter rateLimitingWebFilter;

    @Autowired
    private RateLimitProperties rateLimitProperties;

    @BeforeEach
    void clearRateLimitBuckets() {
        rateLimitingWebFilter.clearBuckets();
    }

    @Test
    void rules_are_ordered_as_configured() {
        List<String> orderedIds = rateLimitProperties.getRules().stream()
                .sorted(Comparator.comparingInt(RateLimitProperties.Rule::getOrder)
                        .thenComparing(RateLimitProperties.Rule::getId))
                .map(RateLimitProperties.Rule::getId)
                .toList();

        assertThat(orderedIds).containsExactly("login", "clients");
    }

    @Test
    void login_rate_limit_uses_ip_key_and_returns_english_message() {
        stubTokenEndpointSuccess();

        doLogin("en").expectStatus().isOk();
        doLogin("en").expectStatus().isOk();

        EntityExchangeResult<AppResponse<KeycloakTokenResponse>> blocked = doLogin("en")
                .expectStatus().isEqualTo(429)
                .expectBody(new ParameterizedTypeReference<AppResponse<KeycloakTokenResponse>>() {})
                .returnResult();

        assertThat(blocked.getResponseBody()).isNotNull();
        assertThat(blocked.getResponseBody().code()).isEqualTo(AppResponse.ErrorCode.TOO_MANY_REQUESTS.getCode());
        assertThat(blocked.getResponseBody().message()).isEqualTo("Too many requests");
    }

    @Test
    void login_rate_limit_returns_russian_message_when_locale_is_ru() {
        stubTokenEndpointSuccess();

        doLogin("ru").expectStatus().isOk();
        doLogin("ru").expectStatus().isOk();

        EntityExchangeResult<AppResponse<KeycloakTokenResponse>> blocked = doLogin("ru")
                .expectStatus().isEqualTo(429)
                .expectBody(new ParameterizedTypeReference<AppResponse<KeycloakTokenResponse>>() {})
                .returnResult();

        assertThat(blocked.getResponseBody()).isNotNull();
        assertThat(blocked.getResponseBody().code()).isEqualTo(AppResponse.ErrorCode.TOO_MANY_REQUESTS.getCode());
        assertThat(blocked.getResponseBody().message()).isEqualTo("Слишком много запросов");
    }

    @Test
    void clients_rate_limit_uses_client_id_key_and_ignores_other_client() {
        stubIntrospectionForOtherClient("other-token", "mobile-app");

        requestClients("spring-token").expectStatus().isBadRequest();
        requestClients("spring-token").expectStatus().isBadRequest();

        EntityExchangeResult<AppResponse<Void>> blocked = requestClients("spring-token")
                .expectStatus().isEqualTo(429)
                .expectBody(new ParameterizedTypeReference<AppResponse<Void>>() {})
                .returnResult();

        assertThat(blocked.getResponseBody()).isNotNull();
        assertThat(blocked.getResponseBody().code()).isEqualTo(AppResponse.ErrorCode.TOO_MANY_REQUESTS.getCode());

        requestClients("other-token").expectStatus().isBadRequest();
        requestClients("other-token").expectStatus().isBadRequest();
        requestClients("other-token").expectStatus().isBadRequest();
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec doLogin(String language) {
        return webTestClient.post()
                .uri("/api/auth/login")
                .header(ACCEPT_LANGUAGE, language)
                .bodyValue(new LoginRequest(USERNAME, USER_PASSWORD, "test-client", "test-secret"))
                .exchange();
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec requestClients(String token) {
        return webTestClient.get()
                .uri("/api/clients/not-a-number")
                .header(AUTHORIZATION, "Bearer " + token)
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

    private void stubIntrospectionForOtherClient(String token, String clientId) {
        stubFor(post(urlEqualTo(INTROSPECT_PATH))
                .atPriority(1)
                .withRequestBody(containing("token=" + token))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(CONTENT_TYPE, APPLICATION_JSON_VALUE)
                        .withBody("""
                                {
                                  "active": true,
                                  "username": "user",
                                  "client_id": "%s",
                                  "azp": "%s",
                                  "realm_access": {"roles": ["CLIENT_GET", "CLIENT_CREATE"]},
                                  "resource_access": {"spring-app": {"roles": ["CLIENT_GET", "CLIENT_CREATE"]}}
                                }
                                """.formatted(clientId, clientId))));
    }
}
