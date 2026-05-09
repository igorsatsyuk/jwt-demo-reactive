package lt.satsyuk.security;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lt.satsyuk.auth.JsonAuthEntryPoint;
import lt.satsyuk.config.DpopProperties;
import lt.satsyuk.exception.DpopProofValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DpopAuthenticationWebFilterTest {

    private final DpopProperties properties = new DpopProperties();
    private final DpopProofValidator validator = mock(DpopProofValidator.class);
    private final JsonAuthEntryPoint authEntryPoint = mock(JsonAuthEntryPoint.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private final DpopAuthenticationWebFilter filter = new DpopAuthenticationWebFilter(properties, validator, authEntryPoint, meterRegistry);

    private void assertFilterPassesThroughWithoutDpopValidation(boolean propertiesEnabled) {
        assertFilterPassesThroughWithoutDpopValidation(propertiesEnabled, HttpMethod.GET, "https://api.example.com/resource", null, null, null);
    }

    private void assertFilterPassesThroughWithoutDpopValidation(boolean propertiesEnabled, HttpMethod method, String uri, String authorization, String dpopProof, Authentication authentication) {
        properties.setEnabled(propertiesEnabled);
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = exchange(method, uri, authorization, dpopProof);
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        var filterMono = filter.filter(exchange, chain);
        if (authentication != null) {
            filterMono = filterMono.contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
        }

        StepVerifier.create(filterMono)
                .verifyComplete();

        verify(chain).filter(exchange);
    }

    private void assertFilterValidatesAndContinuesChain(boolean propertiesEnabled, HttpMethod method, String uri, String authorization, String dpopProof, Authentication authentication, java.util.function.Consumer<DpopProofValidator> validatorVerification) {
        properties.setEnabled(propertiesEnabled);
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = exchange(method, uri, authorization, dpopProof);
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        var filterMono = filter.filter(exchange, chain);
        if (authentication != null) {
            filterMono = filterMono.contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
        }

        StepVerifier.create(filterMono)
                .verifyComplete();

        verify(chain).filter(exchange);
        validatorVerification.accept(validator);
    }

    private void assertFilterRejectsWithUnauthorized(boolean propertiesEnabled, HttpMethod method, String uri, String authorization, String dpopProof, Authentication authentication) {
        assertFilterRejectsWithUnauthorized(propertiesEnabled, method, uri, authorization, dpopProof, authentication, null);
    }

    private void assertFilterRejectsWithUnauthorized(boolean propertiesEnabled, HttpMethod method, String uri, String authorization, String dpopProof, Authentication authentication, java.util.function.Consumer<DpopProofValidator> validatorSetup) {
        properties.setEnabled(propertiesEnabled);
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = exchange(method, uri, authorization, dpopProof);
        when(authEntryPoint.commence(any(), any())).thenReturn(Mono.empty());

        if (validatorSetup != null) {
            validatorSetup.accept(validator);
        }

        var filterMono = filter.filter(exchange, chain);
        if (authentication != null) {
            filterMono = filterMono.contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
        }

        StepVerifier.create(filterMono)
                .verifyComplete();

        verify(authEntryPoint).commence(any(ServerWebExchange.class), any());
        verify(chain, never()).filter(exchange);
    }

    @Test
    void filter_skipsValidationWhenDpopDisabled() {
        assertFilterPassesThroughWithoutDpopValidation(false);
        verify(validator, never()).validate(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void filter_skipsWhenNoAuthenticationInContext() {
        assertFilterPassesThroughWithoutDpopValidation(true);
    }

    @Test
    void filter_skipsWhenRequestDoesNotUseDpopAndTokenNotBound() {
        assertFilterPassesThroughWithoutDpopValidation(true, HttpMethod.GET, "https://api.example.com/resource", "Bearer token", null, jwtAuthWithoutCnf());
        verify(validator, never()).validate(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void filter_returnsUnauthorizedWhenDpopSchemeMissingButProofProvided() {
        assertFilterRejectsWithUnauthorized(true, HttpMethod.GET, "https://api.example.com/resource", "Bearer token", "proof", jwtAuthWithoutCnf());
        assertThat(
                meterRegistry.counter("security.dpop.rejected", "reason", "scheme_required").count()
        ).isEqualTo(1.0d);
    }

    @Test
    void filter_returnsUnauthorizedWhenProofMissingForDpopScheme() {
        assertFilterRejectsWithUnauthorized(true, HttpMethod.GET, "https://api.example.com/resource", "DPoP token", null, jwtAuthWithoutCnf());
        assertThat(
                meterRegistry.counter("security.dpop.rejected", "reason", "proof_missing").count()
        ).isEqualTo(1.0d);
    }

    @Test
    void filter_validatesProofAndContinuesChain() {
        assertFilterValidatesAndContinuesChain(
            true,
            HttpMethod.POST,
            "https://api.example.com/resource?q=1",
            "DPoP jwt-token",
            "proof",
            jwtAuthWithCnf(),
            v -> verify(v).validate("POST", "https://api.example.com/resource?q=1", "jwt-token", "proof", "thumbprint")
        );
    }

    @Test
    void filter_returnsUnauthorizedWhenValidatorThrows() {
        assertFilterRejectsWithUnauthorized(
            true,
            HttpMethod.GET,
            "https://api.example.com/resource",
            "DPoP jwt-token",
            "proof",
            jwtAuthWithoutCnf(),
            v -> doThrow(new DpopProofValidationException("bad proof"))
                    .when(v)
                    .validate("GET", "https://api.example.com/resource", "jwt-token", "proof", null)
        );
        assertThat(
                meterRegistry.counter("security.dpop.rejected", "reason", "validation_failed").count()
        ).isEqualTo(1.0d);
    }

    @Test
    void filter_handlesUnsupportedAuthenticationByFailingDpopValidation() {
        TestingAuthenticationToken auth = new TestingAuthenticationToken("user", "password");
        auth.setAuthenticated(true);
        assertFilterRejectsWithUnauthorized(
            true,
            HttpMethod.GET,
            "https://api.example.com/resource",
            "DPoP token",
            "proof",
            auth,
            v -> doThrow(new DpopProofValidationException("Access token is missing for DPoP validation"))
                    .when(v)
                    .validate("GET", "https://api.example.com/resource", null, "proof", null)
        );
    }

    @Test
    void filter_recordsReplayDetectedReason() {
        assertMappedReason("DPoP proof replay detected", "replay_detected");
    }

    @Test
    void filter_recordsUriMismatchReason() {
        assertMappedReason("DPoP proof URI mismatch", "uri_mismatch");
    }

    @Test
    void filter_recordsMethodMismatchReason() {
        assertMappedReason("DPoP proof method mismatch", "method_mismatch");
    }

    @Test
    void filter_recordsJktMismatchReason() {
        assertMappedReason("DPoP proof key thumbprint mismatch", "jkt_mismatch");
    }

    @Test
    void filter_recordsAthMismatchReason() {
        assertMappedReason("DPoP proof access token hash mismatch", "ath_mismatch");
    }

    @Test
    void filter_recordsIatOutOfRangeReason() {
        assertMappedReason("DPoP proof is expired or issued in the future", "iat_out_of_range");
    }

    @Test
    void filter_recordsHostRequiredReason() {
        assertMappedReason("URI host is required for DPoP validation", "host_required");
    }

    @Test
    void filter_recordsUriSchemeRequiredReason() {
        assertMappedReason("URI scheme is required for DPoP validation", "uri_scheme_required");
    }

    private void assertMappedReason(String validatorMessage, String expectedReason) {
        assertFilterRejectsWithUnauthorized(
            true,
            HttpMethod.GET,
            "https://api.example.com/resource",
            "DPoP jwt-token",
            "proof",
            jwtAuthWithoutCnf(),
            v -> doThrow(new DpopProofValidationException(validatorMessage))
                    .when(v)
                    .validate("GET", "https://api.example.com/resource", "jwt-token", "proof", null)
        );
        assertThat(meterRegistry.counter("security.dpop.rejected", "reason", expectedReason).count()).isEqualTo(1.0d);
    }

    private static MockServerWebExchange exchange(HttpMethod method, String uri, String authorization, String dpopProof) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.method(method, uri);
        if (authorization != null) {
            builder.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        if (dpopProof != null) {
            builder.header("DPoP", dpopProof);
        }
        return MockServerWebExchange.from(builder.build());
    }

    private static Authentication jwtAuthWithoutCnf() {
        Jwt jwt = Jwt.withTokenValue("jwt-token")
                .header("alg", "none")
                .claim("sub", "user")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        return new JwtAuthenticationToken(jwt, AuthorityUtils.NO_AUTHORITIES);
    }

    private static Authentication jwtAuthWithCnf() {
        Jwt jwt = Jwt.withTokenValue("jwt-token")
                .header("alg", "none")
                .claim("sub", "user")
                .claim("cnf", Map.of("jkt", "thumbprint"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        return new JwtAuthenticationToken(jwt, AuthorityUtils.NO_AUTHORITIES);
    }
}

