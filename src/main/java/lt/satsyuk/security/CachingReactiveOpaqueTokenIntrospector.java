package lt.satsyuk.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.ReactiveOpaqueTokenIntrospector;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

public class CachingReactiveOpaqueTokenIntrospector implements ReactiveOpaqueTokenIntrospector {

    private static final String SHA_256 = "SHA-256";

    private final ReactiveOpaqueTokenIntrospector delegate;
    private final boolean cacheEnabled;
    private final Cache<String, OAuth2AuthenticatedPrincipal> cache;
    private final ConcurrentHashMap<String, Mono<OAuth2AuthenticatedPrincipal>> inFlight = new ConcurrentHashMap<>();
    private final Counter cacheHitCounter;
    private final Counter cacheMissCounter;

    public CachingReactiveOpaqueTokenIntrospector(ReactiveOpaqueTokenIntrospector delegate,
                                                  boolean cacheEnabled,
                                                  Duration ttl,
                                                  long maxSize,
                                                  MeterRegistry meterRegistry) {
        Duration normalizedTtl = (ttl == null || ttl.isZero() || ttl.isNegative())
                ? Duration.ofSeconds(10)
                : ttl;
        this.delegate = delegate;
        this.cacheEnabled = cacheEnabled;
        this.cache = Caffeine.newBuilder()
                .maximumSize(Math.max(1, maxSize))
                .expireAfterWrite(normalizedTtl)
                .build();
        this.cacheHitCounter = Counter.builder("security.opaque_introspection.cache")
                .tag("result", "hit")
                .description("Opaque token introspection cache hits")
                .register(meterRegistry);
        this.cacheMissCounter = Counter.builder("security.opaque_introspection.cache")
                .tag("result", "miss")
                .description("Opaque token introspection cache misses")
                .register(meterRegistry);
    }

    @Override
    public Mono<OAuth2AuthenticatedPrincipal> introspect(String token) {
        if (!cacheEnabled || !StringUtils.hasText(token)) {
            return delegate.introspect(token);
        }

        String tokenKey = tokenCacheKey(token);
        OAuth2AuthenticatedPrincipal cached = cache.getIfPresent(tokenKey);
        if (cached != null) {
            cacheHitCounter.increment();
            return Mono.just(cached);
        }

        Mono<OAuth2AuthenticatedPrincipal> existing = inFlight.get(tokenKey);
        if (existing != null) {
            return existing;
        }

        Mono<OAuth2AuthenticatedPrincipal> loading = delegate.introspect(token)
                .doOnNext(principal -> cache.put(tokenKey, principal))
                .doFinally(signal -> inFlight.remove(tokenKey))
                .cache();

        Mono<OAuth2AuthenticatedPrincipal> previous = inFlight.putIfAbsent(tokenKey, loading);
        if (previous != null) {
            return previous;
        }

        cacheMissCounter.increment();
        return loading;
    }

    private String tokenCacheKey(String token) {
        MessageDigest digest = sha256();
        byte[] hash = digest.digest(token.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance(SHA_256);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
