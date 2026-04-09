package lt.satsyuk.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(
        @NotBlank(message = "{validation.refreshToken.required}")
        @Schema(example = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...")
        String refreshToken,
        @NotBlank(message = "{validation.clientId.required}")
        @Schema(example = "spring-app")
        String clientId,
        @NotBlank(message = "{validation.clientSecret.required}")
        @Schema(example = "spring-app-secret")
        String clientSecret
) {}