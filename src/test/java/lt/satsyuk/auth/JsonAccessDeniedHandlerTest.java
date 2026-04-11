package lt.satsyuk.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JsonAccessDeniedHandlerTest {

    @Test
    void handle_writesForbiddenJsonPayload() {
        JsonAccessDeniedHandler handler = new JsonAccessDeniedHandler(new ObjectMapper());
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/secure").build());

        handler.handle(exchange, new AccessDeniedException("denied")).block();

        assertThat(exchange.getResponse().getStatusCode()).isNotNull();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(403);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isNotNull().hasToString("application/json");

        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).contains("\"code\":40301", "\"message\":\"Forbidden\"");
    }

    @Test
    void handle_completesResponseWhenSerializationFails() throws Exception {
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        when(objectMapper.writeValueAsBytes(any())).thenThrow(new RuntimeException("boom"));

        JsonAccessDeniedHandler handler = new JsonAccessDeniedHandler(objectMapper);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/secure").build());

        handler.handle(exchange, new AccessDeniedException("denied")).block();

        assertThat(exchange.getResponse().getStatusCode()).isNotNull();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(403);
        assertThat(exchange.getResponse().getBodyAsString().block()).isEmpty();
    }
}

