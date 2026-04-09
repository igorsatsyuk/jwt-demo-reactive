package lt.satsyuk.dto;

public record ClientResponse(
        Long id,
        String firstName,
        String lastName,
        String phone
) {}