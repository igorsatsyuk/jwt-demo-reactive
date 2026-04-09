package lt.satsyuk.exception;

import lombok.Getter;

import java.util.UUID;

@Getter
public class RequestNotFoundException extends RuntimeException {
    private final UUID requestId;

    public RequestNotFoundException(UUID requestId) {
        super("Request with id=" + requestId + " not found");
        this.requestId = requestId;
    }

    public String getMessageCode() {
        return "error.request.notFound";
    }
}

