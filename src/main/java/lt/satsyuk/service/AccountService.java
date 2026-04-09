package lt.satsyuk.service;

import lt.satsyuk.dto.AccountResponse;
import lt.satsyuk.dto.UpdateBalanceRequest;
import lt.satsyuk.exception.AccountNotFoundException;
import lt.satsyuk.exception.AccountOptimisticLockException;
import lt.satsyuk.mapper.AccountMapper;
import lt.satsyuk.model.Account;
import lt.satsyuk.repository.AccountRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;

@Service
public class AccountService {

    private static final int MAX_OPTIMISTIC_RETRIES = 3;

    private final AccountRepository accountRepository;
    private final AccountMapper accountMapper;
    private final TransactionalOperator transactionalOperator;

    public AccountService(AccountRepository accountRepository,
                          AccountMapper accountMapper,
                          ReactiveTransactionManager transactionManager) {
        this.accountRepository = accountRepository;
        this.accountMapper = accountMapper;
        this.transactionalOperator = TransactionalOperator.create(transactionManager);
    }

    public Mono<AccountResponse> updateBalancePessimistic(UpdateBalanceRequest request) {
        return transactionalOperator.transactional(
                accountRepository.findByClientIdForPessimisticUpdate(request.clientId())
                        .switchIfEmpty(Mono.error(new AccountNotFoundException(request.clientId())))
                        .flatMap(account -> saveUpdatedAccount(account, request.amount()))
        );
    }

    public Mono<AccountResponse> updateBalanceOptimistic(UpdateBalanceRequest request) {
        return updateBalanceOptimistic(request.clientId(), request.amount(), 0);
    }

    public Mono<AccountResponse> getByClientId(Long clientId) {
        return accountRepository.findByClientId(clientId)
                .switchIfEmpty(Mono.error(new AccountNotFoundException(clientId)))
                .map(accountMapper::toResponse);
    }

    private Mono<AccountResponse> updateBalanceOptimistic(Long clientId, BigDecimal amount, int attempt) {
        return Mono.defer(() -> transactionalOperator.transactional(
                        accountRepository.findByClientId(clientId)
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
                    return updateBalanceOptimistic(clientId, amount, attempt + 1);
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
}

