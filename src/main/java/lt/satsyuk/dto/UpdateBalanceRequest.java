package lt.satsyuk.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

public record UpdateBalanceRequest(
        @Schema(description = "Idempotency key. When provided, used for idempotent request tracking.")
        UUID idempotencyKey,

        @NotNull(message = "{validation.clientId.required}")
        @Positive(message = "{validation.clientId.positive}")
        @Schema(example = "1")
        Long clientId,

        @NotNull(message = "{validation.amount.required}")
        @Schema(example = "100.50")
        BigDecimal amount
) {}
