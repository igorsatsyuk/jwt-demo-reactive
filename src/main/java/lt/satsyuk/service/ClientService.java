package lt.satsyuk.service;

import lt.satsyuk.dto.ClientResponse;
import lt.satsyuk.dto.CreateClientRequest;
import lt.satsyuk.exception.ClientNotFoundException;
import lt.satsyuk.exception.ClientSearchQueryTooShortException;
import lt.satsyuk.mapper.ClientMapper;
import lt.satsyuk.model.Account;
import lt.satsyuk.model.Client;
import lt.satsyuk.model.ClientAccess;
import lt.satsyuk.repository.AccountRepository;
import lt.satsyuk.repository.ClientAccessRepository;
import lt.satsyuk.repository.ClientRepository;
import io.r2dbc.spi.R2dbcDataIntegrityViolationException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ClientService {

    public static final int MIN_SEARCH_QUERY_LENGTH = 3;
    private static final String UNIQUE_VIOLATION_SQLSTATE = "23505";
    private static final Pattern CONSTRAINT_NAME_SPLITTER = Pattern.compile("[^a-z0-9_]+");
    private static final Set<String> PHONE_UNIQUE_CONSTRAINTS = Set.of(
            "uq_client_phone",
            "client_phone_key"
    );

    private final ClientRepository repo;
    private final AccountRepository accountRepository;
    private final ClientAccessRepository clientAccessRepository;
    private final ClientMapper mapper;
    @Value("${app.clients.search.max-results:20}")
    private int searchMaxResults;

    public Mono<ClientResponse> create(CreateClientRequest req, String authClientId) {
        return repo.findByPhone(req.phone())
                .flatMap(existing -> addAccessIfAbsent(existing, authClientId)
                        .then(Mono.fromSupplier(() -> mapper.toResponse(existing))))
                .switchIfEmpty(Mono.defer(() ->
                        Mono.fromSupplier(() -> mapper.toEntity(req))
                                .flatMap(repo::save)
                                .onErrorMap(this::isPhoneUniqueViolation, _ -> new DuplicateKeyException("phone"))
                                .flatMap(saved -> saveClientAccess(saved.getId(), authClientId)
                                        .then(saveZeroBalanceAccount(saved.getId()))
                                        .then(Mono.fromSupplier(() -> mapper.toResponse(saved))))
                                .onErrorResume(DuplicateKeyException.class, _ ->
                                        repo.findByPhone(req.phone())
                                                .switchIfEmpty(Mono.error(new IllegalStateException("Phone lookup failed after constraint violation")))
                                                .flatMap(duplicate -> addAccessIfAbsent(duplicate, authClientId)
                                                        .then(Mono.fromSupplier(() -> mapper.toResponse(duplicate)))))
                ));
    }

    private Mono<Void> addAccessIfAbsent(Client client, String authClientId) {
        return clientAccessRepository.existsByClientIdAndAuthClientId(client.getId(), authClientId)
                .flatMap(exists -> {
                    if (Boolean.TRUE.equals(exists)) {
                        return Mono.empty();
                    }
                    return clientAccessRepository.save(ClientAccess.builder()
                                    .clientId(client.getId())
                                    .authClientId(authClientId)
                                    .build())
                            .then();
                });
    }

    private Mono<ClientAccess> saveClientAccess(Long clientId, String authClientId) {
        return clientAccessRepository.save(ClientAccess.builder()
                .clientId(clientId)
                .authClientId(authClientId)
                .build());
    }

    private Mono<Void> saveZeroBalanceAccount(Long clientId) {
        Account account = Account.builder()
                .clientId(clientId)
                .balance(BigDecimal.ZERO)
                .build();
        return accountRepository.save(account).then();
    }

    private boolean isPhoneUniqueViolation(Throwable throwable) {
        boolean hasPhoneConstraintInMessage = false;
        boolean hasUniqueViolation = false;

        Throwable current = throwable;
        while (current != null) {
            hasUniqueViolation = hasUniqueViolation || isUniqueViolation(current);
            hasPhoneConstraintInMessage = hasPhoneConstraintInMessage || containsPhoneConstraintName(current.getMessage());

            if (hasUniqueViolation && hasPhoneConstraintInMessage) {
                return true;
            }
            current = current.getCause();
        }

        if (!hasUniqueViolation) {
            return false;
        }

        current = throwable;
        while (current != null) {
            if (isPhoneConstraintName(extractConstraintName(current))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isUniqueViolation(Throwable throwable) {
        if (throwable instanceof DuplicateKeyException) {
            return true;
        }
        return throwable instanceof R2dbcDataIntegrityViolationException integrity
                && UNIQUE_VIOLATION_SQLSTATE.equals(integrity.getSqlState());
    }

    private boolean isPhoneConstraintName(String constraintName) {
        if (constraintName == null) {
            return false;
        }
        return PHONE_UNIQUE_CONSTRAINTS.contains(constraintName.toLowerCase(Locale.ROOT));
    }

    private boolean containsPhoneConstraintName(String message) {
        if (message == null) {
            return false;
        }
        String normalizedMessage = message.toLowerCase(Locale.ROOT);
        String[] tokens = CONSTRAINT_NAME_SPLITTER.split(normalizedMessage);
        for (String token : tokens) {
            if (PHONE_UNIQUE_CONSTRAINTS.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String extractConstraintName(Throwable throwable) {
        String directConstraint = invokeStringGetter(throwable, "getConstraintName");
        if (directConstraint != null) {
            return directConstraint;
        }

        Object errorDetails = invokeGetter(throwable, "getErrorDetails");
        String detailsConstraint = invokeStringGetter(errorDetails, "getConstraintName");
        if (detailsConstraint != null) {
            return detailsConstraint;
        }

        Object serverError = invokeGetter(throwable, "getServerErrorMessage");
        return invokeStringGetter(serverError, "getConstraint");
    }

    private Object invokeGetter(Object target, String getterName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(getterName);
            return method.invoke(target);
        } catch (Exception _) {
            return null;
        }
    }

    private String invokeStringGetter(Object target, String getterName) {
        Object value = invokeGetter(target, getterName);
        return value instanceof String str ? str : null;
    }

    public Mono<ClientResponse> get(Long id, String authClientId) {
        return repo.findByIdAndAuthClientId(id, authClientId)
                .switchIfEmpty(Mono.error(new ClientNotFoundException(id)))
                .map(mapper::toResponse);
    }

    public Mono<List<ClientResponse>> searchByNameOrSurname(String query, String authClientId) {
        String normalizedQuery = query == null ? "" : query.trim();
        if (normalizedQuery.length() < MIN_SEARCH_QUERY_LENGTH) {
            return Mono.error(new ClientSearchQueryTooShortException(MIN_SEARCH_QUERY_LENGTH));
        }

        return repo.searchByNameOrSurnameAndAuthClientId(normalizedQuery, authClientId, searchMaxResults)
                .map(mapper::toResponse)
                .collectList();
    }
}

