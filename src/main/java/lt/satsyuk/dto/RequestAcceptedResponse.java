package lt.satsyuk.dto;

import lt.satsyuk.model.RequestStatus;

import java.util.UUID;

public record RequestAcceptedResponse(
        UUID requestId,
        RequestStatus status
) {
}

