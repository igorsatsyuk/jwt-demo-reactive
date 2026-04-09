package lt.satsyuk.dto;

import lt.satsyuk.model.RequestStatus;
import lt.satsyuk.model.RequestType;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RequestStatusResponse(
        UUID requestId,
        RequestType type,
        RequestStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime statusChangedAt,
        Object response
) {
}

