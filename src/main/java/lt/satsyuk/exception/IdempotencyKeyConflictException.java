package lt.satsyuk.exception;

import lombok.Getter;

@Getter
public class IdempotencyKeyConflictException extends RuntimeException {

    private final String messageCode;

    public IdempotencyKeyConflictException(String messageCode) {
        super(messageCode);
        this.messageCode = messageCode;
    }
}
