package lt.satsyuk.dto;
import jakarta.validation.constraints.NotBlank;

public record LogoutRequest(
        @NotBlank(message = "{validation.refreshToken.required}")
        String refreshToken,
        @NotBlank(message = "{validation.clientId.required}")
        String clientId,
        @NotBlank(message = "{validation.clientSecret.required}")
        String clientSecret
) {}