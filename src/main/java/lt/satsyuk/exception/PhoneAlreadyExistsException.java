package lt.satsyuk.exception;

import lombok.Getter;

@Getter
public class PhoneAlreadyExistsException extends RuntimeException {
    private final String phone;

    public PhoneAlreadyExistsException(String phone) {
        super("Client with phone=" + phone + " already exists");
        this.phone = phone;
    }

    public String getMessageCode() {
        return "error.client.phoneExists";
    }
}