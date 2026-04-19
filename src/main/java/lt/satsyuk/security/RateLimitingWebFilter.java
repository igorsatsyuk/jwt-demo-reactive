package lt.satsyuk.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lt.satsyuk.config.RateLimitProperties;
import lt.satsyuk.dto.AppResponse;
import lt.satsyuk.service.MessageService;
import lt.satsyuk.service.SecurityService;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpMethod;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class RateLimitingWebFilter implements WebFilter {

    private static final String RATE_LIMIT_MESSAGE_KEY = "api.error.tooManyRequests";
    private static final String DECISION_ALLOWED = "allowed";
    private static final String DECISION_REJECTED = "rejected";
    public static final String DEFAULT_RATE_LIMIT_BUCKETS_CACHE = "rateLimitBuckets";

    private final SecurityService securityService;
    private final MessageService messageService;
    private final RateLimitProperties rateLimitProperties;
    private final CacheManager cacheManager;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final List<CompiledRule> activeRules;
    private final ConcurrentMap<String, Counter> decisionCounters = new ConcurrentHashMap<>();
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public RateLimitingWebFilter(SecurityService securityService,
                                 MessageService messageService,
                                 RateLimitProperties rateLimitProperties,
                                 CacheManager cacheManager,
                                 ObjectMapper objectMapper,
                                 MeterRegistry meterRegistry) {
        this.securityService = securityService;
        this.messageService = messageService;
        this.rateLimitProperties = rateLimitProperties;
        this.cacheManager = cacheManager;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.activeRules = compileRules();
        preRegisterDecisionCounters();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        String method = Optional.ofNullable(exchange.getRequest().getMethod())
                .map(HttpMethod::name)
                .orElse("");

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
        for (CompiledRule rule : activeRules) {
            if (!matchesRequest(rule, method, path) || !matchesClient(rule, auth)) {
                continue;
            }

            String key = resolveKey(rule, exchange, auth);
            boolean shouldLimit = isRateLimited(rule, key);
            recordRateLimitDecision(rule, shouldLimit ? DECISION_REJECTED : DECISION_ALLOWED);

            if (shouldLimit) {
                return writeRateLimitedResponse(exchange);
            }
        }
        return chain.filter(exchange);
    }

    private List<CompiledRule> compileRules() {
        return rateLimitProperties.getRules().stream()
                .filter(RateLimitProperties.Rule::isEnabled)
                .sorted(Comparator.comparingInt(RateLimitProperties.Rule::getOrder)
                        .thenComparing(RateLimitProperties.Rule::getId))
                .map(this::compileRule)
                .toList();
    }

    private CompiledRule compileRule(RateLimitProperties.Rule rule) {
        Set<String> normalizedMethods = normalizeMethods(rule.getMethods());
        boolean methodsUnrestricted = normalizedMethods.isEmpty();
        boolean wildcardMethod = normalizedMethods.contains("*");
        Cache cache = cacheManager.getCache(resolveCacheName(rule));
        return new CompiledRule(
                rule,
                cache,
                safeValue(rule.getId()),
                rule.getKeyStrategy().name().toLowerCase(Locale.ROOT),
                methodsUnrestricted,
                wildcardMethod,
                normalizedMethods
        );
    }

    private Set<String> normalizeMethods(Set<String> methods) {
        if (methods == null || methods.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new HashSet<>();
        for (String candidate : methods) {
            normalized.add(candidate == null ? "" : candidate.trim().toUpperCase(Locale.ROOT));
        }
        return Set.copyOf(normalized);
    }

    private boolean matchesRequest(CompiledRule rule, String method, String path) {
        return matchesPath(rule, path) && matchesMethod(rule, method);
    }

    private boolean matchesPath(CompiledRule rule, String path) {
        return path != null && pathMatcher.match(rule.rule().getPathPattern(), path);
    }

    private boolean matchesMethod(CompiledRule rule, String method) {
        if (rule.methodsUnrestricted()) {
            return true;
        }
        if (!StringUtils.hasText(method)) {
            return false;
        }
        if (rule.wildcardMethod()) {
            return true;
        }

        String normalizedMethod = method.trim().toUpperCase(Locale.ROOT);
        return rule.normalizedMethods().contains(normalizedMethod);
    }

    private boolean matchesClient(CompiledRule rule, Authentication auth) {
        Set<String> clientIds = rule.rule().getClientIds();
        if (clientIds == null || clientIds.isEmpty()) {
            return true;
        }
        return clientIds.contains(safeValue(securityService.clientId(auth)));
    }

    private boolean isRateLimited(CompiledRule rule, String key) {
        return !resolveBucket(rule, key).tryConsume(1);
    }

    private Bucket resolveBucket(CompiledRule rule, String key) {
        Cache cache = rule.cache();
        String cacheName = resolveCacheName(rule.rule());
        if (cache == null) {
            throw new IllegalStateException("Cache '" + cacheName + "' is not configured");
        }

        String cacheKey = rule.rule().getId() + ":" + key;
        return cache.get(cacheKey, () -> Bucket.builder()
                .addLimit(Bandwidth.classic(
                        rule.rule().getCapacity(),
                        Refill.intervally(rule.rule().getCapacity(), Duration.ofSeconds(rule.rule().getWindowSeconds()))
                ))
                .build()
        );
    }

    private String resolveCacheName(RateLimitProperties.Rule rule) {
        return StringUtils.hasText(rule.getCacheName())
                ? rule.getCacheName()
                : DEFAULT_RATE_LIMIT_BUCKETS_CACHE;
    }

    private String resolveKey(CompiledRule rule, ServerWebExchange exchange, Authentication auth) {
        return switch (rule.rule().getKeyStrategy()) {
            case IP -> "ip:" + safeValue(getRemoteAddress(exchange));
            case CLIENT_ID -> "client:" + safeValue(securityService.clientId(auth));
            case USERNAME -> "user:" + safeValue(securityService.username(auth));
            case HEADER -> "header:" + safeValue(exchange.getRequest().getHeaders().getFirst(rule.rule().getKeyHeader()));
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

    private void recordRateLimitDecision(CompiledRule rule, String decision) {
        String strategy = rule.strategyTag();
        String ruleId = rule.ruleId();
        decisionCounters
                .computeIfAbsent(counterKey(ruleId, strategy, decision),
                        cacheKey -> registerDecisionCounter(ruleId, strategy, decision))
                .increment();
    }

    private void preRegisterDecisionCounters() {
        for (CompiledRule rule : activeRules) {
            String ruleId = rule.ruleId();
            String strategy = rule.strategyTag();
            decisionCounters.putIfAbsent(counterKey(ruleId, strategy, DECISION_ALLOWED), registerDecisionCounter(ruleId, strategy, DECISION_ALLOWED));
            decisionCounters.putIfAbsent(counterKey(ruleId, strategy, DECISION_REJECTED), registerDecisionCounter(ruleId, strategy, DECISION_REJECTED));
        }
    }

    private String counterKey(String ruleId, String strategy, String decision) {
        return ruleId + "|" + strategy + "|" + decision;
    }

    private Counter registerDecisionCounter(String ruleId, String strategy, String decision) {
        return meterRegistry.counter(
                "security.rate_limit.decisions",
                "rule_id",
                ruleId,
                "key_strategy",
                strategy,
                "decision",
                decision
        );
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
        } catch (Exception _) {
            return exchange.getResponse().setComplete();
        }
    }

    private Locale resolveLocale(ServerWebExchange exchange) {
        Locale locale = exchange.getLocaleContext().getLocale();
        return locale != null ? locale : Locale.ENGLISH;
    }

    private record CompiledRule(RateLimitProperties.Rule rule,
                                Cache cache,
                                String ruleId,
                                String strategyTag,
                                boolean methodsUnrestricted,
                                boolean wildcardMethod,
                                Set<String> normalizedMethods) {
    }
}

