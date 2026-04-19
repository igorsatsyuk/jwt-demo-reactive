package lt.satsyuk.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lt.satsyuk.dto.AppResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class JsonAccessDeniedHandler implements ServerAccessDeniedHandler {

    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException denied) {
        meterRegistry.counter(
                "security.http.responses",
                "status",
                "403",
                "endpoint_group",
                endpointGroup(exchange)
        ).increment();

        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        AppResponse<Void> error = AppResponse.error(
                AppResponse.ErrorCode.FORBIDDEN.getCode(),
                AppResponse.ErrorCode.FORBIDDEN.getDescription()
        );

        try {
            byte[] payload = objectMapper.writeValueAsBytes(error);
            return response.writeWith(Mono.just(response.bufferFactory().wrap(payload)));
        } catch (Exception _) {
            return response.setComplete();
        }
    }

    private String endpointGroup(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        if (path == null || path.isBlank()) {
            return "unknown";
        }
        if (path.startsWith("/api/auth/")) {
            return "auth";
        }
        if (path.startsWith("/api/clients")) {
            return "clients";
        }
        if (path.startsWith("/api/requests")) {
            return "requests";
        }
        if (path.startsWith("/api/accounts")) {
            return "accounts";
        }
        if (path.startsWith("/actuator")) {
            return "actuator";
        }
        if (path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs")) {
            return "docs";
        }
        return "other";
    }
}

