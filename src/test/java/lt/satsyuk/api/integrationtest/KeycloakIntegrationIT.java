package lt.satsyuk.api.integrationtest;

import lt.satsyuk.api.util.KeycloakIntegrationTest;
import lt.satsyuk.dto.AppResponse;
import lt.satsyuk.dto.KeycloakTokenResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakIntegrationIT extends KeycloakIntegrationTest {

    @Test
    void login_success() {
        KeycloakTokenResponse data = loginAndGetData(USERNAME, USER_PASSWORD);
        assertThat(data).isNotNull();
        assertThat(data.getAccessToken()).isNotBlank();
        assertThat(data.getRefreshToken()).isNotBlank();
    }

    @Test
    void login_wrong_password() {
        EntityExchangeResult<AppResponse<KeycloakTokenResponse>> result =
                loginExchange(USERNAME, "wrongpassword");

        assertErrorResult(result, 401, AppResponse.ErrorCode.UNAUTHORIZED.getCode(), INVALID_GRANT);
    }

    @Test
    void login_unknown_user() {
        EntityExchangeResult<AppResponse<KeycloakTokenResponse>> result =
                loginExchange("unknownuser", "whatever");

        assertErrorResult(result, 401, AppResponse.ErrorCode.UNAUTHORIZED.getCode(), INVALID_GRANT);
    }

    @Test
    void admin_login_success() {
        KeycloakTokenResponse data = loginAndGetData(ADMIN, ADMIN_PASSWORD);
        assertThat(data).isNotNull();
        assertThat(data.getAccessToken()).isNotBlank();
        assertThat(data.getRefreshToken()).isNotBlank();
    }

    @Test
    void refresh_success() {
        String refreshToken = loginAndGetRefresh(USERNAME, USER_PASSWORD);

        EntityExchangeResult<AppResponse<KeycloakTokenResponse>> refreshResult =
                refreshExchange(refreshToken, CLIENT_ID, CLIENT_SECRET);

        assertThat(refreshResult.getStatus().value()).isEqualTo(200);
        AppResponse<KeycloakTokenResponse> body = refreshResult.getResponseBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isZero();
        assertThat(body.data()).isNotNull();
        assertThat(body.data().getAccessToken()).isNotBlank();
        assertThat(body.data().getRefreshToken()).isNotBlank();
    }

    @Test
    void refresh_wrong_token() {
        EntityExchangeResult<AppResponse<KeycloakTokenResponse>> refreshResult =
                refreshExchange("invalid-token", CLIENT_ID, CLIENT_SECRET);

        assertErrorResult(refreshResult, 400, AppResponse.ErrorCode.INVALID_GRANT.getCode(), INVALID_GRANT);
    }

    @Test
    void logout_success() {
        String refreshToken = loginAndGetRefresh(USERNAME, USER_PASSWORD);

        EntityExchangeResult<AppResponse<Void>> logoutResult =
                logoutExchange(refreshToken, CLIENT_ID, CLIENT_SECRET);

        assertThat(logoutResult.getStatus().value()).isEqualTo(200);
        AppResponse<Void> body = logoutResult.getResponseBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isZero();
    }

    @Test
    void logout_wrong_token() {
        EntityExchangeResult<AppResponse<Void>> logoutResult =
                logoutExchange("invalid-token", CLIENT_ID, CLIENT_SECRET);

        assertErrorResult(logoutResult, 400, AppResponse.ErrorCode.INVALID_TOKEN.getCode(), INVALID_GRANT);
    }
}




