package lt.satsyuk.service;

import lt.satsyuk.dto.ClientResponse;
import lt.satsyuk.dto.CreateClientRequest;
import lt.satsyuk.exception.ClientNotFoundException;
import lt.satsyuk.exception.ClientSearchQueryTooShortException;
import lt.satsyuk.exception.PhoneAlreadyExistsException;
import lt.satsyuk.mapper.ClientMapper;
import lt.satsyuk.model.Account;
import lt.satsyuk.model.Client;
import lt.satsyuk.repository.AccountRepository;
import lt.satsyuk.repository.ClientRepository;
import io.r2dbc.spi.R2dbcDataIntegrityViolationException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ClientService {

    public static final int MIN_SEARCH_QUERY_LENGTH = 3;
    private static final String UNIQUE_VIOLATION_SQLSTATE = "23505";
    private static final String PHONE_UNIQUE_CONSTRAINT = "client_phone_key";

    private final ClientRepository repo;
    private final AccountRepository accountRepository;
    private final ClientMapper mapper;
    @Value("${app.clients.search.max-results:20}")
    private int searchMaxResults;

    public Mono<ClientResponse> create(CreateClientRequest req) {
        Client client = mapper.toEntity(req);
        return repo.save(client)
                .onErrorMap(this::isPhoneUniqueViolation,
                        _ -> new PhoneAlreadyExistsException(req.phone()))
                .flatMap(saved -> {
                    Account account = Account.builder()
                            .clientId(saved.getId())
                            .balance(BigDecimal.ZERO)
                            .build();
                    return accountRepository.save(account)
                            .then(Mono.fromSupplier(() -> mapper.toResponse(saved)));
                });
    }

    private boolean isPhoneUniqueViolation(Throwable throwable) {
        if (!hasPhoneConstraint(throwable)) {
            return false;
        }

        Throwable current = throwable;
        while (current != null) {
            if (current instanceof DuplicateKeyException) {
                return true;
            }
            if (current instanceof R2dbcDataIntegrityViolationException integrity
                    && UNIQUE_VIOLATION_SQLSTATE.equals(integrity.getSqlState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean hasPhoneConstraint(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String constraintName = extractConstraintName(current);
            if (PHONE_UNIQUE_CONSTRAINT.equalsIgnoreCase(constraintName)) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.contains(PHONE_UNIQUE_CONSTRAINT)) {
                return true;
            }
            current = current.getCause();
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

    public Mono<ClientResponse> get(Long id) {
        return repo.findById(id)
                .switchIfEmpty(Mono.error(new ClientNotFoundException(id)))
                .map(mapper::toResponse);
    }

    public Mono<List<ClientResponse>> searchByNameOrSurname(String query) {
        String normalizedQuery = query == null ? "" : query.trim();
        if (normalizedQuery.length() < MIN_SEARCH_QUERY_LENGTH) {
            return Mono.error(new ClientSearchQueryTooShortException(MIN_SEARCH_QUERY_LENGTH));
        }

        return repo.searchByNameOrSurname(normalizedQuery, searchMaxResults)
                .map(mapper::toResponse)
                .collectList();
    }
}

