package lt.satsyuk.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.ReactiveOpaqueTokenIntrospector;
import org.springframework.security.oauth2.server.resource.introspection.SpringReactiveOpaqueTokenIntrospector;
import reactor.core.publisher.Mono;

import java.util.Collection;

public class KeycloakReactiveOpaqueTokenIntrospector implements ReactiveOpaqueTokenIntrospector {

    private final ReactiveOpaqueTokenIntrospector delegate;
    private final KeycloakOpaqueRoleConverter roleConverter;

    public KeycloakReactiveOpaqueTokenIntrospector(String introspectionUrl,
                                                   String clientId,
                                                   String clientSecret,
                                                   KeycloakOpaqueRoleConverter roleConverter) {
        this.delegate = SpringReactiveOpaqueTokenIntrospector.withIntrospectionUri(introspectionUrl)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .build();
        this.roleConverter = roleConverter;
    }

    @Override
    public Mono<OAuth2AuthenticatedPrincipal> introspect(String token) {
        return delegate.introspect(token).map(principal -> {
            Collection<GrantedAuthority> authorities = roleConverter.convert(principal.getAttributes());
            return new DefaultOAuth2AuthenticatedPrincipal(
                    principal.getName(),
                    principal.getAttributes(),
                    authorities
            );
        });
    }
}

