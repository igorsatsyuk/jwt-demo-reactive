package lt.satsyuk.exception;

import lt.satsyuk.dto.AppResponse;
import lt.satsyuk.service.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.UnsupportedMediaTypeStatusException;
import org.springframework.web.bind.support.WebExchangeBindException;
import reactor.core.publisher.Mono;

import java.util.Locale;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private static final String VALIDATION_FAILED_MESSAGE_KEY = "error.validation.failed";
    private static final String INVALID_UUID_PREFIX = "Invalid UUID string: ";

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

    @ExceptionHandler(HandlerMethodValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Mono<AppResponse<Void>> handleHandlerMethodValidation(HandlerMethodValidationException ex, ServerWebExchange exchange) {
        Locale locale = resolveLocale(exchange);
        String errorMessage = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> resolveParameterName(result, error) + ": " + resolveLocalizedMessage(error, locale)))
                .reduce((a, b) -> a + "; " + b)
                .orElseGet(() -> messageService.getMessage(VALIDATION_FAILED_MESSAGE_KEY, null, locale));

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
        String bindValidationMessage = extractBindValidationMessage(ex);
        if (bindValidationMessage != null) {
            return Mono.just(AppResponse.error(AppResponse.ErrorCode.BAD_REQUEST.getCode(), bindValidationMessage));
        }

        String invalidUuid = extractInvalidUuidValue(ex);
        String message = invalidUuid != null
                ? messageService.getMessage("error.typeMismatch", new Object[]{invalidUuid})
                : ex.getReason() != null ? ex.getReason() : messageService.getMessage(VALIDATION_FAILED_MESSAGE_KEY);
        return Mono.just(AppResponse.error(AppResponse.ErrorCode.BAD_REQUEST.getCode(), message));
    }

    private String extractBindValidationMessage(ServerWebInputException ex) {
        WebExchangeBindException bindException = null;
        if (ex instanceof WebExchangeBindException casted) {
            bindException = casted;
        } else {
            Throwable current = ex.getCause();
            while (current != null) {
                if (current instanceof WebExchangeBindException casted) {
                    bindException = casted;
                    break;
                }
                current = current.getCause();
            }
        }

        if (bindException == null) {
            return null;
        }

        return bindException.getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse(null);
    }

    private String extractInvalidUuidValue(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof IllegalArgumentException illegalArgumentException) {
                String message = illegalArgumentException.getMessage();
                if (message != null && message.startsWith(INVALID_UUID_PREFIX)) {
                    return message.substring(INVALID_UUID_PREFIX.length()).trim();
                }
            }
            current = current.getCause();
        }
        return null;
    }

    private String resolveLocalizedMessage(MessageSourceResolvable resolvable, Locale locale) {
        String defaultMessage = resolvable.getDefaultMessage();
        if (defaultMessage != null && defaultMessage.startsWith("{") && defaultMessage.endsWith("}")) {
            String key = defaultMessage.substring(1, defaultMessage.length() - 1);
            return messageService.getMessage(key, null, locale);
        }
        if (defaultMessage != null && !defaultMessage.isBlank()) {
            return defaultMessage;
        }
        String[] codes = resolvable.getCodes();
        if (codes != null) {
            for (String code : codes) {
                try {
                    return messageService.getMessage(code, null, locale);
                } catch (Exception ignored) {
                    // Try the next code and fallback to generic validation message.
                }
            }
        }
        return messageService.getMessage(VALIDATION_FAILED_MESSAGE_KEY, null, locale);
    }

    private String resolveParameterName(ParameterValidationResult result, MessageSourceResolvable resolvable) {
        String[] codes = resolvable.getCodes();
        if (codes != null) {
            for (String code : codes) {
                int idx = code.lastIndexOf('.');
                if (idx >= 0 && idx + 1 < code.length()) {
                    String candidate = code.substring(idx + 1);
                    if (!candidate.isBlank()) {
                        return candidate;
                    }
                }
            }
        }
        String parameterName = result.getMethodParameter().getParameterName();
        return parameterName != null ? parameterName : "parameter";
    }

    private Locale resolveLocale(ServerWebExchange exchange) {
        Locale locale = exchange.getLocaleContext().getLocale();
        return locale != null ? locale : Locale.ENGLISH;
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
