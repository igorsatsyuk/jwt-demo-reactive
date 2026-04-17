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

import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(name = "app.trace-id-header.enabled", havingValue = "true", matchIfMissing = true)
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class TraceIdResponseHeaderWebFilter implements WebFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String MDC_TRACE_ID_KEY = "traceId";
    private static final String INVALID_ZERO_TRACE_ID = "00000000000000000000000000000000";
    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("^[0-9a-f]{32}$", Pattern.CASE_INSENSITIVE);

    private final Tracer tracer;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        exposeTraceAndRequestHeadersForCors(exchange);
        setRequestIdHeaderIfMissing(exchange);

        setTraceIdHeaderIfMissing(exchange);

        exchange.getResponse().beforeCommit(() -> {
            exposeTraceAndRequestHeadersForCors(exchange);
            setRequestIdHeaderIfMissing(exchange);

            setTraceIdHeaderIfMissing(exchange);
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
        return StringUtils.hasText(candidate)
                && !INVALID_ZERO_TRACE_ID.equals(candidate)
                && TRACE_ID_PATTERN.matcher(candidate).matches();
    }

    private void setTraceIdHeaderIfMissing(ServerWebExchange exchange) {
        if (StringUtils.hasText(exchange.getResponse().getHeaders().getFirst(TRACE_ID_HEADER))) {
            return;
        }

        String traceId = resolveTraceId();
        if (StringUtils.hasText(traceId)) {
            exchange.getResponse().getHeaders().set(TRACE_ID_HEADER, traceId);
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

    private void exposeTraceAndRequestHeadersForCors(ServerWebExchange exchange) {
        var exposeHeaders = new java.util.ArrayList<>(exchange.getResponse().getHeaders().getAccessControlExposeHeaders());
        if (!containsHeaderIgnoreCase(exposeHeaders, TRACE_ID_HEADER)) {
            exposeHeaders.add(TRACE_ID_HEADER);
        }
        if (!containsHeaderIgnoreCase(exposeHeaders, REQUEST_ID_HEADER)) {
            exposeHeaders.add(REQUEST_ID_HEADER);
        }
        exchange.getResponse().getHeaders().setAccessControlExposeHeaders(exposeHeaders);
    }

    private boolean containsHeaderIgnoreCase(java.util.List<String> headers, String expectedHeader) {
        return headers.stream().anyMatch(header -> expectedHeader.equalsIgnoreCase(header));
    }
}

