package lt.satsyuk.exception;

import lt.satsyuk.dto.AppResponse;
import lt.satsyuk.service.MessageService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.NoSuchMessageException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.server.UnsupportedMediaTypeStatusException;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final MessageService messageService = mock(MessageService.class);
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(messageService);

    @Test
    void handleValidationException_aggregatesFieldErrors() throws Exception {
        BindingResult bindingResult = mock(BindingResult.class);
        when(bindingResult.getFieldErrors()).thenReturn(List.of(
                new FieldError("loginRequest", "username", "must not be blank"),
                new FieldError("loginRequest", "password", "must not be blank")
        ));

        Method method = ValidationDummy.class.getDeclaredMethod("submit", String.class);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(new MethodParameter(method, 0), bindingResult);

        AppResponse<Void> response = handler.handleValidationException(ex).block();

        assertThat(response.code()).isEqualTo(AppResponse.ErrorCode.BAD_REQUEST.getCode());
        assertThat(response.message()).isEqualTo("username: must not be blank; password: must not be blank");
    }

    @Test
    void handleValidationException_usesDefaultMessageWhenNoFieldErrors() throws Exception {
        BindingResult bindingResult = mock(BindingResult.class);
        when(bindingResult.getFieldErrors()).thenReturn(List.of());
        when(messageService.getMessage("error.validation.failed")).thenReturn("Validation failed");

        Method method = ValidationDummy.class.getDeclaredMethod("submit", String.class);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(new MethodParameter(method, 0), bindingResult);

        AppResponse<Void> response = handler.handleValidationException(ex).block();

        assertThat(response.code()).isEqualTo(AppResponse.ErrorCode.BAD_REQUEST.getCode());
        assertThat(response.message()).isEqualTo("Validation failed");
    }

    @Test
    void handleAccessDenied_returnsForbiddenCodeAndLocalizedMessage() {
        when(messageService.getMessage("api.error.forbidden")).thenReturn("Forbidden localized");

        AppResponse<Void> response = handler.handleAccessDenied(new AccessDeniedException("nope")).block();

        assertThat(response.code()).isEqualTo(AppResponse.ErrorCode.FORBIDDEN.getCode());
        assertThat(response.message()).isEqualTo("Forbidden localized");
    }

    @Test
    void handleDomainExceptions_buildsMessageWithArgs() {
        when(messageService.getMessage(eq("error.account.notFound"), any())).thenReturn("Account 10 not found");
        when(messageService.getMessage(eq("error.account.optimisticLock"), any())).thenReturn("Lock conflict for 11");
        when(messageService.getMessage(eq("error.client.notFound"), any())).thenReturn("Client 12 not found");
        when(messageService.getMessage(eq("error.client.searchQueryTooShort"), any())).thenReturn("min length 3");
        when(messageService.getMessage(eq("error.request.notFound"), any())).thenReturn("Request not found");
        when(messageService.getMessage(eq("error.client.phoneExists"), any())).thenReturn("Phone exists");

        AppResponse<Void> accountNotFound = handler.handleAccountNotFound(new AccountNotFoundException(10L)).block();
        AppResponse<Void> lockConflict = handler.handleAccountOptimisticLock(new AccountOptimisticLockException(11L)).block();
        AppResponse<Void> clientNotFound = handler.handleNotFound(new ClientNotFoundException(12L)).block();
        AppResponse<Void> shortQuery = handler.handleClientSearchQueryTooShort(new ClientSearchQueryTooShortException(3)).block();
        AppResponse<Void> requestNotFound = handler.handleRequestNotFound(new RequestNotFoundException(UUID.randomUUID())).block();
        AppResponse<Void> phoneExists = handler.handlePhoneExists(new PhoneAlreadyExistsException("+123")).block();

        assertThat(accountNotFound.code()).isEqualTo(AppResponse.ErrorCode.NOT_FOUND.getCode());
        assertThat(lockConflict.code()).isEqualTo(AppResponse.ErrorCode.CONFLICT.getCode());
        assertThat(clientNotFound.code()).isEqualTo(AppResponse.ErrorCode.NOT_FOUND.getCode());
        assertThat(shortQuery.code()).isEqualTo(AppResponse.ErrorCode.BAD_REQUEST.getCode());
        assertThat(requestNotFound.code()).isEqualTo(AppResponse.ErrorCode.NOT_FOUND.getCode());
        assertThat(phoneExists.code()).isEqualTo(AppResponse.ErrorCode.CONFLICT.getCode());
    }

    @Test
    void handleTypeMismatch_usesMessageServiceWithInvalidValue() {
        when(messageService.getMessage(eq("error.typeMismatch"), any())).thenReturn("Invalid value: abc");

        MethodArgumentTypeMismatchException ex = mock(MethodArgumentTypeMismatchException.class);
        when(ex.getValue()).thenReturn("abc");
        AppResponse<Void> response = handler.handleTypeMismatch(ex).block();

        assertThat(response.code()).isEqualTo(AppResponse.ErrorCode.BAD_REQUEST.getCode());
        assertThat(response.message()).isEqualTo("Invalid value: abc");

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(messageService).getMessage(eq("error.typeMismatch"), argsCaptor.capture());
        assertThat(argsCaptor.getValue()).containsExactly("abc");
    }

    @Test
    void handleServerWebInput_andUnsupportedMediaType_useReasonOrFallback() {
        when(messageService.getMessage("error.validation.failed", null, Locale.ENGLISH)).thenReturn("Validation fallback");
        when(messageService.getMessage("error.validation.failed")).thenReturn("Validation fallback");

        ServerWebInputException inputWithReason = mock(ServerWebInputException.class);
        ServerWebInputException inputWithoutReason = mock(ServerWebInputException.class);
        when(inputWithReason.getReason()).thenReturn("invalid json");
        when(inputWithoutReason.getReason()).thenReturn(null);

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/test").build());
        AppResponse<Void> withReason = handler.handleServerWebInput(inputWithReason, exchange).block();
        AppResponse<Void> fallbackInput = handler.handleServerWebInput(inputWithoutReason, exchange).block();

        UnsupportedMediaTypeStatusException mediaWithReason = mock(UnsupportedMediaTypeStatusException.class);
        UnsupportedMediaTypeStatusException mediaWithoutReason = mock(UnsupportedMediaTypeStatusException.class);
        when(mediaWithReason.getReason()).thenReturn("Content type not supported");
        when(mediaWithoutReason.getReason()).thenReturn(null);

        AppResponse<Void> mediaReason = handler.handleUnsupportedMediaType(mediaWithReason).block();
        AppResponse<Void> mediaFallback = handler.handleUnsupportedMediaType(mediaWithoutReason).block();

        assertThat(withReason.message()).isEqualTo("invalid json");
        assertThat(fallbackInput.message()).isEqualTo("Validation fallback");
        assertThat(mediaReason.message()).isEqualTo("Content type not supported");
        assertThat(mediaFallback.message()).isEqualTo("Validation fallback");
    }

    @Test
    void handleServerWebInput_invalidUuid_usesTypeMismatchMessage() {
        when(messageService.getMessage(eq("error.typeMismatch"), any(), eq(Locale.ENGLISH))).thenReturn("Invalid value: not-a-uuid");

        ServerWebInputException ex = mock(ServerWebInputException.class);
        when(ex.getCause()).thenReturn(new IllegalArgumentException("Invalid UUID string: not-a-uuid"));
        when(ex.getReason()).thenReturn("reason should be ignored for invalid uuid");

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/test").build());
        AppResponse<Void> response = handler.handleServerWebInput(ex, exchange).block();

        assertThat(response).isNotNull();
        assertThat(response.code()).isEqualTo(AppResponse.ErrorCode.BAD_REQUEST.getCode());
        assertThat(response.message()).isEqualTo("Invalid value: not-a-uuid");

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(messageService).getMessage(eq("error.typeMismatch"), argsCaptor.capture(), eq(Locale.ENGLISH));
        assertThat(argsCaptor.getValue()).containsExactly("not-a-uuid");
    }

    @Test
    void handleServerWebInput_withBindException_returnsFieldValidationMessage() {
        WebExchangeBindException ex = mock(WebExchangeBindException.class);
        when(ex.getFieldErrors()).thenReturn(List.of(new FieldError("request", "clientId", "ClientId must be greater than 0")));

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/test").build());
        AppResponse<Void> response = handler.handleServerWebInput(ex, exchange).block();

        assertThat(response).isNotNull();
        assertThat(response.code()).isEqualTo(AppResponse.ErrorCode.BAD_REQUEST.getCode());
        assertThat(response.message()).isEqualTo("clientId: ClientId must be greater than 0");
    }

    @Test
    void handleHandlerMethodValidation_usesMessageServiceAndFormatsFieldError() throws Exception {
        HandlerMethodValidationException ex = mock(HandlerMethodValidationException.class);
        ParameterValidationResult result = mock(ParameterValidationResult.class);
        MessageSourceResolvable resolvable = mock(MessageSourceResolvable.class);

        Method method = ValidationDummy.class.getDeclaredMethod("submit", String.class);
        when(result.getMethodParameter()).thenReturn(new MethodParameter(method, 0));
        when(result.getResolvableErrors()).thenReturn(List.of(resolvable));
        when(resolvable.getCodes()).thenReturn(new String[]{"Positive.updateBalanceRequest.clientId"});
        when(resolvable.getDefaultMessage()).thenReturn("{validation.clientId.positive}");
        when(ex.getParameterValidationResults()).thenReturn(List.of(result));
        when(messageService.getMessage("validation.clientId.positive", null, Locale.ENGLISH))
                .thenReturn("ClientId must be greater than 0");

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/accounts/balance/pessimistic").build());

        AppResponse<Void> response = handler.handleHandlerMethodValidation(ex, exchange).block();

        assertThat(response).isNotNull();
        assertThat(response.code()).isEqualTo(AppResponse.ErrorCode.BAD_REQUEST.getCode());
        assertThat(response.message()).isEqualTo("clientId: ClientId must be greater than 0");
        verify(messageService).getMessage("validation.clientId.positive", null, Locale.ENGLISH);
    }

    @Test
    void handleHandlerMethodValidation_usesDefaultMessage_whenNotMessageKey() throws Exception {
        HandlerMethodValidationException ex = mock(HandlerMethodValidationException.class);
        ParameterValidationResult result = mock(ParameterValidationResult.class);
        MessageSourceResolvable resolvable = mock(MessageSourceResolvable.class);

        Method method = ValidationDummy.class.getDeclaredMethod("submit", String.class);
        when(result.getMethodParameter()).thenReturn(new MethodParameter(method, 0));
        when(result.getResolvableErrors()).thenReturn(List.of(resolvable));
        when(resolvable.getCodes()).thenReturn(new String[]{"validation.payload.invalid"});
        when(resolvable.getDefaultMessage()).thenReturn("must not be blank");
        when(ex.getParameterValidationResults()).thenReturn(List.of(result));

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/test").build());

        AppResponse<Void> response = handler.handleHandlerMethodValidation(ex, exchange).block();

        assertThat(response).isNotNull();
        assertThat(response.code()).isEqualTo(AppResponse.ErrorCode.BAD_REQUEST.getCode());
        assertThat(response.message()).isEqualTo("invalid: must not be blank");
    }

    @Test
    void handleHandlerMethodValidation_fallsBackToNextCode_whenLookupFails() throws Exception {
        HandlerMethodValidationException ex = mock(HandlerMethodValidationException.class);
        ParameterValidationResult result = mock(ParameterValidationResult.class);
        MessageSourceResolvable resolvable = mock(MessageSourceResolvable.class);

        Method method = ValidationDummy.class.getDeclaredMethod("submit", String.class);
        when(result.getMethodParameter()).thenReturn(new MethodParameter(method, 0));
        when(result.getResolvableErrors()).thenReturn(List.of(resolvable));
        when(resolvable.getCodes()).thenReturn(new String[]{"validation.clientId.primary", "validation.clientId.secondary"});
        when(resolvable.getDefaultMessage()).thenReturn(null);
        when(ex.getParameterValidationResults()).thenReturn(List.of(result));

        when(messageService.getMessage("validation.clientId.primary", null, Locale.ENGLISH))
                .thenThrow(new NoSuchMessageException("validation.clientId.primary"));
        when(messageService.getMessage("validation.clientId.secondary", null, Locale.ENGLISH))
                .thenReturn("ClientId must be greater than 0");

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/test").build());

        AppResponse<Void> response = handler.handleHandlerMethodValidation(ex, exchange).block();

        assertThat(response).isNotNull();
        assertThat(response.code()).isEqualTo(AppResponse.ErrorCode.BAD_REQUEST.getCode());
        assertThat(response.message()).isEqualTo("primary: ClientId must be greater than 0");
    }

    @Test
    void handleHandlerMethodValidation_withoutErrors_usesValidationFallbackMessage() {
        HandlerMethodValidationException ex = mock(HandlerMethodValidationException.class);
        when(ex.getParameterValidationResults()).thenReturn(List.of());
        when(messageService.getMessage("error.validation.failed", null, Locale.ENGLISH)).thenReturn("Validation failed");

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/test").build());

        AppResponse<Void> response = handler.handleHandlerMethodValidation(ex, exchange).block();

        assertThat(response).isNotNull();
        assertThat(response.code()).isEqualTo(AppResponse.ErrorCode.BAD_REQUEST.getCode());
        assertThat(response.message()).isEqualTo("Validation failed");
        verify(messageService).getMessage("error.validation.failed", null, Locale.ENGLISH);
    }

    @Test
    void handleKeycloakAuthException_setsStatusAndReturnsUnauthorizedEnvelope() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/login").build());
        KeycloakAuthException ex = new KeycloakAuthException("Login failed", HttpStatus.UNAUTHORIZED, "invalid_grant");

        AppResponse<Void> response = handler.handleKeycloakAuthException(ex, exchange).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.code()).isEqualTo(AppResponse.ErrorCode.UNAUTHORIZED.getCode());
        assertThat(response.message()).isEqualTo("invalid_grant");
    }

    @Test
    void handleGeneric_returnsInternalServerErrorEnvelope() {
        when(messageService.getMessage("api.error.internalServerError")).thenReturn("Internal error");

        AppResponse<Void> response = handler.handleGeneric(new RuntimeException("boom")).block();

        assertThat(response.code()).isEqualTo(AppResponse.ErrorCode.INTERNAL_SERVER_ERROR.getCode());
        assertThat(response.message()).isEqualTo("Internal error");
    }

    @Test
    void handleServerWebInput_bindExceptionInCauseChain_returnsAggregatedFieldValidationMessage() {
        WebExchangeBindException bindException = mock(WebExchangeBindException.class);
        when(bindException.getFieldErrors()).thenReturn(List.of(
                new FieldError("request", "clientId", "must be greater than 0"),
                new FieldError("request", "amount", "must be greater than 0")
        ));

        ServerWebInputException ex = mock(ServerWebInputException.class);
        Throwable nestedCause = mock(Throwable.class);
        when(nestedCause.getCause()).thenReturn(bindException);
        when(ex.getCause()).thenReturn(nestedCause);
        when(ex.getReason()).thenReturn("reason should not be used");

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/test").build());
        AppResponse<Void> response = handler.handleServerWebInput(ex, exchange).block();

        assertThat(response).isNotNull();
        assertThat(response.code()).isEqualTo(AppResponse.ErrorCode.BAD_REQUEST.getCode());
        assertThat(response.message()).isEqualTo("clientId: must be greater than 0; amount: must be greater than 0");
    }

    @Test
    void handleServerWebInput_bindExceptionWithEmptyErrors_fallsBackToReason() {
        WebExchangeBindException bindException = mock(WebExchangeBindException.class);
        when(bindException.getFieldErrors()).thenReturn(List.of());

        ServerWebInputException ex = mock(ServerWebInputException.class);
        Throwable nestedCause = mock(Throwable.class);
        when(nestedCause.getCause()).thenReturn(bindException);
        when(ex.getCause()).thenReturn(nestedCause);
        when(ex.getReason()).thenReturn("invalid payload");

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/test").build());
        AppResponse<Void> response = handler.handleServerWebInput(ex, exchange).block();

        assertThat(response).isNotNull();
        assertThat(response.code()).isEqualTo(AppResponse.ErrorCode.BAD_REQUEST.getCode());
        assertThat(response.message()).isEqualTo("invalid payload");
    }

    @Test
    void handleHandlerMethodValidation_blankDefaultAndNullCodes_usesValidationFallbackMessage() throws Exception {
        HandlerMethodValidationException ex = mock(HandlerMethodValidationException.class);
        ParameterValidationResult result = mock(ParameterValidationResult.class);
        MessageSourceResolvable resolvable = mock(MessageSourceResolvable.class);

        Method method = ValidationDummy.class.getDeclaredMethod("submit", String.class);
        when(result.getMethodParameter()).thenReturn(new MethodParameter(method, 0));
        when(result.getResolvableErrors()).thenReturn(List.of(resolvable));
        when(resolvable.getDefaultMessage()).thenReturn("   ");
        when(resolvable.getCodes()).thenReturn(null);
        when(ex.getParameterValidationResults()).thenReturn(List.of(result));
        when(messageService.getMessage("error.validation.failed", null, Locale.ENGLISH)).thenReturn("Validation failed");

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/test").build());

        AppResponse<Void> response = handler.handleHandlerMethodValidation(ex, exchange).block();

        assertThat(response).isNotNull();
        assertThat(response.code()).isEqualTo(AppResponse.ErrorCode.BAD_REQUEST.getCode());
        assertThat(response.message()).endsWith(": Validation failed");
        verify(messageService).getMessage("error.validation.failed", null, Locale.ENGLISH);
    }

    @Test
    void handleHandlerMethodValidation_allCodeLookupsFail_usesValidationFallbackMessage() throws Exception {
        HandlerMethodValidationException ex = mock(HandlerMethodValidationException.class);
        ParameterValidationResult result = mock(ParameterValidationResult.class);
        MessageSourceResolvable resolvable = mock(MessageSourceResolvable.class);

        Method method = ValidationDummy.class.getDeclaredMethod("submit", String.class);
        when(result.getMethodParameter()).thenReturn(new MethodParameter(method, 0));
        when(result.getResolvableErrors()).thenReturn(List.of(resolvable));
        when(resolvable.getDefaultMessage()).thenReturn(null);
        when(resolvable.getCodes()).thenReturn(new String[]{"validation.clientId.primary", "validation.clientId.secondary"});
        when(ex.getParameterValidationResults()).thenReturn(List.of(result));

        when(messageService.getMessage("validation.clientId.primary", null, Locale.ENGLISH))
                .thenThrow(new NoSuchMessageException("validation.clientId.primary"));
        when(messageService.getMessage("validation.clientId.secondary", null, Locale.ENGLISH))
                .thenThrow(new NoSuchMessageException("validation.clientId.secondary"));
        when(messageService.getMessage("error.validation.failed", null, Locale.ENGLISH)).thenReturn("Validation failed");

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/test").build());

        AppResponse<Void> response = handler.handleHandlerMethodValidation(ex, exchange).block();

        assertThat(response).isNotNull();
        assertThat(response.code()).isEqualTo(AppResponse.ErrorCode.BAD_REQUEST.getCode());
        assertThat(response.message()).isEqualTo("primary: Validation failed");
        verify(messageService).getMessage("error.validation.failed", null, Locale.ENGLISH);
    }

    @Test
    void handleHandlerMethodValidation_withoutCandidateCodeAndParameterName_usesParameterFallback() {
        HandlerMethodValidationException ex = mock(HandlerMethodValidationException.class);
        ParameterValidationResult result = mock(ParameterValidationResult.class);
        MessageSourceResolvable resolvable = mock(MessageSourceResolvable.class);
        MethodParameter methodParameter = mock(MethodParameter.class);

        when(result.getMethodParameter()).thenReturn(methodParameter);
        when(methodParameter.getParameterName()).thenReturn(null);
        when(result.getResolvableErrors()).thenReturn(List.of(resolvable));
        when(resolvable.getCodes()).thenReturn(new String[]{"Positive.", "NoDot"});
        when(resolvable.getDefaultMessage()).thenReturn("must not be blank");
        when(ex.getParameterValidationResults()).thenReturn(List.of(result));

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/test").build());

        AppResponse<Void> response = handler.handleHandlerMethodValidation(ex, exchange).block();

        assertThat(response).isNotNull();
        assertThat(response.code()).isEqualTo(AppResponse.ErrorCode.BAD_REQUEST.getCode());
        assertThat(response.message()).isEqualTo("parameter: must not be blank");
    }

    @Test
    void handleHandlerMethodValidation_missingBracedDefaultKey_fallsBackToCodes() throws Exception {
        HandlerMethodValidationException ex = mock(HandlerMethodValidationException.class);
        ParameterValidationResult result = mock(ParameterValidationResult.class);
        MessageSourceResolvable resolvable = mock(MessageSourceResolvable.class);

        Method method = ValidationDummy.class.getDeclaredMethod("submit", String.class);
        when(result.getMethodParameter()).thenReturn(new MethodParameter(method, 0));
        when(result.getResolvableErrors()).thenReturn(List.of(resolvable));
        when(resolvable.getDefaultMessage()).thenReturn("{validation.missing.key}");
        when(resolvable.getCodes()).thenReturn(new String[]{"validation.clientId.secondary"});
        when(ex.getParameterValidationResults()).thenReturn(List.of(result));

        when(messageService.getMessage("validation.missing.key", null, Locale.ENGLISH))
                .thenThrow(new NoSuchMessageException("validation.missing.key"));
        when(messageService.getMessage("validation.clientId.secondary", null, Locale.ENGLISH))
                .thenReturn("ClientId must be greater than 0");

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/test").build());

        AppResponse<Void> response = handler.handleHandlerMethodValidation(ex, exchange).block();

        assertThat(response).isNotNull();
        assertThat(response.code()).isEqualTo(AppResponse.ErrorCode.BAD_REQUEST.getCode());
        assertThat(response.message()).isEqualTo("secondary: ClientId must be greater than 0");
    }

    static class ValidationDummy {
        @SuppressWarnings("unused")
        void submit(String payload) {
            // No-op helper for MethodParameter construction.
        }
    }
}

