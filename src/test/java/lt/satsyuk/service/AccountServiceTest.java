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
import lt.satsyuk.model.RequestStatus;
import lt.satsyuk.model.RequestType;
import lt.satsyuk.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountServiceTest {

    private static final String AUTH_CLIENT_ID = "spring-app";

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final AccountMapper accountMapper = mock(AccountMapper.class);
    private final ReactiveTransactionManager transactionManager = mock(ReactiveTransactionManager.class);
    private final RequestService requestService = mock(RequestService.class);
    private final SecurityService securityService = mock(SecurityService.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final TransactionalOperator transactionalOperator = mock(TransactionalOperator.class);

    private AccountService accountService;

    @BeforeEach
    void setUp() {
        when(securityService.clientId()).thenReturn(Mono.just(AUTH_CLIENT_ID));
        when(transactionalOperator.transactional(any(Mono.class))).thenAnswer(inv -> inv.getArgument(0));
        accountService = new AccountService(accountRepository, accountMapper, transactionManager,
                requestService, securityService, objectMapper);
        // Replace the real TransactionalOperator with our mock
        org.springframework.test.util.ReflectionTestUtils.setField(accountService, "transactionalOperator", transactionalOperator);
    }

    @Test
    void updateBalancePessimistic_replayAfterCompletedReturnsSavedResponse() throws JsonProcessingException {
        UUID idempotencyKey = UUID.randomUUID();
        UpdateBalanceRequest request = new UpdateBalanceRequest(idempotencyKey, 11L, new BigDecimal("25.50"));
        AccountResponse savedResponse = new AccountResponse(22L, 11L, new BigDecimal("125.50"));
        String savedResponseJson = objectMapper.writeValueAsString(AppResponse.ok(savedResponse));
        UUID requestId = UUID.randomUUID();

        when(requestService.createPendingRequestIfAbsent(idempotencyKey, request, RequestType.UPDATE_BALANCE_PESSIMISTIC, AUTH_CLIENT_ID))
                .thenReturn(Mono.just(new RequestService.CreateRequestResult(requestId, true, RequestStatus.COMPLETED, savedResponseJson)));

        StepVerifier.create(accountService.updateBalancePessimistic(request))
                .assertNext(response -> {
                    assertThat(response).isEqualTo(savedResponse);
                })
                .verifyComplete();

        verify(accountRepository, never()).findByClientIdAndAuthClientIdForPessimisticUpdate(any(), any());
    }

    @Test
    void updateBalanceOptimistic_replayAfterCompletedReturnsSavedResponse() throws JsonProcessingException {
        UUID idempotencyKey = UUID.randomUUID();
        UpdateBalanceRequest request = new UpdateBalanceRequest(idempotencyKey, 11L, new BigDecimal("3.00"));
        AccountResponse savedResponse = new AccountResponse(22L, 11L, new BigDecimal("13.00"));
        String savedResponseJson = objectMapper.writeValueAsString(AppResponse.ok(savedResponse));
        UUID requestId = UUID.randomUUID();

        when(requestService.createPendingRequestIfAbsent(idempotencyKey, request, RequestType.UPDATE_BALANCE_OPTIMISTIC, AUTH_CLIENT_ID))
                .thenReturn(Mono.just(new RequestService.CreateRequestResult(requestId, true, RequestStatus.COMPLETED, savedResponseJson)));

        StepVerifier.create(accountService.updateBalanceOptimistic(request))
                .assertNext(response -> assertThat(response).isEqualTo(savedResponse))
                .verifyComplete();
    }

    @Test
    void updateBalancePessimistic_replayAfterFailedRethrowsAccountNotFound() throws JsonProcessingException {
        UUID idempotencyKey = UUID.randomUUID();
        UpdateBalanceRequest request = new UpdateBalanceRequest(idempotencyKey, 11L, new BigDecimal("25.50"));
        UUID requestId = UUID.randomUUID();
        String errorJson = objectMapper.writeValueAsString(
                new AccountService.StoredError(40401, "Account for client id=999 not found", "AccountNotFoundException", 999L));

        when(requestService.createPendingRequestIfAbsent(idempotencyKey, request, RequestType.UPDATE_BALANCE_PESSIMISTIC, AUTH_CLIENT_ID))
                .thenReturn(Mono.just(new RequestService.CreateRequestResult(requestId, true, RequestStatus.FAILED, errorJson)));

        StepVerifier.create(accountService.updateBalancePessimistic(request))
                .expectError(AccountNotFoundException.class)
                .verify();
    }

    @Test
    void updateBalanceOptimistic_replayAfterFailedRethrowsOptimisticLock() throws JsonProcessingException {
        UUID idempotencyKey = UUID.randomUUID();
        UpdateBalanceRequest request = new UpdateBalanceRequest(idempotencyKey, 11L, new BigDecimal("3.00"));
        UUID requestId = UUID.randomUUID();
        String errorJson = objectMapper.writeValueAsString(
                new AccountService.StoredError(40901, "Too many optimistic lock retries for client id=11", "AccountOptimisticLockException", 11L));

        when(requestService.createPendingRequestIfAbsent(idempotencyKey, request, RequestType.UPDATE_BALANCE_OPTIMISTIC, AUTH_CLIENT_ID))
                .thenReturn(Mono.just(new RequestService.CreateRequestResult(requestId, true, RequestStatus.FAILED, errorJson)));

        StepVerifier.create(accountService.updateBalanceOptimistic(request))
                .expectError(AccountOptimisticLockException.class)
                .verify();
    }

    @Test
    void updateBalancePessimistic_replayAfterInProgressThrowsAccountUpdateInProgress() {
        UUID idempotencyKey = UUID.randomUUID();
        UpdateBalanceRequest request = new UpdateBalanceRequest(idempotencyKey, 11L, new BigDecimal("25.50"));
        UUID requestId = UUID.randomUUID();

        when(requestService.createPendingRequestIfAbsent(idempotencyKey, request, RequestType.UPDATE_BALANCE_PESSIMISTIC, AUTH_CLIENT_ID))
                .thenReturn(Mono.just(new RequestService.CreateRequestResult(requestId, true, RequestStatus.PROCESSING, null)));

        StepVerifier.create(accountService.updateBalancePessimistic(request))
                .expectError(AccountUpdateInProgressException.class)
                .verify();
    }

    @Test
    void updateBalancePessimistic_replayAfterPendingThrowsAccountUpdateInProgress() {
        UUID idempotencyKey = UUID.randomUUID();
        UpdateBalanceRequest request = new UpdateBalanceRequest(idempotencyKey, 11L, new BigDecimal("25.50"));
        UUID requestId = UUID.randomUUID();

        when(requestService.createPendingRequestIfAbsent(idempotencyKey, request, RequestType.UPDATE_BALANCE_PESSIMISTIC, AUTH_CLIENT_ID))
                .thenReturn(Mono.just(new RequestService.CreateRequestResult(requestId, true, RequestStatus.PENDING, null)));

        StepVerifier.create(accountService.updateBalancePessimistic(request))
                .expectError(AccountUpdateInProgressException.class)
                .verify();
    }

    @Test
    void updateBalancePessimistic_replayAfterFailedWithUnknownTypeThrowsIllegalState() throws JsonProcessingException {
        UUID idempotencyKey = UUID.randomUUID();
        UpdateBalanceRequest request = new UpdateBalanceRequest(idempotencyKey, 11L, new BigDecimal("25.50"));
        UUID requestId = UUID.randomUUID();
        String errorJson = objectMapper.writeValueAsString(
                new AccountService.StoredError(50000, "Some error", "UnknownExceptionType", null));

        when(requestService.createPendingRequestIfAbsent(idempotencyKey, request, RequestType.UPDATE_BALANCE_PESSIMISTIC, AUTH_CLIENT_ID))
                .thenReturn(Mono.just(new RequestService.CreateRequestResult(requestId, true, RequestStatus.FAILED, errorJson)));

        StepVerifier.create(accountService.updateBalancePessimistic(request))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void updateBalancePessimistic_failsRequestOnAccountNotFound() {
        UpdateBalanceRequest request = new UpdateBalanceRequest(null, 11L, BigDecimal.ONE);
        UUID requestId = UUID.randomUUID();

        when(requestService.createPendingRequestIfAbsent(null, request, RequestType.UPDATE_BALANCE_PESSIMISTIC, AUTH_CLIENT_ID))
                .thenReturn(Mono.just(new RequestService.CreateRequestResult(requestId, false, RequestStatus.PENDING, null)));
        when(accountRepository.findByClientIdAndAuthClientIdForPessimisticUpdate(11L, AUTH_CLIENT_ID))
                .thenReturn(Mono.empty());
        when(requestService.failRequest(eq(requestId), eq(AUTH_CLIENT_ID), any(String.class)))
                .thenReturn(Mono.empty());

        StepVerifier.create(accountService.updateBalancePessimistic(request))
                .expectError(AccountNotFoundException.class)
                .verify();

        verify(requestService).failRequest(eq(requestId), eq(AUTH_CLIENT_ID), any(String.class));
    }

    @Test
    void updateBalancePessimistic_failsRequestOnOptimisticLock() {
        UpdateBalanceRequest request = new UpdateBalanceRequest(null, 11L, new BigDecimal("3.00"));
        UUID requestId = UUID.randomUUID();

        when(requestService.createPendingRequestIfAbsent(null, request, RequestType.UPDATE_BALANCE_PESSIMISTIC, AUTH_CLIENT_ID))
                .thenReturn(Mono.just(new RequestService.CreateRequestResult(requestId, false, RequestStatus.PENDING, null)));
        when(accountRepository.findByClientIdAndAuthClientIdForPessimisticUpdate(11L, AUTH_CLIENT_ID))
                .thenReturn(Mono.error(new OptimisticLockingFailureException("Optimistic lock")));
        when(requestService.failRequest(eq(requestId), eq(AUTH_CLIENT_ID), any(String.class)))
                .thenReturn(Mono.empty());

        StepVerifier.create(accountService.updateBalancePessimistic(request))
                .expectErrorSatisfies(ex -> assertThat(ex).isInstanceOf(OptimisticLockingFailureException.class))
                .verify();

        verify(requestService).failRequest(eq(requestId), eq(AUTH_CLIENT_ID), any(String.class));
    }

    @Test
    void updateBalancePessimistic_successCreatesRequestAndCompletesIt() {
        UpdateBalanceRequest request = new UpdateBalanceRequest(null, 11L, new BigDecimal("25.50"));
        UUID requestId = UUID.randomUUID();
        lt.satsyuk.model.Account account = lt.satsyuk.model.Account.builder()
                .id(22L).clientId(11L).balance(new BigDecimal("100.00")).version(0L).build();
        AccountResponse response = new AccountResponse(22L, 11L, new BigDecimal("125.50"));

        when(requestService.createPendingRequestIfAbsent(null, request, RequestType.UPDATE_BALANCE_PESSIMISTIC, AUTH_CLIENT_ID))
                .thenReturn(Mono.just(new RequestService.CreateRequestResult(requestId, false, RequestStatus.PENDING, null)));
        when(accountRepository.findByClientIdAndAuthClientIdForPessimisticUpdate(11L, AUTH_CLIENT_ID))
                .thenReturn(Mono.just(account));
        when(accountRepository.save(any(lt.satsyuk.model.Account.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(accountMapper.toResponse(any(lt.satsyuk.model.Account.class))).thenReturn(response);
        when(requestService.completeRequest(eq(requestId), eq(AUTH_CLIENT_ID), any(String.class))).thenReturn(Mono.empty());

        StepVerifier.create(accountService.updateBalancePessimistic(request))
                .assertNext(r -> assertThat(r).isEqualTo(response))
                .verifyComplete();

        verify(requestService).completeRequest(eq(requestId), eq(AUTH_CLIENT_ID), any(String.class));
    }

    @Test
    void markRequestFailedSwallowsExceptionWhenFailRequestThrows() {
        UpdateBalanceRequest request = new UpdateBalanceRequest(null, 11L, BigDecimal.ONE);
        UUID requestId = UUID.randomUUID();

        when(requestService.createPendingRequestIfAbsent(null, request, RequestType.UPDATE_BALANCE_PESSIMISTIC, AUTH_CLIENT_ID))
                .thenReturn(Mono.just(new RequestService.CreateRequestResult(requestId, false, RequestStatus.PENDING, null)));
        when(accountRepository.findByClientIdAndAuthClientIdForPessimisticUpdate(11L, AUTH_CLIENT_ID))
                .thenReturn(Mono.empty());
        when(requestService.failRequest(eq(requestId), eq(AUTH_CLIENT_ID), any(String.class)))
                .thenReturn(Mono.error(new lt.satsyuk.exception.RequestNotFoundException(requestId)));

        StepVerifier.create(accountService.updateBalancePessimistic(request))
                .expectError(AccountNotFoundException.class)
                .verify();
    }
}
