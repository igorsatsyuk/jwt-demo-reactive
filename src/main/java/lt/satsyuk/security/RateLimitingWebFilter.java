package lt.satsyuk.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import lombok.RequiredArgsConstructor;
import lt.satsyuk.config.RateLimitProperties;
import lt.satsyuk.dto.AppResponse;
import lt.satsyuk.service.MessageService;
import lt.satsyuk.service.SecurityService;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class RateLimitingWebFilter implements WebFilter {

    private static final String RATE_LIMIT_MESSAGE_KEY = "api.error.tooManyRequests";
    public static final String DEFAULT_RATE_LIMIT_BUCKETS_CACHE = "rateLimitBuckets";

    private final SecurityService securityService;
    private final MessageService messageService;
    private final RateLimitProperties rateLimitProperties;
    private final CacheManager cacheManager;
    private final ObjectMapper objectMapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        String method = exchange.getRequest().getMethod() != null ? exchange.getRequest().getMethod().toString() : null;

        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication())
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(authOptional -> applyRateLimits(exchange, chain, method, path, authOptional.orElse(null)));
    }

    private Mono<Void> applyRateLimits(ServerWebExchange exchange,
                                       WebFilterChain chain,
                                       String method,
                                       String path,
                                       Authentication auth) {
        for (RateLimitProperties.Rule rule : sortedRules()) {
            boolean shouldLimit = matchesRequest(rule, method, path)
                    && matchesClient(rule, auth)
                    && isRateLimited(rule, resolveKey(rule, exchange, auth));

            if (shouldLimit) {
                return writeRateLimitedResponse(exchange);
            }
        }
        return chain.filter(exchange);
    }

    private List<RateLimitProperties.Rule> sortedRules() {
        return rateLimitProperties.getRules().stream()
                .filter(RateLimitProperties.Rule::isEnabled)
                .sorted(Comparator.comparingInt(RateLimitProperties.Rule::getOrder)
                        .thenComparing(RateLimitProperties.Rule::getId))
                .toList();
    }

    private boolean matchesRequest(RateLimitProperties.Rule rule, String method, String path) {
        return matchesPath(rule, path) && matchesMethod(rule, method);
    }

    private boolean matchesPath(RateLimitProperties.Rule rule, String path) {
        return path != null && pathMatcher.match(rule.getPathPattern(), path);
    }

    private boolean matchesMethod(RateLimitProperties.Rule rule, String method) {
        Set<String> methods = rule.getMethods();
        if (methods == null || methods.isEmpty()) {
            return true;
        }
        if (!StringUtils.hasText(method)) {
            return false;
        }

        String normalizedMethod = method.trim().toUpperCase(Locale.ROOT);
        return methods.stream()
                .map(candidate -> candidate == null ? "" : candidate.trim().toUpperCase(Locale.ROOT))
                .anyMatch(candidate -> "*".equals(candidate) || candidate.equals(normalizedMethod));
    }

    private boolean matchesClient(RateLimitProperties.Rule rule, Authentication auth) {
        Set<String> clientIds = rule.getClientIds();
        if (clientIds == null || clientIds.isEmpty()) {
            return true;
        }
        return clientIds.contains(safeValue(securityService.clientId(auth)));
    }

    private boolean isRateLimited(RateLimitProperties.Rule rule, String key) {
        return !resolveBucket(rule, key).tryConsume(1);
    }

    private Bucket resolveBucket(RateLimitProperties.Rule rule, String key) {
        String cacheName = resolveCacheName(rule);
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            throw new IllegalStateException("Cache '" + cacheName + "' is not configured");
        }

        String cacheKey = rule.getId() + ":" + key;
        return cache.get(cacheKey, () -> Bucket.builder()
                .addLimit(Bandwidth.classic(
                        rule.getCapacity(),
                        Refill.intervally(rule.getCapacity(), Duration.ofSeconds(rule.getWindowSeconds()))
                ))
                .build()
        );
    }

    private String resolveCacheName(RateLimitProperties.Rule rule) {
        return StringUtils.hasText(rule.getCacheName())
                ? rule.getCacheName()
                : DEFAULT_RATE_LIMIT_BUCKETS_CACHE;
    }

    private String resolveKey(RateLimitProperties.Rule rule, ServerWebExchange exchange, Authentication auth) {
        return switch (rule.getKeyStrategy()) {
            case IP -> "ip:" + safeValue(getRemoteAddress(exchange));
            case CLIENT_ID -> "client:" + safeValue(securityService.clientId(auth));
            case USERNAME -> "user:" + safeValue(securityService.username(auth));
            case HEADER -> "header:" + safeValue(exchange.getRequest().getHeaders().getFirst(rule.getKeyHeader()));
            case CLIENT_ID_AND_IP -> "client-ip:" + safeValue(securityService.clientId(auth)) + "|" + safeValue(getRemoteAddress(exchange));
        };
    }

    private String getRemoteAddress(ServerWebExchange exchange) {
        String remoteAddress = exchange.getRequest().getRemoteAddress() != null
                ? exchange.getRequest().getRemoteAddress().getHostString()
                : null;

        // Check X-Forwarded-For header for proxy scenarios
        if (!StringUtils.hasText(remoteAddress)) {
            remoteAddress = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        }

        return remoteAddress;
    }

    private String safeValue(String value) {
        return StringUtils.hasText(value) ? value : "unknown";
    }

    public void clearBuckets() {
        rateLimitProperties.getRules().stream()
                .map(this::resolveCacheName)
                .distinct()
                .forEach(cacheName -> {
                    Cache cache = cacheManager.getCache(cacheName);
                    if (cache != null) {
                        cache.clear();
                    }
                });
    }

    private Mono<Void> writeRateLimitedResponse(ServerWebExchange exchange) {
        AppResponse<Void> error = AppResponse.error(
                AppResponse.ErrorCode.TOO_MANY_REQUESTS.getCode(),
                messageService.getMessage(RATE_LIMIT_MESSAGE_KEY, null, resolveLocale(exchange))
        );

        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        try {
            byte[] payload = objectMapper.writeValueAsBytes(error);
            return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(payload)));
        } catch (Exception e) {
            return exchange.getResponse().setComplete();
        }
    }

    private Locale resolveLocale(ServerWebExchange exchange) {
        Locale locale = exchange.getLocaleContext().getLocale();
        return locale != null ? locale : Locale.ENGLISH;
    }
}

