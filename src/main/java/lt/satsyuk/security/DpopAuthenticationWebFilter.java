package lt.satsyuk.security;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lt.satsyuk.auth.JsonAuthEntryPoint;
import lt.satsyuk.config.DpopProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class DpopAuthenticationWebFilter implements WebFilter {

    private static final String DPOP_HEADER = "DPoP";
    private static final String DPOP_SCHEME = "DPoP ";
    private static final String CNF = "cnf";
    private static final String JKT = "jkt";
    private static final String DPOP_REJECTED_METRIC = "security.dpop.rejected";

    private final DpopProperties properties;
    private final DpopProofValidator validator;
    private final JsonAuthEntryPoint authEntryPoint;
    private final MeterRegistry meterRegistry;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!properties.isEnabled()) {
            return chain.filter(exchange);
        }

        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(ctx -> ctx.getAuthentication())
                .filter(Authentication::isAuthenticated)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(authOptional -> authOptional
                        .map(auth -> validateDpop(exchange, chain, auth))
                        .orElseGet(() -> chain.filter(exchange)));
    }

    private Mono<Void> validateDpop(ServerWebExchange exchange, WebFilterChain chain, Authentication auth) {
        String accessToken = extractAccessToken(auth);
        Map<String, Object> tokenAttributes = extractTokenAttributes(auth);
        String expectedJkt = extractExpectedJkt(tokenAttributes);

        String dpopProof = exchange.getRequest().getHeaders().getFirst(DPOP_HEADER);
        boolean usesDpopScheme = usesDpopScheme(exchange);
        boolean tokenIsBoundToDpop = StringUtils.hasText(expectedJkt);

        if (!usesDpopScheme && !StringUtils.hasText(dpopProof) && !tokenIsBoundToDpop) {
            return chain.filter(exchange);
        }

        if (!usesDpopScheme) {
            recordDpopRejected("scheme_required");
            return authEntryPoint.commence(exchange, new InsufficientAuthenticationException("DPoP authorization scheme is required"));
        }

        if (!StringUtils.hasText(dpopProof)) {
            recordDpopRejected("proof_missing");
            return authEntryPoint.commence(exchange, new InsufficientAuthenticationException("DPoP proof is required"));
        }

        String requestUri = buildRequestUri(exchange);
        try {
            String method = exchange.getRequest().getMethod() != null
                ? exchange.getRequest().getMethod().toString()
                : "GET";
            validator.validate(method, requestUri, accessToken, dpopProof, expectedJkt);
            return chain.filter(exchange);
        } catch (DpopProofValidationException ex) {
            log.warn("DPoP validation failed: {}", ex.getMessage());
            recordDpopRejected(mapDpopRejectReason(ex.getMessage()));
            return authEntryPoint.commence(exchange, new InsufficientAuthenticationException(ex.getMessage()));
        }
    }

    private void recordDpopRejected(String reason) {
        meterRegistry.counter(DPOP_REJECTED_METRIC, "reason", reason).increment();
    }

    private String mapDpopRejectReason(String message) {
        if (!StringUtils.hasText(message)) {
            return "validation_failed";
        }

        return switch (message) {
            case "DPoP proof header is missing", "DPoP proof is required" -> "proof_missing";
            case "DPoP authorization scheme is required" -> "scheme_required";
            case "Access token is missing for DPoP validation" -> "access_token_missing";
            case "Invalid DPoP proof format" -> "proof_format_invalid";
            case "Invalid DPoP proof type" -> "proof_type_invalid";
            case "Invalid DPoP proof algorithm" -> "proof_algorithm_invalid";
            case "DPoP proof JWK is missing" -> "jwk_missing";
            case "DPoP proof public JWK is missing" -> "public_jwk_missing";
            case "Invalid DPoP proof signature" -> "signature_invalid";
            case "Unable to verify DPoP proof signature" -> "signature_verification_failed";
            case "DPoP proof method mismatch" -> "method_mismatch";
            case "DPoP proof URI is missing" -> "uri_missing";
            case "DPoP proof URI mismatch" -> "uri_mismatch";
            case "DPoP proof issue time is missing" -> "iat_missing";
            case "DPoP proof is expired or issued in the future" -> "iat_out_of_range";
            case "DPoP proof ID is missing" -> "jti_missing";
            case "DPoP proof replay detected" -> "replay_detected";
            case "DPoP proof access token hash is missing" -> "ath_missing";
            case "DPoP proof access token hash mismatch" -> "ath_mismatch";
            case "DPoP proof key thumbprint mismatch" -> "jkt_mismatch";
            case "Unable to compute DPoP proof key thumbprint" -> "jkt_compute_failed";
            case "Invalid DPoP proof claims" -> "claims_invalid";
            default -> "validation_failed";
        };
    }

    private boolean usesDpopScheme(ServerWebExchange exchange) {
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        return StringUtils.hasText(authorization)
                && authorization.regionMatches(true, 0, DPOP_SCHEME, 0, DPOP_SCHEME.length());
    }

    private String buildRequestUri(ServerWebExchange exchange) {
        StringBuilder uri = new StringBuilder();
        uri.append(exchange.getRequest().getURI().getScheme()).append("://");
        uri.append(exchange.getRequest().getURI().getHost());
        int port = exchange.getRequest().getURI().getPort();
        if (port > 0) {
            uri.append(":").append(port);
        }
        uri.append(exchange.getRequest().getPath());
        String query = exchange.getRequest().getURI().getQuery();
        if (StringUtils.hasText(query)) {
            uri.append("?").append(query);
        }
        return uri.toString();
    }

    private String extractAccessToken(Authentication authentication) {
        if (authentication instanceof BearerTokenAuthentication bearer) {
            return bearer.getToken().getTokenValue();
        }
        if (authentication instanceof JwtAuthenticationToken jwt) {
            return jwt.getToken().getTokenValue();
        }
        return null;
    }

    private Map<String, Object> extractTokenAttributes(Authentication authentication) {
        if (authentication instanceof BearerTokenAuthentication bearer) {
            return bearer.getTokenAttributes();
        }
        if (authentication instanceof JwtAuthenticationToken jwt) {
            return jwt.getTokenAttributes();
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private String extractExpectedJkt(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }

        Object cnf = attributes.get(CNF);
        if (cnf instanceof Map<?, ?> cnfMap) {
            Object jkt = cnfMap.get(JKT);
            if (jkt instanceof String thumbprint && StringUtils.hasText(thumbprint)) {
                return thumbprint;
            }
        }

        return null;
    }
}

