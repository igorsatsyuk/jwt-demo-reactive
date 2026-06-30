package lt.satsyuk.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lt.satsyuk.dto.AppResponse;
import lt.satsyuk.dto.AccountResponse;
import lt.satsyuk.dto.UpdateBalanceRequest;
import lt.satsyuk.exception.AccountNotFoundException;
import lt.satsyuk.exception.AccountOptimisticLockException;
import lt.satsyuk.exception.AccountUpdateInProgressException;
import lt.satsyuk.mapper.AccountMapper;
import lt.satsyuk.model.Account;
import lt.satsyuk.model.RequestStatus;
import lt.satsyuk.model.RequestType;
import lt.satsyuk.repository.AccountRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.UUID;

@Slf4j
@Service
public class AccountService {

    private static final int MAX_OPTIMISTIC_RETRIES = 3;

    private final AccountRepository accountRepository;
    private final AccountMapper accountMapper;
    private final TransactionalOperator transactionalOperator;
    private final RequestService requestService;
    private final SecurityService securityService;
    private final ObjectMapper objectMapper;

    public AccountService(AccountRepository accountRepository,
                          AccountMapper accountMapper,
                          ReactiveTransactionManager transactionManager,
                          RequestService requestService,
                          SecurityService securityService,
                          ObjectMapper objectMapper) {
        this.accountRepository = accountRepository;
        this.accountMapper = accountMapper;
        this.transactionalOperator = TransactionalOperator.create(transactionManager);
        this.requestService = requestService;
        this.securityService = securityService;
        this.objectMapper = objectMapper;
    }

    public Mono<AccountResponse> updateBalancePessimistic(UpdateBalanceRequest request) {
        return securityService.clientId()
                .flatMap(authClientId -> requestService.createPendingRequestIfAbsent(
                                request.idempotencyKey(), request, RequestType.UPDATE_BALANCE_PESSIMISTIC, authClientId)
                        .flatMap(result -> {
                            if (result.alreadyExisted()) {
                                return handleExistingRequest(result);
                            }
                            UUID requestId = result.requestId();
                            return executePessimisticUpdate(request, authClientId, requestId)
                                    .onErrorResume(ex -> markRequestFailed(requestId, authClientId, ex)
                                            .then(Mono.error(ex)));
                        })
                );
    }

    private Mono<AccountResponse> executePessimisticUpdate(UpdateBalanceRequest request, String authClientId, UUID requestId) {
        return transactionalOperator.transactional(
                        accountRepository.findByClientIdAndAuthClientIdForPessimisticUpdate(request.clientId(), authClientId)
                                .switchIfEmpty(Mono.error(new AccountNotFoundException(request.clientId())))
                                .flatMap(account -> saveUpdatedAccount(account, request.amount()))
                )
                .flatMap(response -> requestService.completeRequest(requestId, authClientId, writeJson(AppResponse.ok(response)))
                        .then(Mono.just(response)));
    }

    public Mono<AccountResponse> updateBalanceOptimistic(UpdateBalanceRequest request) {
        return securityService.clientId()
                .flatMap(authClientId -> requestService.createPendingRequestIfAbsent(
                                request.idempotencyKey(), request, RequestType.UPDATE_BALANCE_OPTIMISTIC, authClientId)
                        .flatMap(result -> {
                            if (result.alreadyExisted()) {
                                return handleExistingRequest(result);
                            }
                            UUID requestId = result.requestId();
                            return executeOptimisticUpdate(request.clientId(), request.amount(), authClientId, requestId)
                                    .onErrorResume(ex -> markRequestFailed(requestId, authClientId, ex)
                                            .then(Mono.error(ex)));
                        })
                );
    }

    private Mono<AccountResponse> executeOptimisticUpdate(Long clientId, BigDecimal amount, String authClientId, UUID requestId) {
        return updateBalanceOptimistic(clientId, amount, authClientId, 0)
                .flatMap(response -> requestService.completeRequest(requestId, authClientId, writeJson(AppResponse.ok(response)))
                        .then(Mono.just(response)));
    }

