package lt.satsyuk.service;

import lt.satsyuk.dto.ClientResponse;
import lt.satsyuk.dto.CreateClientRequest;
import lt.satsyuk.exception.ClientNotFoundException;
import lt.satsyuk.exception.ClientSearchQueryTooShortException;
import lt.satsyuk.exception.PhoneAlreadyExistsException;
import lt.satsyuk.mapper.ClientMapper;
import lt.satsyuk.model.Account;
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
import java.util.Locale;
import java.util.List;
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
    private final ClientMapper mapper;
    @Value("${app.clients.search.max-results:20}")
    private int searchMaxResults;

    public Mono<ClientResponse> create(CreateClientRequest req) {
        return Mono.fromSupplier(() -> mapper.toEntity(req))
                .flatMap(repo::save)
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

