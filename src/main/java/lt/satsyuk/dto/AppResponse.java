package lt.satsyuk.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AppResponse<T>(int code, T data, String message) {

    @AllArgsConstructor
    @Getter
    public enum ErrorCode {
        BAD_REQUEST(40001, "Bad request"),
        UNAUTHORIZED(40101, "Unauthorized"),
        INVALID_GRANT(40102, "Invalid grant"),
        INVALID_TOKEN(40103, "Invalid token"),
        FORBIDDEN(40301, "Forbidden"),
        TOO_MANY_REQUESTS(42901, "Too many requests"),
        NOT_FOUND(40401, "Not found"),
        CONFLICT(40901, "Conflict"),
        INTERNAL_SERVER_ERROR(50000, "Internal server error");

        private final int code;
        private final String description;
    }

    public static <T> AppResponse<T> ok(T data) {
        return new AppResponse<>(0, data, "OK");
    }

    public static <T> AppResponse<T> error(int code, String message) {
        return new AppResponse<>(code, null, message);
    }
}
