package lt.satsyuk.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import lt.satsyuk.config.KeycloakProperties;
import lt.satsyuk.dto.KeycloakTokenResponse;
import lt.satsyuk.dto.LoginRequest;
import lt.satsyuk.dto.LogoutRequest;
import lt.satsyuk.dto.RefreshRequest;
import lt.satsyuk.exception.KeycloakAuthException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;


@Service
@Slf4j
public class KeycloakReactiveAuthService {

    public static final String RESULT = "result";
    public static final String SUCCESS = "success";
    public static final String FAILURE = "failure";
    public static final String CLIENT_ID = "client_id";
    public static final String CLIENT_SECRET = "client_secret";
    public static final String INVALID_TOKEN = "invalid_token";
    public static final String INVALID_GRANT = "invalid_grant";
    public static final String REFRESH_TOKEN = "refresh_token";
    private static final String DPOP = "DPoP";
    private static final String GRANT_TYPE = "grant_type";
    private static final String SCOPE = "scope";
    private static final String OFFLINE_ACCESS = "offline_access";
    private static final String USERNAME = "username";
    private static final String PASSWORD = "password";
    private static final String INVALID_CLIENT = "invalid_client";
    private static final String NOT_ALLOWED = "not_allowed";
    private static final String LOGIN_FAILED = "Login failed";
    private static final String REFRESH_FAILED = "Refresh failed";
    private static final String LOGOUT_FAILED = "Logout failed";

    private final WebClient webClient;
    private final KeycloakProperties props;

    private final Counter loginSuccessCounter;
    private final Counter loginFailureCounter;
    private final Counter refreshSuccessCounter;
    private final Counter refreshFailureCounter;
    private final Counter logoutSuccessCounter;
    private final Counter logoutFailureCounter;

    public KeycloakReactiveAuthService(WebClient webClient, KeycloakProperties props, MeterRegistry registry) {
        this.webClient = webClient;
        this.props = props;

        this.loginSuccessCounter = Counter.builder("auth.login")
                .tag(RESULT, SUCCESS)
                .description("Successful login attempts")
                .register(registry);

        this.loginFailureCounter = Counter.builder("auth.login")
                .tag(RESULT, FAILURE)
                .description("Failed login attempts")
                .register(registry);

        this.refreshSuccessCounter = Counter.builder("auth.refresh")
                .tag(RESULT, SUCCESS)
                .description("Successful token refresh attempts")
                .register(registry);

        this.refreshFailureCounter = Counter.builder("auth.refresh")
                .tag(RESULT, FAILURE)
                .description("Failed token refresh attempts")
                .register(registry);

        this.logoutSuccessCounter = Counter.builder("auth.logout")
                .tag(RESULT, SUCCESS)
                .description("Successful logout attempts")
                .register(registry);

        this.logoutFailureCounter = Counter.builder("auth.logout")
                .tag(RESULT, FAILURE)
                .description("Failed logout attempts")
                .register(registry);
    }

    // ============================================================================
    // LOGIN
    // ============================================================================
    public Mono<KeycloakTokenResponse> login(LoginRequest req) {
        return login(req, null);
    }

    public Mono<KeycloakTokenResponse> login(LoginRequest req, String dpopProof) {
        MultiValueMap<String, String> form = buildLoginForm(req);

        log.info("➡️  LOGIN request to Keycloak: user={}, clientId={}, realm={}",
                req.username(), req.clientId(), props.getRealm());

        return webClient.post()
                .uri(props.getTokenUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .headers(h -> { if (dpopProof != null) h.add(DPOP, dpopProof); })
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono(KeycloakTokenResponse.class)
                .switchIfEmpty(Mono.error(new KeycloakAuthException(
                        LOGIN_FAILED,
                        HttpStatus.BAD_REQUEST,
                        INVALID_GRANT
                )))
                .doOnSuccess(response -> {
                    log.info("⬅️  TOKEN response: status=200");
                    loginSuccessCounter.increment();
                })
                .doOnError(WebClientResponseException.class, ex -> {
                    log.error("❌ LOGIN error: status={}, body={}",
                            ex.getStatusCode(), ex.getResponseBodyAsString());
                    loginFailureCounter.increment();
                })
                .onErrorMap(WebClientResponseException.class, this::mapLoginError)
                .onErrorMap(this::mapGeneralError);
    }

    private MultiValueMap<String, String> buildLoginForm(LoginRequest req) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add(CLIENT_ID, req.clientId());
        form.add(CLIENT_SECRET, req.clientSecret());
        form.add(GRANT_TYPE, PASSWORD);
        form.add(SCOPE, OFFLINE_ACCESS);
        form.add(USERNAME, req.username());
        form.add(PASSWORD, req.password());
        return form;
    }

