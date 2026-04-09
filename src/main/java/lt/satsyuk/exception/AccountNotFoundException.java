package lt.satsyuk.exception;

import lombok.Getter;

@Getter
public class AccountNotFoundException extends RuntimeException {
    private final Long clientId;

    public AccountNotFoundException(Long clientId) {
        super("Account for client id=" + clientId + " not found");
        this.clientId = clientId;
    }

    public String getMessageCode() {
        return "error.account.notFound";
    }
}
