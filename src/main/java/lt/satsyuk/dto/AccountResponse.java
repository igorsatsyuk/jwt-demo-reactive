package lt.satsyuk.dto;

import java.math.BigDecimal;

public record AccountResponse(
        Long accountId,
        Long clientId,
        BigDecimal balance
) {}
