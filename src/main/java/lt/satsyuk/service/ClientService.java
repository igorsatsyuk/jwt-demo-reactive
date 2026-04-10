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
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;

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
        return repo.existsByPhone(req.phone())
                .flatMap(exists -> {
                    if (Boolean.TRUE.equals(exists)) {
                        return Mono.error(new PhoneAlreadyExistsException(req.phone()));
                    }

                    Client client = mapper.toEntity(req);
                    return repo.save(client)
                            .onErrorMap(DataIntegrityViolationException.class,
                                    ex -> new PhoneAlreadyExistsException(req.phone()))
                            .flatMap(saved -> {
                                Account account = Account.builder()
                                        .clientId(saved.getId())
                                        .balance(BigDecimal.ZERO)
                                        .build();
                                return accountRepository.save(account)
                                        .map(acc -> mapper.toResponse(saved));
                            });
                });
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

        return repo.findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCaseOrderByIdAsc(
                        normalizedQuery,
                        normalizedQuery
                )
                .map(mapper::toResponse)
                .take(searchMaxResults)
                .collectList();
    }
}

