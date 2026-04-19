package lt.satsyuk.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lt.satsyuk.config.RateLimitProperties;
import lt.satsyuk.service.MessageService;
import lt.satsyuk.service.SecurityService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RateLimitingWebFilterPerformanceTest {

    @Disabled("Local microbenchmark only; excluded from regular CI test signal")
    @Test
    void benchmark_hotPath_allowedRequest() {
        RateLimitingWebFilter filter = buildFilter();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.GET, "http://localhost/api/ping")
                        .remoteAddress(new InetSocketAddress("127.0.0.1", 12345))
                        .build()
        );
        WebFilterChain chain = ignored -> Mono.empty();

        int warmup = 20_000;
        int iterations = 200_000;

        runLoop(filter, exchange, chain, warmup);
        long started = System.nanoTime();
        runLoop(filter, exchange, chain, iterations);
        long elapsedNanos = System.nanoTime() - started;

        double nsPerOp = (double) elapsedNanos / iterations;
        double opsPerSec = 1_000_000_000d / nsPerOp;

        System.out.printf("RATE_LIMIT_BENCH iterations=%d elapsedMs=%.2f nsPerOp=%.2f opsPerSec=%.2f%n",
                iterations,
                elapsedNanos / 1_000_000d,
                nsPerOp,
                opsPerSec);

        assertThat(nsPerOp).isPositive();
        assertThat(opsPerSec).isPositive();
    }

    private void runLoop(RateLimitingWebFilter filter,
                         MockServerWebExchange exchange,
                         WebFilterChain chain,
                         int iterations) {
        for (int i = 0; i < iterations; i++) {
            filter.filter(exchange, chain).block();
        }
    }

    private RateLimitingWebFilter buildFilter() {
        RateLimitProperties properties = new RateLimitProperties();
        RateLimitProperties.Rule rule = new RateLimitProperties.Rule();
        rule.setId("bench");
        rule.setEnabled(true);
        rule.setOrder(0);
        rule.setPathPattern("/api/**");
        rule.setMethods(Set.of("GET"));
        rule.setKeyStrategy(RateLimitProperties.KeyStrategy.IP);
        rule.setCacheName(RateLimitingWebFilter.DEFAULT_RATE_LIMIT_BUCKETS_CACHE);
        rule.setCapacity(1_000_000L);
        rule.setWindowSeconds(60L);
        properties.setRules(List.of(rule));

        SecurityService securityService = mock(SecurityService.class);
        MessageService messageService = mock(MessageService.class);

        return new RateLimitingWebFilter(
                securityService,
                messageService,
                properties,
                new ConcurrentMapCacheManager(RateLimitingWebFilter.DEFAULT_RATE_LIMIT_BUCKETS_CACHE),
                new ObjectMapper(),
                new SimpleMeterRegistry()
        );
    }
}

