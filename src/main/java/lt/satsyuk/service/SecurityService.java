package lt.satsyuk.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;

@Component
public class SecurityService {

    public static final String ANONYMOUS = "anonymous";

    public Mono<String> clientId() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(this::extractClientId)
                .defaultIfEmpty(ANONYMOUS);
    }

    public Mono<String> username() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(Authentication::getName)
                .defaultIfEmpty(ANONYMOUS);
    }

    public String clientId(Authentication authentication) {
        if (authentication == null) return ANONYMOUS;
        return extractClientId(authentication);
    }

    public String username(Authentication authentication) {
        return authentication != null ? authentication.getName() : ANONYMOUS;
    }

    private String extractClientId(Authentication authentication) {
        if (authentication instanceof BearerTokenAuthentication bearer) {
            return extractFromAttributes(bearer.getTokenAttributes());
        }
        return ANONYMOUS;
    }

    private String extractFromAttributes(Map<String, Object> attributes) {
        if (attributes == null) return ANONYMOUS;

        Object azp = attributes.get("azp");
        if (azp instanceof String s && !s.isBlank()) {
            return s;
        }

        Object clientId = attributes.get("client_id");
        if (clientId instanceof String s && !s.isBlank()) {
            return s;
        }

        return ANONYMOUS;
    }
}


