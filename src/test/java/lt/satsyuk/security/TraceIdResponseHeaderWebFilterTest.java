package lt.satsyuk.security;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TraceIdResponseHeaderWebFilterTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void filter_addsHeaderFromCurrentSpan() {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn("trace-123");

        TraceIdResponseHeaderWebFilter filter = new TraceIdResponseHeaderWebFilter(tracer);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build());
        WebFilterChain chain = e -> e.getResponse().setComplete();

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getHeaders().getFirst(TraceIdResponseHeaderWebFilter.TRACE_ID_HEADER))
                .isEqualTo("trace-123");
    }

    @Test
    void filter_fallsBackToMdcWhenSpanMissing() {
        Tracer tracer = mock(Tracer.class);
        when(tracer.currentSpan()).thenReturn(null);
        MDC.put("traceId", "mdc-456");

        TraceIdResponseHeaderWebFilter filter = new TraceIdResponseHeaderWebFilter(tracer);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build());

        filter.filter(exchange, e -> e.getResponse().setComplete()).block();

        assertThat(exchange.getResponse().getHeaders().getFirst(TraceIdResponseHeaderWebFilter.TRACE_ID_HEADER))
                .isEqualTo("mdc-456");
    }

    @Test
    void filter_addsHeaderBeforeCommitWhenTraceAppearsLater() {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(null, span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn("late-trace");

        TraceIdResponseHeaderWebFilter filter = new TraceIdResponseHeaderWebFilter(tracer);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build());

        filter.filter(exchange, e -> e.getResponse().setComplete()).block();

        assertThat(exchange.getResponse().getHeaders().getFirst(TraceIdResponseHeaderWebFilter.TRACE_ID_HEADER))
                .isEqualTo("late-trace");
    }

    @Test
    void filter_doesNotResolveLateTraceWhenHeaderAlreadySetByDownstream() {
        Tracer tracer = mock(Tracer.class);
        when(tracer.currentSpan()).thenReturn(null);

        TraceIdResponseHeaderWebFilter filter = new TraceIdResponseHeaderWebFilter(tracer);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build());

        filter.filter(exchange, e -> {
            e.getResponse().getHeaders().add(TraceIdResponseHeaderWebFilter.TRACE_ID_HEADER, "existing");
            return e.getResponse().setComplete();
        }).block();

        assertThat(exchange.getResponse().getHeaders().get(TraceIdResponseHeaderWebFilter.TRACE_ID_HEADER))
                .containsExactly("existing");
        verify(tracer, times(1)).currentSpan();
    }
}

