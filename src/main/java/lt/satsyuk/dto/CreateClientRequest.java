package lt.satsyuk.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateClientRequest(

        @NotBlank(message = "{validation.firstName.required}")
        @Size(max = 100)
        @Schema(example = "John")
        String firstName,

        @NotBlank(message = "{validation.lastName.required}")
        @Size(max = 100)
        @Schema(example = "Doe")
        String lastName,

        @NotBlank(message = "{validation.phone.required}")
        @Pattern(regexp = "\\+?\\d{7,15}", message = "{validation.phone.invalid}")
        @Schema(example = "+37060000000")
        String phone,

        @Schema(description = "Optional idempotency key. If provided, used as request id instead of generating a new one.",
                example = "550e8400-e29b-41d4-a716-446655440000")
        UUID idempotencyKey
) {}