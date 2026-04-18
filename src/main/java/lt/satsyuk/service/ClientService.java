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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ClientService {

    public static final int MIN_SEARCH_QUERY_LENGTH = 3;

    private final ClientRepository repo;
    private final AccountRepository accountRepository;
    private final ClientMapper mapper;
    @Value("${app.clients.search.max-results:20}")
    private int searchMaxResults;

    public Mono<ClientResponse> create(CreateClientRequest req) {
        Client client = mapper.toEntity(req);
        return repo.save(client)
                .onErrorMap(this::isPhoneUniqueViolation,
                        ex -> new PhoneAlreadyExistsException(req.phone()))
                .flatMap(saved -> {
                    Account account = Account.builder()
                            .clientId(saved.getId())
                            .balance(BigDecimal.ZERO)
                            .build();
                    return accountRepository.save(account)
                            .thenReturn(mapper.toResponse(saved));
                });
    }

    private boolean isPhoneUniqueViolation(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof DuplicateKeyException) {
                return true;
            }
            if (current instanceof R2dbcDataIntegrityViolationException integrity
                    && "23505".equals(integrity.getSqlState())) {
                return true;
            }
            if (current instanceof DataIntegrityViolationException && hasDuplicateKeyMessage(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean hasDuplicateKeyMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("duplicate key")
                || normalized.contains("unique constraint")
                || normalized.contains("client_phone_key");
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

