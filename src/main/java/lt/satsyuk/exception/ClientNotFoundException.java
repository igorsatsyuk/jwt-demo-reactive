package lt.satsyuk.exception;

import lombok.Getter;

@Getter
public class ClientNotFoundException extends RuntimeException {
    private final Long clientId;

    public ClientNotFoundException(Long id) {
        super("Client with id=" + id + " not found");
        this.clientId = id;
    }

    public String getMessageCode() {
        return "error.client.notFound";
    }
}