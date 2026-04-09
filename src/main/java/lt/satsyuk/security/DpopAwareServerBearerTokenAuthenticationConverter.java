package lt.satsyuk.security;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Accepts both Authorization schemes: Bearer and DPoP.
 * DPoP tokens are mapped to the same bearer authentication token type.
 */
public class DpopAwareServerBearerTokenAuthenticationConverter implements ServerAuthenticationConverter {

    private static final String DPOP_PREFIX = "DPoP ";

    private final ServerBearerTokenAuthenticationConverter delegate = new ServerBearerTokenAuthenticationConverter();

    @Override
    public Mono<Authentication> convert(ServerWebExchange exchange) {
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(authorization)
                && authorization.regionMatches(true, 0, DPOP_PREFIX, 0, DPOP_PREFIX.length())) {
            String token = authorization.substring(DPOP_PREFIX.length()).trim();
            if (!StringUtils.hasText(token)) {
                return Mono.error(new AuthenticationCredentialsNotFoundException("Bearer token is malformed"));
            }
            return Mono.just(new BearerTokenAuthenticationToken(token));
        }

        return delegate.convert(exchange);
    }
}

