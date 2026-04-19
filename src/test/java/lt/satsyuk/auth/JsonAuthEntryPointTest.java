package lt.satsyuk.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.InsufficientAuthenticationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JsonAuthEntryPointTest {

    @Test
    void commence_writesUnauthorizedJsonPayload() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        JsonAuthEntryPoint entryPoint = new JsonAuthEntryPoint(new ObjectMapper(), meterRegistry);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/accounts/client/1").build());

        entryPoint.commence(exchange, new InsufficientAuthenticationException("unauthorized")).block();

        assertThat(exchange.getResponse().getStatusCode()).isNotNull();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(401);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isNotNull().hasToString("application/json");

        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).contains("\"code\":40101", "\"message\":\"Unauthorized\"");
        assertThat(meterRegistry.counter(
                "security.http.responses",
                "status",
                "401",
                "endpoint_group",
                "accounts"
        ).count()).isEqualTo(1.0d);
    }

    @Test
    void commence_completesResponseWhenSerializationFails() throws Exception {
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        when(objectMapper.writeValueAsBytes(any())).thenThrow(new RuntimeException("boom"));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        JsonAuthEntryPoint entryPoint = new JsonAuthEntryPoint(objectMapper, meterRegistry);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/clients/1").build());

        entryPoint.commence(exchange, new InsufficientAuthenticationException("unauthorized")).block();

        assertThat(exchange.getResponse().getStatusCode()).isNotNull();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(401);
        assertThat(exchange.getResponse().getBodyAsString().block()).isEmpty();
        assertThat(meterRegistry.counter(
                "security.http.responses",
                "status",
                "401",
                "endpoint_group",
                "clients"
        ).count()).isEqualTo(1.0d);
    }
}
