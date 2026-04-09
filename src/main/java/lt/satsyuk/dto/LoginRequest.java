package lt.satsyuk.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "{validation.username.required}")
        @Schema(example = "jdoe")
        String username,
        @NotBlank(message = "{validation.password.required}")
        @Schema(example = "P@ssw0rd!")
        String password,
        @NotBlank(message = "{validation.clientId.required}")
        @Schema(example = "spring-app")
        String clientId,
        @NotBlank(message = "{validation.clientSecret.required}")
        @Schema(example = "spring-app-secret")
        String clientSecret
) {}