    // ============================================================================
    // REFRESH
    // ============================================================================
    public Mono<KeycloakTokenResponse> refresh(RefreshRequest req) {
        return refresh(req, null);
    }

    public Mono<KeycloakTokenResponse> refresh(RefreshRequest req, String dpopProof) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add(CLIENT_ID, req.clientId());
        form.add(CLIENT_SECRET, req.clientSecret());
        form.add(GRANT_TYPE, REFRESH_TOKEN);
        form.add(REFRESH_TOKEN, req.refreshToken());
        form.add(SCOPE, OFFLINE_ACCESS);

        log.info("➡️  REFRESH request to Keycloak: clientId={}, realm={}",
                req.clientId(), props.getRealm());

        return webClient.post()
                .uri(props.getTokenUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .headers(h -> { if (dpopProof != null) h.add(DPOP, dpopProof); })
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono(KeycloakTokenResponse.class)
                .switchIfEmpty(Mono.error(new KeycloakAuthException(
                        REFRESH_FAILED,
                        HttpStatus.BAD_REQUEST,
                        INVALID_GRANT
                )))
                .doOnSuccess(response -> {
                    log.info("⬅️  REFRESH response: status=200");
                    refreshSuccessCounter.increment();
                })
                .doOnError(WebClientResponseException.class, ex -> {
                    log.error("❌ REFRESH error: status={}, body={}",
                            ex.getStatusCode(), ex.getResponseBodyAsString());
                    refreshFailureCounter.increment();
                })
                .onErrorMap(WebClientResponseException.class, this::mapRefreshError)
                .onErrorMap(this::mapGeneralError);
    }

    // ============================================================================
    // LOGOUT
    // ============================================================================
    public Mono<Void> logout(LogoutRequest req) {
        return logout(req, null);
    }

    public Mono<Void> logout(LogoutRequest req, String dpopProof) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add(CLIENT_ID, req.clientId());
        form.add(CLIENT_SECRET, req.clientSecret());
        form.add(REFRESH_TOKEN, req.refreshToken());

        log.info("➡️  LOGOUT request to Keycloak: clientId={}, realm={}",
                req.clientId(), props.getRealm());

        return webClient.post()
                .uri(props.getLogoutUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .headers(h -> { if (dpopProof != null) h.add(DPOP, dpopProof); })
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .toBodilessEntity()
                .doOnSuccess(response -> {
                    log.info("⬅️  LOGOUT response: status=200");
                    logoutSuccessCounter.increment();
                })
                .doOnError(WebClientResponseException.class, ex -> {
                    log.error("❌ LOGOUT error: status={}, body={}",
                            ex.getStatusCode(), ex.getResponseBodyAsString());
                    logoutFailureCounter.increment();
                })
                .onErrorMap(WebClientResponseException.class, this::mapLogoutError)
                .onErrorMap(this::mapGeneralError)
                .then();
    }

    // ============================================================================
    // ERROR MAPPING
    // ============================================================================
    private KeycloakAuthException mapLoginError(WebClientResponseException ex) {
        return new KeycloakAuthException(
                LOGIN_FAILED,
                HttpStatus.valueOf(ex.getStatusCode().value()),
                extractErrorMessage(ex.getResponseBodyAsString())
        );
    }

    private KeycloakAuthException mapRefreshError(WebClientResponseException ex) {
        return new KeycloakAuthException(
                REFRESH_FAILED,
                HttpStatus.valueOf(ex.getStatusCode().value()),
                extractErrorMessage(ex.getResponseBodyAsString())
        );
    }

    private KeycloakAuthException mapLogoutError(WebClientResponseException ex) {
        return new KeycloakAuthException(
                LOGOUT_FAILED,
                HttpStatus.valueOf(ex.getStatusCode().value()),
                extractErrorMessage(ex.getResponseBodyAsString())
        );
    }

    private Throwable mapGeneralError(Throwable ex) {
        if (ex instanceof KeycloakAuthException) {
            return ex;
        }
        log.error("❌ Unexpected error in Keycloak service", ex);
        return new KeycloakAuthException(
                "Unexpected error",
                HttpStatus.INTERNAL_SERVER_ERROR,
                INVALID_GRANT
        );
    }

    private String extractErrorMessage(String body) {
        if (body == null) return INVALID_GRANT;
        if (body.contains(INVALID_TOKEN)) return INVALID_TOKEN;
        if (body.contains(INVALID_GRANT)) return INVALID_GRANT;
        if (body.contains(INVALID_CLIENT)) return INVALID_CLIENT;
        if (body.contains(NOT_ALLOWED)) return NOT_ALLOWED;
        return INVALID_GRANT;
    }
}

