package lt.satsyuk.exception;

import lombok.Getter;

@Getter
public class AccountOptimisticLockException extends RuntimeException {
    private final Long clientId;

    public AccountOptimisticLockException(Long clientId) {
        super("Too many optimistic lock retries for client id=" + clientId);
        this.clientId = clientId;
    }

    public String getMessageCode() {
        return "error.account.optimisticLock";
    }
}
