package lt.satsyuk.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.ReactiveOpaqueTokenIntrospector;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.time.Duration;

public class CachingReactiveOpaqueTokenIntrospector implements ReactiveOpaqueTokenIntrospector {

    private final ReactiveOpaqueTokenIntrospector delegate;
    private final boolean cacheEnabled;
    private final Cache<String, OAuth2AuthenticatedPrincipal> cache;

    public CachingReactiveOpaqueTokenIntrospector(ReactiveOpaqueTokenIntrospector delegate,
                                                  boolean cacheEnabled,
                                                  Duration ttl,
                                                  long maxSize) {
        Duration normalizedTtl = (ttl == null || ttl.isZero() || ttl.isNegative())
                ? Duration.ofSeconds(10)
                : ttl;
        this.delegate = delegate;
        this.cacheEnabled = cacheEnabled;
        this.cache = Caffeine.newBuilder()
                .maximumSize(Math.max(1, maxSize))
                .expireAfterWrite(normalizedTtl)
                .build();
    }

    @Override
    public Mono<OAuth2AuthenticatedPrincipal> introspect(String token) {
        if (!cacheEnabled || !StringUtils.hasText(token)) {
            return delegate.introspect(token);
        }

        OAuth2AuthenticatedPrincipal cached = cache.getIfPresent(token);
        if (cached != null) {
            return Mono.just(cached);
        }

        return delegate.introspect(token)
                .doOnNext(principal -> cache.put(token, principal));
    }
}
