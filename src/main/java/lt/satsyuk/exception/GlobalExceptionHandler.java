package lt.satsyuk.exception;

import lt.satsyuk.dto.AppResponse;
import lt.satsyuk.service.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.UnsupportedMediaTypeStatusException;
import reactor.core.publisher.Mono;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private static final String VALIDATION_FAILED_MESSAGE_KEY = "error.validation.failed";

    private final MessageSource messageSource;
    private final MessageService messageService;

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Mono<AppResponse<Void>> handleValidationException(MethodArgumentNotValidException ex) {
        String errorMessage = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElseGet(() -> messageService.getMessage(VALIDATION_FAILED_MESSAGE_KEY));

        return Mono.just(AppResponse.error(AppResponse.ErrorCode.BAD_REQUEST.getCode(), errorMessage));
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Mono<AppResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        return Mono.just(AppResponse.error(AppResponse.ErrorCode.FORBIDDEN.getCode(),
                messageService.getMessage("api.error.forbidden")));
    }

    @ExceptionHandler(AccountNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Mono<AppResponse<Void>> handleAccountNotFound(AccountNotFoundException ex) {
        String message = messageService.getMessage(ex.getMessageCode(), new Object[]{String.valueOf(ex.getClientId())});
        return Mono.just(AppResponse.error(AppResponse.ErrorCode.NOT_FOUND.getCode(), message));
    }

    @ExceptionHandler(AccountOptimisticLockException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Mono<AppResponse<Void>> handleAccountOptimisticLock(AccountOptimisticLockException ex) {
        String message = messageService.getMessage(ex.getMessageCode(), new Object[]{String.valueOf(ex.getClientId())});
        return Mono.just(AppResponse.error(AppResponse.ErrorCode.CONFLICT.getCode(), message));
    }

    @ExceptionHandler(ClientNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Mono<AppResponse<Void>> handleNotFound(ClientNotFoundException ex) {
        String message = messageService.getMessage(ex.getMessageCode(), new Object[]{String.valueOf(ex.getClientId())});
        return Mono.just(AppResponse.error(AppResponse.ErrorCode.NOT_FOUND.getCode(), message));
    }

    @ExceptionHandler(ClientSearchQueryTooShortException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Mono<AppResponse<Void>> handleClientSearchQueryTooShort(ClientSearchQueryTooShortException ex) {
        String message = messageService.getMessage(ex.getMessageCode(), new Object[]{String.valueOf(ex.getMinLength())});
        return Mono.just(AppResponse.error(AppResponse.ErrorCode.BAD_REQUEST.getCode(), message));
    }

    @ExceptionHandler(RequestNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Mono<AppResponse<Void>> handleRequestNotFound(RequestNotFoundException ex) {
        String message = messageService.getMessage(ex.getMessageCode(), new Object[]{String.valueOf(ex.getRequestId())});
        return Mono.just(AppResponse.error(AppResponse.ErrorCode.NOT_FOUND.getCode(), message));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Mono<AppResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String message = messageService.getMessage("error.typeMismatch", new Object[]{String.valueOf(ex.getValue())});
        return Mono.just(AppResponse.error(AppResponse.ErrorCode.BAD_REQUEST.getCode(), message));
    }

    @ExceptionHandler(ServerWebInputException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Mono<AppResponse<Void>> handleServerWebInput(ServerWebInputException ex) {
        String message = ex.getReason() != null ? ex.getReason() : messageService.getMessage(VALIDATION_FAILED_MESSAGE_KEY);
        return Mono.just(AppResponse.error(AppResponse.ErrorCode.BAD_REQUEST.getCode(), message));
    }

    @ExceptionHandler(UnsupportedMediaTypeStatusException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Mono<AppResponse<Void>> handleUnsupportedMediaType(UnsupportedMediaTypeStatusException ex) {
        String message = ex.getReason() != null ? ex.getReason() : messageService.getMessage(VALIDATION_FAILED_MESSAGE_KEY);
        return Mono.just(AppResponse.error(AppResponse.ErrorCode.BAD_REQUEST.getCode(), message));
    }

    @ExceptionHandler(PhoneAlreadyExistsException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Mono<AppResponse<Void>> handlePhoneExists(PhoneAlreadyExistsException ex) {
        String message = messageService.getMessage(ex.getMessageCode(), new Object[]{String.valueOf(ex.getPhone())});
        return Mono.just(AppResponse.error(AppResponse.ErrorCode.CONFLICT.getCode(), message));
    }

    @ExceptionHandler(KeycloakAuthException.class)
    public Mono<AppResponse<Void>> handleKeycloakAuthException(KeycloakAuthException ex, ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(ex.getStatus());
        return Mono.just(AppResponse.error(AppResponse.ErrorCode.UNAUTHORIZED.getCode(), ex.getKeycloakMessage()));
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Mono<AppResponse<Void>> handleGeneric(Exception ex) {
        log.error("Unhandled exception caught by GlobalExceptionHandler", ex);

        return Mono.just(AppResponse.error(AppResponse.ErrorCode.INTERNAL_SERVER_ERROR.getCode(),
                messageService.getMessage("api.error.internalServerError")));
    }
}
