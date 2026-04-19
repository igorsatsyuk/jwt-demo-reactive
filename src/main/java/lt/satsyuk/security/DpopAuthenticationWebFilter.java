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
import java.util.List;
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
    private static final String VALIDATION_FAILED_REASON = "validation_failed";
    private static final List<ReasonMapping> REASON_MAPPINGS = List.of(
            new ReasonMapping("uri_scheme_required", "URI scheme is required for DPoP validation"),
            new ReasonMapping("replay_detected", "replay"),
            new ReasonMapping("scheme_required", "scheme"),
            new ReasonMapping("host_required", "host"),
            new ReasonMapping("proof_missing", "proof is required", "header is missing"),
            new ReasonMapping("uri_mismatch", "URI mismatch"),
            new ReasonMapping("method_mismatch", "method mismatch"),
            new ReasonMapping("jkt_mismatch", "thumbprint mismatch"),
            new ReasonMapping("ath_mismatch", "access token hash mismatch"),
            new ReasonMapping("iat_out_of_range", "expired", "issued in the future")
    );

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
            return VALIDATION_FAILED_REASON;
        }
        for (ReasonMapping mapping : REASON_MAPPINGS) {
            if (mapping.matches(message)) {
                return mapping.reason();
            }
        }
        return VALIDATION_FAILED_REASON;
    }

    private record ReasonMapping(String reason, String... markers) {
        private boolean matches(String message) {
            for (String marker : markers) {
                if (message.contains(marker)) {
                    return true;
                }
            }
            return false;
        }
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

