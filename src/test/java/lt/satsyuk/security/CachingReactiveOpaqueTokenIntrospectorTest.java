package lt.satsyuk.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.ReactiveOpaqueTokenIntrospector;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CachingReactiveOpaqueTokenIntrospectorTest {

    private final ReactiveOpaqueTokenIntrospector delegate = mock(ReactiveOpaqueTokenIntrospector.class);

    @Test
    void introspect_reusesCachedPrincipalForSameToken() {
        OAuth2AuthenticatedPrincipal principal = new DefaultOAuth2AuthenticatedPrincipal(
                "user-1",
                Map.of("sub", "user-1"),
                AuthorityUtils.NO_AUTHORITIES
        );
        when(delegate.introspect("token-1")).thenReturn(Mono.just(principal));

        CachingReactiveOpaqueTokenIntrospector introspector = new CachingReactiveOpaqueTokenIntrospector(
                delegate,
                true,
                Duration.ofMinutes(1),
                1000
        );

        StepVerifier.create(introspector.introspect("token-1"))
                .expectNext(principal)
                .verifyComplete();
        StepVerifier.create(introspector.introspect("token-1"))
                .expectNext(principal)
                .verifyComplete();

        verify(delegate, times(1)).introspect("token-1");
    }

    @Test
    void introspect_doesNotCacheErrors() {
        when(delegate.introspect("bad-token")).thenReturn(Mono.error(new IllegalStateException("invalid token")));

        CachingReactiveOpaqueTokenIntrospector introspector = new CachingReactiveOpaqueTokenIntrospector(
                delegate,
                true,
                Duration.ofMinutes(1),
                1000
        );

        StepVerifier.create(introspector.introspect("bad-token"))
                .expectError(IllegalStateException.class)
                .verify();
        StepVerifier.create(introspector.introspect("bad-token"))
                .expectError(IllegalStateException.class)
                .verify();

        verify(delegate, times(2)).introspect("bad-token");
    }

    @Test
    void introspect_skipsCacheWhenDisabled() {
        OAuth2AuthenticatedPrincipal principal = new DefaultOAuth2AuthenticatedPrincipal(
                "user-2",
                Map.of("sub", "user-2"),
                AuthorityUtils.NO_AUTHORITIES
        );
        when(delegate.introspect("token-2")).thenReturn(Mono.just(principal));

        CachingReactiveOpaqueTokenIntrospector introspector = new CachingReactiveOpaqueTokenIntrospector(
                delegate,
                false,
                Duration.ofMinutes(1),
                1000
        );

        StepVerifier.create(introspector.introspect("token-2"))
                .expectNext(principal)
                .verifyComplete();
        StepVerifier.create(introspector.introspect("token-2"))
                .expectNext(principal)
                .verifyComplete();

        verify(delegate, times(2)).introspect("token-2");
    }

    @Test
    void introspect_deduplicatesConcurrentMisses() {
        OAuth2AuthenticatedPrincipal principal = new DefaultOAuth2AuthenticatedPrincipal(
                "user-3",
                Map.of("sub", "user-3"),
                AuthorityUtils.NO_AUTHORITIES
        );
        AtomicInteger delegateCalls = new AtomicInteger(0);
        Sinks.One<OAuth2AuthenticatedPrincipal> sink = Sinks.one();
        when(delegate.introspect("token-3")).thenAnswer(invocation -> {
            delegateCalls.incrementAndGet();
            return sink.asMono();
        });

        CachingReactiveOpaqueTokenIntrospector introspector = new CachingReactiveOpaqueTokenIntrospector(
                delegate,
                true,
                Duration.ofMinutes(1),
                1000
        );

        Mono<OAuth2AuthenticatedPrincipal> first = introspector.introspect("token-3");
        Mono<OAuth2AuthenticatedPrincipal> second = introspector.introspect("token-3");
        assertThat(delegateCalls.get()).isEqualTo(1);

        sink.tryEmitValue(principal);

        StepVerifier.create(first).expectNext(principal).verifyComplete();
        StepVerifier.create(second).expectNext(principal).verifyComplete();
        StepVerifier.create(introspector.introspect("token-3")).expectNext(principal).verifyComplete();

        verify(delegate, times(1)).introspect("token-3");
    }
}
