package lt.satsyuk.security;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(name = "app.trace-id-header.enabled", havingValue = "true", matchIfMissing = true)
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class TraceIdResponseHeaderWebFilter implements WebFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String MDC_TRACE_ID_KEY = "traceId";
    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("^[0-9a-f]{32}$", Pattern.CASE_INSENSITIVE);
    private static final HexFormat HEX = HexFormat.of();
    private static final ThreadLocal<MessageDigest> SHA_256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    });

    private final Tracer tracer;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        exposeTraceHeaderForCors(exchange);
        setRequestIdHeaderIfMissing(exchange);

        String traceId = resolveTraceId();
        if (StringUtils.hasText(traceId)) {
            exchange.getResponse().getHeaders().set(TRACE_ID_HEADER, traceId);
        }

        exchange.getResponse().beforeCommit(() -> {
            exposeTraceHeaderForCors(exchange);
            setRequestIdHeaderIfMissing(exchange);

            if (!StringUtils.hasText(exchange.getResponse().getHeaders().getFirst(TRACE_ID_HEADER))) {
                String lateTraceId = resolveTraceId();
                exchange.getResponse().getHeaders().set(
                        TRACE_ID_HEADER,
                        StringUtils.hasText(lateTraceId)
                                ? lateTraceId
                                : buildFallbackTraceId(exchange.getRequest().getId())
                );
            }
            return Mono.empty();
        });

        return chain.filter(exchange);
    }

    private String resolveTraceId() {
        Span span = tracer.currentSpan();
        if (span != null) {
            TraceContext context = span.context();
            if (isValidTraceId(context.traceId())) {
                return context.traceId();
            }
        }

        String traceIdFromMdc = MDC.get(MDC_TRACE_ID_KEY);
        if (isValidTraceId(traceIdFromMdc)) {
            return traceIdFromMdc;
        }

        return null;
    }

    private boolean isValidTraceId(String candidate) {
        return StringUtils.hasText(candidate) && TRACE_ID_PATTERN.matcher(candidate).matches();
    }

    private String buildFallbackTraceId(String requestId) {
        if (!StringUtils.hasText(requestId)) {
            return UUID.randomUUID().toString().replace("-", "");
        }

        try {
            byte[] hash = SHA_256.get().digest(requestId.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(hash, 0, 16);
        } catch (RuntimeException _) {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }

    private void setRequestIdHeaderIfMissing(ServerWebExchange exchange) {
        if (StringUtils.hasText(exchange.getResponse().getHeaders().getFirst(REQUEST_ID_HEADER))) {
            return;
        }

        String requestId = exchange.getRequest().getId();
        if (StringUtils.hasText(requestId)) {
            exchange.getResponse().getHeaders().set(REQUEST_ID_HEADER, requestId);
        }
    }

    private void exposeTraceHeaderForCors(ServerWebExchange exchange) {
        var exposeHeaders = new java.util.ArrayList<>(exchange.getResponse().getHeaders().getAccessControlExposeHeaders());
        if (!exposeHeaders.contains(TRACE_ID_HEADER)) {
            exposeHeaders.add(TRACE_ID_HEADER);
        }
        if (!exposeHeaders.contains(REQUEST_ID_HEADER)) {
            exposeHeaders.add(REQUEST_ID_HEADER);
        }
        exchange.getResponse().getHeaders().setAccessControlExposeHeaders(exposeHeaders);
    }
}