    public Mono<AccountResponse> getByClientId(Long clientId, String authClientId) {
        return accountRepository.findByClientIdAndAuthClientId(clientId, authClientId)
                .switchIfEmpty(Mono.error(new AccountNotFoundException(clientId)))
                .map(accountMapper::toResponse);
    }

    private Mono<AccountResponse> updateBalanceOptimistic(Long clientId, BigDecimal amount, String authClientId, int attempt) {
        return Mono.defer(() -> transactionalOperator.transactional(
                        accountRepository.findByClientIdAndAuthClientId(clientId, authClientId)
                                .switchIfEmpty(Mono.error(new AccountNotFoundException(clientId)))
                                .flatMap(account -> saveUpdatedAccount(account, amount))
                ))
                .onErrorResume(ex -> {
                    if (!isOptimisticConflict(ex)) {
                        return Mono.error(ex);
                    }
                    if (attempt >= MAX_OPTIMISTIC_RETRIES - 1) {
                        return Mono.error(new AccountOptimisticLockException(clientId));
                    }
                    return updateBalanceOptimistic(clientId, amount, authClientId, attempt + 1);
                });
    }

    private Mono<AccountResponse> saveUpdatedAccount(Account account, BigDecimal amount) {
        account.setBalance(account.getBalance().add(amount));
        return accountRepository.save(account)
                .map(accountMapper::toResponse);
    }

    private boolean isOptimisticConflict(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof OptimisticLockingFailureException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private Mono<AccountResponse> handleExistingRequest(RequestService.CreateRequestResult result) {
        return switch (result.status()) {
            case COMPLETED -> Mono.justOrEmpty(readSavedResponse(result.savedResponseData()));
            case FAILED -> Mono.defer(() -> {
                rethrowStoredException(result.savedResponseData());
                return Mono.empty(); // unreachable
            });
            case PENDING, PROCESSING -> Mono.error(new AccountUpdateInProgressException(result.requestId()));
        };
    }

    private AccountResponse readSavedResponse(String responseData) {
        AppResponse<AccountResponse> parsed = parseErrorResponse(responseData);
        return parsed != null ? parsed.data() : null;
    }

    private void rethrowStoredException(String responseData) {
        StoredError stored = parseStoredError(responseData);
        if (stored == null) {
            throw new IllegalStateException("Request failed with unknown error");
        }
        throw switch (stored.exceptionType()) {
            case "AccountNotFoundException" -> new AccountNotFoundException(stored.clientId());
            case "AccountOptimisticLockException" -> new AccountOptimisticLockException(stored.clientId());
            default -> new IllegalStateException(stored.message());
        };
    }

    private StoredError parseStoredError(String responseData) {
        if (responseData == null || responseData.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(responseData, StoredError.class);
        } catch (JsonProcessingException _) {
            return null;
        }
    }

    private AppResponse<AccountResponse> parseErrorResponse(String responseData) {
        if (responseData == null || responseData.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(responseData,
                    objectMapper.getTypeFactory().constructParametricType(AppResponse.class, AccountResponse.class));
        } catch (JsonProcessingException _) {
            return null;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize response", ex);
        }
    }

    private Mono<Void> markRequestFailed(UUID requestId, String authClientId, Throwable ex) {
        StoredError stored = switch (ex) {
            case AccountNotFoundException e -> new StoredError(
                    AppResponse.ErrorCode.NOT_FOUND.getCode(), e.getMessage(),
                    e.getClass().getSimpleName(), e.getClientId());
            case AccountOptimisticLockException e -> new StoredError(
                    AppResponse.ErrorCode.CONFLICT.getCode(), e.getMessage(),
                    e.getClass().getSimpleName(), e.getClientId());
            default -> new StoredError(
                    AppResponse.ErrorCode.INTERNAL_SERVER_ERROR.getCode(), "Internal server error",
                    ex.getClass().getSimpleName(), null);
        };
        return requestService.failRequest(requestId, authClientId, writeJson(stored))
                .onErrorResume(ex2 -> {
                    log.warn("Failed to mark request {} as FAILED: {}", requestId, ex2.getMessage());
                    return Mono.empty();
                });
    }

    public record StoredError(int code, String message, String exceptionType, Long clientId) {}
}

