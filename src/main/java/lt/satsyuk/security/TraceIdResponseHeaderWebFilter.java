package lt.satsyuk.security;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "app.trace-id-header.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class TraceIdResponseHeaderWebFilter implements WebFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String MDC_TRACE_ID_KEY = "traceId";

    private final Tracer tracer;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String traceId = resolveTraceId();
        if (StringUtils.hasText(traceId)) {
            exchange.getResponse().getHeaders().add(TRACE_ID_HEADER, traceId);
        }

        exchange.getResponse().beforeCommit(() -> {
            if (!StringUtils.hasText(exchange.getResponse().getHeaders().getFirst(TRACE_ID_HEADER))) {
                String lateTraceId = resolveTraceId();
                if (StringUtils.hasText(lateTraceId)) {
                    exchange.getResponse().getHeaders().add(TRACE_ID_HEADER, lateTraceId);
                }
            }
            return Mono.empty();
        });

        return chain.filter(exchange);
    }

    private String resolveTraceId() {
        Span span = tracer.currentSpan();
        if (span != null) {
            TraceContext context = span.context();
            if (StringUtils.hasText(context.traceId())) {
                return context.traceId();
            }
        }

        String traceIdFromMdc = MDC.get(MDC_TRACE_ID_KEY);
        return StringUtils.hasText(traceIdFromMdc) ? traceIdFromMdc : null;
    }
}

