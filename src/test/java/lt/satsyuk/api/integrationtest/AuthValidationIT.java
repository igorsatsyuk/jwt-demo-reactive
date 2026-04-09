package lt.satsyuk.api.integrationtest;

import lt.satsyuk.dto.AppResponse;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;

class AuthValidationIT extends AbstractIntegrationTest {

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

