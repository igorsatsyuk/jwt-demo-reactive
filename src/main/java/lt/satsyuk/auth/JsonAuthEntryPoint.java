package lt.satsyuk.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lt.satsyuk.dto.AppResponse;
import lt.satsyuk.security.SecurityEndpointGroupResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class JsonAuthEntryPoint implements ServerAuthenticationEntryPoint {

    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        meterRegistry.counter(
                "security.http.responses",
                "status",
                "401",
                "endpoint_group",
                SecurityEndpointGroupResolver.resolve(exchange.getRequest().getPath().value())
        ).increment();

        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        AppResponse<Void> error = AppResponse.error(
                AppResponse.ErrorCode.UNAUTHORIZED.getCode(),
                AppResponse.ErrorCode.UNAUTHORIZED.getDescription()
        );

        try {
            byte[] payload = objectMapper.writeValueAsBytes(error);
            return response.writeWith(Mono.just(response.bufferFactory().wrap(payload)));
        } catch (Exception _) {
            return response.setComplete();
        }
    }
}

