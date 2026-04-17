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

    private static final String VALID_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String LATE_TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
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
        when(context.traceId()).thenReturn(VALID_TRACE_ID);

        TraceIdResponseHeaderWebFilter filter = new TraceIdResponseHeaderWebFilter(tracer);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build());
        WebFilterChain chain = e -> e.getResponse().setComplete();

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getHeaders().getFirst(TraceIdResponseHeaderWebFilter.TRACE_ID_HEADER))
                .isEqualTo(VALID_TRACE_ID);
        assertThat(exchange.getResponse().getHeaders().getFirst(TraceIdResponseHeaderWebFilter.REQUEST_ID_HEADER))
                .isEqualTo(exchange.getRequest().getId());
    }

    @Test
    void filter_fallsBackToMdcWhenSpanMissing() {
        Tracer tracer = mock(Tracer.class);
        when(tracer.currentSpan()).thenReturn(null);
        MDC.put("traceId", VALID_TRACE_ID);

        TraceIdResponseHeaderWebFilter filter = new TraceIdResponseHeaderWebFilter(tracer);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build());

        filter.filter(exchange, e -> e.getResponse().setComplete()).block();

        assertThat(exchange.getResponse().getHeaders().getFirst(TraceIdResponseHeaderWebFilter.TRACE_ID_HEADER))
                .isEqualTo(VALID_TRACE_ID);
        assertThat(exchange.getResponse().getHeaders().getFirst(TraceIdResponseHeaderWebFilter.REQUEST_ID_HEADER))
                .isEqualTo(exchange.getRequest().getId());
    }

    @Test
    void filter_addsHeaderBeforeCommitWhenTraceAppearsLater() {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(null, span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn(LATE_TRACE_ID);

        TraceIdResponseHeaderWebFilter filter = new TraceIdResponseHeaderWebFilter(tracer);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build());

        filter.filter(exchange, e -> e.getResponse().setComplete()).block();

        assertThat(exchange.getResponse().getHeaders().getFirst(TraceIdResponseHeaderWebFilter.TRACE_ID_HEADER))
                .isEqualTo(LATE_TRACE_ID);
        assertThat(exchange.getResponse().getHeaders().getFirst(TraceIdResponseHeaderWebFilter.REQUEST_ID_HEADER))
                .isEqualTo(exchange.getRequest().getId());
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
        assertThat(exchange.getResponse().getHeaders().getFirst(TraceIdResponseHeaderWebFilter.REQUEST_ID_HEADER))
                .isEqualTo(exchange.getRequest().getId());
        verify(tracer, times(1)).currentSpan();
    }

    @Test
    void filter_exposesTraceHeaderForCors() {
        Tracer tracer = mock(Tracer.class);
        when(tracer.currentSpan()).thenReturn(null);

        TraceIdResponseHeaderWebFilter filter = new TraceIdResponseHeaderWebFilter(tracer);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build());

        filter.filter(exchange, e -> e.getResponse().setComplete()).block();

        assertThat(exchange.getResponse().getHeaders().getAccessControlExposeHeaders())
                .contains(TraceIdResponseHeaderWebFilter.TRACE_ID_HEADER, TraceIdResponseHeaderWebFilter.REQUEST_ID_HEADER);
    }

    @Test
    void filter_doesNotSetTraceIdWhenTraceSourcesAreMissing() {
        Tracer tracer = mock(Tracer.class);
        when(tracer.currentSpan()).thenReturn(null);

        TraceIdResponseHeaderWebFilter filter = new TraceIdResponseHeaderWebFilter(tracer);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build());

        filter.filter(exchange, e -> e.getResponse().setComplete()).block();

        assertThat(exchange.getResponse().getHeaders().getFirst(TraceIdResponseHeaderWebFilter.TRACE_ID_HEADER))
                .isNull();
        assertThat(exchange.getResponse().getHeaders().getFirst(TraceIdResponseHeaderWebFilter.REQUEST_ID_HEADER))
                .isEqualTo(exchange.getRequest().getId());
    }

    @Test
    void filter_ignoresInvalidTraceIdFromMdcWithoutFallbackTrace() {
        Tracer tracer = mock(Tracer.class);
        when(tracer.currentSpan()).thenReturn(null);
        MDC.put("traceId", "trace-123");

        TraceIdResponseHeaderWebFilter filter = new TraceIdResponseHeaderWebFilter(tracer);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build());

        filter.filter(exchange, e -> e.getResponse().setComplete()).block();

        assertThat(exchange.getResponse().getHeaders().getFirst(TraceIdResponseHeaderWebFilter.TRACE_ID_HEADER))
                .isNull();
        assertThat(exchange.getResponse().getHeaders().getFirst(TraceIdResponseHeaderWebFilter.REQUEST_ID_HEADER))
                .isEqualTo(exchange.getRequest().getId());
    }
}

