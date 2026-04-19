package lt.satsyuk.security;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lt.satsyuk.auth.JsonAuthEntryPoint;
import lt.satsyuk.config.DpopProperties;
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

    @Test
    void filter_skipsValidationWhenDpopDisabled() {
        properties.setEnabled(false);
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = exchange(HttpMethod.GET, "https://api.example.com/resource", null, null);
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(exchange);
        verify(validator, never()).validate(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void filter_skipsWhenNoAuthenticationInContext() {
        properties.setEnabled(true);
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = exchange(HttpMethod.GET, "https://api.example.com/resource", null, null);
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(exchange);
    }

    @Test
    void filter_skipsWhenRequestDoesNotUseDpopAndTokenNotBound() {
        properties.setEnabled(true);
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = exchange(HttpMethod.GET, "https://api.example.com/resource", "Bearer token", null);
        Authentication auth = jwtAuthWithoutCnf();
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)))
                .verifyComplete();

        verify(chain).filter(exchange);
        verify(validator, never()).validate(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void filter_returnsUnauthorizedWhenDpopSchemeMissingButProofProvided() {
        properties.setEnabled(true);
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = exchange(HttpMethod.GET, "https://api.example.com/resource", "Bearer token", "proof");
        Authentication auth = jwtAuthWithoutCnf();
        when(authEntryPoint.commence(any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)))
                .verifyComplete();

        verify(authEntryPoint).commence(any(ServerWebExchange.class), any());
        verify(chain, never()).filter(exchange);
        assertThat(
                meterRegistry.counter("security.dpop.rejected", "reason", "scheme_required").count()
        ).isEqualTo(1.0d);
    }

    @Test
    void filter_returnsUnauthorizedWhenProofMissingForDpopScheme() {
        properties.setEnabled(true);
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = exchange(HttpMethod.GET, "https://api.example.com/resource", "DPoP token", null);
        Authentication auth = jwtAuthWithoutCnf();
        when(authEntryPoint.commence(any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)))
                .verifyComplete();

        verify(authEntryPoint).commence(any(ServerWebExchange.class), any());
        verify(chain, never()).filter(exchange);
        assertThat(
                meterRegistry.counter("security.dpop.rejected", "reason", "proof_missing").count()
        ).isEqualTo(1.0d);
    }

    @Test
    void filter_validatesProofAndContinuesChain() {
        properties.setEnabled(true);
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = exchange(HttpMethod.POST, "https://api.example.com/resource?q=1", "DPoP jwt-token", "proof");
        Authentication auth = jwtAuthWithCnf();
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)))
                .verifyComplete();

        verify(validator).validate("POST", "https://api.example.com/resource?q=1", "jwt-token", "proof", "thumbprint");
        verify(chain).filter(exchange);
    }

    @Test
    void filter_returnsUnauthorizedWhenValidatorThrows() {
        properties.setEnabled(true);
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = exchange(HttpMethod.GET, "https://api.example.com/resource", "DPoP jwt-token", "proof");
        Authentication auth = jwtAuthWithoutCnf();

        when(authEntryPoint.commence(any(), any())).thenReturn(Mono.empty());
        doThrow(new DpopProofValidationException("bad proof"))
                .when(validator)
                .validate("GET", "https://api.example.com/resource", "jwt-token", "proof", null);

        StepVerifier.create(filter.filter(exchange, chain)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)))
                .verifyComplete();

        verify(authEntryPoint).commence(any(ServerWebExchange.class), any());
        verify(chain, never()).filter(exchange);
        assertThat(
                meterRegistry.counter("security.dpop.rejected", "reason", "validation_failed").count()
        ).isEqualTo(1.0d);
    }

    @Test
    void filter_handlesUnsupportedAuthenticationByFailingDpopValidation() {
        properties.setEnabled(true);
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = exchange(HttpMethod.GET, "https://api.example.com/resource", "DPoP token", "proof");
        Authentication auth = new TestingAuthenticationToken("user", "password");
        auth.setAuthenticated(true);

        when(authEntryPoint.commence(any(), any())).thenReturn(Mono.empty());
        doThrow(new DpopProofValidationException("Access token is missing for DPoP validation"))
                .when(validator)
                .validate("GET", "https://api.example.com/resource", null, "proof", null);

        StepVerifier.create(filter.filter(exchange, chain)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)))
                .verifyComplete();

        verify(authEntryPoint).commence(any(ServerWebExchange.class), any());
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
        properties.setEnabled(true);
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = exchange(HttpMethod.GET, "https://api.example.com/resource", "DPoP jwt-token", "proof");
        Authentication auth = jwtAuthWithoutCnf();

        when(authEntryPoint.commence(any(), any())).thenReturn(Mono.empty());
        doThrow(new DpopProofValidationException(validatorMessage))
                .when(validator)
                .validate("GET", "https://api.example.com/resource", "jwt-token", "proof", null);

        StepVerifier.create(filter.filter(exchange, chain)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)))
                .verifyComplete();

        assertThat(meterRegistry.counter("security.dpop.rejected", "reason", expectedReason).count()).isEqualTo(1.0d);
        verify(authEntryPoint).commence(any(ServerWebExchange.class), any());
        verify(chain, never()).filter(exchange);
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

