package lt.satsyuk.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record UpdateBalanceRequest(
        @NotNull(message = "{validation.clientId.required}")
        @Schema(example = "1")
        Long clientId,

        @NotNull(message = "{validation.amount.required}")
        @Schema(example = "100.50")
        BigDecimal amount
) {}
