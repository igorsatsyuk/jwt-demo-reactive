package lt.satsyuk.api.integrationtest;

import lt.satsyuk.dto.AppResponse;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

import static org.assertj.core.api.Assertions.assertThat;

class AuthValidationIT extends AbstractIntegrationTest {

    private static final String TRACE_ID_REGEX = "(?i)^[0-9a-f]{32}$";

    @Test
    void login_unsupported_media_type_includes_trace_id_header() {
        EntityExchangeResult<AppResponse<Void>> result = webTestClient.post()
                .uri("/api/auth/login")
                .contentType(MediaType.TEXT_PLAIN)
                .bodyValue("username=user&password=password")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(new ParameterizedTypeReference<AppResponse<Void>>() {})
                .returnResult();

        String traceId = result.getResponseHeaders().getFirst("X-Trace-Id");
        String requestId = result.getResponseHeaders().getFirst("X-Request-Id");
        assertThat(requestId).isNotBlank();
        if (traceId != null) {
            assertThat(traceId).matches(TRACE_ID_REGEX);
        }
        assertThat(result.getResponseHeaders().getAccessControlExposeHeaders())
                .contains("X-Trace-Id", "X-Request-Id");
    }

    @Test
    void login_unsupported_media_type_returns_bad_request_envelope() {
        AppResponse<Void> body = webTestClient.post()
                .uri("/api/auth/login")
                .contentType(MediaType.TEXT_PLAIN)
                .bodyValue("username=user&password=password")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(new ParameterizedTypeReference<AppResponse<Void>>() {})
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo(AppResponse.ErrorCode.BAD_REQUEST.getCode());
        assertThat(body.message()).contains("Content type");
    }
}

